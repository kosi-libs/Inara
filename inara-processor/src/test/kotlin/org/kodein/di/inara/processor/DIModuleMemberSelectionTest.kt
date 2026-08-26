@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Confirms two member-selection rules: a member with a default body is left alone (no `Binding`
 * constructor parameter, no override generated for it), and `suspend` functions are generated as
 * `suspend` overrides.
 */
class DIModuleMemberSelectionTest {

    @Test
    fun memberWithDefaultBody_isSkipped() {
        val source = SourceFile.kotlin(
            "DefaultBodyModule.kt",
            """
            package sample.selection

            import org.kodein.di.inara.DIModule

            @DIModule
            interface DefaultBodyModule {
                val name: String
                fun greeting(): String = "Hello, ${'$'}name"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/selection", "GeneratedDefaultBodyModule.kt")
        assertFalse("greetingBinding" in generated, generated)
        assertFalse("override fun greeting" in generated, generated)
        assertTrue("nameBinding" in generated, generated)
    }

    @Test
    fun suspendFunction_generatesSuspendOverride() {
        val source = SourceFile.kotlin(
            "SuspendModule.kt",
            """
            package sample.selection

            import org.kodein.di.inara.DIModule

            @DIModule
            interface SuspendModule {
                suspend fun loadData(): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/selection", "GeneratedSuspendModule.kt")
        assertTrue("override suspend fun loadData(): String" in generated, generated)
        assertTrue("loadDataBinding.get(this, Unit)" in generated, generated)
    }
}
