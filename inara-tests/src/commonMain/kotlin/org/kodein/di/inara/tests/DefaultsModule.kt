package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Exercises members with a default implementation: their `Binding` is optional, and `null` keeps
 * the interface's own body instead of overriding it. `greeting` is declared before the abstract
 * `name` it reads, so the generated builder's optional parameter comes before a required one.
 */
@DIModule
public interface DefaultsModule {
    public val greeting: String get() = "Hello, $name"
    public val name: String
    public fun shout(): String = "$greeting!!!"
    public fun prefixed(prefix: String): String = "$prefix$greeting"
}
