@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Partial modules: a `@DIModule` marking a direct supertype `@DIModule.Required` gets a
 * `<Module>Part` class - see [buildPartType] - that a `@DIModule(composed = true)` module takes in
 * place of a built instance of it, reading its bindings with [partBindingCode].
 */
class DIModulePartialTest {

    private fun compileOk(vararg sources: SourceFile): CompileTestResult =
        compile(*sources).also { assertEquals(ExitCode.OK, it.exitCode, it.messages) }

    @Suppress("UNCHECKED_CAST")
    private fun CompileTestResult.runHarness(className: String): Array<Any?> =
        classLoader.loadClass(className).getMethod("run").invoke(null) as Array<Any?>

    private val jdbcFlywayExposed = """
        class DataSource(val url: String)
        class Flyway(val dataSource: DataSource)
        class Database(val dataSource: DataSource)

        @DIModule
        interface JdbcModule {
            val dataSource: DataSource
        }

        @DIModule
        interface FlywayModule : @DIModule.Required JdbcModule {
            val flyway: Flyway
            fun migrate(schema: String, target: Int): Any
        }

        @DIModule
        interface ExposedModule : @DIModule.Required JdbcModule {
            val database: Database
            val label: String get() = "exposed"
        }

        @DIModule(composed = true)
        interface AppModule : JdbcModule, FlywayModule, ExposedModule {
            val appName: String
        }
    """

    @Test
    fun partialModule_generatesAPartClass() {
        val compilation = compileOk(
            SourceFile.kotlin("Partial.kt", "package sample.partial\n\nimport org.kodein.di.inara.DIModule\n$jdbcFlywayExposed")
        )
        val generated = compilation.generatedSource("sample/partial", "InaraFlywayModule.kt")

        assertTrue("public class FlywayModulePart {" in generated, generated)
        assertTrue("@InternalInaraGeneration\n  public val flywayBinding: Binding<FlywayModule, Unit, Flyway>" in generated, generated)
        assertTrue(
            "public val migrateBinding: Binding<FlywayModule, FlywayModuleBindingArguments.Migrate, Any>" in generated,
            generated,
        )
        // Required members are left to the composing module.
        assertTrue("dataSourceBinding" !in generated.substringAfter("public class FlywayModulePart").substringBefore("\n}\n"), generated)
        assertTrue(
            "public constructor(flyway: Binding<FlywayModule, Unit, Flyway>, migrate: Binding<FlywayModule, FlywayModuleBindingArguments.Migrate, Any>) {" in generated,
            generated,
        )
        // Not composed: a single constructor.
        assertEquals(1, Regex("""public constructor\(""").findAll(generated.substringAfter("public class FlywayModulePart")).count(), generated)
        // The module keeps its full builder, required members included.
        assertTrue(Regex("""public fun FlywayModule\(\s*dataSource: Binding<FlywayModule, Unit, DataSource>""").containsMatchIn(generated), generated)

        val exposed = compilation.generatedSource("sample/partial", "InaraExposedModule.kt")
        assertTrue("public constructor(database: Binding<ExposedModule, Unit, Database>, label: Binding<ExposedModule, Unit, String>? = null) {" in exposed, exposed)
    }

    @Test
    fun composedModule_takesAPartForAPartialParent() {
        val compilation = compileOk(
            SourceFile.kotlin("Partial.kt", "package sample.partial\n\nimport org.kodein.di.inara.DIModule\n$jdbcFlywayExposed")
        )
        val generated = compilation.generatedSource("sample/partial", "InaraAppModule.kt")

        assertTrue(
            Regex("""public fun AppModule\(\s*jdbcModule: JdbcModule,\s*flywayModule: FlywayModulePart,\s*exposedModule: ExposedModulePart,\s*appName: Binding<AppModule, Unit, String>,\s*\): AppModule""")
                .containsMatchIn(generated),
            generated,
        )
        // The provider is read from the parent providing it, never from a partial parent requiring it.
        assertTrue(
            "dataSourceBinding = if (jdbcModule is InaraJdbcModule) jdbcModule.dataSourceBinding else BindingImpls.Delegate(jdbcModule::class) { jdbcModule.dataSource }," in generated,
            generated,
        )
        assertTrue("flywayBinding = BindingImpls.copyOf(flywayModule.flywayBinding)," in generated, generated)
        assertTrue("migrateBinding = BindingImpls.copyOf(flywayModule.migrateBinding)," in generated, generated)
        assertTrue("labelBinding = BindingImpls.copyOf(exposedModule.labelBinding)," in generated, generated)
        // The arguments class is the one of the module introducing the member.
        assertTrue("public val migrateBinding: Binding<AppModule, FlywayModuleBindingArguments.Migrate, Any>," in generated, generated)

        // Swapping a Part: `null` keeps it.
        assertTrue(
            "public fun AppModule.copyAppModule(\n  jdbcModule: JdbcModule = this,\n  flywayModule: FlywayModulePart? = null,\n  exposedModule: ExposedModulePart? = null,\n): AppModule" in generated,
            generated,
        )
        assertTrue("flywayModule != null -> BindingImpls.copyOf(flywayModule.flywayBinding)" in generated, generated)
    }

    @Test
    fun partBindings_areCopiedIntoEachComposingModule() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Partial.kt",
                """
                package sample.partial

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.multiplex
                import org.kodein.di.inara.Binding.Bindings.single
                import org.kodein.di.inara.Binding.Bindings.value
                $jdbcFlywayExposed

                fun jdbc(url: String) = JdbcModule(dataSource = single { DataSource(url) })

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val flyway = FlywayModulePart(
                            flyway = single { Flyway(dataSource) },
                            migrate = multiplex { _: FlywayModuleBindingArguments.Migrate -> single { Any() } },
                        )
                        val exposed = ExposedModulePart(database = single { Database(dataSource) })
                        val prod = AppModule(jdbc("prod"), flyway, exposed, appName = value("app"))
                        val test = AppModule(jdbc("test"), flyway, exposed, appName = value("app"))
                        val swapped = prod.copyAppModule(jdbcModule = jdbc("swapped"))
                        val otherFlyway = prod.copyAppModule(flywayModule = FlywayModulePart(
                            flyway = single { Flyway(DataSource("other")) },
                            migrate = multiplex { _: FlywayModuleBindingArguments.Migrate -> single { Any() } },
                        ))
                        return arrayOf(
                            prod.flyway.dataSource.url, test.flyway.dataSource.url,
                            prod.flyway === prod.flyway, prod.database.dataSource === prod.dataSource,
                            prod.migrate("a", 1) === prod.migrate("a", 1), prod.migrate("a", 1) === test.migrate("a", 1),
                            swapped.flyway.dataSource.url, swapped.database.dataSource.url,
                            prod.label, prod.copyAppModule().migrate("a", 1) === prod.migrate("a", 1),
                            otherFlyway.flyway.dataSource.url, otherFlyway.dataSource.url,
                            flyway.toString(),
                        )
                    }
                }
                """
            )
        )

        val results = compilation.runHarness("sample.partial.Harness")
        assertEquals("prod", results[0])
        assertEquals("test", results[1]) // the same Part, composed twice: each module builds its own
        assertEquals(true, results[2])
        assertEquals(true, results[3])
        assertEquals(true, results[4])
        assertEquals(false, results[5]) // 2-parameter member: each module has its own multiplex cache
        assertEquals("swapped", results[6]) // swapping the provider rebuilds the Part bindings on it
        assertEquals("swapped", results[7])
        assertEquals("exposed", results[8]) // a default-body member left unbound
        assertEquals(false, results[9])
        assertEquals("other", results[10]) // swapping a Part in
        assertEquals("prod", results[11]) // ... keeps the provider's recipe
        assertEquals(
            "part FlywayModule (requires JdbcModule) {\n    val flyway: Flyway => single(Synchronized) { Flyway }\n" +
                "    fun migrate(schema: String, target: Int): Any => multiplex { Migrate -> Any }\n}",
            results[12],
        )
    }

    private val configPoolPersistence = """
        class DataSource(val url: String, val pool: Any)

        @DIModule
        interface ConfigModule {
            val url: String
        }

        @DIModule
        interface PoolModule {
            val pool: Any
        }

        @DIModule(composed = true)
        interface PersistenceModule : @DIModule.Required ConfigModule, PoolModule {
            val dataSource: DataSource
        }

        @DIModule(composed = true)
        interface OuterModule : @DIModule.Required ConfigModule, PersistenceModule {
            val extra: String
        }

        @DIModule(composed = true)
        interface App : ConfigModule, PersistenceModule

        @DIModule(composed = true)
        interface OuterApp : ConfigModule, OuterModule
    """

    @Test
    fun composedPartialModule_generatesAFromParentsPartConstructor() {
        val compilation = compileOk(
            SourceFile.kotlin("Partial.kt", "package sample.partial\n\nimport org.kodein.di.inara.DIModule\n$configPoolPersistence")
        )
        val generated = compilation.generatedSource("sample/partial", "InaraPersistenceModule.kt")
        val part = generated.substringAfter("public class PersistenceModulePart")

        assertTrue("public val poolModule: PoolModule?" in part, part)
        // Nullable: left unset by the from-parents constructor.
        assertTrue("public val poolBinding: Binding<PersistenceModule, Unit, Any>?" in part, part)
        assertTrue("public val dataSourceBinding: Binding<PersistenceModule, Unit, DataSource>\n" in part, part)
        assertTrue("public constructor(pool: Binding<PersistenceModule, Unit, Any>, dataSource: Binding<PersistenceModule, Unit, DataSource>) {" in part, part)
        assertTrue("public constructor(poolModule: PoolModule, dataSource: Binding<PersistenceModule, Unit, DataSource>) {" in part, part)

        // The full from-parents builder takes the required parent too.
        assertTrue(
            Regex("""public fun PersistenceModule\(\s*configModule: ConfigModule,\s*poolModule: PoolModule,\s*dataSource: Binding<PersistenceModule, Unit, DataSource>,\s*\): PersistenceModule""")
                .containsMatchIn(generated),
            generated,
        )

        val app = compilation.generatedSource("sample/partial", "InaraApp.kt")
        assertTrue(
            "poolBinding = persistenceModule.poolModule.let { parent0 -> if (parent0 != null) if (parent0 is InaraPoolModule) parent0.poolBinding else BindingImpls.Delegate(parent0::class) { parent0.pool } else BindingImpls.copyOf(persistenceModule.poolBinding!!) }," in app,
            app,
        )
    }

    @Test
    fun partBuiltFromParents_sharesTheParentsBindings() {
        val compilation = compileOk(
            SourceFile.kotlin(
                "Partial.kt",
                """
                package sample.partial

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.provider
                import org.kodein.di.inara.Binding.Bindings.single
                import org.kodein.di.inara.Binding.Bindings.value
                $configPoolPersistence

                object Harness {
                    @JvmStatic
                    fun run(): Array<Any?> {
                        val pool = PoolModule(pool = single { Any() })
                        val fromParents = PersistenceModulePart(poolModule = pool, dataSource = single { DataSource(url, this.pool) })
                        val flattened = PersistenceModulePart(pool = single { Any() }, dataSource = single { DataSource(url, this.pool) })
                        val a = App(ConfigModule(url = value("a")), fromParents)
                        val b = App(ConfigModule(url = value("b")), fromParents)
                        val c = App(ConfigModule(url = value("c")), flattened)
                        val d = App(ConfigModule(url = value("d")), flattened)
                        val outer = OuterModulePart(persistenceModule = fromParents, extra = provider { "extra " + url })
                        val outerApp = OuterApp(ConfigModule(url = value("o")), outer)
                        val full = PersistenceModule(ConfigModule(url = value("f")), pool, dataSource = single { DataSource(url, this.pool) })
                        return arrayOf(
                            a.pool === pool.pool, b.pool === pool.pool, a.dataSource.url, b.dataSource.url,
                            a.dataSource.pool === pool.pool,
                            c.pool === d.pool, c.dataSource.pool === c.pool,
                            outerApp.pool === pool.pool, outerApp.dataSource.url, outerApp.extra,
                            full.dataSource.url, full.pool === pool.pool,
                            fromParents.toString(),
                        )
                    }
                }
                """
            )
        )

        val results = compilation.runHarness("sample.partial.Harness")
        assertEquals(true, results[0]) // a parent the Part was built from is shared
        assertEquals(true, results[1])
        assertEquals("a", results[2]) // its own bindings are still copied
        assertEquals("b", results[3])
        assertEquals(true, results[4])
        assertEquals(false, results[5]) // flattened: every binding is copied
        assertEquals(true, results[6])
        assertEquals(true, results[7]) // a Part nested in a Part
        assertEquals("o", results[8])
        assertEquals("extra o", results[9])
        assertEquals("f", results[10]) // a composed partial module's full from-parents builder
        assertEquals(true, results[11])
        assertEquals(
            "part PersistenceModule (requires ConfigModule) {\n    val pool: Any => [from poolModule]\n" +
                "    val dataSource: DataSource => single(Synchronized) { DataSource }\n}",
            results[12],
        )
    }

    @Test
    fun partialModuleFromACompiledLibrary_isComposedFromItsBinaries() {
        val library = compileOk(
            SourceFile.kotlin(
                "Library.kt",
                """
                package sample.lib

                import org.kodein.di.inara.DIModule
                import org.kodein.di.inara.Binding.Bindings.factory
                import org.kodein.di.inara.Binding.Bindings.single

                class DataSource(val url: String)
                class Flyway(val dataSource: DataSource)

                @DIModule
                interface JdbcModule {
                    val dataSource: DataSource
                }

                @DIModule
                interface FlywayModule : @DIModule.Required JdbcModule {
                    val flyway: Flyway
                    fun migrate(schema: String, target: Int): String
                }

                fun flywayModule(): FlywayModulePart = FlywayModulePart(
                    flyway = single { Flyway(dataSource) },
                    migrate = factory { "${'$'}{it.schema}@${'$'}{it.target} on ${'$'}{dataSource.url}" },
                )
                """
            )
        )

        val app = compile(
            listOf(
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package sample.app

                    import org.kodein.di.inara.DIModule
                    import org.kodein.di.inara.Binding.Bindings.single
                    import sample.lib.*

                    @DIModule(composed = true)
                    interface AppModule : JdbcModule, FlywayModule

                    object Harness {
                        @JvmStatic
                        fun run(): Array<Any?> {
                            val app = AppModule(JdbcModule(dataSource = single { DataSource("db") }), flywayModule())
                            return arrayOf(app.flyway.dataSource === app.dataSource, app.migrate("s", 2))
                        }
                    }
                    """
                )
            ),
            dependencies = listOf(library),
        )
        assertEquals(ExitCode.OK, app.exitCode, app.messages)

        val generated = app.generatedSource("sample/app", "InaraAppModule.kt")
        assertTrue("flywayModule: FlywayModulePart" in generated, generated)
        assertTrue("Binding<AppModule, FlywayModuleBindingArguments.Migrate, String>" in generated, generated)
        assertTrue("BindingArguments private constructor()" !in generated, generated)

        val results = app.runHarness("sample.app.Harness")
        assertEquals(true, results[0])
        assertEquals("s@2 on db", results[1])
    }
}
