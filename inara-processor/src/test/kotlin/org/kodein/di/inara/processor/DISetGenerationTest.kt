@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Verifies the `setOf<Annotation>()` extension function(s) generated for `@DIModule(sets = ...)` -
 * see [buildSetFunctions] - and the `InaraSets` lookups that make them answer for the module they are
 * actually called on: the generated shape, which members land in the set, and what the set holds at
 * runtime.
 */
class DISetGenerationTest {

    private fun compileOk(vararg sources: SourceFile): CompileTestResult =
        compile(*sources).also { assertEquals(ExitCode.OK, it.exitCode, it.messages) }

    @Suppress("UNCHECKED_CAST")
    private fun CompileTestResult.runHarness(className: String): Array<Any?> =
        classLoader.loadClass(className).getMethod("run").invoke(null) as Array<Any?>

    private val services = """
        interface AppService
        interface Database : AppService
        interface Server : AppService

        @DISet(AppService::class)
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
        annotation class AppServices
    """

    @Test
    fun composedModule_collectsInheritedMembersInOrder() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "AppModule.kt",
                """
                package sample.sets

                import org.kodein.di.inara.Binding.Bindings.single
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                $services

                @DIModule
                interface DbModule {
                    @AppServices fun database(): Database? = null
                }

                @DIModule
                interface ServerModule {
                    @AppServices val server: Server
                    val port: Int
                }

                @DIModule(composed = true, sets = [AppServices::class])
                interface AppModule : DbModule, ServerModule

                object Harness {
                    val theServer = object : Server {}
                    val theDatabase = object : Database {}

                    @JvmStatic
                    fun run(): Array<Any?> {
                        val serverModule = ServerModule(server = single { theServer }, port = single { 80 })
                        val withoutDb = AppModule(dbModule = DbModule(), serverModule = serverModule)
                        val withDb = AppModule(dbModule = DbModule(database = single { theDatabase }), serverModule = serverModule)
                        return arrayOf(withoutDb.setOfAppServices(), withDb.setOfAppServices().toList())
                    }
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraAppModule.kt")
        // The function asks its receiver, falling back to this module's members for a hand-written one.
        assertTrue(
            """
            |@Suppress("UNCHECKED_CAST")
            |public fun AppModule.setOfAppServices(): Set<AppService> = (this as? InaraSets)?.inaraSetOf("sample.sets.AppServices") as Set<AppService>?
            |    ?: kotlin.collections.setOfNotNull(
            |  this.database(),
            |  this.server,
            |)
            """.trimMargin() in generated,
            generated,
        )
        assertTrue("public class InaraAppModule(" in generated && ") : AppModule,\n    InaraSets {" in generated, generated)
        assertTrue(
            """
            |  override fun inaraSetOf(`set`: String): Set<*>? = when (set) {
            |    "sample.sets.AppServices" -> kotlin.collections.setOfNotNull(
            |      this.database(),
            |      this.server,
            |    )
            |    else -> null
            |  }
            """.trimMargin() in generated,
            generated,
        )
        // A module neither listing the set nor extending one that does can never be asked for it.
        assertFalse("setOf" in compilation.generatedSource("sample/sets", "InaraDbModule.kt"))
        assertFalse("InaraSets" in compilation.generatedSource("sample/sets", "InaraServerModule.kt"))

        val results = compilation.runHarness("sample.sets.Harness")
        val harness = compilation.classLoader.loadClass("sample.sets.Harness")
        val instance = harness.getField("INSTANCE").get(null)
        val server = harness.getMethod("getTheServer").invoke(instance)
        val database = harness.getMethod("getTheDatabase").invoke(instance)
        assertEquals(setOf(server), results[0])
        assertEquals(listOf(database, server), results[1])
    }

    @Test
    fun internalModule_generatesInternalFunction() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Internal.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                internal interface Plugin

                @DISet(Plugin::class)
                annotation class Plugins

                @DIModule(sets = [Plugins::class])
                internal interface InternalModule {
                    @Plugins val plugin: Plugin
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraInternalModule.kt")
        assertTrue("internal fun InternalModule.setOfPlugins(): Set<Plugin>" in generated, generated)
    }

    @Test
    fun suspendArgument_aloneDecidesWhetherTheFunctionIsSuspend() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Suspend.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface Svc

                @DISet(Svc::class) annotation class Sync
                @DISet(Svc::class, suspend = true) annotation class Async

                @DIModule(sets = [Sync::class, Async::class])
                interface SuspendModule {
                    @Sync @Async val sync: Svc
                    @Async suspend fun async(): Svc
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraSuspendModule.kt")
        assertTrue("public fun SuspendModule.setOfSync(): Set<Svc> = (this as? InaraSets)?.inaraSetOf(\"sample.sets.Sync\")" in generated, generated)
        assertTrue(
            "public suspend fun SuspendModule.setOfAsync(): Set<Svc> = (this as? InaraSets)?.inaraSuspendSetOf(\"sample.sets.Async\")" in generated,
            generated,
        )
        assertTrue("override fun inaraSetOf(`set`: String): Set<*>? = when (set) {\n    \"sample.sets.Sync\" ->" in generated, generated)
        assertTrue(
            "override suspend fun inaraSuspendSetOf(`set`: String): Set<*>? = when (set) {\n    \"sample.sets.Async\" ->" in generated,
            generated,
        )
    }

    @Test
    fun genericOfType_isStarProjected() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Generic.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface Plugin<T>

                @DISet(Plugin::class)
                annotation class Plugins

                @DIModule(sets = [Plugins::class])
                interface PluginModule {
                    @Plugins val strings: Plugin<String>
                    @Plugins fun ints(): Plugin<Int>?
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraPluginModule.kt")
        assertTrue("public fun PluginModule.setOfPlugins(): Set<Plugin<*>>" in generated, generated)
    }

    @Test
    fun typeAliasOfType_isExpanded() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Alias.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface Service
                typealias Svc = Service

                @DISet(Svc::class)
                annotation class Services

                @DIModule(sets = [Services::class])
                interface AliasModule {
                    @Services val service: Service
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraAliasModule.kt")
        assertTrue("public fun AliasModule.setOfServices(): Set<Service>" in generated, generated)
    }

    @Test
    fun functionCallableWithoutArguments_isCalledWithNone() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Defaults.kt",
                """
                package sample.sets

                import org.kodein.di.inara.Binding.Bindings.factory
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                data class Tag(val name: String)

                @DISet(Tag::class)
                annotation class Tags

                @DIModule(sets = [Tags::class])
                interface DefaultsModule {
                    @Tags fun defaulted(name: String = "default"): Tag
                    @Tags fun varargs(vararg names: String): Tag
                }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val module = DefaultsModule(
                            defaulted = factory { Tag(it) },
                            varargs = factory { Tag(it.size.toString()) },
                        )
                        return arrayOf(module.setOfTags())
                    }
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraDefaultsModule.kt")
        assertTrue("this.defaulted(),\n  this.varargs(),\n" in generated, generated)

        val tag = compilation.classLoader.loadClass("sample.sets.Tag").getConstructor(String::class.java)
        assertEquals(setOf(tag.newInstance("default"), tag.newInstance("0")), compilation.runHarness("sample.sets.Harness")[0])
    }

    @Test
    fun setFunction_answersForTheModuleItIsCalledOn() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Inherited.kt",
                """
                package sample.sets

                import org.kodein.di.inara.Binding.Bindings.value
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                $services

                @DIModule(sets = [AppServices::class])
                interface ParentModule {
                    @AppServices val server: Server
                }

                interface Plain : ParentModule

                @DIModule
                interface ChildModule : Plain {
                    @AppServices val database: Database
                }

                // Listing an inherited set again is allowed, and generates its own function.
                @DIModule(sets = [AppServices::class])
                interface RelistingModule : ParentModule

                object Harness {
                    val theServer = object : Server {}
                    val theDatabase = object : Database {}

                    @JvmStatic
                    fun run(): Array<Any?> {
                        val child = ChildModule(server = value(theServer), database = value(theDatabase))
                        val asParent: ParentModule = child
                        val handWritten = object : ChildModule {
                            override val server: Server = theServer
                            override val database: Database = theDatabase
                        }
                        return arrayOf(
                            child.setOfAppServices().toList(), asParent.setOfAppServices().toList(),
                            handWritten.setOfAppServices().toList(),
                        )
                    }
                }
                """
            )
        )

        // The child reuses its ancestor's function, and only answers for the set.
        val child = compilation.generatedSource("sample/sets", "InaraChildModule.kt")
        assertTrue("setOfAppServices" !in child, child)
        assertTrue("\"sample.sets.AppServices\" -> kotlin.collections.setOfNotNull(\n      this.server,\n      this.database,\n    )" in child, child)
        val relisting = compilation.generatedSource("sample/sets", "InaraRelistingModule.kt")
        assertEquals(1, Regex("""fun RelistingModule\.setOfAppServices\(""").findAll(relisting).count(), relisting)

        val harness = compilation.classLoader.loadClass("sample.sets.Harness")
        val instance = harness.getField("INSTANCE").get(null)
        val server = harness.getMethod("getTheServer").invoke(instance)
        val database = harness.getMethod("getTheDatabase").invoke(instance)
        val results = compilation.runHarness("sample.sets.Harness")
        assertEquals(listOf(server, database), results[0])
        // Statically typed as the parent, still the members of the actual module.
        assertEquals(listOf(server, database), results[1])
        // A hand-written implementation only gets the members of the module listing the set.
        assertEquals(listOf(server), results[2])
    }

    @Test
    fun annotationOnOverriddenDeclaration_stillCounts() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Override.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                $services

                interface Base {
                    @AppServices val server: AppService
                    @AppServices fun database(id: Int = 0): AppService
                }

                // Neither override repeats the annotation, nor - as Kotlin forbids it - the default value.
                interface Derived : Base {
                    override val server: Server
                    override fun database(id: Int): Database
                }

                @DIModule(sets = [AppServices::class])
                interface OverrideModule : Derived
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraOverrideModule.kt")
        assertTrue("this.server,\n  this.database(),\n" in generated, generated)
    }

    @Test
    fun memberInSeveralSets_isInEach() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Several.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface Svc

                @DISet(Svc::class) annotation class First
                @DISet(Any::class) annotation class Second

                @DIModule(sets = [First::class, Second::class])
                interface SeveralModule {
                    @First @Second val both: Svc
                    @Second val label: String
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraSeveralModule.kt")
        assertTrue("\"sample.sets.First\" -> kotlin.collections.setOfNotNull(\n      this.both,\n    )" in generated, generated)
        assertTrue("\"sample.sets.Second\" -> kotlin.collections.setOfNotNull(\n      this.both,\n      this.label,\n    )" in generated, generated)
    }

    @Test
    fun setNamedNotNull_doesNotCaptureTheStdlibCall() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "NotNull.kt",
                """
                package sample.sets

                import org.kodein.di.inara.Binding.Bindings.value
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                @DISet(String::class)
                annotation class NotNull

                @DIModule(sets = [NotNull::class])
                interface NotNullModule {
                    @NotNull val a: String
                    @NotNull val b: String?
                }

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> = arrayOf(NotNullModule(a = value("a"), b = value(null)).setOfNotNull())
                }
                """
            )
        )

        assertEquals(setOf("a"), compilation.runHarness("sample.sets.Harness")[0])
    }

    @Test
    fun setsFromACompiledLibrary_areReadFromItsBinaries() {
        val library = compile(
            SourceFile.kotlin(
                "Library.kt",
                """
                package sample.lib

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface AppService
                interface Server : AppService

                @DISet(AppService::class)
                annotation class AppServices

                @DIModule(sets = [AppServices::class])
                interface LibraryModule {
                    @AppServices val server: Server
                }
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

                    import org.kodein.di.inara.Binding.Bindings.value
                    import org.kodein.di.inara.DIModule
                    import sample.lib.*

                    @DIModule
                    interface AppModule : LibraryModule {
                        @AppServices val other: AppService
                    }

                    object Harness {
                        val theServer = object : Server {}
                        val theOther = object : AppService {}

                        @JvmStatic
                        fun run(): Array<Any?> {
                            val library: LibraryModule = AppModule(server = value(theServer), other = value(theOther))
                            return arrayOf(library.setOfAppServices().toList() == listOf(theServer, theOther))
                        }
                    }
                    """
                )
            ),
            dependencies = listOf(library),
        )
        assertEquals(ExitCode.OK, app.exitCode, app.messages)

        val generated = app.generatedSource("sample/app", "InaraAppModule.kt")
        assertTrue("\"sample.lib.AppServices\" -> kotlin.collections.setOfNotNull(\n      this.server,\n      this.other,\n    )" in generated, generated)
        // The library's own function, called on the app's module, answers with the app's members.
        assertEquals(true, app.runHarness("sample.app.Harness")[0])
    }

    @Test
    fun aModuleListingASetWithoutMembers_gathersItsDescendantsContributions() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Gathering.kt",
                """
                package sample.sets

                import org.kodein.di.inara.Binding.Bindings.single
                import org.kodein.di.inara.Binding.Bindings.value
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                $services

                @DIModule(sets = [AppServices::class])
                interface FrameworkModule {
                    val name: String
                }

                @DIModule
                interface DbModule {
                    @AppServices val database: Database
                }

                @DIModule
                interface ServerModule : @DIModule.Required DbModule {
                    @AppServices val server: Server
                }

                @DIModule(composed = true)
                interface AppModule : FrameworkModule, DbModule, ServerModule

                fun boot(module: FrameworkModule): List<AppService> = module.setOfAppServices().toList()

                object Harness {
                    val theServer = object : Server {}
                    val theDatabase = object : Database {}

                    @JvmStatic
                    fun run(): Array<Any?> {
                        val app = AppModule(
                            FrameworkModule(name = value("app")),
                            DbModule(database = value(theDatabase)),
                            ServerModulePart(server = single { theServer }),
                        )
                        return arrayOf(boot(app) == listOf(theDatabase, theServer), boot(FrameworkModule(name = value("alone"))))
                    }
                }
                """
            )
        )

        val framework = compilation.generatedSource("sample/sets", "InaraFrameworkModule.kt")
        assertTrue("?: kotlin.collections.emptySet<AppService>()" in framework, framework)

        val results = compilation.runHarness("sample.sets.Harness")
        assertEquals(true, results[0]) // a Part's member is the composing module's like any other
        assertEquals(emptyList<Any>(), results[1])
    }
}
