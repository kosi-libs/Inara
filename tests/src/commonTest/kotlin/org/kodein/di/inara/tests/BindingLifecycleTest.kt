package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class BindingLifecycleTest {
    @Test
    fun provider_reinvokesOnEveryAccess() {
        var calls = 0
        val module = CounterModule(
            next = Binding.provider { calls++ },
            instance = Binding.value(Service(0)),
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        assertEquals(0, module.next())
        assertEquals(1, module.next())
        assertEquals(2, module.next())
    }

    @Test
    fun single_returnsSameInstanceEveryTime() {
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.single { Service(id = next()) },
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        val first = module.instance
        val second = module.instance
        assertSame(first, second)
    }

    @Test
    fun multiplex_returnsStableInstancePerKey_andDistinctAcrossKeys() {
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.value(Service(0)),
            keyed = Binding.multiplex { key -> Binding.single { Service(key.length) } },
        )

        val abc1 = module.keyed("abc")
        val abc2 = module.keyed("abc")
        val de = module.keyed("de")

        assertSame(abc1, abc2)
        assertNotSame(abc1, de)
        assertEquals(3, abc1.id)
        assertEquals(2, de.id)
    }

    @Test
    fun detachedSingle_isSharedByEveryCopyAndShadow() {
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.detachedSingle { Service(id = 0) },
            keyed = Binding.detachedMultiplex { key -> Binding.detachedSingle { Service(key.length) } },
        )

        val instance = module.instance
        val keyed = module.keyed("abc")

        assertSame(instance, module.copy().instance)
        assertSame(instance, module.copy(next = Binding.provider { 1 }).instance)
        assertSame(instance, module.shadow(next = Binding.provider { 1 }).instance)
        assertSame(keyed, module.copy().keyed("abc"))
        assertSame(keyed, module.shadow(next = Binding.provider { 1 }).keyed("abc"))
    }

    @Test
    fun detachedSingle_isBuiltOnlyOnce_evenWhenTheCopyIsAccessedFirst() {
        var builds = 0
        val module = CounterModule(
            next = Binding.provider { 0 },
            instance = Binding.detachedSingle { Service(id = builds++) },
            keyed = Binding.multiplex { key -> Binding.provider { Service(key.length) } },
        )

        val fromCopy = module.copy().instance

        assertSame(fromCopy, module.instance)
        assertEquals(1, builds)
    }
}
