package org.kodein.di.inara.processor

import com.google.devtools.ksp.symbol.KSFile
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
 * Emits the implementation class(es) and builder function(s) for [model], as described on
 * `@DIModule`.
 *
 * A module with no `@DIModule` parents gets exactly one `Generated<Module>` implementation and one
 * `<Module>(...)` builder taking a `Binding` (`SuspendBinding` for a `suspend` member) per member -
 * unchanged from before module inheritance existed.
 *
 * A module that extends other `@DIModule` interfaces gets two pairs instead:
 * - `GeneratedDelegated<Module>` / a builder taking one already-built parent module instance per
 *   `@DIModule` parent (`Module1 by module1`-style delegation) plus a binding per member declared
 *   directly on this interface.
 * - `Generated<Module>` / a builder taking a binding for every member in the consolidated
 *   interface, inherited ones included, each parameterized on this (leaf) module type - so any
 *   provider/factory/single body can reach any member, inherited or not, through its receiver.
 *
 * A member with a default implementation (see [MemberModel.hasDefaultImplementation]) gets a
 * nullable `Binding`/`SuspendBinding` that defaults to `null` in the builder function: `null` keeps
 * the interface's own default body, any other value overrides it.
 */
internal fun generateModuleFile(model: ModuleModel): FileSpec {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"

    val originatingFiles: List<KSFile> =
        listOfNotNull(model.declaration.containingFile) + model.ancestorDeclarations.mapNotNull { it.containingFile }

    val fileBuilder = FileSpec.builder(moduleClassName.packageName, generatedClassName)

    if (model.parents.isEmpty()) {
        val (typeSpec, builderFun) = buildModuleType(
            generatedClassName = generatedClassName,
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            visibility = model.visibility,
            members = model.ownMembers,
            parents = emptyList(),
            originatingFiles = originatingFiles,
        )
        fileBuilder.addType(typeSpec).addFunction(builderFun)
    } else {
        val (delegatedType, delegatedBuilderFun) = buildModuleType(
            generatedClassName = "GeneratedDelegated${model.generatedBaseName}",
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            visibility = model.visibility,
            members = model.ownMembers,
            parents = model.parents,
            originatingFiles = originatingFiles,
        )
        val (fullType, fullBuilderFun) = buildModuleType(
            generatedClassName = generatedClassName,
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            visibility = model.visibility,
            members = model.allMembers,
            parents = emptyList(),
            originatingFiles = originatingFiles,
        )
        fileBuilder.addType(delegatedType).addFunction(delegatedBuilderFun)
        fileBuilder.addType(fullType).addFunction(fullBuilderFun)
    }

    return fileBuilder.build()
}

/**
 * Builds one `Generated<...>` implementation class and its matching [builderName] builder
 * function: a constructor/builder parameter of [parents]'s module type per parent (delegated via
 * `by`), then one `Binding`/`SuspendBinding` parameter per member in [members].
 */
private fun buildModuleType(
    generatedClassName: String,
    builderName: String,
    moduleClassName: ClassName,
    visibility: Visibility,
    members: List<MemberModel>,
    parents: List<ParentModel>,
    originatingFiles: List<KSFile>,
): Pair<TypeSpec, FunSpec> {
    fun bindingTypeOf(member: MemberModel): TypeName {
        val argTypeName = member.argType?.toTypeName() ?: UNIT
        val returnTypeName = member.returnType.toTypeName()
        val bindingClass = if (member.isSuspend) SUSPEND_BINDING else BINDING
        val bindingType = bindingClass.parameterizedBy(moduleClassName, argTypeName, returnTypeName)
        return if (member.hasDefaultImplementation) bindingType.copy(nullable = true) else bindingType
    }

    val classBuilder = TypeSpec.classBuilder(generatedClassName)
        .addModifiers(KModifier.PRIVATE)
    for (file in originatingFiles) {
        classBuilder.addOriginatingKSFile(file)
    }
    val constructorBuilder = FunSpec.constructorBuilder()

    for (parent in parents) {
        constructorBuilder.addParameter(parent.paramName, parent.moduleClassName)
    }

    for (member in members) {
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
    // addSuperinterface(type, constructorParameter) below requires the primary constructor to
    // already be set on the builder and to already contain that parameter.
    classBuilder.primaryConstructor(constructorBuilder.build())
    classBuilder.addSuperinterface(moduleClassName)
    for (parent in parents) {
        classBuilder.addSuperinterface(parent.moduleClassName, parent.paramName)
    }

    val builderFun = FunSpec.builder(builderName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        builderFun.addModifiers(KModifier.INTERNAL)
    }

    val callCode = CodeBlock.builder()
        .add("return %N(\n", generatedClassName)
        .indent()
        .apply {
            for (parent in parents) {
                addStatement("%N = %N,", parent.paramName, parent.paramName)
            }
            for (member in members) {
                addStatement("%N = %N,", "${member.name}Binding", member.name)
            }
        }
        .unindent()
        .add(")\n")
        .build()
    builderFun.addCode(callCode)

    for (parent in parents) {
        builderFun.addParameter(parent.paramName, parent.moduleClassName)
    }
    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(member))
        if (member.hasDefaultImplementation) {
            paramBuilder.defaultValue("null")
        }
        builderFun.addParameter(paramBuilder.build())
    }

    return classBuilder.build() to builderFun.build()
}
