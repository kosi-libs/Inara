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

    @Test
    fun ignoredMembers_useTheInterfaceBody_noBindingNeeded() {
        // tag (a fully-defaulted var), String.shoutedLength (a defaulted extension property), and
        // identity (a defaulted generic function) can't be bound at all - the builder call below
        // takes no parameter for any of them, and they still resolve to DefaultsModule's own body.
        val module = DefaultsModule(name = Binding.value("World"))

        assertEquals("default-tag", module.tag)
        assertEquals(5, with(module) { "hello".shoutedLength })
        assertEquals(42, module.identity(42))
        assertEquals("forty-two", module.identity("forty-two"))
    }

    @Test
    fun ignoredMembers_stillPresentAfterCopy() {
        val module = DefaultsModule(name = Binding.value("World"))
        val copy = module.copy(greeting = Binding.value("Hi"))

        assertEquals("default-tag", copy.tag)
        assertEquals(4, with(copy) { "abcd".shoutedLength })
    }
}
