@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the generated shape for a function member with more than one parameter (vararg
 * included): a `<Module>BindingArguments` container holding one `data class` per such member,
 * used as that member's `Binding` argument type. A lone vararg parameter needs no data class - the
 * array type it's seen as inside the function body doubles as the argument type directly.
 */
class DIModuleMultiParamTest {

    @Test
    fun reproducesTheUserExample() {
        val source = SourceFile.kotlin(
            "MultiParam.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface Module {
                fun va(vararg str: String): String
                fun mult(s: List<String>, i: Int): Long
                fun both(vararg ids: Int, flag: Boolean = false): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraModule.kt")

        // Container: private constructor, one data class per multi-param member, no defaults, and
        // @Suppress("ArrayInDataClass") only on the one with an array field.
        assertTrue("public class ModuleBindingArguments private constructor()" in generated, generated)
        assertTrue("public data class Mult(" in generated, generated)
        assertTrue("val s: List<String>," in generated, generated)
        assertTrue("val i: Int," in generated, generated)
        assertTrue("@Suppress(\"ArrayInDataClass\")" !in generated.substringBefore("public data class Mult("), generated)
        assertTrue("@Suppress(\"ArrayInDataClass\")\n  public data class Both(" in generated, generated)
        assertTrue("val ids: IntArray," in generated, generated)
        assertTrue("val flag: Boolean," in generated, generated)
        assertTrue("val flag: Boolean = false" !in generated, generated)

        // va: single vararg, no data class - the array type is the Binding argument type directly.
        assertTrue("public val vaBinding: Binding<Module, Array<out String>, String>," in generated, generated)
        assertTrue("override fun va(vararg str: String): String = this.vaBinding.get(this, str)" in generated, generated)

        // mult: two non-vararg params, positional args-object construction.
        assertTrue("public val multBinding: Binding<Module, ModuleBindingArguments.Mult, Long>," in generated, generated)
        assertTrue(
            "override fun mult(s: List<String>, i: Int): Long = this.multBinding.get(this, ModuleBindingArguments.Mult(s, i))" in generated,
            generated,
        )

        // both: vararg + named param, positional args-object construction, no default repeated on
        // the override.
        assertTrue("public val bothBinding: Binding<Module, ModuleBindingArguments.Both, String>," in generated, generated)
        assertTrue("override fun both(vararg ids: Int, flag: Boolean): String" in generated, generated)
        assertTrue("this.bothBinding.get(this, ModuleBindingArguments.Both(ids, flag))" in generated, generated)

        // Builder signature.
        assertTrue("va: Binding<Module, Array<out String>, String>," in generated, generated)
        assertTrue("mult: Binding<Module, ModuleBindingArguments.Mult, Long>," in generated, generated)
        assertTrue("both: Binding<Module, ModuleBindingArguments.Both, String>," in generated, generated)

        // copy delegate reconstruction: named args, no spread anywhere.
        assertTrue("BindingImpls.Delegate(this@copy::class) { this@copy.va(str = it) }" in generated, generated)
        assertTrue("BindingImpls.Delegate(this@copy::class) { this@copy.mult(s = it.s, i = it.i) }" in generated, generated)
        assertTrue("BindingImpls.Delegate(this@copy::class) { this@copy.both(ids = it.ids, flag = it.flag) }" in generated, generated)
        assertTrue("*" !in generated, generated)
    }

    @Test
    fun primitiveVarargUsesDedicatedArrayType() {
        val source = SourceFile.kotlin(
            "PrimitiveVararg.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface PrimitiveVarargModule {
                fun ints(vararg values: Int): Int
                fun longs(vararg values: Long): Long
                fun booleans(vararg values: Boolean): Boolean
                fun strings(vararg values: String): String
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraPrimitiveVarargModule.kt")
        assertTrue("Binding<PrimitiveVarargModule, IntArray, Int>" in generated, generated)
        assertTrue("Binding<PrimitiveVarargModule, LongArray, Long>" in generated, generated)
        assertTrue("Binding<PrimitiveVarargModule, BooleanArray, Boolean>" in generated, generated)
        assertTrue("Binding<PrimitiveVarargModule, Array<out String>, String>" in generated, generated)
    }

    @Test
    fun suspendMultiParamFunction_getsSuspendBindingAndArgsClass() {
        val source = SourceFile.kotlin(
            "SuspendMultiParam.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface SuspendMultiParamModule {
                suspend fun combine(a: Int, b: Int): Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraSuspendMultiParamModule.kt")
        assertTrue("public data class Combine(" in generated, generated)
        assertTrue("public val combineBinding:" in generated, generated)
        assertTrue(
            "SuspendBinding<SuspendMultiParamModule, SuspendMultiParamModuleBindingArguments.Combine, Int>," in generated,
            generated,
        )
        assertTrue("override suspend fun combine(a: Int, b: Int): Int" in generated, generated)
    }

    @Test
    fun defaultBodiedMultiParamFunction_getsOptionalBindingAndNamedSuperCall() {
        val source = SourceFile.kotlin(
            "DefaultMultiParam.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface DefaultMultiParamModule {
                fun both(vararg ids: Int, flag: Boolean = false): String = "${'$'}ids ${'$'}flag"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraDefaultMultiParamModule.kt")
        assertTrue("public val bothBinding:" in generated, generated)
        assertTrue(
            "Binding<DefaultMultiParamModule, DefaultMultiParamModuleBindingArguments.Both, String>?," in generated,
            generated,
        )
        assertTrue("super<DefaultMultiParamModule>.both(ids = ids, flag = flag)" in generated, generated)
        assertTrue(
            "both: Binding<DefaultMultiParamModule, DefaultMultiParamModuleBindingArguments.Both, String>? = null" in generated,
            generated,
        )
    }

    @Test
    fun multiParamMember_inheritedFromDIModuleParent_regeneratesOwnArgsClass() {
        val source = SourceFile.kotlin(
            "InheritedMultiParam.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                fun mult(s: List<String>, i: Int): Long
            }

            @DIModule
            interface ChildModule : BaseModule {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generatedBase = compilation.generatedSource("sample/multiparam", "InaraBaseModule.kt")
        assertTrue("public class BaseModuleBindingArguments private constructor()" in generatedBase, generatedBase)

        val generatedChild = compilation.generatedSource("sample/multiparam", "InaraChildModule.kt")
        assertTrue("public class ChildModuleBindingArguments private constructor()" in generatedChild, generatedChild)
        assertTrue("public data class Mult(" in generatedChild, generatedChild)
        assertTrue(
            "public val multBinding: Binding<ChildModule, ChildModuleBindingArguments.Mult, Long>," in generatedChild,
            generatedChild,
        )
    }

    @Test
    fun composedModule_inheritedMultiParamMember_alwaysForwardsThroughDelegate() {
        val source = SourceFile.kotlin(
            "ComposedMultiParam.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                fun single(x: Int): Int
                fun mult(s: List<String>, i: Int): Long
            }

            @DIModule(composed = true)
            interface ChildModule : BaseModule {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraChildModule.kt")

        // `single` (one parameter, module-independent argument type) is carried across as-is.
        assertTrue(
            "singleBinding = if (baseModule is InaraBaseModule) baseModule.singleBinding else BindingImpls.Delegate(baseModule::class) { baseModule.single(it) }" in generated,
            generated,
        )
        // `mult` (2+ parameters -> a per-module ChildModuleBindingArguments.Mult) can't be handed
        // across at all - the from-parents builder always forwards through a delegate.
        assertTrue(
            "multBinding = BindingImpls.Delegate(baseModule::class) { baseModule.mult(s = it.s, i = it.i) }" in generated,
            generated,
        )
        assertTrue("if (baseModule is InaraBaseModule) baseModule.multBinding" !in generated, generated)
    }

    @Test
    fun multiParamMember_inheritedFromPlainSupertype_regeneratesOwnArgsClass() {
        val source = SourceFile.kotlin(
            "InheritedMultiParamPlain.kt",
            """
            package sample.multiparam

            import org.kodein.di.inara.DIModule

            interface Api {
                fun mult(s: List<String>, i: Int): Long
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/multiparam", "InaraModule1.kt")
        assertTrue("public class Module1BindingArguments private constructor()" in generated, generated)
        assertTrue("public data class Mult(" in generated, generated)
        assertTrue("public val multBinding: Binding<Module1, Module1BindingArguments.Mult, Long>," in generated, generated)
    }
}
