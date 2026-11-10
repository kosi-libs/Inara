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
private val BINDING_IMPLS = ClassName("org.kodein.di.inara", "BindingImpls")
private val SUSPEND_BINDING_IMPLS = ClassName("org.kodein.di.inara", "SuspendBindingImpls")
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
private val SET = ClassName("kotlin.collections", "Set")

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
 * `kotlin.collections.List<kotlin.String>`. The processor's human-readable type renderer: it feeds
 * the debug `toString()` header line built by [memberSignature] and also the `@DIFactory`
 * diagnostics in `DIFactoryModel.kt` that quote a signature back to the user. Mirrors the simple
 * names a binding's own `toString()` already shows via `KClass.simpleName` (see
 * `Bindings.kt`/`SuspendBindings.kt`).
 */
internal fun TypeName.simpleString(): String {
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
 * function or on a `copy<Module>` function, see [buildMemberOverrideCopyFunction]) is nullable exactly when
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
 * (see [parentBindingCode]) - and, deliberately even for the generated implementation, the default
 * of every shadow function's parameter (see [buildMemberShadowFunction]). [receiver]'s own runtime class is passed through so the delegate's
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
 * `BindingImpls.copyOf(<bindingCode>)` (`SuspendBindingImpls.copyOf` for a `suspend` member): a copy
 * of the binding with its own, unbuilt state, so a copied module never shares a `single` /
 * `weakSingle` / `multiplex` with the module it came from.
 */
private fun copiedBindingCode(member: MemberModel, bindingCode: CodeBlock): CodeBlock =
    CodeBlock.of("%T.copyOf(%L)", if (member.isSuspend) SUSPEND_BINDING_IMPLS else BINDING_IMPLS, bindingCode)

/**
 * The binding for [member] read off a copy function's receiver: a copy (see [copiedBindingCode]) of
 * `<receiver>.<member>Binding` when the receiver is the [generatedClass] implementation, otherwise a
 * current-value delegate (see [delegateBindingCode]) - a hand-written receiver's state is not ours
 * to copy. [receiver] is always the copy function's labeled receiver (`this@copy<Module>`) - the
 * receiver's own generated class, so its `<member>Binding` is exactly the right type even for a
 * multi-parameter member.
 */
private fun storedBindingOrDelegateCode(
    member: MemberModel,
    receiver: CodeBlock,
    generatedClass: ClassName,
): CodeBlock = CodeBlock.of(
    "if (%L is %T) %L else %L",
    receiver, generatedClass,
    copiedBindingCode(member, CodeBlock.of("%L.%N", receiver, "${member.name}Binding")),
    delegateBindingCode(member, receiver),
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
 *
 * With [copied], the binding read off the parent is a copy (see [copiedBindingCode]) - the
 * parent-swapping `copy<Module>` gives the new module its own state - rather than shared with the parent as
 * in the composed builder. The delegate fallback is never copied: it holds no state of its own.
 */
private fun parentBindingCode(
    member: MemberModel,
    parentParam: String,
    parentGeneratedClass: ClassName,
    copied: Boolean = false,
): CodeBlock {
    val delegate = delegateBindingCode(member, CodeBlock.of("%N", parentParam))
    if (member.parameters.size > 1) return delegate
    val stored = CodeBlock.of("%N.%N", parentParam, "${member.name}Binding")
    return CodeBlock.of(
        "if (%N is %T) %L else %L",
        parentParam, parentGeneratedClass, if (copied) copiedBindingCode(member, stored) else stored, delegate,
    )
}

/**
 * The default value of an inherited [member]'s parameter on the parent-swapping `copy<Module>(...)`
 * overload: three cases, since that overload defaults each parent parameter to the receiver itself.
 * [receiver] is the labeled receiver of that overload (`this@copy<Module>`).
 *
 * - the parent *was* swapped (`<parentParam> !== <receiver>`) - take the binding from the new parent
 *   instance ([parentBindingCode]);
 * - it wasn't, and the receiver is the generated implementation - carry a copy of
 *   `<receiver>.<member>Binding` across, exactly like the member-override `copy<Module>`;
 * - it wasn't, and the receiver is hand-written - a current-value delegate over the receiver.
 */
private fun parentSwapInheritedCode(
    member: MemberModel,
    parentParamName: String,
    parentGeneratedClass: ClassName,
    selfGeneratedClass: ClassName,
    receiver: CodeBlock,
): CodeBlock {
    val bindingPropertyName = "${member.name}Binding"
    return CodeBlock.builder()
        .add("when {\n")
        .indent()
        .add(
            "%N !== %L -> %L\n",
            parentParamName, receiver,
            parentBindingCode(member, parentParamName, parentGeneratedClass, copied = true),
        )
        .add(
            "%L is %T -> %L\n",
            receiver, selfGeneratedClass, copiedBindingCode(member, CodeBlock.of("%L.%N", receiver, bindingPropertyName)),
        )
        .add("else -> %L\n", delegateBindingCode(member, receiver))
        .unindent()
        .add("}")
        .build()
}

/**
 * Emits the implementation class, builder function(s), and `copy<Module>` / `shadow<Module>`
 * function(s) for [model], as described on `@DIModule`.
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
 * See [buildCopyFunctions], [buildShadowFunctions] and [buildSetFunctions] for the
 * `copy<Module>`, `shadow<Module>` and `setOf<Annotation>` function(s) appended after these.
 */
internal fun generateModuleFile(model: ModuleModel): FileSpec {
    val moduleClassName = model.moduleClassName
    val generatedClassName = inaraName(model.generatedBaseName)
    val argsClassName = ClassName(moduleClassName.packageName, "${model.generatedBaseName}BindingArguments")

    val originatingFiles: List<KSFile> =
        listOfNotNull(model.declaration.containingFile) + model.ancestorDeclarations.mapNotNull { it.containingFile }

    val fileBuilder = FileSpec.builder(moduleClassName.packageName, generatedClassName)
        .addAnnotation(FILE_OPT_IN_INTERNAL_INARA_GENERATION)

    buildArgsContainerType(argsClassName, model.visibility, model.allMembers, originatingFiles)?.let { fileBuilder.addType(it) }

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

    for (shadowFun in buildShadowFunctions(model, argsClassName)) {
        fileBuilder.addFunction(shadowFun)
    }

    for (setFun in buildSetFunctions(model)) {
        fileBuilder.addFunction(setFun)
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
 *
 * The container mirrors the module's [visibility], like the implementation class and every builder /
 * copy function - an `internal` module's data classes wrap the module's own member parameter types, which
 * may themselves be `internal`, so a `public` container would fail to compile.
 */
private fun buildArgsContainerType(argsClassName: ClassName, visibility: Visibility, members: List<MemberModel>, originatingFiles: List<KSFile>): TypeSpec? {
    val multiParamMembers = members.filter { it.parameters.size > 1 }
    if (multiParamMembers.isEmpty()) return null

    val containerBuilder = TypeSpec.classBuilder(argsClassName)
        .apply { if (visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
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
 * generated file, and this file's own copy functions, read its `<member>Binding` properties
 * off a smart-cast instance. Every builder / copy function is still typed on the module interface.
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

private val DEPRECATED = ClassName("kotlin", "Deprecated")
private val SUPPRESS = ClassName("kotlin", "Suppress")
private val REPLACE_WITH = ClassName("kotlin", "ReplaceWith")
private val DEPRECATION_LEVEL = ClassName("kotlin", "DeprecationLevel")

/**
 * One name a module's copy or shadow functions are emitted under: [name] itself - its receiver
 * labeled `this@<name>` in every default value - and, for the former `copy` / `shadow` name only,
 * [replacedBy]: the `copy<Module>` / `shadow<Module>` name the deprecated function points to.
 */
private class FunctionName(val name: String, val replacedBy: String? = null) {
    val receiver: CodeBlock get() = CodeBlock.of("this@%L", name)
}

/** `<functionName>(a = a, b = b, ...)`: a call passing each of [parameterNames] through by name. */
private fun forwardingCall(functionName: String, parameterNames: List<String>): CodeBlock =
    CodeBlock.of("%L(%L)", functionName, parameterNames.map { CodeBlock.of("%N = %N", it, it) }.joinToCode(", "))

/**
 * Adds, when [name] is a former name, `@Deprecated(level = ERROR)` pointing to its replacement: the
 * call to [name]'s `copy<Module>` / `shadow<Module>` counterpart with every one of [parameterNames]
 * passed through by name, imported from [packageName].
 *
 * A module may have a member named `copy` or `shadow`, which then hides the deprecated function -
 * and, for a member function without parameters, makes Kotlin warn that the generated extension is
 * shadowed. That warning is suppressed, so that it never fails a build treating warnings as errors.
 */
private fun FunSpec.Builder.deprecatedIfRenamed(name: FunctionName, parameterNames: List<String>, packageName: String): FunSpec.Builder {
    val newName = name.replacedBy ?: return this
    val import = if (packageName.isEmpty()) newName else "$packageName.$newName"
    addAnnotation(AnnotationSpec.builder(SUPPRESS).addMember("%S", "EXTENSION_SHADOWED_BY_MEMBER").build())
    return addAnnotation(
        AnnotationSpec.builder(DEPRECATED)
            .addMember(
                "message = %S",
                "'${name.name}' is renamed '$newName': unlike '${name.name}', it can never be ambiguous with a parent module's " +
                    "function. The next version of Inara will no longer generate '${name.name}'.",
            )
            .addMember("replaceWith = %T(%S, %S)", REPLACE_WITH, forwardingCall(newName, parameterNames).toString(), import)
            .addMember("level = %T.ERROR", DEPRECATION_LEVEL)
            .build()
    )
}

/**
 * Emits every copy function for [model] under its own name, `copy<Module>` (see
 * [copyFunctionName]), then again under the former name `copy`, each deprecated with an error that
 * points to its `copy<Module>` counterpart - the next version of Inara no longer generates those.
 * Every module's copy functions used to share the name `copy`, so a call on a child module naming
 * only inherited members matched a parent's `copy` as well as the child's own, and could not be
 * resolved. Each name gets:
 * - a no-argument overload that builds a fresh `Inara<Module>` holding a copy of every binding, with
 *   the same defaults as the member-override overload. Always emitted - it is also what keeps a bare
 *   `copy<Module>()` call from being ambiguous between the overloads.
 *   (A copy never shares state: every binding it holds is a copy, see [copiedBindingCode].)
 * - an overload taking one `Binding`/`SuspendBinding` per member in [ModuleModel.allMembers],
 *   building the `Inara<Module>` class. Only emitted when there is at least one member - with
 *   none, it would take no parameters and collide with the no-arg overload. See
 *   [buildMemberOverrideCopyFunction] and [storedBindingOrDelegateCode] for how each parameter
 *   defaults to the binding the receiver currently holds for that member.
 * - if [ModuleModel.isComposed], an extra overload taking one already-built module instance per
 *   `@DIModule` parent (each defaulting to the receiver itself), also building `Inara<Module>` -
 *   lets a caller swap out a whole parent module at once instead of rebinding its members
 *   individually. See [buildModuleOverrideCopyFunction].
 */
internal fun buildCopyFunctions(model: ModuleModel, argsClassName: ClassName): List<FunSpec> {
    val name = copyFunctionName(model.simpleName)
    return buildCopyFunctions(model, argsClassName, FunctionName(name)) +
        buildCopyFunctions(model, argsClassName, FunctionName("copy", replacedBy = name))
}

private fun buildCopyFunctions(model: ModuleModel, argsClassName: ClassName, name: FunctionName): List<FunSpec> {
    val moduleClassName = model.moduleClassName
    val generatedClassName = inaraName(model.generatedBaseName)
    val selfGeneratedClass = ClassName(moduleClassName.packageName, generatedClassName)

    val noArgCopy = FunSpec.builder(name.name)
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .apply { if (model.visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .deprecatedIfRenamed(name, emptyList(), moduleClassName.packageName)
        .addCode(
            CodeBlock.builder()
                .add("return %N(\n", generatedClassName)
                .indent()
                .apply {
                    for (member in model.allMembers) {
                        addStatement(
                            "%N = %L,", "${member.name}Binding",
                            storedBindingOrDelegateCode(member, name.receiver, selfGeneratedClass),
                        )
                    }
                }
                .unindent()
                .add(")\n")
                .build()
        )
        .build()

    val copyFunctions = mutableListOf(noArgCopy)

    if (model.allMembers.isNotEmpty()) {
        copyFunctions += buildMemberOverrideCopyFunction(moduleClassName, argsClassName, generatedClassName, selfGeneratedClass, model.allMembers, model.visibility, name)
    }

    if (model.isComposed) {
        copyFunctions += buildModuleOverrideCopyFunction(model, generatedClassName, selfGeneratedClass, name)
    }

    return copyFunctions
}

/**
 * The copy overload that lets any individual member's binding be overridden, emitted under [name].
 *
 * Every parameter defaults to a copy of the binding the receiver currently holds for that member
 * (see [storedBindingOrDelegateCode]): a copy of `<receiver>.<member>Binding` when the receiver is the
 * generated implementation, a current-value `BindingImpls.Delegate` otherwise. Omitting a parameter
 * therefore keeps that member's recipe but not its state - a `single`/`multiplex` starts unbuilt in
 * the new module - and a default-body member's `null` stays `null` (its body then re-runs against
 * the *new* module, still observing any sibling overridden in the same call). A default-body member
 * keeps its nullable parameter so a caller can still pass an explicit `null` to force it back to the
 * interface's own body.
 */
private fun buildMemberOverrideCopyFunction(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    generatedClassName: String,
    selfGeneratedClass: ClassName,
    members: List<MemberModel>,
    visibility: Visibility,
    name: FunctionName,
): FunSpec {
    val funBuilder = FunSpec.builder(name.name)
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .deprecatedIfRenamed(name, members.map { it.name }, moduleClassName.packageName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }

    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, argsClassName, member))
        paramBuilder.defaultValue(storedBindingOrDelegateCode(member, name.receiver, selfGeneratedClass))
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
 * Emits every shadow function for [model] under its own name, `shadow<Module>` (see
 * [shadowFunctionName]), then again under the former name `shadow`, deprecated with an error exactly
 * like the former `copy` (see [buildCopyFunctions]). Each name gets:
 * - a no-argument overload that returns the receiver unchanged. Always emitted.
 * - an overload taking one `Binding`/`SuspendBinding` per member in [ModuleModel.allMembers],
 *   building the `Inara<Module>` class. Only emitted when there is at least one member. See
 *   [buildMemberShadowFunction].
 *
 * Unlike a copy, an omitted parameter never carries the receiver's stored binding across: it
 * delegates to the receiver itself, so the receiver keeps evaluating against its own members and
 * never sees what the shadowing module overrides.
 */
internal fun buildShadowFunctions(model: ModuleModel, argsClassName: ClassName): List<FunSpec> {
    val name = shadowFunctionName(model.simpleName)
    return buildShadowFunctions(model, argsClassName, FunctionName(name)) +
        buildShadowFunctions(model, argsClassName, FunctionName("shadow", replacedBy = name))
}

private fun buildShadowFunctions(model: ModuleModel, argsClassName: ClassName, name: FunctionName): List<FunSpec> {
    val moduleClassName = model.moduleClassName
    val generatedClassName = inaraName(model.generatedBaseName)

    val noArgShadow = FunSpec.builder(name.name)
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .apply { if (model.visibility == Visibility.INTERNAL) addModifiers(KModifier.INTERNAL) }
        .deprecatedIfRenamed(name, emptyList(), moduleClassName.packageName)
        .addStatement("return this")
        .build()

    val shadowFunctions = mutableListOf(noArgShadow)

    if (model.allMembers.isNotEmpty()) {
        shadowFunctions += buildMemberShadowFunction(moduleClassName, argsClassName, generatedClassName, model.allMembers, model.visibility, name)
    }

    return shadowFunctions
}

/**
 * The shadow overload that lets any individual member's binding be overridden, emitted under [name].
 *
 * Every parameter defaults to a current-value delegate over the receiver (see
 * [delegateBindingCode]) - always, even when the receiver is the generated implementation, since
 * carrying its stored binding across would re-bind that binding's receiver to the new module. An
 * omitted member is therefore evaluated by the receiver, against the receiver, and a memoized
 * binding is built and kept by the receiver alone. A default-body member keeps its nullable
 * parameter: an explicit `null` runs the interface's own body against the *new* module.
 */
private fun buildMemberShadowFunction(
    moduleClassName: ClassName,
    argsClassName: ClassName,
    generatedClassName: String,
    members: List<MemberModel>,
    visibility: Visibility,
    name: FunctionName,
): FunSpec {
    val funBuilder = FunSpec.builder(name.name)
        .receiver(moduleClassName)
        .returns(moduleClassName)
        .deprecatedIfRenamed(name, members.map { it.name }, moduleClassName.packageName)
    if (visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }

    for (member in members) {
        val paramBuilder = ParameterSpec.builder(member.name, bindingTypeOf(moduleClassName, argsClassName, member))
        paramBuilder.defaultValue(delegateBindingCode(member, name.receiver))
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
 * Emits one `fun <Module>.setOf<Annotation>(): Set<Of>` extension per [ModuleModel.sets] entry, as
 * visible as the module and `suspend` as resolved by [SetModel.isSuspend]: it reads every collected
 * member off the receiver (a function called without arguments, its parameters all `vararg` or
 * defaulted) into `setOfNotNull`, so a nullable member's `null` is left out.
 *
 * `setOfNotNull` is called fully qualified: a set annotation named `NotNull` generates a
 * `setOfNotNull()` extension of its own in the same package, which must not capture the call.
 */
internal fun buildSetFunctions(model: ModuleModel): List<FunSpec> = model.sets.map { set ->
    val funBuilder = FunSpec.builder(set.functionName)
        .receiver(model.moduleClassName)
        .returns(SET.parameterizedBy(set.elementTypeName))
    if (model.visibility == Visibility.INTERNAL) {
        funBuilder.addModifiers(KModifier.INTERNAL)
    }
    if (set.isSuspend) {
        funBuilder.addModifiers(KModifier.SUSPEND)
    }
    for (file in set.originatingFiles) {
        funBuilder.addOriginatingKSFile(file)
    }

    val body = CodeBlock.builder()
    if (set.members.isEmpty()) {
        // Only reachable through an inherited set whose members this compilation can't see.
        body.addStatement("return kotlin.collections.emptySet()")
    } else {
        body.add("return kotlin.collections.setOfNotNull(\n").indent()
        for (member in set.members) {
            if (member.isFunction) body.addStatement("%N(),", member.name) else body.addStatement("%N,", member.name)
        }
        body.unindent().add(")\n")
    }
    funBuilder.addCode(body.build()).build()
}

/**
 * The copy overload that lets a whole `@DIModule` parent be swapped out at once, emitted under
 * [name]: one parameter per parent, each defaulting to the receiver itself, building the same
 * `Inara<Module>` as every other builder/copy.
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
    name: FunctionName,
): FunSpec {
    val ownMemberNames = model.ownMembers.mapTo(mutableSetOf()) { it.name }

    val funBuilder = FunSpec.builder(name.name)
        .receiver(model.moduleClassName)
        .returns(model.moduleClassName)
        .deprecatedIfRenamed(name, model.parents.map { it.paramName }, model.moduleClassName.packageName)
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
                    storedBindingOrDelegateCode(member, name.receiver, selfGeneratedClass)
                } else {
                    val parent = model.parentByMemberName.getValue(member.name)
                    parentSwapInheritedCode(member, parent.paramName, parent.generatedClassName, selfGeneratedClass, name.receiver)
                }
                addStatement("%N = %L,", "${member.name}Binding", valueCode)
            }
        }
        .unindent()
        .add(")\n")
        .build()

    return funBuilder.addCode(callCode).build()
}
