@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the `shadow` extension function(s) generated alongside the implementation class(es)/
 * builder(s) - see [buildShadowFunctions]: the exact generated shape, then the runtime behaviour
 * that sets it apart from `copy` (the receiver never sees what the shadowing module overrides).
 */
class DIModuleShadowTest {

    @Test
    fun simpleModule_generatesNoArgAndMemberOverrideShadow() {
        val source = SourceFile.kotlin(
            "NetworkModule.kt",
            """
            package sample.shadow

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

        val generated = compilation.generatedSource("sample/shadow", "InaraNetworkModule.kt")

        assertEquals(2, Regex("""fun NetworkModule\.shadow\(""").findAll(generated).count(), generated)
        assertTrue("public fun NetworkModule.shadow(): NetworkModule = this" in generated, generated)

        // Always a delegate to the receiver, never its stored binding.
        assertTrue(
            "baseUrl: Binding<NetworkModule, Unit, String> = BindingImpls.Delegate(this@shadow::class) { this@shadow.baseUrl }" in generated,
            generated,
        )
        assertTrue(
            "httpClient: Binding<NetworkModule, Unit, HttpClient> = BindingImpls.Delegate(this@shadow::class) { this@shadow.httpClient() }" in generated,
            generated,
        )
        assertTrue(
            "context: Binding<NetworkModule, Int, Context> = BindingImpls.Delegate(this@shadow::class) { this@shadow.context(it) }" in generated,
            generated,
        )
        assertTrue("contextBinding = context" in generated, generated)
    }

    @Test
    fun memberlessModule_generatesOnlyNoArgShadow() {
        val source = SourceFile.kotlin(
            "EmptyModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/shadow", "InaraEmptyModule.kt")

        assertEquals(1, Regex("""fun EmptyModule\.shadow\(""").findAll(generated).count(), generated)
        assertTrue("public fun EmptyModule.shadow(): EmptyModule = this" in generated, generated)
    }

    @Test
    fun internalModule_generatesInternalShadow() {
        val source = SourceFile.kotlin(
            "HiddenModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule

            @DIModule
            internal interface HiddenModule {
                val name: String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/shadow", "InaraHiddenModule.kt")

        assertEquals(2, Regex("""internal fun HiddenModule\.shadow\(""").findAll(generated).count(), generated)
    }

    @Test
    fun suspendMember_shadowUsesSuspendDelegate() {
        val source = SourceFile.kotlin(
            "AsyncModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule

            @DIModule
            interface AsyncModule {
                suspend fun load(): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/shadow", "InaraAsyncModule.kt")

        assertTrue(
            "load: SuspendBinding<AsyncModule, Unit, String> = SuspendBindingImpls.SuspendDelegate(this@shadow::class) { this@shadow.load() }" in generated,
            generated,
        )
    }

    @Test
    fun multiParameterMember_shadowUnpacksTheArguments() {
        val source = SourceFile.kotlin(
            "MultiModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule

            @DIModule
            interface MultiModule {
                fun join(left: String, right: String): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/shadow", "InaraMultiModule.kt")

        assertTrue(
            "BindingImpls.Delegate(this@shadow::class) { this@shadow.join(left = it.left, right = it.right) }" in generated,
            generated,
        )
    }

    @Test
    fun shadow_hidesOverridesFromTheParent() {
        val source = SourceFile.kotlin(
            "GreetingModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.provider
            import org.kodein.di.inara.Binding.Bindings.single
            import org.kodein.di.inara.Binding.Bindings.value

            @DIModule
            interface GreetingModule {
                val name: String
                val hello: String
                val shout: String
            }

            object Harness {
                @JvmStatic
                fun run(): Array<Any?> {
                    val parent = GreetingModule(
                        name = value("John"),
                        hello = single { "Hello, ${'$'}name" },
                        shout = provider { "${'$'}name!" },
                    )
                    val child = parent.shadow(
                        name = value("Jane"),
                        shout = provider { "${'$'}name!!" },
                    )
                    val copied = parent.copy(name = value("Jane"))
                    return arrayOf(child.name, child.hello, child.shout, parent.name, parent.shout, copied.hello)
                }
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val harnessClass = compilation.classLoader.loadClass("sample.shadow.Harness")
        @Suppress("UNCHECKED_CAST")
        val results = harnessClass.getMethod("run").invoke(null) as Array<Any?>

        assertEquals("Jane", results[0])
        // Not overridden: evaluated by the parent, against the parent.
        assertEquals("Hello, John", results[1])
        // Overridden: runs against the child.
        assertEquals("Jane!!", results[2])
        assertEquals("John", results[3])
        assertEquals("John!", results[4])
        // Contrast: the `single` was already built (and memoized) by the parent via the shadow.
        assertEquals("Hello, John", results[5])
    }

    @Test
    fun shadow_keepsMemoizedBindingsWithTheParentAndChains() {
        val source = SourceFile.kotlin(
            "CounterModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.single
            import org.kodein.di.inara.Binding.Bindings.value

            @DIModule
            interface CounterModule {
                val name: String
                val service: Any
            }

            object Harness {
                @JvmStatic
                fun run(): Array<Any?> {
                    val parent = CounterModule(
                        name = value("a"),
                        service = single { Any() },
                    )
                    val child = parent.shadow(name = value("b"))
                    val grandChild = child.shadow(name = value("c"))
                    return arrayOf(grandChild.service === parent.service, grandChild.name, child.name)
                }
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val harnessClass = compilation.classLoader.loadClass("sample.shadow.Harness")
        @Suppress("UNCHECKED_CAST")
        val results = harnessClass.getMethod("run").invoke(null) as Array<Any?>

        assertEquals(true, results[0])
        assertEquals("c", results[1])
        assertEquals("b", results[2])
    }

    @Test
    fun shadow_defaultBodyMember_omittedDelegatesToParentAndNullRunsAgainstChild() {
        val source = SourceFile.kotlin(
            "DefaultsModule.kt",
            """
            package sample.shadow

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.value

            @DIModule
            interface DefaultsModule {
                val name: String
                fun greeting(): String = "Hello, ${'$'}name"
            }

            object Harness {
                @JvmStatic
                fun run(): Array<Any?> {
                    val parent = DefaultsModule(name = value("John"))
                    val omitted = parent.shadow(name = value("Jane"))
                    val explicitNull = parent.shadow(name = value("Jane"), greeting = null)
                    return arrayOf(omitted.greeting(), explicitNull.greeting())
                }
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val harnessClass = compilation.classLoader.loadClass("sample.shadow.Harness")
        @Suppress("UNCHECKED_CAST")
        val results = harnessClass.getMethod("run").invoke(null) as Array<Any?>

        assertEquals("Hello, John", results[0])
        assertEquals("Hello, Jane", results[1])
    }
}
