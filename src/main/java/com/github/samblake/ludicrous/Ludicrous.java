package com.github.samblake.ludicrous;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates {@code <Name>Builder}, which tracks in its type which arguments have been set.
 * Its static {@code build} only accepts a builder with every argument set.
 *
 * <p>On a class, the builder calls its only non private constructor. On a constructor, the builder calls that
 * constructor, so a class with several constructors can choose one, or annotate more than one and give each a
 * different {@link #name()}. On a static method, such as {@code Money.of(amount, currency)}, the builder calls
 * that method and is named after the type it returns.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface Ludicrous {

    /**
     * Also generates {@code <Name>Builders}, which the annotated class must extend, so that
     * {@code <Name>.from(builder)} is available. Only supported on a class or constructor, and only one builder per
     * class can use it.
     */
    boolean parent() default false;

    /**
     * Also generates a static {@code <Name>Builder.from(instance)}, which returns a builder with every argument
     * set from an existing instance. With {@link #parent()}, the instance method {@code toBuilder()} is added too.
     * Each argument is read from a {@code <argument>()} or {@code get<Argument>()} method ({@code is<Argument>()}
     * for a {@code boolean}), or a field named after it, none of which may be private.
     */
    boolean toBuilder() default false;

    /**
     * The name of the generated builder class. Defaults to {@code <Name>Builder}.
     */
    String name() default "";

    /**
     * The prefix of each setter, so {@code "set"} gives {@code setTotal} and {@code ""} gives {@code total}.
     */
    String prefix() default "with";

}
