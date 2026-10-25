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
    fun unresolvedTransitiveSupertype_failsCleanlyWithoutCrashing() {
        // A @DIModule whose grandparent supertype doesn't resolve used to NPE the processor:
        // KSP's validate() only checks direct supertypes, so parseModule ran and interfaceClosure
        // hit `qualifiedName!!` on the error type. The compile() call itself throwing is the
        // pre-fix failure - this test wouldn't reach its assertion.
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                interface Base : DefinitelyNotAThing

                @DIModule
                interface M : Base {
                    val value: String
                }
                """
            )
        )

        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
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
    fun memberNameShapedLikeArgFunction_isRejected() {
        // A member named `argFoo` would silently shadow a same-named @DIFactory extension function
        // (`fun M.argFoo(): T`) - Kotlin always prefers a member over an extension.
        assertRejected(
            "may not start with 'arg' followed by an uppercase letter",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BadNameModule {
                val argFoo: String
            }
            """
        )
    }

    @Test
    fun memberNamedArgument_isAllowed() {
        // "argument" is fine - only "arg" followed immediately by an UPPERCASE letter is rejected.
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface OkNameModule {
                    val argument: String
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
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
    fun memberNamedShadow_propertyIsRejected() {
        assertRejected(
            "may not be named 'shadow'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ShadowPropertyModule {
                val shadow: String
            }
            """
        )
    }

    @Test
    fun memberNamedShadow_functionIsRejected() {
        assertRejected(
            "may not be named 'shadow'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ShadowFunctionModule {
                fun shadow(): String
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
    fun privateModuleInterface_isRejected() {
        // The generated builder and copy functions are top-level and would expose a type nothing
        // outside the file can name.
        assertRejected(
            "must be public or internal",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            private interface PrivateModule {
                val value: String
            }
            """
        )
    }

    @Test
    fun protectedModuleInterface_isRejected() {
        assertRejected(
            "must be public or internal",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            abstract class Host {
                @DIModule
                protected interface ProtectedModule {
                    val value: String
                }
            }
            """
        )
    }

    @Test
    fun interfaceExtendingNonDIModuleInterface_isAllowed() {
        // A @DIModule interface may extend any interface, not just another @DIModule one - the
        // plain supertype's members are bound just like the module's own (see
        // DIModuleInheritanceTest for the generated shape).
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
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
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun diamondInheritance_isRejectedForComposedModule() {
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

            @DIModule(composed = true)
            interface DiamondModule : Module1, Module2 {
                val value: String
            }
            """
        )
    }

    @Test
    fun diamondInheritance_reportsOnlyTheMostDerivedSharedAncestor() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                interface Root
                interface Base : Root

                @DIModule
                interface Module1 : Base

                @DIModule
                interface Module2 : Base

                @DIModule(composed = true)
                interface DiamondModule : Module1, Module2 {
                    val value: String
                }
                """
            )
        )

        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue("'Base' is inherited more than once" in compilation.messages, compilation.messages)
        assertTrue("'Root' is inherited more than once" !in compilation.messages, compilation.messages)
        assertEquals(
            1,
            Regex("is inherited more than once").findAll(compilation.messages).count(),
            compilation.messages,
        )
    }

    @Test
    fun diamondInheritance_isAllowedForPlainModule() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
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
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun composedModuleWithoutParents_isRejected() {
        assertRejected(
            "requires the interface to extend at least one @DIModule interface",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule(composed = true)
            interface StandaloneModule {
                val value: String
            }
            """
        )
    }

    @Test
    fun sameParamNameParents_isRejectedForComposedModule() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Foo {
                    val a: String
                }

                @DIModule(composed = true)
                interface Combined : Foo, other.Foo {
                    val b: Int
                }
                """
            ),
            SourceFile.kotlin(
                "OtherFoo.kt",
                """
                package other

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Foo {
                    val c: Long
                }
                """
            ),
        )
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue("would both bind to the parameter name" in compilation.messages, compilation.messages)
    }

    @Test
    fun sameParamNameParents_isAllowedForPlainModule() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Foo {
                    val a: String
                }

                @DIModule
                interface Combined : Foo, other.Foo {
                    val b: Int
                }
                """
            ),
            SourceFile.kotlin(
                "OtherFoo.kt",
                """
                package other

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Foo {
                    val c: Long
                }
                """
            ),
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun parentParamNameCollidesWithMemberName_isRejectedForComposedModule() {
        assertRejected(
            "collides with a member name",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface Foo {
                val a: String
            }

            @DIModule(composed = true)
            interface Combined : Foo {
                val foo: Int
            }
            """
        )
    }

    @Test
    fun parentParamNameCollidesWithMemberName_isAllowedForPlainModule() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface Foo {
                    val a: String
                }

                @DIModule
                interface Combined : Foo {
                    val foo: Int
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun duplicateMemberNameAcrossParents_isRejected() {
        assertRejected(
            "declared in more than one unrelated interface",
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
    fun twoParameterMember_isAllowed() {
        // Members with more than one parameter are wrapped into a generated arguments data class -
        // see DIModuleMultiParamTest for the generated shape.
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface TwoParamModule {
                    fun combine(a: Int, b: Int): Int
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun varargParameter_isAllowed() {
        // A lone vararg parameter's Binding argument type is the array type directly - see
        // DIModuleMultiParamTest for the generated shape.
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface VarargModule {
                    fun join(vararg parts: String): String
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    // Every rule above also applies to a member with a default implementation EXCEPT var/extension/
    // generic (see defaultBodyMember_{varProperty,extension,generic}_isIgnored below), private (a
    // private interface member always has its own body - see privateMember_isIgnored), and parameter
    // count/vararg (unrestricted regardless of default status, see twoParameterMember_isAllowed /
    // varargParameter_isAllowed above): `Binding` can express none of the first three regardless of
    // default status, so a defaulted one is silently excluded from the bindable surface entirely
    // rather than rejected; parameter count/vararg are simply never restricted. Every other rule
    // still applies just as if the member were abstract.

    @Test
    fun privateMember_isIgnored() {
        // A private member always carries its own body - there is nothing for the generated class to
        // implement, exactly as for a private member inherited from a supertype.
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface PrivateMemberModule {
                    val name: String
                    private fun greeting(): String = "Hello, ${'$'}name"
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/validation", "InaraPrivateMemberModule.kt")
        assertTrue("greetingBinding" !in generated, generated)
        assertTrue("override fun greeting" !in generated, generated)
        assertTrue("public val nameBinding: Binding<PrivateMemberModule, Unit, String>," in generated, generated)
    }

    @Test
    fun internalMember_isRejected() {
        // Unlike private, an internal member can be abstract and participates in overriding, so it
        // stays rejected - Inara only binds the public surface.
        assertRejected(
            "Non-public members are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface InternalMemberModule {
                internal val secret: String
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_extension_isIgnored() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface ExtensionDefaultModule {
                    fun String.shout(): String = uppercase()
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/validation", "InaraExtensionDefaultModule.kt")
        assertTrue("shoutBinding" !in generated, generated)
        assertTrue("shout" !in generated, generated)
    }

    @Test
    fun defaultBodyMember_generic_isIgnored() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface GenericDefaultModule {
                    fun <T> identity(value: T): T = value
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/validation", "InaraGenericDefaultModule.kt")
        assertTrue("identityBinding" !in generated, generated)
        assertTrue("identity" !in generated, generated)
    }

    @Test
    fun defaultBodyMember_varProperty_isIgnored() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
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
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/validation", "InaraVarDefaultModule.kt")
        assertTrue("valueBinding" !in generated, generated)
        assertTrue("value" !in generated, generated)
    }

    @Test
    fun varProperty_getterOnlyDefault_isRejected() {
        assertRejected(
            "var properties are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarGetterOnlyModule {
                var value: String
                    get() = "default"
            }
            """
        )
    }

    @Test
    fun varProperty_setterOnlyDefault_isRejected() {
        assertRejected(
            "var properties are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface VarSetterOnlyModule {
                var value: String
                    set(_) {}
            }
            """
        )
    }

    @Test
    fun defaultedExtensionMemberNamedBinding_stillRejected() {
        // The naming-identity rules stay unconditional even for an otherwise-liftable member: this
        // one is liftable as an extension member with a default body, but its own name would still
        // collide with the generated class's internal `<name>Binding` naming scheme.
        assertRejected(
            "may not end with 'Binding'",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface ExtensionNamedBindingModule {
                val String.nameBinding: Int get() = length
            }
            """
        )
    }

    @Test
    fun defaultBodyMember_vararg_isAllowed() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface VarargDefaultModule {
                    fun join(vararg parts: String): String = parts.joinToString()
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun defaultBodyMember_twoParameters_isAllowed() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                @DIModule
                interface TwoParamDefaultModule {
                    fun combine(a: Int, b: Int): Int = a + b
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
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

    @Test
    fun varPropertyOfPlainSupertype_isRejected() {
        assertRejected(
            "var properties are not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            interface Api {
                var name: String
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )
    }

    @Test
    fun twoParameterMemberOfPlainSupertype_isAllowed() {
        val compilation = compile(
            SourceFile.kotlin(
                "Module.kt",
                """
                package sample.validation

                import org.kodein.di.inara.DIModule

                interface Api {
                    fun combine(a: Int, b: Int): Int
                }

                @DIModule
                interface Module1 : Api {
                    val extra: Int
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun overloadedMembersOfPlainSupertype_isRejected() {
        assertRejected(
            "Overloading is not supported",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            interface Api {
                fun get(): String
                fun get(x: Int): String
            }

            @DIModule
            interface Module1 : Api {
                val extra: Int
            }
            """
        )
    }

    @Test
    fun multiParamMembersWithSameCapitalizedName_isRejected() {
        assertRejected(
            "would all generate a 'Mult' arguments class",
            """
            package sample.validation

            import org.kodein.di.inara.DIModule

            @DIModule
            interface CollidingArgsModule {
                fun mult(a: Int, b: Int): Int
                fun Mult(a: Int, b: Int): Int
            }
            """
        )
    }
}
