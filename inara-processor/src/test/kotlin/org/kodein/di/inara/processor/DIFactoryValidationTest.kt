@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * One test per `@DIFactory` pattern that must be rejected at compile time, verifying both that
 * compilation fails and that the diagnostic names the actual problem.
 */
class DIFactoryValidationTest {

    private fun assertRejected(expectedMessageFragment: String, contents: String) {
        val compilation = compile(SourceFile.kotlin("Factory.kt", contents))
        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        assertTrue(expectedMessageFragment in compilation.messages, compilation.messages)
    }

    @Test
    fun privateObject_isRejected() {
        assertRejected(
            "A @DIFactory object must be public or internal",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            private object GreeterDIFactory
            """
        )
    }

    @Test
    fun publicObject_withInternalFromModule_isRejected() {
        assertRejected(
            "Cannot generate the 'public' factory function 'GreeterDIFactory.Greeter' because " +
                "the 'from' module 'SecretModule' is 'internal'. Make 'GreeterDIFactory' 'internal', " +
                "or make it 'public'.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            internal interface SecretModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = SecretModule::class)
            public object GreeterDIFactory
            """
        )
    }

    @Test
    fun publicObject_withInternalBuildsClass_isRejected() {
        assertRejected(
            "because the 'builds' class 'Greeter' is 'internal'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            internal class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            public object GreeterDIFactory
            """
        )
    }

    @Test
    fun publicObject_withInternalSelectedConstructor_isRejected() {
        assertRejected(
            "because its selected constructor is 'internal'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val count: Int) {
                internal constructor(greeting: String) : this(greeting.length)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            public object GreeterDIFactory
            """
        )
    }

    @Test
    fun publicObject_withInternalWithType_isRejected() {
        assertRejected(
            "because the 'with' type 'Ctx' is 'internal'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            internal class Ctx

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
            public object GreeterDIFactory {
                internal fun GreetingModule.argGreeting(ctx: Ctx): String = greeting
            }
            """
        )
    }

    @Test
    fun publicObject_withAPublicArgFunction_isRejected() {
        assertRejected(
            "'argGreeting' must be 'internal': 'GreeterDIFactory' is 'public', so its arg functions " +
                "may not widen its public API.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            public object GreeterDIFactory {
                fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )
    }

    @Test
    fun nonObject_isRejected() {
        assertRejected(
            "Only objects may be annotated with @DIFactory",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal class GreeterDIFactory
            """
        )
    }

    @Test
    fun abstractBuilds_isRejected() {
        assertRejected(
            "must be a concrete class",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            abstract class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun sealedBuilds_isRejected() {
        assertRejected(
            "must be a concrete class",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            sealed class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun interfaceBuilds_isRejected() {
        assertRejected(
            "must be a concrete class",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            interface Greeter

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun objectBuilds_isRejected() {
        assertRejected(
            "must be a concrete class",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            object Greeter

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun privateConstructorBuilds_isRejected() {
        assertRejected(
            "must have a public or internal primary constructor",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter private constructor(val greeting: String) {
                companion object {
                    fun create(greeting: String) = Greeter(greeting)
                }
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun privateBuilds_isRejected() {
        assertRejected(
            "must be public or internal",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            private class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun invalidFromModule_reportsEachErrorOnce() {
        // Pre-fix: the @DIModule loop reports once, then each @DIFactory re-parses `from` and reports
        // again - N+1 copies of one diagnostic. The module parse is now memoized per round.
        val compilation = compile(
            SourceFile.kotlin(
                "Factory.kt",
                """
                package sample.factory

                import org.kodein.di.inara.DIFactory
                import org.kodein.di.inara.DIModule

                @DIModule
                interface BrokenModule {
                    var broken: String
                }

                class Greeter(val broken: String)
                class Shouter(val broken: String)

                @DIFactory(builds = Greeter::class, from = BrokenModule::class)
                internal object GreeterDIFactory

                @DIFactory(builds = Shouter::class, from = BrokenModule::class)
                internal object ShouterDIFactory
                """
            )
        )

        assertEquals(ExitCode.COMPILATION_ERROR, compilation.exitCode, compilation.messages)
        val hits = Regex(Regex.escape("var properties are not supported: 'broken'."))
            .findAll(compilation.messages).count()
        assertEquals(1, hits, compilation.messages)
    }

    @Test
    fun nonDIModuleFrom_isRejected() {
        assertRejected(
            "must be a @DIModule interface",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory

            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun objectProperty_isRejected() {
        assertRejected(
            "may only declare",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                val unrelated: Int = 0
            }
            """
        )
    }

    @Test
    fun objectFunctionNotShapedLikeArg_isRejected() {
        assertRejected(
            "may only declare",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.helper(): String = greeting
            }
            """
        )
    }

    @Test
    fun argFunctionNamingUnknownArgument_isRejected() {
        assertRejected(
            "does not match any constructor argument",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argNonsense(): String = greeting
            }
            """
        )
    }

    @Test
    fun argFunctionWrongReturnType_isRejected() {
        assertRejected(
            "must return the type of",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(): Int = greeting.length
            }
            """
        )
    }

    @Test
    fun suspendArgFunctionUnderForbid_isRejected() {
        assertRejected(
            "may not be 'suspend'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIFactory.Suspend
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, suspend = Suspend.Forbid)
            internal object GreeterDIFactory {
                suspend fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )
    }

    @Test
    fun suspendArgFunctionUnderDefaultAllow_isAccepted() {
        val compilation = compile(
            SourceFile.kotlin(
                "Factory.kt",
                """
                package sample.factory

                import org.kodein.di.inara.DIFactory
                import org.kodein.di.inara.DIModule

                @DIModule
                interface GreetingModule { val greeting: String }
                class Greeter(val greeting: String)

                @DIFactory(builds = Greeter::class, from = GreetingModule::class)
                internal object GreeterDIFactory {
                    suspend fun GreetingModule.argGreeting(): String = greeting
                }
                """
            )
        )
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)
    }

    @Test
    fun argFunctionWithParameter_isRejected() {
        assertRejected(
            "may not take any parameter",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(extra: Int): String = greeting
            }
            """
        )
    }

    @Test
    fun privateArgFunction_isRejected() {
        // The generated builder is an extension on the object, not a member - it can't see a
        // `private` one.
        assertRejected(
            "must be public or internal",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                private fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )
    }

    @Test
    fun argFunctionWrongReceiver_isRejected() {
        assertRejected(
            "must be an extension function on",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Unrelated
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun Unrelated.argGreeting(): String = "x"
            }
            """
        )
    }

    @Test
    fun ambiguousExactMatch_isRejected() {
        assertRejected(
            "is ambiguous",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface NameModule {
                val firstName: String
                val lastName: String
            }
            class Named(val name: String)

            @DIFactory(builds = Named::class, from = NameModule::class)
            internal object NamedDIFactory
            """
        )
    }

    @Test
    fun ambiguousAssignableMatch_isRejected() {
        assertRejected(
            "is ambiguous",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            open class Animal
            class Dog : Animal()
            class Cat : Animal()

            @DIModule
            interface PetModule {
                fun dog(): Dog
                fun cat(): Cat
            }
            class Shelter(val animal: Animal)

            @DIFactory(builds = Shelter::class, from = PetModule::class)
            internal object ShelterDIFactory
            """
        )
    }

    @Test
    fun allow_ambiguousBetweenSuspendAndNonSuspendCandidates_isRejected() {
        // A suspend candidate is now just as eligible as a non-suspend one under Suspend.Allow, so
        // two exact-type matches - one of each - are ambiguous exactly like any other pair.
        assertRejected(
            "is ambiguous",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface MixedModule {
                suspend fun suspendGreeting(): String
                val plainGreeting: String
            }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = MixedModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun constructorIndexOutOfRange_isRejected() {
        // Greeter has exactly one constructor (its primary) - index 1 is out of range.
        assertRejected(
            "'constructorIndex' is 1, but the 'builds' class 'Greeter' has 1 constructor(s)",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun constructorIndexNegative_isRejected() {
        assertRejected(
            "'constructorIndex' is -1",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = -1)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun argFunctionNamedForPrimaryOnlyParam_isRejectedWhenSecondaryIsSelected() {
        // 'label' only exists on Greeter's primary constructor - once constructorIndex = 1 selects
        // the secondary ('prefix'), an arg function still named argLabel no longer matches anything.
        assertRejected(
            "does not match any constructor argument",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val base: String
            }

            class Greeter(val label: String) {
                constructor(prefix: String) : this(prefix)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            internal object GreeterDIFactory {
                fun GreetingModule.argLabel(): String = base
            }
            """
        )
    }

    @Test
    fun argFunctionMissingTheWithParameter_isRejected() {
        assertRejected(
            "must take exactly one parameter of type 'Ctx'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )
    }

    @Test
    fun argFunctionWithAWrongTypedParameter_isRejected() {
        assertRejected(
            "must take exactly one parameter of type 'Ctx'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(wrong: Int): String = greeting
            }
            """
        )
    }

    @Test
    fun withArgument_zeroAndOneParameterMembersMatchingTheSameArgument_isAmbiguous() {
        assertRejected(
            "is ambiguous",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val count: Int
                fun countFor(scope: String): Int
            }
            class Greeter(val count: Int)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = String::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun bothWithAndWithNullable_isRejected() {
        assertRejected(
            "Only one of 'with' and 'withNullable' may be set on @DIFactory - 'GreeterDIFactory' sets both.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(
                builds = Greeter::class,
                from = GreetingModule::class,
                with = Ctx::class,
                withNullable = Ctx::class,
            )
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun withNullable_argFunctionWithANonNullableParameter_isRejected() {
        assertRejected(
            "must take exactly one parameter of type 'Ctx?' (the @DIFactory 'withNullable' type)",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, withNullable = Ctx::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(ctx: Ctx): String = greeting
            }
            """
        )
    }

    @Test
    fun repeatedAnnotation_duplicateSignature_isRejected() {
        assertRejected(
            "'Fac' has 2 @DIFactory annotations that all generate 'fun Fac.Widget(module: LabelModule): Widget'.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface LabelModule { val label: String }
            class Widget(val label: String)

            @DIFactory(builds = Widget::class, from = LabelModule::class)
            @DIFactory(builds = Widget::class, from = LabelModule::class)
            internal object Fac
            """
        )
    }

    @Test
    fun repeatedAnnotation_argFunctionMatchingNoFactory_isRejected() {
        assertRejected(
            "does not match any constructor argument",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface AppModule { val a: String }
            class A(val a: String)
            class B(val a: String)

            @DIFactory(builds = A::class, from = AppModule::class)
            @DIFactory(builds = B::class, from = AppModule::class)
            internal object Fac {
                fun AppModule.argNonsense(): String = a
            }
            """
        )
    }

    @Test
    fun repeatedAnnotation_argFunctionValidForNoFactory_isRejected() {
        assertRejected(
            "is not a valid arg function for any @DIFactory on 'Fac'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface AppModule { val a: String }
            class A(val title: String)
            class B(val title: Int)

            @DIFactory(builds = A::class, from = AppModule::class)
            @DIFactory(builds = B::class, from = AppModule::class)
            internal object Fac {
                fun AppModule.argTitle(): Boolean = true   // wrong return type for both
            }
            """
        )
    }

    @Test
    fun publicObject_withUnresolvableArgumentOfAnInternalType_isRejected() {
        // 'greeting' can't resolve off the module, so it would become a pass-through parameter -
        // but its type 'Secret' is 'internal', which a 'public' generated function can't name.
        assertRejected(
            "Cannot generate the 'public' factory function 'GreeterDIFactory.Greeter'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            internal class Secret
            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val greeting: Secret)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            public object GreeterDIFactory
            """
        )
    }

    @Test
    fun genericBuildsClass_unresolvableTypeParameterArgument_isRejected() {
        // 'Box<T>' is a generic class used as more than a raw type; 'value: T' can't resolve off the
        // module and can't become a pass-through parameter either - a type parameter of 'Box' means
        // nothing in the generated function's own signature - so this is a dedicated diagnostic
        // rather than the processor crashing on 'T'.toTypeName()`.
        assertRejected(
            "refers to a type parameter of 'Box'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Box<T>(val value: T)

            @DIModule
            interface EmptyModule { val unrelated: Int }

            @DIFactory(builds = Box::class, from = EmptyModule::class)
            internal object BoxDIFactory
            """
        )
    }
}
