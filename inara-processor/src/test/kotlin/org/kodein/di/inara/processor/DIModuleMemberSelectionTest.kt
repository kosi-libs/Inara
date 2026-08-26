@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Confirms two member-selection rules: a member with a default body gets an optional, nullable
 * `Binding` parameter that falls back to the interface's own default implementation, and
 * `suspend` functions are generated as `suspend` overrides.
 */
class DIModuleMemberSelectionTest {

    @Test
    fun memberWithDefaultBody_getsOptionalBinding() {
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
        assertTrue("nameBinding" in generated, generated)
        assertTrue(
            "private val greetingBinding: Binding<DefaultBodyModule, Unit, String>?" in generated,
            generated,
        )
        assertTrue("override fun greeting" in generated, generated)
        assertTrue("super<DefaultBodyModule>.greeting()" in generated, generated)
        assertTrue(
            "greeting: Binding<DefaultBodyModule, Unit, String>? = null" in generated,
            generated,
        )
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
        assertTrue("this.loadDataBinding.get(this, Unit)" in generated, generated)
    }
}
