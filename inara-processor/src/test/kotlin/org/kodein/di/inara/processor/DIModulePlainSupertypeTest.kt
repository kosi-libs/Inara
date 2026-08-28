@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the shape generated for a `@DIModule` interface that extends a *plain* (non-`@DIModule`)
 * interface: every member reachable through it is bound exactly like an own declaration - required
 * for an abstract member, optional/nullable for a default-bodied one - and, unlike a `@DIModule`
 * parent, it can't be sourced from an instance: its members stay binding parameters even on a
 * `@DIModule(composed = true)` from-parents builder.
 */
class DIModulePlainSupertypeTest {

    @Test
    fun abstractMemberOfPlainSupertype_getsRequiredBinding() {
        val source = SourceFile.kotlin(
            "PlainAbstract.kt",
            """
            package sample.plain

            import org.kodein.di.inara.DIModule

            interface Api {
                val name: String
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule1.kt")

        assertTrue("public val nameBinding: Binding<Module1, Unit, String>," in generated, generated)
        assertTrue("override val name: String" in generated, generated)
        assertTrue("this.nameBinding.get(this, Unit)" in generated, generated)
        assertTrue("name: Binding<Module1, Unit, String>," in generated, generated)
    }

    @Test
    fun defaultBodyMemberOfPlainSupertype_getsOptionalBinding() {
        val source = SourceFile.kotlin(
            "PlainDefault.kt",
            """
            package sample.plain

            import org.kodein.di.inara.DIModule

            interface Api {
                fun tag(): String = "api"
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule1.kt")

        assertTrue("public val tagBinding: Binding<Module1, Unit, String>?," in generated, generated)
        assertTrue("super<Module1>.tag()" in generated, generated)
        assertTrue("tag: Binding<Module1, Unit, String>? = null" in generated, generated)
    }

    @Test
    fun genericPlainSupertype_substitutesTypeArgument() {
        val source = SourceFile.kotlin(
            "PlainGeneric.kt",
            """
            package sample.plain

            interface Api<T> {
                val value: T
                fun map(input: T): T
            }

            @org.kodein.di.inara.DIModule
            interface Module1 : Api<String> {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule1.kt")

        // The substituted concrete type shows up, not Api's own type parameter name.
        assertTrue("public val valueBinding: Binding<Module1, Unit, String>," in generated, generated)
        assertTrue("public val mapBinding: Binding<Module1, String, String>," in generated, generated)
        assertTrue("override fun map(input: String): String" in generated, generated)
    }

    @Test
    fun overrideInPlainSupertypeHierarchy_memberAppearsOnceWithDerivedDefault() {
        val source = SourceFile.kotlin(
            "PlainOverride.kt",
            """
            package sample.plain

            import org.kodein.di.inara.DIModule

            interface Api {
                val tag: String
            }

            interface DefaultingApi : Api {
                override val tag: String get() = "default"
            }

            @DIModule
            interface Module1 : DefaultingApi {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule1.kt")

        // `tag` appears once, and as the *derived* (default-bodied) declaration, not Api's abstract
        // one - a required binding would mean the abstract version won.
        assertEquals(1, Regex("""tagBinding""").findAll(generated).count { generated.substring(it.range.first).startsWith("tagBinding:") }, generated)
        assertTrue("public val tagBinding: Binding<Module1, Unit, String>?," in generated, generated)
        assertTrue("super<Module1>.tag" in generated, generated)
    }

    @Test
    fun composedModule_plainSupertypeMembersAreOwn_parentMembersAreNot() {
        val source = SourceFile.kotlin(
            "PlainComposed.kt",
            """
            package sample.plain

            import org.kodein.di.inara.DIModule

            interface Api {
                val tag: String
            }

            @DIModule
            interface Module1 {
                val a: Int
            }

            @DIModule(composed = true)
            interface Module2 : Module1, Api {
                val b: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule2.kt")

        // The composed builder: from its own `public fun Module2(` up to the first `copy`.
        val composedBuilder = generated
            .substringAfterLast("public fun Module2(")
            .substringBefore("public fun Module2.copy")

        // Api (plain) is not delegable: its member, and the own member `b`, are direct parameters.
        assertTrue("tag: Binding<Module2, Unit, String>," in composedBuilder, generated)
        assertTrue("b: Binding<Module2, Unit, Int>," in composedBuilder, generated)
        // Module1 (a @DIModule parent) is: it comes in as a `module1: Module1` instance, no binding
        // parameter for its member `a` - that binding is pulled off the instance instead.
        assertTrue("module1: Module1," in composedBuilder, generated)
        assertTrue("a: Binding" !in composedBuilder, generated)
        assertTrue(
            "aBinding = if (module1 is InaraModule1) module1.aBinding else BindingImpls.Delegate { module1.a }" in composedBuilder,
            generated,
        )
    }

    @Test
    fun diModuleParentExtendingPlainSupertype_grandchildBindsPlainMemberToo() {
        val source = SourceFile.kotlin(
            "PlainGrandparent.kt",
            """
            package sample.plain

            import org.kodein.di.inara.DIModule

            interface Api {
                val tag: String
            }

            @DIModule
            interface Module1 : Api {
                val a: Int
            }

            @DIModule
            interface Module2 : Module1 {
                val b: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/plain", "InaraModule2.kt")

        assertTrue("public val tagBinding: Binding<Module2, Unit, String>," in generated, generated)
        assertTrue("override val tag: String" in generated, generated)
        assertTrue("public val aBinding: Binding<Module2, Unit, Int>," in generated, generated)
        assertTrue("public val bBinding: Binding<Module2, Unit, Int>," in generated, generated)
    }
}
