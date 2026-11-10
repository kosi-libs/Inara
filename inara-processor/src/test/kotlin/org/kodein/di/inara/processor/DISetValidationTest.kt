@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * One test per misuse of `@DISet`, of a set annotation, or of `@DIModule(sets = ...)` that must be
 * rejected at compile time, verifying both that compilation fails and that the diagnostic names the
 * actual problem.
 */
class DISetValidationTest {

    private fun assertRejected(expectedMessageFragment: String, contents: String, vararg otherFiles: SourceFile) {
        val compilation = compile(SourceFile.kotlin("Module.kt", contents), *otherFiles)
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue(expectedMessageFragment in compilation.messages, compilation.messages)
    }

    private fun source(body: String) = """
        package sample.validation

        import org.kodein.di.inara.DIFactory.Suspend
        import org.kodein.di.inara.DIModule
        import org.kodein.di.inara.DISet

        interface Svc

        $body
    """

    // ----- The set annotation's declaration -----

    @Test
    fun sourceRetention_isRejected() {
        assertRejected(
            "may not have a SOURCE retention",
            source(
                """
                @DISet(Svc::class)
                @Retention(AnnotationRetention.SOURCE)
                annotation class Services
                """
            ),
        )
    }

    @Test
    fun targetWithoutFunctionOrProperty_isRejected() {
        assertRejected(
            "must target FUNCTION or PROPERTY",
            source(
                """
                @DISet(Svc::class)
                @Target(AnnotationTarget.CLASS)
                annotation class Services
                """
            ),
        )
    }

    @Test
    fun unresolvedOfType_isRejected() {
        // KSP's validate() defers the annotation declaration itself; the module listing it reports.
        assertRejected(
            "The 'of' argument of @DISet on 'Services' must reference a class.",
            source(
                """
                @DISet(DefinitelyNotAThing::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface M {
                    @Services val svc: Svc
                }
                """
            ),
        )
    }

    @Test
    fun privateOfType_isRejected() {
        assertRejected(
            "must be public or internal, and so must every declaration enclosing it",
            source(
                """
                private interface Hidden

                @DISet(Hidden::class)
                annotation class Services
                """
            ),
        )
    }

    @Test
    fun ofTypeNestedInPrivateDeclaration_isRejected() {
        assertRejected(
            "must be public or internal, and so must every declaration enclosing it",
            source(
                """
                private object Outer {
                    interface Nested
                }

                @DISet(Outer.Nested::class)
                annotation class Services
                """
            ),
        )
    }

    // ----- @DIModule(sets = ...) -----

    @Test
    fun setsEntryWithoutDISet_isRejected() {
        assertRejected(
            "'NotASet' is listed in 'sets', but it is not annotated with @DISet.",
            source(
                """
                annotation class NotASet

                @DIModule(sets = [NotASet::class])
                interface M {
                    val svc: Svc
                }
                """
            ),
        )
    }

    @Test
    fun setListedTwice_isRejected() {
        assertRejected(
            "'@Services' is listed more than once in 'sets'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class, Services::class])
                interface M {
                    @Services val svc: Svc
                }
                """
            ),
        )
    }

    @Test
    fun twoSetsWithTheSameSimpleName_areRejected() {
        assertRejected(
            "would all generate 'setOfServices'",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class, sample.other.Services::class])
                interface M {
                    @Services @sample.other.Services val svc: Svc
                }
                """
            ),
            SourceFile.kotlin(
                "Other.kt",
                """
                package sample.other

                import org.kodein.di.inara.DISet

                @DISet(Any::class)
                annotation class Services
                """
            ),
        )
    }

    @Test
    fun memberNamedLikeTheGeneratedFunction_isRejected() {
        assertRejected(
            "A member named 'setOfServices' clashes with the function generated for the set '@Services'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface M {
                    @Services val svc: Svc
                    fun setOfServices(): Set<Svc> = emptySet()
                }
                """
            ),
        )
    }

    @Test
    fun memberNamedLikeAnInheritedSetFunction_isRejected() {
        assertRejected(
            "A member named 'setOfServices' clashes with the function generated for the set '@Services'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface Parent {
                    @Services val svc: Svc
                }

                @DIModule
                interface Child : Parent {
                    val setOfServices: String
                }
                """
            ),
        )
    }

    @Test
    fun publicModuleWithInternalOfType_isRejected() {
        assertRejected(
            "The public module 'M' can't expose the internal 'of' type 'Hidden' of the set '@Services'.",
            source(
                """
                internal interface Hidden

                @DISet(Hidden::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface M {
                    @Services val svc: Svc
                }
                """
            ),
        )
    }

    @Test
    fun ofTypeInternalToAnotherModule_isRejected() {
        val library = compile(
            SourceFile.kotlin(
                "Library.kt",
                """
                package sample.lib

                import org.kodein.di.inara.DISet

                internal interface Hidden

                @DISet(Hidden::class)
                annotation class Services
                """
            )
        )
        assertEquals(ExitCode.OK, library.exitCode, library.messages)

        val app = compile(
            listOf(
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package sample.app

                    import org.kodein.di.inara.DIModule
                    import sample.lib.Services

                    @DIModule(sets = [Services::class])
                    internal interface M {
                        @Services val svc: Any
                    }
                    """
                )
            ),
            dependencies = listOf(library),
        )
        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode, app.messages)
        assertTrue("The 'of' type 'Hidden' of the set '@Services' is internal to another module" in app.messages, app.messages)
    }

    @Test
    fun listedSetWithoutMember_isRejected() {
        assertRejected(
            "No member of 'M' is annotated with '@Services', listed in its 'sets'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface M {
                    val svc: Svc
                }
                """
            ),
        )
    }

    // ----- Members carrying a set annotation -----

    @Test
    fun ignoredVar_isRejected() {
        assertRejected(
            "'svc' is annotated with @Services, but Inara ignores a 'var', so it can't belong to a set.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services var svc: Svc
                        get() = TODO()
                        set(value) {}
                }
                """
            ),
        )
    }

    @Test
    fun ignoredExtensionMember_isRejected() {
        assertRejected(
            "'svc' is annotated with @Services, but Inara ignores an extension member, so it can't belong to a set.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services fun String.svc(): Svc = TODO()
                }
                """
            ),
        )
    }

    @Test
    fun ignoredGenericFunction_isRejected() {
        assertRejected(
            "'svc' is annotated with @Services, but Inara ignores a generic function, so it can't belong to a set.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services fun <T : Svc> svc(): Svc = TODO()
                }
                """
            ),
        )
    }

    @Test
    fun functionRequiringArguments_isRejected() {
        assertRejected(
            "'svc' is annotated with @Services, but can't be called without arguments: 'id', 'name' have no default value.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services fun svc(id: Int, flag: Boolean = false, name: String): Svc
                }
                """
            ),
        )
    }

    @Test
    fun memberNotOfTheSetType_isRejected() {
        assertRejected(
            "'label' is annotated with @Services, but its type 'String?' is not a subtype of 'Svc'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services val label: String?
                }
                """
            ),
        )
    }

    @Test
    fun inheritedMemberNotOfTheSetType_isRejectedOnTheChild() {
        assertRejected(
            "Member 'label' inherited from 'Base': 'label' is annotated with @Services, but its type 'String' is not a subtype of 'Svc'.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                interface Base {
                    @Services val label: String
                }

                @DIModule
                interface M : Base
                """
            ),
        )
    }

    @Test
    fun suspendMemberOfForbiddingSet_isRejected() {
        assertRejected(
            "'svc' is suspend, but @Services is @DISet(suspend = Forbid).",
            source(
                """
                @DISet(Svc::class, suspend = Suspend.Forbid)
                annotation class Services

                @DIModule
                interface M {
                    @Services suspend fun svc(): Svc
                }
                """
            ),
        )
    }

    // ----- Uses no module can reach -----

    @Test
    fun setAnnotationOnClassMember_isRejected() {
        assertRejected(
            "@Services may only annotate a function or a property declared in an interface.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                class NotAModule {
                    @Services val svc: Svc = TODO()
                }
                """
            ),
        )
    }

    @Test
    fun setAnnotationOnTopLevelFunction_isRejected() {
        assertRejected(
            "@Services may only annotate a function or a property declared in an interface.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @Services fun svc(): Svc = TODO()
                """
            ),
        )
    }

    @Test
    fun setAnnotationOnClass_isRejected() {
        assertRejected(
            "@Services may only annotate a function or a property declared in an interface.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @Services class NotAMember
                """
            ),
        )
    }

    @Test
    fun setAnnotationOnPrivateInterfaceMember_isRejected() {
        assertRejected(
            "@Services may not annotate a private member: Inara never sees it.",
            source(
                """
                @DISet(Svc::class)
                annotation class Services

                @DIModule
                interface M {
                    @Services private fun svc(): Svc = TODO()
                }
                """
            ),
        )
    }
}
