package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInheritanceTest {
    @Test
    fun delegatedBuilder_combinesParentModulesWithOwnBindings() = runTest {
        val greeter = GreeterModule(greeting = Binding.value(Greeting("Hello")))
        val asyncCounter = AsyncCounterModule(nextAsync = SuspendBinding.provider { 42 })

        val module = ComposedModule(
            greeterModule = greeter,
            asyncCounterModule = asyncCounter,
            label = Binding.value("composed"),
        )

        assertEquals("Hello, Ada!", module.greet("Ada"))
        assertEquals(42, module.nextAsync())
        assertEquals("composed", module.label)
    }

    @Test
    fun fullBuilder_letsInheritedBindingReachSiblingMemberOnLeafType() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hi")),
            nextAsync = SuspendBinding.provider { label.length },
            label = Binding.value("abc"),
        )

        assertEquals(3, module.nextAsync())
    }

    @Test
    fun fullBuilder_inheritedDefaultBodyMember_usesInterfaceBodyWhenOmitted() = runTest {
        val module = ComposedModule(
            greeting = Binding.value(Greeting("Hey")),
            nextAsync = SuspendBinding.provider { 0 },
            label = Binding.value("abc"),
        )

        assertEquals("Hey, Bob!", module.greet("Bob"))
    }
}
