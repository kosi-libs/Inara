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
    fun nestedModule_builderKeepsSimpleName() {
        // The generated class/file flatten to InaraOuterInner, but the builder stays `Inner`.
        val module: Outer.Inner = Inner(
            label = Binding.value("inner"),
        )

        assertEquals("inner", module.label)
    }
}
