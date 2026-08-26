@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the generated code actually behaves as documented at runtime: values come from the
 * bindings passed to the builder function, `this` (the module instance) is forwarded to
 * `Binding.get` so a provider can read sibling members, and a factory receives its argument.
 */
class DIModuleBehaviourTest {

    @Test
    fun generatedModule_delegatesToBindingsAtRuntime() {
        val source = SourceFile.kotlin(
            "GreetingModule.kt",
            """
            package sample.behaviour

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.factory
            import org.kodein.di.inara.Binding.Bindings.provider
            import org.kodein.di.inara.Binding.Bindings.value

            @DIModule
            interface GreetingModule {
                val name: String
                fun greeting(): String
                fun shout(word: String): String
            }

            object Harness {
                @JvmStatic
                fun run(): Array<Any?> {
                    val module = GreetingModule(
                        name = value("World"),
                        greeting = provider { "Hello, ${'$'}name" },
                        shout = factory { word -> "${'$'}word!!!" },
                    )
                    return arrayOf(module.name, module.greeting(), module.shout("hi"))
                }
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val harnessClass = compilation.classLoader.loadClass("sample.behaviour.Harness")
        @Suppress("UNCHECKED_CAST")
        val results = harnessClass.getMethod("run").invoke(null) as Array<Any?>

        assertEquals("World", results[0])
        assertEquals("Hello, World", results[1])
        assertEquals("hi!!!", results[2])
    }

    @Test
    fun defaultImplementation_isKeptUnlessOverridden() {
        val source = SourceFile.kotlin(
            "DefaultsModule.kt",
            """
            package sample.behaviour

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.value

            @DIModule
            interface DefaultsModule {
                val name: String
                val greeting: String? get() = "Hello, ${'$'}name"
                fun shout(): String = "${'$'}{greeting}!!!"
            }

            object Harness {
                @JvmStatic
                fun run(): Array<Any?> {
                    // No greeting/shout bindings: the interface's own defaults run, and they see
                    // the bound `name`.
                    val defaulted = DefaultsModule(name = value("World"))

                    // greeting bound to a literal null: must NOT fall back to the default, since
                    // the fallback branches on the binding being absent, not on the value being
                    // null.
                    val overridden = DefaultsModule(
                        name = value("World"),
                        greeting = value(null),
                        shout = value("overridden"),
                    )

                    return arrayOf(
                        defaulted.greeting, defaulted.shout(),
                        overridden.greeting, overridden.shout(),
                    )
                }
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val harnessClass = compilation.classLoader.loadClass("sample.behaviour.Harness")
        @Suppress("UNCHECKED_CAST")
        val results = harnessClass.getMethod("run").invoke(null) as Array<Any?>

        assertEquals("Hello, World", results[0])
        assertEquals("Hello, World!!!", results[1])
        assertEquals(null, results[2])
        assertEquals("overridden", results[3])
    }
}
