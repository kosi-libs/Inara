package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

public class Service(public val id: Int)

/**
 * Used to assert lifecycle differences between `Binding.provider`, `Binding.singleton`, and
 * `Binding.multiplex` through real Gradle/KSP-generated code.
 */
@DIModule
public interface CounterModule {
    public fun next(): Int
    public val instance: Service
    public fun keyed(key: String): Service
}
