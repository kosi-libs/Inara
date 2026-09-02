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
}
