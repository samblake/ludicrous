package com.github.samblake.ludicrous;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeVariableName;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Optional;

import static java.lang.Character.toUpperCase;

/**
 * A parameter of the constructor or method a builder calls, with every name generated for it.
 */
final class Argument {

    final VariableElement parameter;
    final String name;
    final String capitalised;
    final TypeMirror type;
    final String setter;
    final TypeVariableName state;
    final ClassName missing;
    final ClassName present;

    /** The member access that reads it back from an instance for toBuilder, such as {@code getName()}. */
    final Optional<String> reader;

    /** Its {@code @param} description from the Javadoc. */
    final Optional<String> doc;

    Argument(VariableElement parameter, ClassName builder, String prefix, Optional<String> reader, Optional<String> doc) {
        this.parameter = parameter;
        this.name = parameter.getSimpleName().toString();
        this.capitalised = capitalise(name);
        this.type = parameter.asType();
        this.setter = prefix.isEmpty() ? name : prefix + capitalised;
        this.state = TypeVariableName.get(capitalised + "State");
        this.missing = builder.nestedClass(capitalised + "Missing");
        this.present = builder.nestedClass(capitalised + "Present");
        this.reader = reader;
        this.doc = doc;
    }

    static String capitalise(String name) {
        return toUpperCase(name.charAt(0)) + name.substring(1);
    }

}
