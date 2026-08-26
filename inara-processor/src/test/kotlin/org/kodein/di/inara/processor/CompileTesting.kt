package org.kodein.di.inara.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Compiles [sources] through kctfork with [DIModuleProcessorProvider] wired in, inheriting the
 * test classpath so sources can reference `org.kodein.di.inara.Binding` and `@DIModule` from
 * `inara-runtime`.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun compile(vararg sources: SourceFile): CompileTestResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
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

    /**
     * Reads the KSP-generated Kotlin source at `<packagePath>/<fileName>`, e.g.
     * `generatedSource("org/kodein/di/inara/sample", "GeneratedNetworkModule.kt")`.
     */
    fun generatedSource(packagePath: String, fileName: String): String =
        compilation.kspSourcesDir.resolve("kotlin").resolve(packagePath).resolve(fileName).readText()
}
