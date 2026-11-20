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
import com.squareup.kotlinpoet.ksp.toTypeName

internal const val DI_MODULE_ANNOTATION = "org.kodein.di.inara.DIModule"
/** The type annotation marking a direct `@DIModule` supertype as required rather than provided. */
internal const val DI_MODULE_REQUIRED_ANNOTATION = "org.kodein.di.inara.DIModule.Required"
internal const val DI_FACTORY_ANNOTATION = "org.kodein.di.inara.DIFactory"
/** JVM synthetic container the compiler wraps repeated `@DIFactory` annotations in - KSP may surface
 * this instead of the individual entries, so [parseFactories] unwraps it. */
internal const val DI_FACTORY_CONTAINER_ANNOTATION = "org.kodein.di.inara.DIFactory.Container"

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
 *
 * [sets] are the qualified names of the set annotations (see `@DISet`) the member carries, directly
 * or on a declaration it overrides - the sets a `setOf<Annotation>()` function collects it into.
 *
 * [argsClassName] is, for a member with two or more parameters only, the generated data class its
 * arguments are wrapped into: `<Owner>BindingArguments.<Name>`, where the owner is the `@DIModule`
 * that introduces the member - the module itself for an own or plain-supertype member, its source
 * parent's owner for one pulled from a `@DIModule` parent (see [parseModule]). Every module reaching
 * the member therefore shares one arguments class, so a parent's (or a `Part`'s) binding fits a
 * child's slot as it is.
 */
internal data class MemberModel(
    val name: String,
    val isFunction: Boolean,
    val isSuspend: Boolean,
    val parameters: List<ParameterModel>,
    val returnType: KSType,
    val hasDefaultImplementation: Boolean,
    val sets: List<String> = emptyList(),
    val argsClassName: ClassName? = null,
)

/**
 * A direct `@DIModule` supertype of a module. [paramName] is the name of the constructor/builder
 * parameter that accepts an already-built instance of it, e.g. `module1` for `Module1`.
 *
 * [generatedClassName] is the parent's own `Inara<Parent>` implementation class. It is `public`
 * (behind `@InternalInaraGeneration`), so a `@DIModule(composed = true)` builder always reads each
 * inherited member's binding straight off it - whichever compilation the parent came from - falling
 * back to a `BindingImpls.Delegate` only for a hand-written parent.
 *
 * [isRequired] is true when the module marks this supertype `@DIModule.Required`. [partClassName]
 * is the parent's `<Parent>Part` class when the parent is itself partial (see
 * [ModuleModel.isPartial]): a builder then takes that `Part` in place of a parent instance.
 * [model] is the parent's own parsed model (`null` only if it failed to parse), read to share its
 * arguments classes and to generate the reads off its `Part`.
 */
internal class ParentModel(
    val declaration: KSClassDeclaration,
    val moduleClassName: ClassName,
    val generatedClassName: ClassName,
    val paramName: String,
    val isRequired: Boolean,
    val partClassName: ClassName?,
    val model: ModuleModel?,
) {
    /** The type a builder or copy function takes for this parent: its `Part`, or the module itself. */
    val parameterClassName: ClassName get() = partClassName ?: moduleClassName
}

/**
 * [ownMembers] are the members declared directly on this interface plus every member reachable
 * through a *plain* (non-`@DIModule`) supertype, transitively - nothing delegates those, so they
 * need their own binding exactly like an own declaration. [allMembers] additionally folds in every
 * `@DIModule` [parents]' member closure, with no two members sharing a name (see [parseModule]). A
 * module with no `@DIModule` parents has `allMembers == ownMembers`.
 *
 * [parentByMemberName] maps each [allMembers] member reached *only* through a `@DIModule` parent to
 * that direct parent - the generator reads the member's binding off the parent instance in the
 * `@DIModule(composed = true)` builder and parent-swapping `copy<Module>`. Own members and plain-supertype
 * members are absent; the diamond rule (see [parseModule]) guarantees at most one parent per name.
 *
 * [ancestorDeclarations] is every interface transitively reachable through any direct supertype -
 * `@DIModule` or plain alike - (deduplicated) - used only so the generator can register each of
 * their source files as an incremental-build dependency of the generated code.
 *
 * [isComposed] is true when the interface is annotated `@DIModule(composed = true)` - it alone decides
 * whether the generator emits the extra parent-instance builder/`copy<Module>` overloads on top of the
 * plain full ones; [parents] is populated regardless of its value.
 *
 * [sets] holds one entry per set the module generates a `setOf<Annotation>()` function for: every
 * one listed in its own `@DIModule(sets = ...)` or in any `@DIModule` ancestor's.
 *
 * [isPartial] is true when at least one of [parents] is `@DIModule.Required`: the module then also
 * gets a [partClassName] class holding a binding for each of [partMembers] - every member of
 * [allMembers] but those pulled from a required parent.
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
    val parentByMemberName: Map<String, ParentModel>,
    val ancestorDeclarations: List<KSClassDeclaration>,
    val isComposed: Boolean,
    val sets: List<SetModel>,
    val isPartial: Boolean = false,
    val partClassName: ClassName? = null,
    val partMembers: List<MemberModel> = emptyList(),
) {
    /** Whether the [partClassName] class has a second constructor, taking the already-built parents
     * the module provides: only for a `@DIModule(composed = true)` partial module providing at least
     * one `@DIModule` parent - with none, it would clash with the flattened constructor. */
    val hasPartFromParents: Boolean get() = isPartial && isComposed && parents.any { !it.isRequired }
}

/** The `<Module>Part` class name of a partial module whose flattened base name is [baseName] (see
 * [generatedBaseName]), in [packageName]. */
internal fun partClassNameOf(packageName: String, baseName: String): ClassName = ClassName(packageName, "${baseName}Part")

/** The `<Module>BindingArguments` container class name of a module whose flattened base name is
 * [baseName] (see [generatedBaseName]), in [packageName]. */
internal fun argsContainerClassNameOf(packageName: String, baseName: String): ClassName =
    ClassName(packageName, "${baseName}BindingArguments")

/**
 * The declaration's simple name prefixed with every enclosing class/interface/object's simple name,
 * e.g. `OuterInner` for `Outer.Inner`. Used as the base for the generated file/class name so two
 * same-named nested declarations in different enclosing scopes can't collide - both for a `@DIModule`
 * interface and for a `@DIFactory` object (see `generateFactoryFile`) - and as the base for a
 * `@DIModule` parent's constructor parameter name (decapitalized).
 */
internal fun KSClassDeclaration.generatedBaseName(): String {
    val names = mutableListOf(simpleName.asString())
    var parent = parentDeclaration
    while (parent is KSClassDeclaration) {
        names.add(0, parent.simpleName.asString())
        parent = parent.parentDeclaration
    }
    return names.joinToString("")
}

/** The simple name of the generated implementation class / file for a flattened [baseName] (see
 * [generatedBaseName]) - also used for the `@DIFactory` builder file name. */
internal fun inaraName(baseName: String): String = "Inara$baseName"

/** The name of the copy functions generated for a module named [moduleSimpleName]: `copy<Module>`.
 * Named after the module so that a module's copy is never ambiguous with a parent module's. */
internal fun copyFunctionName(moduleSimpleName: String): String = "copy" + moduleSimpleName.replaceFirstChar(Char::uppercaseChar)

/** The name of the shadow functions generated for a module named [moduleSimpleName]: `shadow<Module>`. */
internal fun shadowFunctionName(moduleSimpleName: String): String = "shadow" + moduleSimpleName.replaceFirstChar(Char::uppercaseChar)

private fun KSClassDeclaration.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }

/** True for `@DIModule`. Also used by `DIFactoryModel.kt` to validate a `@DIFactory`'s `from`
 * argument. */
internal fun KSClassDeclaration.isDIModule(): Boolean =
    hasAnnotation(DI_MODULE_ANNOTATION)

/** The annotation classes listed in this declaration's `@DIModule(sets = ...)` - empty when it isn't
 * a `@DIModule`. A `KClass` array argument surfaces as a list of [KSType]s. */
private fun KSClassDeclaration.diModuleSetTypes(): List<KSType> {
    val annotation = annotations.firstOrNull { it.isAnnotation(DI_MODULE_ANNOTATION) } ?: return emptyList()
    return (annotation.argumentValue("sets") as? List<*>).orEmpty().filterIsInstance<KSType>()
}

/** True only for `@DIModule(composed = true)` - drives whether the extra from-parents builder and
 * parent-swapping `copy<Module>` overload are generated. */
private fun KSClassDeclaration.isComposedDIModule(): Boolean {
    val annotation = annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == DI_MODULE_ANNOTATION
    } ?: return false
    val arg = annotation.arguments.find { it.name?.asString() == "composed" }
        ?: annotation.defaultArguments.find { it.name?.asString() == "composed" }
    return arg?.value as? Boolean ?: false
}

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

/** Per-`parseModule`-call memo for [interfaceClosure], keyed by qualified name. A closure is
 * invariant within a round, and `resolveClosure`'s owner comparison plus diamond detection would
 * otherwise rebuild the same ones many times over on a wide or deep hierarchy. */
private typealias ClosureCache = MutableMap<String, Map<String, KSClassDeclaration>>

/**
 * [classDecl] plus every interface transitively reachable through its supertypes - `@DIModule` or
 * not, annotation doesn't matter here - keyed by qualified name. Used both to find the most
 * derived owner of a member reached through more than one path (see [resolveClosure]) and to
 * detect a shared ancestor pulled in by two different `@DIModule(composed = true)` parents.
 */
private fun interfaceClosure(classDecl: KSClassDeclaration, cache: ClosureCache): Map<String, KSClassDeclaration> {
    // An unresolved supertype resolves to a KSErrorTypeClassDeclaration with a null qualifiedName; it
    // carries no members and no supertypes, so drop it - the unresolved reference is already a
    // compiler error in its own right.
    val name = classDecl.qualifiedName?.asString() ?: return emptyMap()
    cache[name]?.let { return it }
    val result = LinkedHashMap<String, KSClassDeclaration>()
    result[name] = classDecl
    for (supertype in classDecl.superInterfaces()) {
        for ((qualifiedName, decl) in interfaceClosure(supertype, cache)) {
            result.putIfAbsent(qualifiedName, decl)
        }
    }
    // Only cache once fully built - never a partial result mid-recursion. Consumers only read it.
    return result.also { cache[name] = it }
}

/** True when [sub] is [sup], or transitively extends it. */
private fun isSubtypeOf(sub: KSClassDeclaration, sup: KSClassDeclaration, cache: ClosureCache): Boolean =
    sup.qualifiedName?.asString() in interfaceClosure(sub, cache).keys

/** True when this supertype reference carries the `@DIModule.Required` type annotation. KSP reads it
 * off a compiled library's declarations too, from the Kotlin metadata. */
private fun KSTypeReference.isMarkedRequired(): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == DI_MODULE_REQUIRED_ANNOTATION }

/** The direct supertypes of a `@DIModule` marked `@DIModule.Required` - none for any other interface,
 * on which the annotation means nothing. */
private fun KSClassDeclaration.requiredSuperInterfaces(): List<KSClassDeclaration> {
    if (!isDIModule()) return emptyList()
    return superTypes
        .filter { it.isMarkedRequired() }
        .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
        .toList()
}

/** True for a partial `@DIModule`: one with at least one `@DIModule.Required` direct supertype. */
private fun KSClassDeclaration.isPartialDIModule(): Boolean =
    isDIModule() && superTypes.any { it.isMarkedRequired() }

/**
 * Every interface [decl] requires rather than provides, keyed by qualified name: the closure of each
 * of its `@DIModule.Required` direct supertypes. Requirements of a parent it provides are not
 * included: [decl]'s own builders bind them (flattened), or a composing check rejects them (see
 * [checkComposedProviders]).
 */
private fun requiredClosure(decl: KSClassDeclaration, cache: ClosureCache): Map<String, KSClassDeclaration> {
    val result = LinkedHashMap<String, KSClassDeclaration>()
    for (required in decl.requiredSuperInterfaces()) {
        for ((name, ancestor) in interfaceClosure(required, cache)) result.putIfAbsent(name, ancestor)
    }
    return result
}

/** Every interface [decl] provides - itself and its whole closure, minus what it requires (see
 * [requiredClosure]) - keyed by qualified name. A plain interface provides its whole closure. */
private fun providedClosure(decl: KSClassDeclaration, cache: ClosureCache): Map<String, KSClassDeclaration> {
    val required = requiredClosure(decl, cache)
    return interfaceClosure(decl, cache).filterKeys { it !in required }
}

/** The members of [decls] that no other member of [decls] derives from: when `Base : Root` are both
 * involved in one mistake, naming `Root` too would just describe it twice. Two incomparable ones are
 * two genuine problems, so both are kept. */
private fun mostDerived(decls: Collection<KSClassDeclaration>, cache: ClosureCache): List<KSClassDeclaration> =
    decls.filter { c ->
        decls.none { o ->
            o.qualifiedName?.asString() != c.qualifiedName?.asString() && isSubtypeOf(o, c, cache)
        }
    }

/** A property or function declaration paired with the interface that actually declares it. */
private data class ClosureDecl(val decl: KSDeclaration, val owner: KSClassDeclaration)

/**
 * Every property/function transitively reachable from [classDecl]'s supertypes, `@DIModule` or
 * plain alike (annotation doesn't matter once inside the walk - only [parseModule]'s direct-parent
 * partitioning cares), each paired with its declaring interface. A private member is dropped here
 * (and, symmetrically, [parseModule]'s own-member loop drops one declared directly on the annotated
 * interface): an interface's private member always carries its own body, so the generated class
 * never has anything to implement for it. Raw: the same declaration can appear more than once
 * (diamond inheritance, or an override alongside the member it overrides) - see [resolveClosure].
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
 * parent (pulled off that parent instance in the `@DIModule(composed = true)` builder,
 * [ModuleModel.allMembers] only). [directParent] is that `@DIModule` parent, `null` for a
 * plain-supertype candidate. [overridden] is filled by [resolveClosure] on the surviving candidate:
 * every other declaration of the same name it collapsed, i.e. those the survivor overrides - a set
 * annotation (see `@DISet`) or a parameter's default value on one of them still counts.
 */
private data class Candidate(
    val closureDecl: ClosureDecl,
    val node: KSTypeReference,
    val ownFromPlain: Boolean,
    val directParent: ParentModel?,
    val overridden: List<KSDeclaration> = emptyList(),
)

/**
 * Resolves [candidates] into at most one [Candidate] per member name, reporting every genuine
 * clash through [reportError] (anchored on [classDecl]).
 *
 * Two entries with the same name collapse silently when one's owner is the other's (the exact
 * same declaration reached twice, e.g. diamond inheritance) or a subtype of the other's (an
 * `override` in a more derived interface shadowing what it overrides, however many levels apart) -
 * the most derived entry survives, preferring one reached through a plain supertype over one
 * reached through a `@DIModule` parent when both reach the same winning owner, then one reached
 * through a parent that provides that owner (see [providedClosure]) over one reached through a
 * parent that only requires it - a member always comes from the parent able to supply it. Anything
 * else - two unrelated owners, or two distinct declarations on the very same owner (an overload) -
 * is rejected.
 */
private fun resolveClosure(
    candidates: List<Candidate>,
    classDecl: KSClassDeclaration,
    reportError: (String, KSNode) -> Unit,
    closureCache: ClosureCache,
): List<Candidate> {
    fun providesOwner(candidate: Candidate): Boolean {
        val parent = candidate.directParent ?: return false
        return candidate.closureDecl.owner.qualifiedName?.asString() in providedClosure(parent.declaration, closureCache)
    }
    val result = mutableListOf<Candidate>()
    for ((name, entries) in candidates.groupBy { it.closureDecl.decl.simpleName.asString() }) {
        val mostDerivedOwners = entries
            .map { it.closureDecl.owner }
            .distinctBy { it.qualifiedName?.asString() }
            .filter { candidate -> entries.all { other -> isSubtypeOf(candidate, other.closureDecl.owner, closureCache) } }

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

        val survivor = winners.firstOrNull { it.ownFromPlain } ?: winners.firstOrNull(::providesOwner) ?: winners.first()
        val overridden = entries.map { it.closureDecl.decl }.distinct().filter { it != survivor.closureDecl.decl }
        result += survivor.copy(overridden = overridden)
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
 * [parseModuleUncached], memoized for the lifetime of one processing round via [cache] (keyed by
 * qualified name) - so a `@DIModule` that is also some `@DIFactory`'s `from` is validated, and its
 * diagnostics emitted, exactly once rather than once per pointing factory. A failed parse is cached
 * as `null` and not re-reported.
 */
internal fun parseModule(
    classDecl: KSClassDeclaration,
    logger: KSPLogger,
    cache: RoundCache,
): ModuleModel? {
    val key = classDecl.qualifiedName?.asString() ?: return parseModuleUncached(classDecl, logger, cache)
    if (key in cache.modules) return cache.modules[key]
    return parseModuleUncached(classDecl, logger, cache).also { cache.modules[key] = it }
}

/**
 * Validates [classDecl] against every `@DIModule` rule and builds a [ModuleModel] out of it.
 *
 * Every violation found is reported through [logger] before returning - so a single compilation
 * surfaces every problem in the interface at once, rather than one error per run. Returns `null`
 * if the interface is invalid.
 *
 * The sets (see `@DISet`) the module generates a function for are validated last, once every member
 * is known - see [parseSets].
 */
private fun parseModuleUncached(classDecl: KSClassDeclaration, logger: KSPLogger, cache: RoundCache): ModuleModel? {
    var hasError = false
    fun reportError(message: String, node: KSNode) {
        logger.error(message, node)
        hasError = true
    }
    val closureCache: ClosureCache = mutableMapOf()

    if (classDecl.classKind != ClassKind.INTERFACE) {
        reportError("Only interfaces may be annotated with @DIModule.", classDecl)
        return null
    }

    if (classDecl.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
        reportError(
            "A @DIModule interface must be public or internal: '${classDecl.simpleName.asString()}' is not.",
            classDecl,
        )
        return null
    }

    val isComposed = classDecl.isComposedDIModule()
    val moduleType = classDecl.asStarProjectedType()

    if (classDecl.typeParameters.isNotEmpty()) {
        reportError("A @DIModule interface may not have type parameters.", classDecl)
    }

    // Resolve direct supertypes into @DIModule parents (a @DIModule(composed = true) module can be
    // built from instances of these) and plain supertypes (their members are bound
    // directly, like this interface's own). A supertype that doesn't resolve to an interface at
    // all (an unresolved/error reference) makes the rest of this function unsafe to run, so bail
    // out immediately after reporting every such supertype.
    var supertypesValid = true
    val parentEntries = mutableListOf<Pair<KSTypeReference, KSClassDeclaration>>()
    val plainEntries = mutableListOf<Pair<KSTypeReference, KSClassDeclaration>>()
    val requiredParentNames = HashSet<String>()
    for (superType in classDecl.superTypes) {
        val resolved = superType.resolve().declaration
        if (resolved.qualifiedName?.asString() == "kotlin.Any") continue
        val superDecl = resolved as? KSClassDeclaration
        if (superDecl == null) {
            reportError("A @DIModule interface may only extend interfaces.", superType)
            supertypesValid = false
            continue
        }
        val isRequired = superType.isMarkedRequired()
        if (superDecl.isDIModule()) {
            parentEntries += superType to superDecl
            if (isRequired) superDecl.qualifiedName?.asString()?.let(requiredParentNames::add)
        } else {
            plainEntries += superType to superDecl
            if (isRequired) {
                reportError(
                    "Only a @DIModule supertype can be @DIModule.Required: '${superDecl.simpleName.asString()}' is not a @DIModule.",
                    superType,
                )
            }
        }
    }
    if (!supertypesValid) return null

    val parentDecls = parentEntries.map { it.second }
    fun KSClassDeclaration.isRequiredParent(): Boolean = qualifiedName?.asString() in requiredParentNames

    if (isComposed && parentDecls.isEmpty()) {
        reportError(
            "@DIModule(composed = true) requires the interface to extend at least one @DIModule interface.",
            classDecl,
        )
    }

    // A required supertype must stay required all the way down: no interface it reaches may also be
    // reached through a supertype this module provides - that one would bind it (or override one of
    // its members), so whoever composes this module would have it provided twice.
    val requiredDecls = parentDecls.filter { it.isRequiredParent() }
    val providingDecls = parentDecls.filterNot { it.isRequiredParent() } + plainEntries.map { it.second }
    for (required in requiredDecls) {
        val requiredClosure = interfaceClosure(required, closureCache)
        for (providing in providingDecls) {
            val provided = providedClosure(providing, closureCache)
            val shared = requiredClosure.filterKeys { it in provided }.values
            for (ancestor in mostDerived(shared, closureCache)) {
                val through = if (ancestor.qualifiedName?.asString() == required.qualifiedName?.asString()) {
                    ""
                } else {
                    " through '${required.simpleName.asString()}'"
                }
                reportError(
                    "Interface '${ancestor.simpleName.asString()}' is required$through, " +
                        "but '${providing.simpleName.asString()}' provides it.",
                    classDecl,
                )
            }
        }
    }

    if (isComposed) checkComposedProviders(classDecl, parentDecls, { it.isRequiredParent() }, closureCache, ::reportError)

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
        // The parent's own model shares its arguments classes with this module, and describes its
        // Part. It is parsed (once per round) wherever it comes from - a library's compiled parent
        // included. A parent from this compilation that fails to parse has already reported why.
        val parentModel = parseModule(parent, logger, cache)
        if (parentModel == null) {
            if (parent.containingFile == null) {
                reportError("The @DIModule parent '${parent.simpleName.asString()}' could not be read.", classDecl)
            } else {
                hasError = true
            }
        }
        // The parent's own Inara<Parent> class is public (behind @InternalInaraGeneration), so it's
        // always referenceable - whichever compilation the parent came from.
        val parentPackage = parent.packageName.asString()
        val parentBaseName = parent.generatedBaseName()
        parentModels += ParentModel(
            declaration = parent,
            moduleClassName = parent.toClassName(),
            generatedClassName = ClassName(parentPackage, inaraName(parentBaseName)),
            paramName = paramName,
            isRequired = parent.isRequiredParent(),
            partClassName = if (parent.isPartialDIModule()) partClassNameOf(parentPackage, parentBaseName) else null,
            model = parentModel,
        )
    }
    val parentModelByDecl: Map<KSClassDeclaration, ParentModel> =
        parentDecls.zip(parentModels).toMap()

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
     * it. The naming-identity rules above stay unconditional regardless: a `Binding`-suffixed,
     * `of`-named or `arg<Argument>`-shaped member could still collide with the generated class's own
     * naming scheme, with a generated function's parameter, or with a `@DIFactory` object's expected
     * extension-function convention, even while unbound. A member named `copy` or `shadow` is
     * allowed: it only hides the deprecated functions of these names, which no call can use anyway.
     *
     * A function's parameter count and any vararg parameter are unrestricted - see [ParameterModel]
     * and `ModuleFileGenerator` for how more than one parameter is wrapped into a generated data
     * class.
     *
     * A member carrying a set annotation (see `@DISet`) - itself, or one of the [overridden]
     * declarations it collapsed (see [Candidate.overridden]) - must also be one Inara binds (one it
     * would otherwise silently exclude is rejected instead), be callable without arguments (every
     * parameter `vararg` or defaulted, here or on the declaration it overrides), be typed as a
     * subtype of every such set's `of` type (nullability aside), and not be `suspend` when a set is
     * `@DISet(suspend = Forbid)`. Checked whether or not the module lists the set: a descendant may.
     */
    fun parseMember(
        decl: KSDeclaration,
        owner: KSClassDeclaration,
        node: KSNode,
        isOwn: Boolean,
        overridden: List<KSDeclaration> = emptyList(),
    ): MemberModel? {
        val name = decl.simpleName.asString()
        val prefix = if (isOwn) "" else "Member '$name' inherited from '${owner.simpleName.asString()}': "
        var valid = true
        // Why Inara silently excludes this member from the module's bindable surface - `null` when it doesn't.
        var skipReason: String? = null
        fun fail(message: String) {
            reportError(prefix + message, node)
            valid = false
        }

        if (name.endsWith("Binding")) fail("A @DIModule member name may not end with 'Binding': '$name'.")
        // Reserved for the `of` parameter of the `Module.copy(of = module, ...)` and
        // `Module.shadow(of = module, ...)` companion extensions that are to replace `copy<Module>` and
        // `shadow<Module>` once Kotlin's companion extensions are stable.
        if (name == "of") fail("A @DIModule member may not be named 'of'.")
        // Implemented by the generated class to answer for its sets (see InaraSets).
        if (name == "inaraSetOf" || name == "inaraSuspendSetOf") fail("A @DIModule member may not be named '$name'.")
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
                    decl.hasFullyDefaultAccessors() -> skipReason = "a 'var'"
                    else -> fail("var properties are not supported: '$name'.")
                }
                if (skipReason == null && decl.extensionReceiver != null) {
                    if (hasDefault) skipReason = "an extension member" else fail("Extension members are not supported: '$name'.")
                }
                // `private` is already filtered upstream (own: parseModule's own-member loop,
                // inherited: superDeclarationClosure), so this only ever catches `internal`.
                if (skipReason == null && decl.getVisibility() != Visibility.PUBLIC) {
                    fail("Non-public members are not supported: '$name'.")
                }
            }
            is KSFunctionDeclaration -> {
                hasDefault = !decl.isAbstract
                if (decl.extensionReceiver != null) {
                    if (hasDefault) skipReason = "an extension member" else fail("Extension members are not supported: '$name'.")
                }
                if (skipReason == null && decl.typeParameters.isNotEmpty()) {
                    if (hasDefault) skipReason = "a generic function" else fail("Generic members are not supported: '$name'.")
                }
                if (skipReason == null && decl.getVisibility() != Visibility.PUBLIC) {
                    fail("Non-public members are not supported: '$name'.")
                }
            }
            else -> return null
        }

        val memberSets = (listOf(decl) + overridden).flatMap { cache.setSpecsOn(it) }.distinctBy { it.qualifiedName }
        val setNames = memberSets.joinToString(", ") { "@${it.simpleName}" }
        if (memberSets.isNotEmpty()) {
            if (skipReason != null) {
                fail("'$name' is annotated with $setNames, but Inara ignores $skipReason, so it can't belong to a set.")
            }
            if (decl is KSFunctionDeclaration) {
                val overriddenFunctions = overridden.filterIsInstance<KSFunctionDeclaration>()
                val required = decl.parameters.withIndex().filter { (index, param) ->
                    !param.isVararg && !param.hasDefault &&
                        overriddenFunctions.none { it.parameters.getOrNull(index)?.hasDefault == true }
                }
                if (required.isNotEmpty()) {
                    fail(
                        "'$name' is annotated with $setNames, but can't be called without arguments: " +
                            required.joinToString(", ") { "'${it.value.name?.asString()}'" } +
                            " ${if (required.size == 1) "has" else "have"} no default value.",
                    )
                }
                val forbidding = memberSets.filter { !it.isSuspend }
                if (Modifier.SUSPEND in decl.modifiers && forbidding.isNotEmpty()) {
                    fail(
                        "'$name' is suspend, but " + forbidding.joinToString(", ") { "@${it.simpleName}" } +
                            " ${if (forbidding.size == 1) "is not a" else "are not"} @DISet(suspend = true).",
                    )
                }
            }
        }

        if (!valid) return null
        if (skipReason != null) return null
        val member = buildMember(decl, moduleType, hasDefault).copy(sets = memberSets.map { it.qualifiedName })

        // Checked against the member's type as seen from the module (see buildMember), since a plain
        // generic supertype may only fix it there.
        if (!member.returnType.isError) {
            val returnType = member.returnType.makeNotNullable()
            val mismatches = memberSets.filter { it.ofType?.isAssignableFrom(returnType) == false }
            for (set in mismatches) {
                fail(
                    "'$name' is annotated with @${set.simpleName}, but its type " +
                        "'${member.returnType.toTypeName().simpleString()}' is not a subtype of " +
                        "'${set.ofType!!.toTypeName().simpleString()}'.",
                )
            }
            if (mismatches.isNotEmpty()) return null
        }
        return member
    }

    val allMembers = mutableListOf<MemberModel>()
    val ownMembers = mutableListOf<MemberModel>()
    val parentByMemberName = mutableMapOf<String, ParentModel>()

    // Every member reachable through a @DIModule parent (allMembers only - pulled off that parent
    // instance in the @DIModule(composed = true) builder) or a plain supertype (both - nothing
    // supplies it from a parent, so it needs its own binding just like an own declaration),
    // resolved together so an override spanning both groups, or a shared ancestor reached through
    // either, collapses to one candidate instead of a false clash.
    val candidates = parentEntries.flatMap { (node, parent) ->
        superDeclarationClosure(parent).map { Candidate(it, node, ownFromPlain = false, directParent = parentModelByDecl[parent]) }
    } + plainEntries.flatMap { (node, plain) ->
        superDeclarationClosure(plain).map { Candidate(it, node, ownFromPlain = true, directParent = null) }
    }

    val ownArgsContainer = argsContainerClassNameOf(classDecl.packageName.asString(), classDecl.generatedBaseName())
    /** [member]'s own arguments class, owned by this module - for a member it introduces. */
    fun ownArgs(member: MemberModel): MemberModel =
        if (member.parameters.size > 1) member.copy(argsClassName = ownArgsContainer.nestedClass(member.argsClassSimpleName())) else member

    for (candidate in resolveClosure(candidates, classDecl, ::reportError, closureCache)) {
        val (closureDecl, node, ownFromPlain, directParent, overridden) = candidate
        val parsed = parseMember(closureDecl.decl, closureDecl.owner, node, isOwn = false, overridden) ?: continue
        nameOwners[closureDecl.decl.simpleName.asString()] = closureDecl.owner
        // A member pulled from a @DIModule parent shares that parent's arguments class, so the
        // parent's binding fits this module's slot as it is.
        val member = if (ownFromPlain || directParent == null) {
            ownArgs(parsed)
        } else {
            val parentArgs = directParent.model?.allMembers?.firstOrNull { it.name == parsed.name }?.argsClassName
            if (parentArgs != null) parsed.copy(argsClassName = parentArgs) else ownArgs(parsed)
        }
        allMembers += member
        if (ownFromPlain) ownMembers += member
        else directParent?.let { parentByMemberName[member.name] = it }
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
        // A private member always carries its own body - nothing for the generated class to
        // implement, exactly as superDeclarationClosure treats an inherited one.
        if (decl.getVisibility() == Visibility.PRIVATE) continue
        val claimOk = claimName(decl.simpleName.asString(), decl)
        val member = parseMember(decl, classDecl, decl, isOwn = true)?.let(::ownArgs)
        if (claimOk && member != null) {
            ownMembers += member
            allMembers += member
        }
    }

    // A @DIModule parent's parameter name may not collide with any member name in the
    // consolidated interface (checked last, since own member names are only known by now). Only
    // relevant to a @DIModule(composed = true) interface - a plain @DIModule interface has no such parameter.
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
    // generate the same nested data class twice. Only the classes this module generates itself
    // matter: an inherited member's lives in the container of the module introducing it.
    val ownedArgsMembers = allMembers.filter { it.argsClassName?.enclosingClassName() == ownArgsContainer }
    for ((capitalized, group) in ownedArgsMembers.groupBy { it.argsClassSimpleName() }) {
        if (group.size > 1) {
            reportError(
                "Members " + group.map { it.name }.distinct().sorted().joinToString(", ") { "'$it'" } +
                    " would all generate a '$capitalized' arguments class - rename one of them.",
                classDecl,
            )
        }
    }

    checkCopyAndShadowNames(classDecl, closureCache, ::reportError)
    val sets = parseSets(classDecl, allMembers, cache, closureCache, ::reportError)

    if (hasError) return null

    val isPartial = parentModels.any { it.isRequired }

    val ancestorDeclarations = LinkedHashMap<String, KSClassDeclaration>().apply {
        for (supertype in parentDecls + plainEntries.map { it.second }) {
            for ((qualifiedName, decl) in interfaceClosure(supertype, closureCache)) {
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
        parentByMemberName = parentByMemberName,
        ancestorDeclarations = ancestorDeclarations,
        isComposed = isComposed,
        sets = sets,
        isPartial = isPartial,
        partClassName = if (isPartial) partClassNameOf(classDecl.packageName.asString(), classDecl.generatedBaseName()) else null,
        partMembers = if (isPartial) allMembers.filter { parentByMemberName[it.name]?.isRequired != true } else emptyList(),
    )
}

/**
 * The rule that replaces the outright diamond rejection for a `@DIModule(composed = true)` module:
 * its from-parents builder reads every inherited member off exactly one parent, so every interface
 * reachable through [parents] must be *provided* (see [providedClosure]) by at most one of them -
 * two would be ambiguous - and by exactly one whenever some parent *requires* it (see
 * [requiredClosure]). A parent this module itself marks `@DIModule.Required` counts as a provider:
 * the from-parents builder takes it too. A required parent overlapping a provided one is already
 * reported by [parseModule]'s required-supertype rule, so such a pair is skipped here.
 */
private fun checkComposedProviders(
    classDecl: KSClassDeclaration,
    parents: List<KSClassDeclaration>,
    isRequired: (KSClassDeclaration) -> Boolean,
    closureCache: ClosureCache,
    reportError: (String, KSNode) -> Unit,
) {
    val provided = parents.map { it to providedClosure(it, closureCache) }
    for (i in provided.indices) {
        for (j in i + 1 until provided.size) {
            val (parentA, closureA) = provided[i]
            val (parentB, closureB) = provided[j]
            if (isRequired(parentA) != isRequired(parentB)) continue
            val shared = closureA.filterKeys { it in closureB }.values
            for (ancestor in mostDerived(shared, closureCache)) {
                reportError(
                    "Interface '${ancestor.simpleName.asString()}' is inherited more than once, " +
                        "via '${parentA.simpleName.asString()}' and '${parentB.simpleName.asString()}'.",
                    classDecl,
                )
            }
        }
    }

    val providedNames = provided.flatMapTo(HashSet()) { it.second.keys }
    val unmet = LinkedHashMap<String, KSClassDeclaration>()
    for (parent in parents) {
        for ((name, decl) in requiredClosure(parent, closureCache)) {
            if (name !in providedNames) unmet.putIfAbsent(name, decl)
        }
    }
    for (ancestor in mostDerived(unmet.values, closureCache)) {
        val ancestorName = ancestor.qualifiedName?.asString()
        val requirers = parents.filter { ancestorName in requiredClosure(it, closureCache) }.map { "'${it.simpleName.asString()}'" }
        reportError(
            requirers.joinToString(" and ") + " require${if (requirers.size == 1) "s" else ""} " +
                "'${ancestor.simpleName.asString()}', which no parent of '${classDecl.simpleName.asString()}' provides.",
            classDecl,
        )
    }
}

/**
 * Keeps every `copy<Module>` / `shadow<Module>` function callable on [classDecl] unambiguous - its
 * own, and those of each of its `@DIModule` ancestors, which a module's instance can call too.
 *
 * The module may not share its simple name with one of its `@DIModule` ancestors: both would
 * generate the same `copy<Module>` / `shadow<Module>` names, so a call on the module naming only
 * the ancestor's members would match the ancestor's function as well as its own. (Two *sibling*
 * ancestors sharing a name are fine: the module's own functions are not affected.) And no
 * non-private member of the module, bound or not, may be named like one of those functions: Kotlin
 * always prefers a member over an extension, so the function would silently stop being called.
 */
private fun checkCopyAndShadowNames(
    classDecl: KSClassDeclaration,
    closureCache: ClosureCache,
    reportError: (String, KSNode) -> Unit,
) {
    val modules = interfaceClosure(classDecl, closureCache).values.filter { it.isDIModule() }

    val simpleName = classDecl.simpleName.asString()
    for (ancestor in modules) {
        if (ancestor.simpleName.asString() == simpleName && ancestor.qualifiedName?.asString() != classDecl.qualifiedName?.asString()) {
            reportError(
                "'${classDecl.qualifiedName?.asString()}' shares its simple name with its ancestor " +
                    "'${ancestor.qualifiedName?.asString()}': their '${copyFunctionName(simpleName)}' and " +
                    "'${shadowFunctionName(simpleName)}' functions would be ambiguous - rename one of them.",
                classDecl,
            )
        }
    }

    val memberNames = superDeclarationClosure(classDecl).mapTo(HashSet()) { it.decl.simpleName.asString() }
    for (module in modules) {
        val moduleName = module.simpleName.asString()
        for (functionName in listOf(copyFunctionName(moduleName), shadowFunctionName(moduleName))) {
            if (functionName in memberNames) {
                reportError(
                    "A member named '$functionName' clashes with the '$functionName' function generated for '$moduleName'.",
                    classDecl,
                )
            }
        }
    }
}

/**
 * Validates the sets [classDecl] answers for - every one listed in its own `@DIModule(sets = ...)`,
 * then every one listed by any `@DIModule` ancestor (reached through a `@DIModule` parent or a plain
 * supertype alike), each once - and builds a [SetModel] for each set that passed, collecting the
 * members of [allMembers] that carry it. Only the sets it lists itself get a `setOf<Annotation>()`
 * function on this module (see [SetModel.isListed]); an inherited one's function is its ancestor's.
 *
 * Every entry of the module's own `sets` must be a set annotation, listed once (listing a set
 * already inherited is fine). Two sets may not share a simple name (their functions would be
 * ambiguous on this module), and no non-private member of the module, bound or not, may be named
 * like one of their functions (it would silently shadow the extension). The `of` type of every set
 * the module lists must be nameable from its generated function: never `internal` to another
 * module, and `public` for a `public` module. A set may collect no member at all: a module listing
 * it is then the place its descendants' members are gathered from.
 */
private fun parseSets(
    classDecl: KSClassDeclaration,
    allMembers: List<MemberModel>,
    cache: RoundCache,
    closureCache: ClosureCache,
    reportError: (String, KSNode) -> Unit,
): List<SetModel> {
    val moduleName = classDecl.simpleName.asString()
    val moduleVisibility = classDecl.getVisibility()

    val ownSets = mutableListOf<SetSpec>()
    for (type in classDecl.diModuleSetTypes()) {
        val spec = (type.declaration as? KSClassDeclaration)?.let(cache::setSpecOf)
        if (spec == null) {
            reportError(
                "'${type.declaration.simpleName.asString()}' is listed in 'sets', but it is not annotated with @DISet.",
                classDecl,
            )
            continue
        }
        if (ownSets.any { it.qualifiedName == spec.qualifiedName }) {
            reportError("'@${spec.simpleName}' is listed more than once in 'sets'.", classDecl)
            continue
        }
        ownSets += spec
    }
    val ownSetNames = ownSets.mapTo(HashSet()) { it.qualifiedName }

    // A non-@DISet entry of an ancestor's `sets` is that ancestor's own error, reported when it is parsed.
    val inheritedSets = interfaceClosure(classDecl, closureCache).values
        .filter { it.qualifiedName?.asString() != classDecl.qualifiedName?.asString() && it.isDIModule() }
        .flatMap { ancestor -> ancestor.diModuleSetTypes().mapNotNull { (it.declaration as? KSClassDeclaration)?.let(cache::setSpecOf) } }
    val sets = (ownSets + inheritedSets).distinctBy { it.qualifiedName }

    val invalid = HashSet<String>()
    fun reportSetError(set: SetSpec, message: String) {
        reportError(message, classDecl)
        invalid += set.qualifiedName
    }

    for ((simpleName, group) in sets.groupBy { it.simpleName }) {
        if (group.size > 1) {
            for (set in group) invalid += set.qualifiedName
            reportError(
                "Sets " + group.joinToString(", ") { "'${it.qualifiedName}'" } +
                    " would all generate 'setOf$simpleName' - rename one of them.",
                classDecl,
            )
        }
    }

    val memberNames = superDeclarationClosure(classDecl).mapTo(HashSet()) { it.decl.simpleName.asString() }
    for (set in sets) {
        if (set.functionName in memberNames) {
            reportSetError(set, "A member named '${set.functionName}' clashes with the function generated for the set '@${set.simpleName}'.")
        }

        // Only a set listed here names its `of` type, in the function this module generates. An
        // inherited one was checked on the ancestor listing it.
        if (set.qualifiedName !in ownSetNames) continue
        val ofError = set.ofTypeError()
        if (ofError != null) {
            reportSetError(set, ofError)
            continue
        }
        val ofDecl = set.ofDeclaration!!
        val ofName = ofDecl.simpleName.asString()
        if (ofDecl.effectiveVisibility() == Visibility.INTERNAL) {
            when {
                ofDecl.containingFile == null -> reportSetError(
                    set,
                    "The 'of' type '$ofName' of the set '@${set.simpleName}' is internal to another module, so '$moduleName' can't name it.",
                )
                moduleVisibility == Visibility.PUBLIC -> reportSetError(
                    set,
                    "The public module '$moduleName' can't expose the internal 'of' type '$ofName' of the set " +
                        "'@${set.simpleName}'. Make '$moduleName' internal, or '$ofName' public.",
                )
            }
        }
    }

    return sets.mapNotNull { set ->
        if (set.qualifiedName in invalid) return@mapNotNull null
        // An inherited set's `of` type is only named by its ancestor's function: if it doesn't
        // resolve, that ancestor reports it.
        val ofType = set.ofType ?: return@mapNotNull null
        SetModel(
            qualifiedName = set.qualifiedName,
            functionName = set.functionName,
            elementTypeName = ofType.toTypeName(),
            members = allMembers.filter { set.qualifiedName in it.sets },
            isSuspend = set.isSuspend,
            isListed = set.qualifiedName in ownSetNames,
            originatingFiles = listOfNotNull(set.declaration.containingFile, set.ofDeclaration?.containingFile),
        )
    }
}
