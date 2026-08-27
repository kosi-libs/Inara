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
 * accessors defaulted, an extension member with a default body, or a generic function with a
 * default body: none of these get a `Binding` parameter or an override anywhere. The generated
 * class simply never touches them, relying on ordinary Kotlin inheritance to resolve them to
 * whichever interface's default body applies - own, a `@DIModule` parent, or a plain supertype.
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedVarModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("override var value" !in generated, generated)
        assertTrue("private val extraBinding: Binding<VarModule, Unit, Int>," in generated, generated)
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedExtensionFunctionModule.kt")
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedExtensionPropertyModule.kt")
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedGenericModule.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("identity" !in generated, generated)
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedGenericExtensionModule.kt")
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedChildModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("private val extraBinding: Binding<ChildModule, Unit, Int>," in generated, generated)
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedModule1.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("private val extraBinding: Binding<Module1, Unit, Int>," in generated, generated)
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

        val generated = compilation.generatedSource("sample/ignored", "GeneratedOnlyIgnoredModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("public fun OnlyIgnoredModule(): OnlyIgnoredModule" in generated, generated)
        assertTrue("public fun OnlyIgnoredModule.copy(): OnlyIgnoredModule = this" in generated, generated)
        assertEquals(1, Regex("""fun OnlyIgnoredModule\.copy\(""").findAll(generated).count(), generated)
    }
}
