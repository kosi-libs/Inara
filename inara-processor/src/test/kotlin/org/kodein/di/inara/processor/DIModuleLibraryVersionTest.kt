@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * A module extending modules of other libraries, which change without it being recompiled: its
 * generated code records their signatures (see [moduleSignature]), checks them at startup (see
 * `InaraSignatures`), and a composed module reading it reports it when it is out of date (see
 * [checkReadModulesUpToDate]).
 *
 * The libraries form `Base <- DomainA, DomainB <- Business`, compiled separately in that order.
 */
class DIModuleLibraryVersionTest {

    private fun library(name: String, source: String, vararg dependencies: CompileTestResult): CompileTestResult =
        compile(listOf(SourceFile.kotlin("$name.kt", source.trimIndent())), dependencies.toList())
            .also { assertEquals(ExitCode.OK, it.exitCode, it.messages) }

    private val base by lazy {
        library(
            "Base",
            """
            package sample.base

            import org.kodein.di.inara.DIModule

            @DIModule
            interface BaseModule {
                val dataSource: String
            }
            """,
        )
    }

    private fun domainA(vararg members: String) = library(
        "DomainA",
        """
        package sample.a

        import org.kodein.di.inara.DIModule
        import org.kodein.di.inara.Binding.Bindings.single
        import sample.base.BaseModule

        @DIModule
        interface DomainAModule : @DIModule.Required BaseModule {
            ${members.joinToString("\n            ") { "val $it: String" }}
        }

        fun domainAModule(): DomainAModulePart = DomainAModulePart(
            ${members.joinToString("\n            ") { "$it = single { \"$it on ${'$'}dataSource\" }," }}
        )
        """,
        base,
    )

    private val domainAV1 by lazy { domainA("repoA") }

    private val domainB by lazy {
        library(
            "DomainB",
            """
            package sample.b

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.single
            import sample.base.BaseModule

            @DIModule
            interface DomainBModule : @DIModule.Required BaseModule {
                val repoB: String
            }

            fun domainBModule(): DomainBModulePart = DomainBModulePart(repoB = single { "repoB on ${'$'}dataSource" })
            """,
            base,
        )
    }

    private val business by lazy {
        library(
            "Business",
            """
            package sample.business

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.value
            import sample.base.BaseModule
            import sample.a.*
            import sample.b.*

            @DIModule(composed = true)
            interface BusinessModule : BaseModule, DomainAModule, DomainBModule

            fun businessModule(): BusinessModule = BusinessModule(
                baseModule = BaseModule(dataSource = value("db")),
                domainAModule = domainAModule(),
                domainBModule = domainBModule(),
            )

            object Harness {
                @JvmStatic
                fun run(): Any = businessModule().toString()
            }
            """,
            base, domainAV1, domainB,
        )
    }

    private val appSource = SourceFile.kotlin(
        "App.kt",
        """
        package sample.app

        import org.kodein.di.inara.DIModule
        import org.kodein.di.inara.Binding.Bindings.value
        import sample.business.*

        @DIModule(composed = true)
        interface AppModule : BusinessModule {
            val appName: String
        }

        object Harness {
            @JvmStatic
            fun run(): Any = AppModule(businessModule = businessModule(), appName = value("app")).toString()
        }
        """.trimIndent(),
    )

    /** Runs `<pkg>.Harness.run()` with exactly [libraries] on the classpath, as an application would. */
    private fun runWith(pkg: String, vararg libraries: CompileTestResult): Any? {
        val loader = URLClassLoader(libraries.map { it.outputDirectory.toURI().toURL() }.toTypedArray(), javaClass.classLoader)
        return try {
            loader.loadClass("$pkg.Harness").getMethod("run").invoke(null)
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }
    }

    @Test
    fun upToDateLibraries_compileAndRun() {
        val app = compile(listOf(appSource), listOf(base, domainAV1, domainB, business))
        assertEquals(ExitCode.OK, app.exitCode, app.messages)

        val generated = app.generatedSource("sample/app", "InaraAppModule.kt")
        assertTrue(
            """"sample.a.DomainAModule\ninara $GENERATION_FORMAT\nmodule sample.a.DomainAModule\nparent sample.base.BaseModule required\nmember repoA val: kotlin.String"""" in generated,
            generated,
        )
        assertTrue("private fun inaraCheckSignaturesOfAppModule() {" in generated, generated)
        assertTrue(
            Regex("""InaraSignatures\.check\("sample\.app\.AppModule", "sample\.a\.DomainAModule", "[0-9a-f]{16}",\s+inaraSignatureOfDomainAModule\)""")
                .containsMatchIn(generated),
            generated,
        )

        val result = runWith("sample.app", app, business, domainAV1, domainB, base) as String
        assertTrue("val repoA: String => single(Synchronized) { String }" in result, result)
    }

    @Test
    fun libraryWithoutLibraryAncestors_checksNothing() {
        val generated = base.generatedSource("sample/base", "InaraBaseModule.kt")
        assertTrue("@InaraGenerated(format = $GENERATION_FORMAT)\npublic class InaraBaseModule(" in generated, generated)
        assertTrue(Regex("""public val inaraSignatureOfBaseModule: String\s+get\(\) = "[0-9a-f]{16}"""").containsMatchIn(generated), generated)
        assertTrue("inaraCheckSignatures" !in generated, generated)
    }

    @Test
    fun composedModuleReadingAnOutOfDateLibrary_isACompileError() {
        val domainAV2 = domainA("repoA", "auditLog")
        val app = compile(listOf(appSource), listOf(base, domainAV2, domainB, business))
        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode, app.messages)
        assertTrue(
            "'BusinessModule' was compiled against another version of 'DomainAModule' (member 'auditLog' added): " +
                "recompile the library declaring 'BusinessModule' against the current 'DomainAModule'." in app.messages,
            app.messages,
        )
        assertTrue("Unresolved reference" !in app.messages, app.messages)
    }

    @Test
    fun outOfDateLibrary_reportsEveryChange() {
        val domainAV2 = domainA("repository", "auditLog")
        val app = compile(listOf(appSource), listOf(base, domainAV2, domainB, business))
        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode, app.messages)
        assertTrue(
            "(member 'auditLog' added, member 'repository' added, member 'repoA' removed)" in app.messages,
            app.messages,
        )
    }

    @Test
    fun nonComposedModule_doesNotReadTheLibrariesItExtends() {
        val domainAV2 = domainA("repoA", "auditLog")
        val app = compile(
            listOf(
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package sample.app

                    import org.kodein.di.inara.DIModule
                    import sample.business.*

                    @DIModule
                    interface AppModule : BusinessModule
                    """.trimIndent(),
                )
            ),
            listOf(base, domainAV2, domainB, business),
        )
        assertEquals(ExitCode.OK, app.exitCode, app.messages)
        assertTrue("auditLog: Binding<AppModule, Unit, String>" in app.generatedSource("sample/app", "InaraAppModule.kt"))
    }

    @Test
    fun outOfDateLibrary_failsAtStartup() {
        // `repoA` is gone: without the check, building BusinessModule would fail reading it off the
        // Part with a NoSuchMethodError.
        val domainAV2 = domainA("repository")
        val error = assertFailsWith<IllegalStateException> { runWith("sample.business", business, domainAV2, domainB, base) }
        assertEquals(
            "'sample.business.BusinessModule' was compiled against another version of 'sample.a.DomainAModule' " +
                "than the one it runs with: recompile the library declaring 'sample.business.BusinessModule' against it.",
            error.message,
        )
    }

    @Test
    fun upToDateLibrary_runs() {
        val result = runWith("sample.business", business, domainAV1, domainB, base) as String
        assertTrue("val repoA: String => single(Synchronized) { String }" in result, result)
    }

    /**
     * Every kind of member and type a signature renders, seen once from source (the library's own
     * hash) and once compiled (the hash a module extending it records): they must be equal, or the
     * startup check would fail for an up to date library.
     */
    @Test
    fun signatureOfACompiledModule_isTheOneComputedFromItsSource() {
        val types = library(
            "Types",
            """
            package sample.types

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.DISet

            typealias Handler<T> = (T) -> Unit
            typealias Name = String

            @DISet(of = Any::class)
            annotation class Services

            interface Plain<T> {
                val plain: T
            }

            @DIModule(sets = [Services::class])
            interface TypesModule : Plain<List<String>> {
                val name: Name
                val handler: Handler<Int>
                val maybe: Map<String, List<Int?>>?
                val star: List<*>
                val suspender: suspend (String) -> Int get() = { it.length }
                val variance: Comparable<in String>
                fun many(vararg ids: Int): Int
                fun pair(a: String, b: Int): String
                suspend fun load(id: Int): String = "loaded"
                val label: String get() = "types"
                @Services val service: Any
                fun <T> ignored(): T? = null
            }
            """,
        )
        val middle = library(
            "Middle",
            """
            package sample.middle

            import org.kodein.di.inara.DIModule
            import org.kodein.di.inara.Binding.Bindings.factory
            import org.kodein.di.inara.Binding.Bindings.value
            import sample.types.*

            @DIModule
            interface MiddleModule : TypesModule {
                val extra: String
            }

            object Harness {
                @JvmStatic
                fun run(): Any = MiddleModule(
                    plain = value(listOf("p")),
                    name = value("n"),
                    handler = value { _: Int -> },
                    maybe = value(null),
                    star = value(emptyList<Any>()),
                    variance = value("v"),
                    many = factory { it.sum() },
                    pair = factory { "${'$'}{it.a}${'$'}{it.b}" },
                    service = value("s"),
                    extra = value("e"),
                ).pair("a", 1)
            }
            """,
            types,
        )
        assertEquals("a1", runWith("sample.middle", middle, types))
    }

    @Test
    fun libraryCompiledWithoutInara_isACompileError() {
        val library = compile(
            listOf(
                SourceFile.kotlin(
                    "Lib.kt",
                    """
                    package sample.lib

                    import org.kodein.di.inara.DIModule

                    @DIModule
                    interface LibModule {
                        val name: String
                    }
                    """.trimIndent(),
                )
            ),
            withProcessor = false,
        )
        assertEquals(ExitCode.OK, library.exitCode, library.messages)
        val app = compile(listOf(composedOver("LibModule")), listOf(library))
        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode, app.messages)
        assertTrue(
            "'LibModule' has no code generated by Inara: the library declaring it must run the Inara processor." in app.messages,
            app.messages,
        )
    }

    @Test
    fun nonComposedModuleExtendingALibraryCompiledByAnotherInaraVersion_isNotChecked() {
        val library = compile(listOf(legacyLibrary), withProcessor = false)
        assertEquals(ExitCode.OK, library.exitCode, library.messages)
        val app = compile(
            listOf(
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package sample.app

                    import org.kodein.di.inara.DIModule
                    import sample.lib.*

                    @DIModule
                    interface AppModule : LibModule
                    """.trimIndent(),
                )
            ),
            listOf(library),
        )
        assertEquals(ExitCode.OK, app.exitCode, app.messages)
        assertTrue("inaraCheckSignatures" !in app.generatedSource("sample/app", "InaraAppModule.kt"))
    }

    private val legacyLibrary = SourceFile.kotlin(
        "Lib.kt",
        """
        @file:OptIn(InternalInaraGeneration::class)
        package sample.lib

        import org.kodein.di.inara.*

        @DIModule
        interface LibModule {
            val name: String
        }

        @InaraGenerated(format = 0)
        class InaraLibModule(val nameBinding: Binding<LibModule, Unit, String>) : LibModule {
            override val name: String get() = nameBinding.get(this, Unit)
        }
        """.trimIndent(),
    )

    @Test
    fun libraryCompiledByAnotherInaraVersion_isACompileError() {
        val library = compile(listOf(legacyLibrary), withProcessor = false)
        assertEquals(ExitCode.OK, library.exitCode, library.messages)
        val app = compile(listOf(composedOver("LibModule")), listOf(library))
        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode, app.messages)
        assertTrue(
            "'LibModule' was compiled by another version of Inara: recompile the library declaring it with this one." in app.messages,
            app.messages,
        )
    }

    private fun composedOver(parent: String) = SourceFile.kotlin(
        "App.kt",
        """
        package sample.app

        import org.kodein.di.inara.DIModule
        import sample.lib.*

        @DIModule(composed = true)
        interface AppModule : $parent {
            val appName: String
        }
        """.trimIndent(),
    )
}
