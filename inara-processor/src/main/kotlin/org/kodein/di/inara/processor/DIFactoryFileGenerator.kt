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
import org.kodein.di.inara.processor.ArgumentSource.PassThrough
import org.kodein.di.inara.processor.ArgumentSource.WithValue
import org.kodein.di.inara.processor.WithArgument.Named
import org.kodein.di.inara.processor.WithArgument.Positional

/**
 * Emits one file for a `@DIFactory` object holding one builder function per [models] entry (one per
 * `@DIFactory` annotation - it is `@Repeatable`). Each is an **extension function on the object**,
 * named after its `builds`, taking a `from`-typed `module` parameter - optionally followed by a
 * call-time `with` parameter, and by one `arg<Argument>`-named parameter per
 * [FactoryModel.passThroughParameters] for a constructor argument nothing else could resolve - that
 * resolves every constructor argument off `module`, `with`, or its own matching parameter (see
 * [FactoryModel.arguments]). The three prefixes are mutually exclusive by construction, so a
 * generated signature is legible at a glance: one `module`, zero or one `with`, zero or more `arg*`.
 * The object being the extension receiver is what puts its own `arg<Argument>` extension functions
 * in scope - no `with(<object>) { ... }` wrapper - and makes the functions discoverable from the
 * object.
 *
 * The file is named after the `@DIFactory` object rather than `builds`, and lives in the object's
 * own package - two different `@DIFactory` objects could otherwise target `builds` classes that
 * happen to share a simple name. The object's name is flattened across its enclosing scopes
 * (`OuterA.Fac` -> `InaraOuterAFac`, see [FactoryModel.generatedBaseName]), because Kotlin's
 * name-uniqueness guarantee only covers _top-level_ objects - two `Fac` objects nested in different
 * enclosing classes of one package would otherwise both claim `InaraFac.kt`.
 */
internal fun generateFactoryFile(models: List<FactoryModel>): FileSpec {
    require(models.isNotEmpty()) { "generateFactoryFile called with no models" }
    val fileBuilder = FileSpec.builder(models[0].factoryClassName.packageName, inaraName(models[0].generatedBaseName))
    models.forEach { fileBuilder.addFunction(buildFactoryFunction(it)) }
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
    model.passThroughParameters.forEach { funBuilder.addParameter(it.name, it.typeName) }

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
                        is PassThrough -> CodeBlock.of("%N", source.functionParamName)
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
