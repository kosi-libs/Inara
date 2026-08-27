package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleFactorySuspendTest {
    @Test
    fun generatedSuspendFactory_resolvesASuspendModuleMember() = runTest {
        val async = AsyncModule(
            name = Binding.value("async"),
            load = SuspendBinding.value(Service(42)),
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        val greeter = AsyncGreeter(async)

        assertEquals(42, greeter.service.id)
    }
}
