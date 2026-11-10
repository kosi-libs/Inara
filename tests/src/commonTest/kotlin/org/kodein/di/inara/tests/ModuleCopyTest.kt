package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ModuleCopyTest {
    @Test
    fun noArgCopy_returnsADifferentInstance() {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        assertNotSame(module, module.copyComposedModule())
    }

    @Test
    fun copy_hasItsOwnSingleton() {
        var builds = 0
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.single { Service(id = builds++) },
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        val original = module.instance
        val copy = module.copyCounterModule()

        assertNotSame(original, copy.instance)
        assertSame(copy.instance, copy.instance)
        assertSame(original, module.instance)
        assertEquals(2, builds)
    }

    @Test
    fun copy_hasItsOwnSingleton_whenTheCopyIsAccessedFirst() {
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.single { Service(id = 0) },
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        val copy = module.copyCounterModule(next = Binding.provider { 1 })
        val fromCopy = copy.instance
        val fromOriginal = module.instance

        assertNotSame(fromCopy, fromOriginal)
        assertSame(fromCopy, copy.instance)
        assertSame(fromOriginal, module.instance)
    }

    @Test
    fun copy_withAnOverride_readsTheOverrideInItsOwnSingleton() {
        val module = CounterModule(
            next = Binding.provider { 1 },
            instance = Binding.single { Service(id = next()) },
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        val copy = module.copyCounterModule(next = Binding.provider { 2 })

        assertEquals(2, copy.instance.id)
        assertEquals(1, module.instance.id)
    }

    @Test
    fun copy_hasItsOwnMultiplexCache() {
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.value(Service(0)),
            keyed = Binding.multiplex { key -> Binding.single { Service(key.length) } },
        )

        val original = module.keyed("abc")
        val copy = module.copyCounterModule()

        assertNotSame(original, copy.keyed("abc"))
        assertSame(copy.keyed("abc"), copy.keyed("abc"))
        assertSame(original, module.keyed("abc"))
    }

    @Test
    fun copy_hasItsOwnSuspendSingleton() = runTest {
        var builds = 0
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.single { builds++ },
            label = Binding.value("abc"),
        )

        val original = module.nextAsync()
        val copy = module.copyComposedModule()

        assertEquals(0, original)
        assertEquals(1, copy.nextAsync())
        assertEquals(1, copy.nextAsync())
        assertEquals(0, module.nextAsync())
    }

    @Test
    fun memberOverrideCopy_overridesOnlyTheGivenMember() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { 1 },
            label = Binding.value("abc"),
        )

        val copy = module.copyComposedModule(label = Binding.value("xyz"))

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

        // `greeting` is inherited from GreeterModule: `copyComposedModule` is never ambiguous with
        // `copyGreeterModule`, so `Binding.value` infers its module type without any help.
        val copy = module.copyComposedModule(greeting = Binding.value(Greeting("Hello")))

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
        val copy = module.copyComposedModule(greeterModule = otherGreeter)

        assertEquals("Yo, Ada!", copy.greet("Ada"))
        assertEquals("abc", copy.label)
        assertEquals(1, copy.nextAsync())
    }

    @Test
    fun copyOfDefaultBodyMember_stillUsesTheInterfaceDefault() {
        val module = DefaultsModule(name = Binding.value("World"))

        val copy = module.copyDefaultsModule(name = Binding.value("Kotlin"))

        assertEquals("Hello, Kotlin", copy.greeting)
        assertEquals("Hello, Kotlin!!!", copy.shout())
    }

    @Test
    fun chainedCopies_compose() {
        val module = DefaultsModule(name = Binding.value("World"))

        val copy = module
            .copyDefaultsModule(name = Binding.value("A"))
            .copyDefaultsModule(greeting = Binding.value("B"))

        assertEquals("B", copy.greeting)
        assertEquals("B!!!", copy.shout())
    }
}
