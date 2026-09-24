package org.kodein.di.inara.processor

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/** Mirrors `org.kodein.di.inara.DIFactory.Suspend` - read from the annotation via [readSuspendMode]
 * since the processor has no compile-time dependency on `inara-runtime`'s actual enum class. */
internal enum class SuspendMode { FORBID, ALLOW, FORCE }

/**
 * Reads `@DIFactory`'s `suspend` argument. Per `KSValueArgument.value`'s documented contract, an
 * enum-typed annotation argument surfaces as a `KSClassDeclaration` (the entry) - reading its
 * simple name avoids depending on exactly which `ClassKind` KSP reports for it. Falls back from
 * `arguments` to `defaultArguments` to cover whichever of the two actually carries an unspecified,
 * defaulted argument.
 */
private fun readSuspendMode(annotation: KSAnnotation): SuspendMode {
    val arg = annotation.arguments.find { it.name?.asString() == "suspend" }
        ?: annotation.defaultArguments.find { it.name?.asString() == "suspend" }
    return when ((arg?.value as? KSClassDeclaration)?.simpleName?.asString()) {
        "Forbid" -> SuspendMode.FORBID
        "Force" -> SuspendMode.FORCE
        else -> SuspendMode.ALLOW // "Allow", or the argument is missing/unrecognized
    }
}

/**
 * Reads `@DIFactory`'s `constructorIndex` argument - `0` (the default) selects `builds`'s primary
 * constructor, `1` its first secondary, and so on. Same `arguments` -> `defaultArguments` fallback as
 * [readSuspendMode]; an `Int`-typed argument needs no unwrapping.
 */
private fun readConstructorIndex(annotation: KSAnnotation): Int {
    val arg = annotation.arguments.find { it.name?.asString() == "constructorIndex" }
        ?: annotation.defaultArguments.find { it.name?.asString() == "constructorIndex" }
    return arg?.value as? Int ?: 0
}

/**
 * How a `@DIFactory`'s `with` / `withNullable` value is passed to a candidate provider that takes
 * one parameter - `null` everywhere when neither is set (no `with` parameter is generated at all).
 */
internal sealed class WithArgument {
    /** `provider(with)` - the parameter is non-vararg and the `with` value casts straight into it. */
    internal data object Positional : WithArgument()

    /** `provider(<parameterName> = with)` - the parameter is a vararg whose array type `with` is;
     * a named argument assigns straight into a vararg, no spread operator needed. */
    internal data class Named(val parameterName: String) : WithArgument()
}

/**
 * Where a `@DIFactory`-generated constructor argument's value comes from: an `arg<Argument>`
 * extension function declared on the `@DIFactory` object itself, the generated function's own
 * call-time `with` value, or a member of the `from` module resolved automatically (see
 * [resolveFactory]).
 */
internal sealed class ArgumentSource {
    /** `module.<functionName>()` - an `arg<Argument>` function on the `@DIFactory` object. Takes the
     * `with` value (`module.<functionName>(with)`) when `with` / `withNullable` is set - uniformly,
     * so the generator reads that off [FactoryModel.withTypeName] rather than from here. */
    internal data class FactoryFunction(val functionName: String) : ArgumentSource()

    /** `with` - the generated function's own call-time parameter, passed straight through. Only ever
     * produced when `with` / `withNullable` is set, so the generator's `with` parameter exists. */
    internal data object WithValue : ArgumentSource()

    /** `module.<memberName>` - a `val` (or no-arg `fun`, see [ModuleFunction]) on `from`. */
    internal data class ModuleProperty(val memberName: String) : ArgumentSource()

    /** `module.<memberName>()` - a `fun` on `from`, called with no argument ([withArgument] `null`)
     * or with the `with` value per [withArgument]. */
    internal data class ModuleFunction(val memberName: String, val withArgument: WithArgument?) : ArgumentSource()

    /** `<functionParamName>` - the generated function's own pass-through parameter, passed straight
     * through to the constructor. Produced when nothing else resolves the argument (step 5): rather
     * than a compile error, the generated function simply asks the caller for it. Never `suspend`
     * (see [FactoryArgumentModel.isSuspend]) and never a `vararg` (see [FactoryContext.varargParams]
     * - a vararg is always omissible, so it never reaches step 5). */
    internal data class PassThrough(val functionParamName: String) : ArgumentSource()
}

/** One argument of `builds`'s selected constructor (see `constructorIndex`), resolved to where its
 * value comes from.
 * [isSuspend] is true when the resolved provider - the arg-function, or the module member - is
 * itself `suspend`; always false for a [ArgumentSource.ModuleProperty] (a property getter can't be
 * `suspend`). */
internal data class FactoryArgumentModel(
    val paramName: String,
    val source: ArgumentSource,
    val isSuspend: Boolean,
)

/**
 * A `@DIFactory` object, parsed and every one of `builds`'s selected constructor's arguments resolved.
 *
 * [visibility] equals the `@DIFactory` object's own visibility (`public` or `internal`) - the
 * generated function is an extension on that object, so it can be no more visible. [parseFactories]
 * rejects a `public` object whose `builds`, selected constructor, `from`, or `with` / `withNullable`
 * type is `internal` (that function's signature couldn't compile) rather than silently narrowing.
 *
 * [isSuspend] drives whether the generated builder function itself is `suspend` - see
 * [SuspendMode]: always `false` under `Suspend.Forbid` (structurally, since no `suspend` provider
 * is ever resolved under that mode), true when any resolved argument is under `Suspend.Allow`, and
 * unconditionally `true` under `Suspend.Force`.
 */
internal data class FactoryModel(
    val factoryClassName: ClassName,
    /** The `@DIFactory` object's simple name flattened across its enclosing scopes (`OuterA.Fac` ->
     * `OuterAFac`), used to name the generated file - see `generateFactoryFile`. */
    val generatedBaseName: String,
    val buildsClassName: ClassName,
    val fromClassName: ClassName,
    /** The generated function's `with` parameter type - `null` when neither `with` nor `withNullable`
     * is set, and itself nullable (`Ctx?`) under `withNullable`. */
    val withTypeName: TypeName?,
    val visibility: Visibility,
    val arguments: List<FactoryArgumentModel>,
    /** One parameter the generated function takes purely to hand it to the constructor - a step-5
     * argument nothing resolved, in constructor declaration order, placed after `module` and `with`
     * (see `DIFactoryFileGenerator`). */
    val passThroughParameters: List<PassThroughParameter>,
    val isSuspend: Boolean,
    val originatingFiles: List<KSFile>,
)

/** One [FactoryModel.passThroughParameters] entry: [name] is the generated function's parameter
 * name - always `arg<Argument>` (see `passThroughParamName`), never the constructor parameter's own
 * name - [typeName] the constructor parameter's own declared type, unchanged. */
internal data class PassThroughParameter(val name: String, val typeName: TypeName)

/** The simple name of the `arg<Argument>` function that would resolve a constructor parameter
 * named [paramName], e.g. `argEndpoint` for `endpoint`. */
private fun argFunctionName(paramName: String): String = "arg${paramName.replaceFirstChar(Char::uppercaseChar)}"

/** True when [a] and [b] denote the same type - `KSType` has no reliable structural `equals`, so
 * mutual assignability is the robust way to ask this using only the documented API. */
private fun sameType(a: KSType, b: KSType): Boolean = a.isAssignableFrom(b) && b.isAssignableFrom(a)

/** Every declaration [type] structurally reaches: its own, an alias it is written through *and*
 * that alias's expansion (a `typealias` doesn't shield what it expands to, e.g. `typealias Ids =
 * List<InternalId>`), and recursively every type argument's. [depth] bounds the recursion for a
 * pathological or self-referential generic rather than relying on `KSType` identity, which isn't a
 * documented equality contract. Backs both [containsUnresolvedTypeParameter]/[containsErrorType]
 * (checked before a pass-through parameter's type is ever handed to `toTypeName()`) and
 * [internalDeclarationsIn] (checked before it is written into a signature). */
private fun reachableDeclarations(type: KSType, depth: Int = 0): List<KSDeclaration> {
    if (depth > 20) return emptyList()
    val decl = type.declaration
    val result = mutableListOf(decl)
    if (decl is KSTypeAlias) result += reachableDeclarations(decl.type.resolve(), depth + 1)
    for (arg in type.arguments) {
        val argType = arg.type?.resolve() ?: continue
        result += reachableDeclarations(argType, depth + 1)
    }
    return result
}

/** True when [type] (or a type argument of it, recursively) resolves to a type parameter of the
 * enclosing generic declaration - the shape [parseFactories]'s KDoc already documents as
 * unsupported for a generic `builds` class. `KSType.toTypeName()` throws on one with the default
 * (empty) `TypeParameterResolver`, so a pass-through parameter must be checked for this *before*
 * that call, or the processor crashes instead of leaving it as an ordinary compile error. */
private fun containsUnresolvedTypeParameter(type: KSType, depth: Int = 0): Boolean {
    if (depth > 20) return false
    if (type.declaration is KSTypeParameter) return true
    return type.arguments.any { arg ->
        arg.type?.resolve()?.let { containsUnresolvedTypeParameter(it, depth + 1) } == true
    }
}

/** True when [type] (or a type argument of it, recursively) is itself an unresolved error type -
 * reachable when `builds` is pulled in from a module that has its own, unrelated compile error.
 * `KSType.toTypeName()` throws on one, so this must be checked before that call too. */
private fun containsErrorType(type: KSType, depth: Int = 0): Boolean {
    if (depth > 20) return false
    if (type.isError) return true
    return type.arguments.any { arg ->
        arg.type?.resolve()?.let { containsErrorType(it, depth + 1) } == true
    }
}

/** One `internal` (or Java package-private) declaration [reachableDeclarations] finds in a
 * pass-through parameter's type. [isCrossModule] is true when it was compiled elsewhere - a class
 * read from a `.class` file has no [KSDeclaration.containingFile], and a Java package-private
 * declaration is foreign to Kotlin-generated code by construction - and such a type can never be
 * named from generated code at *any* visibility of the `@DIFactory` object, unlike a same-module
 * `internal` type, which only public generated code can't name. */
private data class InternalTypeUse(val declaration: KSDeclaration, val isCrossModule: Boolean)

private fun internalDeclarationsIn(type: KSType): List<InternalTypeUse> = reachableDeclarations(type).mapNotNull { decl ->
    val visibility = when (decl) {
        is KSClassDeclaration -> decl.getVisibility()
        is KSTypeAlias -> decl.getVisibility()
        else -> null
    }
    when (visibility) {
        Visibility.INTERNAL -> InternalTypeUse(decl, isCrossModule = decl.containingFile == null)
        Visibility.JAVA_PACKAGE -> InternalTypeUse(decl, isCrossModule = true)
        else -> null
    }
}

/**
 * Assigns the generated function's parameter name for a pass-through constructor argument named
 * [paramName] - always [argFunctionName]'s `arg<Argument>` form, never the constructor parameter's
 * own name, so a pass-through parameter is unmistakable by prefix alone in the generated signature:
 * one `module` argument, zero or one `with` argument, and zero or more `arg*` arguments. This also
 * makes a collision with the literal names `module` or `with` structurally impossible - neither is
 * ever an `arg<Argument>` string - so the only collision left is two sibling pass-through parameters
 * landing on the same name (e.g. constructor parameters `count` and `Count`), deduplicated by
 * appending `_` until free - deterministic and total, since each step strictly lengthens the
 * candidate. [usedNames] tracks every name already assigned in this generated function and is
 * mutated with the chosen one.
 */
private fun passThroughParamName(paramName: String, usedNames: MutableSet<String>): String {
    var candidate = argFunctionName(paramName)
    while (candidate in usedNames) candidate += "_"
    usedNames += candidate
    return candidate
}

/**
 * Reads one of `@DIFactory`'s `with` / `withNullable` arguments ([argumentName]) - the type of the
 * second parameter the generated builder function takes. `Unit` is the "unset" sentinel for each
 * (an explicit `with = Unit::class` reads the same as an omitted one): this returns `null`, and
 * every `with`-dependent branch downstream treats `null` as "no such parameter". Same `arguments` ->
 * `defaultArguments` fallback as [readSuspendMode]; a `KClass`-typed argument surfaces as a [KSType].
 */
private fun readWithType(annotation: KSAnnotation, argumentName: String): KSType? {
    val arg = annotation.arguments.find { it.name?.asString() == argumentName }
        ?: annotation.defaultArguments.find { it.name?.asString() == argumentName }
    val type = arg?.value as? KSType ?: return null
    return type.takeUnless { it.declaration.qualifiedName?.asString() == "kotlin.Unit" }
}

/**
 * How a `@DIFactory`'s [withType] value would be passed to [param] of a candidate module member -
 * `null` if it can't be (so the member is not a candidate for that argument at all).
 *
 * A non-vararg parameter takes the value positionally when [withType] casts into it. A vararg
 * parameter's declared [ParameterModel.type] is its *element* type; the value goes in as a named
 * argument (which assigns straight into a vararg, no spread operator) when [withType] is exactly
 * that vararg's array type as seen inside the function body - a dedicated primitive array for a
 * non-nullable primitive element (see [PRIMITIVE_ARRAYS]), else `kotlin.Array` with a type argument
 * assignable to the element type. A `withNullable` value (a nullable [withType]) never matches a
 * vararg - a vararg's array type is never itself nullable - so it only ever resolves positionally.
 */
private fun withArgumentFor(param: ParameterModel, withType: KSType): WithArgument? {
    if (!param.isVararg) {
        return if (param.type.isAssignableFrom(withType)) WithArgument.Positional else null
    }
    if (withType.isMarkedNullable) return null
    val elementType = param.type
    val elementName = elementType.declaration.qualifiedName?.asString()
    val withName = withType.declaration.qualifiedName?.asString()
    val primitiveArray = if (!elementType.isMarkedNullable) PRIMITIVE_ARRAYS[elementName] else null
    val matches = if (primitiveArray != null) {
        withName == primitiveArray.canonicalName
    } else {
        withName == "kotlin.Array" &&
            withType.arguments.singleOrNull()?.type?.resolve()?.let { elementType.isAssignableFrom(it) } == true
    }
    return if (matches) WithArgument.Named(param.name) else null
}

/** One `@DIFactory` annotation on an object, validated up to (not including) constructor-argument
 * resolution - the input [resolveFactory] turns into a [FactoryModel]. */
internal class FactoryContext(
    val buildsDecl: KSClassDeclaration,
    val fromDecl: KSClassDeclaration,
    val fromModel: ModuleModel,
    val buildsName: String,
    val fromName: String,
    val fromType: KSType,
    val ctorParams: Map<String, KSType>,
    val omissibleParams: Set<String>,
    val varargParams: Set<String>,
    val withType: KSType?,
    val withDecl: KSClassDeclaration?,
    val withArgName: String,
    val suspendMode: SuspendMode,
    val originatingFiles: List<KSFile>,
)

/**
 * Validates [objDecl] against every `@DIFactory` rule and builds one [FactoryModel] per `@DIFactory`
 * annotation on it (the annotation is `@Repeatable`), resolving each `builds` constructor's
 * arguments (see `constructorIndex`). An `arg<Argument>` function declared on the object is shared
 * across the annotations, matched by extension receiver and parameter name; it is an error only if
 * it fits none of them.
 *
 * Every violation found is reported through [logger] before returning - so a single compilation
 * surfaces every problem at once. Returns an empty list if anything about the object is invalid; a
 * valid object always yields at least one model.
 *
 * One shape is explicitly unsupported and neither validated against nor specially handled: a
 * `vararg` parameter on `builds`'s selected constructor - a `vararg` argument is always omissible
 * (see `omissibleParams`), so it never reaches step 5 and this never comes up in practice; if it
 * ever did, it would surface as an ordinary Kotlin compile error in the generated file rather than a
 * dedicated diagnostic here. A generic `builds` class used as more than a raw type - a constructor
 * argument typed with one of its own type parameters - *is* handled: [resolveFactory] reports a
 * dedicated diagnostic for it rather than letting a step-5 pass-through crash the processor (see
 * `containsUnresolvedTypeParameter`).
 */
internal fun parseFactories(
    objDecl: KSClassDeclaration,
    logger: KSPLogger,
    moduleCache: MutableMap<String, ModuleModel?>,
): List<FactoryModel> {
    var hasError = false
    fun reportError(message: String, node: KSNode) {
        logger.error(message, node)
        hasError = true
    }

    if (objDecl.classKind != ClassKind.OBJECT) {
        reportError("Only objects may be annotated with @DIFactory.", objDecl)
        return emptyList()
    }
    val objVisibility = objDecl.getVisibility()
    if (objVisibility !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
        reportError("A @DIFactory object must be public or internal.", objDecl)
        return emptyList()
    }

    fun KSAnnotation.fqn() = annotationType.resolve().declaration.qualifiedName?.asString()
    // KSP 2 lists the repeated annotations individually; the `.Container` unwrap is belt-and-braces
    // for KSP versions/platforms that instead expose the synthetic JVM container.
    val annotations = objDecl.annotations.filter { it.fqn() == DI_FACTORY_ANNOTATION }.toList() +
        objDecl.annotations.filter { it.fqn() == DI_FACTORY_CONTAINER_ANNOTATION }.flatMap { container ->
            (container.arguments.firstOrNull { it.name?.asString() == "value" }?.value as? List<*>)
                .orEmpty().filterIsInstance<KSAnnotation>()
        }

    val contexts: List<FactoryContext> = annotations.mapNotNull {
        parseFactoryContext(objDecl, it, objVisibility, moduleCache, logger, ::reportError)
    }
    val argFunctionMaps = validateArgFunctions(objDecl, contexts, objVisibility, ::reportError)
    val models = contexts.zip(argFunctionMaps).map { (context, argFunctions) ->
        resolveFactory(objDecl, objVisibility, context, argFunctions, ::reportError)
    }

    // Two annotations that would emit the same `fun Obj.Builds(module: From[, with: W][, pass-through
    // params...])` - identical `builds`, `from`, `with` type and pass-through parameter *types* (in
    // order; names don't disambiguate an overload) - collide as a redeclaration. A differing `from`,
    // `with`, `constructorIndex` (now that it can change which parameters fall through to step 5) or
    // pass-through shape is a real overload. Skipped when a context already errored: an ambiguous
    // argument (see `resolveFactory`) drops out of `arguments`/`passThroughParameters` entirely
    // rather than resolving one way or the other, which could otherwise make two genuinely different
    // annotations hash to the same key on top of the error already reported for them.
    if (!hasError) {
        models.groupBy {
            Triple(it.buildsClassName, it.fromClassName, it.withTypeName) to it.passThroughParameters.map { p -> p.typeName }
        }
            .values.filter { it.size > 1 }
            .forEach { dups ->
                val m = dups.first()
                val withPart = m.withTypeName?.let { ", with: ${it.simpleString()}" } ?: ""
                val passThroughPart = m.passThroughParameters.joinToString("") { ", ${it.name}: ${it.typeName.simpleString()}" }
                reportError(
                    "'${objDecl.simpleName.asString()}' has ${dups.size} @DIFactory annotations that all generate " +
                        "'fun ${objDecl.simpleName.asString()}.${m.buildsClassName.simpleName}(module: " +
                        "${m.fromClassName.simpleName}$withPart$passThroughPart): ${m.buildsClassName.simpleName}'.",
                    objDecl,
                )
            }
    }

    return if (hasError) emptyList() else models
}

private fun parseFactoryContext(
    objDecl: KSClassDeclaration,
    annotation: KSAnnotation,
    objVisibility: Visibility,
    moduleCache: MutableMap<String, ModuleModel?>,
    logger: KSPLogger,
    reportError: (String, KSNode) -> Unit,
): FactoryContext? {
    val suspendMode = readSuspendMode(annotation)
    val constructorIndex = readConstructorIndex(annotation)
    val buildsArg = annotation.arguments.find { it.name?.asString() == "builds" }
    val fromArg = annotation.arguments.find { it.name?.asString() == "from" }
    val buildsDecl = (buildsArg?.value as? KSType)?.declaration as? KSClassDeclaration
    val fromDecl = (fromArg?.value as? KSType)?.declaration as? KSClassDeclaration
    // `null` when neither `with` nor `withNullable` is set (both default to `Unit`) - no `with`
    // parameter on the generated function, and `arg<Argument>` functions take nothing, exactly as
    // before this argument existed. The two collapse into one `withType` here, `withNullable`
    // differing only by carrying nullability, so every branch below stays single-valued;
    // `withArgName` names the one actually written, for diagnostics.
    val plainWithType = readWithType(annotation, "with")
    val nullableWithType = readWithType(annotation, "withNullable")
    if (plainWithType != null && nullableWithType != null) {
        reportError(
            "Only one of 'with' and 'withNullable' may be set on @DIFactory - " +
                "'${objDecl.simpleName.asString()}' sets both.",
            objDecl,
        )
        return null
    }
    val withArgName = if (nullableWithType != null) "withNullable" else "with"
    val withType = plainWithType ?: nullableWithType?.makeNullable()
    val withDecl = withType?.declaration as? KSClassDeclaration

    if (buildsDecl == null) {
        reportError("The 'builds' argument must reference a class.", objDecl)
        return null
    }
    if (fromDecl == null) {
        reportError("The 'from' argument must reference an interface.", objDecl)
        return null
    }

    val buildsName = buildsDecl.simpleName.asString()
    val isConcreteClass = buildsDecl.classKind == ClassKind.CLASS &&
        Modifier.ABSTRACT !in buildsDecl.modifiers &&
        Modifier.SEALED !in buildsDecl.modifiers
    if (!isConcreteClass) {
        reportError("The 'builds' argument must be a concrete class: '$buildsName' is not.", objDecl)
        return null
    }
    if (buildsDecl.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
        reportError("The 'builds' class '$buildsName' must be public or internal.", objDecl)
        return null
    }
    // `getConstructors()` (declarations filtered to constructors) is documented to preserve lexical
    // declaration order, and a class's primary constructor is always declared in its header, ahead
    // of any secondary - so it naturally lands at index 0 without special-casing, matching
    // `constructorIndex`'s "0 = primary, 1 = first secondary, ..." contract directly.
    val constructors = buildsDecl.getConstructors().toList()
    if (constructorIndex < 0 || constructorIndex >= constructors.size) {
        reportError(
            "'constructorIndex' is $constructorIndex, but the 'builds' class '$buildsName' has " +
                "${constructors.size} constructor(s) - it must be between 0 and ${constructors.size - 1}.",
            objDecl,
        )
        return null
    }
    val ctor = constructors[constructorIndex]
    if (ctor.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
        reportError(
            if (constructorIndex == 0) {
                "The 'builds' class '$buildsName' must have a public or internal primary constructor."
            } else {
                "The 'builds' class '$buildsName' must have a public or internal constructor at index $constructorIndex."
            },
            objDecl,
        )
        return null
    }

    if (!fromDecl.isDIModule()) {
        reportError(
            "The 'from' argument must be a @DIModule interface: '${fromDecl.simpleName.asString()}' is not.",
            objDecl,
        )
        return null
    }
    val fromModel = parseModule(fromDecl, logger, moduleCache) ?: return null

    val fromName = fromDecl.simpleName.asString()
    val fromType = fromDecl.asStarProjectedType()
    val ctorParams = ctor.parameters.associate { p -> p.name!!.asString() to p.type.resolve() }
    // Parameters Kotlin can supply itself when the generated call omits them: an explicit `= ...`
    // default, or a `vararg` (omitted = empty array). KSP reports `hasDefault = false` for a
    // vararg, so the two have to be OR-ed rather than relying on `hasDefault` alone.
    val omissibleParams = ctor.parameters
        .filter { it.hasDefault || it.isVararg }
        .mapTo(mutableSetOf()) { it.name!!.asString() }
    // `ctorParams` maps a vararg to its *element* type, so a same-typed `with` value would appear
    // assignable to it - but a named vararg argument takes the array, not one element. A vararg is
    // therefore never resolved from the `with` value; it keeps falling through to its own default.
    val varargParams = ctor.parameters.filter { it.isVararg }.mapTo(mutableSetOf()) { it.name!!.asString() }

    // The generated function is an extension on the `@DIFactory` object, so it is exactly as visible
    // as the object. A `public` function still can't name an `internal` type in its signature, so a
    // `public` object whose `from`, `builds`, selected constructor, or `with` / `withNullable` type
    // is `internal` can't work - report that here rather than emitting a file `kotlinc` then rejects.
    if (objVisibility == Visibility.PUBLIC) {
        val internalDeps = buildList {
            if (fromDecl.getVisibility() == Visibility.INTERNAL) add("the 'from' module '$fromName'")
            if (buildsDecl.getVisibility() == Visibility.INTERNAL) add("the 'builds' class '$buildsName'")
            if (ctor.getVisibility() == Visibility.INTERNAL) add("its selected constructor")
            if (withDecl?.getVisibility() == Visibility.INTERNAL) {
                add("the '$withArgName' type '${withDecl.simpleName.asString()}'")
            }
        }
        if (internalDeps.isNotEmpty()) {
            val isAre = if (internalDeps.size == 1) "is" else "are"
            val itThem = if (internalDeps.size == 1) "it" else "them"
            reportError(
                "Cannot generate the 'public' factory function '${objDecl.simpleName.asString()}.$buildsName' " +
                    "because ${internalDeps.joinToString(", ")} $isAre 'internal'. Make " +
                    "'${objDecl.simpleName.asString()}' 'internal', or make $itThem 'public'.",
                objDecl,
            )
            return null
        }
    }

    val originatingFiles: List<KSFile> = listOfNotNull(
        objDecl.containingFile,
        buildsDecl.containingFile,
        fromDecl.containingFile,
        withDecl?.containingFile,
    ) + fromModel.ancestorDeclarations.mapNotNull { it.containingFile }

    return FactoryContext(
        buildsDecl = buildsDecl,
        fromDecl = fromDecl,
        fromModel = fromModel,
        buildsName = buildsName,
        fromName = fromName,
        fromType = fromType,
        ctorParams = ctorParams,
        omissibleParams = omissibleParams,
        varargParams = varargParams,
        withType = withType,
        withDecl = withDecl,
        withArgName = withArgName,
        suspendMode = suspendMode,
        originatingFiles = originatingFiles,
    )
}

/**
 * The object may declare nothing but well-shaped `fun <From>.arg<Argument>(): T` functions. Each is
 * shared across every [FactoryContext] whose `from` is its extension receiver and whose selected
 * constructor has a parameter of that name and type; it is rejected only if it fits none. Returns
 * one `paramName -> function` map per entry of [contexts], in the same order.
 */
private fun validateArgFunctions(
    objDecl: KSClassDeclaration,
    contexts: List<FactoryContext>,
    objVisibility: Visibility,
    reportError: (String, KSNode) -> Unit,
): List<Map<String, KSFunctionDeclaration>> {
    val argFunctions = List(contexts.size) { mutableMapOf<String, KSFunctionDeclaration>() }
    val objName = objDecl.simpleName.asString()

    fun notAnArgFunction(name: String, node: KSNode) = reportError(
        "A @DIFactory object may only declare 'fun <Module>.arg<Argument>(): T' functions - '$name' is not one.",
        node,
    )

    for (decl in objDecl.declarations) {
        // `declarations` includes the object's own synthetic no-arg constructor - not a real
        // member, skip it silently rather than rejecting it as an invalid one.
        if (decl is KSFunctionDeclaration && decl.simpleName.asString() == "<init>") continue

        val fn = decl as? KSFunctionDeclaration
        if (fn == null) {
            notAnArgFunction(decl.simpleName.asString(), decl)
            continue
        }
        val name = fn.simpleName.asString()
        if (!(name.startsWith("arg") && name.length > 3 && name[3].isUpperCase())) {
            notAnArgFunction(name, fn)
            continue
        }
        val paramName = name.removePrefix("arg").replaceFirstChar(Char::lowercaseChar)

        if (objVisibility == Visibility.PUBLIC) {
            if (fn.getVisibility() != Visibility.INTERNAL) {
                reportError(
                    "'$name' must be 'internal': '$objName' is 'public', so its arg functions may not " +
                        "widen its public API.",
                    fn,
                )
                continue
            }
        } else if (fn.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            reportError("'$name' must be public or internal.", fn)
            continue
        }

        val nameMatches = contexts.withIndex().filter { paramName in it.value.ctorParams }
        if (nameMatches.isEmpty()) {
            val allParams = contexts.flatMap { it.ctorParams.keys }.distinct().sorted()
            reportError(
                "'$name' does not match any constructor argument of a @DIFactory on '$objName' - name it " +
                    "'arg<Argument>' for one of: ${allParams.joinToString(", ")}.",
                fn,
            )
            continue
        }

        // With one matching factory the specific mismatch reason is the whole story (unchanged
        // single-annotation behaviour). With several, a mismatch just means "not this one" - it is
        // an error only when the function fits none of them.
        val soleMatch = nameMatches.singleOrNull()
        var servedAny = false
        for ((index, context) in nameMatches) {
            val mismatch = argFunctionMismatch(fn, name, paramName, objName, context)
            if (mismatch != null) {
                if (soleMatch != null) reportError(mismatch, fn)
                continue
            }
            argFunctions[index][paramName] = fn
            servedAny = true
        }
        if (soleMatch == null && !servedAny) {
            reportError("'$name' is not a valid arg function for any @DIFactory on '$objName'.", fn)
        }
    }

    return argFunctions
}

/** `null` when [fn] is a valid `arg<Argument>` function for [context], else why it isn't. */
private fun argFunctionMismatch(
    fn: KSFunctionDeclaration,
    name: String,
    paramName: String,
    objName: String,
    context: FactoryContext,
): String? {
    val paramType = context.ctorParams.getValue(paramName)
    if (context.suspendMode == SuspendMode.FORBID && Modifier.SUSPEND in fn.modifiers) {
        return "'$name' may not be 'suspend': 'suspend' is 'Suspend.Forbid' on '$objName'."
    }
    val withType = context.withType
    if (withType == null) {
        if (fn.parameters.isNotEmpty()) return "'$name' may not take any parameter."
    } else {
        val singleParam = fn.parameters.singleOrNull()
        val paramMatchesWith = singleParam != null && !singleParam.isVararg &&
            sameType(singleParam.type.resolve(), withType)
        if (!paramMatchesWith) {
            return "'$name' must take exactly one parameter of type " +
                "'${withType.toTypeName().simpleString()}' (the @DIFactory '${context.withArgName}' type)."
        }
    }
    val receiverType = fn.extensionReceiver?.resolve()
    if (receiverType == null || !receiverType.isAssignableFrom(context.fromType)) {
        return "'$name' must be an extension function on '${context.fromName}'."
    }
    val returnType = fn.returnType?.resolve()
    if (returnType == null || !sameType(returnType, paramType)) {
        return "'$name' must return the type of '${context.buildsName}''s '$paramName' argument."
    }
    return null
}

/** Resolves every argument of [context]'s constructor and builds the [FactoryModel]. */
private fun resolveFactory(
    objDecl: KSClassDeclaration,
    objVisibility: Visibility,
    context: FactoryContext,
    argFunctions: Map<String, KSFunctionDeclaration>,
    reportError: (String, KSNode) -> Unit,
): FactoryModel {
    val buildsName = context.buildsName
    val fromName = context.fromName
    val withType = context.withType
    val suspendMode = context.suspendMode

    // Every constructor argument must be uniquely resolvable, in priority order: an arg function
    // (see `argFunctions`), else the call-time `with` value when its type fits the argument, else the
    // parameter's own default (see `omissibleParams` - the generated call just omits it), else a
    // unique exactly-typed module member, else a unique assignable-typed module member, else it
    // becomes a parameter of the generated function itself, passed straight to the constructor - the
    // caller supplies it directly, the same way `with` does for the one value that's shared across
    // every argument. The `with` value outranks the default and the module pool: the caller supplied
    // it for this very call, which is more specific than a compile-time default and more deliberate
    // than a type match. The default in turn outranks any module member: it is a deliberate, local
    // statement of what the argument should be absent an override, whereas a module match is by type
    // alone and can be coincidental. An ambiguous match (two or more module members) still stays a
    // compile error rather than falling through - a real mistake in the module, not something to
    // silently defer to the caller.
    // Under Suspend.Forbid, a suspend candidate is excluded here entirely (rather than resolved and
    // then rejected) - the same exclusion already applied to arg-functions, so a resolved
    // FactoryArgumentModel can never end up suspend when the mode is Forbid; its argument instead
    // falls through to a pass-through parameter like any other unresolvable one.
    //
    // A member takes no parameter (called plainly), or - only when `with` / `withNullable` is set -
    // takes one parameter the `with` value can be passed to (see [withArgumentFor]). The two live in one pool:
    // a zero-parameter and a one-parameter member matching the same argument type is an ambiguity,
    // exactly as two zero-parameter members are.
    val candidates: List<Pair<MemberModel, WithArgument?>> = context.fromModel.allMembers.mapNotNull { member ->
        if (suspendMode == SuspendMode.FORBID && member.isSuspend) return@mapNotNull null
        when {
            member.parameters.isEmpty() -> member to null
            withType != null && member.parameters.size == 1 ->
                withArgumentFor(member.parameters.single(), withType)?.let { member to it }
            else -> null
        }
    }
    val arguments = mutableListOf<FactoryArgumentModel>()
    // Tracks every pass-through parameter name already assigned in this generated function, so two
    // siblings never collide - see `passThroughParamName`. Empty to start: every pass-through is
    // `arg<Argument>`, which can never equal the literal `module` or `with`, so those two need no
    // seeding here.
    val usedFunctionParamNames = mutableSetOf<String>()
    val passThroughParameters = mutableListOf<PassThroughParameter>()
    val passThroughDeclarations = mutableListOf<KSDeclaration>()
    for ((paramName, paramType) in context.ctorParams) {
        val resolvedArgFunction = argFunctions[paramName]
        if (resolvedArgFunction != null) {
            arguments += FactoryArgumentModel(
                paramName,
                ArgumentSource.FactoryFunction(resolvedArgFunction.simpleName.asString()),
                isSuspend = Modifier.SUSPEND in resolvedArgFunction.modifiers,
            )
            continue
        }

        // The call-time value itself, when it fits the argument - above the parameter's own default
        // and above any module member (see the priority comment above). It is a single named source
        // rather than a pool, so it wins outright and is never part of an ambiguity. A vararg is
        // excluded (see `varargParams`). `isSuspend = false`: a plain parameter is never a
        // suspending provider, so this holds under `Suspend.Forbid` too.
        if (withType != null && paramName !in context.varargParams && paramType.isAssignableFrom(withType)) {
            arguments += FactoryArgumentModel(paramName, ArgumentSource.WithValue, isSuspend = false)
            continue
        }

        // Adding no FactoryArgumentModel is what applies the default - every emitted argument is
        // named, so an omitted one falls back to the constructor's declared default (or, for a
        // vararg, an empty array). An `arg<Argument>` function still wins, having been handled above.
        if (paramName in context.omissibleParams) continue

        val exact = candidates.filter { sameType(it.first.returnType, paramType) }
        if (exact.size == 1) {
            val (member, withArgument) = exact.single()
            arguments += member.toFactoryArgumentModel(paramName, withArgument)
            continue
        }
        if (exact.size > 1) {
            reportError(
                "Argument '$paramName' of '$buildsName' is ambiguous: matched by " +
                    exact.map { it.first.name }.sorted().joinToString(", ") { "'$it'" } + ", all exactly of that type.",
                objDecl,
            )
            continue
        }

        val assignable = candidates.filter { paramType.isAssignableFrom(it.first.returnType) }
        if (assignable.size == 1) {
            val (member, withArgument) = assignable.single()
            arguments += member.toFactoryArgumentModel(paramName, withArgument)
            continue
        }
        if (assignable.size > 1) {
            reportError(
                "Argument '$paramName' of '$buildsName' is ambiguous: matched by " +
                    assignable.map { it.first.name }.sorted().joinToString(", ") { "'$it'" } + ", all assignable to that type.",
                objDecl,
            )
            continue
        }

        // Step 5: nothing resolved this argument - hand it to the caller as a parameter of the
        // generated function instead of erroring, in constructor order after `module` and `with`
        // (see DIFactoryFileGenerator). A vararg never reaches here (see `omissibleParams` above);
        // the `check` turns a future ordering mistake into a loud failure instead of a silently wrong
        // non-spread pass-through of an array where the constructor expects an element.
        check(paramName !in context.varargParams) {
            "vararg parameter '$paramName' unexpectedly reached step 5 pass-through"
        }

        // Two shapes make `paramType.toTypeName()` throw outright rather than degrade into an
        // ordinary compile error in the generated file - checked structurally, before it is ever
        // called, so the processor reports a diagnostic instead of crashing.
        if (containsUnresolvedTypeParameter(paramType)) {
            reportError(
                "Cannot resolve argument '$paramName' of '$buildsName': its type refers to a type " +
                    "parameter of '$buildsName', which @DIFactory does not support for a generic class.",
                objDecl,
            )
            continue
        }
        if (containsErrorType(paramType)) {
            reportError(
                "Cannot resolve argument '$paramName' of '$buildsName': its type could not be " +
                    "resolved - fix the compile error reported elsewhere first.",
                objDecl,
            )
            continue
        }

        // A pass-through parameter puts the constructor argument's own type directly into the
        // generated signature - unlike every other source above, whose type was already checked
        // against the object's own visibility in `parseFactoryContext` (`from`, `builds`, the
        // selected constructor, `with`/`withNullable`). This type is only known now, so it gets the
        // same check here instead. An `internal` type from *another module* can never be named from
        // generated code at any visibility - Kotlin's `internal` is module-scoped - so that one is
        // unconditional; a same-module `internal` type only breaks a `public` generated function.
        val internalUses = internalDeclarationsIn(paramType)
        val crossModuleUses = internalUses.filter { it.isCrossModule }
        if (crossModuleUses.isNotEmpty()) {
            val names = crossModuleUses.mapNotNull { it.declaration.simpleName?.asString() }.distinct().sorted()
            val argParams = withType?.let { "with: ${it.toTypeName().simpleString()}" } ?: ""
            reportError(
                "Cannot pass argument '$paramName' of '$buildsName' through as a parameter of the " +
                    "generated function: ${names.joinToString(", ") { "'$it'" }} " +
                    (if (names.size == 1) "is" else "are") + " 'internal' in another module, so it " +
                    "could never be named there. Resolve '$paramName' instead - add a 'fun " +
                    "$fromName.${argFunctionName(paramName)}($argParams): " +
                    "${paramType.toTypeName().simpleString()}' function to " +
                    "'${objDecl.simpleName.asString()}', or a matching member to '$fromName'.",
                objDecl,
            )
            continue
        }
        if (objVisibility == Visibility.PUBLIC && internalUses.isNotEmpty()) {
            val names = internalUses.mapNotNull { it.declaration.simpleName?.asString() }.distinct().sorted()
            val isAre = if (names.size == 1) "is" else "are"
            val itThem = if (names.size == 1) "it" else "them"
            reportError(
                "Cannot generate the 'public' factory function '${objDecl.simpleName.asString()}." +
                    "$buildsName' because passing '$paramName' through would expose " +
                    "${names.joinToString(", ") { "'$it'" }}, which $isAre 'internal'. Make " +
                    "'${objDecl.simpleName.asString()}' 'internal', make $itThem 'public', or resolve " +
                    "'$paramName' with an 'arg<Argument>' function or a matching '$fromName' member.",
                objDecl,
            )
            continue
        }

        val functionParamName = passThroughParamName(paramName, usedFunctionParamNames)
        passThroughDeclarations += reachableDeclarations(paramType)
        passThroughParameters += PassThroughParameter(functionParamName, paramType.toTypeName())
        arguments += FactoryArgumentModel(paramName, ArgumentSource.PassThrough(functionParamName), isSuspend = false)
    }

    val isSuspend = when (suspendMode) {
        SuspendMode.FORBID -> false
        SuspendMode.ALLOW -> arguments.any { it.isSuspend }
        SuspendMode.FORCE -> true
    }

    return FactoryModel(
        factoryClassName = objDecl.toClassName(),
        generatedBaseName = objDecl.generatedBaseName(),
        buildsClassName = context.buildsDecl.toClassName(),
        fromClassName = context.fromDecl.toClassName(),
        withTypeName = withType?.toTypeName(),
        visibility = objVisibility,
        arguments = arguments,
        passThroughParameters = passThroughParameters,
        isSuspend = isSuspend,
        // A pass-through parameter's type now appears by name in the generated file, so its
        // declaration's containing file is an input the generated file must be regenerated for -
        // added here (rather than in `parseFactoryContext`) since it's only known post-resolution.
        originatingFiles = (context.originatingFiles + passThroughDeclarations.mapNotNull { it.containingFile }).distinct(),
    )
}

private fun MemberModel.toFactoryArgumentModel(paramName: String, withArgument: WithArgument?): FactoryArgumentModel {
    val source = if (isFunction) ArgumentSource.ModuleFunction(name, withArgument) else ArgumentSource.ModuleProperty(name)
    return FactoryArgumentModel(paramName, source, isSuspend = isSuspend)
}
