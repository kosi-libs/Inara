package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleVisibilityTest {
    @Test
    fun internalModule_builderIsUsableWithinTheModule() {
        val module: InternalModule = InternalModule(
            secret = Binding.value("shh"),
        )

        assertEquals("shh", module.secret)
    }

    @Test
    fun nestedModule_generatesFlattenedBuilder() {
        val module: Outer.Inner = Inner(
            label = Binding.value("inner"),
        )

        assertEquals("inner", module.label)
    }
}
