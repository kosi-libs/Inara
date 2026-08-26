package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleDefaultsTest {
    @Test
    fun defaultBodies_areKeptWhenBindingIsOmitted() {
        val module = DefaultsModule(
            name = Binding.value("World"),
        )

        assertEquals("Hello, World", module.greeting)
        assertEquals("Hello, World!!!", module.shout())
        assertEquals(">>Hello, World", module.prefixed(">>"))
    }

    @Test
    fun defaultBodies_areOverriddenWhenBindingIsProvided() {
        val module = DefaultsModule(
            name = Binding.value("World"),
            greeting = Binding.value("Hi"),
            shout = Binding.value("overridden"),
            prefixed = Binding.factory { prefix -> "$prefix-custom" },
        )

        assertEquals("Hi", module.greeting)
        assertEquals("overridden", module.shout())
        assertEquals(">>-custom", module.prefixed(">>"))
    }
}
