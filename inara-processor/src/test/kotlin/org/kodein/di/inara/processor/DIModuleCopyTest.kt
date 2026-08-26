@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Golden test: verifies the exact shape of the `copy` extension function(s) generated alongside
 * the implementation class(es)/builder(s) - see [buildCopyFunctions].
 */
class DIModuleCopyTest {

    @Test
    fun simpleModule_generatesNoArgAndMemberOverrideCopy() {
        val source = SourceFile.kotlin(
            "GreetingModule.kt",
            """
            package sample.copy

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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy", "GeneratedNetworkModule.kt")

        assertEquals(2, Regex("""fun NetworkModule\.copy\(""").findAll(generated).count(), generated)

        assertTrue("public fun NetworkModule.copy(): NetworkModule = this" in generated, generated)

        assertTrue("@OptIn(InternalDIBindings::class)" in generated, generated)
        assertTrue(
            "baseUrl: Binding<NetworkModule, Unit, String> = BindingImpls.Delegate { this@copy.baseUrl }" in generated,
            generated,
        )
        assertTrue(
            "httpClient: Binding<NetworkModule, Unit, HttpClient> = BindingImpls.Delegate { this@copy.httpClient() }" in generated,
            generated,
        )
        assertTrue(
            "context: Binding<NetworkModule, Int, Context> = BindingImpls.Delegate { this@copy.context(it) }" in generated,
            generated,
        )
        assertTrue("baseUrlBinding = baseUrl" in generated, generated)
        assertTrue("httpClientBinding = httpClient" in generated, generated)
        assertTrue("contextBinding = context" in generated, generated)
    }

    @Test
    fun memberlessModule_generatesOnlyNoArgCopy() {
        val source = SourceFile.kotlin(
            "EmptyModule.kt",
            """
            package sample.copy

            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy", "GeneratedEmptyModule.kt")

        assertEquals(1, Regex("""fun EmptyModule\.copy\(""").findAll(generated).count(), generated)
        assertTrue("public fun EmptyModule.copy(): EmptyModule = this" in generated, generated)
    }

    @Test
    fun defaultBodyMember_copyParameterStaysNullableAndDefaultsToNull() {
        val source = SourceFile.kotlin(
            "DefaultsModule.kt",
            """
            package sample.copy

            import org.kodein.di.inara.DIModule

            @DIModule
            interface DefaultsModule {
                val name: String
                fun greeting(): String = "Hello, ${'$'}name"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy", "GeneratedDefaultsModule.kt")

        // name (no default implementation): frozen to its current value.
        assertTrue(
            "name: Binding<DefaultsModule, Unit, String> = BindingImpls.Delegate { this@copy.name }" in generated,
            generated,
        )

        // greeting (default implementation): same nullable-parameter-defaulting-to-null shape as
        // the builder function, so an omitted override still re-runs the interface's default body
        // - now against the *new* module, so it sees `name` above if that's also overridden in the
        // same call - rather than freezing the value it produced on the old module.
        // Appears twice: once as the builder function's parameter, once as the copy function's.
        assertEquals(
            2,
            Regex(Regex.escape("greeting: Binding<DefaultsModule, Unit, String>? = null")).findAll(generated).count(),
            generated,
        )

        // No BindingImpls.Delegate is ever built for `greeting` itself.
        assertTrue("this@copy.greeting()" !in generated, generated)
    }

    @Test
    fun suspendMember_copyUsesSuspendDelegate() {
        val source = SourceFile.kotlin(
            "AsyncModule.kt",
            """
            package sample.copy

            import org.kodein.di.inara.DIModule

            @DIModule
            interface AsyncModule {
                suspend fun load(): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy", "GeneratedAsyncModule.kt")

        assertTrue(
            "load: SuspendBinding<AsyncModule, Unit, String> = SuspendBindingImpls.SuspendDelegate { this@copy.load() }" in generated,
            generated,
        )
    }

    @Test
    fun internalModule_copyFunctionsAreInternal() {
        val source = SourceFile.kotlin(
            "InternalModule.kt",
            """
            package sample.copy

            import org.kodein.di.inara.DIModule

            @DIModule
            internal interface InternalModule {
                val secret: String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy", "GeneratedInternalModule.kt")

        assertTrue("internal fun InternalModule.copy(): InternalModule = this" in generated, generated)
        assertTrue(
            Regex("""internal fun InternalModule\.copy\(\s*secret""").containsMatchIn(generated),
            generated,
        )
    }

    @Test
    fun composedModule_generatesThreeCopyOverloads() {
        val source = SourceFile.kotlin(
            "ComposedModules.kt",
            """
            package sample.copy.inheritance

            import org.kodein.di.inara.DIModule

            class TypeA
            class TypeB
            class TypeC

            @DIModule
            interface Module1 { val a: TypeA }

            @DIModule
            interface Module2 { val b: TypeB }

            @DIModule
            interface Module3 : Module1, Module2 { val c: TypeC }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy/inheritance", "GeneratedModule3.kt")

        assertEquals(3, Regex("""fun Module3\.copy\(""").findAll(generated).count(), generated)

        assertTrue("public fun Module3.copy(): Module3 = this" in generated, generated)

        // Member-override overload: one parameter per member, inherited included, all on Module3.
        assertTrue(
            "a: Binding<Module3, Unit, TypeA> = BindingImpls.Delegate { this@copy.a }" in generated,
            generated,
        )
        assertTrue(
            "b: Binding<Module3, Unit, TypeB> = BindingImpls.Delegate { this@copy.b }" in generated,
            generated,
        )
        assertTrue(
            "c: Binding<Module3, Unit, TypeC> = BindingImpls.Delegate { this@copy.c }" in generated,
            generated,
        )

        // Module-override overload: one parameter per @DIModule parent, defaulting to (and
        // actually passing through) the receiver; own member still gets a delegate binding.
        assertTrue("module1: Module1 = this" in generated, generated)
        assertTrue("module2: Module2 = this" in generated, generated)
        val moduleOverrideCall = generated.substringAfterLast("GeneratedDelegatedModule3(")
        assertTrue("module1 = module1," in moduleOverrideCall, generated)
        assertTrue("module2 = module2," in moduleOverrideCall, generated)
        assertTrue("cBinding = BindingImpls.Delegate { this@copy.c }" in moduleOverrideCall, generated)
    }
}
