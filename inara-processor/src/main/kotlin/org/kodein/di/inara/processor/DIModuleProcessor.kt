package org.kodein.di.inara.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ksp.writeTo

private const val DI_MODULE_ANNOTATION = "org.kodein.di.inara.DIModule"

/**
 * For every interface annotated with `@DIModule`, generates a private `Binding`-backed
 * implementation and a builder function named after the interface. See [parseModule] for the
 * validation rules and [generateModuleFile] for the generated shape.
 */
public class DIModuleProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (symbol in resolver.getSymbolsWithAnnotation(DI_MODULE_ANNOTATION)) {
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }

            val classDecl = symbol as? KSClassDeclaration
            if (classDecl == null) {
                logger.error("@DIModule can only be applied to interfaces.", symbol)
                continue
            }

            val model = parseModule(classDecl, logger) ?: continue
            generateModuleFile(model).writeTo(codeGenerator, aggregating = false)
        }

        return deferred
    }
}
