package com.github.samblake.ludicrous;

import com.github.samblake.ludicrous.Blueprints.Plan;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
        for (Element element : roundEnv.getElementsAnnotatedWith(Ludicrous.class)) {
            Plan plan = blueprints.plan(element);
            plan.problems.forEach(problem -> error(element, problem));
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
        BuilderGenerator generator = new BuilderGenerator(blueprint);
        write(blueprint, generator.builder());
        if (blueprint.settings.parent()) {
            write(blueprint, generator.parent());
        }
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
            error(blueprint.annotated, "Could not write " + generated.name + ": " + e.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(ERROR, message, element);
    }

}
