package com.github.samblake.ludicrous;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class LudicrousProcessorTest {

    private static final String PERSON =
            "package test;\n"
            + "@com.github.samblake.ludicrous.Ludicrous\n"
            + "public class Person {\n"
            + "    public Person(String name, java.util.List<String> nicknames, int age) {}\n"
            + "}\n";

    private static final String PERSON_WITH_PARENT =
            "package test;\n"
            + "@com.github.samblake.ludicrous.Ludicrous(parent = true)\n"
            + "public class Person extends PersonBuilders {\n"
            + "    public Person(String name) {}\n"
            + "}\n";

    @Rule
    public TemporaryFolder output = new TemporaryFolder();

    @Test
    public void compilesWhenEveryArgumentIsSet() {
        Result result = compile(PERSON, usage(".withAge(3).withName(\"a\").withNicknames(null)"));

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWhenAnArgumentIsMissing() {
        Result result = compile(PERSON, usage(".withName(\"a\").withAge(3)"));

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("PersonBuilder<test.PersonBuilder.NamePresent,"
                + "test.PersonBuilder.NicknamesMissing,test.PersonBuilder.AgePresent> cannot be converted"));
    }

    @Test
    public void compilesThroughTheParent() {
        Result result = compile(PERSON_WITH_PARENT,
                "package test;\n"
                + "class Usage {\n"
                + "    Person person = Person.from(PersonBuilder.builder().withName(\"a\"));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void doesNotGenerateTheParentByDefault() {
        Result result = compile(PERSON,
                "package test;\n"
                + "class Usage extends PersonBuilders {}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("class PersonBuilders"));
    }

    @Test
    public void startsABuilderFromAnInstance() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(toBuilder = true)\n"
                + "public class Person {\n"
                + "    final int age;\n"
                + "    private final String name;\n"
                + "    private final java.util.List<String> nicknames;\n"
                + "    public Person(String name, java.util.List<String> nicknames, int age) {\n"
                + "        this.name = name; this.nicknames = nicknames; this.age = age;\n"
                + "    }\n"
                + "    public String name() { return name; }\n"
                + "    public java.util.List<String> getNicknames() { return nicknames; }\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person copy(Person person) { return PersonBuilder.build(PersonBuilder.from(person).withAge(4)); }\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void addsToBuilderToTheParent() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(parent = true, toBuilder = true)\n"
                + "public class Person extends PersonBuilders {\n"
                + "    private final String name;\n"
                + "    public Person(String name) { this.name = name; }\n"
                + "    public String getName() { return name; }\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person rename(Person person) { return Person.from(person.toBuilder().withName(\"b\")); }\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWhenAnArgumentCannotBeRead() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(toBuilder = true)\n"
                + "public class Person {\n"
                + "    private final String name;\n"
                + "    public Person(String name) { this.name = name; }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("cannot read name"));
    }

    @Test
    public void buildsThroughAStaticFactoryMethod() {
        Result result = compile(
                "package test;\n"
                + "public class Money {\n"
                + "    private Money(long amount, String currency) {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public static Money of(long amount, String currency) { return new Money(amount, currency); }\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Money money = MoneyBuilder.build(MoneyBuilder.builder().withCurrency(\"GBP\").withAmount(1));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWithAParentOnAStaticFactoryMethod() {
        Result result = compile(
                "package test;\n"
                + "public class Money {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(parent = true)\n"
                + "    public static Money of(long amount) { return new Money(); }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("@Ludicrous(parent = true) can only be used on a class"));
    }

    @Test
    public void failsOnAnInstanceMethod() {
        Result result = compile(
                "package test;\n"
                + "public class Money {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Money of(long amount) { return this; }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("can only be used on a non private static method"));
    }

    @Test
    public void usesTheConfiguredNameAndPrefix() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(name = \"PersonMaker\", prefix = \"\")\n"
                + "public class Person {\n"
                + "    public Person(String name, int age) {}\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person person = PersonMaker.build(PersonMaker.builder().name(\"a\").age(3));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWhenThePrefixClashesWithABuilderMethod() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(prefix = \"\")\n"
                + "public class Person {\n"
                + "    public Person(String build) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("clashes with the builder's own build method"));
    }

    @Test
    public void keepsVarargs() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    public Person(String name, String... tags) {}\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person person = PersonBuilder.build(PersonBuilder.builder().withName(\"a\").withTags(\"b\", \"c\"));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void keepsParameterAnnotationsAndJavadoc() {
        Result result = compile(
                "package test;\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)\n"
                + "public @interface Checked {}\n",
                "package test;\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\n"
                + "public @interface Nullable {}\n",
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    /**\n"
                + "     * @param name the person's name\n"
                + "     *     as written\n"
                + "     * @param nickname what friends call them\n"
                + "     */\n"
                + "    public Person(@Checked String name, @Nullable String nickname) {}\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        String builder = generated("test/PersonBuilder.java");
        assertThat(builder, containsString("withName(@Checked String name)"));
        assertThat(builder, containsString("withNickname(@Nullable String nickname)"));
        assertThat(builder, containsString("@param name the person's name as written"));
        assertThat(builder, containsString("@param nickname what friends call them"));
    }

    @Test
    public void onlyAllowsTheParentToBeExtendedInTheSamePackage() {
        Result result = compile(PERSON_WITH_PARENT,
                "package other;\n"
                + "public class Impostor extends test.PersonBuilders {}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("PersonBuilders() is not public in test.PersonBuilders"));
    }

    @Test
    public void buildsThroughTheAnnotatedConstructor() {
        Result result = compile(
                "package test;\n"
                + "public class Person {\n"
                + "    public Person(String name) {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Person(String name, int age) {}\n"
                + "    Person(int age) {}\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person person = PersonBuilder.build(PersonBuilder.builder().withName(\"a\").withAge(3));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void buildsThroughSeveralNamedConstructors() {
        Result result = compile(
                "package test;\n"
                + "public class Person {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Person(String name) {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(name = \"AgedPersonBuilder\")\n"
                + "    public Person(String name, int age) {}\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person named = PersonBuilder.build(PersonBuilder.builder().withName(\"a\"));\n"
                + "    Person aged = AgedPersonBuilder.build(AgedPersonBuilder.builder().withName(\"a\").withAge(3));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWhenTwoConstructorsShareABuilderName() {
        Result result = compile(
                "package test;\n"
                + "public class Person {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Person(String name) {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Person(String name, int age) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("PersonBuilder is already generated for Person(java.lang.String), "
                + "use @Ludicrous(name = ...) to give this builder a different name"));
    }

    @Test
    public void failsOnBothTheClassAndAConstructor() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public Person(String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("@Ludicrous cannot be used on both Person and one of its constructors"));
    }

    @Test
    public void failsOnAPrivateConstructor() {
        Result result = compile(
                "package test;\n"
                + "public class Person {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    private Person(String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("@Ludicrous cannot be used on a private constructor"));
    }

    @Test
    public void failsWhenTwoConstructorsWantTheParent() {
        Result result = compile(
                "package test;\n"
                + "public class Person extends PersonBuilders {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(parent = true)\n"
                + "    public Person(String name) {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(parent = true, name = \"AgedPersonBuilder\")\n"
                + "    public Person(String name, int age) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("Only one constructor of Person can use @Ludicrous(parent = true)"));
    }

    @Test
    public void usesTheParentFromAnAnnotatedConstructor() {
        Result result = compile(
                "package test;\n"
                + "public class Person extends PersonBuilders {\n"
                + "    public Person() {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(parent = true)\n"
                + "    public Person(String name) {}\n"
                + "}\n",
                "package test;\n"
                + "class Usage {\n"
                + "    Person person = Person.from(PersonBuilder.builder().withName(\"a\"));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsOnAMethodInAPrivateClass() {
        Result result = compile(
                "package test;\n"
                + "public class Outer {\n"
                + "    private static class Inner {\n"
                + "        @com.github.samblake.ludicrous.Ludicrous\n"
                + "        static Outer make(String name) { return new Outer(); }\n"
                + "    }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("cannot be used on a method in a private class"));
    }

    @Test
    public void failsOnAMethodReturningAPrivateClass() {
        Result result = compile(
                "package test;\n"
                + "public class Outer {\n"
                + "    private static class Secret {}\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    static Secret make(String name) { return new Secret(); }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("cannot be used on a method returning Secret"));
    }

    @Test
    public void failsWhenParametersDifferOnlyInTheirFirstLetter() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    public Person(String a, String A) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("cannot tell the parameters a and A apart"));
    }

    @Test
    public void failsWhenAPrefixClashesWithAnObjectMethod() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(prefix = \"\")\n"
                + "public class Person {\n"
                + "    public Person(long wait) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("gives wait, which clashes with Object's wait method"));
    }

    @Test
    public void skipsAParamTagWithoutADescription() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    /**\n"
                + "     * @param name\n"
                + "     * @param age in years\n"
                + "     */\n"
                + "    public Person(String name, int age) {}\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        String builder = generated("test/PersonBuilder.java");
        assertThat(builder, not(containsString("@param name")));
        assertThat(builder, containsString("@param age in years"));
    }

    @Test
    public void readsAGetterInheritedFromAGenericSuperclass() {
        Result result = compile(
                "package test;\n"
                + "public class Base<T> {\n"
                + "    private final T name;\n"
                + "    Base(T name) { this.name = name; }\n"
                + "    public T getName() { return name; }\n"
                + "}\n",
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(toBuilder = true)\n"
                + "public class Person extends Base<String> {\n"
                + "    public Person(String name) { super(name); }\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        assertThat(generated("test/PersonBuilder.java"), containsString("source.getName()"));
    }

    @Test
    public void skipsAGetterThatThrowsForTheField() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(toBuilder = true)\n"
                + "public class Person {\n"
                + "    final String name;\n"
                + "    public Person(String name) { this.name = name; }\n"
                + "    public String getName() throws java.io.IOException { return name; }\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        assertThat(generated("test/PersonBuilder.java"), containsString("source.name)"));
    }

    @Test
    public void failsWhenTheBuilderWouldHideAJavaLangClass() {
        Result result = compile(
                "package test;\n"
                + "public class Strings {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    public static String repeat(String text, int times) { return text; }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("The builder would be named StringBuilder, "
                + "which hides java.lang.StringBuilder"));
    }

    @Test
    public void keepsTheBuilderPackagePrivateForAPackagePrivateConstructor() {
        Result result = compile(
                "package test;\n"
                + "public class Person {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous\n"
                + "    Person(String name) {}\n"
                + "}\n",
                "package other;\n"
                + "class Usage {\n"
                + "    Object person = test.PersonBuilder.builder();\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("test.PersonBuilder is not public in test"));
    }

    @Test
    public void reportsEveryProblemAtOnce() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(prefix = \"\", toBuilder = true)\n"
                + "public class Person {\n"
                + "    public Person(String build, long wait) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("gives build, which clashes with the builder's own build method"));
        assertThat(result.errors, containsString("gives wait, which clashes with Object's wait method"));
        assertThat(result.errors, containsString("cannot read build"));
        assertThat(result.errors, containsString("cannot read wait"));
    }

    @Test
    public void skipsAGetterThatWouldNeedUnboxing() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(toBuilder = true)\n"
                + "public class Person {\n"
                + "    final int age;\n"
                + "    public Person(int age) { this.age = age; }\n"
                + "    public Integer getAge() { return age; }\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        assertThat(generated("test/PersonBuilder.java"), containsString("source.age)"));
    }

    @Test
    public void failsOnAMethodReturningARawType() {
        Result result = compile(
                "package test;\n"
                + "public class Lists {\n"
                + "    @com.github.samblake.ludicrous.Ludicrous(name = \"ListBuilder\")\n"
                + "    @SuppressWarnings(\"rawtypes\")\n"
                + "    public static java.util.List of(String first) { return null; }\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("needs a method that returns a non generic class"));
    }

    @Test
    public void buildsAClassInTheDefaultPackage() {
        Result result = compile(
                "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    public Person(String name) {}\n"
                + "}\n",
                "class Usage {\n"
                + "    Person person = PersonBuilder.build(PersonBuilder.builder().withName(\"a\"));\n"
                + "}\n");

        assertThat(result.errors, result.success, is(true));
        assertThat(generated("PersonBuilder.java"), containsString("public final class PersonBuilder"));
    }

    @Test
    public void failsWhenTheGeneratedBaseIsNotExtended() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous(parent = true)\n"
                + "public class Person {\n"
                + "    public Person(String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("Person must extend the generated PersonBuilders"));
    }

    @Test
    public void failsWithMoreThanOneConstructor() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    public Person(String name) {}\n"
                + "    public Person(int age) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("needs exactly one non private constructor, "
                + "put @Ludicrous on the constructor to use instead"));
    }

    @Test
    public void failsWithAGenericConstructor() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.ludicrous.Ludicrous\n"
                + "public class Person {\n"
                + "    public <T extends Number> Person(T value, String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("does not support generic constructors"));
    }

    private static String usage(String calls) {
        return "package test;\n"
                + "class Usage {\n"
                + "    Person person = PersonBuilder.build(PersonBuilder.builder()" + calls + ");\n"
                + "}\n";
    }

    private String generated(String path) {
        try {
            return new String(Files.readAllBytes(output.getRoot().toPath().resolve(path)), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Result compile(String... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> files = Arrays.stream(sources).map(Source::new).collect(Collectors.toList());

        JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics,
                Arrays.asList("-d", output.getRoot().getPath(), "-s", output.getRoot().getPath(),
                        "-classpath", System.getProperty("java.class.path")),
                null, files);
        task.setProcessors(singletonList(new LudicrousProcessor()));
        boolean success = task.call();

        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));

        return new Result(success, errors);
    }

    private static final class Result {
        private final boolean success;
        private final String errors;

        private Result(boolean success, String errors) {
            this.success = success;
            this.errors = errors;
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private static final Pattern TYPE_NAME = Pattern.compile("(?:class|interface)\\s+(\\w+)");

        private final String code;

        private Source(String code) {
            super(URI.create("string:///" + className(code).replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        private static String className(String code) {
            Matcher name = TYPE_NAME.matcher(code);
            if (!name.find()) {
                throw new IllegalArgumentException("No type declared in " + code);
            }
            if (!code.startsWith("package ")) {
                return name.group(1);
            }
            return code.substring("package ".length(), code.indexOf(';')) + "." + name.group(1);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
