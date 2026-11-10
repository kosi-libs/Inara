package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

/**
 * Compiles [sources] through kctfork with [DIModuleProcessorProvider] wired in, inheriting the
 * test classpath so sources can reference `org.kodein.di.inara.Binding`,
 * `org.kodein.di.inara.SuspendBinding`, and `@DIModule` from `inara-runtime`.
 */
internal fun compile(vararg sources: SourceFile): CompileTestResult = compile(sources.toList())

/**
 * Same as the vararg overload, with [dependencies] - earlier compilations, e.g. a library - added to
 * the classpath: their declarations are then only seen compiled, never as source, exactly like a
 * declaration from another Gradle module.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun compile(sources: List<SourceFile>, dependencies: List<CompileTestResult> = emptyList()): CompileTestResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources
        this.jvmTarget = "17"
        inheritClassPath = true
        classpaths = dependencies.map { it.outputDirectory }
        configureKsp {
            symbolProcessorProviders += DIModuleProcessorProvider()
        }
    }
    return CompileTestResult(compilation, compilation.compile())
}

@OptIn(ExperimentalCompilerApi::class)
internal class CompileTestResult(
    private val compilation: KotlinCompilation,
    private val result: JvmCompilationResult,
) {
    val exitCode: KotlinCompilation.ExitCode get() = result.exitCode
    val messages: String get() = result.messages
    val classLoader: ClassLoader get() = result.classLoader
    val outputDirectory: File get() = result.outputDirectory

    /**
     * Reads the KSP-generated Kotlin source at `<packagePath>/<fileName>`, e.g.
     * `generatedSource("org/kodein/di/inara/sample", "InaraNetworkModule.kt")`.
     */
    fun generatedSource(packagePath: String, fileName: String): String =
        compilation.kspSourcesDir.resolve("kotlin").resolve(packagePath).resolve(fileName).readText()
}
