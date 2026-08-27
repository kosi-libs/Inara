package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Exercises members with a default implementation: their `Binding` is optional, and `null` keeps
 * the interface's own body instead of overriding it. `greeting` is declared before the abstract
 * `name` it reads, so the generated builder's optional parameter comes before a required one.
 *
 * [tag] (a fully-defaulted `var`), [String.shoutedLength] (a defaulted extension property), and
 * [identity] (a defaulted generic function) can't be expressed as a `Binding` at all - none of them
 * get a `Binding` parameter or an override anywhere; the generated class relies on ordinary Kotlin
 * inheritance to resolve them straight to this interface's own body.
 */
@DIModule
public interface DefaultsModule {
    public val greeting: String get() = "Hello, $name"
    public val name: String
    public fun shout(): String = "$greeting!!!"
    public fun prefixed(prefix: String): String = "$prefix$greeting"

    public var tag: String
        get() = "default-tag"
        set(_) {}

    public val String.shoutedLength: Int get() = uppercase().length

    public fun <T> identity(value: T): T = value
}
