@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * One test per `@DIModule` pattern that must be rejected at compile time, verifying both that
 * compilation fails and that the diagnostic names the actual problem.
 */
class DIModuleValidationTest {

    private fun assertRejected(expectedMessageFragment: String, contents: String) {
        val compilation = compile(SourceFile.kotlin("Module.kt", contents))
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue(expectedMessageFragment in compilation.messages, compilation.messages)
    }

    @Test
    fun overloadedMembers_areRejected() {
        assertRejected(
            "Overloading is not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface OverloadModule {
                fun get(): String
                fun get(x: Int): String
            }
            """
        )
    }

    @Test
    fun memberNameEndingInBinding_isRejected() {
        assertRejected(
            "may not end with 'Binding'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BadNameModule {
                val fooBinding: String
            }
            """
        )
    }

    @Test
    fun varProperty_isRejected() {
        assertRejected(
            "var properties are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarModule {
                var value: String
            }
            """
        )
    }

    @Test
    fun nonInterface_isRejected() {
        assertRejected(
            "Only interfaces may be annotated",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            abstract class NotAnInterfaceModule {
                abstract val value: String
            }
            """
        )
    }

    @Test
    fun interfaceExtendingAnotherInterface_isRejected() {
        assertRejected(
            "may not extend other interfaces",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            interface BaseModule {
                val base: String
            }

            @DIModule
            interface ExtendingModule : BaseModule {
                val value: String
            }
            """
        )
    }

    @Test
    fun genericMember_isRejected() {
        assertRejected(
            "Generic members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface GenericMemberModule {
                fun <T> get(): T
            }
            """
        )
    }

    @Test
    fun extensionMember_isRejected() {
        assertRejected(
            "Extension members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ExtensionMemberModule {
                fun String.shout(): String
            }
            """
        )
    }

    @Test
    fun twoParameterMember_isRejected() {
        assertRejected(
            "more than one parameter",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface TwoParamModule {
                fun combine(a: Int, b: Int): Int
            }
            """
        )
    }

    @Test
    fun varargParameter_isRejected() {
        assertRejected(
            "vararg parameters are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarargModule {
                fun join(vararg parts: String): String
            }
            """
        )
    }
}
