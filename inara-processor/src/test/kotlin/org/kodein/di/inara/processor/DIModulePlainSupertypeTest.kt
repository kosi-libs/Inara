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
 * parent, it is never delegable: its members show up in the delegated variant's own bindings too.
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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule1.kt")

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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule1.kt")

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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule1.kt")

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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule1.kt")

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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule2.kt")

        val delegated = generated.substringAfter("private class GeneratedDelegatedModule2(")
            .substringBefore("private class GeneratedModule2(")

        // Api (plain) is not delegable: its member is bound directly in the delegated variant.
        assertTrue("tagBinding: Binding<Module2, Unit, String>," in delegated, generated)
        // Module1 (a @DIModule parent) is delegable: no binding for its member here, just the
        // `module1: Module1` delegate parameter.
        assertTrue("module1: Module1," in delegated, generated)
        assertTrue("aBinding" !in delegated, generated)
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

        val generated = compilation.generatedSource("sample/plain", "GeneratedModule2.kt")

        assertTrue("public val tagBinding: Binding<Module2, Unit, String>," in generated, generated)
        assertTrue("override val tag: String" in generated, generated)
        assertTrue("public val aBinding: Binding<Module2, Unit, Int>," in generated, generated)
        assertTrue("public val bBinding: Binding<Module2, Unit, Int>," in generated, generated)
    }
}
