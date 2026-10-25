package org.kodein.di.inara

import org.kodein.di.inara.Binding.Bindings.value as bindingValue
import org.kodein.di.inara.SuspendBinding.Bindings.factory
import org.kodein.di.inara.SuspendBinding.Bindings.multiplex
import org.kodein.di.inara.SuspendBinding.Bindings.provider
import org.kodein.di.inara.SuspendBinding.Bindings.single
import org.kodein.di.inara.SuspendBinding.Bindings.value
import org.kodein.di.inara.SuspendBinding.Bindings.weakSingle
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test
import kotlin.test.assertEquals

private class SuspendTestModule(val name: String = "module")

private data class SuspendKey(val id: Int)

/** `toString` mirror of [BindingsTest] for [SuspendBindingImpls] - `toString` is never `suspend`,
 * so these run like any other test without a coroutine runner. */
class SuspendBindingsTest {

    @Test
    fun value_toString_namesTheHeldType() {
        assertEquals("value ( String )", value<SuspendTestModule, String>("x").toString())
    }

    @Test
    fun value_toString_namesTheHeldType_evenWhenTheValueIsNull() {
        assertEquals("value ( String )", value<SuspendTestModule, String?>(null).toString())
    }

    @Test
    fun provider_toString_namesTheReturnedType() {
        assertEquals("provider { String }", provider<SuspendTestModule, String> { name }.toString())
    }

    @Test
    fun factory_toString_namesArgAndReturnedTypes() {
        assertEquals("factory { Int -> String }", factory<SuspendTestModule, Int, String> { "$it" }.toString())
    }

    @Test
    fun single_toString_namesSafetyAndReturnedType() {
        assertEquals(
            "single(Synchronized) { String }",
            single<SuspendTestModule, String>(SingleThreadSafety.Synchronized) { name }.toString(),
        )
        assertEquals(
            "single(Published) { String }",
            single<SuspendTestModule, String>(SingleThreadSafety.Published) { name }.toString(),
        )
    }

    @Test
    fun weakSingle_toString_namesSafetyAndReturnedType() {
        assertEquals(
            "weakSingle(Synchronized) { Any }",
            weakSingle<SuspendTestModule, Any>(SingleThreadSafety.Synchronized) { Any() }.toString(),
        )
        assertEquals(
            "weakSingle(Published) { Any }",
            weakSingle<SuspendTestModule, Any>(SingleThreadSafety.Published) { Any() }.toString(),
        )
    }

    @Test
    fun multiplex_toString_namesArgAndReturnedTypes() {
        assertEquals(
            "multiplex { SuspendKey -> Any }",
            multiplex<SuspendTestModule, SuspendKey, Any> { single { Any() } }.toString(),
        )
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun suspendDelegate_toString_namesTheClassItDelegatesTo() {
        assertEquals(
            "[delegated to SuspendTestModule]",
            SuspendBindingImpls.SuspendDelegate<SuspendTestModule, Unit, String>(SuspendTestModule::class) { name }.toString(),
        )
    }

    @Test
    fun suspend_toString_mirrorsTheWrappedBindingsOwnDescription() {
        // Binding.suspend() just lifts an existing Binding - there's no receiver instance to name,
        // so its toString() forwards to the wrapped Binding's own description instead of a
        // "[delegated to ...]" marker.
        val lifted = bindingValue<SuspendTestModule, String>("x").suspend()
        assertEquals("value ( String )", lifted.toString())
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_everyStatefulKind_returnsAFreshBinding() {
        val bindings = listOf<SuspendBinding<SuspendTestModule, *, *>>(
            single<SuspendTestModule, Any>(SingleThreadSafety.Synchronized) { Any() },
            single<SuspendTestModule, Any>(SingleThreadSafety.Published) { Any() },
            weakSingle<SuspendTestModule, Any>(SingleThreadSafety.Synchronized) { Any() },
            weakSingle<SuspendTestModule, Any>(SingleThreadSafety.Published) { Any() },
            multiplex<SuspendTestModule, Int, Any> { provider { Any() } },
            Binding.Bindings.single<SuspendTestModule, Any> { Any() }.suspend(),
        )
        for (binding in bindings) {
            val copy = SuspendBindingImpls.copyOf(binding)
            assertNotSame(binding, copy)
            assertEquals(binding.toString(), copy.toString())
        }
    }

    @OptIn(InternalInaraGeneration::class)
    @Test
    fun copyOf_statelessBindingsAndNull_areReturnedAsTheyAre() {
        val stateless = listOf<SuspendBinding<SuspendTestModule, *, *>>(
            value<SuspendTestModule, String>("x"),
            provider<SuspendTestModule, String> { "x" },
            factory<SuspendTestModule, Int, String> { "x" },
            bindingValue<SuspendTestModule, String>("x").suspend(),
        )
        for (binding in stateless) assertSame(binding, SuspendBindingImpls.copyOf(binding))

        val none: SuspendBinding<SuspendTestModule, Unit, String>? = null
        assertNull(SuspendBindingImpls.copyOf(none))
    }
}
