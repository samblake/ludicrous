package com.github.samblake.ludicrous;

import com.github.samblake.ludicrous.Blueprints.Plan;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Generated;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.ModuleElement.RequiresDirective;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Generates builders for each class, constructor or static method annotated with {@link Ludicrous}.
 *
 * <p>{@code <Name>Builder} has one type parameter per argument, each starting as the nested
 * {@code <Argument>Missing}. Every {@code with<Argument>} call returns a new builder with that argument's type
 * parameter set to the nested {@code <Argument>Present}, so the builder's type records which arguments have been
 * supplied, and a compile error names the arguments that are missing. Its static {@code build} only accepts a
 * builder whose type parameters are all {@code <Argument>Present}, so it fails to compile until every argument
 * has been set.
 *
 * <p>With {@link Ludicrous#parent()}, {@code <Name>Builders} is also generated as a superclass the annotated class
 * must extend. Its static {@code from} delegates to {@code build}, so {@code <Name>.from(builder)} can be used.
 *
 * <p>With {@link Ludicrous#toBuilder()}, {@code <Name>Builder} also gets a static {@code from} that starts a builder
 * from an existing instance with every argument set, and {@code <Name>Builders} gets {@code toBuilder()}.
 *
 * <pre>{@code
 * OrderView view = OrderViewBuilder.build(OrderViewBuilder.builder()
 *         .withProducts(products)
 *         .withAddress(address)
 *         .withTotal(total));
 * }</pre>
 *
 * <p>{@link Blueprints} works out what to generate and why it can't be, and {@link BuilderGenerator} generates it.
 */
@SupportedAnnotationTypes("com.github.samblake.ludicrous.Ludicrous")
public class LudicrousProcessor extends AbstractProcessor {

    /** Every builder generated so far, to report two builders sharing a name. */
    private final Map<ClassName, Blueprint> generated = new HashMap<>();

    private Blueprints blueprints;

    private boolean reportedSourceVersion;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        blueprints = new Blueprints(processingEnv);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Ludicrous.class);
        if (elements.isEmpty()) {
            return true;
        }
        // Earlier releases have no records or sealed classes, so the generated code would fail somewhere less obvious
        if (processingEnv.getSourceVersion().compareTo(SourceVersion.RELEASE_17) < 0) {
            if (!reportedSourceVersion) {
                processingEnv.getMessager().printMessage(ERROR, "Ludicrous needs Java 17 or later, but this is "
                        + "compiling for " + processingEnv.getSourceVersion());
                reportedSourceVersion = true;
            }
            return true;
        }
        for (Element element : elements) {
            Plan plan = blueprints.plan(element);
            // Reported as one error, as JDK 8 only shows the first error at each position
            if (!plan.problems.isEmpty()) {
                error(element, String.join("\n", plan.problems));
            }
            plan.blueprint
                    .filter(blueprint -> plan.problems.isEmpty())
                    .filter(this::isUniquelyNamed)
                    .ifPresent(this::generate);
        }
        return true;
    }

    private boolean isUniquelyNamed(Blueprint blueprint) {
        Blueprint existing = generated.putIfAbsent(blueprint.builder, blueprint);
        if (existing == null) {
            return true;
        }
        error(blueprint.annotated, blueprint.builder.simpleName() + " is already generated for "
                + existing.description() + ", use @Ludicrous(name = ...) to give this builder a different name");
        return false;
    }

    private void generate(Blueprint blueprint) {
        BuilderGenerator generator = new BuilderGenerator(blueprint, generatedAnnotationFor(blueprint));
        write(blueprint, generator.builder());
        if (blueprint.settings.parent()) {
            write(blueprint, generator.parent());
        }
    }

    /**
     * {@code @Generated}, if the generated code can use it, as a named module can only see it if it requires
     * {@code java.compiler}.
     */
    private Optional<AnnotationSpec> generatedAnnotationFor(Blueprint blueprint) {
        ModuleElement module = processingEnv.getElementUtils().getModuleOf(blueprint.owner);
        if (!module.isUnnamed() && !reads(module, "java.compiler")) {
            return Optional.empty();
        }
        return Optional.of(AnnotationSpec.builder(Generated.class)
                .addMember("value", "$S", LudicrousProcessor.class.getName())
                .build());
    }

    /**
     * Whether the module reads the named one, by requiring it or a module that requires it transitively, as
     * {@code java.se} does {@code java.compiler}.
     */
    private static boolean reads(ModuleElement module, String name) {
        Set<ModuleElement> visited = new HashSet<>();
        return ElementFilter.requiresIn(module.getDirectives()).stream()
                .map(RequiresDirective::getDependency)
                .anyMatch(dependency -> impliesReadabilityOf(dependency, name, visited));
    }

    private static boolean impliesReadabilityOf(ModuleElement module, String name, Set<ModuleElement> visited) {
        if (module.getQualifiedName().contentEquals(name)) {
            return true;
        }
        if (!visited.add(module)) {
            return false;
        }
        return ElementFilter.requiresIn(module.getDirectives()).stream()
                .filter(RequiresDirective::isTransitive)
                .anyMatch(directive -> impliesReadabilityOf(directive.getDependency(), name, visited));
    }

    private void write(Blueprint blueprint, TypeSpec generated) {
        try {
            JavaFile.builder(blueprint.builder.packageName(), generated)
                    .skipJavaLangImports(true)
                    .indent("    ")
                    .build()
                    .writeTo(processingEnv.getFiler());
        }
        catch (IOException e) {
            error(blueprint.annotated, "Could not write " + generated.name() + ": " + e.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(ERROR, message, element);
    }

}
