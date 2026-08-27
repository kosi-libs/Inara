package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleMultiParamTest {
    @Test
    fun buildsAndCallsEveryShape() {
        val module = MultiParamModule(
            va = Binding.factory { parts -> parts.joinToString("-") },
            mult = Binding.factory { args -> args.s.size.toLong() + args.i },
            both = Binding.factory { args -> "${args.ids.toList()}:${args.flag}" },
        )

        assertEquals("a-b-c", module.va("a", "b", "c"))
        assertEquals(5L, module.mult(listOf("x", "y"), 3))
        assertEquals("[1, 2, 3]:true", module.both(1, 2, 3, flag = true))
        assertEquals("[]:false", module.both())
    }

    @Test
    fun copyReconstructsVarargAndMultiParamDelegates() {
        val module = MultiParamModule(
            va = Binding.factory { "fixed" },
            mult = Binding.factory { 1L },
            both = Binding.factory { "fixed-both" },
        )

        val copy = module.copy(va = Binding.factory { parts -> parts.size.toString() })

        assertEquals("3", copy.va("a", "b", "c"))
        // mult/both are untouched by this copy - their delegates read the *current* (fixed) value.
        assertEquals(1L, copy.mult(listOf("x"), 0))
        assertEquals("fixed-both", copy.both(1, 2))
    }
}
