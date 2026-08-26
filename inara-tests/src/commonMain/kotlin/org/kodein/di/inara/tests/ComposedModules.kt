package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

public data class Greeting(public val text: String)

/**
 * Two leaf `@DIModule` interfaces and a `ComposedModule` extending both, exercising module
 * inheritance through real Gradle/KSP-generated code: `greet` (inherited, has a default body) and
 * `nextAsync` (inherited, `suspend`) both come from a parent, alongside `label`, declared directly
 * on `ComposedModule`.
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

@DIModule
public interface ComposedModule : GreeterModule, AsyncCounterModule {
    public val label: String
}
