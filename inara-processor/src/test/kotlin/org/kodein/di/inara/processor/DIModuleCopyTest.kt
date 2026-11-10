@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Golden test: verifies the exact shape of the `copy<Module>` extension function(s) generated
 * alongside the implementation class(es)/builder(s), and of their deprecated `copy` counterparts -
 * see [buildCopyFunctions].
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

        assertEquals(2, Regex("""fun NetworkModule\.copyNetworkModule\(""").findAll(generated).count(), generated)

        assertTrue(Regex("""public fun NetworkModule\.copyNetworkModule\(\): NetworkModule = InaraNetworkModule\(\s*baseUrlBinding = if \(this@copyNetworkModule is InaraNetworkModule\) BindingImpls\.copyOf\(this@copyNetworkModule\.baseUrlBinding\)""").containsMatchIn(generated), generated)

        assertTrue("@file:OptIn(InternalInaraGeneration::class)" in generated, generated)
        // Each parameter defaults to the receiver's own stored binding when it's the generated
        // implementation, falling back to a current-value delegate for any other implementation.
        assertTrue(
            "baseUrl: Binding<NetworkModule, Unit, String> = if (this@copyNetworkModule is InaraNetworkModule) BindingImpls.copyOf(this@copyNetworkModule.baseUrlBinding) else BindingImpls.Delegate(this@copyNetworkModule::class) { this@copyNetworkModule.baseUrl }" in generated,
            generated,
        )
        assertTrue(
            "httpClient: Binding<NetworkModule, Unit, HttpClient> = if (this@copyNetworkModule is InaraNetworkModule) BindingImpls.copyOf(this@copyNetworkModule.httpClientBinding) else BindingImpls.Delegate(this@copyNetworkModule::class) { this@copyNetworkModule.httpClient() }" in generated,
            generated,
        )
        assertTrue(
            "context: Binding<NetworkModule, Int, Context> = if (this@copyNetworkModule is InaraNetworkModule) BindingImpls.copyOf(this@copyNetworkModule.contextBinding) else BindingImpls.Delegate(this@copyNetworkModule::class) { this@copyNetworkModule.context(it) }" in generated,
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

        assertEquals(1, Regex("""fun EmptyModule\.copyEmptyModule\(""").findAll(generated).count(), generated)
        assertTrue(Regex("""public fun EmptyModule\.copyEmptyModule\(\): EmptyModule = InaraEmptyModule\(\s*\)""").containsMatchIn(generated), generated)
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
            "name: Binding<DefaultsModule, Unit, String> = if (this@copyDefaultsModule is InaraDefaultsModule) BindingImpls.copyOf(this@copyDefaultsModule.nameBinding) else BindingImpls.Delegate(this@copyDefaultsModule::class) { this@copyDefaultsModule.name }" in generated,
            generated,
        )

        // greeting (default implementation): parameter is still nullable so a caller can pass an
        // explicit `null` to force the interface's own body back. Its default is the receiver's
        // stored binding, which is itself `null` when the member was never bound - the body then
        // re-runs against the *new* module, seeing `name` if that's overridden in the same call.
        assertTrue(
            "greeting: Binding<DefaultsModule, Unit, String>? = if (this@copyDefaultsModule is InaraDefaultsModule) BindingImpls.copyOf(this@copyDefaultsModule.greetingBinding) else BindingImpls.Delegate(this@copyDefaultsModule::class) { this@copyDefaultsModule.greeting() }" in generated,
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
            "load: SuspendBinding<AsyncModule, Unit, String> = if (this@copyAsyncModule is InaraAsyncModule) SuspendBindingImpls.copyOf(this@copyAsyncModule.loadBinding) else SuspendBindingImpls.SuspendDelegate(this@copyAsyncModule::class) { this@copyAsyncModule.load() }" in generated,
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
        assertTrue(Regex("""internal fun InternalModule\.copyInternalModule\(\): InternalModule = InaraInternalModule\(""").containsMatchIn(generated), generated)
        assertTrue(
            Regex("""internal fun InternalModule\.copyInternalModule\(\s*secret""").containsMatchIn(generated),
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

        assertEquals(3, Regex("""fun Module3\.copyModule3\(""").findAll(generated).count(), generated)

        assertTrue("public fun Module3.copyModule3(): Module3 = InaraModule3(" in generated, generated)

        // Member-override overload: one parameter per member, inherited included, all on Module3,
        // each a single-holder guard over the one implementation class.
        assertTrue(
            "a: Binding<Module3, Unit, TypeA> = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.aBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.a }" in generated,
            generated,
        )
        assertTrue(
            "c: Binding<Module3, Unit, TypeC> = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.cBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.c }" in generated,
            generated,
        )

        // Module-override overload: one parameter per @DIModule parent, defaulting to the receiver.
        // It builds the same InaraModule3; an inherited member takes its binding from the
        // (possibly swapped) parent, an own member from the receiver.
        assertTrue("public fun Module3.copyModule3(module1: Module1 = this, module2: Module2 = this): Module3 = InaraModule3(" in generated, generated)
        assertTrue(
            Regex(
                Regex.escape("aBinding = when {") + "\\s*" +
                    Regex.escape("module1 !== this@copyModule3 -> if (module1 is InaraModule1) BindingImpls.copyOf(module1.aBinding) else BindingImpls.Delegate(module1::class) { module1.a }") + "\\s*" +
                    Regex.escape("this@copyModule3 is InaraModule3 -> BindingImpls.copyOf(this@copyModule3.aBinding)") + "\\s*" +
                    Regex.escape("else -> BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.a }") + "\\s*\\}"
            ).containsMatchIn(generated),
            generated,
        )
        val moduleOverrideCall = generated.substringAfter("copyModule3(module1: Module1 = this").substringBefore("@Deprecated")
        assertTrue("cBinding = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.cBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.c }" in moduleOverrideCall, generated)
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
        assertEquals(2, Regex("""fun Module3\.copyModule3\(""").findAll(generated).count(), generated)

        assertTrue("public fun Module3.copyModule3(): Module3 = InaraModule3(" in generated, generated)
        // No delegated class here, so every member's default is a single-holder guard.
        assertTrue(
            "a: Binding<Module3, Unit, TypeA> = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.aBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.a }" in generated,
            generated,
        )
        assertTrue(
            "b: Binding<Module3, Unit, TypeB> = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.bBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.b }" in generated,
            generated,
        )
        assertTrue(
            "c: Binding<Module3, Unit, TypeC> = if (this@copyModule3 is InaraModule3) BindingImpls.copyOf(this@copyModule3.cBinding) else BindingImpls.Delegate(this@copyModule3::class) { this@copyModule3.c }" in generated,
            generated,
        )
    }

    private fun runHarness(source: SourceFile, pkg: String): Array<Any?> {
        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
        val harnessClass = compilation.classLoader.loadClass("$pkg.Harness")
        @Suppress("UNCHECKED_CAST")
        return harnessClass.getMethod("run").invoke(null) as Array<Any?>
    }

    @Test
    fun copy_givesTheCopyItsOwnSingles() {
        val results = runHarness(
            SourceFile.kotlin(
                "CopyBehaviour.kt",
                """
                package sample.copybehaviour

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.single
                import org.kodein.di.inara.Binding.Bindings.value
                import org.kodein.di.inara.Binding.Bindings.multiplex
                import org.kodein.di.inara.Binding.Bindings.provider

                @DIModule
                interface MyModule {
                    val name: String
                    val hello: String
                    val shared: Any
                    fun tagged(id: Int): Any
                    fun greeting(): String = "Hi, ${'$'}name"
                }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val parent = MyModule(
                            name = value("John"),
                            hello = single { "Hello, ${'$'}name" },
                            shared = single { Any() },
                            tagged = multiplex { _: Int -> single { Any() } },
                        )
                        val builtBefore = parent.shared
                        val child = parent.copyMyModule(name = value("Jane"))
                        val again = parent.copyMyModule()
                        return arrayOf(
                            child.hello, parent.hello,
                            builtBefore === child.shared, builtBefore === parent.shared,
                            child.shared === child.shared,
                            parent.tagged(1) === child.tagged(1), child.tagged(1) === child.tagged(1),
                            again.shared === parent.shared, again !== parent, again.name,
                            child.greeting(),
                        )
                    }
                }
                """
            ),
            "sample.copybehaviour",
        )

        assertEquals("Hello, Jane", results[0])
        assertEquals("Hello, John", results[1])
        assertEquals(false, results[2]) // a single built before the copy is rebuilt by the copy
        assertEquals(true, results[3])
        assertEquals(true, results[4])
        assertEquals(false, results[5]) // multiplex caches are separate
        assertEquals(true, results[6])
        assertEquals(false, results[7]) // copy() with no argument is a fresh module too
        assertEquals(true, results[8])
        assertEquals("John", results[9])
        assertEquals("Hi, Jane", results[10]) // a default-body member stays unbound and re-runs
    }

    @Test
    fun parentSwapCopy_copiesTheNewParentsBindingsButTheComposedBuilderSharesThem() {
        val results = runHarness(
            SourceFile.kotlin(
                "CopyComposed.kt",
                """
                package sample.copycomposed

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.single

                @DIModule
                interface Greeter { val service: Any }

                @DIModule(composed = true)
                interface App : Greeter { val other: Any }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val greeter = Greeter(service = single { Any() })
                        val app = App(greeter, other = single { Any() })
                        val swapped = app.copyApp(greeter = greeter)
                        return arrayOf(app.service === greeter.service, swapped.service === greeter.service, swapped.service === app.service)
                    }
                }
                """
            ),
            "sample.copycomposed",
        )

        assertEquals(true, results[0]) // the composed builder shares the parent's binding
        assertEquals(false, results[1]) // the swapped-in parent keeps its singles to itself
        assertEquals(false, results[2])
    }

    @Test
    fun formerCopy_isStillGeneratedDeprecatedWithAnErrorPointingToCopyModule() {
        val compilation = compile(
            SourceFile.kotlin(
                "App.kt",
                """
                package sample.copy.deprecated

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Greeter { val greeting: String }

                @DIModule(composed = true)
                interface App : Greeter { val name: String }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/copy/deprecated", "InaraApp.kt")
        val message = "message = \"'copy' is renamed 'copyApp': unlike 'copy', it can never be ambiguous with a parent module's " +
            "function. The next version of Inara will no longer generate 'copy'.\""
        val import = "\"sample.copy.deprecated.copyApp\""
        for (replacement in listOf("copyApp()", "copyApp(greeting = greeting, name = name)", "copyApp(greeter = greeter)")) {
            assertTrue(
                Regex(
                    Regex.escape("@Deprecated(") + "\\s*" + Regex.escape(message) + ",\\s*" +
                        Regex.escape("replaceWith = ReplaceWith(\"$replacement\", $import),") + "\\s*" +
                        Regex.escape("level = DeprecationLevel.ERROR,") + "\\s*\\)\\s*" + Regex.escape("public fun App.copy(")
                ).containsMatchIn(generated),
                "$replacement\n$generated",
            )
        }
        assertEquals(3, Regex("""fun App\.copy\(""").findAll(generated).count(), generated)
        assertEquals(3, Regex("""fun App\.copyApp\(""").findAll(generated).count(), generated)
    }

    @Test
    fun callingFormerCopy_isACompileError() {
        val compilation = compile(
            SourceFile.kotlin(
                "Caller.kt",
                """
                package sample.copy.deprecated

                import org.kodein.di.inara.Binding
                import org.kodein.di.inara.DIModule

                @DIModule
                interface Caller { val name: String }

                fun use(caller: Caller) = caller.copy(name = Binding.value("x"))
                """
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue("'copy' is renamed 'copyCaller'" in compilation.messages, compilation.messages)
    }

    @Test
    fun copyOverridingOnlyInheritedMembers_needsNoTypeHint() {
        // With every module's copy named `copy`, this call matched the parent's `copy` as well as the
        // child's, and `Binding.value`'s module type could not be inferred.
        val results = runHarness(
            SourceFile.kotlin(
                "Inherited.kt",
                """
                package sample.copyinherited

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.provider
                import org.kodein.di.inara.Binding.Bindings.value

                @DIModule
                interface ServerModule { val server: String; val port: Int }

                @DIModule
                interface AppModule : ServerModule { val name: String }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val app = AppModule(server = value("s"), port = value(80), name = value("app"))
                        val copied: AppModule = app.copyAppModule(server = provider { "${'$'}name:${'$'}port" })
                        val shadowed: AppModule = app.shadowAppModule(port = value(8080))
                        val asParent: ServerModule = app.copyServerModule(server = value("p"))
                        return arrayOf(copied.server, shadowed.port, asParent.server)
                    }
                }
                """
            ),
            "sample.copyinherited",
        )

        assertEquals("app:80", results[0])
        assertEquals(8080, results[1])
        assertEquals("p", results[2])
    }

    @Test
    fun membersNamedCopyAndShadow_areAllowedAndHideTheDeprecatedFunctions() {
        val results = runHarness(
            SourceFile.kotlin(
                "Members.kt",
                """
                package sample.copymembers

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.provider
                import org.kodein.di.inara.Binding.Bindings.value

                @DIModule
                interface DocumentModule {
                    val copy: String
                    fun shadow(): String
                }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val module = DocumentModule(copy = value("original"), shadow = provider { "${'$'}copy-shade" })
                        val copied = module.copyDocumentModule(copy = value("copied"))
                        val shadowed = module.shadowDocumentModule(shadow = provider { "dark" })
                        return arrayOf(module.copy, module.shadow(), copied.copy, copied.shadow(), shadowed.copy, shadowed.shadow())
                    }
                }
                """
            ),
            "sample.copymembers",
        )

        assertEquals(listOf("original", "original-shade", "copied", "copied-shade", "original", "dark"), results.toList())
    }
}
