@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Golden test: verifies the exact shape of the code generated for the `@DIModule` interface
 * shown in the processor's documentation - a `val` property, a zero-arg function, and a one-arg
 * function.
 */
class DIModuleGenerationTest {

    private val networkModuleSource = SourceFile.kotlin(
        "NetworkModule.kt",
        """
        package sample

        import org.kodein.di.inara.DIModule

        class HttpClient
        class Context

        @DIModule
        interface NetworkModule {
            val baseUrl: String
            fun httpClient(): HttpClient
            fun context(id: Int): Context
        }
        """
    )

    @Test
    fun networkModule_compilesSuccessfully() {
        val compilation = compile(networkModuleSource)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun networkModule_generatesExpectedShape() {
        val compilation = compile(networkModuleSource)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample", "InaraNetworkModule.kt")

        assertTrue("@file:OptIn(InternalInaraGeneration::class)" in generated, generated)
        assertTrue("@InternalInaraGeneration\npublic class InaraNetworkModule(" in generated, generated)
        assertTrue("public val baseUrlBinding: Binding<NetworkModule, Unit, String>" in generated, generated)
        assertTrue("public val httpClientBinding: Binding<NetworkModule, Unit, HttpClient>" in generated, generated)
        assertTrue("public val contextBinding: Binding<NetworkModule, Int, Context>" in generated, generated)
        assertTrue(": NetworkModule" in generated, generated)
        assertTrue("override val baseUrl: String" in generated, generated)
        assertTrue("this.baseUrlBinding.get(this, Unit)" in generated, generated)
        assertTrue("override fun httpClient(): HttpClient" in generated, generated)
        assertTrue("this.httpClientBinding.get(this, Unit)" in generated, generated)
        assertTrue("override fun context(id: Int): Context" in generated, generated)
        assertTrue("this.contextBinding.get(this, id)" in generated, generated)

        assertTrue("fun NetworkModule(" in generated, generated)
        assertTrue("baseUrl: Binding<NetworkModule, Unit, String>" in generated, generated)
        assertTrue("httpClient: Binding<NetworkModule, Unit, HttpClient>" in generated, generated)
        assertTrue("context: Binding<NetworkModule, Int, Context>" in generated, generated)
        assertTrue("baseUrlBinding = baseUrl" in generated, generated)
        assertTrue("httpClientBinding = httpClient" in generated, generated)
        assertTrue("contextBinding = context" in generated, generated)
    }
}
