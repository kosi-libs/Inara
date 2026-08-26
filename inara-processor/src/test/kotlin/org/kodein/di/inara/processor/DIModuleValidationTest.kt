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
    fun memberNamedCopy_propertyIsRejected() {
        assertRejected(
            "may not be named 'copy'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface CopyPropertyModule {
                val copy: String
            }
            """
        )
    }

    @Test
    fun memberNamedCopy_functionIsRejected() {
        assertRejected(
            "may not be named 'copy'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface CopyFunctionModule {
                fun copy(): String
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
    fun interfaceExtendingNonDIModuleInterface_isRejected() {
        assertRejected(
            "may only extend other @DIModule interfaces",
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
    fun diamondInheritance_isRejected() {
        assertRejected(
            "is inherited more than once",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                val base: String
            }

            @DIModule
            interface Module1 : BaseModule

            @DIModule
            interface Module2 : BaseModule

            @DIModule
            interface DiamondModule : Module1, Module2 {
                val value: String
            }
            """
        )
    }

    @Test
    fun duplicateMemberNameAcrossParents_isRejected() {
        assertRejected(
            "is already declared in",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface Module1 {
                val shared: String
            }

            @DIModule
            interface Module2 {
                val shared: Int
            }

            @DIModule
            interface CombinedModule : Module1, Module2
            """
        )
    }

    @Test
    fun overrideOfInheritedMember_isRejected() {
        assertRejected(
            "is already declared in",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                val name: String
            }

            @DIModule
            interface OverridingModule : BaseModule {
                override val name: String
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

    // Every rule above also applies to a member with a default implementation - it is still a
    // bindable member, just with an optional Binding, so its shape must be just as constrained.

    @Test
    fun defaultBodyMember_nonPublic_isRejected() {
        assertRejected(
            "Non-public members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface NonPublicDefaultModule {
                val name: String
                private fun greeting(): String = "Hello, ${'$'}name"
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_extension_isRejected() {
        assertRejected(
            "Extension members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ExtensionDefaultModule {
                fun String.shout(): String = uppercase()
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_generic_isRejected() {
        assertRejected(
            "Generic members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface GenericDefaultModule {
                fun <T> identity(value: T): T = value
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_varProperty_isRejected() {
        assertRejected(
            "var properties are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarDefaultModule {
                var value: String
                    get() = "default"
                    set(_) {}
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_vararg_isRejected() {
        assertRejected(
            "vararg parameters are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarargDefaultModule {
                fun join(vararg parts: String): String = parts.joinToString()
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_twoParameters_isRejected() {
        assertRejected(
            "more than one parameter",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface TwoParamDefaultModule {
                fun combine(a: Int, b: Int): Int = a + b
            }
            """
        )
    }

    @Test
    fun abstractMemberAndDefaultBodyMember_sameName_isRejected() {
        assertRejected(
            "Overloading is not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface MixedOverloadModule {
                fun get(): String
                fun get(x: Int): String = "${'$'}x"
            }
            """
        )
    }
}
