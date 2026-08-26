package org.kodein.di.inara.processor

import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
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
private val BINDING_IMPLS_DELEGATE = ClassName("org.kodein.di.inara", "BindingImpls").nestedClass("Delegate")
private val SUSPEND_BINDING_IMPLS_DELEGATE = ClassName("org.kodein.di.inara", "SuspendBindingImpls").nestedClass("SuspendDelegate")
private val INTERNAL_DI_BINDINGS = ClassName("org.kodein.di.inara", "InternalDIBindings")
private val OPT_IN_INTERNAL_DI_BINDINGS: AnnotationSpec = AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
    .addMember("%T::class", INTERNAL_DI_BINDINGS)
    .build()

/**
 * The `Binding`/`SuspendBinding` type of [member], parameterized on [moduleClassName].
 *
 * [nullable] defaults to [MemberModel.hasDefaultImplementation] - a parameter (whether on a builder
 * function or on a `copy` function, see [buildMemberOverrideCopyFunction]) is nullable exactly when
 * the member has a default body, `null` meaning "run that default body". A member with no default
 * implementation has no such optional case, so its parameter is never nullable.
 */
private fun bindingTypeOf(moduleClassName: ClassName, member: MemberModel, nullable: Boolean = member.hasDefaultImplementation): TypeName {
    val argTypeName = member.argType?.toTypeName() ?: UNIT
    val returnTypeName = member.returnType.toTypeName()
    val bindingClass = if (member.isSuspend) SUSPEND_BINDING else BINDING
    val bindingType = bindingClass.parameterizedBy(moduleClassName, argTypeName, returnTypeName)
    return if (nullable) bindingType.copy(nullable = true) else bindingType
}

/**
 * `BindingImpls.Delegate { this@copy.<member> }` (`SuspendBindingImpls.SuspendDelegate` for a
 * `suspend` member): a binding that reads [member]'s *current* value off the `copy` receiver. Used
 * as a `copy` parameter's default value, so omitting it preserves the module's existing behavior -
 * default implementation or bound value alike - rather than reverting to anything.
 */
private fun copyDelegateDefaultCode(member: MemberModel): CodeBlock {
    val delegateClass = if (member.isSuspend) SUSPEND_BINDING_IMPLS_DELEGATE else BINDING_IMPLS_DELEGATE
    val accessCode = when {
        !member.isFunction -> CodeBlock.of("this@copy.%N", member.name)
        member.paramName != null -> CodeBlock.of("this@copy.%N(it)", member.name)
        else -> CodeBlock.of("this@copy.%N()", member.name)
    }
    return CodeBlock.of("%T { %L }", delegateClass, accessCode)
}

/**
 * Emits the implementation class(es), builder function(s), and `copy` function(s) for [model], as
 * described on `@DIModule` / `@ComposedDIModule`.
 *
 * A module that isn't `@ComposedDIModule` (see [ModuleModel.isComposed]) gets exactly one
 * `Generated<Module>` implementation and one `<Module>(...)` builder taking a `Binding`
 * (`SuspendBinding` for a `suspend` member) per member in the consolidated interface - inherited
 * members included, so a `@DIModule` interface that merely extends other `@DIModule` interfaces
 * (without composing them) still gets a binding for everything it inherits, exactly as if those
 * members had been declared directly on it.
 *
 * A `@ComposedDIModule` interface gets two pairs instead:
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
 *
 * See [buildCopyFunctions] for the `copy` function(s) appended after these.
 */
internal fun generateModuleFile(model: ModuleModel): FileSpec {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"

    val originatingFiles: List<KSFile> =
        listOfNotNull(model.declaration.containingFile) + model.ancestorDeclarations.mapNotNull { it.containingFile }

    val fileBuilder = FileSpec.builder(moduleClassName.packageName, generatedClassName)

    if (!model.isComposed) {
        val (typeSpec, builderFun) = buildModuleType(
            generatedClassName = generatedClassName,
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            visibility = model.visibility,
            members = model.allMembers,
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

    for (copyFun in buildCopyFunctions(model)) {
        fileBuilder.addFunction(copyFun)
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
    fun bindingTypeOf(member: MemberModel): TypeName = bindingTypeOf(moduleClassName, member)

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

/**
 * Emits every `copy` extension function for [model]:
 * - a no-argument `copy()` that returns the receiver unchanged, so a copy with no overrides doesn't
 *   allocate one. Always emitted.
 * - a `copy(...)` taking one `Binding`/`SuspendBinding` per member in [ModuleModel.allMembers],
 *   building through the "full" `Generated<Module>` class. Only emitted when there is at least one
 *   member - with none, it would take no parameters and collide with the no-arg overload. See
 *   [buildMemberOverrideCopyFunction] for how each parameter's default differs depending on whether
 *   the member has a default implementation.
 * - if [ModuleModel.isComposed], an extra `copy(...)` taking one already-built module instance per
 *   `@DIModule` parent (each defaulting to the receiver itself), building through the "delegated"
 *   `GeneratedDelegated<Module>` class - lets a caller swap out a whole parent module at once
 *   instead of rebinding its members individually.
 */
internal fun buildCopyFunctions(model: ModuleModel): List<FunSpec> {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"

    val noArgCopy = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .apply { if (model.visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .addStatement("return this")
        .build()

    val copyFunctions = mutableListOf(noArgCopy)

    if (model.allMembers.isNotEmpty()) {
        copyFunctions += buildMemberOverrideCopyFunction(moduleClassName, generatedClassName, model.allMembers, model.visibility)
    }

    if (model.isComposed) {
        val delegatedClassName = "GeneratedDelegated${model.generatedBaseName}"
        copyFunctions += buildModuleOverrideCopyFunction(moduleClassName, delegatedClassName, model.ownMembers, model.parents, model.visibility)
    }

    return copyFunctions
}

/**
 * The `copy(...)` overload that lets any individual member's binding be overridden.
 *
 * A member with a default implementation (see [MemberModel.hasDefaultImplementation]) keeps the
 * exact same optional, nullable parameter the builder function gives it - `null` still means "run
 * the interface's own default body", now re-evaluated against the *new* module (so it sees any
 * other member overridden in the same `copy` call). It can *not* instead default to a delegate
 * reading the member's current value the way every other member's parameter does: that current
 * value was itself computed by the default body reading the *old* module, so freezing it would
 * silently stop the member from reacting to sibling overrides in this very call - defeating the
 * point of overriding a sibling in the first place.
 *
 * A member with no default implementation has no such body to re-run, so its current value - a
 * fixed, previously-bound `Binding` - is exactly what "unchanged" means; that value is safe to
 * freeze via [copyDelegateDefaultCode].
 */
private fun buildMemberOverrideCopyFunction(
    moduleClassName: ClassName,
    generatedClassName: String,
    members: List<MemberModel>,
    visibility: Visibility,
): FunSpec {
    val funBuilder = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    // BindingImpls.Delegate is only used for a member with no default implementation - if every
    // member has one, this overload never touches it and doesn't need the opt-in.
    if (members.any { !it.hasDefaultImplementation }) {
        funBuilder.addAnnotation(OPT_IN_INTERNAL_DI_BINDINGS)
    }

    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, member))
        if (member.hasDefaultImplementation) {
            paramBuilder.defaultValue("null")
        } else {
            paramBuilder.defaultValue(copyDelegateDefaultCode(member))
        }
        funBuilder.addParameter(paramBuilder.build())
    }

    val callCode = CodeBlock.builder()
        .add("return %N(\n", generatedClassName)
        .indent()
        .apply {
            for (member in members) {
                addStatement("%N = %N,", "${member.name}Binding", member.name)
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return funBuilder.addCode(callCode).build()
}

/** The `copy(...)` overload that lets a whole `@DIModule` parent be swapped out at once. */
private fun buildModuleOverrideCopyFunction(
    moduleClassName: ClassName,
    generatedClassName: String,
    ownMembers: List<MemberModel>,
    parents: List<ParentModel>,
    visibility: Visibility,
): FunSpec {
    val funBuilder = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    // An own member with no default implementation is rebound via a Delegate (see
    // buildMemberOverrideCopyFunction for why a default-body member isn't) - @OptIn is only
    // needed when there is at least one such member.
    if (ownMembers.any { !it.hasDefaultImplementation }) {
        funBuilder.addAnnotation(OPT_IN_INTERNAL_DI_BINDINGS)
    }

    for (parent in parents) {
        funBuilder.addParameter(
            ParameterSpec.builder(parent.paramName, parent.moduleClassName)
                .defaultValue("this")
                .build()
        )
    }

    val callCode = CodeBlock.builder()
        .add("return %N(\n", generatedClassName)
        .indent()
        .apply {
            for (parent in parents) {
                addStatement("%N = %N,", parent.paramName, parent.paramName)
            }
            for (member in ownMembers) {
                val valueCode = if (member.hasDefaultImplementation) CodeBlock.of("null") else copyDelegateDefaultCode(member)
                addStatement("%N = %L,", "${member.name}Binding", valueCode)
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return funBuilder.addCode(callCode).build()
}
