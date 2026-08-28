package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModuleInheritanceTest {
    @Test
    fun fromParentsBuilder_combinesParentModulesWithOwnBindings() = runTest {
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
    fun plainDIModuleChild_hasNoFromParentsBuilder_fullBuilderBindsInheritedMember() = runTest {
        // InheritedModule is a plain @DIModule child of GreeterModule (not composed = true), so
        // there is no InheritedModule(greeterModule, suffix) from-parents builder to call - only the
        // full one, taking a binding for every member, inherited included.
        val module = InheritedModule(
            greeting = Binding.value(Greeting("Yo")),
            suffix = Binding.value("!"),
        )

        assertEquals("Yo, Zoe!", module.greet("Zoe"))
        assertEquals("!", module.suffix)
    }

    @Test
    fun composedModule_copyPreservesInheritedSingletonBinding() {
        // The bug this design fixes: a composed module built from parent instances used to become a
        // GeneratedDelegated* with no binding property for inherited members, so copy() couldn't
        // carry them across and rebuilt a delegate - dropping the single's memo. Now it holds the
        // parent's actual binding, so the memo survives the copy.
        var builds = 0
        val greeter = GreeterModule(greeting = Binding.single { Greeting("built-${builds++}") })
        val module = ComposedModule(
            greeterModule = greeter,
            asyncCounterModule = AsyncCounterModule(nextAsync = SuspendBinding.provider { 0 }),
            label = Binding.value("a"),
        )

        val first = module.greeting
        val copy = module.copy(label = Binding.value("b"))

        assertSame(first, copy.greeting)
        assertEquals(1, builds)
        assertEquals("b", copy.label)
    }

    @Test
    fun composedModule_copyOverridesInheritedMemberBinding() = runTest {
        val module = ComposedModule(
            greeterModule = GreeterModule(greeting = Binding.value(Greeting("old"))),
            asyncCounterModule = AsyncCounterModule(nextAsync = SuspendBinding.provider { 0 }),
            label = Binding.value("a"),
        )

        // Explicit type: with three `copy` overloads compiled ahead of time the compiler can't
        // infer `Binding.value`'s type parameter from a bare named argument (see ModuleCopyTest).
        val greetingBinding: Binding<ComposedModule, Unit, Greeting> = Binding.value(Greeting("new"))
        val copy = module.copy(greeting = greetingBinding)

        assertEquals("new, Ada!", copy.greet("Ada"))
    }

    @Test
    fun diamondInheritance_allowedForPlainDIModule_baseMemberBoundOnce() = runTest {
        // DiamondModule reaches DiamondBase through both DiamondLeft and DiamondRight; none of the
        // four interfaces is composed = true, so this is allowed and `base` is bound once.
        val module = DiamondModule(
            base = Binding.value("shared"),
            extra = Binding.value(7),
        )

        assertEquals("shared", module.base)
        assertEquals(7, module.extra)
    }
}
