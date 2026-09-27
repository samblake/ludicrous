package com.github.samblake.ludicrous;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
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

    BuilderGenerator(Blueprint blueprint) {
        this.blueprint = blueprint;
        this.builder = blueprint.builder;
        this.arguments = blueprint.arguments;
        this.complete = ParameterSpec.builder(builderOf(map(arguments, argument -> argument.present)), "builder")
                .build();
    }

    TypeSpec builder() {
        TypeSpec.Builder builderType = TypeSpec.classBuilder(builder)
                .addOriginatingElement(blueprint.owner)
                .addModifiers(visibility())
                .addModifiers(FINAL)
                .addTypeVariables(map(arguments, argument -> argument.state));

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(PRIVATE);
        for (Argument argument : arguments) {
            builderType.addField(TypeName.get(argument.type), argument.name, PRIVATE, FINAL);
            constructor
                    .addParameter(TypeName.get(argument.type), argument.name)
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
                .addModifiers(ABSTRACT)
                // Package private so only classes alongside the annotated one can extend it
                .addMethod(MethodSpec.constructorBuilder().build())
                .addMethod(MethodSpec.methodBuilder("from")
                        .addModifiers(PUBLIC, STATIC)
                        .returns(product)
                        .addParameter(complete)
                        .addExceptions(map(blueprint.creator.getThrownTypes(), TypeName::get))
                        .addStatement("return $T.build($N)", builder, complete)
                        .build());

        if (blueprint.settings.toBuilder()) {
            // The annotated class is required to extend this one, and the package private constructor keeps
            // other subclasses to its own package
            parentType.addMethod(MethodSpec.methodBuilder("toBuilder")
                    .addModifiers(PUBLIC)
                    .returns(complete.type)
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
        argument.doc.ifPresent(doc -> setter.addJavadoc("Sets {@code $L}.\n\n@param $L $L\n",
                argument.name, argument.name, doc));
        return setter.build();
    }

    private MethodSpec fromMethod() {
        ParameterSpec source = ParameterSpec.builder(ClassName.get(blueprint.product), "source").build();
        return MethodSpec.methodBuilder("from")
                .addModifiers(PUBLIC, STATIC)
                .returns(complete.type)
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
     * type annotations such as a type use {@code @Nullable}.
     */
    private static ParameterSpec setterParameterOf(Argument argument) {
        // An annotation that targets both parameters and types is reported in both places, so only one is kept
        List<AnnotationSpec> typeAnnotations = argument.type.getAnnotationMirrors().stream()
                .filter(annotation -> !targets(annotation, ElementType.PARAMETER))
                .map(AnnotationSpec::get)
                .collect(toList());
        ParameterSpec.Builder setterParameter =
                ParameterSpec.builder(TypeName.get(argument.type).annotated(typeAnnotations), argument.name);
        argument.parameter.getAnnotationMirrors().stream()
                .filter(annotation -> targets(annotation, ElementType.PARAMETER))
                .map(AnnotationSpec::get)
                .forEach(setterParameter::addAnnotation);
        return setterParameter.build();
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
