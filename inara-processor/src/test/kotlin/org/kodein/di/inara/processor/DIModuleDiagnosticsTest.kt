@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a build reports when a module is invalid: Inara's own errors, and nothing else.
 *
 * Since KSP 2.3.13, when a processor reports an error during a round, KSP type-checks the sources of
 * that round and reports every compiler diagnostic as an error too - with an analysis that doesn't
 * see the files generated during that very round. Inara therefore reports its errors once all rounds
 * are over (see [DeferredErrorsLogger]), after KSP decided whether to type-check.
 */
class DIModuleDiagnosticsTest {

    @Test
    fun invalidModule_reportsOnlyInarasError() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.diagnostics

                import org.kodein.di.inara.Binding
                import org.kodein.di.inara.DIModule

                @DIModule
                interface NameModule {
                    val name: String
                }

                fun nameModule(): NameModule = NameModule(name = Binding.value("name"))

                @DIModule
                interface GreeterModule : @DIModule.Required NameModule {
                    val greeter: String
                }

                fun greeterModule(): GreeterModulePart = GreeterModulePart(greeter = Binding.single { "Hello, ${'$'}name" })

                @DIModule
                interface GreetingModule : @DIModule.Required NameModule {
                    val greeting: String
                }

                // GreetingModulePart is generated during the round that fails: a type-check of that
                // round wouldn't resolve it, nor anything inferred from it.
                fun greetingModule(): GreetingModulePart = GreetingModulePart(greeting = Binding.single { "Hi, ${'$'}name" })

                // Invalid: no parent provides NameModule, so no AppModule builder is generated.
                @DIModule(composed = true)
                interface AppModule : GreeterModule, GreetingModule

                val app: AppModule = AppModule(nameModule = nameModule(), greeterModule = greeterModule(), greetingModule = greetingModule())
                """
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue(
            "'GreeterModule' and 'GreetingModule' require 'NameModule', which no parent of 'AppModule' provides." in compilation.messages,
            compilation.messages,
        )
        for (noise in listOf("Unresolved reference", "Cannot infer type", "does not have constructors")) {
            assertFalse(noise in compilation.messages, compilation.messages)
        }
    }
}
