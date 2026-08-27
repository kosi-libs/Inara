package org.kodein.di.inara.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Where a `@DIFactory`-generated constructor argument's value comes from: an `arg<Argument>`
 * extension function declared on the `@DIFactory` object itself, or a member of the `from` module
 * resolved automatically (see [parseFactory]).
 */
internal sealed class ArgumentSource {
    /** `module.<functionName>()` - an `arg<Argument>` function on the `@DIFactory` object. */
    internal data class FactoryFunction(val functionName: String) : ArgumentSource()

    /** `module.<memberName>` - a `val` (or no-arg `fun`, see [ModuleFunction]) on `from`. */
    internal data class ModuleProperty(val memberName: String) : ArgumentSource()

    /** `module.<memberName>()` - a no-argument `fun` on `from`. */
    internal data class ModuleFunction(val memberName: String) : ArgumentSource()
}

/** One argument of `builds`'s primary constructor, resolved to where its value comes from. */
internal data class FactoryArgumentModel(
    val paramName: String,
    val source: ArgumentSource,
)

/**
 * A `@DIFactory` object, parsed and every one of `builds`'s primary constructor arguments resolved.
 *
 * [visibility] is `builds`'s own visibility, not the `@DIFactory` object's (which is always
 * `internal`, see [parseFactory]) - it drives whether the generated builder function is `internal`.
 */
internal data class FactoryModel(
    val factoryClassName: ClassName,
    val buildsClassName: ClassName,
    val fromClassName: ClassName,
    val visibility: Visibility,
    val arguments: List<FactoryArgumentModel>,
    val originatingFiles: List<KSFile>,
)

/** The simple name of the `arg<Argument>` function that would resolve a constructor parameter
 * named [paramName], e.g. `argEndpoint` for `endpoint`. */
private fun argFunctionName(paramName: String): String = "arg${paramName.replaceFirstChar(Char::uppercaseChar)}"

/** True when [a] and [b] denote the same type - `KSType` has no reliable structural `equals`, so
 * mutual assignability is the robust way to ask this using only the documented API. */
private fun sameType(a: KSType, b: KSType): Boolean = a.isAssignableFrom(b) && b.isAssignableFrom(a)

/**
 * Validates [objDecl] against every `@DIFactory` rule, resolves every one of `builds`'s primary
 * constructor arguments, and builds a [FactoryModel] out of it.
 *
 * Every violation found is reported through [logger] before returning - so a single compilation
 * surfaces every problem at once, rather than one error per run. Returns `null` if the declaration
 * is invalid.
 *
 * Two shapes are explicitly unsupported and neither validated against nor specially handled: a
 * `vararg` parameter on `builds`'s primary constructor, and a generic `builds` class used as more
 * than a raw type. Either surfaces as an ordinary Kotlin compile error in the generated file rather
 * than a dedicated diagnostic here.
 */
internal fun parseFactory(objDecl: KSClassDeclaration, logger: KSPLogger): FactoryModel? {
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
    val buildsArg = annotation.arguments.find { it.name?.asString() == "builds" }
    val fromArg = annotation.arguments.find { it.name?.asString() == "from" }
    val buildsDecl = (buildsArg?.value as? KSType)?.declaration as? KSClassDeclaration
    val fromDecl = (fromArg?.value as? KSType)?.declaration as? KSClassDeclaration

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
    val ctor = buildsDecl.primaryConstructor
    if (ctor == null || ctor.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
        reportError("The 'builds' class '$buildsName' must have a public or internal primary constructor.", objDecl)
        return null
    }

    if (!fromDecl.isDIModule()) {
        reportError(
            "The 'from' argument must be a @DIModule interface: '${fromDecl.simpleName.asString()}' is not.",
            objDecl,
        )
        return null
    }
    val fromModel = parseModule(fromDecl, logger) ?: return null

    val fromName = fromDecl.simpleName.asString()
    val fromType = fromDecl.asStarProjectedType()
    val ctorParams = ctor.parameters.associate { p -> p.name!!.asString() to p.type.resolve() }

    // Every declaration in the object must be a well-shaped `fun M.arg<Argument>(): T` - collect
    // the valid ones (paramName -> function name) and reject anything else.
    val argFunctions = mutableMapOf<String, String>()
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
        if (Modifier.SUSPEND in fn.modifiers) {
            reportError("'$name' may not be 'suspend'.", fn)
            continue
        }
        if (fn.parameters.isNotEmpty()) {
            reportError("'$name' may not take any parameter.", fn)
            continue
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
        argFunctions[paramName] = name
    }

    // Every constructor argument must be uniquely resolvable: an arg function declared above, else
    // a unique exactly-typed module member, else a unique assignable-typed module member, else an
    // error.
    val candidates = fromModel.allMembers.filter { it.parameters.isEmpty() && !it.isSuspend }
    val arguments = mutableListOf<FactoryArgumentModel>()
    for ((paramName, paramType) in ctorParams) {
        val resolvedArgFunctionName = argFunctions[paramName]
        if (resolvedArgFunctionName != null) {
            arguments += FactoryArgumentModel(paramName, ArgumentSource.FactoryFunction(resolvedArgFunctionName))
            continue
        }

        val exact = candidates.filter { sameType(it.returnType, paramType) }
        if (exact.size == 1) {
            arguments += FactoryArgumentModel(paramName, exact.single().toArgumentSource())
            continue
        }
        if (exact.size > 1) {
            reportError(
                "Argument '$paramName' of '$buildsName' is ambiguous: matched by " +
                    exact.map { it.name }.sorted().joinToString(", ") { "'$it'" } + ", all exactly of that type.",
                objDecl,
            )
            continue
        }

        val assignable = candidates.filter { paramType.isAssignableFrom(it.returnType) }
        if (assignable.size == 1) {
            arguments += FactoryArgumentModel(paramName, assignable.single().toArgumentSource())
            continue
        }
        if (assignable.size > 1) {
            reportError(
                "Argument '$paramName' of '$buildsName' is ambiguous: matched by " +
                    assignable.map { it.name }.sorted().joinToString(", ") { "'$it'" } + ", all assignable to that type.",
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
    ) + fromModel.ancestorDeclarations.mapNotNull { it.containingFile }

    return FactoryModel(
        factoryClassName = objDecl.toClassName(),
        buildsClassName = buildsDecl.toClassName(),
        fromClassName = fromDecl.toClassName(),
        visibility = buildsDecl.getVisibility(),
        arguments = arguments,
        originatingFiles = originatingFiles,
    )
}

private fun MemberModel.toArgumentSource(): ArgumentSource =
    if (isFunction) ArgumentSource.ModuleFunction(name) else ArgumentSource.ModuleProperty(name)
