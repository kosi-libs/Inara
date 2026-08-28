@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the generated shape for a `@DIFactory` object: a top-level function named after `builds`
 * that resolves every one of its primary constructor's arguments off a `from` module instance,
 * inside a `with(<factory object>) { ... }` block so the object's own `arg<Argument>` extension
 * functions are in scope.
 */
class DIFactoryTest {

    @Test
    fun reproducesTheUserExample() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class HttpClient

            @DIModule
            interface NetworkModule {
                val baseUrl: String
                fun httpClient(): HttpClient
                fun endpoint(path: String): String
            }

            class UsersController(
                private val httpClient: HttpClient,
                private val endpoint: String,
            )

            @DIFactory(builds = UsersController::class, from = NetworkModule::class)
            internal object UserControllerDIFactory {
                fun NetworkModule.argEndpoint(): String = endpoint("users")
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraUserControllerDIFactory.kt")

        assertTrue("public fun UsersController(module: NetworkModule): UsersController" in generated, generated)
        assertTrue("with(UserControllerDIFactory)" in generated, generated)
        assertTrue("UsersController(" in generated, generated)
        assertTrue("httpClient = module.httpClient()," in generated, generated)
        assertTrue("endpoint = module.argEndpoint()," in generated, generated)
    }

    @Test
    fun exactTypeMatch_resolvesWithoutAnArgFunction() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
            }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("greeting = module.greeting," in generated, generated)
    }

    @Test
    fun assignableTypeMatch_resolvesToASupertypeParameter() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            open class Animal
            class Dog : Animal()

            @DIModule
            interface PetModule {
                fun dog(): Dog
            }

            class Shelter(val animal: Animal)

            @DIFactory(builds = Shelter::class, from = PetModule::class)
            internal object ShelterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraShelterDIFactory.kt")
        assertTrue("animal = module.dog()," in generated, generated)
    }

    @Test
    fun argFunctionPriority_winsOverAnEligibleSuspendModuleMember() {
        // An explicit arg-function always wins over auto-resolution, whether or not the module
        // member it would otherwise resolve to is itself suspend - see the DIFactorySuspendTest
        // for suspend-specific candidate-pool behavior.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface TokenModule {
                suspend fun token(): String
                val fallback: String
            }

            class Session(val token: String)

            @DIFactory(builds = Session::class, from = TokenModule::class)
            internal object SessionDIFactory {
                fun TokenModule.argToken(): String = fallback
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraSessionDIFactory.kt")
        assertTrue("token = module.argToken()," in generated, generated)
    }

    @Test
    fun internalBuilds_generatesInternalFunction() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
            }

            internal class InternalGreeter(val greeting: String)

            @DIFactory(builds = InternalGreeter::class, from = GreetingModule::class)
            internal object InternalGreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraInternalGreeterDIFactory.kt")
        assertTrue("internal fun InternalGreeter(module: GreetingModule): InternalGreeter" in generated, generated)
    }

    @Test
    fun argFunctionReceiverOnSupertype_isAccepted() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            interface BaseApi {
                fun raw(): Int
            }

            @DIModule
            interface ApiModule : BaseApi

            class Wrapped(val computed: Int)

            @DIFactory(builds = Wrapped::class, from = ApiModule::class)
            internal object WrappedDIFactory {
                fun BaseApi.argComputed(): Int = raw() * 2
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraWrappedDIFactory.kt")
        assertTrue("computed = module.argComputed()," in generated, generated)
    }

    @Test
    fun defaultConstructorIndex_stillSelectsThePrimaryWhenSecondariesExist() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                val count: Int
            }

            class Greeter(val count: Int) {
                constructor(greeting: String) : this(greeting.length)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("count = module.count," in generated, generated)
    }

    @Test
    fun constructorIndexSelectsASecondaryConstructor() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                val count: Int
            }

            class Greeter(val count: Int) {
                constructor(greeting: String) : this(greeting.length)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = module.greeting," in generated, generated)
    }

    @Test
    fun constructorIndexSelectsASecondaryConstructor_argFunctionMatchesItsParameters() {
        // 'label' only exists on the primary - resolving 'prefix'/'suffix' via arg functions proves
        // constructorIndex = 1 really moved parameter resolution to the secondary.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val base: String
            }

            class Greeter(val label: String) {
                constructor(prefix: String, suffix: String) : this(prefix + suffix)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            internal object GreeterDIFactory {
                fun GreetingModule.argPrefix(): String = base
                fun GreetingModule.argSuffix(): String = "!"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("prefix = module.argPrefix()," in generated, generated)
        assertTrue("suffix = module.argSuffix()," in generated, generated)
        assertTrue("label" !in generated, generated)
    }

    @Test
    fun internalSecondaryConstructorOnPublicClass_generatesInternalFunction() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
            }

            class Greeter(val count: Int) {
                internal constructor(greeting: String) : this(greeting.length)
            }

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, constructorIndex = 1)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("internal fun Greeter(module: GreetingModule): Greeter" in generated, generated)
    }
}
