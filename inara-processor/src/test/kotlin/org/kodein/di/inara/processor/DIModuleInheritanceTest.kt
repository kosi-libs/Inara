@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the shape generated for an interface that extends other `@DIModule` interfaces.
 *
 * A plain `@DIModule` child only gets the full implementation/builder pair (one binding per
 * member, inherited included, parameterized on the leaf module type) - see
 * [plainModule_generatesOnlyFullVariant]. A `@ComposedDIModule` child additionally gets a
 * delegated implementation/builder pair (parent instances + own bindings) - see
 * [composedModule_generatesDelegatedAndFullVariants].
 */
class DIModuleInheritanceTest {

    @Test
    fun composedModule_generatesDelegatedAndFullVariants() {
        val source = SourceFile.kotlin(
            "ComposedModules.kt",
            """
            package sample.inheritance

            import org.kodein.di.inara.ComposedDIModule
            import org.kodein.di.inara.DIModule

            class TypeA
            class TypeB
            class TypeC

            @DIModule
            interface Module1 { val a: TypeA }

            @DIModule
            interface Module2 { val b: TypeB }

            @ComposedDIModule
            interface Module3 : Module1, Module2 { val c: TypeC }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "GeneratedModule3.kt")

        // Delegated variant: parent instances + own binding only.
        assertTrue("private class GeneratedDelegatedModule3(" in generated, generated)
        assertTrue("module1: Module1," in generated, generated)
        assertTrue("module2: Module2," in generated, generated)
        assertTrue("private val cBinding: Binding<Module3, Unit, TypeC>," in generated, generated)
        assertTrue("Module1 by module1" in generated, generated)
        assertTrue("Module2 by module2" in generated, generated)
        assertTrue("override val c: TypeC" in generated, generated)
        assertTrue("this.cBinding.get(this, Unit)" in generated, generated)
        assertTrue("aBinding" !in generated.substringBefore("private class GeneratedModule3("), generated)

        // Full variant: one binding per member, inherited included, all on Module3.
        assertTrue("private class GeneratedModule3(" in generated, generated)
        assertTrue("private val aBinding: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("private val bBinding: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("override val a: TypeA" in generated, generated)
        assertTrue("this.aBinding.get(this, Unit)" in generated, generated)
        assertTrue("override val b: TypeB" in generated, generated)
        assertTrue("this.bBinding.get(this, Unit)" in generated, generated)

        // Both builder functions are named after the module and take a binding for `c`.
        val builderOccurrences = Regex("""fun Module3\(""").findAll(generated).count()
        assertEquals(2, builderOccurrences, generated)
        assertTrue("a: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("b: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("c: Binding<Module3, Unit, TypeC>," in generated, generated)
    }

    @Test
    fun plainModule_generatesOnlyFullVariant() {
        val source = SourceFile.kotlin(
            "PlainInheritance.kt",
            """
            package sample.inheritance

            import org.kodein.di.inara.DIModule

            class TypeA
            class TypeB
            class TypeC

            @DIModule
            interface Module1 { val a: TypeA }

            @DIModule
            interface Module2 { val b: TypeB }

            @DIModule
            interface Module3 : Module1, Module2 { val c: TypeC }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "GeneratedModule3.kt")

        // No delegated variant at all - just the one Generated<Module> with every member's binding.
        assertTrue("GeneratedDelegatedModule3" !in generated, generated)
        assertTrue("private class GeneratedModule3(" in generated, generated)
        assertTrue("private val aBinding: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("private val bBinding: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("private val cBinding: Binding<Module3, Unit, TypeC>," in generated, generated)
        assertTrue("override val a: TypeA" in generated, generated)
        assertTrue("override val b: TypeB" in generated, generated)
        assertTrue("override val c: TypeC" in generated, generated)

        // Exactly one builder function, taking a binding for every member (inherited included).
        val builderOccurrences = Regex("""fun Module3\(""").findAll(generated).count()
        assertEquals(1, builderOccurrences, generated)
        assertTrue("a: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("b: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("c: Binding<Module3, Unit, TypeC>," in generated, generated)
    }

    @Test
    fun diamondInheritance_isAllowedWithoutComposition() {
        val source = SourceFile.kotlin(
            "PlainDiamond.kt",
            """
            package sample.inheritance

            import org.kodein.di.inara.DIModule

            @DIModule
            interface Base {
                val name: String
                fun greeting(): String = "Hello, ${'$'}name"
            }

            @DIModule
            interface Left : Base

            @DIModule
            interface Right : Base

            @DIModule
            interface Leaf : Left, Right {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "GeneratedLeaf.kt")

        // The shared ancestor's members are folded in exactly once, not once per path: each gets a
        // single constructor-parameter declaration, not one per path reaching Base.
        assertEquals(1, Regex("""nameBinding:""").findAll(generated).count(), generated)
        assertEquals(1, Regex("""greetingBinding:""").findAll(generated).count(), generated)
        assertTrue("override val name: String" in generated, generated)
        assertTrue("super<Leaf>.greeting()" in generated, generated)
    }

    @Test
    fun inheritedDefaultBodyMember_getsOptionalBinding() {
        val source = SourceFile.kotlin(
            "DefaultInheritance.kt",
            """
            package sample.inheritance

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                val name: String
                fun greeting(): String = "Hello, ${'$'}name"
            }

            @DIModule
            interface ChildModule : BaseModule {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "GeneratedChildModule.kt")

        assertTrue(
            "private val greetingBinding: Binding<ChildModule, Unit, String>?" in generated,
            generated,
        )
        assertTrue("super<ChildModule>.greeting()" in generated, generated)
        assertTrue(
            "greeting: Binding<ChildModule, Unit, String>? = null" in generated,
            generated,
        )
    }

    @Test
    fun inheritedSuspendMember_getsSuspendBinding() {
        val source = SourceFile.kotlin(
            "SuspendInheritance.kt",
            """
            package sample.inheritance

            import org.kodein.di.inara.DIModule

            @DIModule
            interface AsyncBaseModule {
                suspend fun load(): String
            }

            @DIModule
            interface AsyncChildModule : AsyncBaseModule {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "GeneratedAsyncChildModule.kt")

        assertTrue(
            "private val loadBinding: SuspendBinding<AsyncChildModule, Unit, String>" in generated,
            generated,
        )
        assertTrue("override suspend fun load(): String" in generated, generated)
        assertTrue(
            "load: SuspendBinding<AsyncChildModule, Unit, String>" in generated,
            generated,
        )
    }
}
