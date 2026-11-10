package org.kodein.di.inara.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.TypeName

internal const val DI_SET_ANNOTATION = "org.kodein.di.inara.DISet"

/**
 * A *set annotation*: an annotation class annotated with `@DISet`. [ofType] is the `of` type
 * star-projected (`Plugin<*>` for `of = Plugin::class`), a `typealias` expanded - `null`, along with
 * [ofDeclaration], when it doesn't resolve to a class (see [ofTypeError]).
 */
internal class SetSpec(
    val declaration: KSClassDeclaration,
    val qualifiedName: String,
    val ofDeclaration: KSClassDeclaration?,
    val ofType: KSType?,
    val suspendMode: SuspendMode,
) {
    val simpleName: String get() = declaration.simpleName.asString()

    /** The name of the extension function generated for this set on a module listing it. */
    val functionName: String get() = "setOf$simpleName"
}

/**
 * One set a module generates a `setOf<Annotation>()` function for: every member of
 * [ModuleModel.allMembers] carrying the set annotation, in that order. [elementTypeName] is the
 * star-projected `of` type. [originatingFiles] are the set annotation's and its `of` type's own
 * files, on top of the module's (the generated function depends on both).
 */
internal data class SetModel(
    val functionName: String,
    val elementTypeName: TypeName,
    val members: List<MemberModel>,
    val isSuspend: Boolean,
    val originatingFiles: List<KSFile>,
)

/**
 * Everything memoized for the lifetime of one processing round - KSP invalidates `KSNode`s between
 * rounds, so none of it may outlive `process()`. [modules] backs [parseModule] (so a module that is
 * also some `@DIFactory`'s `from` is validated, and its diagnostics reported, once). The set
 * annotations read through [setSpecOf] are also what [validateSetUses] checks the uses of.
 */
internal class RoundCache {
    val modules: MutableMap<String, ModuleModel?> = mutableMapOf()
    private val setSpecs = LinkedHashMap<String, SetSpec?>()

    /** [annotationDecl]'s [SetSpec], or `null` when it isn't annotated with `@DISet`. */
    fun setSpecOf(annotationDecl: KSClassDeclaration): SetSpec? {
        val name = annotationDecl.qualifiedName?.asString() ?: return null
        if (name in setSpecs) return setSpecs[name]
        return readSetSpec(annotationDecl, name).also { setSpecs[name] = it }
    }

    /** Every set annotation [annotated] carries, each once (a `@Repeatable` one may be repeated). */
    fun setSpecsOn(annotated: KSAnnotated): List<SetSpec> =
        annotated.annotations
            .mapNotNull { annotation ->
                val decl = annotation.annotationType.resolve().declaration as? KSClassDeclaration
                decl?.takeIf { it.classKind == ClassKind.ANNOTATION_CLASS }?.let(::setSpecOf)
            }
            .distinctBy { it.qualifiedName }
            .toList()

    /** Every set annotation read so far this round. */
    val knownSets: List<SetSpec> get() = setSpecs.values.filterNotNull()
}

/** True when this annotation is [qualifiedName] - the cheap short-name check first, so an unrelated
 * annotation never needs its type resolved. */
internal fun KSAnnotation.isAnnotation(qualifiedName: String): Boolean =
    shortName.asString() == qualifiedName.substringAfterLast('.') &&
        annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

/** The value of the argument [name], falling back to its default value when it isn't set - KSP may
 * carry an unset, defaulted argument in either list. */
internal fun KSAnnotation.argumentValue(name: String): Any? =
    (arguments.find { it.name?.asString() == name } ?: defaultArguments.find { it.name?.asString() == name })?.value

/** The entry name of an enum-typed annotation argument value - KSP documents a `KSClassDeclaration`
 * for it (see `readSuspendMode`), but a `KSType` is tolerated too. */
private fun enumEntryName(value: Any?): String? = when (value) {
    is KSClassDeclaration -> value.simpleName.asString()
    is KSType -> value.declaration.simpleName.asString()
    else -> value?.toString()?.substringAfterLast('.')
}

private fun KSType.expandAliases(depth: Int = 0): KSType {
    val decl = declaration
    return if (decl is KSTypeAlias && depth < 20) decl.type.resolve().expandAliases(depth + 1) else this
}

private fun readSetSpec(annotationDecl: KSClassDeclaration, qualifiedName: String): SetSpec? {
    val diSet = annotationDecl.annotations.firstOrNull { it.isAnnotation(DI_SET_ANNOTATION) } ?: return null
    val ofType = (diSet.argumentValue("of") as? KSType)?.expandAliases()?.takeUnless { it.isError }
    val ofDecl = ofType?.declaration as? KSClassDeclaration
    return SetSpec(
        declaration = annotationDecl,
        qualifiedName = qualifiedName,
        ofDeclaration = ofDecl,
        ofType = ofDecl?.asStarProjectedType(),
        suspendMode = readSuspendMode(diSet),
    )
}

/**
 * The visibility generated code can name this declaration with: `INTERNAL` if it, or any declaration
 * enclosing it, is `internal`, `PUBLIC` if they all are, and `null` if any of them is anything else
 * (`private`, `protected`, local, Java package-private) - generated code can't name it at all.
 */
internal fun KSDeclaration.effectiveVisibility(): Visibility? {
    var result = Visibility.PUBLIC
    var current: KSDeclaration? = this
    while (current != null) {
        when (current.getVisibility()) {
            Visibility.PUBLIC -> Unit
            Visibility.INTERNAL -> result = Visibility.INTERNAL
            else -> return null
        }
        current = current.parentDeclaration
    }
    return result
}

/** Why [SetSpec.ofType] can't be named from generated code at all, `null` if it can. Reported both on
 * the set annotation itself and on any module generating a function for it. */
internal fun SetSpec.ofTypeError(): String? {
    val ofDecl = ofDeclaration ?: return "The 'of' argument of @DISet on '$simpleName' must reference a class."
    if (ofDecl.effectiveVisibility() == null) {
        return "The 'of' type '${ofDecl.simpleName.asString()}' of @DISet on '$simpleName' must be public or internal, " +
            "and so must every declaration enclosing it."
    }
    return null
}

/**
 * Validates the declaration of [spec]'s annotation class, every error anchored on it: its retention
 * can't be `SOURCE` (Inara could never see it on a member compiled in another module), its
 * `@Target`, when declared, must include `FUNCTION` or `PROPERTY` (it could annotate no module
 * member otherwise), and its `of` type must be nameable from generated code (see [ofTypeError]).
 */
internal fun validateSetDeclaration(spec: SetSpec, logger: KSPLogger) {
    val decl = spec.declaration
    val name = spec.simpleName

    val retention = decl.annotations.firstOrNull { it.isAnnotation("kotlin.annotation.Retention") }
    if (retention != null && enumEntryName(retention.argumentValue("value")) == "SOURCE") {
        logger.error(
            "The @DISet annotation '$name' may not have a SOURCE retention: " +
                "Inara could not see it on members compiled in another module.",
            decl,
        )
    }

    val target = decl.annotations.firstOrNull { it.isAnnotation("kotlin.annotation.Target") }
    if (target != null) {
        val targets = (target.argumentValue("allowedTargets") as? List<*>).orEmpty().map(::enumEntryName)
        if ("FUNCTION" !in targets && "PROPERTY" !in targets) {
            logger.error(
                "The @DISet annotation '$name' must target FUNCTION or PROPERTY: it may only annotate module members.",
                decl,
            )
        }
    }

    spec.ofTypeError()?.let { logger.error(it, decl) }
}

/**
 * Checks every use of [sets] in this round's sources that no `@DIModule` could ever reach, every
 * error anchored on the annotated symbol: a set annotation may only annotate a function or property
 * declared in an interface (never a top-level declaration, a class or object member, a class, a
 * parameter, an accessor...), and never a `private` one (an interface's private member is invisible
 * to Inara). The member rules themselves are checked by [parseModule], on every module reaching it.
 */
internal fun validateSetUses(resolver: Resolver, sets: Collection<SetSpec>, logger: KSPLogger) {
    for (spec in sets) {
        for (symbol in resolver.getSymbolsWithAnnotation(spec.qualifiedName)) {
            val isMember = symbol is KSPropertyDeclaration || symbol is KSFunctionDeclaration
            val owner = (symbol as? KSDeclaration)?.parentDeclaration as? KSClassDeclaration
            when {
                !isMember || owner?.classKind != ClassKind.INTERFACE -> logger.error(
                    "@${spec.simpleName} may only annotate a function or a property declared in an interface.",
                    symbol,
                )
                (symbol as KSDeclaration).getVisibility() == Visibility.PRIVATE -> logger.error(
                    "@${spec.simpleName} may not annotate a private member: Inara never sees it.",
                    symbol,
                )
            }
        }
    }
}
