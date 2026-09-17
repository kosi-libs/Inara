package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end check of the debug `toString()` the processor generates on every `Inara<Module>` (see
 * `ModuleFileGenerator.buildToStringFunction`), exercised through real Gradle/KSP-generated code
 * rather than the processor's own golden tests.
 */
class ModuleToStringTest {

    @Test
    fun networkModule_listsEachMemberAndItsBinding() {
        val module = NetworkModule(
            baseUrl = Binding.value("http://example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        assertEquals(
            "module NetworkModule {\n" +
                "    val baseUrl: String => value ( String )\n" +
                "    fun httpClient(): HttpClient => single(Synchronized) { HttpClient }\n" +
                "    fun endpoint(path: String): String => factory { String -> String }\n" +
                "}",
            module.toString(),
        )
    }

    @Test
    fun asyncModule_showsSuspendBindingsAndTheDefaultMarker() {
        // `described` is left unbound - its binding is `null`, so its line falls back to the
        // `[default]` marker. `load`/`loadKeyed` render identically to a blocking `provider`/
        // `factory` - the "suspend fun" already on the line's own signature is what tells them apart.
        val module = AsyncModule(
            name = Binding.value("mod"),
            load = SuspendBinding.provider { Service(1) },
            loadKeyed = SuspendBinding.factory { key -> Service(key.length) },
        )

        assertEquals(
            "module AsyncModule {\n" +
                "    val name: String => value ( String )\n" +
                "    suspend fun load(): Service => provider { Service }\n" +
                "    suspend fun loadKeyed(key: String): Service => factory { String -> Service }\n" +
                "    suspend fun described(): String => [default]\n" +
                "}",
            module.toString(),
        )
    }

    @Test
    fun multiParamModule_rendersVarargAndMultiParamSignatures() {
        val module = MultiParamModule(
            va = Binding.factory { it.joinToString() },
            mult = Binding.factory { it.i.toLong() },
            both = Binding.factory { it.flag.toString() },
        )

        assertEquals(
            "module MultiParamModule {\n" +
                "    fun va(vararg str: String): String => factory { Array -> String }\n" +
                "    fun mult(s: List<String>, i: Int): Long => factory { Mult -> Long }\n" +
                "    fun both(vararg ids: Int, flag: Boolean): String => factory { Both -> String }\n" +
                "}",
            module.toString(),
        )
    }

    @Test
    fun composedModule_listsInheritedMembersUnmarkedAlongsideItsOwn() {
        // Built through the from-parents overload: the real InaraMultiParamModule instance lets a
        // single-parameter member's binding (`va`) carry across as-is, while a multi-parameter one
        // (`mult`, `both`) always falls back to a delegate naming that same instance's class - see
        // `parentBindingCode`. Both cases are listed the same way, unmarked, alongside the module's
        // own `tag`.
        val parent = MultiParamModule(
            va = Binding.factory { it.joinToString() },
            mult = Binding.factory { it.i.toLong() },
            both = Binding.factory { it.flag.toString() },
        )
        val composed = ComposedMultiParamModule(parent, tag = Binding.value("x"))

        assertEquals(
            "module ComposedMultiParamModule {\n" +
                "    fun va(vararg str: String): String => factory { Array -> String }\n" +
                "    fun mult(s: List<String>, i: Int): Long => [delegated to InaraMultiParamModule]\n" +
                "    fun both(vararg ids: Int, flag: Boolean): String => [delegated to InaraMultiParamModule]\n" +
                "    val tag: String => value ( String )\n" +
                "}",
            composed.toString(),
        )
    }
}
