package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import org.kodein.di.inara.suspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SuspendBindingLifecycleTest {
    @Test
    fun suspendProvider_reinvokesOnEveryAccess() = runTest {
        var calls = 0
        val module = AsyncModule(
            name = Binding.value("async"),
            load = SuspendBinding.provider { Service(calls++) },
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        assertEquals(0, module.load().id)
        assertEquals(1, module.load().id)
        assertEquals(2, module.load().id)
    }

    @Test
    fun suspendSingle_returnsSameInstanceEveryTime() = runTest {
        val module = AsyncModule(
            name = Binding.value("async"),
            load = SuspendBinding.single { Service(id = name.length) },
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        val first = module.load()
        val second = module.load()
        assertSame(first, second)
    }

    @Test
    fun suspendMultiplex_returnsStableInstancePerKey_andDistinctAcrossKeys() = runTest {
        val module = AsyncModule(
            name = Binding.value("async"),
            load = SuspendBinding.provider { Service(0) },
            loadKeyed = SuspendBinding.multiplex { key -> SuspendBinding.single { Service(key.length) } },
        )

        val abc1 = module.loadKeyed("abc")
        val abc2 = module.loadKeyed("abc")
        val de = module.loadKeyed("de")

        assertSame(abc1, abc2)
        assertNotSame(abc1, de)
        assertEquals(3, abc1.id)
        assertEquals(2, de.id)
    }

    @Test
    fun plainBindingAdaptsViaSuspend() = runTest {
        val module = AsyncModule(
            name = Binding.value("async"),
            load = Binding.provider<AsyncModule, Service> { Service(42) }.suspend(),
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        assertEquals(42, module.load().id)
    }

    @Test
    fun suspendMemberWithDefaultBody_usesInterfaceBodyWhenNull() = runTest {
        val module = AsyncModule(
            name = Binding.value("async"),
            load = SuspendBinding.provider { Service(7) },
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        assertEquals("async: 7", module.described())
    }
}
