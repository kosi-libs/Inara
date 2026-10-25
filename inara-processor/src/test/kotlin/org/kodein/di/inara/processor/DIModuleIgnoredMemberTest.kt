@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the generated shape for a member `Binding` can never express - a `var` with both
 * accessors defaulted, an extension member with a default body, a generic function with a default
 * body, or a private member (always has its own body): none of these get a `Binding` parameter or an
 * override anywhere. The generated class simply never touches them, relying on ordinary Kotlin
 * inheritance to resolve them to whichever interface's default body applies - own, a `@DIModule`
 * parent, or a plain supertype.
 */
class DIModuleIgnoredMemberTest {

    @Test
    fun fullyDefaultedVar_isAbsentFromBuilder_siblingStillBinds() {
        val source = SourceFile.kotlin(
            "IgnoredVar.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarModule {
                var value: String
                    get() = "default"
                    set(_) {}
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraVarModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("override var value" !in generated, generated)
        assertTrue("public val extraBinding: Binding<VarModule, Unit, Int>," in generated, generated)
        assertTrue("override val extra: Int" in generated, generated)
        assertTrue("fun VarModule(extra: Binding<VarModule, Unit, Int>): VarModule" in generated, generated)
    }

    @Test
    fun defaultedExtensionFunction_isAbsentFromBuilder() {
        val source = SourceFile.kotlin(
            "IgnoredExtensionFunction.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ExtensionFunctionModule {
                fun String.shout(): String = uppercase()
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraExtensionFunctionModule.kt")
        assertTrue("shoutBinding" !in generated, generated)
        assertTrue("shout" !in generated, generated)
    }

    @Test
    fun defaultedExtensionProperty_isAbsentFromBuilder() {
        val source = SourceFile.kotlin(
            "IgnoredExtensionProperty.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ExtensionPropertyModule {
                val String.doubled: String get() = this + this
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraExtensionPropertyModule.kt")
        assertTrue("doubledBinding" !in generated, generated)
        assertTrue("doubled" !in generated, generated)
    }

    @Test
    fun defaultedGenericFunction_isAbsentFromBuilder() {
        val source = SourceFile.kotlin(
            "IgnoredGeneric.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface GenericModule {
                fun <T> identity(value: T): T = value
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraGenericModule.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("identity" !in generated, generated)
    }

    @Test
    fun privateMember_isAbsentFromBuilder() {
        val source = SourceFile.kotlin(
            "IgnoredPrivate.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface PrivateModule {
                private fun helper(): String = extra.toString()
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraPrivateModule.kt")
        assertTrue("helperBinding" !in generated, generated)
        assertTrue("helper" !in generated, generated)
        assertTrue("public val extraBinding: Binding<PrivateModule, Unit, Int>," in generated, generated)
    }

    @Test
    fun defaultedGenericExtensionFunction_isIgnoredOnce() {
        val source = SourceFile.kotlin(
            "IgnoredGenericExtension.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface GenericExtensionModule {
                fun <T> T.identity(): T = this
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraGenericExtensionModule.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("identity" !in generated, generated)
    }

    @Test
    fun ignoredMember_inheritedFromDIModuleParent_staysUnboundInChild() {
        val source = SourceFile.kotlin(
            "IgnoredParent.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                var value: String
                    get() = "default"
                    set(_) {}
            }

            @DIModule
            interface ChildModule : BaseModule {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraChildModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("public val extraBinding: Binding<ChildModule, Unit, Int>," in generated, generated)
    }

    @Test
    fun ignoredMember_inheritedFromPlainSupertype_staysUnbound() {
        val source = SourceFile.kotlin(
            "IgnoredPlain.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            interface Api {
                fun <T> identity(value: T): T = value
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraModule1.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("public val extraBinding: Binding<Module1, Unit, Int>," in generated, generated)
    }

    @Test
    fun moduleWithOnlyIgnoredMember_generatesNoArgBuilderAndOnlyNoArgCopy() {
        val source = SourceFile.kotlin(
            "OnlyIgnored.kt",
            """
            package sample.ignored

            import org.kodein.di.inara.DIModule

            @DIModule
            interface OnlyIgnoredModule {
                var value: String
                    get() = "default"
                    set(_) {}
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/ignored", "InaraOnlyIgnoredModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("public fun OnlyIgnoredModule(): OnlyIgnoredModule" in generated, generated)
        assertTrue(Regex("""public fun OnlyIgnoredModule\.copy\(\): OnlyIgnoredModule = Inara\w*\(\s*\)""").containsMatchIn(generated), generated)
        assertEquals(1, Regex("""fun OnlyIgnoredModule\.copy\(""").findAll(generated).count(), generated)
    }
}
