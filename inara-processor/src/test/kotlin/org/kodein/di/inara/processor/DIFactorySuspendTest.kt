@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies `@DIFactory`'s `suspend` argument (`Suspend.Forbid`/`Allow`/`Force`): whether a
 * `suspend` arg-function or module member is a valid provider at all, and whether the generated
 * builder function itself ends up `suspend`.
 */
class DIFactorySuspendTest {

    @Test
    fun allow_suspendModuleMemberResolvedAutomatically_generatedFunctionIsSuspend() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface TokenModule {
                suspend fun token(): String
            }

            class Session(val token: String)

            @DIFactory(builds = Session::class, from = TokenModule::class)
            internal object SessionDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraSessionDIFactory.kt")
        assertTrue("public suspend fun Session(module: TokenModule): Session" in generated, generated)
        assertTrue("token = module.token()," in generated, generated)
    }

    @Test
    fun allow_noSuspendProviderAnywhere_generatedFunctionStaysNonSuspend() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("suspend" !in generated, generated)
    }

    @Test
    fun allow_suspendArgFunction_generatedFunctionIsSuspend() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                suspend fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public suspend fun Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("greeting = module.argGreeting()," in generated, generated)
    }

    @Test
    fun force_noSuspendProviderAnywhere_generatedFunctionIsSuspendAnyway() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIFactory.Suspend
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, suspend = Suspend.Force)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public suspend fun Greeter(module: GreetingModule): Greeter" in generated, generated)
    }

    @Test
    fun forbid_onlyNonSuspendProvidersAvailable_compilesNonSuspend() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIFactory.Suspend
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, suspend = Suspend.Forbid)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("suspend" !in generated, generated)
    }

    @Test
    fun forbid_defaultedParameterWhoseOnlyCandidateIsSuspend_fallsBackToTheDefault() {
        // Under Forbid the suspend member is not a candidate; for a non-defaulted parameter that
        // would be "Cannot resolve argument", but `verbose` has a default, so it is simply omitted
        // and the generated function stays non-suspend.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIFactory.Suspend
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                suspend fun verbose(): Boolean
            }

            class Greeter(val greeting: String, val verbose: Boolean = false)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, suspend = Suspend.Forbid)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("suspend" !in generated, generated)
        assertTrue("verbose" !in generated, generated)
    }
}
