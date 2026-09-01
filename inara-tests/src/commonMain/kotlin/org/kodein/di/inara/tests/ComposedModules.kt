package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

data class Greeting(val text: String)

/**
 * Two leaf `@DIModule` interfaces and a `ComposedModule` extending both, exercising module
 * inheritance through real Gradle/KSP-generated code: `greet` (inherited, has a default body) and
 * `nextAsync` (inherited, `suspend`) both come from a parent, alongside `label`, declared directly
 * on `ComposedModule`. `ComposedModule` is `@DIModule(composed = true)` so it additionally gets the
 * from-parents builder overload and the parent-swapping `copy` overload - see [InheritedModule] for
 * a plain `@DIModule` child, which doesn't.
 */
@DIModule
interface GreeterModule {
    val greeting: Greeting
    fun greet(name: String): String = "${greeting.text}, $name!"
}

@DIModule
interface AsyncCounterModule {
    suspend fun nextAsync(): Int
}

@DIModule(composed = true)
interface ComposedModule : GreeterModule, AsyncCounterModule {
    val label: String
}

/**
 * A plain `@DIModule` child of [GreeterModule]: it inherits `greet`/`greeting` (bindable through
 * its own full builder, like any other member) but gets no `InheritedModule(greeterModule, ...)`
 * from-parents builder and no parent-swapping `copy` overload, since it isn't `composed = true`.
 */
@DIModule
interface InheritedModule : GreeterModule {
    val suffix: String
}

/**
 * A plain `@DIModule` diamond: [DiamondLeft] and [DiamondRight] both extend [DiamondBase], and
 * [DiamondModule] extends both. This is only legal because none of the four interfaces is
 * `@DIModule(composed = true)` - [DiamondBase]'s members are folded into [DiamondModule]'s full builder
 * exactly once, reached through either path.
 */
@DIModule
interface DiamondBase {
    val base: String
}

@DIModule
interface DiamondLeft : DiamondBase

@DIModule
interface DiamondRight : DiamondBase

@DIModule
interface DiamondModule : DiamondLeft, DiamondRight {
    val extra: Int
}
