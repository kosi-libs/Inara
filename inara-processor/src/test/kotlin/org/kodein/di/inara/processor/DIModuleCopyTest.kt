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

        val generated = compilation.generatedSource("sample/copy", "InaraNetworkModule.kt")

        assertEquals(2, Regex("""fun NetworkModule\.copy\(""").findAll(generated).count(), generated)

        assertTrue("public fun NetworkModule.copy(): NetworkModule = this" in generated, generated)

        assertTrue("@file:OptIn(InternalInaraGeneration::class)" in generated, generated)
        // Each parameter defaults to the receiver's own stored binding when it's the generated
        // implementation, falling back to a current-value delegate for any other implementation.
        assertTrue(
            "baseUrl: Binding<NetworkModule, Unit, String> = if (this@copy is InaraNetworkModule) this@copy.baseUrlBinding else BindingImpls.Delegate(this@copy::class) { this@copy.baseUrl }" in generated,
            generated,
        )
        assertTrue(
            "httpClient: Binding<NetworkModule, Unit, HttpClient> = if (this@copy is InaraNetworkModule) this@copy.httpClientBinding else BindingImpls.Delegate(this@copy::class) { this@copy.httpClient() }" in generated,
            generated,
        )
        assertTrue(
            "context: Binding<NetworkModule, Int, Context> = if (this@copy is InaraNetworkModule) this@copy.contextBinding else BindingImpls.Delegate(this@copy::class) { this@copy.context(it) }" in generated,
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

        val generated = compilation.generatedSource("sample/copy", "InaraEmptyModule.kt")

        assertEquals(1, Regex("""fun EmptyModule\.copy\(""").findAll(generated).count(), generated)
        assertTrue("public fun EmptyModule.copy(): EmptyModule = this" in generated, generated)
    }

    @Test
    fun defaultBodyMember_copyParameterStaysNullableAndPassesTheStoredBindingThrough() {
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

        val generated = compilation.generatedSource("sample/copy", "InaraDefaultsModule.kt")

        // name (no default implementation): its parameter defaults to the receiver's stored
        // binding, so omitting it changes nothing - even a not-yet-resolved single/multiplex keeps
        // its memo. A non-generated implementation falls back to a current-value delegate.
        assertTrue(
            "name: Binding<DefaultsModule, Unit, String> = if (this@copy is InaraDefaultsModule) this@copy.nameBinding else BindingImpls.Delegate(this@copy::class) { this@copy.name }" in generated,
            generated,
        )

        // greeting (default implementation): parameter is still nullable so a caller can pass an
        // explicit `null` to force the interface's own body back. Its default is the receiver's
        // stored binding, which is itself `null` when the member was never bound - the body then
        // re-runs against the *new* module, seeing `name` if that's overridden in the same call.
        assertTrue(
            "greeting: Binding<DefaultsModule, Unit, String>? = if (this@copy is InaraDefaultsModule) this@copy.greetingBinding else BindingImpls.Delegate(this@copy::class) { this@copy.greeting() }" in generated,
            generated,
        )

        // The builder function's parameter for a default-body member is unchanged: `? = null`.
        assertTrue("greeting: Binding<DefaultsModule, Unit, String>? = null" in generated, generated)
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

        val generated = compilation.generatedSource("sample/copy", "InaraAsyncModule.kt")

        assertTrue(
            "load: SuspendBinding<AsyncModule, Unit, String> = if (this@copy is InaraAsyncModule) this@copy.loadBinding else SuspendBindingImpls.SuspendDelegate(this@copy::class) { this@copy.load() }" in generated,
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

        val generated = compilation.generatedSource("sample/copy", "InaraInternalModule.kt")

        // The implementation class mirrors the interface's visibility - internal here, so it can't
        // expose the internal InternalModule supertype.
        assertTrue("@InternalInaraGeneration\ninternal class InaraInternalModule(" in generated, generated)
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

            @DIModule(composed = true)
            interface Module3 : Module1, Module2 { val c: TypeC }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy/inheritance", "InaraModule3.kt")

        assertEquals(3, Regex("""fun Module3\.copy\(""").findAll(generated).count(), generated)

        assertTrue("public fun Module3.copy(): Module3 = this" in generated, generated)

        // Member-override overload: one parameter per member, inherited included, all on Module3,
        // each a single-holder guard over the one implementation class.
        assertTrue(
            "a: Binding<Module3, Unit, TypeA> = if (this@copy is InaraModule3) this@copy.aBinding else BindingImpls.Delegate(this@copy::class) { this@copy.a }" in generated,
            generated,
        )
        assertTrue(
            "c: Binding<Module3, Unit, TypeC> = if (this@copy is InaraModule3) this@copy.cBinding else BindingImpls.Delegate(this@copy::class) { this@copy.c }" in generated,
            generated,
        )

        // Module-override overload: one parameter per @DIModule parent, defaulting to the receiver.
        // It builds the same InaraModule3; an inherited member takes its binding from the
        // (possibly swapped) parent, an own member from the receiver.
        assertTrue("public fun Module3.copy(module1: Module1 = this, module2: Module2 = this): Module3 = InaraModule3(" in generated, generated)
        assertTrue(
            Regex(
                Regex.escape("aBinding = when {") + "\\s*" +
                    Regex.escape("module1 !== this@copy -> if (module1 is InaraModule1) module1.aBinding else BindingImpls.Delegate(module1::class) { module1.a }") + "\\s*" +
                    Regex.escape("this@copy is InaraModule3 -> this@copy.aBinding") + "\\s*" +
                    Regex.escape("else -> BindingImpls.Delegate(this@copy::class) { this@copy.a }") + "\\s*\\}"
            ).containsMatchIn(generated),
            generated,
        )
        val moduleOverrideCall = generated.substringAfterLast("module1: Module1 = this")
        assertTrue("cBinding = if (this@copy is InaraModule3) this@copy.cBinding else BindingImpls.Delegate(this@copy::class) { this@copy.c }" in moduleOverrideCall, generated)
    }

    @Test
    fun plainModule_generatesOnlyTwoCopyOverloads() {
        val source = SourceFile.kotlin(
            "PlainInheritance.kt",
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

        val generated = compilation.generatedSource("sample/copy/inheritance", "InaraModule3.kt")

        assertTrue("InaraDelegatedModule3" !in generated, generated)
        assertEquals(2, Regex("""fun Module3\.copy\(""").findAll(generated).count(), generated)

        assertTrue("public fun Module3.copy(): Module3 = this" in generated, generated)
        // No delegated class here, so every member's default is a single-holder guard.
        assertTrue(
            "a: Binding<Module3, Unit, TypeA> = if (this@copy is InaraModule3) this@copy.aBinding else BindingImpls.Delegate(this@copy::class) { this@copy.a }" in generated,
            generated,
        )
        assertTrue(
            "b: Binding<Module3, Unit, TypeB> = if (this@copy is InaraModule3) this@copy.bBinding else BindingImpls.Delegate(this@copy::class) { this@copy.b }" in generated,
            generated,
        )
        assertTrue(
            "c: Binding<Module3, Unit, TypeC> = if (this@copy is InaraModule3) this@copy.cBinding else BindingImpls.Delegate(this@copy::class) { this@copy.c }" in generated,
            generated,
        )
    }
}
