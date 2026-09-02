package org.kodein.di.inara.processor

import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import org.kodein.di.inara.processor.ArgumentSource.FactoryFunction
import org.kodein.di.inara.processor.ArgumentSource.ModuleFunction
import org.kodein.di.inara.processor.ArgumentSource.ModuleProperty
import org.kodein.di.inara.processor.ArgumentSource.WithValue
import org.kodein.di.inara.processor.WithArgument.Named
import org.kodein.di.inara.processor.WithArgument.Positional

/**
 * Emits the generated builder function for [model]: an **extension function on the `@DIFactory`
 * object**, named after `builds`, taking a single `from`-typed `module` parameter, that resolves
 * every constructor argument off `module` (see [FactoryModel.arguments]). The object being the
 * extension receiver is what puts its own `arg<Argument>` extension functions in scope - no
 * `with(<object>) { ... }` wrapper - and makes the function discoverable from the object.
 *
 * The file is named after the `@DIFactory` object rather than `builds`, and lives in the object's
 * own package - two different `@DIFactory` objects could otherwise target `builds` classes that
 * happen to share a simple name. The object's name is flattened across its enclosing scopes
 * (`OuterA.Fac` -> `InaraOuterAFac`, see [FactoryModel.generatedBaseName]), because Kotlin's
 * name-uniqueness guarantee only covers _top-level_ objects - two `Fac` objects nested in different
 * enclosing classes of one package would otherwise both claim `InaraFac.kt`.
 */
internal fun generateFactoryFile(model: FactoryModel): FileSpec {
    val fileBuilder = FileSpec.builder(model.factoryClassName.packageName, inaraName(model.generatedBaseName))
    fileBuilder.addFunction(buildFactoryFunction(model))
    return fileBuilder.build()
}

private fun buildFactoryFunction(model: FactoryModel): FunSpec {
    val funBuilder = FunSpec.builder(model.buildsClassName.simpleName)
        .receiver(model.factoryClassName)
        .returns(model.buildsClassName)
    for (file in model.originatingFiles) {
        funBuilder.addOriginatingKSFile(file)
    }
    if (model.visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    if (model.isSuspend) {
        funBuilder.addModifiers(KModifier.SUSPEND)
    }
    funBuilder.addParameter("module", model.fromClassName)
    model.withTypeName?.let { funBuilder.addParameter("with", it) }

    // The `with` parameter's value, passed to every arg-function and to any one-parameter module
    // member when it exists.
    val withArg = if (model.withTypeName != null) CodeBlock.of("with") else null

    // Every constructor argument the model carries is emitted as a named argument; any parameter it
    // omits (an `arg<Argument>`-less default or vararg) falls back to its own declared default. When
    // the model carries no arguments at all, that is a bare `Builds()` - no newline, nothing to
    // indent.
    val constructorCall = if (model.arguments.isEmpty()) {
        CodeBlock.of("%T()", model.buildsClassName)
    } else {
        CodeBlock.builder()
            .add("%T(\n", model.buildsClassName)
            .indent()
            .apply {
                for (argument in model.arguments) {
                    val accessCode = when (val source = argument.source) {
                        is FactoryFunction ->
                            if (withArg != null) CodeBlock.of("module.%N(%L)", source.functionName, withArg)
                            else CodeBlock.of("module.%N()", source.functionName)
                        is WithValue -> checkNotNull(withArg) { "WithValue without a `with` parameter" }
                        is ModuleProperty -> CodeBlock.of("module.%N", source.memberName)
                        is ModuleFunction -> when (val w = source.withArgument) {
                            null -> CodeBlock.of("module.%N()", source.memberName)
                            is Positional -> CodeBlock.of("module.%N(%L)", source.memberName, withArg)
                            is Named -> CodeBlock.of("module.%N(%N = %L)", source.memberName, w.parameterName, withArg)
                        }
                    }
                    addStatement("%N = %L,", argument.paramName, accessCode)
                }
            }
            .unindent()
            .add(")")
            .build()
    }

    funBuilder.addCode(CodeBlock.builder().add("return %L\n", constructorCall).build())

    return funBuilder.build()
}
