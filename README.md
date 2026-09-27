# Ludicrous

Builder of Builders

An annotation processor that generates builders that track which properties have been set.
This means we can ensure `build` is only called once all arguments have been set.
Missing values is a compile time error, not runtime.

Zero runtime dependencies. The generated builders only use your own classes and the JDK.

Phantom types are used to track which values have been set. This leads to ludicrous type definitions.

## Usage

Annotate a class with `@Ludicrous`.

```java
@Ludicrous
public class OrderView {

    public OrderView(List<Product> products, Address address, Money total) {
        ...
    }
}
```

Then build it with the generated `<Name>Builder`. The `with` methods can be called in any order:

```java
OrderView view = OrderViewBuilder.build(OrderViewBuilder.builder()
        .withProducts(products)
        .withAddress(address)
        .withTotal(total));
```

Leaving a call out fails to compile:

```
incompatible types: OrderViewBuilder<ProductsMissing,AddressPresent,TotalPresent> cannot be converted to OrderViewBuilder<ProductsPresent,AddressPresent,TotalPresent>
```

The annotated class must be a top level, non generic, non abstract class with exactly one non private constructor.
If it has several constructors, annotate the one to use instead (see below). If it hides its constructor, annotate
a static factory method.

### Records

`@Ludicrous` works on a record too, using its canonical constructor, so any extra constructors don't get in the
way:

```java
@Ludicrous
public record OrderView(List<Product> products, Address address, Money total) {}
```

Every record component has an accessor named after it, so `toBuilder = true` works on a record without adding
anything. A record can't extend a class, so `parent = true` isn't supported on one.

### Building from the class itself

Set `parent = true` to also generate `<Name>Builders`, which the class must extend. This adds a static `from`,
so the class can be built with `OrderView.from(...)` instead of `OrderViewBuilder.build(...)`.

`<Name>Builders` is sealed, with the annotated class as its only permitted subclass. Java requires a class extending
a sealed class to be `final`, `sealed` or `non-sealed`, so the annotated class must be one of those:

```java
@Ludicrous(parent = true)
public final class OrderView extends OrderViewBuilders {
    ...
}

OrderView view = OrderView.from(OrderViewBuilder.builder()
        .withProducts(products)
        .withAddress(address)
        .withTotal(total));
```

### Constructors

For a class with several constructors, put `@Ludicrous` on the constructor the builder should call. The other
constructors are ignored:

```java
public class OrderView {

    public OrderView(List<Product> products) {
        ...
    }

    @Ludicrous
    public OrderView(List<Product> products, Address address, Money total) {
        ...
    }
}
```

More than one constructor can be annotated, as long as each builder has its own `name`:

```java
@Ludicrous
public OrderView(List<Product> products) {
    ...
}

@Ludicrous(name = "DeliveredOrderViewBuilder")
public OrderView(List<Product> products, Address address, Money total) {
    ...
}
```

Two builders with the same name fail to compile, saying which constructor already has it. `@Ludicrous` can't go on both
the class and one of its constructors, and only one annotated constructor per class can use `parent = true`, as the
class can only extend one generated parent.

### Static factory methods

`@Ludicrous` can go on a non private static method instead of a class. The builder calls that method and is named after
the type it returns:

```java
public final class Money {

    private Money(long amount, Currency currency) {
        ...
    }

    @Ludicrous
    public static Money of(long amount, Currency currency) {
        return new Money(amount, currency);
    }
}

Money price = MoneyBuilder.build(MoneyBuilder.builder()
        .withAmount(499)
        .withCurrency(GBP));
```

`parent = true` isn't supported on a method, as the builder doesn't control what the returned type extends.

### Naming

`name` sets the builder class name and `prefix` sets the start of each setter. An empty prefix uses the
argument name on its own:

```java
@Ludicrous(name = "Orders", prefix = "")
public class OrderView {
    ...
}

OrderView view = Orders.build(Orders.builder()
        .products(products)
        .address(address)
        .total(total));
```

`name` is also how to give two builders for the same type different names, for example when a class and one of
its factory methods are both annotated.

### What carries over from the parameters

* Annotations that can go on a parameter, such as validation annotations, are copied to the setter's parameter.
* Type annotations, such as a type use `@Nullable`, are kept at every level of the type, so
  `List<@Nullable String>` stays as it is on the setter's parameter and the builder's field.
* A varargs final parameter stays varargs, so `.withTags("new", "sale")` works.
* Each `@param` description in the constructor or method's Javadoc becomes the setter's Javadoc, so it shows up
  in your IDE. For a record, the `@param` tags on the record itself are used, unless you've written a canonical
  constructor with its own Javadoc.

## Bonus: `toBuilder`

Set `toBuilder = true` to start a builder from an existing instance, with every argument already set. Change the
ones you want and build a copy.

**It needs a way to read each constructor argument back from your class.** For every argument, the class must
have one of these, which Ludicrous tries in this order:

1. A method named after the argument, such as `products()`.
2. A getter, such as `getProducts()`, or `isGift()` for a `boolean`.
3. A field named after the argument, such as `products`.

It can be declared on the class or inherited from a superclass. It must not be private or static, it must
be public or in the same package as the builder, and its type must be assignable to the argument's type without
unboxing, so an `Integer getAge()` isn't used for an `int age`. A method that declares exceptions, such as
`getFile() throws IOException`, is skipped. The builder is in the same package as the annotated class, or for a
static factory method, the class declaring the method.

If an argument can't be read, it fails to compile, naming the argument and what to add. This check only runs with
`toBuilder = true`, so classes without accessors can still use plain `@Ludicrous`.

For example, with getters:

```java
@Ludicrous(toBuilder = true)
public class OrderView {

    private final List<Product> products;
    private final Address address;
    private final Money total;

    public OrderView(List<Product> products, Address address, Money total) {
        ...
    }

    public List<Product> getProducts() { return products; }
    public Address getAddress() { return address; }
    public Money getTotal() { return total; }
}

OrderView redirected = OrderViewBuilder.build(OrderViewBuilder.from(view)
        .withAddress(newAddress));
```

With `parent = true` as well, the class also gets an instance method, which reads better still:

```java
@Ludicrous(parent = true, toBuilder = true)
public final class OrderView extends OrderViewBuilders {
    ...
}

OrderView redirected = OrderView.from(view.toBuilder()
        .withAddress(newAddress));
```

## You probably shouldn't use this.

### Why you should use it

* Forgetting an argument is caught by the compiler, not by a `NullPointerException` in production.
* Adding a constructor parameter breaks every call site that doesn't set it, so none are missed.
* Arguments are named at the call site, so two parameters of the same type can't be silently swapped
  as they can with a long constructor call.
* Arguments can be set in any order.
* Zero runtime dependencies. Everything happens at compile time, with no reflection.

### Why you shouldn't use it

* As mentioned, the ludicrously long type definitions.
* You either have to call `build` on the builder class or your annotated class must extend the generated
  `<Name>Builders`, both of which are ugly.
* Compile errors are type mismatches between builder types with hard to read messages.
* Passing a partially built builder around means writing out its full type, one type parameter per
  constructor argument. A local variable can use `var` instead, but fields, parameters and return types can't.
* Every `with` call creates a new builder.
* Every argument is always required.

## Adding it to a project

Ludicrous needs Java 17 or later.

```xml
<dependency>
    <groupId>com.github.samblake.ludicrous</groupId>
    <artifactId>ludicrous</artifactId>
    <version>1.0</version>
    <scope>provided</scope>
</dependency>
```

Nothing from Ludicrous is needed at runtime, so `provided` keeps it out of the packaged application.

If the project sets `annotationProcessorPaths` on `maven-compiler-plugin`, processors on the classpath are ignored,
so add `ludicrous` to that list as well.

In IntelliJ, annotation processing must be enabled for the generated classes to be found.

### Modules

In a modular project, require Ludicrous statically, as it's only needed while compiling, and put it on the
processor module path along with Palantir's javapoet, `com.palantir.javapoet:javapoet`:

```java
module shop {
    requires static com.github.samblake.ludicrous;
}
```

Generated classes are marked `@Generated`, so coverage tools and linters can skip them. The annotation is in the
`java.compiler` module, so in a named module it's only added if the module reads `java.compiler`, by requiring it
directly or through a module such as `java.se`.
