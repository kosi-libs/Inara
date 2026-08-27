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
            "Cannot resolve argument",
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
}
