@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Golden test: verifies the shape of the debug `override fun toString()` generated on the
 * `Inara<Module>` implementation class - see [buildToStringFunction]/[memberSignature].
 *
 * Kotlinpoet renders a `%S`-embedded `\n` as a multi-line triple-quoted `trimMargin()` block rather
 * than an escaped `"\n"`, so assertions below match the literal `"<signature> => " + <binding> +`
 * fragment either side of that block instead of a full line.
 */
class DIModuleToStringTest {

    @Test
    fun everyMemberShape_rendersItsSignatureAndBindingProperty() {
        val source = SourceFile.kotlin(
            "AllShapesModule.kt",
            """
            package sample.tostring

            import org.kodein.di.inara.DIModule

            class Service

            @DIModule
            interface AllShapesModule {
                val name: String
                fun httpClient(): Service
                fun endpoint(path: String): String
                fun va(vararg str: String): String
                fun mult(s: List<String>, i: Int): Long
                suspend fun load(): Service
                fun greeting(): String = "Hello, ${'$'}name"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/tostring", "InaraAllShapesModule.kt")

        assertTrue("override fun toString(): String = " in generated, generated)
        assertTrue("|module AllShapesModule {" in generated, generated)

        assertTrue("\"    val name: String => \" + nameBinding +" in generated, generated)
        assertTrue("\"    fun httpClient(): Service => \" + httpClientBinding +" in generated, generated)
        assertTrue("\"    fun endpoint(path: String): String => \" + endpointBinding +" in generated, generated)
        assertTrue("\"    fun va(vararg str: String): String => \" + vaBinding +" in generated, generated)
        assertTrue("\"    fun mult(s: List<String>, i: Int): Long => \" + multBinding +" in generated, generated)
        assertTrue("\"    suspend fun load(): Service => \" + loadBinding +" in generated, generated)
        // A default-body member falls back to the `[default]` marker when its binding is `null`,
        // matching BindingImpls.Delegate/SuspendBindingImpls.SuspendDelegate's `[delegated to ...]`.
        assertTrue("\"    fun greeting(): String => \" + (greetingBinding ?: \"[default]\") +" in generated, generated)
    }

    @Test
    fun memberlessModule_rendersAnEmptyBody() {
        val source = SourceFile.kotlin(
            "EmptyModule.kt",
            """
            package sample.tostring

            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/tostring", "InaraEmptyModule.kt")

        assertTrue("override fun toString(): String = \"module EmptyModule {}\"" in generated, generated)
    }

    @Test
    fun inheritedMembers_areListedUnmarkedInAllMembersOrder() {
        val source = SourceFile.kotlin(
            "ComposedModules.kt",
            """
            package sample.tostring

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ParentModule { val a: String }

            @DIModule(composed = true)
            interface ChildModule : ParentModule { val b: String }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/tostring", "InaraChildModule.kt")

        assertTrue("|module ChildModule {" in generated, generated)
        assertTrue("\"    val a: String => \" + aBinding +" in generated, generated)
        assertTrue("\"    val b: String => \" + bBinding +" in generated, generated)
    }

    @Test
    fun userDeclaredToString_isNotDoubled() {
        val source = SourceFile.kotlin(
            "SelfDescribingModule.kt",
            """
            package sample.tostring

            import org.kodein.di.inara.DIModule

            @DIModule
            interface SelfDescribingModule {
                val name: String
                override fun toString(): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/tostring", "InaraSelfDescribingModule.kt")

        assertEquals(1, Regex("""override fun toString\(\)""").findAll(generated).count(), generated)
        assertTrue("this.toStringBinding.get(this, Unit)" in generated, generated)
    }
}
