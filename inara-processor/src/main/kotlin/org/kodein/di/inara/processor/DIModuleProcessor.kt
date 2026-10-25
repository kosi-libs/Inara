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
 * taking its `@DIModule` parents as built instances, plus a parent-swapping `copy` overload. Every
 * module also gets `copy` and `shadow` extension functions. The
 * interface may extend any interface, not just other `@DIModule` ones - a plain supertype's members
 * are bound exactly like the module's own. See [parseModule] for the validation rules and
 * [generateModuleFile] for the generated shape(s).
 *
 * For every `public` or `internal` object annotated with `@DIFactory`, generates - per `@DIFactory`
 * annotation (it is `@Repeatable`) - a builder function for the class it `builds`, an extension on
 * that object, resolving each argument of that class's selected constructor (see `constructorIndex`)
 * off an instance of the `@DIModule` interface it's declared to build `from` - so the built class
 * itself never needs to know Inara exists. All of one object's builder functions land in one file.
 * See [parseFactories] for the resolution rules and [generateFactoryFile] for the generated shape.
 */
public class DIModuleProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        // Round-scoped: KSP invalidates KSNodes between rounds, so this must not outlive process().
        // Shared between the two loops so a @DIFactory's `from` module is parsed (and its diagnostics
        // reported) once, not again per factory - see parseModule.
        val moduleCache = mutableMapOf<String, ModuleModel?>()

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

            val model = parseModule(classDecl, logger, moduleCache) ?: continue
            generateModuleFile(model).writeTo(codeGenerator, aggregating = false)
        }

        // `@DIFactory` is `@Repeatable`. KSP 2 surfaces the object once here and lists the individual
        // annotations on it; the `.Container` query and the `seen` guard are belt-and-braces for
        // KSP versions/platforms that instead report the synthetic container symbol.
        val seenFactoryObjects = mutableSetOf<String>()
        val factorySymbols = resolver.getSymbolsWithAnnotation(DI_FACTORY_ANNOTATION) +
            resolver.getSymbolsWithAnnotation(DI_FACTORY_CONTAINER_ANNOTATION)
        for (symbol in factorySymbols) {
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }

            val objDecl = symbol as? KSClassDeclaration
            if (objDecl == null) {
                logger.error("@DIFactory can only be applied to objects.", symbol)
                continue
            }
            if (!seenFactoryObjects.add(objDecl.qualifiedName?.asString() ?: continue)) continue

            val models = parseFactories(objDecl, logger, moduleCache)
            if (models.isNotEmpty()) generateFactoryFile(models).writeTo(codeGenerator, aggregating = false)
        }

        return deferred
    }
}
