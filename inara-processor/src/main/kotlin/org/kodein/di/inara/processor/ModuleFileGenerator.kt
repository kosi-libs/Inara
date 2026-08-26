package org.kodein.di.inara.processor

import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName

private val BINDING = ClassName("org.kodein.di.inara", "Binding")
private val SUSPEND_BINDING = ClassName("org.kodein.di.inara", "SuspendBinding")

/**
 * Emits the `Generated<Module>` private implementation and the `<Module>(...)` builder function
 * for [model], as described on `@DIModule`.
 *
 * A member with a default implementation (see [MemberModel.hasDefaultImplementation]) gets a
 * nullable `Binding` that defaults to `null` in the builder function: `null` keeps the
 * interface's own default body, any other value overrides it. A `suspend` member is backed by a
 * `SuspendBinding` instead of a `Binding`, so its provider/factory/single body may itself suspend.
 */
internal fun generateModuleFile(model: ModuleModel): FileSpec {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"

    fun bindingTypeOf(member: MemberModel): TypeName {
        val argTypeName = member.argType?.toTypeName() ?: UNIT
        val returnTypeName = member.returnType.toTypeName()
        val bindingClass = if (member.isSuspend) SUSPEND_BINDING else BINDING
        val bindingType = bindingClass.parameterizedBy(moduleClassName, argTypeName, returnTypeName)
        return if (member.hasDefaultImplementation) bindingType.copy(nullable = true) else bindingType
    }

    val classBuilder = TypeSpec.classBuilder(generatedClassName)
        .addModifiers(KModifier.PRIVATE)
        .addSuperinterface(moduleClassName)
        .addOriginatingKSFile(model.declaration.containingFile!!)
    val constructorBuilder = FunSpec.constructorBuilder()

    for (member in model.members) {
        val bindingParamName = "${member.name}Binding"
        val bindingType = bindingTypeOf(member)

        constructorBuilder.addParameter(bindingParamName, bindingType)
        classBuilder.addProperty(
            PropertySpec.builder(bindingParamName, bindingType)
                .addModifiers(KModifier.PRIVATE)
                .initializer(bindingParamName)
                .build()
        )

        if (member.isFunction) {
            val funBuilder = FunSpec.builder(member.name)
                .addModifiers(KModifier.OVERRIDE)
                .returns(member.returnType.toTypeName())
            if (member.isSuspend) {
                funBuilder.addModifiers(KModifier.SUSPEND)
            }
            val argCode: CodeBlock
            if (member.paramName != null && member.argType != null) {
                funBuilder.addParameter(member.paramName, member.argType.toTypeName())
                argCode = CodeBlock.of("%N", member.paramName)
            } else {
                argCode = CodeBlock.of("Unit")
            }
            if (member.hasDefaultImplementation) {
                val defaultArgCode = member.paramName?.let { CodeBlock.of("%N", it) } ?: CodeBlock.of("")
                funBuilder.addStatement(
                    "return if (this.%N == null) super<%T>.%N(%L) else this.%N.get(this, %L)",
                    bindingParamName, moduleClassName, member.name, defaultArgCode, bindingParamName, argCode,
                )
            } else {
                funBuilder.addStatement("return this.%N.get(this, %L)", bindingParamName, argCode)
            }
            classBuilder.addFunction(funBuilder.build())
        } else {
            val getterBuilder = FunSpec.getterBuilder()
            if (member.hasDefaultImplementation) {
                getterBuilder.addStatement(
                    "return if (this.%N == null) super<%T>.%N else this.%N.get(this, Unit)",
                    bindingParamName, moduleClassName, member.name, bindingParamName,
                )
            } else {
                getterBuilder.addStatement("return this.%N.get(this, Unit)", bindingParamName)
            }
            classBuilder.addProperty(
                PropertySpec.builder(member.name, member.returnType.toTypeName())
                    .addModifiers(KModifier.OVERRIDE)
                    .getter(getterBuilder.build())
                    .build()
            )
        }
    }
    classBuilder.primaryConstructor(constructorBuilder.build())

    val builderFun = FunSpec.builder(model.simpleName)
        .returns(moduleClassName)
    if (model.visibility == Visibility.INTERNAL) {
        builderFun.addModifiers(KModifier.INTERNAL)
    }
    for (member in model.members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(member))
        if (member.hasDefaultImplementation) {
            paramBuilder.defaultValue("null")
        }
        builderFun.addParameter(paramBuilder.build())
    }

    val callCode = CodeBlock.builder()
        .add("return %N(\n", generatedClassName)
        .indent()
        .apply {
            for (member in model.members) {
                addStatement("%N = %N,", "${member.name}Binding", member.name)
            }
        }
        .unindent()
        .add(")\n")
        .build()
    builderFun.addCode(callCode)

    return FileSpec.builder(moduleClassName.packageName, generatedClassName)
        .addType(classBuilder.build())
        .addFunction(builderFun.build())
        .build()
}
