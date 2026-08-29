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
import org.kodein.di.inara.processor.WithArgument.Named
import org.kodein.di.inara.processor.WithArgument.Positional

/**
 * Emits the generated builder function for [model]: a top-level function named after `builds`,
 * taking a single `from`-typed `module` parameter, that resolves every constructor argument off
 * `module` (see [FactoryModel.arguments]) inside a `with(<factory object>) { ... }` block, so the
 * object's own `arg<Argument>` extension functions are in scope.
 *
 * The file is named after the `@DIFactory` object rather than `builds`, and lives in the object's
 * own package - two different `@DIFactory` objects could otherwise target `builds` classes that
 * happen to share a simple name, whereas two objects in the same package can never collide (Kotlin
 * already guarantees that).
 */
internal fun generateFactoryFile(model: FactoryModel): FileSpec {
    val fileBuilder = FileSpec.builder(model.factoryClassName.packageName, inaraName(model.factoryClassName.simpleName))
    fileBuilder.addFunction(buildFactoryFunction(model))
    return fileBuilder.build()
}

private fun buildFactoryFunction(model: FactoryModel): FunSpec {
    val funBuilder = FunSpec.builder(model.buildsClassName.simpleName)
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

    // When there's a `with` parameter it's passed to every arg-function and to any one-parameter
    // module member; `with` as an argument name would otherwise shadow the `kotlin.with` scoping
    // function below, so that call is fully qualified whenever the parameter exists.
    val withArg = if (model.withTypeName != null) CodeBlock.of("with") else null

    val constructorCall = CodeBlock.builder()
        .add("%T(\n", model.buildsClassName)
        .indent()
        .apply {
            for (argument in model.arguments) {
                val accessCode = when (val source = argument.source) {
                    is FactoryFunction ->
                        if (withArg != null) CodeBlock.of("module.%N(%L)", source.functionName, withArg)
                        else CodeBlock.of("module.%N()", source.functionName)
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

    val scopeFunction = if (withArg != null) "kotlin.with" else "with"
    funBuilder.addCode(
        CodeBlock.builder()
            .add("return $scopeFunction(%T) {\n", model.factoryClassName)
            .indent()
            .add(constructorCall)
            .add("\n")
            .unindent()
            .add("}\n")
            .build()
    )

    return funBuilder.build()
}
