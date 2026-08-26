package org.kodein.di.inara.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * For every interface annotated with `@DIModule` or `@ComposedDIModule`, generates a private
 * `Binding`-backed implementation (`SuspendBinding` for `suspend` members) and a builder function
 * named after the interface. A `@ComposedDIModule` interface additionally gets a second, delegated
 * implementation/builder/`copy` set for its `@DIModule` parents. The interface may extend any
 * interface, not just other `@DIModule` ones - a plain supertype's members are bound exactly like
 * the module's own. See [parseModule] for the validation rules and [generateModuleFile] for the
 * generated shape(s).
 */
public class DIModuleProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        // An interface annotated with both @DIModule and @ComposedDIModule shows up under both
        // annotations - dedupe by qualified name (falling back to identity for anything that isn't
        // a KSClassDeclaration, so it is at least never silently dropped) so it is processed once.
        val symbols = (
            resolver.getSymbolsWithAnnotation(DI_MODULE_ANNOTATION) +
                resolver.getSymbolsWithAnnotation(COMPOSED_DI_MODULE_ANNOTATION)
            ).distinctBy { (it as? KSClassDeclaration)?.qualifiedName?.asString() ?: it }

        for (symbol in symbols) {
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }

            val classDecl = symbol as? KSClassDeclaration
            if (classDecl == null) {
                logger.error("@DIModule / @ComposedDIModule can only be applied to interfaces.", symbol)
                continue
            }

            val model = parseModule(classDecl, logger) ?: continue
            generateModuleFile(model).writeTo(codeGenerator, aggregating = false)
        }

        return deferred
    }
}
