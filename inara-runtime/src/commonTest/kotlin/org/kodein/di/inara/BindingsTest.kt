package org.kodein.di.inara

import org.kodein.di.inara.Binding.Bindings.detachedMultiplex
import org.kodein.di.inara.Binding.Bindings.detachedSingle
import org.kodein.di.inara.Binding.Bindings.detachedWeakSingle
import org.kodein.di.inara.Binding.Bindings.factory
import org.kodein.di.inara.Binding.Bindings.multiplex
import org.kodein.di.inara.Binding.Bindings.provider
import org.kodein.di.inara.Binding.Bindings.single
import org.kodein.di.inara.Binding.Bindings.value
import org.kodein.di.inara.Binding.Bindings.weakSingle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

private class TestModule(val name: String = "module")

private data class Key(val id: Int)

private open class Animal
private class Dog : Animal()

class BindingsTest {

    @Test
    fun value_returnsSameInstanceEveryTime() {
        val instance = Any()
        val binding = value<TestModule, Any>(instance)
        val module = TestModule()

        assertSame(instance, binding.get(module, Unit))
        assertSame(instance, binding.get(module, Unit))
    }

    @Test
    fun value_acceptsNull() {
        val binding = value<TestModule, String?>(null)
        assertNull(binding.get(TestModule(), Unit))
    }

    @Test
    fun provider_invokesBuilderOnEveryGet() {
        var calls = 0
        val binding = provider<TestModule, String> {
            calls++
            name
        }
        val module = TestModule("mod-a")

        assertEquals("mod-a", binding.get(module, Unit))
        assertEquals("mod-a", binding.get(module, Unit))
        assertEquals(2, calls)
    }

    @Test
    fun provider_seesDifferentModulesPerCall() {
        val binding = provider<TestModule, String> { name }

        assertEquals("one", binding.get(TestModule("one"), Unit))
        assertEquals("two", binding.get(TestModule("two"), Unit))
    }

    @Test
    fun factory_invokesBuilderOnEveryGetWithArg() {
        var calls = 0
        val binding = factory<TestModule, Int, String> { arg ->
            calls++
            "$name-$arg"
        }
        val module = TestModule("mod")

        assertEquals("mod-1", binding.get(module, 1))
        assertEquals("mod-2", binding.get(module, 2))
        assertEquals("mod-1", binding.get(module, 1))
        assertEquals(3, calls)
    }

    @Test
    fun singleton_synchronized_buildsOnceAndCaches() =
        assertSingletonBuildsOnce(SingleThreadSafety.Synchronized)

    @Test
    fun singleton_publication_buildsOnceAndCaches() =
        assertSingletonBuildsOnce(SingleThreadSafety.Published)

    private fun assertSingletonBuildsOnce(safety: SingleThreadSafety) {
        var calls = 0
        val binding = single<TestModule, String>(safety) {
            calls++
            name
        }
        val module = TestModule("mod")

        val first = binding.get(module, Unit)
        val second = binding.get(module, Unit)
        val third = binding.get(module, Unit)

        assertEquals("mod", first)
        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, calls)
    }

    @Test
    fun singleton_synchronized_memoizesNullResult() =
        assertSingletonMemoizesNull(SingleThreadSafety.Synchronized)

    @Test
    fun singleton_publication_memoizesNullResult() =
        assertSingletonMemoizesNull(SingleThreadSafety.Published)

    private fun assertSingletonMemoizesNull(safety: SingleThreadSafety) {
        var calls = 0
        val binding = single<TestModule, String?>(safety) {
            calls++
            null
        }
        val module = TestModule()

        assertNull(binding.get(module, Unit))
        assertNull(binding.get(module, Unit))
        assertEquals(1, calls)
    }

    @Test
    fun weakSingleton_synchronized_cachesWhileStronglyReferenced() =
        assertWeakSingletonCaches(SingleThreadSafety.Synchronized)

    @Test
    fun weakSingleton_publication_cachesWhileStronglyReferenced() =
        assertWeakSingletonCaches(SingleThreadSafety.Published)

    private fun assertWeakSingletonCaches(safety: SingleThreadSafety) {
        var calls = 0
        val binding = weakSingle<TestModule, Any>(safety) {
            calls++
            Any()
        }
        val module = TestModule()

        // Hold strong references so nothing can be collected between the two gets.
        val first = binding.get(module, Unit)
        val second = binding.get(module, Unit)

        assertSame(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun multiplex_buildsDelegateOncePerDistinctArg() {
        var delegateBuilds = 0
        val binding = multiplex<TestModule, Key, Any> {
            delegateBuilds++
            single { Any() }
        }
        val module = TestModule()
        val keyA = Key(1)
        val keyB = Key(2)

        val a1 = binding.get(module, keyA)
        val a2 = binding.get(module, keyA)
        val b1 = binding.get(module, keyB)

        assertSame(a1, a2)
        assertNotSame(a1, b1)
        assertEquals(2, delegateBuilds)
    }

    @Test
    fun multiplex_perArgBindingIsItselfCached() {
        var innerBuilds = 0
        val binding = multiplex<TestModule, Int, Any> {
            single {
                innerBuilds++
                Any()
            }
        }
        val module = TestModule()

        binding.get(module, 1)
        binding.get(module, 1)
        binding.get(module, 1)

        assertEquals(1, innerBuilds)
    }

    @Test
    fun value_toString_namesTheHeldType() {
        assertEquals("value ( String )", value<TestModule, String>("x").toString())
    }

    @Test
    fun value_toString_namesTheHeldType_evenWhenTheValueIsNull() {
        // The reified type is still `String`, classifier resolution does not depend on nullability
        // of the *value* held - only the "?: ..." fallback (an unreachable case here) would print "?".
        assertEquals("value ( String )", value<TestModule, String?>(null).toString())
    }

    @Test
    fun toString_showsTheDeclaredTypeNotTheRuntimeClassOfWhatItHolds() {
        // Binding<M, A, out T> is covariant in T, so a Binding<TestModule, Unit, Animal> can hold a
        // Dog instance. `cls<T>()` captures the reified `T` at the call site (Animal) - not the
        // runtime class of the value the caller happened to pass (Dog) - which is exactly what makes
        // that assignment sound: the binding is typed, and prints itself, as what it was declared to
        // build, regardless of which subtype actually backs it.
        val binding: Binding<TestModule, Unit, Animal> = value<TestModule, Animal>(Dog())
        assertEquals("value ( Animal )", binding.toString())
    }

    @Test
    fun provider_toString_namesTheReturnedType() {
        assertEquals("provider { String }", provider<TestModule, String> { name }.toString())
    }

    @Test
    fun factory_toString_namesArgAndReturnedTypes() {
        assertEquals("factory { Int -> String }", factory<TestModule, Int, String> { "$it" }.toString())
    }

    @Test
    fun single_toString_namesSafetyAndReturnedType() {
        assertEquals(
            "single(Synchronized) { String }",
            single<TestModule, String>(SingleThreadSafety.Synchronized) { name }.toString(),
        )
        assertEquals(
            "single(Published) { String }",
            single<TestModule, String>(SingleThreadSafety.Published) { name }.toString(),
        )
    }

    @Test
    fun weakSingle_toString_namesSafetyAndReturnedType() {
        assertEquals(
            "weakSingle(Synchronized) { Any }",
            weakSingle<TestModule, Any>(SingleThreadSafety.Synchronized) { Any() }.toString(),
        )
        assertEquals(
            "weakSingle(Published) { Any }",
            weakSingle<TestModule, Any>(SingleThreadSafety.Published) { Any() }.toString(),
        )
    }

    @Test
    fun multiplex_toString_namesArgAndReturnedTypes() {
        assertEquals(
            "multiplex { Key -> Any }",
            multiplex<TestModule, Key, Any> { single { Any() } }.toString(),
        )
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun delegate_toString_namesTheClassItDelegatesTo() {
        assertEquals(
            "[delegated to TestModule]",
            BindingImpls.Delegate<TestModule, Unit, String>(TestModule::class) { name }.toString(),
        )
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_single_buildsItsOwnInstance() {
        var builds = 0
        val original = single<TestModule, Any> { builds++; Any() }
        val module = TestModule()
        val first = original.get(module, Unit)

        val copy = BindingImpls.copyOf(original)

        assertNotSame(original, copy)
        assertNotSame(first, copy.get(module, Unit))
        assertSame(first, original.get(module, Unit))
        assertEquals(2, builds)
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_everyStatefulKind_returnsAFreshBinding() {
        val bindings = listOf<Binding<TestModule, *, *>>(
            single<TestModule, Any>(SingleThreadSafety.Synchronized) { Any() },
            single<TestModule, Any>(SingleThreadSafety.Published) { Any() },
            weakSingle<TestModule, Any>(SingleThreadSafety.Synchronized) { Any() },
            weakSingle<TestModule, Any>(SingleThreadSafety.Published) { Any() },
            multiplex<TestModule, Int, Any> { provider { Any() } },
        )
        for (binding in bindings) {
            val copy = BindingImpls.copyOf(binding)
            assertNotSame(binding, copy)
            assertEquals(binding.toString(), copy.toString())
        }
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_multiplex_startsWithAnEmptyCache() {
        val original = multiplex<TestModule, Int, Any> { single { Any() } }
        val module = TestModule()
        val first = original.get(module, 1)

        val copy = BindingImpls.copyOf(original)

        assertNotSame(first, copy.get(module, 1))
        assertSame(first, original.get(module, 1))
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_statelessBindingsAndNull_areReturnedAsTheyAre() {
        val stateless = listOf<Binding<TestModule, *, *>>(
            value<TestModule, String>("x"),
            provider<TestModule, String> { "x" },
            factory<TestModule, Int, String> { "x" },
            BindingImpls.Delegate<TestModule, Unit, String>(TestModule::class) { name },
        )
        for (binding in stateless) assertSame(binding, BindingImpls.copyOf(binding))

        val none: Binding<TestModule, Unit, String>? = null
        assertNull(BindingImpls.copyOf(none))
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun detachedSingle_buildsOnceAndIsItsOwnCopy() {
        var builds = 0
        val binding = detachedSingle { builds++; Any() }
        val module = TestModule()

        val first = binding.get(module, Unit)

        assertSame(first, binding.get(TestModule(), Unit))
        assertEquals(1, builds)
        assertSame(binding, BindingImpls.copyOf(binding))
        assertSame(first, BindingImpls.copyOf(binding).get(module, Unit))
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun detachedWeakSingle_isItsOwnCopy() {
        val binding = detachedWeakSingle { Any() }

        assertSame(binding, BindingImpls.copyOf(binding))
        val held = binding.get(TestModule(), Unit)
        assertSame(held, binding.get(TestModule(), Unit))
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun detachedMultiplex_isItsOwnCopy_andPassesNullAsTheModule() {
        val modules = mutableListOf<Any?>()
        val binding = detachedMultiplex<Int, Any> { _ ->
            provider<Any?, Any> { modules += this; Any() }
        }

        binding.get(TestModule(), 1)
        binding.get(TestModule(), 2)

        assertEquals(listOf<Any?>(null, null), modules)
        assertSame(binding, BindingImpls.copyOf(binding))
    }

    @Test
    fun detachedMultiplex_returnsStableInstancePerKey() {
        val binding = detachedMultiplex<Int, Any> { _ -> single { Any() } }
        val module = TestModule()

        assertSame(binding.get(module, 1), binding.get(module, 1))
        assertNotSame(binding.get(module, 1), binding.get(module, 2))
    }

    @Test
    fun detached_toString_prefixesTheWrappedBinding() {
        assertEquals("detached single(Synchronized) { String }", detachedSingle { "x" }.toString())
        assertEquals("detached single(Published) { String }", detachedSingle(SingleThreadSafety.Published) { "x" }.toString())
        assertEquals("detached weakSingle(Synchronized) { String }", detachedWeakSingle { "x" }.toString())
    }
}
