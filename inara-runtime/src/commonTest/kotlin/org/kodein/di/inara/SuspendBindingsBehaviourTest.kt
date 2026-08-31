package org.kodein.di.inara

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding.Bindings.value as bindingValue
import org.kodein.di.inara.SuspendBinding.Bindings.factory
import org.kodein.di.inara.SuspendBinding.Bindings.multiplex
import org.kodein.di.inara.SuspendBinding.Bindings.provider
import org.kodein.di.inara.SuspendBinding.Bindings.single
import org.kodein.di.inara.SuspendBinding.Bindings.value
import org.kodein.di.inara.SuspendBinding.Bindings.weakSingle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

private class SuspendBehaviourModule(val name: String = "module")

private data class SuspendBehaviourKey(val id: Int)

/**
 * Behaviour mirror of [BindingsTest] for [SuspendBindingImpls] - the `suspend get` lifecycle that
 * [SuspendBindingsTest] (only `toString`) and `inara-tests`' generated-code lifecycle tests leave to
 * indirect coverage. The `SingleThreadSafety` pairs from [BindingsTest] collapse to one test each:
 * the suspend side always uses a coroutine `Mutex`, there is no mode to choose.
 */
class SuspendBindingsBehaviourTest {

    @Test
    fun value_returnsSameInstanceEveryTime() = runTest {
        val instance = Any()
        val binding = value<SuspendBehaviourModule, Any>(instance)
        val module = SuspendBehaviourModule()

        assertSame(instance, binding.get(module, Unit))
        assertSame(instance, binding.get(module, Unit))
    }

    @Test
    fun value_acceptsNull() = runTest {
        val binding = value<SuspendBehaviourModule, String?>(null)
        assertNull(binding.get(SuspendBehaviourModule(), Unit))
    }

    @Test
    fun provider_invokesBuilderOnEveryGet() = runTest {
        var calls = 0
        val binding = provider<SuspendBehaviourModule, String> {
            calls++
            name
        }
        val module = SuspendBehaviourModule("mod-a")

        assertEquals("mod-a", binding.get(module, Unit))
        assertEquals("mod-a", binding.get(module, Unit))
        assertEquals(2, calls)
    }

    @Test
    fun provider_seesDifferentModulesPerCall() = runTest {
        val binding = provider<SuspendBehaviourModule, String> { name }

        assertEquals("one", binding.get(SuspendBehaviourModule("one"), Unit))
        assertEquals("two", binding.get(SuspendBehaviourModule("two"), Unit))
    }

    @Test
    fun factory_invokesBuilderOnEveryGetWithArg() = runTest {
        var calls = 0
        val binding = factory<SuspendBehaviourModule, Int, String> { arg ->
            calls++
            "$name-$arg"
        }
        val module = SuspendBehaviourModule("mod")

        assertEquals("mod-1", binding.get(module, 1))
        assertEquals("mod-2", binding.get(module, 2))
        assertEquals("mod-1", binding.get(module, 1))
        assertEquals(3, calls)
    }

    @Test
    fun single_buildsOnceAndCaches() = runTest {
        var calls = 0
        val binding = single<SuspendBehaviourModule, String> {
            calls++
            name
        }
        val module = SuspendBehaviourModule("mod")

        val first = binding.get(module, Unit)
        val second = binding.get(module, Unit)
        val third = binding.get(module, Unit)

        assertEquals("mod", first)
        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, calls)
    }

    @Test
    fun single_memoizesNullResult() = runTest {
        var calls = 0
        val binding = single<SuspendBehaviourModule, String?> {
            calls++
            null
        }
        val module = SuspendBehaviourModule()

        assertNull(binding.get(module, Unit))
        assertNull(binding.get(module, Unit))
        assertEquals(1, calls)
    }

    @Test
    fun weakSingle_cachesWhileStronglyReferenced() = runTest {
        var calls = 0
        val binding = weakSingle<SuspendBehaviourModule, Any> {
            calls++
            Any()
        }
        val module = SuspendBehaviourModule()

        // Hold strong references so nothing can be collected between the two gets.
        val first = binding.get(module, Unit)
        val second = binding.get(module, Unit)

        assertSame(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun multiplex_buildsDelegateOncePerDistinctArg() = runTest {
        var delegateBuilds = 0
        val binding = multiplex<SuspendBehaviourModule, SuspendBehaviourKey, Any> {
            delegateBuilds++
            single { Any() }
        }
        val module = SuspendBehaviourModule()
        val keyA = SuspendBehaviourKey(1)
        val keyB = SuspendBehaviourKey(2)

        val a1 = binding.get(module, keyA)
        val a2 = binding.get(module, keyA)
        val b1 = binding.get(module, keyB)

        assertSame(a1, a2)
        assertNotSame(a1, b1)
        assertEquals(2, delegateBuilds)
    }

    @Test
    fun multiplex_perArgBindingIsItselfCached() = runTest {
        var innerBuilds = 0
        val binding = multiplex<SuspendBehaviourModule, Int, Any> {
            single {
                innerBuilds++
                Any()
            }
        }
        val module = SuspendBehaviourModule()

        binding.get(module, 1)
        binding.get(module, 1)
        binding.get(module, 1)

        assertEquals(1, innerBuilds)
    }

    @Test
    fun lifted_forwardsToTheWrappedBinding() = runTest {
        val instance = Any()
        val lifted = bindingValue<SuspendBehaviourModule, Any>(instance).suspend()
        val module = SuspendBehaviourModule()

        assertSame(instance, lifted.get(module, Unit))
        assertSame(instance, lifted.get(module, Unit))
    }
}
