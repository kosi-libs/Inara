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
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName

private val BINDING = ClassName("org.kodein.di.inara", "Binding")
private val SUSPEND_BINDING = ClassName("org.kodein.di.inara", "SuspendBinding")
private val BINDING_IMPLS_DELEGATE = ClassName("org.kodein.di.inara", "BindingImpls").nestedClass("Delegate")
private val SUSPEND_BINDING_IMPLS_DELEGATE = ClassName("org.kodein.di.inara", "SuspendBindingImpls").nestedClass("SuspendDelegate")
private val INTERNAL_INARA_GENERATION = ClassName("org.kodein.di.inara", "InternalInaraGeneration")

/** `@file:OptIn(InternalInaraGeneration::class)` - added to every generated file so its `Inara<Module>`
 * class, and every `BindingImpls.Delegate` it builds, need no further opt-in. */
private val FILE_OPT_IN_INTERNAL_INARA_GENERATION: AnnotationSpec = AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
    .addMember("%T::class", INTERNAL_INARA_GENERATION)
    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
    .build()

private val ARRAY = ClassName("kotlin", "Array")

/** Every Kotlin type with a dedicated primitive array type - a non-nullable vararg of one of these
 * uses it (`IntArray`, ...) instead of `Array<out T>`. Also read by `DIFactoryModel.kt` to decide
 * whether a `@DIFactory` `with` type can be passed to a candidate member's vararg parameter. */
internal val PRIMITIVE_ARRAYS: Map<String, ClassName> = mapOf(
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
 * Renders [this] using simple (unqualified) names - `List<String>` rather than
 * `kotlin.collections.List<kotlin.String>` - for the debug `toString()` header line built by
 * [memberSignature]. Mirrors the simple names a binding's own `toString()` already shows via
 * `KClass.simpleName` (see `Bindings.kt`/`SuspendBindings.kt`).
 */
private fun TypeName.simpleString(): String {
    val base = when (this) {
        is ClassName -> simpleNames.joinToString(".")
        is ParameterizedTypeName -> "${rawType.simpleString()}<${typeArguments.joinToString(", ") { it.simpleString() }}>"
        is WildcardTypeName -> when {
            inTypes.size == 1 -> "in ${inTypes[0].simpleString()}"
            outTypes == STAR.outTypes -> "*"
            else -> "out ${outTypes[0].simpleString()}"
        }
        is LambdaTypeName -> buildString {
            if (isSuspending) append("suspend ")
            receiver?.let { append(it.simpleString()); append(".") }
            append('(')
            append(parameters.joinToString(", ") { it.type.simpleString() })
            append(") -> ")
            append(returnType.simpleString())
        }
        is TypeVariableName -> name
        else -> toString()
    }
    return if (isNullable && this !is LambdaTypeName) "$base?" else base
}

/**
 * The literal member-signature text shown left of `=>` in the debug `toString()` [buildToStringFunction]
 * generates - e.g. `val baseUrl: String` or `suspend fun loadKeyed(key: String): Service`. A
 * parameter's declared default value is never reproduced - KSP doesn't expose the expression (the
 * same limitation [buildArgsContainerType] documents), so `both(vararg ids: Int, flag: Boolean =
 * false)` renders as `fun both(vararg ids: Int, flag: Boolean): String`.
 */
private fun memberSignature(member: MemberModel): String {
    val returnTypeString = member.returnType.toTypeName().simpleString()
    if (!member.isFunction) return "val ${member.name}: $returnTypeString"
    val params = member.parameters.joinToString(", ") { p ->
        val prefix = if (p.isVararg) "vararg " else ""
        "$prefix${p.name}: ${p.type.toTypeName().simpleString()}"
    }
    val suspendPrefix = if (member.isSuspend) "suspend " else ""
    return "${suspendPrefix}fun ${member.name}($params): $returnTypeString"
}

/**
 * The debug `override fun toString()` emitted on the `Inara<Module>` class (see [buildModuleType]):
 * one line per member in [members], each showing [memberSignature] followed by `=> ` and the
 * current `toString()` of the binding backing it - `[default]` when a default-body member's binding
 * is `null` (its interface's own default body runs instead), matching the `[delegated to ...]` marker
 * `BindingImpls.Delegate`/`SuspendBindingImpls.SuspendDelegate` already use for "no real binding
 * here". [builderName] (the module interface's own simple name) is used as the header, not the
 * generated class name.
 */
private fun buildToStringFunction(builderName: String, members: List<MemberModel>): FunSpec {
    val expression = if (members.isEmpty()) {
        CodeBlock.of("%S", "module $builderName {}")
    } else {
        val body = CodeBlock.builder().add("%S", "module $builderName {\n")
        for (member in members) {
            val bindingPropertyName = "${member.name}Binding"
            body.add(" +\n    %S + ", "    ${memberSignature(member)} => ")
            if (member.hasDefaultImplementation) {
                body.add("(%N ?: %S)", bindingPropertyName, "[default]")
            } else {
                body.add("%N", bindingPropertyName)
            }
            body.add(" + %S", "\n")
        }
        body.add(" +\n    %S", "}")
        body.build()
    }
    return FunSpec.builder("toString")
        .addModifiers(KModifier.OVERRIDE)
        .returns(STRING)
        .addStatement("return %L", expression)
        .build()
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
 * `BindingImpls.Delegate(<receiver>::class) { <receiver>.<member>(…) }`
 * (`SuspendBindingImpls.SuspendDelegate` for a `suspend` member): a binding that reads [member]'s
 * *current* value off [receiver] on every `get`. The fallback for when a real stored binding can't
 * be reached - a hand-written `<Module>` implementation, or an inherited member with 2+ parameters
 * (see [parentBindingCode]). [receiver]'s own runtime class is passed through so the delegate's
 * `toString()` can name it (`[delegated to ManualModule]`) - always a hand-written implementation or
 * such an inherited member, since the generated implementation itself is read straight off its
 * `<member>Binding` property instead of ever reaching this fallback.
 *
 * A named argument assigns straight into a vararg parameter, no spread operator needed (using one
 * is flagged as redundant) - so a lone vararg parameter, and every parameter of a multi-parameter
 * member (vararg or not, matching each field's name on the generated arguments data class), is
 * passed named. A lone non-vararg parameter stays positional.
 */
private fun delegateBindingCode(member: MemberModel, receiver: CodeBlock): CodeBlock {
    val delegateClass = if (member.isSuspend) SUSPEND_BINDING_IMPLS_DELEGATE else BINDING_IMPLS_DELEGATE
    val accessCode = when {
        !member.isFunction -> CodeBlock.of("%L.%N", receiver, member.name)
        member.parameters.isEmpty() -> CodeBlock.of("%L.%N()", receiver, member.name)
        member.parameters.size == 1 && !member.parameters[0].isVararg -> CodeBlock.of("%L.%N(it)", receiver, member.name)
        member.parameters.size == 1 -> member.parameters[0].let { p ->
            CodeBlock.of("%L.%N(%N = it)", receiver, member.name, p.name)
        }
        else -> CodeBlock.of(
            "%L.%N(%L)",
            receiver, member.name,
            member.parameters.map { p -> CodeBlock.of("%N = it.%N", p.name, p.name) }.joinToCode(", "),
        )
    }
    return CodeBlock.of("%T(%L::class) { %L }", delegateClass, receiver, accessCode)
}

/**
 * The binding for [member] read off the `copy` receiver: `this@copy.<member>Binding` when the
 * receiver is the [generatedClass] implementation, otherwise a current-value delegate (see
 * [delegateBindingCode]). [receiver] is always `this@copy` here - the receiver's own generated
 * class, so its `<member>Binding` is exactly the right type even for a multi-parameter member.
 */
private fun storedBindingOrDelegateCode(
    member: MemberModel,
    receiver: CodeBlock,
    generatedClass: ClassName,
): CodeBlock = CodeBlock.of(
    "if (%L is %T) %L.%N else %L",
    receiver, generatedClass, receiver, "${member.name}Binding", delegateBindingCode(member, receiver),
)

/**
 * The binding for an inherited [member] sourced from its parent instance [parentParam]: the
 * `<member>Binding` property read off [parentGeneratedClass] when the instance is that
 * implementation, otherwise a delegate that forwards to `parentParam.<member>(...)`.
 *
 * A member with 2+ parameters always takes the delegate: its arguments are wrapped in a *per-module*
 * `<Module>BindingArguments` data class, so the parent's `Binding<Parent, ParentArgs.X, T>` can't
 * fill the child's `Binding<Child, ChildArgs.X, T>` slot (`A` is invariant) - the delegate sidesteps
 * it by unpacking and forwarding.
 */
private fun parentBindingCode(
    member: MemberModel,
    parentParam: String,
    parentGeneratedClass: ClassName,
): CodeBlock {
    val delegate = delegateBindingCode(member, CodeBlock.of("%N", parentParam))
    if (member.parameters.size > 1) return delegate
    return CodeBlock.of(
        "if (%N is %T) %N.%N else %L",
        parentParam, parentGeneratedClass, parentParam, "${member.name}Binding", delegate,
    )
}

/**
 * The default value of an inherited [member]'s parameter on the parent-swapping `copy(...)`
 * overload: three cases, since that overload defaults each parent parameter to the receiver itself.
 *
 * - the parent *was* swapped (`<parentParam> !== this@copy`) - take the binding from the new parent
 *   instance ([parentBindingCode]);
 * - it wasn't, and the receiver is the generated implementation - carry `this@copy.<member>Binding`
 *   straight across, exactly like the member-override `copy`;
 * - it wasn't, and the receiver is hand-written - a current-value delegate over `this@copy`.
 */
private fun parentSwapInheritedCode(
    member: MemberModel,
    parentParamName: String,
    parentGeneratedClass: ClassName,
    selfGeneratedClass: ClassName,
): CodeBlock {
    val bindingPropertyName = "${member.name}Binding"
    return CodeBlock.builder()
        .add("when {\n")
        .indent()
        .add(
            "%N !== this@copy -> %L\n",
            parentParamName,
            parentBindingCode(member, parentParamName, parentGeneratedClass),
        )
        .add("this@copy is %T -> this@copy.%N\n", selfGeneratedClass, bindingPropertyName)
        .add("else -> %L\n", delegateBindingCode(member, CodeBlock.of("this@copy")))
        .unindent()
        .add("}")
        .build()
}

/**
 * Emits the implementation class, builder function(s), and `copy` function(s) for [model], as
 * described on `@DIModule`.
 *
 * Every module - `@DIModule(composed = true)` or not - gets exactly one `Inara<Module>`
 * implementation and one `<Module>(...)` builder taking a `Binding` (`SuspendBinding` for a
 * `suspend` member) per member in the consolidated interface, inherited members included, each
 * parameterized on this (leaf) module type - so any provider/factory/single body can reach any
 * member, inherited or not, through its receiver.
 *
 * A `@DIModule(composed = true)` interface additionally gets a second `<Module>(...)` builder
 * overload (see [buildComposedBuilderFunction]) taking one already-built parent module instance per
 * `@DIModule` parent plus a binding per own/plain-supertype member: it constructs the *same*
 * `Inara<Module>`, reading each inherited member's binding straight off the parent instance
 * (`Binding<M, A, T>` is contravariant in `M`, so a `Binding<Parent, …>` fits a `Binding<Child, …>`
 * slot). A parent that isn't the generated implementation - a hand-written one - falls back to a
 * `BindingImpls.Delegate` over the instance, as does an inherited member with 2+ parameters (its
 * `Binding` argument type is the *per-module* `<Module>BindingArguments` data class, and `A` is
 * invariant - see [parentBindingCode]).
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
    val generatedClassName = inaraName(model.generatedBaseName)
    val argsClassName = ClassName(moduleClassName.packageName, "${model.generatedBaseName}BindingArguments")

    val originatingFiles: List<KSFile> =
        listOfNotNull(model.declaration.containingFile) + model.ancestorDeclarations.mapNotNull { it.containingFile }

    val fileBuilder = FileSpec.builder(moduleClassName.packageName, generatedClassName)
        .addAnnotation(FILE_OPT_IN_INTERNAL_INARA_GENERATION)

    buildArgsContainerType(argsClassName, model.allMembers, originatingFiles)?.let { fileBuilder.addType(it) }

    val (typeSpec, builderFun) = buildModuleType(
        generatedClassName = generatedClassName,
        builderName = model.simpleName,
        moduleClassName = moduleClassName,
        argsClassName = argsClassName,
        visibility = model.visibility,
        members = model.allMembers,
        originatingFiles = originatingFiles,
    )
    fileBuilder.addType(typeSpec).addFunction(builderFun)

    if (model.isComposed) {
        fileBuilder.addFunction(buildComposedBuilderFunction(model, generatedClassName, argsClassName))
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
 * Builds the `Inara<Module>` implementation class and its matching [builderName] builder
 * function: one `Binding`/`SuspendBinding` parameter per member in [members] (which is always
 * [ModuleModel.allMembers], inherited members included).
 *
 * The class mirrors the module interface's own [visibility] (`internal` interface -> `internal`
 * class, so it can't expose a less-visible supertype) and is marked `@InternalInaraGeneration`: a
 * `@RequiresOptIn` signal that it exists only to support generated code - a composed child's
 * generated file, and this file's own `copy(...)` extensions, read its `<member>Binding` properties
 * off a smart-cast instance. Every builder / `copy` function is still typed on the module interface.
 */
private fun buildModuleType(
    generatedClassName: String,
    builderName: String,
    moduleClassName: ClassName,
    argsClassName: ClassName,
    visibility: Visibility,
    members: List<MemberModel>,
    originatingFiles: List<KSFile>,
): Pair<TypeSpec, FunSpec> {
    fun bindingTypeOf(member: MemberModel): TypeName = bindingTypeOf(moduleClassName, argsClassName, member)

    val classBuilder = TypeSpec.classBuilder(generatedClassName)
        .apply { if (visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .addAnnotation(INTERNAL_INARA_GENERATION)
    for (file in originatingFiles) {
        classBuilder.addOriginatingKSFile(file)
    }
    val constructorBuilder = FunSpec.constructorBuilder()

    for (member in members) {
        val bindingParamName = "${member.name}Binding"
        val bindingType = bindingTypeOf(member)

        constructorBuilder.addParameter(bindingParamName, bindingType)
        classBuilder.addProperty(
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
    classBuilder.primaryConstructor(constructorBuilder.build())
    classBuilder.addSuperinterface(moduleClassName)

    // A @DIModule interface may itself declare a zero-arg `toString(): String` (abstract or
    // defaulted) - the loop above already generated an override backed by a `toStringBinding` for
    // it, so the debug one below is skipped rather than emitting a second, non-compiling override.
    if (members.none { it.isFunction && it.name == "toString" && it.parameters.isEmpty() }) {
        classBuilder.addFunction(buildToStringFunction(builderName, members))
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
            for (member in members) {
                addStatement("%N = %N,", "${member.name}Binding", member.name)
            }
        }
        .unindent()
        .add(")\n")
        .build()
    builderFun.addCode(callCode)

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
 * The second `<Module>(...)` builder overload for a `@DIModule(composed = true)` interface: one
 * already-built parent module instance per `@DIModule` parent, then one binding per own /
 * plain-supertype member (a default-impl member's defaults to `null`). It constructs the same
 * `Inara<Module>` as the full builder - each inherited member's binding pulled off its parent
 * instance via [parentBindingCode] (contravariant `Binding<in M>` lets a `Binding<Parent, …>` fill
 * a `Binding<Child, …>` slot; a hand-written parent, or a 2+ parameter member, falls back to a
 * `BindingImpls.Delegate`).
 */
private fun buildComposedBuilderFunction(
    model: ModuleModel,
    generatedClassName: String,
    argsClassName: ClassName,
): FunSpec {
    val moduleClassName = model.moduleClassName
    val ownMemberNames = model.ownMembers.mapTo(mutableSetOf()) { it.name }

    val builderFun = FunSpec.builder(model.simpleName).returns(moduleClassName)
    if (model.visibility == Visibility.INTERNAL) {
        builderFun.addModifiers(KModifier.INTERNAL)
    }

    for (parent in model.parents) {
        builderFun.addParameter(parent.paramName, parent.moduleClassName)
    }
    for (member in model.ownMembers) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, argsClassName, member))
        if (member.hasDefaultImplementation) {
            paramBuilder.defaultValue("null")
        }
        builderFun.addParameter(paramBuilder.build())
    }

    val callCode = CodeBlock.builder()
        .add("return %N(\n", generatedClassName)
        .indent()
        .apply {
            for (member in model.allMembers) {
                val valueCode = if (member.name in ownMemberNames) {
                    CodeBlock.of("%N", member.name)
                } else {
                    val parent = model.parentByMemberName.getValue(member.name)
                    parentBindingCode(member, parent.paramName, parent.generatedClassName)
                }
                addStatement("%N = %L,", "${member.name}Binding", valueCode)
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return builderFun.addCode(callCode).build()
}

/**
 * Emits every `copy` extension function for [model]:
 * - a no-argument `copy()` that returns the receiver unchanged, so a copy with no overrides doesn't
 *   allocate one. Always emitted.
 * - a `copy(...)` taking one `Binding`/`SuspendBinding` per member in [ModuleModel.allMembers],
 *   building the `Inara<Module>` class. Only emitted when there is at least one member - with
 *   none, it would take no parameters and collide with the no-arg overload. See
 *   [buildMemberOverrideCopyFunction] and [storedBindingOrDelegateCode] for how each parameter
 *   defaults to the binding the receiver currently holds for that member.
 * - if [ModuleModel.isComposed], an extra `copy(...)` taking one already-built module instance per
 *   `@DIModule` parent (each defaulting to the receiver itself), also building `Inara<Module>` -
 *   lets a caller swap out a whole parent module at once instead of rebinding its members
 *   individually. See [buildModuleOverrideCopyFunction].
 */
internal fun buildCopyFunctions(model: ModuleModel, argsClassName: ClassName): List<FunSpec> {
    val moduleClassName = model.moduleClassName
    val generatedClassName = inaraName(model.generatedBaseName)
    val selfGeneratedClass = ClassName(moduleClassName.packageName, generatedClassName)

    val noArgCopy = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .apply { if (model.visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .addStatement("return this")
        .build()

    val copyFunctions = mutableListOf(noArgCopy)

    if (model.allMembers.isNotEmpty()) {
        copyFunctions += buildMemberOverrideCopyFunction(moduleClassName, argsClassName, generatedClassName, selfGeneratedClass, model.allMembers, model.visibility)
    }

    if (model.isComposed) {
        copyFunctions += buildModuleOverrideCopyFunction(model, generatedClassName, selfGeneratedClass)
    }

    return copyFunctions
}

/**
 * The `copy(...)` overload that lets any individual member's binding be overridden.
 *
 * Every parameter defaults to the binding the receiver currently holds for that member (see
 * [storedBindingOrDelegateCode]): `this@copy.<member>Binding` when the receiver is the generated
 * implementation, a current-value `BindingImpls.Delegate` otherwise. Omitting a parameter therefore
 * changes nothing about that member, including a `single`/`multiplex` binding's memo and a
 * default-body member's `null` (its body then re-runs against the *new* module, still observing any
 * sibling overridden in the same call). A default-body member keeps its nullable parameter so a
 * caller can still pass an explicit `null` to force it back to the interface's own body.
 */
private fun buildMemberOverrideCopyFunction(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    generatedClassName: String,
    selfGeneratedClass: ClassName,
    members: List<MemberModel>,
    visibility: Visibility,
): FunSpec {
    val funBuilder = FunSpec.builder("copy")
        .receiver(moduleClassName)
        .returns(moduleClassName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }

    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, argsClassName, member))
        paramBuilder.defaultValue(storedBindingOrDelegateCode(member, CodeBlock.of("this@copy"), selfGeneratedClass))
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
 * The `copy(...)` overload that lets a whole `@DIModule` parent be swapped out at once: one
 * parameter per parent, each defaulting to the receiver itself, building the same `Inara<Module>`
 * as every other builder/copy.
 *
 * An inherited member's binding comes from its (possibly swapped) parent - see
 * [parentSwapInheritedCode], which carries the receiver's own binding straight across when that
 * parent wasn't swapped. An own / plain-supertype member's comes from the receiver, exactly as in
 * [buildMemberOverrideCopyFunction].
 */
private fun buildModuleOverrideCopyFunction(
    model: ModuleModel,
    generatedClassName: String,
    selfGeneratedClass: ClassName,
): FunSpec {
    val ownMemberNames = model.ownMembers.mapTo(mutableSetOf()) { it.name }

    val funBuilder = FunSpec.builder("copy")
        .receiver(model.moduleClassName)
        .returns(model.moduleClassName)
    if (model.visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }

    for (parent in model.parents) {
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
            for (member in model.allMembers) {
                val valueCode = if (member.name in ownMemberNames) {
                    storedBindingOrDelegateCode(member, CodeBlock.of("this@copy"), selfGeneratedClass)
                } else {
                    val parent = model.parentByMemberName.getValue(member.name)
                    parentSwapInheritedCode(member, parent.paramName, parent.generatedClassName, selfGeneratedClass)
                }
                addStatement("%N = %L,", "${member.name}Binding", valueCode)
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return funBuilder.addCode(callCode).build()
}
