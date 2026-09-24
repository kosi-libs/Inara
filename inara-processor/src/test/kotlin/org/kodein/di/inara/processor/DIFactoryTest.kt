@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the generated shape for a `@DIFactory` object: an extension function on the object, named
 * after `builds`, that resolves every one of its primary constructor's arguments off a `from` module
 * instance. The object is the extension receiver, so its own `arg<Argument>` extension functions are
 * in scope with no `with(<object>) { ... }` wrapper. The function's visibility matches the object's.
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
            public object UserControllerDIFactory {
                internal fun NetworkModule.argEndpoint(): String = endpoint("users")
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraUserControllerDIFactory.kt")

        assertTrue("public fun UserControllerDIFactory.UsersController(module: NetworkModule): UsersController" in generated, generated)
        assertTrue("with(" !in generated, generated)
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
            public object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("greeting = module.greeting," in generated, generated)
    }

    @Test
    fun internalArgFunction_isAccepted() {
        // A `public` @DIFactory object requires its arg functions to be `internal` (they are
        // implementation detail); the generated file is in the same module, so calling one is fine.
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
            public object GreeterDIFactory {
                internal fun GreetingModule.argGreeting(): String = greeting.uppercase()
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: GreetingModule): Greeter" in generated, generated)
        assertTrue("greeting = module.argGreeting()," in generated, generated)
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
    fun publicObject_generatesPublicExtensionFunction() {
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
            public object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: GreetingModule): Greeter" in generated, generated)
    }

    @Test
    fun internalObject_generatesInternalExtensionFunction() {
        // The object's visibility is the function's; an `internal` `from` module is fine here
        // precisely because the function is `internal` too.
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
        assertTrue("internal fun GreeterDIFactory.Greeter(module: SecretModule): Greeter" in generated, generated)
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
                val port: Int
                fun clientFor(scope: String): HttpClient
            }

            class Controller(val client: HttpClient, val port: Int, val tag: String)

            @DIFactory(builds = Controller::class, from = NetworkModule::class, with = String::class)
            public object ControllerDIFactory {
                internal fun NetworkModule.argTag(scope: String): String = scope.uppercase()
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraControllerDIFactory.kt")
        assertTrue("public fun ControllerDIFactory.Controller(module: NetworkModule, with: String): Controller" in generated, generated)
        assertTrue("client = module.clientFor(with)," in generated, generated)
        assertTrue("port = module.port," in generated, generated)
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
    fun withArgument_resolvesAnAssignableConstructorArgumentFromTheCallTimeValue() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }

            class Greeter(val name: String)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class, with = String::class)
            public object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: EmptyModule, with: String): Greeter" in generated, generated)
        assertTrue("name = with," in generated, generated)
    }

    @Test
    fun withArgument_callTimeValueOutranksADefaultAndASameTypedMember() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }

            class Greeter(val greeting: String = "hi")

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = String::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = with," in generated, generated)
    }

    @Test
    fun withArgument_argFunctionOutranksTheCallTimeValue() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val unrelated: Int }

            class Greeter(val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, with = String::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(with: String): String = with.uppercase()
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = module.argGreeting(with)," in generated, generated)
    }

    @Test
    fun withArgument_varargArgumentIsNotResolvedFromTheCallTimeValue() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }

            class Thing(vararg val tags: String)

            @DIFactory(builds = Thing::class, from = EmptyModule::class, with = String::class)
            public object ThingDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraThingDIFactory.kt")
        assertTrue("public fun ThingDIFactory.Thing(module: EmptyModule, with: String): Thing" in generated, generated)
        assertTrue("Thing()" in generated, generated)
        assertTrue("tags =" !in generated, generated)
    }

    @Test
    fun withNullableArgument_resolvesANullableConstructorArgumentFromTheCallTimeValue() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx

            @DIModule
            interface EmptyModule { val unrelated: Int }

            class Screen(val ctx: Ctx?)

            @DIFactory(builds = Screen::class, from = EmptyModule::class, withNullable = Ctx::class)
            public object ScreenDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraScreenDIFactory.kt")
        assertTrue("public fun ScreenDIFactory.Screen(module: EmptyModule, with: Ctx?): Screen" in generated, generated)
        assertTrue("ctx = with," in generated, generated)
    }

    @Test
    fun withNullableArgument_generatesANullableParameterPassedToArgFunctionsAndOneParameterMembers() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx

            @DIModule
            interface GreetingModule {
                fun localeFor(ctx: Ctx?): String
            }

            class Greeter(val locale: String, val greeting: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class, withNullable = Ctx::class)
            public object GreeterDIFactory {
                internal fun GreetingModule.argGreeting(ctx: Ctx?): String = ctx?.toString() ?: "anon"
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: GreetingModule, with: Ctx?): Greeter" in generated, generated)
        assertTrue("locale = module.localeFor(with)," in generated, generated)
        assertTrue("greeting = module.argGreeting(with)," in generated, generated)
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
            public object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("public fun GreeterDIFactory.Greeter(module: GreetingModule): Greeter" in generated, generated)
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
                public object Fac
            }

            class OuterB {
                @DIFactory(builds = Gadget::class, from = LabelModule::class)
                public object Fac
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        // A wrong file name would throw FileNotFoundException here, as it does for every other test.
        val a = compilation.generatedSource("sample/factory", "InaraOuterAFac.kt")
        val b = compilation.generatedSource("sample/factory", "InaraOuterBFac.kt")

        assertTrue("public fun OuterA.Fac.Widget(module: LabelModule): Widget" in a, a)
        assertTrue("public fun OuterB.Fac.Gadget(module: LabelModule): Gadget" in b, b)
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
                public object Fac
            }

            class OuterB {
                @DIFactory(builds = Widget::class, from = LabelModuleB::class)
                public object Fac
            }
            """
        )

        // Both generated functions are named `Widget`; the compiler accepts them because their
        // receivers (`OuterA.Fac` / `OuterB.Fac`) and `module` parameter types both differ, so
        // compiling the generated code is the assertion.
        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val a = compilation.generatedSource("sample/factory", "InaraOuterAFac.kt")
        val b = compilation.generatedSource("sample/factory", "InaraOuterBFac.kt")
        assertTrue("public fun OuterA.Fac.Widget(module: LabelModuleA): Widget" in a, a)
        assertTrue("public fun OuterB.Fac.Widget(module: LabelModuleB): Widget" in b, b)
    }

    @Test
    fun repeatedAnnotation_generatesOneFunctionPerAnnotationInOneFile() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface AppModule {
                val title: String
                val count: Int
            }

            class LoginScreen(val title: String)
            class HomeScreen(val count: Int)

            @DIFactory(builds = LoginScreen::class, from = AppModule::class)
            @DIFactory(builds = HomeScreen::class, from = AppModule::class)
            public object Screens
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraScreens.kt")
        assertTrue("public fun Screens.LoginScreen(module: AppModule): LoginScreen" in generated, generated)
        assertTrue("public fun Screens.HomeScreen(module: AppModule): HomeScreen" in generated, generated)
        assertTrue("title = module.title," in generated, generated)
        assertTrue("count = module.count," in generated, generated)
    }

    @Test
    fun repeatedAnnotation_differentFromModules_bothGenerated() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface ModuleA { val a: String }
            @DIModule
            interface ModuleB { val b: String }

            class FromA(val a: String)
            class FromB(val b: String)

            @DIFactory(builds = FromA::class, from = ModuleA::class)
            @DIFactory(builds = FromB::class, from = ModuleB::class)
            public object Fac
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraFac.kt")
        assertTrue("public fun Fac.FromA(module: ModuleA): FromA" in generated, generated)
        assertTrue("public fun Fac.FromB(module: ModuleB): FromB" in generated, generated)
    }

    @Test
    fun repeatedAnnotation_argFunctionSharedByBothFactories() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface AppModule { val base: String }

            class LoginScreen(val title: String)
            class HomeScreen(val title: String)

            @DIFactory(builds = LoginScreen::class, from = AppModule::class)
            @DIFactory(builds = HomeScreen::class, from = AppModule::class)
            public object Screens {
                internal fun AppModule.argTitle(): String = base.uppercase()
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraScreens.kt")
        assertTrue("public fun Screens.LoginScreen(module: AppModule): LoginScreen" in generated, generated)
        assertTrue("public fun Screens.HomeScreen(module: AppModule): HomeScreen" in generated, generated)
        assertEquals(2, Regex(Regex.escape("title = module.argTitle(),")).findAll(generated).count(), generated)
    }

    @Test
    fun repeatedAnnotation_argFunctionForOnlyOneFactory_isNotAnErrorForTheOther() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface AppModule {
                val base: String
                val count: Int
            }

            class LoginScreen(val title: String)
            class HomeScreen(val count: Int)

            @DIFactory(builds = LoginScreen::class, from = AppModule::class)
            @DIFactory(builds = HomeScreen::class, from = AppModule::class)
            public object Screens {
                internal fun AppModule.argTitle(): String = base   // only LoginScreen has `title`
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraScreens.kt")
        assertTrue("title = module.argTitle()," in generated, generated)
        assertTrue("count = module.count," in generated, generated)
    }

    @Test
    fun repeatedAnnotation_sameBuildsDifferentWith_generatesOverloads() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            class Ctx

            @DIModule
            interface AppModule { val greeting: String }

            class Banner(val greeting: String)

            @DIFactory(builds = Banner::class, from = AppModule::class)
            @DIFactory(builds = Banner::class, from = AppModule::class, with = Ctx::class)
            public object Banners {
                internal fun AppModule.argGreeting(ctx: Ctx): String = greeting
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraBanners.kt")
        assertTrue("public fun Banners.Banner(module: AppModule): Banner" in generated, generated)
        assertTrue("public fun Banners.Banner(module: AppModule, with: Ctx): Banner" in generated, generated)
    }

    @Test
    fun defaultedParameter_withNoCandidate_isOmittedFromTheCall() {
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

            class Greeter(val greeting: String, val verbose: Boolean = false)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = module.greeting," in generated, generated)
        assertTrue("verbose" !in generated, generated)
    }

    @Test
    fun defaultedParameter_outranksAMatchingModuleMember() {
        // `flag` has a default AND `GreetingModule.flag` would match it exactly - the default wins,
        // so the module member is never consulted and the argument is left out of the call.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                val flag: Boolean
            }

            class Greeter(val greeting: String, val flag: Boolean = true)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = module.greeting," in generated, generated)
        assertTrue("flag" !in generated, generated)
    }

    @Test
    fun defaultedParameter_withAnArgFunction_usesTheArgFunction() {
        // An explicit arg function still outranks the default - the escape hatch works even when a
        // default exists.
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

            class Greeter(val greeting: String, val verbose: Boolean = false)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argVerbose(): Boolean = true
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("verbose = module.argVerbose()," in generated, generated)
    }

    @Test
    fun defaultedParameter_withAmbiguousModuleMembers_stillResolvesToTheDefault() {
        // Two exactly-typed members would be an ambiguity error for a non-defaulted parameter; the
        // default short-circuits the whole module lookup, so this compiles.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                val a: Boolean
                val b: Boolean
            }

            class Greeter(val greeting: String, val flag: Boolean = false)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("flag" !in generated, generated)
    }

    @Test
    fun everyParameterDefaulted_generatesABareConstructorCall() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule {
                val unrelated: Int
            }

            class Greeter(val greeting: String = "hi", val verbose: Boolean = false)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("Greeter()" in generated, generated)
    }

    @Test
    fun varargParameter_isOmittedEvenWhenAnElementTypedMemberExists() {
        // `ctorParams` maps `vararg tags: String` to its element type `String`; without the
        // vararg-is-omissible rule the generator would emit `tags = module.tag`, which doesn't
        // compile. The vararg is left out and defaults to an empty array.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule {
                val greeting: String
                val tag: String
            }

            class Greeter(val greeting: String, vararg val tags: String)

            @DIFactory(builds = Greeter::class, from = GreetingModule::class)
            internal object GreeterDIFactory {
                fun GreetingModule.argGreeting(): String = greeting
            }
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("greeting = module.argGreeting()," in generated, generated)
        assertTrue("tags" !in generated, generated)
    }

    // --- Pass-through parameters: a step-5 argument nothing else resolves becomes a parameter of
    // the generated function instead of a compile error, passed straight to the constructor. Every
    // pass-through parameter is named `arg<Argument>` - never the constructor parameter's own name -
    // so a generated signature is legible by prefix alone: one `module`, zero or one `with`, zero or
    // more `arg*`. ---

    @Test
    fun unresolvableArgument_becomesAnArgNamedPassThroughParameter() {
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("fun GreeterDIFactory.Greeter(" in generated, generated)
        assertTrue("module: EmptyModule" in generated, generated)
        assertTrue("argGreeting: String" in generated, generated)
        assertTrue("greeting = argGreeting," in generated, generated)
    }

    @Test
    fun forbid_onlySuspendCandidate_becomesANonSuspendPassThroughParameter() {
        // Suspend.Forbid excludes the suspend module member from the candidate pool entirely, so
        // 'token' can't resolve - it becomes an ordinary, non-suspend pass-through parameter rather
        // than an error, exactly like any other unresolvable argument.
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraSessionDIFactory.kt")
        assertTrue("fun SessionDIFactory.Session(" in generated, generated)
        assertTrue("suspend" !in generated, generated)
        assertTrue("argToken: String" in generated, generated)
        assertTrue("token = argToken," in generated, generated)
    }

    @Test
    fun withArgument_oneParameterMemberParameterMismatch_becomesAPassThroughParameter() {
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("with: Ctx" in generated, generated)
        assertTrue("argGreeting: String" in generated, generated)
        assertTrue("greeting = argGreeting," in generated, generated)
    }

    @Test
    fun withArgument_unresolvableArgument_becomesAPassThroughParameterAfterWith() {
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("module: GreetingModule" in generated, generated)
        assertTrue("with: Ctx" in generated, generated)
        assertTrue("argGreeting: String" in generated, generated)
        assertTrue("greeting = argGreeting," in generated, generated)
    }

    @Test
    fun withNullable_nonNullableOneParameterMember_becomesAPassThroughParameter() {
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("with: Ctx?" in generated, generated)
        assertTrue("argGreeting: String" in generated, generated)
        assertTrue("greeting = argGreeting," in generated, generated)
    }

    @Test
    fun withNullable_varargMember_isNotACandidate_becomesAPassThroughParameter() {
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

            @DIFactory(builds = Thing::class, from = PathModule::class, withNullable = Array<String>::class)
            internal object ThingDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraThingDIFactory.kt")
        assertTrue("argText: String" in generated, generated)
        assertTrue("text = argText," in generated, generated)
    }

    @Test
    fun withNullable_nonNullableConstructorArgument_becomesAPassThroughParameterAlongsideWith() {
        // 'ctx' is non-nullable and 'with' is 'Ctx?', so it can't resolve straight from the call-time
        // value (see the `withType` step) - it becomes its own pass-through parameter, in addition to
        // (not instead of) `with`.
        val source = SourceFile.kotlin(
            "Factory.kt",
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

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraScreenDIFactory.kt")
        assertTrue("module: EmptyModule" in generated, generated)
        assertTrue("with: Ctx?" in generated, generated)
        assertTrue("argCtx: Ctx" in generated, generated)
        assertTrue("ctx = argCtx," in generated, generated)
    }

    @Test
    fun multiplePassThroughParameters_keepConstructorDeclarationOrderInterleavedWithResolvedArguments() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface GreetingModule { val greeting: String }
            class Widget(val first: Int, val greeting: String, val second: Boolean)

            @DIFactory(builds = Widget::class, from = GreetingModule::class)
            internal object WidgetDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraWidgetDIFactory.kt")
        assertTrue("argFirst: Int" in generated, generated)
        assertTrue("argSecond: Boolean" in generated, generated)
        // Both pass-throughs come after `module`, `argFirst` before `argSecond` - constructor order.
        val moduleIndex = generated.indexOf("module: GreetingModule")
        val firstIndex = generated.indexOf("argFirst: Int")
        val secondIndex = generated.indexOf("argSecond: Boolean")
        assertTrue(moduleIndex in 0..<firstIndex && firstIndex < secondIndex, generated)
        assertTrue("greeting = module.greeting," in generated, generated)
        assertTrue("first = argFirst," in generated, generated)
        assertTrue("second = argSecond," in generated, generated)
    }

    @Test
    fun passThroughParameter_ofANullableType() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val nickname: String?)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("argNickname: String?" in generated, generated)
        assertTrue("nickname = argNickname," in generated, generated)
    }

    @Test
    fun passThroughParameter_namedModule_isPrefixedWithArgLikeAnyOther() {
        // The `arg` prefix is unconditional, not just a fallback for an actual name collision - so a
        // constructor parameter literally named `module` becomes `argModule` exactly like any other
        // pass-through, and never collides with the generated function's own `module` parameter.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val module: String)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("argModule: String" in generated, generated)
        assertTrue("module = argModule," in generated, generated)
    }

    @Test
    fun passThroughParameters_namedModuleAndArgModule_bothUnresolvable_areRenamedDeterministically() {
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val module: String, val argModule: String)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        // 'module' claims 'argModule' first (constructor order); the real 'argModule' constructor
        // parameter would also become 'argModule', so it falls back one step further, to
        // 'argArgModule'. Both constructor arguments are still named at the call site, so which
        // generated parameter name backs which is an implementation detail - only that the outcome is
        // deterministic and each name is used exactly once.
        assertTrue("argModule: String" in generated, generated)
        assertTrue("argArgModule: String" in generated, generated)
        assertTrue("module = argModule," in generated, generated)
        assertTrue("argModule = argArgModule," in generated, generated)
    }

    @Test
    fun passThroughParameter_namedWith_isStillPrefixedWithArg() {
        // The `arg` prefix applies unconditionally, whether or not `with`/`withNullable` is set - so
        // a constructor parameter literally named `with` becomes `argWith` here too, even though
        // there is no `with` function parameter for it to possibly collide with.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }
            class Greeter(val with: String)

            @DIFactory(builds = Greeter::class, from = EmptyModule::class)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("argWith: String" in generated, generated)
        assertTrue("with = argWith," in generated, generated)
    }

    @Test
    fun differingConstructorIndex_generatesDistinctOverloadsWhenPassThroughShapesDiffer() {
        // Two annotations sharing `builds`/`from`/`with` used to collide unconditionally on that
        // triple; they no longer do when the selected constructors leave different arguments
        // unresolved - here the primary's 'greeting: String' can't resolve off 'EmptyModule' (so it
        // becomes a pass-through parameter) while the secondary's 'nickname: Int' resolves straight
        // off 'unrelated', so the two generated functions genuinely have different signatures.
        val source = SourceFile.kotlin(
            "Factory.kt",
            """
            package sample.factory

            import org.kodein.di.inara.DIFactory
            import org.kodein.di.inara.DIModule

            @DIModule
            interface EmptyModule { val unrelated: Int }

            class Greeter(val greeting: String) {
                constructor(nickname: Int) : this(nickname.toString())
            }

            @DIFactory(builds = Greeter::class, from = EmptyModule::class, constructorIndex = 0)
            @DIFactory(builds = Greeter::class, from = EmptyModule::class, constructorIndex = 1)
            internal object GreeterDIFactory
            """
        )

        val compilation = compile(source)
        assertEquals(ExitCode.OK, compilation.exitCode, compilation.messages)

        val generated = compilation.generatedSource("sample/factory", "InaraGreeterDIFactory.kt")
        assertTrue("fun GreeterDIFactory.Greeter(module: EmptyModule, argGreeting: String): Greeter" in generated, generated)
        assertTrue("fun GreeterDIFactory.Greeter(module: EmptyModule): Greeter" in generated, generated)
        assertTrue("nickname = module.unrelated," in generated, generated)
    }
}
