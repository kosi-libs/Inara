package org.kodein.di.inara.tests

import org.kodein.di.inara.ComposedDIModule
import org.kodein.di.inara.DIModule

public data class Greeting(public val text: String)

/**
 * Two leaf `@DIModule` interfaces and a `ComposedModule` extending both, exercising module
 * inheritance through real Gradle/KSP-generated code: `greet` (inherited, has a default body) and
 * `nextAsync` (inherited, `suspend`) both come from a parent, alongside `label`, declared directly
 * on `ComposedModule`. `ComposedModule` is `@ComposedDIModule` so it additionally gets the
 * delegated implementation/builder/`copy` set that takes already-built parent instances - see
 * [InheritedModule] for a plain `@DIModule` child, which doesn't.
 */
@DIModule
public interface GreeterModule {
    public val greeting: Greeting
    public fun greet(name: String): String = "${greeting.text}, $name!"
}

@DIModule
public interface AsyncCounterModule {
    public suspend fun nextAsync(): Int
}

@ComposedDIModule
public interface ComposedModule : GreeterModule, AsyncCounterModule {
    public val label: String
}

/**
 * A plain `@DIModule` child of [GreeterModule]: it inherits `greet`/`greeting` (bindable through
 * its own full builder, like any other member) but gets no `InheritedModule(greeterModule, ...)`
 * delegated builder and no parent-swapping `copy` overload, since it isn't `@ComposedDIModule`.
 */
@DIModule
public interface InheritedModule : GreeterModule {
    public val suffix: String
}

/**
 * A plain `@DIModule` diamond: [DiamondLeft] and [DiamondRight] both extend [DiamondBase], and
 * [DiamondModule] extends both. This is only legal because none of the four interfaces is
 * `@ComposedDIModule` - [DiamondBase]'s members are folded into [DiamondModule]'s full builder
 * exactly once, reached through either path.
 */
@DIModule
public interface DiamondBase {
    public val base: String
}

@DIModule
public interface DiamondLeft : DiamondBase

@DIModule
public interface DiamondRight : DiamondBase

@DIModule
public interface DiamondModule : DiamondLeft, DiamondRight {
    public val extra: Int
}
