package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

class Service(val id: Int)

/**
 * Used to assert lifecycle differences between `Binding.provider`, `Binding.single`, and
 * `Binding.multiplex` through real Gradle/KSP-generated code.
 */
@DIModule
interface CounterModule {
    fun next(): Int
    val instance: Service
    fun keyed(key: String): Service
}
