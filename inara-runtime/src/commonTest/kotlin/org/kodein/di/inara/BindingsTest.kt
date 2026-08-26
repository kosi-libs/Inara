package org.kodein.di.inara

import org.kodein.di.inara.Binding.Bindings.ThreadSafety
import org.kodein.di.inara.Binding.Bindings.factory
import org.kodein.di.inara.Binding.Bindings.multiplex
import org.kodein.di.inara.Binding.Bindings.provider
import org.kodein.di.inara.Binding.Bindings.singleton
import org.kodein.di.inara.Binding.Bindings.value
import org.kodein.di.inara.Binding.Bindings.weakSingleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

private class TestModule(val name: String = "module")

private data class Key(val id: Int)

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
        assertSingletonBuildsOnce(ThreadSafety.Synchronized)

    @Test
    fun singleton_publication_buildsOnceAndCaches() =
        assertSingletonBuildsOnce(ThreadSafety.Publication)

    private fun assertSingletonBuildsOnce(safety: ThreadSafety) {
        var calls = 0
        val binding = singleton<TestModule, String>(safety) {
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
        assertSingletonMemoizesNull(ThreadSafety.Synchronized)

    @Test
    fun singleton_publication_memoizesNullResult() =
        assertSingletonMemoizesNull(ThreadSafety.Publication)

    private fun assertSingletonMemoizesNull(safety: ThreadSafety) {
        var calls = 0
        val binding = singleton<TestModule, String?>(safety) {
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
        assertWeakSingletonCaches(ThreadSafety.Synchronized)

    @Test
    fun weakSingleton_publication_cachesWhileStronglyReferenced() =
        assertWeakSingletonCaches(ThreadSafety.Publication)

    private fun assertWeakSingletonCaches(safety: ThreadSafety) {
        var calls = 0
        val binding = weakSingleton<TestModule, Any>(safety) {
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
            singleton { Any() }
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
            singleton {
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
}
