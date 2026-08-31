package org.kodein.di.inara.processor

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
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
 * How a `@DIFactory`'s `with` value is passed to a candidate provider that takes one parameter -
 * `null` everywhere when `with` is `Unit` (no `with` parameter is generated at all).
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
 * extension function declared on the `@DIFactory` object itself, or a member of the `from` module
 * resolved automatically (see [parseFactory]).
 */
internal sealed class ArgumentSource {
    /** `module.<functionName>()` - an `arg<Argument>` function on the `@DIFactory` object. Takes the
     * `with` value (`module.<functionName>(with)`) when `with` is not `Unit` - uniformly, so the
     * generator reads that off [FactoryModel.withTypeName] rather than from here. */
    internal data class FactoryFunction(val functionName: String) : ArgumentSource()

    /** `module.<memberName>` - a `val` (or no-arg `fun`, see [ModuleFunction]) on `from`. */
    internal data class ModuleProperty(val memberName: String) : ArgumentSource()

    /** `module.<memberName>()` - a `fun` on `from`, called with no argument ([withArgument] `null`)
     * or with the `with` value per [withArgument]. */
    internal data class ModuleFunction(val memberName: String, val withArgument: WithArgument?) : ArgumentSource()
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
 * [visibility] is `internal` when any of `builds`, the selected constructor, `from`, or `with` is
 * `internal`, else `public` - it is never the `@DIFactory` object's own visibility (which is always
 * `internal`, see [parseFactory]). It drives whether the generated builder function is `internal`.
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
    val withTypeName: TypeName?,
    val visibility: Visibility,
    val arguments: List<FactoryArgumentModel>,
    val isSuspend: Boolean,
    val originatingFiles: List<KSFile>,
)

/** The simple name of the `arg<Argument>` function that would resolve a constructor parameter
 * named [paramName], e.g. `argEndpoint` for `endpoint`. */
private fun argFunctionName(paramName: String): String = "arg${paramName.replaceFirstChar(Char::uppercaseChar)}"

/** True when [a] and [b] denote the same type - `KSType` has no reliable structural `equals`, so
 * mutual assignability is the robust way to ask this using only the documented API. */
private fun sameType(a: KSType, b: KSType): Boolean = a.isAssignableFrom(b) && b.isAssignableFrom(a)

/**
 * Reads `@DIFactory`'s `with` argument - the type of the second parameter the generated builder
 * function takes. `Unit` (the default) means no such parameter: this returns `null`, and every
 * `with`-dependent branch downstream treats `null` as "no `with`". Same `arguments` ->
 * `defaultArguments` fallback as [readSuspendMode]; a `KClass`-typed argument surfaces as a [KSType].
 */
private fun readWithType(annotation: KSAnnotation): KSType? {
    val arg = annotation.arguments.find { it.name?.asString() == "with" }
        ?: annotation.defaultArguments.find { it.name?.asString() == "with" }
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
 * assignable to the element type.
 */
private fun withArgumentFor(param: ParameterModel, withType: KSType): WithArgument? {
    if (!param.isVararg) {
        return if (param.type.isAssignableFrom(withType)) WithArgument.Positional else null
    }
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

/**
 * Validates [objDecl] against every `@DIFactory` rule, resolves every one of `builds`'s selected
 * constructor's arguments (see `constructorIndex`), and builds a [FactoryModel] out of it.
 *
 * Every violation found is reported through [logger] before returning - so a single compilation
 * surfaces every problem at once, rather than one error per run. Returns `null` if the declaration
 * is invalid.
 *
 * Two shapes are explicitly unsupported and neither validated against nor specially handled: a
 * `vararg` parameter on `builds`'s selected constructor, and a generic `builds` class used as more
 * than a raw type. Either surfaces as an ordinary Kotlin compile error in the generated file rather
 * than a dedicated diagnostic here.
 */
internal fun parseFactory(
    objDecl: KSClassDeclaration,
    logger: KSPLogger,
    moduleCache: MutableMap<String, ModuleModel?>,
): FactoryModel? {
    var hasError = false
    fun reportError(message: String, node: KSNode) {
        logger.error(message, node)
        hasError = true
    }

    if (objDecl.classKind != ClassKind.OBJECT) {
        reportError("Only objects may be annotated with @DIFactory.", objDecl)
        return null
    }
    if (objDecl.getVisibility() != Visibility.INTERNAL) {
        reportError("A @DIFactory object must be internal.", objDecl)
        return null
    }

    val annotation = objDecl.annotations.first {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == DI_FACTORY_ANNOTATION
    }
    val suspendMode = readSuspendMode(annotation)
    val constructorIndex = readConstructorIndex(annotation)
    val buildsArg = annotation.arguments.find { it.name?.asString() == "builds" }
    val fromArg = annotation.arguments.find { it.name?.asString() == "from" }
    val buildsDecl = (buildsArg?.value as? KSType)?.declaration as? KSClassDeclaration
    val fromDecl = (fromArg?.value as? KSType)?.declaration as? KSClassDeclaration
    // `null` when `with` is `Unit` (the default) - no `with` parameter on the generated function,
    // and `arg<Argument>` functions take nothing, exactly as before this argument existed.
    val withType = readWithType(annotation)
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

    // Every declaration in the object must be a well-shaped `fun M.arg<Argument>(): T` - collect
    // the valid ones (paramName -> the function itself, so its `suspend`-ness can be read later)
    // and reject anything else.
    val argFunctions = mutableMapOf<String, KSFunctionDeclaration>()
    for (decl in objDecl.declarations) {
        // `declarations` includes the object's own synthetic no-arg constructor - not a real
        // member, skip it silently rather than rejecting it as an invalid one.
        if (decl is KSFunctionDeclaration && decl.simpleName.asString() == "<init>") continue

        val fn = decl as? KSFunctionDeclaration
        if (fn == null) {
            reportError(
                "A @DIFactory object may only declare 'fun $fromName.arg<Argument>(): T' functions - " +
                    "'${decl.simpleName.asString()}' is not one.",
                decl,
            )
            continue
        }
        val name = fn.simpleName.asString()
        if (!(name.startsWith("arg") && name.length > 3 && name[3].isUpperCase())) {
            reportError(
                "A @DIFactory object may only declare 'fun $fromName.arg<Argument>(): T' functions - " +
                    "'$name' is not one.",
                fn,
            )
            continue
        }
        val paramName = name.removePrefix("arg").replaceFirstChar(Char::lowercaseChar)
        val paramType = ctorParams[paramName]
        if (paramType == null) {
            reportError(
                "'$name' does not match any constructor argument of '$buildsName' - rename it to " +
                    "'arg<Argument>' matching one of: ${ctorParams.keys.sorted().joinToString(", ")}.",
                fn,
            )
            continue
        }
        if (fn.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            reportError("'$name' must be public or internal.", fn)
            continue
        }
        if (suspendMode == SuspendMode.FORBID && Modifier.SUSPEND in fn.modifiers) {
            reportError("'$name' may not be 'suspend': 'suspend' is 'Suspend.Forbid' on '${objDecl.simpleName.asString()}'.", fn)
            continue
        }
        if (withType == null) {
            if (fn.parameters.isNotEmpty()) {
                reportError("'$name' may not take any parameter.", fn)
                continue
            }
        } else {
            val singleParam = fn.parameters.singleOrNull()
            val paramMatchesWith = singleParam != null && !singleParam.isVararg &&
                sameType(singleParam.type.resolve(), withType)
            if (!paramMatchesWith) {
                reportError(
                    "'$name' must take exactly one parameter of type " +
                        "'${withDecl?.simpleName?.asString() ?: withType.declaration.simpleName.asString()}' " +
                        "(the @DIFactory 'with' type).",
                    fn,
                )
                continue
            }
        }
        val receiverType = fn.extensionReceiver?.resolve()
        if (receiverType == null || !receiverType.isAssignableFrom(fromType)) {
            reportError("'$name' must be an extension function on '$fromName'.", fn)
            continue
        }
        val returnType = fn.returnType?.resolve()
        if (returnType == null || !sameType(returnType, paramType)) {
            reportError(
                "'$name' must return the type of '$buildsName''s '$paramName' argument.",
                fn,
            )
            continue
        }
        argFunctions[paramName] = fn
    }

    // Every constructor argument must be uniquely resolvable: an arg function declared above, else
    // a unique exactly-typed module member, else a unique assignable-typed module member, else an
    // error. Under Suspend.Forbid, a suspend candidate is excluded here entirely (rather than
    // resolved and then rejected) - the same exclusion already applied to arg-functions above, so a
    // resolved FactoryArgumentModel can never end up suspend when the mode is Forbid.
    //
    // A member takes no parameter (called plainly), or - only when `with` is not `Unit` - takes one
    // parameter the `with` value can be passed to (see [withArgumentFor]). The two live in one pool:
    // a zero-parameter and a one-parameter member matching the same argument type is an ambiguity,
    // exactly as two zero-parameter members are.
    val candidates: List<Pair<MemberModel, WithArgument?>> = fromModel.allMembers.mapNotNull { member ->
        if (suspendMode == SuspendMode.FORBID && member.isSuspend) return@mapNotNull null
        when {
            member.parameters.isEmpty() -> member to null
            withType != null && member.parameters.size == 1 ->
                withArgumentFor(member.parameters.single(), withType)?.let { member to it }
            else -> null
        }
    }
    val arguments = mutableListOf<FactoryArgumentModel>()
    for ((paramName, paramType) in ctorParams) {
        val resolvedArgFunction = argFunctions[paramName]
        if (resolvedArgFunction != null) {
            arguments += FactoryArgumentModel(
                paramName,
                ArgumentSource.FactoryFunction(resolvedArgFunction.simpleName.asString()),
                isSuspend = Modifier.SUSPEND in resolvedArgFunction.modifiers,
            )
            continue
        }

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

        reportError(
            "Cannot resolve argument '$paramName' of '$buildsName' from '$fromName' - add a " +
                "'fun $fromName.${argFunctionName(paramName)}(): T' function to '${objDecl.simpleName.asString()}'.",
            objDecl,
        )
    }

    if (hasError) return null

    val originatingFiles: List<KSFile> = listOfNotNull(
        objDecl.containingFile,
        buildsDecl.containingFile,
        fromDecl.containingFile,
        withDecl?.containingFile,
    ) + fromModel.ancestorDeclarations.mapNotNull { it.containingFile }

    val isSuspend = when (suspendMode) {
        SuspendMode.FORBID -> false
        SuspendMode.ALLOW -> arguments.any { it.isSuspend }
        SuspendMode.FORCE -> true
    }

    // The generated function calls `ctor` directly and takes `from` (as `module`) and `with` as
    // parameters, so it can only be as visible as the most restrictive of `builds`, the selected
    // constructor, `from`, and `with` - a public class with an internal constructor (only reachable
    // once a non-primary constructor can be selected), an internal `from` module, or an internal
    // `with` type, still needs an internal generated function, or the signature wouldn't compile.
    val visibility =
        if (Visibility.INTERNAL in setOfNotNull(buildsDecl.getVisibility(), ctor.getVisibility(), fromDecl.getVisibility(), withDecl?.getVisibility()))
            Visibility.INTERNAL
        else Visibility.PUBLIC

    return FactoryModel(
        factoryClassName = objDecl.toClassName(),
        generatedBaseName = objDecl.generatedBaseName(),
        buildsClassName = buildsDecl.toClassName(),
        fromClassName = fromDecl.toClassName(),
        withTypeName = withType?.toTypeName(),
        visibility = visibility,
        arguments = arguments,
        isSuspend = isSuspend,
        originatingFiles = originatingFiles,
    )
}

private fun MemberModel.toFactoryArgumentModel(paramName: String, withArgument: WithArgument?): FactoryArgumentModel {
    val source = if (isFunction) ArgumentSource.ModuleFunction(name, withArgument) else ArgumentSource.ModuleProperty(name)
    return FactoryArgumentModel(paramName, source, isSuspend = isSuspend)
}
