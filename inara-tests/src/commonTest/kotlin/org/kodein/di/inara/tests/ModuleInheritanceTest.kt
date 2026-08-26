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

    @Test
    fun plainDIModuleChild_hasNoDelegatedBuilder_fullBuilderBindsInheritedMember() = runTest {
        // InheritedModule is a plain @DIModule child of GreeterModule (not @ComposedDIModule), so
        // there is no InheritedModule(greeterModule, suffix) delegated builder to call - only the
        // full one, taking a binding for every member, inherited included.
        val module = InheritedModule(
            greeting = Binding.value(Greeting("Yo")),
            suffix = Binding.value("!"),
        )

        assertEquals("Yo, Zoe!", module.greet("Zoe"))
        assertEquals("!", module.suffix)
    }

    @Test
    fun diamondInheritance_allowedForPlainDIModule_baseMemberBoundOnce() = runTest {
        // DiamondModule reaches DiamondBase through both DiamondLeft and DiamondRight; none of the
        // four interfaces is @ComposedDIModule, so this is allowed and `base` is bound once.
        val module = DiamondModule(
            base = Binding.value("shared"),
            extra = Binding.value(7),
        )

        assertEquals("shared", module.base)
        assertEquals(7, module.extra)
    }
}
