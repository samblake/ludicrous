package com.github.samblake.ludicrous;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.SEALED;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Generates the builder for a {@link Blueprint}, and its parent class with {@link Ludicrous#parent()}.
 */
final class BuilderGenerator {

    private final Blueprint blueprint;
    private final ClassName builder;
    private final List<Argument> arguments;

    /** The {@code build} parameter, a builder with every argument present. */
    private final ParameterSpec complete;

    /** The {@code @Generated} annotation for the builder and parent, when the generated code can use it. */
    private final Optional<AnnotationSpec> generated;

    BuilderGenerator(Blueprint blueprint, Optional<AnnotationSpec> generated) {
        this.blueprint = blueprint;
        this.builder = blueprint.builder;
        this.arguments = blueprint.arguments;
        this.generated = generated;
        this.complete = ParameterSpec.builder(builderOf(map(arguments, argument -> argument.present)), "builder")
                .build();
    }

    TypeSpec builder() {
        TypeSpec.Builder builderType = TypeSpec.classBuilder(builder)
                .addOriginatingElement(blueprint.owner)
                .addModifiers(visibility())
                .addModifiers(FINAL)
                .addTypeVariables(map(arguments, argument -> argument.state));
        generated.ifPresent(builderType::addAnnotation);

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(PRIVATE);
        for (Argument argument : arguments) {
            builderType.addField(typeNameOf(argument.type), argument.name, PRIVATE, FINAL);
            constructor
                    .addParameter(typeNameOf(argument.type), argument.name)
                    .addStatement("this.$N = $N", argument.name, argument.name);
        }
        builderType.addMethod(constructor.build());

        for (Argument argument : arguments) {
            builderType.addType(stateType(argument.missing));
            builderType.addType(stateType(argument.present));
        }

        builderType.addMethod(MethodSpec.methodBuilder("builder")
                .addModifiers(PUBLIC, STATIC)
                .returns(builderOf(map(arguments, argument -> argument.missing)))
                .addStatement("return new $T<>($L)", builder, join(map(arguments, BuilderGenerator::defaultOf)))
                .build());

        for (int i = 0; i < arguments.size(); i++) {
            builderType.addMethod(setter(i));
        }

        builderType.addMethod(MethodSpec.methodBuilder("build")
                .addModifiers(PUBLIC, STATIC)
                .returns(ClassName.get(blueprint.product))
                .addParameter(complete)
                .addExceptions(map(blueprint.creator.getThrownTypes(), TypeName::get))
                .addStatement("return $L", creation(join(
                        map(arguments, argument -> CodeBlock.of("$N.$N", complete, argument.name)))))
                .build());

        if (blueprint.settings.toBuilder()) {
            builderType.addMethod(fromMethod());
        }
        return builderType.build();
    }

    TypeSpec parent() {
        ClassName product = ClassName.get(blueprint.product);
        TypeSpec.Builder parentType = TypeSpec.classBuilder(product.peerClass(product.simpleName() + "Builders"))
                .addOriginatingElement(blueprint.owner)
                .addModifiers(visibility())
                .addModifiers(ABSTRACT, SEALED)
                .addPermittedSubclass(product)
                .addMethod(MethodSpec.methodBuilder("from")
                        .addModifiers(PUBLIC, STATIC)
                        .returns(product)
                        .addParameter(complete)
                        .addExceptions(map(blueprint.creator.getThrownTypes(), TypeName::get))
                        .addStatement("return $T.build($N)", builder, complete)
                        .build());
        generated.ifPresent(parentType::addAnnotation);

        if (blueprint.settings.toBuilder()) {
            // Safe, as this class is sealed with the annotated class as its only permitted subclass
            parentType.addMethod(MethodSpec.methodBuilder("toBuilder")
                    .addModifiers(PUBLIC)
                    .returns(complete.type())
                    .addStatement("return $T.from(($T) this)", builder, product)
                    .build());
        }
        return parentType.build();
    }

    /**
     * The setter for the argument at the index, which returns a builder with only that argument's state changed.
     */
    private MethodSpec setter(int index) {
        Argument argument = arguments.get(index);
        List<TypeName> states = new ArrayList<>(map(arguments, other -> other.state));
        states.set(index, argument.present);

        MethodSpec.Builder setter = MethodSpec.methodBuilder(argument.setter)
                .addModifiers(PUBLIC)
                .returns(builderOf(states))
                .addParameter(setterParameterOf(argument))
                .varargs(blueprint.creator.isVarArgs() && index == arguments.size() - 1)
                .addStatement("return new $T<>($L)", builder,
                        join(map(arguments, other -> CodeBlock.of("$N", other.name))));
        String summary = blueprint.markdownDocs ? "Sets `$L`.\n\n@param $L $L\n" : "Sets {@code $L}.\n\n@param $L $L\n";
        argument.doc.ifPresent(doc -> setter.addJavadoc(summary, argument.name, argument.name, doc));
        return setter.build();
    }

    private MethodSpec fromMethod() {
        ParameterSpec source = ParameterSpec.builder(ClassName.get(blueprint.product), "source").build();
        return MethodSpec.methodBuilder("from")
                .addModifiers(PUBLIC, STATIC)
                .returns(complete.type())
                .addParameter(source)
                .addStatement("return new $T<>($L)", builder,
                        join(map(arguments, argument -> CodeBlock.of("$N.$L", source, argument.reader.get()))))
                .build();
    }

    private TypeSpec stateType(ClassName state) {
        return TypeSpec.classBuilder(state)
                .addModifiers(visibility())
                .addModifiers(STATIC, FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(PRIVATE).build())
                .build();
    }

    /**
     * Creates the product with the constructor or static method, as {@code new Person(...)} or {@code Money.of(...)}.
     */
    private CodeBlock creation(CodeBlock arguments) {
        return blueprint.isConstructor()
                ? CodeBlock.of("new $T($L)", ClassName.get(blueprint.product), arguments)
                : CodeBlock.of("$T.$N($L)", ClassName.get(blueprint.owner),
                        blueprint.creator.getSimpleName().toString(), arguments);
    }

    /**
     * Public only when the constructor or method it calls can be called from anywhere.
     */
    private Modifier[] visibility() {
        boolean visible = blueprint.owner.getModifiers().contains(PUBLIC)
                && blueprint.creator.getModifiers().contains(PUBLIC);
        return visible ? new Modifier[] {PUBLIC} : new Modifier[0];
    }

    private TypeName builderOf(List<? extends TypeName> states) {
        return ParameterizedTypeName.get(builder, states.toArray(new TypeName[0]));
    }

    /**
     * The setter's parameter, keeping the original's declaration annotations that can go on a parameter, and its
     * type annotations such as a type use {@code @Nullable}, including those inside type arguments.
     */
    private static ParameterSpec setterParameterOf(Argument argument) {
        // An annotation that targets both parameters and types is reported in both places at the top level of the
        // type, so only the declaration one is kept
        List<AnnotationSpec> typeAnnotations = argument.type.getAnnotationMirrors().stream()
                .filter(annotation -> !targets(annotation, ElementType.PARAMETER))
                .map(AnnotationSpec::get)
                .collect(toList());
        TypeName type = typeNameOf(argument.type).withoutAnnotations().annotated(typeAnnotations);
        ParameterSpec.Builder setterParameter = ParameterSpec.builder(type, argument.name);
        argument.parameter.getAnnotationMirrors().stream()
                .filter(annotation -> targets(annotation, ElementType.PARAMETER))
                .map(AnnotationSpec::get)
                .forEach(setterParameter::addAnnotation);
        return setterParameter.build();
    }

    /**
     * The type with its type annotations at every level, such as the {@code @Nullable} in
     * {@code List<@Nullable String>}, which {@link TypeName#get(TypeMirror)} drops.
     */
    private static TypeName typeNameOf(TypeMirror type) {
        TypeName name;
        switch (type.getKind()) {
            case DECLARED:
                name = declaredTypeNameOf((DeclaredType) type);
                break;
            case ARRAY:
                name = ArrayTypeName.of(typeNameOf(((ArrayType) type).getComponentType()));
                break;
            case WILDCARD:
                name = wildcardTypeNameOf((WildcardType) type);
                break;
            default:
                name = TypeName.get(type);
        }
        List<AnnotationSpec> annotations = map(type.getAnnotationMirrors(), AnnotationSpec::get);
        return annotations.isEmpty() ? name : name.annotated(annotations);
    }

    private static TypeName declaredTypeNameOf(DeclaredType type) {
        TypeElement element = (TypeElement) type.asElement();
        List<TypeName> typeArguments = map(type.getTypeArguments(), BuilderGenerator::typeNameOf);

        // An inner class of a generic class, such as Outer<String>.Inner, is written with the outer type arguments
        TypeMirror enclosing = type.getEnclosingType();
        if (enclosing.getKind() == TypeKind.DECLARED && !element.getModifiers().contains(STATIC)) {
            TypeName outer = typeNameOf(enclosing);
            if (outer instanceof ParameterizedTypeName) {
                return ((ParameterizedTypeName) outer).nestedClass(element.getSimpleName().toString(), typeArguments);
            }
        }
        ClassName raw = ClassName.get(element);
        return typeArguments.isEmpty()
                ? raw
                : ParameterizedTypeName.get(raw, typeArguments.toArray(new TypeName[0]));
    }

    private static TypeName wildcardTypeNameOf(WildcardType type) {
        if (type.getExtendsBound() != null) {
            return WildcardTypeName.subtypeOf(typeNameOf(type.getExtendsBound()));
        }
        if (type.getSuperBound() != null) {
            return WildcardTypeName.supertypeOf(typeNameOf(type.getSuperBound()));
        }
        return WildcardTypeName.subtypeOf(Object.class);
    }

    private static boolean targets(AnnotationMirror annotation, ElementType elementType) {
        Target target = annotation.getAnnotationType().asElement().getAnnotation(Target.class);
        // Without @Target an annotation applies to every declaration, including parameters, but not to type uses
        return target == null
                ? elementType != ElementType.TYPE_USE && elementType != ElementType.TYPE_PARAMETER
                : Arrays.asList(target.value()).contains(elementType);
    }

    private static CodeBlock defaultOf(Argument argument) {
        TypeKind kind = argument.type.getKind();
        if (kind == TypeKind.BOOLEAN) {
            return CodeBlock.of("false");
        }
        if (kind == TypeKind.CHAR) {
            return CodeBlock.of("'\\0'");
        }
        return kind.isPrimitive()
                ? CodeBlock.of("($T) 0", TypeName.get(argument.type))
                : CodeBlock.of("null");
    }

    private static CodeBlock join(List<CodeBlock> codeBlocks) {
        return CodeBlock.join(codeBlocks, ", ");
    }

    private static <T, R> List<R> map(List<? extends T> items, Function<T, R> mapper) {
        return items.stream()
                .map(mapper)
                .collect(toList());
    }

}
