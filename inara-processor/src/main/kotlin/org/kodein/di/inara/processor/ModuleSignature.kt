package org.kodein.di.inara.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.security.MessageDigest

/**
 * The version of the code Inara generates, as far as the code generated for another module reads
 * it: the `Inara<Module>` class, its `<member>Binding` properties, the `<Module>Part` class, the
 * arguments classes and the `InaraSets` lookups. Bump it whenever one of them changes shape, so that
 * a module compiled by another version of Inara is reported instead of failing in generated code.
 */
internal const val GENERATION_FORMAT = 1

internal const val INARA_GENERATED_ANNOTATION = "org.kodein.di.inara.InaraGenerated"

/**
 * A `@DIModule` that a module extends and that came from another compilation (a library), so it may
 * change without the module being recompiled. [signature] is its [ModuleModel.signature] as seen when
 * the module is compiled; [signatureProperty] is the property its own generated file exposes the
 * hash of its signature with, as it was when *it* was compiled.
 */
internal class ModuleDependency(
    val qualifiedName: String,
    val signature: String,
    val signatureProperty: MemberName,
)

/** The `inaraSignatureOf<Module>` property exposing the signature hash of the module whose flattened
 * base name is [baseName] (see [generatedBaseName]), in [packageName]. */
internal fun signaturePropertyOf(packageName: String, baseName: String): MemberName =
    MemberName(packageName, "inaraSignatureOf$baseName")

/**
 * The signature of [model]: one line per thing the code generated for a module extending it depends
 * on, and that [model]'s own declaration decides - its `@DIModule` arguments, its `@DIModule`
 * supertypes, and every member it introduces (its own and those of its plain supertypes), sorted so
 * that declaration order doesn't matter. Members inherited from a `@DIModule` parent are left to that
 * parent's own signature.
 *
 * Each member line starts with `member <name> `, which [describeSignatureChanges] reads.
 */
internal fun moduleSignature(model: ModuleModel): String = buildList {
    add("inara $GENERATION_FORMAT")
    add("module ${model.moduleClassName.canonicalName}")
    if (model.isComposed) add("composed")
    model.sets.filter { it.isListed }.map { it.qualifiedName }.sorted().forEach { add("lists $it") }
    model.parents.map { "parent ${it.moduleClassName.canonicalName}" + if (it.isRequired) " required" else "" }.sorted().forEach(::add)
    model.allMembers.filter { it.name !in model.parentByMemberName }.sortedBy { it.name }.forEach { add(memberSignatureLine(it)) }
}.joinToString("\n")

private fun memberSignatureLine(member: MemberModel): String = buildString {
    append("member ").append(member.name).append(' ')
    if (member.isSuspend) append("suspend ")
    if (member.isFunction) {
        append("fun(")
        append(member.parameters.joinToString(", ") { (if (it.isVararg) "vararg " else "") + "${it.name}: ${it.type.toTypeName()}" })
        append("): ")
    } else {
        append("val: ")
    }
    append(member.returnType.toTypeName())
    if (member.hasDefaultImplementation) append(" default")
    for (set in member.sets.sorted()) append(" @").append(set)
}

/** The hash [signature] is checked by at runtime: the first 64 bits of its SHA-256, in hexadecimal. */
internal fun signatureHash(signature: String): String =
    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

/**
 * What changed between the [recorded] signature of a module and its [current] one, for a diagnostic:
 * `member 'a' added, member 'b' removed, supertypes changed`. Empty when only the [GENERATION_FORMAT]
 * line differs.
 */
internal fun describeSignatureChanges(recorded: String, current: String): String {
    fun members(signature: String) = signature.lines().filter { it.startsWith("member ") }
        .associateBy { it.removePrefix("member ").substringBefore(' ') }
    fun lines(signature: String, prefix: String) = signature.lines().filter { it.startsWith(prefix) }.toSet()
    val before = members(recorded)
    val after = members(current)
    return buildList {
        after.keys.filter { it !in before }.forEach { add("member '$it' added") }
        before.keys.filter { it !in after }.forEach { add("member '$it' removed") }
        after.keys.filter { it in before && before[it] != after[it] }.forEach { add("member '$it' changed") }
        if (lines(recorded, "parent ") != lines(current, "parent ")) add("supertypes changed")
        if (lines(recorded, "composed") != lines(current, "composed")) add("'composed' changed")
        if (lines(recorded, "lists ") != lines(current, "lists ")) add("sets changed")
    }.joinToString(", ")
}

/**
 * Reports every `@DIModule` that a `@DIModule(composed = true)` module's generated code reads the
 * generated code of, and that is out of date: compiled by another version of Inara, or against another
 * version of a module it extends than the one the module is now compiled against. Its generated code
 * would otherwise fail to compile, or miss members, with no hint of the cause.
 *
 * The modules read are the composed module's direct `@DIModule` parents that come from another
 * compilation, and - through a `Part` built from parents (see [ModuleModel.hasPartFromParents]) -
 * that `Part`'s own provided parents, recursively. A module of this compilation is always up to date.
 */
internal fun checkReadModulesUpToDate(
    classDecl: KSClassDeclaration,
    parents: List<ParentModel>,
    resolver: Resolver,
    currentSignature: (KSClassDeclaration) -> String?,
    reportError: (String, KSNode) -> Unit,
) {
    val checked = HashSet<String>()
    fun check(parent: ParentModel) {
        if (!checked.add(parent.moduleClassName.canonicalName)) return
        if (parent.declaration.containingFile == null) checkUpToDate(classDecl, parent, resolver, currentSignature, reportError)
        val model = parent.model ?: return
        if (parent.partClassName != null && model.hasPartFromParents) {
            model.parents.filter { !it.isRequired }.forEach(::check)
        }
    }
    parents.forEach(::check)
}

private fun checkUpToDate(
    classDecl: KSClassDeclaration,
    parent: ParentModel,
    resolver: Resolver,
    currentSignature: (KSClassDeclaration) -> String?,
    reportError: (String, KSNode) -> Unit,
) {
    val parentName = parent.declaration.simpleName.asString()
    val generated = resolver.getClassDeclarationByName(resolver.getKSNameFromString(parent.generatedClassName.canonicalName))
    if (generated == null) {
        reportError(
            "'$parentName' has no code generated by Inara: the library declaring it must run the Inara processor.",
            classDecl,
        )
        return
    }
    val annotation = generated.currentInaraGenerated()
    if (annotation == null) {
        reportError(
            "'$parentName' was compiled by another version of Inara: recompile the library declaring it with this one.",
            classDecl,
        )
        return
    }
    @Suppress("UNCHECKED_CAST")
    val dependencies = (annotation.argumentValue("dependencies") as? List<String>).orEmpty()
    for (dependency in dependencies) {
        val qualifiedName = dependency.substringBefore('\n')
        val recorded = dependency.substringAfter('\n')
        val simpleName = qualifiedName.substringAfterLast('.')
        val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualifiedName))
        val changes = when {
            decl == null -> "it no longer exists"
            !decl.isDIModule() -> "it is no longer a @DIModule"
            else -> {
                val current = currentSignature(decl) ?: continue
                if (current == recorded) continue
                describeSignatureChanges(recorded, current)
            }
        }
        reportError(
            "'$parentName' was compiled against another version of '$simpleName'" +
                (if (changes.isEmpty()) "" else " ($changes)") +
                ": recompile the library declaring '$parentName' against the current '$simpleName'.",
            classDecl,
        )
    }
}

/** The `@InaraGenerated` annotation of this generated class, when it was generated in the current
 * [GENERATION_FORMAT]. */
private fun KSClassDeclaration.currentInaraGenerated(): KSAnnotation? =
    annotations.firstOrNull { it.isAnnotation(INARA_GENERATED_ANNOTATION) }
        ?.takeIf { (it.argumentValue("format") as? Int) == GENERATION_FORMAT }

/**
 * Whether the library module [decl] has generated code in the current [GENERATION_FORMAT], which
 * exposes its signature hash. Only such a module is checked at startup: one compiled by another
 * version of Inara - or without it - may not expose it, and a module that doesn't read its generated
 * code still works with it (one that does is reported by [checkReadModulesUpToDate]).
 */
internal fun hasCurrentGeneratedCode(decl: KSClassDeclaration, resolver: Resolver): Boolean {
    val name = "${decl.packageName.asString()}.${inaraName(decl.generatedBaseName())}"
    return resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))?.currentInaraGenerated() != null
}
