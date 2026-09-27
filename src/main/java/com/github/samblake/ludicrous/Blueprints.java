package com.github.samblake.ludicrous;

import com.palantir.javapoet.ClassName;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.samblake.ludicrous.Argument.capitalise;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.NON_SEALED;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.SEALED;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.util.ElementFilter.constructorsIn;

/**
 * Works out the {@link Blueprint} for an element annotated with {@link Ludicrous}, and every reason a builder
 * can't be generated from it.
 */
final class Blueprints {

    private static final String PARENT_ON_RECORD =
            "@Ludicrous(parent = true) cannot be used on a record, as a record can't extend a class";

    private static final List<String> BUILDER_METHODS = Arrays.asList("builder", "build", "from");

    private static final List<String> OBJECT_METHODS = Arrays.asList(
            "clone", "equals", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");

    // Only horizontal space may follow the name, so a tag with no description can't run on into the next line
    private static final Pattern PARAM_TAG =
            Pattern.compile("^\\s*@param[ \\t]+(\\S+)[ \\t]*(.*?)(?=^\\s*@|\\z)", MULTILINE | DOTALL);

    /**
     * The blueprint for an annotated element, if one could be worked out, and the problems that stop a builder
     * being generated. With neither, the element is left to another annotated element to report on.
     */
    static final class Plan {
        final Optional<Blueprint> blueprint;
        final List<String> problems;

        private Plan(Optional<Blueprint> blueprint, List<String> problems) {
            this.blueprint = blueprint;
            this.problems = problems;
        }

        private static Plan of(Blueprint blueprint, List<String> problems) {
            return new Plan(Optional.of(blueprint), problems);
        }

        private static Plan failed(String problem) {
            return new Plan(Optional.empty(), Collections.singletonList(problem));
        }

        private static Plan skipped() {
            return new Plan(Optional.empty(), Collections.emptyList());
        }
    }

    private final Elements elements;
    private final Types types;

    Blueprints(ProcessingEnvironment processingEnv) {
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
    }

    Plan plan(Element element) {
        switch (element.getKind()) {
            case CLASS:
                return planClass((TypeElement) element);
            case RECORD:
                return planRecord((TypeElement) element);
            case CONSTRUCTOR:
                return planConstructor((ExecutableElement) element);
            case METHOD:
                return planMethod((ExecutableElement) element);
            default:
                return Plan.failed("@Ludicrous can only be used on a class, a record, a constructor or a static method");
        }
    }

    private Plan planClass(TypeElement type) {
        // The annotated constructor reports this, so reporting it here too would only repeat it
        if (constructorsIn(type.getEnclosedElements()).stream().anyMatch(Blueprints::isAnnotated)) {
            return Plan.skipped();
        }
        Optional<String> problem = problemWith(type);
        if (problem.isPresent()) {
            return Plan.failed(problem.get());
        }
        List<ExecutableElement> constructors = constructorsIn(type.getEnclosedElements()).stream()
                .filter(constructor -> !constructor.getModifiers().contains(PRIVATE))
                .collect(toList());
        if (constructors.size() != 1) {
            return Plan.failed("@Ludicrous on a class needs exactly one non private constructor, "
                    + "put @Ludicrous on the constructor to use instead");
        }
        return plan(type, constructors.get(0), type);
    }

    private Plan planRecord(TypeElement record) {
        // The annotated constructor reports this, so reporting it here too would only repeat it
        if (constructorsIn(record.getEnclosedElements()).stream().anyMatch(Blueprints::isAnnotated)) {
            return Plan.skipped();
        }
        Optional<String> problem = problemWith(record);
        if (problem.isPresent()) {
            return Plan.failed(problem.get());
        }
        if (record.getAnnotation(Ludicrous.class).parent()) {
            return Plan.failed(PARENT_ON_RECORD);
        }
        return plan(record, canonicalConstructorOf(record), record);
    }

    /**
     * The record's canonical constructor, the one taking each component in order, which every record has.
     */
    private ExecutableElement canonicalConstructorOf(TypeElement record) {
        List<? extends RecordComponentElement> components = record.getRecordComponents();
        return constructorsIn(record.getEnclosedElements()).stream()
                .filter(constructor -> constructor.getParameters().size() == components.size())
                .filter(constructor -> {
                    for (int i = 0; i < components.size(); i++) {
                        if (!types.isSameType(constructor.getParameters().get(i).asType(),
                                components.get(i).asType())) {
                            return false;
                        }
                    }
                    return true;
                })
                .findFirst()
                .orElseThrow();
    }

    private Plan planConstructor(ExecutableElement constructor) {
        TypeElement type = (TypeElement) constructor.getEnclosingElement();
        if (isAnnotated(type)) {
            return Plan.failed("@Ludicrous cannot be used on both " + type.getSimpleName()
                    + " and one of its constructors");
        }
        if (constructor.getModifiers().contains(PRIVATE)) {
            return Plan.failed("@Ludicrous cannot be used on a private constructor");
        }
        Optional<String> problem = problemWith(type);
        if (problem.isPresent()) {
            return Plan.failed(problem.get());
        }
        if (constructor.getAnnotation(Ludicrous.class).parent() && type.getKind() == ElementKind.RECORD) {
            return Plan.failed(PARENT_ON_RECORD);
        }
        if (constructor.getAnnotation(Ludicrous.class).parent()) {
            long parents = constructorsIn(type.getEnclosedElements()).stream()
                    .filter(other -> isAnnotated(other) && other.getAnnotation(Ludicrous.class).parent())
                    .count();
            if (parents > 1) {
                return Plan.failed("Only one constructor of " + type.getSimpleName()
                        + " can use @Ludicrous(parent = true), as it can only extend one generated class");
            }
        }
        return plan(constructor, constructor, type);
    }

    private Plan planMethod(ExecutableElement method) {
        if (!method.getModifiers().contains(STATIC) || method.getModifiers().contains(PRIVATE)) {
            return Plan.failed("@Ludicrous can only be used on a non private static method");
        }
        TypeMirror returned = method.getReturnType();
        // Checking the class as well as the type arguments also rejects a raw type such as List
        if (returned.getKind() != TypeKind.DECLARED || !((DeclaredType) returned).getTypeArguments().isEmpty()
                || !((TypeElement) ((DeclaredType) returned).asElement()).getTypeParameters().isEmpty()) {
            return Plan.failed("@Ludicrous needs a method that returns a non generic class");
        }
        if (method.getAnnotation(Ludicrous.class).parent()) {
            return Plan.failed("@Ludicrous(parent = true) can only be used on a class or constructor");
        }
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        TypeElement product = (TypeElement) ((DeclaredType) returned).asElement();
        if (!isTypeAccessibleFrom(owner, packageOf(owner))) {
            return Plan.failed("@Ludicrous cannot be used on a method in a private class, "
                    + "as the builder couldn't call it");
        }
        if (!isTypeAccessibleFrom(product, packageOf(owner))) {
            return Plan.failed("@Ludicrous cannot be used on a method returning " + product.getSimpleName()
                    + ", as the builder can't refer to it");
        }
        return plan(method, method, product);
    }

    /**
     * Why a builder can't be generated for a class's constructor, if there's a reason.
     */
    private static Optional<String> problemWith(TypeElement type) {
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            return Optional.of("@Ludicrous can only be used on a top level class");
        }
        if (type.getModifiers().contains(ABSTRACT)) {
            return Optional.of("@Ludicrous cannot be used on an abstract class");
        }
        if (!type.getTypeParameters().isEmpty()) {
            return Optional.of("@Ludicrous does not support generic classes");
        }
        return Optional.empty();
    }

    private Plan plan(Element annotated, ExecutableElement creator, TypeElement product) {
        Ludicrous settings = annotated.getAnnotation(Ludicrous.class);
        String name = settings.name().isEmpty() ? product.getSimpleName() + "Builder" : settings.name();
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return Plan.failed("@Ludicrous(name = \"" + name + "\") is not a valid class name");
        }
        // A class in the package takes precedence over java.lang, so this would break every use of the real one there
        if (elements.getTypeElement("java.lang." + name) != null) {
            return Plan.failed("The builder would be named " + name + ", which hides java.lang." + name
                    + ", use @Ludicrous(name = ...) to choose another name");
        }
        ClassName builder = ClassName.get(packageOf(creator.getEnclosingElement()), name);

        Map<String, String> docs = parameterDocsOf(creator);
        List<Argument> arguments = new ArrayList<>();
        for (VariableElement parameter : creator.getParameters()) {
            Optional<String> reader = settings.toBuilder()
                    ? readerOf(product, parameter, builder.packageName())
                    : Optional.empty();
            Optional<String> doc = Optional.ofNullable(docs.get(parameter.getSimpleName().toString()));
            arguments.add(new Argument(parameter, builder, settings.prefix(), reader, doc));
        }

        Blueprint blueprint = new Blueprint(annotated, creator, product, builder, arguments);
        return Plan.of(blueprint, problemsWith(blueprint));
    }

    private List<String> problemsWith(Blueprint blueprint) {
        List<String> problems = new ArrayList<>();
        Ludicrous settings = blueprint.settings;
        if (blueprint.arguments.isEmpty()) {
            problems.add("@Ludicrous needs at least one parameter");
        }
        if (!blueprint.creator.getTypeParameters().isEmpty()) {
            problems.add("@Ludicrous does not support generic constructors or methods");
        }

        Map<String, Argument> byGeneratedName = new HashMap<>();
        for (Argument argument : blueprint.arguments) {
            Argument other = byGeneratedName.putIfAbsent(argument.capitalised, argument);
            if (other != null) {
                problems.add("@Ludicrous cannot tell the parameters " + other.name + " and " + argument.name
                        + " apart, as both give " + argument.capitalised + " in generated names, rename one of them");
            }
        }

        for (Argument argument : blueprint.arguments) {
            String gives = "@Ludicrous(prefix = \"" + settings.prefix() + "\") gives " + argument.setter;
            if (!SourceVersion.isName(argument.setter)) {
                problems.add(gives + ", which is not a valid method name");
            }
            else if (BUILDER_METHODS.contains(argument.setter)) {
                problems.add(gives + ", which clashes with the builder's own " + argument.setter + " method");
            }
            else if (OBJECT_METHODS.contains(argument.setter)) {
                problems.add(gives + ", which clashes with Object's " + argument.setter + " method");
            }
        }

        if (settings.parent()) {
            String baseName = blueprint.product.getSimpleName() + "Builders";
            String superclass = blueprint.product.getSuperclass().toString();
            if (!superclass.equals(baseName) && !superclass.equals(packageOf(blueprint.product) + "." + baseName)) {
                problems.add(blueprint.product.getSimpleName() + " must extend the generated " + baseName);
            }
            Set<Modifier> modifiers = blueprint.product.getModifiers();
            if (!modifiers.contains(FINAL) && !modifiers.contains(SEALED) && !modifiers.contains(NON_SEALED)) {
                problems.add(blueprint.product.getSimpleName() + " must be final, sealed or non-sealed, as it extends "
                        + "the generated " + baseName + ", which is sealed");
            }
        }

        if (settings.toBuilder()) {
            for (Argument argument : blueprint.arguments) {
                if (!argument.reader.isPresent()) {
                    problems.add("@Ludicrous(toBuilder = true) cannot read " + argument.name + ", add a non private "
                            + argument.name + "() or get" + argument.capitalised + "() method, or a field named "
                            + argument.name);
                }
            }
        }
        return problems;
    }

    /**
     * Finds how the generated builder can read an argument back from an instance, as the member access to put
     * after {@code instance.}, in order of preference: {@code name()}, {@code getName()}, {@code isName()}
     * for a {@code boolean}, then a {@code name} field.
     */
    private Optional<String> readerOf(TypeElement product, VariableElement parameter, String fromPackage) {
        String name = parameter.getSimpleName().toString();
        List<String> methods = new ArrayList<>();
        methods.add(name);
        methods.add("get" + capitalise(name));
        if (parameter.asType().getKind() == TypeKind.BOOLEAN) {
            methods.add("is" + capitalise(name));
        }

        DeclaredType productType = (DeclaredType) product.asType();
        List<? extends Element> members = elements.getAllMembers(product);
        for (String method : methods) {
            for (ExecutableElement member : ElementFilter.methodsIn(members)) {
                // A method declaring exceptions is skipped, as the generated from has nowhere to report them
                if (member.getSimpleName().contentEquals(method) && member.getParameters().isEmpty()
                        && member.getThrownTypes().isEmpty() && isReadableFrom(member, fromPackage)
                        && isSafelyAssignable(returnTypeOf(productType, member), parameter.asType())) {
                    return Optional.of(method + "()");
                }
            }
        }
        for (VariableElement member : ElementFilter.fieldsIn(members)) {
            // The type as seen on the product, so a T field inherited from Base<String> is a String
            if (member.getSimpleName().contentEquals(name) && isReadableFrom(member, fromPackage)
                    && isSafelyAssignable(types.asMemberOf(productType, member), parameter.asType())) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /**
     * The method's return type as seen on the product, so a {@code T getName()} inherited from {@code Base<String>}
     * returns {@code String}.
     */
    private TypeMirror returnTypeOf(DeclaredType productType, ExecutableElement method) {
        return ((ExecutableType) types.asMemberOf(productType, method)).getReturnType();
    }

    /**
     * Whether the value can be passed as the argument without unboxing, which would throw for a null.
     */
    private boolean isSafelyAssignable(TypeMirror from, TypeMirror to) {
        if (to.getKind().isPrimitive() && !from.getKind().isPrimitive()) {
            return false;
        }
        return types.isAssignable(from, to);
    }

    private boolean isReadableFrom(Element member, String fromPackage) {
        return !member.getModifiers().contains(STATIC) && isAccessibleFrom(member, fromPackage);
    }

    /**
     * Whether code in the package can refer to the type, which also needs every class it's nested in to be
     * accessible.
     */
    private boolean isTypeAccessibleFrom(TypeElement type, String fromPackage) {
        for (Element element = type; element instanceof TypeElement; element = element.getEnclosingElement()) {
            if (!isAccessibleFrom(element, fromPackage)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether code in the package can use the element itself, not counting any class it's nested in.
     */
    private boolean isAccessibleFrom(Element element, String fromPackage) {
        Set<Modifier> modifiers = element.getModifiers();
        return !modifiers.contains(PRIVATE) && (modifiers.contains(PUBLIC) || packageOf(element).equals(fromPackage));
    }

    /**
     * Reads each {@code @param} description from the creator's Javadoc, which is only available when it is
     * compiled from source.
     */
    private Map<String, String> parameterDocsOf(ExecutableElement creator) {
        String doc = elements.getDocComment(creator);
        // A record's canonical constructor is usually implicit, with its components documented on the record
        Element owner = creator.getEnclosingElement();
        if (doc == null && owner.getKind() == ElementKind.RECORD
                && creator.equals(canonicalConstructorOf((TypeElement) owner))) {
            doc = elements.getDocComment(owner);
        }
        if (doc == null) {
            return Collections.emptyMap();
        }
        Map<String, String> docs = new HashMap<>();
        Matcher matcher = PARAM_TAG.matcher(doc);
        while (matcher.find()) {
            String description = matcher.group(2).replaceAll("\\s+", " ").trim();
            if (!description.isEmpty()) {
                docs.put(matcher.group(1), description);
            }
        }
        return docs;
    }

    private String packageOf(Element element) {
        return elements.getPackageOf(element).getQualifiedName().toString();
    }

    private static boolean isAnnotated(Element element) {
        return element.getAnnotation(Ludicrous.class) != null;
    }

}
