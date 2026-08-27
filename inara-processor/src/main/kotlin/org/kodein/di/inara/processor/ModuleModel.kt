package org.kodein.di.inara.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

internal const val DI_MODULE_ANNOTATION = "org.kodein.di.inara.DIModule"
internal const val COMPOSED_DI_MODULE_ANNOTATION = "org.kodein.di.inara.ComposedDIModule"
internal const val DI_FACTORY_ANNOTATION = "org.kodein.di.inara.DIFactory"

/**
 * One parameter of a function member. [type] is the *element* type for a vararg parameter (as
 * declared) - not the array type seen inside the function body - the generator derives the array
 * type itself, since KSP doesn't expose it directly.
 */
internal data class ParameterModel(
    val name: String,
    val type: KSType,
    val isVararg: Boolean,
)

/**
 * A single member of a `@DIModule` interface that will be backed by a `Binding`.
 *
 * [parameters] is empty for a `val` property or a no-argument function - the corresponding
 * `Binding`'s argument type is then `Unit`. A single non-vararg parameter's own type is used
 * directly as the argument type; a single vararg parameter's argument type is the array type seen
 * inside the function body (`Array<out T>`, or a dedicated primitive array like `IntArray`). Two or
 * more parameters (vararg included) are wrapped into a generated `<Module>BindingArguments.<Name>`
 * data class instead - see `ModuleFileGenerator`.
 *
 * [hasDefaultImplementation] is true when the member carries a body in the interface (a default
 * property getter or function body). Its `Binding` parameter is then optional and nullable:
 * `null` means the interface's own default implementation is kept, any other value overrides it.
 */
internal data class MemberModel(
    val name: String,
    val isFunction: Boolean,
    val isSuspend: Boolean,
    val parameters: List<ParameterModel>,
    val returnType: KSType,
    val hasDefaultImplementation: Boolean,
)

/**
 * A direct `@DIModule` supertype of a module. [paramName] is the name of the constructor/builder
 * parameter that accepts an already-built instance of it, e.g. `module1` for `Module1`.
 */
internal data class ParentModel(
    val declaration: KSClassDeclaration,
    val moduleClassName: ClassName,
    val paramName: String,
)

/**
 * [ownMembers] are the members declared directly on this interface plus every member reachable
 * through a *plain* (non-`@DIModule`) supertype, transitively - nothing delegates those, so they
 * need their own binding exactly like an own declaration. [allMembers] additionally folds in every
 * `@DIModule` [parents]' member closure, with no two members sharing a name (see [parseModule]). A
 * module with no `@DIModule` parents has `allMembers == ownMembers`.
 *
 * [ancestorDeclarations] is every interface transitively reachable through any direct supertype -
 * `@DIModule` or plain alike - (deduplicated) - used only so the generator can register each of
 * their source files as an incremental-build dependency of the generated code.
 *
 * [isComposed] is true when the interface is annotated `@ComposedDIModule` - it alone decides
 * whether the generator emits the delegated implementation/builder/`copy` set on top of the plain
 * one; [parents] is populated regardless of its value.
 */
internal data class ModuleModel(
    val declaration: KSClassDeclaration,
    val moduleClassName: ClassName,
    val simpleName: String,
    val generatedBaseName: String,
    val visibility: Visibility,
    val ownMembers: List<MemberModel>,
    val allMembers: List<MemberModel>,
    val parents: List<ParentModel>,
    val ancestorDeclarations: List<KSClassDeclaration>,
    val isComposed: Boolean,
)

/**
 * The interface's simple name prefixed with every enclosing class/interface/object's simple name,
 * e.g. `OuterInner` for `Outer.Inner`. Used as the base for the generated file/class name so two
 * same-named nested interfaces in different enclosing scopes can't collide, and as the base for a
 * `@DIModule` parent's constructor parameter name (decapitalized).
 */
private fun KSClassDeclaration.generatedBaseName(): String {
    val names = mutableListOf(simpleName.asString())
    var parent = parentDeclaration
    while (parent is KSClassDeclaration) {
        names.add(0, parent.simpleName.asString())
        parent = parent.parentDeclaration
    }
    return names.joinToString("")
}

private fun KSClassDeclaration.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }

/** True for `@DIModule` *or* `@ComposedDIModule` - the latter implies the former. Also used by
 * `DIFactoryModel.kt` to validate a `@DIFactory`'s `from` argument. */
internal fun KSClassDeclaration.isDIModule(): Boolean =
    hasAnnotation(DI_MODULE_ANNOTATION) || hasAnnotation(COMPOSED_DI_MODULE_ANNOTATION)

/** True only for `@ComposedDIModule` - drives whether the delegated variant is generated. */
private fun KSClassDeclaration.isComposedDIModule(): Boolean =
    hasAnnotation(COMPOSED_DI_MODULE_ANNOTATION)

/**
 * True for a `var` whose getter *and* setter both carry a body. Inara's `Binding` has no way to
 * express a setter at all, so a `var` can never be bound regardless of default status - unlike the
 * other liftable rules (see [parseMember]), it needs *both* accessors defaulted to be liftable: a
 * `var` with only one accessor implemented still leaves the other abstract with no `Binding`-based
 * way to satisfy it, so it stays rejected.
 */
private fun KSPropertyDeclaration.hasFullyDefaultAccessors(): Boolean {
    val getterHasBody = getter?.let { Modifier.ABSTRACT !in it.modifiers } ?: false
    val setterHasBody = setter?.let { Modifier.ABSTRACT !in it.modifiers } ?: false
    return getterHasBody && setterHasBody
}

/** Direct interface supertypes, `@DIModule` or not, with `kotlin.Any` excluded. */
private fun KSClassDeclaration.superInterfaces(): List<KSClassDeclaration> =
    superTypes.mapNotNull { superType ->
        val resolved = superType.resolve().declaration
        (resolved as? KSClassDeclaration)?.takeUnless { it.qualifiedName?.asString() == "kotlin.Any" }
    }.toList()

/**
 * [classDecl] plus every interface transitively reachable through its supertypes - `@DIModule` or
 * not, annotation doesn't matter here - keyed by qualified name. Used both to find the most
 * derived owner of a member reached through more than one path (see [resolveClosure]) and to
 * detect a shared ancestor pulled in by two different `@ComposedDIModule` delegate parents.
 */
private fun interfaceClosure(classDecl: KSClassDeclaration): Map<String, KSClassDeclaration> {
    val result = LinkedHashMap<String, KSClassDeclaration>()
    result[classDecl.qualifiedName!!.asString()] = classDecl
    for (supertype in classDecl.superInterfaces()) {
        for ((qualifiedName, decl) in interfaceClosure(supertype)) {
            result.putIfAbsent(qualifiedName, decl)
        }
    }
    return result
}

/** True when [sub] is [sup], or transitively extends it. */
private fun isSubtypeOf(sub: KSClassDeclaration, sup: KSClassDeclaration): Boolean =
    sup.qualifiedName?.asString() in interfaceClosure(sub).keys

/** A property or function declaration paired with the interface that actually declares it. */
private data class ClosureDecl(val decl: KSDeclaration, val owner: KSClassDeclaration)

/**
 * Every property/function transitively reachable from [classDecl]'s supertypes, `@DIModule` or
 * plain alike (annotation doesn't matter once inside the walk - only [parseModule]'s direct-parent
 * partitioning cares), each paired with its declaring interface. A private member is dropped here:
 * an interface's private member always carries its own body, so the generated class never has
 * anything to implement for it. Raw: the same declaration can appear more than once (diamond
 * inheritance, or an override alongside the member it overrides) - see [resolveClosure].
 */
private fun superDeclarationClosure(classDecl: KSClassDeclaration): List<ClosureDecl> {
    val result = mutableListOf<ClosureDecl>()
    for (supertype in classDecl.superInterfaces()) {
        result += superDeclarationClosure(supertype)
    }
    for (decl in classDecl.declarations) {
        val isMember = decl is KSPropertyDeclaration || decl is KSFunctionDeclaration
        if (isMember && decl.getVisibility() != Visibility.PRIVATE) {
            result += ClosureDecl(decl, classDecl)
        }
    }
    return result
}

/**
 * One [ClosureDecl] reached through one of [ModuleModel.parents] or a plain supertype, tagged with
 * [node] - the direct supertype reference on the interface being parsed, used to anchor a
 * diagnostic on the user's own code - and [ownFromPlain] - whether it was reached through a plain
 * supertype (needs its own binding, [ModuleModel.ownMembers] included) rather than a `@DIModule`
 * parent (satisfied by that parent's delegate in the `@ComposedDIModule` variant, [ModuleModel.allMembers]
 * only).
 */
private data class Candidate(val closureDecl: ClosureDecl, val node: KSTypeReference, val ownFromPlain: Boolean)

/**
 * Resolves [candidates] into at most one [Candidate] per member name, reporting every genuine
 * clash through [reportError] (anchored on [classDecl]).
 *
 * Two entries with the same name collapse silently when one's owner is the other's (the exact
 * same declaration reached twice, e.g. diamond inheritance) or a subtype of the other's (an
 * `override` in a more derived interface shadowing what it overrides, however many levels apart) -
 * the most derived entry survives, preferring one reached through a plain supertype over one
 * reached through a `@DIModule` parent when both reach the same winning owner. Anything else - two
 * unrelated owners, or two distinct declarations on the very same owner (an overload) - is
 * rejected.
 */
private fun resolveClosure(
    candidates: List<Candidate>,
    classDecl: KSClassDeclaration,
    reportError: (String, KSNode) -> Unit,
): List<Candidate> {
    val result = mutableListOf<Candidate>()
    for ((name, entries) in candidates.groupBy { it.closureDecl.decl.simpleName.asString() }) {
        val mostDerivedOwners = entries
            .map { it.closureDecl.owner }
            .distinctBy { it.qualifiedName?.asString() }
            .filter { candidate -> entries.all { other -> isSubtypeOf(candidate, other.closureDecl.owner) } }

        if (mostDerivedOwners.size != 1) {
            val ownerNames = entries.map { it.closureDecl.owner.simpleName.asString() }.distinct().sorted()
            reportError(
                "A member named '$name' is declared in more than one unrelated interface: " +
                    ownerNames.joinToString(", ") { "'$it'" } + ".",
                classDecl,
            )
            continue
        }

        val winningOwner = mostDerivedOwners.single().qualifiedName?.asString()
        val winners = entries.filter { it.closureDecl.owner.qualifiedName?.asString() == winningOwner }
        val distinctDecls = winners.map { it.closureDecl.decl }.distinct()
        if (distinctDecls.size > 1) {
            reportError(
                "Overloading is not supported: multiple members are named '$name' in " +
                    "'${winners.first().closureDecl.owner.simpleName.asString()}'.",
                classDecl,
            )
            continue
        }

        result += winners.firstOrNull { it.ownFromPlain } ?: winners.first()
    }
    return result
}

/**
 * [decl]'s type as seen from [moduleType] (substituting any type argument a plain supertype
 * declared it with), via `KSPropertyDeclaration`/`KSFunctionDeclaration.asMemberOf`. Falls back to
 * the type as declared, unsubstituted, if resolution yields an error type - degrading gracefully
 * instead of crashing on an otherwise broken supertype.
 */
private fun buildMember(decl: KSDeclaration, moduleType: KSType, hasDefault: Boolean): MemberModel =
    when (decl) {
        is KSPropertyDeclaration -> {
            val resolved = decl.asMemberOf(moduleType)
            MemberModel(
                name = decl.simpleName.asString(),
                isFunction = false,
                isSuspend = false,
                parameters = emptyList(),
                returnType = resolved.takeUnless { it.isError } ?: decl.type.resolve(),
                hasDefaultImplementation = hasDefault,
            )
        }
        is KSFunctionDeclaration -> {
            // KSFunction's return/parameter types are nullable - null (like an error type) means
            // resolution failed for that part specifically, so each falls back independently.
            val signature = decl.asMemberOf(moduleType)
            val parameters = decl.parameters.mapIndexed { index, p ->
                val substituted = signature.parameterTypes.getOrNull(index)?.takeUnless { it.isError }
                ParameterModel(
                    name = p.name?.asString() ?: "arg$index",
                    type = substituted ?: p.type.resolve(),
                    isVararg = p.isVararg,
                )
            }
            MemberModel(
                name = decl.simpleName.asString(),
                isFunction = true,
                isSuspend = Modifier.SUSPEND in decl.modifiers,
                parameters = parameters,
                returnType = signature.returnType?.takeUnless { it.isError } ?: decl.returnType!!.resolve(),
                hasDefaultImplementation = hasDefault,
            )
        }
        else -> error("Unsupported declaration kind for a @DIModule member: $decl")
    }

/** The simple name of the nested `data class` a multi-parameter member's arguments are wrapped
 * into, e.g. `Mult` for a member named `mult`. Meaningful only when `parameters.size > 1`. */
internal fun MemberModel.argsClassSimpleName(): String = name.replaceFirstChar(Char::uppercaseChar)

/**
 * Validates [classDecl] against every `@DIModule` rule and builds a [ModuleModel] out of it.
 *
 * Every violation found is reported through [logger] before returning - so a single compilation
 * surfaces every problem in the interface at once, rather than one error per run. Returns `null`
 * if the interface is invalid.
 */
internal fun parseModule(classDecl: KSClassDeclaration, logger: KSPLogger): ModuleModel? {
    var hasError = false
    fun reportError(message: String, node: KSNode) {
        logger.error(message, node)
        hasError = true
    }

    if (classDecl.classKind != ClassKind.INTERFACE) {
        reportError("Only interfaces may be annotated with @DIModule / @ComposedDIModule.", classDecl)
        return null
    }

    val isComposed = classDecl.isComposedDIModule()
    val moduleType = classDecl.asStarProjectedType()

    if (classDecl.typeParameters.isNotEmpty()) {
        reportError("A @DIModule interface may not have type parameters.", classDecl)
    }

    // Resolve direct supertypes into @DIModule parents (delegable - eligible for a
    // @ComposedDIModule "Parent by parent" clause) and plain supertypes (their members are bound
    // directly, like this interface's own). A supertype that doesn't resolve to an interface at
    // all (an unresolved/error reference) makes the rest of this function unsafe to run, so bail
    // out immediately after reporting every such supertype.
    var supertypesValid = true
    val parentEntries = mutableListOf<Pair<KSTypeReference, KSClassDeclaration>>()
    val plainEntries = mutableListOf<Pair<KSTypeReference, KSClassDeclaration>>()
    for (superType in classDecl.superTypes) {
        val resolved = superType.resolve().declaration
        if (resolved.qualifiedName?.asString() == "kotlin.Any") continue
        val superDecl = resolved as? KSClassDeclaration
        if (superDecl == null) {
            reportError("A @DIModule interface may only extend interfaces.", superType)
            supertypesValid = false
            continue
        }
        if (superDecl.isDIModule()) parentEntries += superType to superDecl else plainEntries += superType to superDecl
    }
    if (!supertypesValid) return null

    val parentDecls = parentEntries.map { it.second }

    if (isComposed && parentDecls.isEmpty()) {
        reportError(
            "@ComposedDIModule requires the interface to extend at least one @DIModule interface.",
            classDecl,
        )
    }

    // Diamond inheritance: two direct @DIModule parents that both reach the same ancestor
    // interface (annotated or not - a plain shared ancestor is just as ambiguous once delegated
    // twice). Kotlin would need an explicit override for that ancestor's members in the delegated
    // implementation, which the generator does not attempt, so this is rejected for a
    // @ComposedDIModule interface outright. A plain @DIModule interface has no delegated
    // implementation to trip over this, so a shared ancestor's members are simply folded in once
    // (see [resolveClosure]).
    if (isComposed && parentDecls.size > 1) {
        val closures = parentDecls.map { it to interfaceClosure(it) }
        for (i in closures.indices) {
            for (j in i + 1 until closures.size) {
                val (parentA, closureA) = closures[i]
                val (parentB, closureB) = closures[j]
                for (qualifiedName in closureA.keys intersect closureB.keys) {
                    val shared = closureA.getValue(qualifiedName)
                    reportError(
                        "Interface '${shared.simpleName.asString()}' is inherited more than once, " +
                            "via '${parentA.simpleName.asString()}' and '${parentB.simpleName.asString()}'.",
                        classDecl,
                    )
                }
            }
        }
    }

    val nameOwners = mutableMapOf<String, KSClassDeclaration>()
    val parentModels = mutableListOf<ParentModel>()

    for (parent in parentDecls) {
        val paramName = parent.generatedBaseName().replaceFirstChar(Char::lowercaseChar)
        if (isComposed) {
            parentModels.find { it.paramName == paramName }?.let { clashing ->
                reportError(
                    "Parent modules '${clashing.declaration.simpleName.asString()}' and " +
                        "'${parent.simpleName.asString()}' would both bind to the parameter name '$paramName'.",
                    classDecl,
                )
            }
        }
        parentModels += ParentModel(parent, parent.toClassName(), paramName)
    }

    /**
     * Validates [decl] (declared on [owner]) against every `@DIModule` member rule and, if it
     * passes, resolves its type as seen from [classDecl] (see [buildMember]). [isOwn] controls the
     * diagnostic wording: an inherited member's is prefixed with which interface declares it, so
     * the caret on [node] - the offending direct supertype reference for an inherited member, the
     * declaration itself for an own one - doesn't leave the reader guessing where the member
     * actually came from.
     *
     * A `var` (with both accessors defaulted, see [hasFullyDefaultAccessors]), an extension member,
     * or a generic function is rejected only when it *lacks* a default implementation - `Binding`
     * can't express any of the three regardless of default status (no setter, no extension
     * receiver, no per-call type parameter), so a defaulted one is instead silently excluded from
     * the module's bindable surface entirely: no `Binding` parameter, no override, `null` returned
     * without an error. Once a member is excluded this way, the *structural* rule below it
     * (visibility) no longer applies either - Inara never touches the member, so it's irrelevant to
     * it. The three naming-identity rules above stay unconditional regardless: a `Binding`-suffixed,
     * `copy`-named, or `arg<Argument>`-shaped member could still collide with the generated class's
     * own naming scheme, or with a `@DIFactory` object's expected extension-function convention,
     * even while unbound.
     *
     * A function's parameter count and any vararg parameter are unrestricted - see [ParameterModel]
     * and `ModuleFileGenerator` for how more than one parameter is wrapped into a generated data
     * class.
     */
    fun parseMember(decl: KSDeclaration, owner: KSClassDeclaration, node: KSNode, isOwn: Boolean): MemberModel? {
        val name = decl.simpleName.asString()
        val prefix = if (isOwn) "" else "Member '$name' inherited from '${owner.simpleName.asString()}': "
        var valid = true
        var skip = false
        fun fail(message: String) {
            reportError(prefix + message, node)
            valid = false
        }

        if (name.endsWith("Binding")) fail("A @DIModule member name may not end with 'Binding': '$name'.")
        if (name == "copy") fail("A @DIModule member may not be named 'copy'.")
        // A member shaped like `argFoo` would silently shadow a same-named @DIFactory extension
        // function (`fun M.argFoo(): T`) - Kotlin always prefers a member over an extension, so the
        // factory's function would never actually be called, with no error to say so.
        if (name.startsWith("arg") && name.length > 3 && name[3].isUpperCase()) {
            fail("A @DIModule member name may not start with 'arg' followed by an uppercase letter: '$name'.")
        }

        val hasDefault: Boolean
        when (decl) {
            is KSPropertyDeclaration -> {
                hasDefault = !decl.isAbstract()
                when {
                    !decl.isMutable -> Unit
                    decl.hasFullyDefaultAccessors() -> skip = true
                    else -> fail("var properties are not supported: '$name'.")
                }
                if (!skip && decl.extensionReceiver != null) {
                    if (hasDefault) skip = true else fail("Extension members are not supported: '$name'.")
                }
                if (!skip && decl.getVisibility() != Visibility.PUBLIC) {
                    fail("Non-public members are not supported: '$name'.")
                }
            }
            is KSFunctionDeclaration -> {
                hasDefault = !decl.isAbstract
                if (decl.extensionReceiver != null) {
                    if (hasDefault) skip = true else fail("Extension members are not supported: '$name'.")
                }
                if (!skip && decl.typeParameters.isNotEmpty()) {
                    if (hasDefault) skip = true else fail("Generic members are not supported: '$name'.")
                }
                if (!skip && decl.getVisibility() != Visibility.PUBLIC) {
                    fail("Non-public members are not supported: '$name'.")
                }
            }
            else -> return null
        }

        if (!valid) return null
        if (skip) return null
        return buildMember(decl, moduleType, hasDefault)
    }

    val allMembers = mutableListOf<MemberModel>()
    val ownMembers = mutableListOf<MemberModel>()

    // Every member reachable through a @DIModule parent (allMembers only - satisfied by that
    // parent's delegate in the @ComposedDIModule variant) or a plain supertype (both - nothing
    // delegates it, so it needs its own binding just like an own declaration), resolved together
    // so an override spanning both groups, or a shared ancestor reached through either, collapses
    // to one candidate instead of a false clash.
    val candidates = parentEntries.flatMap { (node, parent) ->
        superDeclarationClosure(parent).map { Candidate(it, node, ownFromPlain = false) }
    } + plainEntries.flatMap { (node, plain) ->
        superDeclarationClosure(plain).map { Candidate(it, node, ownFromPlain = true) }
    }

    for (candidate in resolveClosure(candidates, classDecl, ::reportError)) {
        val (closureDecl, node, ownFromPlain) = candidate
        val member = parseMember(closureDecl.decl, closureDecl.owner, node, isOwn = false) ?: continue
        nameOwners[closureDecl.decl.simpleName.asString()] = closureDecl.owner
        allMembers += member
        if (ownFromPlain) ownMembers += member
    }

    fun claimName(name: String, node: KSNode): Boolean {
        val existingOwner = nameOwners[name]
        val ok = when (existingOwner) {
            null -> true
            classDecl -> {
                reportError("Overloading is not supported: multiple members are named '$name'.", node)
                false
            }
            else -> {
                reportError("A member named '$name' is already declared in '${existingOwner.simpleName.asString()}'.", node)
                false
            }
        }
        nameOwners[name] = classDecl
        return ok
    }

    for (decl in classDecl.declarations) {
        if (decl !is KSPropertyDeclaration && decl !is KSFunctionDeclaration) continue
        val claimOk = claimName(decl.simpleName.asString(), decl)
        val member = parseMember(decl, classDecl, decl, isOwn = true)
        if (claimOk && member != null) {
            ownMembers += member
            allMembers += member
        }
    }

    // A @DIModule parent's parameter name may not collide with any member name in the
    // consolidated interface (checked last, since own member names are only known by now). Only
    // relevant to a @ComposedDIModule interface - a plain @DIModule interface has no such parameter.
    if (isComposed) {
        for (parentModel in parentModels) {
            if (parentModel.paramName in nameOwners) {
                reportError(
                    "Parent module '${parentModel.declaration.simpleName.asString()}' binds to the parameter name " +
                        "'${parentModel.paramName}', which collides with a member name.",
                    classDecl,
                )
            }
        }
    }

    // Two multi-parameter members whose names capitalize to the same arguments-class name would
    // generate the same nested data class twice.
    for ((capitalized, group) in allMembers.filter { it.parameters.size > 1 }.groupBy { it.argsClassSimpleName() }) {
        if (group.size > 1) {
            reportError(
                "Members " + group.map { it.name }.distinct().sorted().joinToString(", ") { "'$it'" } +
                    " would all generate a '$capitalized' arguments class - rename one of them.",
                classDecl,
            )
        }
    }

    if (hasError) return null

    val ancestorDeclarations = LinkedHashMap<String, KSClassDeclaration>().apply {
        for (supertype in parentDecls + plainEntries.map { it.second }) {
            for ((qualifiedName, decl) in interfaceClosure(supertype)) {
                putIfAbsent(qualifiedName, decl)
            }
        }
    }.values.toList()

    return ModuleModel(
        declaration = classDecl,
        moduleClassName = classDecl.toClassName(),
        simpleName = classDecl.simpleName.asString(),
        generatedBaseName = classDecl.generatedBaseName(),
        visibility = classDecl.getVisibility(),
        ownMembers = ownMembers,
        allMembers = allMembers,
        parents = parentModels,
        ancestorDeclarations = ancestorDeclarations,
        isComposed = isComposed,
    )
}
