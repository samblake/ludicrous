package com.github.samblake.ludicrous;

import com.squareup.javapoet.ClassName;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.List;

import static java.util.stream.Collectors.joining;

/**
 * What a builder is generated from: the annotated element, the constructor or static method it calls,
 * the class that declares that, the type it produces, and an {@link Argument} per parameter.
 */
final class Blueprint {

    final Element annotated;
    final Ludicrous settings;
    final ExecutableElement creator;
    final TypeElement owner;
    final TypeElement product;
    final ClassName builder;
    final List<Argument> arguments;

    Blueprint(Element annotated, ExecutableElement creator, TypeElement product, ClassName builder,
            List<Argument> arguments) {
        this.annotated = annotated;
        this.settings = annotated.getAnnotation(Ludicrous.class);
        this.creator = creator;
        this.owner = (TypeElement) creator.getEnclosingElement();
        this.product = product;
        this.builder = builder;
        this.arguments = arguments;
    }

    boolean isConstructor() {
        return creator.getKind() == ElementKind.CONSTRUCTOR;
    }

    /**
     * The annotated element as it's named in messages, such as {@code Person} or {@code Person(java.lang.String)}.
     */
    String description() {
        if (!(annotated instanceof ExecutableElement)) {
            return annotated.getSimpleName().toString();
        }
        String name = isConstructor()
                ? owner.getSimpleName().toString()
                : owner.getSimpleName() + "." + creator.getSimpleName();
        return name + arguments.stream()
                .map(argument -> argument.type.toString())
                .collect(joining(", ", "(", ")"));
    }

}
