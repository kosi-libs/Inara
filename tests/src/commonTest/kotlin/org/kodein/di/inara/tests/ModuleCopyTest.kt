package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModuleCopyTest {
    @Test
    fun noArgCopy_returnsTheSameInstance() {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        assertSame(module, module.copy())
    }

    @Test
    fun memberOverrideCopy_overridesOnlyTheGivenMember() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        val copy = module.copy(label = Binding.value("xyz"))

        assertEquals("xyz", copy.label)
        assertEquals("Hi, Ada!", copy.greet("Ada"))
        assertEquals(1, copy.nextAsync())
    }

    @Test
    fun memberOverrideCopy_overridesAnInheritedMember() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        // Explicit type on the right-hand side: with three `copy` overloads compiled ahead of time
        // (module inheritance means one has a same-named `Binding.value` parameter type, another
        // none at all), the compiler can't always infer `Binding.value`'s type parameter from a
        // bare named argument alone.
        val greetingBinding: Binding<ComposedModule, Unit, Greeting> = Binding.value(Greeting("Hello"))
        val copy = module.copy(greeting = greetingBinding)

        assertEquals("Hello, Ada!", copy.greet("Ada"))
        assertEquals("abc", copy.label)
        assertEquals(1, copy.nextAsync())
    }

    @Test
    fun moduleOverrideCopy_swapsOutAWholeParent() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        val otherGreeter = GreeterModule(greeting = Binding.value(Greeting("Yo")))
        val copy = module.copy(greeterModule = otherGreeter)

        assertEquals("Yo, Ada!", copy.greet("Ada"))
        assertEquals("abc", copy.label)
        assertEquals(1, copy.nextAsync())
    }

    @Test
    fun copyOfDefaultBodyMember_stillUsesTheInterfaceDefault() {
        val module = DefaultsModule(name = Binding.value("World"))

        val copy = module.copy(name = Binding.value("Kotlin"))

        assertEquals("Hello, Kotlin", copy.greeting)
        assertEquals("Hello, Kotlin!!!", copy.shout())
    }

    @Test
    fun chainedCopies_compose() {
        val module = DefaultsModule(name = Binding.value("World"))

        val copy = module
            .copy(name = Binding.value("A"))
            .copy(greeting = Binding.value("B"))

        assertEquals("B", copy.greeting)
        assertEquals("B!!!", copy.shout())
    }
}
