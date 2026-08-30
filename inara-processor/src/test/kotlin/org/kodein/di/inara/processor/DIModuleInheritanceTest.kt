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
 * Every `@DIModule` child gets one `internal Inara<Module>` implementation and its full builder
 * (one binding per member, inherited included, parameterized on the leaf module type) - see
 * [plainModule_generatesOnlyFullVariant]. A `@DIModule(composed = true)` child additionally gets a
 * second builder overload taking parent instances, which builds that *same* class by pulling each
 * inherited binding off its parent - see [composedModule_generatesFullClassAndComposedBuilder].
 */
class DIModuleInheritanceTest {

    @Test
    fun composedModule_generatesFullClassAndComposedBuilder() {
        val source = SourceFile.kotlin(
            "ComposedModules.kt",
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

            @DIModule(composed = true)
            interface Module3 : Module1, Module2 { val c: TypeC }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/inheritance", "InaraModule3.kt")

        // One implementation class, no delegated variant.
        assertTrue("InaraDelegatedModule3" !in generated, generated)
        assertEquals(1, Regex("""class InaraModule3\(""").findAll(generated).count(), generated)
        assertTrue("@file:OptIn(InternalInaraGeneration::class)" in generated, generated)
        assertTrue("@InternalInaraGeneration\npublic class InaraModule3(" in generated, generated)
        assertTrue("public val aBinding: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("public val bBinding: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("public val cBinding: Binding<Module3, Unit, TypeC>," in generated, generated)
        assertTrue("override val a: TypeA" in generated, generated)
        assertTrue("this.aBinding.get(this, Unit)" in generated, generated)

        // Two builders, both named after the module. The full one takes a binding per member.
        assertEquals(2, Regex("""fun Module3\(""").findAll(generated).count(), generated)
        assertTrue("a: Binding<Module3, Unit, TypeA>," in generated, generated)

        // The composed builder takes parent instances and pulls each inherited binding off them.
        assertTrue("module1: Module1," in generated, generated)
        assertTrue("module2: Module2," in generated, generated)
        assertTrue(
            "aBinding = if (module1 is InaraModule1) module1.aBinding else BindingImpls.Delegate(module1::class) { module1.a }" in generated,
            generated,
        )
        assertTrue(
            "bBinding = if (module2 is InaraModule2) module2.bBinding else BindingImpls.Delegate(module2::class) { module2.b }" in generated,
            generated,
        )
        assertTrue("cBinding = c," in generated, generated)
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

        val generated = compilation.generatedSource("sample/inheritance", "InaraModule3.kt")

        // No delegated variant at all - just the one Inara<Module> with every member's binding.
        assertTrue("InaraDelegatedModule3" !in generated, generated)
        assertTrue("public class InaraModule3(" in generated, generated)
        assertTrue("public val aBinding: Binding<Module3, Unit, TypeA>," in generated, generated)
        assertTrue("public val bBinding: Binding<Module3, Unit, TypeB>," in generated, generated)
        assertTrue("public val cBinding: Binding<Module3, Unit, TypeC>," in generated, generated)
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

        val generated = compilation.generatedSource("sample/inheritance", "InaraLeaf.kt")

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

        val generated = compilation.generatedSource("sample/inheritance", "InaraChildModule.kt")

        assertTrue(
            "public val greetingBinding: Binding<ChildModule, Unit, String>?" in generated,
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

        val generated = compilation.generatedSource("sample/inheritance", "InaraAsyncChildModule.kt")

        assertTrue(
            "public val loadBinding: SuspendBinding<AsyncChildModule, Unit, String>" in generated,
            generated,
        )
        assertTrue("override suspend fun load(): String" in generated, generated)
        assertTrue(
            "load: SuspendBinding<AsyncChildModule, Unit, String>" in generated,
            generated,
        )
    }
}
