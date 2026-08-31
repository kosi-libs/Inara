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
    fun internalFromModule_generatesInternalFunction() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            internal interface SecretModule {
                val greeting: String
            }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = SecretModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("internal fun Greeter(module: SecretModule): Greeter" in generated, generated)
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

    @Test
    fun withArgument_isPassedToArgFunctionsAndOneParameterMembers() {
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
                fun clientFor(scope: String): HttpClient
            }

            class Controller(val client: HttpClient, val base: String, val tag: String)

            @DIFactory(builds = Controller::class, from = NetworkModule::class, with = String::class)
            internal object ControllerDIFactory {
                fun NetworkModule.argTag(scope: String): String = scope.uppercase()
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraControllerDIFactory.kt")
        assertTrue("public fun Controller(module: NetworkModule, with: String): Controller" in generated, generated)
        assertTrue("kotlin.with(ControllerDIFactory)" in generated, generated)
        assertTrue("client = module.clientFor(with)," in generated, generated)
        assertTrue("base = module.baseUrl," in generated, generated)
        assertTrue("tag = module.argTag(with)," in generated, generated)
    }

    @Test
    fun withArgument_varargMemberReceivesItAsANamedArgument() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface PathModule {
                fun join(vararg parts: String): String
            }

            class Thing(val text: String)

            @DIFactory(builds = Thing::class, from = PathModule::class, with = Array<String>::class)
            internal object ThingDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraThingDIFactory.kt")
        assertTrue("with: Array<String>" in generated, generated)
        assertTrue("text = module.join(parts = with)," in generated, generated)
    }

    @Test
    fun internalWithType_generatesInternalFunction() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            internal class Ctx

            @DIModule
            interface GreetingModule {
                val greeting: String
            }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = Ctx::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(ctx: Ctx): String = greeting
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("internal fun Greeter(module: GreetingModule, with: Ctx): Greeter" in generated, generated)
    }

    @Test
    fun defaultWithUnit_leavesTheGeneratedShapeUnchanged() {
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
        assertTrue("= with(GreeterDIFactory)" in generated, generated)
        assertTrue("with:" !in generated, generated)
    }

    @Test
    fun twoSameNamedNestedFactoryObjectsInOnePackage_getDistinctGeneratedFiles() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface LabelModule {
                val label: String
            }

            class Widget(val label: String)
            class Gadget(val label: String)

            class OuterA {
                @DIFactory(builds = Widget::class, from = LabelModule::class)
                internal object Fac
            }

            class OuterB {
                @DIFactory(builds = Gadget::class, from = LabelModule::class)
                internal object Fac
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        // A wrong file name would throw FileNotFoundException here, as it does for every other test.
        val a = compilation.generatedSource("sample/factory", "InaraOuterAFac.kt")
        val b = compilation.generatedSource("sample/factory", "InaraOuterBFac.kt")

        assertTrue("public fun Widget(module: LabelModule): Widget" in a, a)
        assertTrue("with(OuterA.Fac)" in a, a)
        assertTrue("public fun Gadget(module: LabelModule): Gadget" in b, b)
        assertTrue("with(OuterB.Fac)" in b, b)
    }

    @Test
    fun twoFactoriesWithSameBuildsButDifferentFrom_generateValidOverloads() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface LabelModuleA {
                val label: String
            }

            @DIModule
            interface LabelModuleB {
                val label: String
            }

            class Widget(val label: String)

            class OuterA {
                @DIFactory(builds = Widget::class, from = LabelModuleA::class)
                internal object Fac
            }

            class OuterB {
                @DIFactory(builds = Widget::class, from = LabelModuleB::class)
                internal object Fac
            }
            """
        )

        // Both generated functions are named `Widget`; the Kotlin compiler accepts them because
        // their `module` parameter types differ, so compiling the generated code is the assertion.
        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val a = compilation.generatedSource("sample/factory", "InaraOuterAFac.kt")
        val b = compilation.generatedSource("sample/factory", "InaraOuterBFac.kt")
        assertTrue("public fun Widget(module: LabelModuleA): Widget" in a, a)
        assertTrue("public fun Widget(module: LabelModuleB): Widget" in b, b)
    }
}
