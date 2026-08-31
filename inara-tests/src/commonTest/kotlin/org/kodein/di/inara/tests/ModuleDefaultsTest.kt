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
    fun privateHelper_isReachableFromAPublicDefaultBody() {
        // `emphasize` is private - the generated class has no binding for it and never overrides it,
        // it just inherits the interface's body, which `emphasized()` calls.
        val module = DefaultsModule(name = Binding.value("World"))

        assertEquals("*Hello, World*", module.emphasized())
    }

    @Test
    fun ignoredMembers_stillPresentAfterCopy() {
        val module = DefaultsModule(name = Binding.value("World"))
        val copy = module.copy(greeting = Binding.value("Hi"))

        assertEquals("default-tag", copy.tag)
        assertEquals(4, with(copy) { "abcd".shoutedLength })
    }

    @Test
    fun boundDefaultBodyMember_survivesCopy() {
        val module = DefaultsModule(
            name = Binding.value("World"),
            greeting = Binding.value("Hi"),
        )

        val copy = module.copy(name = Binding.value("Kotlin"))

        // `greeting` was explicitly bound and is not named in the copy, so its binding is carried
        // across verbatim rather than reverting to the interface's default body.
        assertEquals("Hi", copy.greeting)
        assertEquals("Hi!!!", copy.shout())
        assertEquals("Kotlin", copy.name)
    }

    @Test
    fun unboundDefaultBodyMember_stillReEvaluatesAfterCopy() {
        val module = DefaultsModule(name = Binding.value("World"))

        val copy = module.copy(name = Binding.value("Kotlin"))

        // `greeting` was never bound, so it stays unbound after the copy and its default body
        // re-runs against the new module - observing the overridden `name`.
        assertEquals("Hello, Kotlin", copy.greeting)
        assertEquals("Hello, Kotlin!!!", copy.shout())
    }

    @Test
    fun explicitNullOnCopy_restoresTheDefaultBody() {
        val module = DefaultsModule(
            name = Binding.value("World"),
            greeting = Binding.value("Hi"),
        )

        val copy = module.copy(greeting = null)

        assertEquals("Hello, World", copy.greeting)
        assertEquals("Hello, World!!!", copy.shout())
    }

    @Test
    fun boundDefaultBodyMember_survivesChainedCopies() {
        val module = DefaultsModule(
            name = Binding.value("World"),
            greeting = Binding.value("Hi"),
        )

        val copy = module
            .copy(name = Binding.value("A"))
            .copy(prefixed = Binding.factory { prefix -> "$prefix!" })

        assertEquals("Hi", copy.greeting)
        assertEquals("Hi!!!", copy.shout())
        assertEquals(">>!", copy.prefixed(">>"))
    }
}
