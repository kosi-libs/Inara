package org.kodein.di.inara.processor

import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.joinToCode
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

private val ARRAY = ClassName("kotlin", "Array")

/** Every Kotlin type with a dedicated primitive array type - a non-nullable vararg of one of these
 * uses it (`IntArray`, ...) instead of `Array<out T>`. */
private val PRIMITIVE_ARRAYS: Map<String, ClassName> = mapOf(
    "kotlin.Boolean" to ClassName("kotlin", "BooleanArray"),
    "kotlin.Byte" to ClassName("kotlin", "ByteArray"),
    "kotlin.Char" to ClassName("kotlin", "CharArray"),
    "kotlin.Double" to ClassName("kotlin", "DoubleArray"),
    "kotlin.Float" to ClassName("kotlin", "FloatArray"),
    "kotlin.Int" to ClassName("kotlin", "IntArray"),
    "kotlin.Long" to ClassName("kotlin", "LongArray"),
    "kotlin.Short" to ClassName("kotlin", "ShortArray"),
    "kotlin.UByte" to ClassName("kotlin", "UByteArray"),
    "kotlin.UInt" to ClassName("kotlin", "UIntArray"),
    "kotlin.ULong" to ClassName("kotlin", "ULongArray"),
    "kotlin.UShort" to ClassName("kotlin", "UShortArray"),
)

/**
 * The type of a vararg parameter as seen inside the function body (and used as a `Binding`
 * argument type / data class field type): a dedicated primitive array for a non-nullable primitive
 * element type, `Array<out ElementType>` otherwise. KSP has no direct way to ask for this - a
 * vararg parameter's own `type` is the *element* type, per Kotlin's declaration model.
 */
private fun varargArrayTypeName(elementType: KSType): TypeName {
    if (!elementType.isMarkedNullable) {
        PRIMITIVE_ARRAYS[elementType.declaration.qualifiedName?.asString()]?.let { return it }
    }
    return ARRAY.parameterizedBy(WildcardTypeName.producerOf(elementType.toTypeName()))
}

/** True for `Array<out ...>` or one of [PRIMITIVE_ARRAYS] - used to decide whether a generated data
 * class needs `@Suppress("ArrayInDataClass")`. */
private fun isArrayTypeName(typeName: TypeName): Boolean = when (typeName) {
    is ParameterizedTypeName -> typeName.rawType == ARRAY
    is ClassName -> typeName in PRIMITIVE_ARRAYS.values
    else -> false
}

/**
 * The `Binding`/`SuspendBinding` argument type (`A`) of [member]: `Unit` for no parameters, a
 * single parameter's own type (the vararg array type if it's a vararg), or - for two or more
 * parameters, vararg included - the generated `<argsClassName>.<Name>` data class.
 */
private fun argsTypeNameOf(argsClassName: ClassName, member: MemberModel): TypeName = when {
    member.parameters.isEmpty() -> UNIT
    member.parameters.size == 1 -> member.parameters[0].let { p ->
        if (p.isVararg) varargArrayTypeName(p.type) else p.type.toTypeName()
    }
    else -> argsClassName.nestedClass(member.argsClassSimpleName())
}

/**
 * The `Binding`/`SuspendBinding` type of [member], parameterized on [moduleClassName].
 *
 * [nullable] defaults to [MemberModel.hasDefaultImplementation] - a parameter (whether on a builder
 * function or on a `copy` function, see [buildMemberOverrideCopyFunction]) is nullable exactly when
 * the member has a default body, `null` meaning "run that default body". A member with no default
 * implementation has no such optional case, so its parameter is never nullable.
 */
private fun bindingTypeOf(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    member: MemberModel,
    nullable: Boolean = member.hasDefaultImplementation,
): TypeName {
    val argTypeName = argsTypeNameOf(argsClassName, member)
    val returnTypeName = member.returnType.toTypeName()
    val bindingClass = if (member.isSuspend) SUSPEND_BINDING else BINDING
    val bindingType = bindingClass.parameterizedBy(moduleClassName, argTypeName, returnTypeName)
    return if (nullable) bindingType.copy(nullable = true) else bindingType
}

/**
 * `BindingImpls.Delegate { this@copy.<member> }` (`SuspendBindingImpls.SuspendDelegate` for a
 * `suspend` member): a binding that reads [member]'s *current* value off the `copy` receiver on
 * every `get`. The *fallback* branch of [copyBindingDefaultCode] - used only when the receiver is
 * some hand-written `<Module>` implementation whose stored binding can't be reached; a generated
 * implementation's binding is carried across verbatim instead.
 *
 * A named argument assigns straight into a vararg parameter, no spread operator needed (using one
 * is flagged as redundant) - so a lone vararg parameter, and every parameter of a multi-parameter
 * member (vararg or not, matching each field's name on the generated arguments data class), is
 * passed named. A lone non-vararg parameter stays positional.
 */
private fun copyDelegateDefaultCode(member: MemberModel): CodeBlock {
    val delegateClass = if (member.isSuspend) SUSPEND_BINDING_IMPLS_DELEGATE else BINDING_IMPLS_DELEGATE
    val accessCode = when {
        !member.isFunction -> CodeBlock.of("this@copy.%N", member.name)
        member.parameters.isEmpty() -> CodeBlock.of("this@copy.%N()", member.name)
        member.parameters.size == 1 && !member.parameters[0].isVararg -> CodeBlock.of("this@copy.%N(it)", member.name)
        member.parameters.size == 1 -> member.parameters[0].let { p ->
            CodeBlock.of("this@copy.%N(%N = it)", member.name, p.name)
        }
        else -> CodeBlock.of(
            "this@copy.%N(%L)",
            member.name,
            member.parameters.map { p -> CodeBlock.of("%N = it.%N", p.name, p.name) }.joinToCode(", "),
        )
    }
    return CodeBlock.of("%T { %L }", delegateClass, accessCode)
}

/**
 * The default value of [member]'s parameter on a generated `copy(...)` function: the binding the
 * receiver *currently* holds for it, carried across verbatim, so omitting the parameter changes
 * nothing about that member - a `single`/`weakSingle`/`multiplex` binding keeps its memo, a
 * default-body member that was never bound keeps its `null` (its body then re-runs against the new
 * module, still seeing any sibling overridden in the same call).
 *
 * [holderClassNames] are the generated implementation classes - in the same file, so referenced by
 * simple name - that expose a `<member>Binding` property for this member: `Generated<Module>`
 * always, plus `GeneratedDelegated<Module>` for a composed module's own member (an inherited one
 * lives on a delegated parent instance, out of reach). When the receiver is none of them - some
 * hand-written implementation - there is no stored binding to read, so the default falls back to
 * [copyDelegateDefaultCode]'s current-value delegate.
 */
private fun copyBindingDefaultCode(member: MemberModel, holderClassNames: List<String>): CodeBlock {
    val bindingPropertyName = "${member.name}Binding"
    val fallback = copyDelegateDefaultCode(member)
    return when (holderClassNames.size) {
        0 -> fallback
        1 -> CodeBlock.of(
            "if (this@copy is %N) this@copy.%N else %L",
            holderClassNames[0], bindingPropertyName, fallback,
        )
        else -> CodeBlock.builder()
            .add("when {\n")
            .indent()
            .apply {
                for (holder in holderClassNames) {
                    add("this@copy is %N -> this@copy.%N\n", holder, bindingPropertyName)
                }
            }
            .add("else -> %L\n", fallback)
            .unindent()
            .add("}")
            .build()
    }
}

/**
 * Emits the implementation class(es), builder function(s), and `copy` function(s) for [model], as
 * described on `@DIModule`.
 *
 * A module that isn't `@DIModule(composed = true)` (see [ModuleModel.isComposed]) gets exactly one
 * `Generated<Module>` implementation and one `<Module>(...)` builder taking a `Binding`
 * (`SuspendBinding` for a `suspend` member) per member in the consolidated interface - inherited
 * members included, so a `@DIModule` interface that merely extends other `@DIModule` interfaces
 * (without composing them) still gets a binding for everything it inherits, exactly as if those
 * members had been declared directly on it.
 *
 * A `@DIModule(composed = true)` interface gets two pairs instead:
 * - `GeneratedDelegated<Module>` / a builder taking one already-built parent module instance per
 *   `@DIModule` parent (`Module1 by module1`-style delegation) plus a binding per member declared
 *   directly on this interface *or reachable through a plain (non-`@DIModule`) supertype* - a plain
 *   supertype is never delegable, so [ModuleModel.ownMembers] already folds its members in
 *   alongside this interface's own declarations (see [parseModule]).
 * - `Generated<Module>` / a builder taking a binding for every member in the consolidated
 *   interface, inherited ones included, each parameterized on this (leaf) module type - so any
 *   provider/factory/single body can reach any member, inherited or not, through its receiver.
 *
 * A member with a default implementation (see [MemberModel.hasDefaultImplementation]) gets a
 * nullable `Binding`/`SuspendBinding` that defaults to `null` in the builder function: `null` keeps
 * the interface's own default body, any other value overrides it.
 *
 * A function with more than one parameter (vararg included) has its arguments wrapped into a
 * generated `<Module>BindingArguments.<Name>` data class - see [buildArgsContainerType]. A member
 * inherited from a `@DIModule` parent or a plain supertype gets its own entry regenerated here too,
 * rather than referencing the ancestor's: `Binding<M, A, T>` is already parameterized on this (leaf)
 * module type for every member, inherited or not, so a `Binding` built for one module was never
 * reusable for another regardless - duplicating the small data class alongside it costs nothing.
 *
 * See [buildCopyFunctions] for the `copy` function(s) appended after these.
 */
internal fun generateModuleFile(model: ModuleModel): FileSpec {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"
    val argsClassName = ClassName(moduleClassName.packageName, "${model.generatedBaseName}BindingArguments")

    val originatingFiles: List<KSFile> =
        listOfNotNull(model.declaration.containingFile) + model.ancestorDeclarations.mapNotNull { it.containingFile }

    val fileBuilder = FileSpec.builder(moduleClassName.packageName, generatedClassName)

    buildArgsContainerType(argsClassName, model.allMembers, originatingFiles)?.let { fileBuilder.addType(it) }

    if (!model.isComposed) {
        val (typeSpec, builderFun) = buildModuleType(
            generatedClassName = generatedClassName,
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            argsClassName = argsClassName,
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
            argsClassName = argsClassName,
            visibility = model.visibility,
            members = model.ownMembers,
            parents = model.parents,
            originatingFiles = originatingFiles,
        )
        val (fullType, fullBuilderFun) = buildModuleType(
            generatedClassName = generatedClassName,
            builderName = model.simpleName,
            moduleClassName = moduleClassName,
            argsClassName = argsClassName,
            visibility = model.visibility,
            members = model.allMembers,
            parents = emptyList(),
            originatingFiles = originatingFiles,
        )
        fileBuilder.addType(delegatedType).addFunction(delegatedBuilderFun)
        fileBuilder.addType(fullType).addFunction(fullBuilderFun)
    }

    for (copyFun in buildCopyFunctions(model, argsClassName)) {
        fileBuilder.addFunction(copyFun)
    }

    return fileBuilder.build()
}

/**
 * The `<Module>BindingArguments` container for every member in [members] with more than one
 * parameter: a `private constructor()` namespace class (never instantiated itself) holding one
 * `data class` per such member, named after the member capitalized (see
 * [MemberModel.argsClassSimpleName]), one field per parameter in declaration order (the array type
 * for a vararg one, see [varargArrayTypeName]) - with no default value, since KSP doesn't expose a
 * parameter's default-value expression to reproduce here. `@Suppress("ArrayInDataClass")` is added
 * only when at least one field is array-typed. Returns `null` when no member qualifies, so the
 * caller adds nothing to the file.
 */
private fun buildArgsContainerType(argsClassName: ClassName, members: List<MemberModel>, originatingFiles: List<KSFile>): TypeSpec? {
    val multiParamMembers = members.filter { it.parameters.size > 1 }
    if (multiParamMembers.isEmpty()) return null

    val containerBuilder = TypeSpec.classBuilder(argsClassName)
        .primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).build())
    for (file in originatingFiles) {
        containerBuilder.addOriginatingKSFile(file)
    }

    for (member in multiParamMembers) {
        val dataClassBuilder = TypeSpec.classBuilder(member.argsClassSimpleName()).addModifiers(KModifier.DATA)
        val ctor = FunSpec.constructorBuilder()
        var hasArrayField = false
        for (param in member.parameters) {
            val fieldType = if (param.isVararg) varargArrayTypeName(param.type) else param.type.toTypeName()
            if (isArrayTypeName(fieldType)) hasArrayField = true
            ctor.addParameter(param.name, fieldType)
            dataClassBuilder.addProperty(PropertySpec.builder(param.name, fieldType).initializer(param.name).build())
        }
        dataClassBuilder.primaryConstructor(ctor.build())
        if (hasArrayField) {
            dataClassBuilder.addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "Suppress")).addMember("%S", "ArrayInDataClass").build()
            )
        }
        containerBuilder.addType(dataClassBuilder.build())
    }

    return containerBuilder.build()
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
    argsClassName: ClassName,
    visibility: Visibility,
    members: List<MemberModel>,
    parents: List<ParentModel>,
    originatingFiles: List<KSFile>,
): Pair<TypeSpec, FunSpec> {
    fun bindingTypeOf(member: MemberModel): TypeName = bindingTypeOf(moduleClassName, argsClassName, member)

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
            // Not `private`: the generated `copy(...)` extensions live in the same file and read
            // this off a smart-cast receiver to carry the module's current binding across verbatim
            // (see `copyBindingDefaultCode`). The class itself is `private`, so this stays
            // file-scoped regardless.
            PropertySpec.builder(bindingParamName, bindingType)
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
            for (param in member.parameters) {
                funBuilder.addParameter(
                    ParameterSpec.builder(param.name, param.type.toTypeName())
                        .apply { if (param.isVararg) addModifiers(KModifier.VARARG) }
                        .build()
                )
            }
            val argCode: CodeBlock = when {
                member.parameters.isEmpty() -> CodeBlock.of("Unit")
                member.parameters.size == 1 -> CodeBlock.of("%N", member.parameters[0].name)
                else -> CodeBlock.of(
                    "%T(%L)",
                    argsClassName.nestedClass(member.argsClassSimpleName()),
                    member.parameters.map { CodeBlock.of("%N", it.name) }.joinToCode(", "),
                )
            }
            if (member.hasDefaultImplementation) {
                // A lone non-vararg parameter stays positional, matching the override's own single
                // parameter; everything else - a lone vararg parameter, or two or more parameters
                // of any kind - is named, since the override's parameter names are guaranteed to
                // match the interface's declaration exactly and naming is always valid regardless
                // of a vararg's position in the list.
                val defaultArgsCode: CodeBlock = when {
                    member.parameters.isEmpty() -> CodeBlock.of("")
                    member.parameters.size == 1 && !member.parameters[0].isVararg -> CodeBlock.of("%N", member.parameters[0].name)
                    member.parameters.size == 1 -> member.parameters[0].let { CodeBlock.of("%N = %N", it.name, it.name) }
                    else -> member.parameters.map { CodeBlock.of("%N = %N", it.name, it.name) }.joinToCode(", ")
                }
                funBuilder.addStatement(
                    "return if (this.%N == null) super<%T>.%N(%L) else this.%N.get(this, %L)",
                    bindingParamName, moduleClassName, member.name, defaultArgsCode, bindingParamName, argCode,
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
 *   [buildMemberOverrideCopyFunction] and [copyBindingDefaultCode] for how each parameter defaults
 *   to the receiver's current binding for that member.
 * - if [ModuleModel.isComposed], an extra `copy(...)` taking one already-built module instance per
 *   `@DIModule` parent (each defaulting to the receiver itself), building through the "delegated"
 *   `GeneratedDelegated<Module>` class - lets a caller swap out a whole parent module at once
 *   instead of rebinding its members individually.
 */
internal fun buildCopyFunctions(model: ModuleModel, argsClassName: ClassName): List<FunSpec> {
    val moduleClassName = model.moduleClassName
    val generatedClassName = "Generated${model.generatedBaseName}"
    val delegatedClassName = "GeneratedDelegated${model.generatedBaseName}"
    val ownMemberNames = model.ownMembers.mapTo(mutableSetOf()) { it.name }

    // The generated implementation classes that expose a stored `<member>Binding` property for
    // this member (see `copyBindingDefaultCode`): the full class always; the delegated class too
    // for a composed module's own member - an inherited member is delegated to a parent instance
    // and has no binding property on `GeneratedDelegated<Module>`.
    fun bindingHolders(member: MemberModel): List<String> = buildList {
        add(generatedClassName)
        if (model.isComposed && member.name in ownMemberNames) add(delegatedClassName)
    }

    val noArgCopy = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .apply { if (model.visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .addStatement("return this")
        .build()

    val copyFunctions = mutableListOf(noArgCopy)

    if (model.allMembers.isNotEmpty()) {
        copyFunctions += buildMemberOverrideCopyFunction(moduleClassName, argsClassName, generatedClassName, model.allMembers, model.visibility, ::bindingHolders)
    }

    if (model.isComposed) {
        copyFunctions += buildModuleOverrideCopyFunction(
            moduleClassName, argsClassName, delegatedClassName, model.ownMembers, model.parents, model.visibility,
            holdersFor = { listOf(generatedClassName, delegatedClassName) },
        )
    }

    return copyFunctions
}

/**
 * The `copy(...)` overload that lets any individual member's binding be overridden.
 *
 * Every parameter defaults to the binding the receiver currently holds for that member (see
 * [copyBindingDefaultCode]) - `holdersFor` gives the generated classes whose `<member>Binding`
 * property is read to obtain it. Omitting a parameter therefore changes nothing about that member,
 * including a `single`/`multiplex` binding's memo and a default-body member's `null` (its body then
 * re-runs against the *new* module, still observing any sibling overridden in the same call). A
 * default-body member keeps its nullable parameter so a caller can still pass an explicit `null` to
 * force it back to the interface's own body.
 */
private fun buildMemberOverrideCopyFunction(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    generatedClassName: String,
    members: List<MemberModel>,
    visibility: Visibility,
    holdersFor: (MemberModel) -> List<String>,
): FunSpec {
    val funBuilder = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    // Every parameter's default falls back to a BindingImpls.Delegate for a foreign receiver, so
    // the opt-in is always needed once there is at least one parameter.
    funBuilder.addAnnotation(OPT_IN_INTERNAL_DI_BINDINGS)

    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, argsClassName, member))
        paramBuilder.defaultValue(copyBindingDefaultCode(member, holdersFor(member)))
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

/**
 * The `copy(...)` overload that lets a whole `@DIModule` parent be swapped out at once. Each own
 * member's binding is carried across from the receiver (see [copyBindingDefaultCode]); `holdersFor`
 * gives the generated classes exposing its `<member>Binding` property.
 */
private fun buildModuleOverrideCopyFunction(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    generatedClassName: String,
    ownMembers: List<MemberModel>,
    parents: List<ParentModel>,
    visibility: Visibility,
    holdersFor: (MemberModel) -> List<String>,
): FunSpec {
    val funBuilder = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    // Every own member's binding falls back to a BindingImpls.Delegate for a foreign receiver, so
    // the opt-in is needed whenever there is at least one own member.
    if (ownMembers.isNotEmpty()) {
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
                addStatement("%N = %L,", "${member.name}Binding", copyBindingDefaultCode(member, holdersFor(member)))
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return funBuilder.addCode(callCode).build()
}
