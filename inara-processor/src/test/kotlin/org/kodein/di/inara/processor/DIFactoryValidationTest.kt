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
    fun publicObject_isRejected() {
        assertRejected(
            "A @DIFactory object must be internal",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            object GreeterDIFactory
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
        // The generated builder is a top-level function; its with(<object>) { } block can't see a
        // private member.
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
    fun unresolvableArgument_isRejected() {
        assertRejected(
            "Cannot resolve argument 'greeting' of 'Greeter' from 'EmptyModule' - add a " +
                "'fun EmptyModule.argGreeting(): String' function to 'GreeterDIFactory'.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun forbid_onlySuspendCandidateAvailable_isRejectedAsUnresolvable() {
        // Suspend.Forbid excludes a suspend module member from the candidate pool entirely, rather
        // than resolving it and then rejecting it - so this falls into the same "cannot resolve,
        // add an arg function" diagnostic as any other unresolvable argument.
        assertRejected(
            "Cannot resolve argument",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIFactory.Suspend
            import org.kodein.di.inara.DIModule

            @DIModule
            interface TokenModule {
                suspend fun token(): String
            }
            class Session(val token: String)

            @DIFactory(builds = Session::class, from = TokenModule::class, suspend = Suspend.Forbid)
            internal object SessionDIFactory
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
    fun withArgument_oneParameterMemberWhoseParameterTheWithValueCannotBePassedTo_isNotACandidate() {
        assertRejected(
            "Cannot resolve argument 'greeting'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule {
                fun greetingFor(count: Int): String
            }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun withArgument_unresolvableArgument_suggestionCarriesTheWithParameter() {
        assertRejected(
            "Cannot resolve argument 'greeting' of 'Greeter' from 'GreetingModule' - add a " +
                "'fun GreetingModule.argGreeting(with: Ctx): String' function to 'GreeterDIFactory'.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule { val unrelated: Int }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
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
    fun withNullable_nonNullableOneParameterMember_isNotACandidate() {
        assertRejected(
            "Cannot resolve argument 'greeting' of 'Greeter' from 'GreetingModule' - add a " +
                "'fun GreetingModule.argGreeting(with: Ctx?): String' function to 'GreeterDIFactory'.",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface GreetingModule {
                fun greetingFor(ctx: Ctx): String
            }
            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, withNullable = Ctx::class)
            internal object GreeterDIFactory
            """
        )
    }

    @Test
    fun withNullable_varargMember_isNotACandidate() {
        assertRejected(
            "Cannot resolve argument 'text'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface PathModule {
                fun join(vararg parts: String): String
            }
            class Thing(val text: String)

            @DIFactory(builds = Thing::class, from = PathModule::class, withNullable = Array<String>::class)
            internal object ThingDIFactory
            """
        )
    }

    @Test
    fun withNullable_nonNullableConstructorArgument_isNotResolvedFromTheCallTimeValue() {
        assertRejected(
            "Cannot resolve argument 'ctx' of 'Screen' from 'EmptyModule'",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx
            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Screen(val ctx: Ctx)

            @DIFactory(builds = Screen::class, from = EmptyModule::class, withNullable = Ctx::class)
            internal object ScreenDIFactory
            """
        )
    }
}
