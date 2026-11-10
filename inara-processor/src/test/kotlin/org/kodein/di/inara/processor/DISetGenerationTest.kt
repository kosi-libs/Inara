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
 * see [buildSetFunctions]: the exact generated shape, which members land in the set, and what the
 * set holds at runtime.
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
        assertTrue(
            """
            |public fun AppModule.setOfAppServices(): Set<AppService> = kotlin.collections.setOfNotNull(
            |  database(),
            |  server,
            |)
            """.trimMargin() in generated,
            generated,
        )
        // Only a module listing the set gets the function.
        assertFalse("setOf" in compilation.generatedSource("sample/sets", "InaraDbModule.kt"))
        assertFalse("setOf" in compilation.generatedSource("sample/sets", "InaraServerModule.kt"))

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
    fun suspendMode_decidesWhetherTheFunctionIsSuspend() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Suspend.kt",
                """
                package sample.sets

                import org.kodein.di.inara.DIFactory.Suspend
                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.DISet

                interface Svc

                @DISet(Svc::class) annotation class AllowSync
                @DISet(Svc::class) annotation class AllowAsync
                @DISet(Svc::class, suspend = Suspend.Force) annotation class Forced
                @DISet(Svc::class, suspend = Suspend.Forbid) annotation class Forbidden

                @DIModule(sets = [AllowSync::class, AllowAsync::class, Forced::class, Forbidden::class])
                interface SuspendModule {
                    @AllowSync @Forced @Forbidden val sync: Svc
                    @AllowAsync suspend fun async(): Svc
                }
                """
            )
        )

        val generated = compilation.generatedSource("sample/sets", "InaraSuspendModule.kt")
        assertTrue("public fun SuspendModule.setOfAllowSync(): Set<Svc>" in generated, generated)
        assertTrue("public suspend fun SuspendModule.setOfAllowAsync(): Set<Svc>" in generated, generated)
        assertTrue("public suspend fun SuspendModule.setOfForced(): Set<Svc>" in generated, generated)
        assertTrue("public fun SuspendModule.setOfForbidden(): Set<Svc>" in generated, generated)
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
        assertTrue("defaulted(),\n  varargs(),\n" in generated, generated)

        val tag = compilation.classLoader.loadClass("sample.sets.Tag").getConstructor(String::class.java)
        assertEquals(setOf(tag.newInstance("default"), tag.newInstance("0")), compilation.runHarness("sample.sets.Harness")[0])
    }

    @Test
    fun sets_areInheritedByEveryDescendant() {
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

                // Listing an inherited set again is allowed, and still generates it once.
                @DIModule(sets = [AppServices::class])
                interface RelistingModule : ParentModule

                object Harness {
                    val theServer = object : Server {}
                    val theDatabase = object : Database {}

                    @JvmStatic
                    fun run(): Array<Any?> {
                        val child = ChildModule(server = value(theServer), database = value(theDatabase))
                        val asParent: ParentModule = child
                        return arrayOf(child.setOfAppServices().toList(), asParent.setOfAppServices().toList())
                    }
                }
                """
            )
        )

        val child = compilation.generatedSource("sample/sets", "InaraChildModule.kt")
        assertTrue("public fun ChildModule.setOfAppServices(): Set<AppService>" in child, child)
        val relisting = compilation.generatedSource("sample/sets", "InaraRelistingModule.kt")
        assertEquals(1, Regex("""fun RelistingModule\.setOfAppServices\(""").findAll(relisting).count(), relisting)

        val harness = compilation.classLoader.loadClass("sample.sets.Harness")
        val instance = harness.getField("INSTANCE").get(null)
        val server = harness.getMethod("getTheServer").invoke(instance)
        val database = harness.getMethod("getTheDatabase").invoke(instance)
        val results = compilation.runHarness("sample.sets.Harness")
        assertEquals(listOf(server, database), results[0])
        // Statically typed as the parent, the parent's own function is called.
        assertEquals(listOf(server), results[1])
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
        assertTrue("server,\n  database(),\n" in generated, generated)
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
        assertTrue("public fun SeveralModule.setOfFirst(): Set<Svc> = kotlin.collections.setOfNotNull(\n  both,\n)" in generated, generated)
        assertTrue("public fun SeveralModule.setOfSecond(): Set<Any> = kotlin.collections.setOfNotNull(\n  both,\n  label,\n)" in generated, generated)
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

                    import org.kodein.di.inara.DIModule
                    import sample.lib.AppService
                    import sample.lib.AppServices
                    import sample.lib.LibraryModule

                    @DIModule
                    interface AppModule : LibraryModule {
                        @AppServices val other: AppService
                    }
                    """
                )
            ),
            dependencies = listOf(library),
        )
        assertEquals(ExitCode.OK, app.exitCode, app.messages)

        val generated = app.generatedSource("sample/app", "InaraAppModule.kt")
        assertTrue(
            "public fun AppModule.setOfAppServices(): Set<AppService> = kotlin.collections.setOfNotNull(\n  server,\n  other,\n)" in generated,
            generated,
        )
    }
}
