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
 * For every interface annotated with `@DIModule`, generates a `Binding`-backed implementation
 * (`SuspendBinding` for `suspend` members) and a builder function named after the
 * interface. A `@DIModule(composed = true)` interface additionally gets a second builder overload
 * taking its `@DIModule` parents as built instances, plus a parent-swapping `copy` overload. The
 * interface may extend any interface, not just other `@DIModule` ones - a plain supertype's members
 * are bound exactly like the module's own. See [parseModule] for the validation rules and
 * [generateModuleFile] for the generated shape(s).
 *
 * For every `internal object` annotated with `@DIFactory`, generates a top-level builder function
 * for the class it `builds`, resolving each of that class's primary constructor arguments off an
 * instance of the `@DIModule` interface it's declared to build `from` - so the built class itself
 * never needs to know Inara exists. See [parseFactory] for the resolution rules and
 * [generateFactoryFile] for the generated shape.
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

        for (symbol in resolver.getSymbolsWithAnnotation(DI_FACTORY_ANNOTATION)) {
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }

            val objDecl = symbol as? KSClassDeclaration
            if (objDecl == null) {
                logger.error("@DIFactory can only be applied to objects.", symbol)
                continue
            }

            val model = parseFactory(objDecl, logger) ?: continue
            generateFactoryFile(model).writeTo(codeGenerator, aggregating = false)
        }

        return deferred
    }
}
