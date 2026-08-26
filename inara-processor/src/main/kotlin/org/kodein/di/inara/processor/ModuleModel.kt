package org.kodein.di.inara.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

internal const val DI_MODULE_ANNOTATION = "org.kodein.di.inara.DIModule"

/**
 * A single member of a `@DIModule` interface that will be backed by a `Binding`.
 *
 * A `val` property is modeled as a function with no parameter: [paramName] and [argType]
 * are null, and the corresponding `Binding`'s argument type is `Unit`.
 *
 * [hasDefaultImplementation] is true when the member carries a body in the interface (a default
 * property getter or function body). Its `Binding` parameter is then optional and nullable:
 * `null` means the interface's own default implementation is kept, any other value overrides it.
 */
internal data class MemberModel(
    val name: String,
    val isFunction: Boolean,
    val isSuspend: Boolean,
    val paramName: String?,
    val argType: KSType?,
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
 * [ownMembers] are the members declared directly on this interface; [allMembers] is the
 * consolidated closure of every `@DIModule` ancestor's members followed by [ownMembers], with no
 * two members sharing a name (see [parseModule]). A module with no `@DIModule` parents has
 * `allMembers == ownMembers` and an empty [ancestorDeclarations].
 *
 * [ancestorDeclarations] is every `@DIModule` interface transitively reachable through [parents]
 * (deduplicated, [parents] included) - used only so the generator can register each of their
 * source files as an incremental-build dependency of the generated code.
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

private fun KSClassDeclaration.isDIModule(): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == DI_MODULE_ANNOTATION }

/**
 * The direct `@DIModule` supertypes of this interface. A supertype that isn't a `@DIModule`
 * interface (including a plain `kotlin.Any`) is silently omitted here - [parseModule] is
 * responsible for rejecting those.
 */
private fun KSClassDeclaration.diModuleParents(): List<KSClassDeclaration> =
    superTypes.mapNotNull { (it.resolve().declaration as? KSClassDeclaration)?.takeIf { d -> d.isDIModule() } }.toList()

private fun propertyMemberModel(decl: KSPropertyDeclaration, hasDefault: Boolean): MemberModel = MemberModel(
    name = decl.simpleName.asString(),
    isFunction = false,
    isSuspend = false,
    paramName = null,
    argType = null,
    returnType = decl.type.resolve(),
    hasDefaultImplementation = hasDefault,
)

private fun functionMemberModel(decl: KSFunctionDeclaration, hasDefault: Boolean): MemberModel {
    val param = decl.parameters.singleOrNull()
    return MemberModel(
        name = decl.simpleName.asString(),
        isFunction = true,
        isSuspend = Modifier.SUSPEND in decl.modifiers,
        paramName = param?.name?.asString(),
        argType = param?.type?.resolve(),
        returnType = decl.returnType!!.resolve(),
        hasDefaultImplementation = hasDefault,
    )
}

/** A [MemberModel] paired with the interface that actually declares it, for diagnostics. */
private data class ClosureMember(val member: MemberModel, val owner: KSClassDeclaration)

/**
 * The full member closure of [classDecl]: every ancestor `@DIModule`'s members (parents first, in
 * declaration order, each parent's own ancestors before its own members), followed by [classDecl]'s
 * own members. No validation is performed here - an ancestor's members are only ever read this way
 * after that ancestor has itself been successfully compiled (in this round or a previous one), so
 * they are already known-valid.
 */
private fun collectAllMembers(classDecl: KSClassDeclaration): List<ClosureMember> {
    val result = mutableListOf<ClosureMember>()
    for (parent in classDecl.diModuleParents()) {
        result += collectAllMembers(parent)
    }
    for (decl in classDecl.declarations) {
        when (decl) {
            is KSPropertyDeclaration -> result += ClosureMember(propertyMemberModel(decl, !decl.isAbstract()), classDecl)
            is KSFunctionDeclaration -> result += ClosureMember(functionMemberModel(decl, !decl.isAbstract), classDecl)
            else -> Unit
        }
    }
    return result
}

/**
 * Every `@DIModule` interface reachable from [classDecl] through [diModuleParents], including
 * [classDecl] itself, keyed by qualified name. Used to detect diamond inheritance: if two of
 * [classDecl]'s direct parents both reach the same ancestor, the same qualified name shows up in
 * both closures.
 */
private fun diModuleClosure(classDecl: KSClassDeclaration): Map<String, KSClassDeclaration> {
    val result = LinkedHashMap<String, KSClassDeclaration>()
    result[classDecl.qualifiedName!!.asString()] = classDecl
    for (parent in classDecl.diModuleParents()) {
        for ((qualifiedName, decl) in diModuleClosure(parent)) {
            result.putIfAbsent(qualifiedName, decl)
        }
    }
    return result
}

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
        reportError("Only interfaces may be annotated with @DIModule.", classDecl)
        return null
    }

    if (classDecl.typeParameters.isNotEmpty()) {
        reportError("A @DIModule interface may not have type parameters.", classDecl)
    }

    // Resolve and validate direct supertypes. A supertype that isn't a @DIModule interface makes
    // the rest of this function unsafe to run (there is nothing sensible to walk), so bail out
    // immediately after reporting every such supertype - the errors already reached `logger`.
    var parentsValid = true
    val parentDecls = mutableListOf<KSClassDeclaration>()
    for (superType in classDecl.superTypes) {
        val resolved = superType.resolve().declaration
        if (resolved.qualifiedName?.asString() == "kotlin.Any") continue
        val parentClass = (resolved as? KSClassDeclaration)?.takeIf { it.isDIModule() }
        if (parentClass == null) {
            reportError("A @DIModule interface may only extend other @DIModule interfaces.", superType)
            parentsValid = false
            continue
        }
        parentDecls += parentClass
    }
    if (!parentsValid) return null

    // Diamond inheritance: two direct parents that both reach the same @DIModule ancestor. Kotlin
    // would need an explicit override for that ancestor's members in the delegated implementation,
    // which the generator does not attempt, so this is rejected outright.
    if (parentDecls.size > 1) {
        val closures = parentDecls.map { it to diModuleClosure(it) }
        for (i in closures.indices) {
            for (j in i + 1 until closures.size) {
                val (parentA, closureA) = closures[i]
                val (parentB, closureB) = closures[j]
                for (qualifiedName in closureA.keys intersect closureB.keys) {
                    val shared = closureA.getValue(qualifiedName)
                    reportError(
                        "@DIModule interface '${shared.simpleName.asString()}' is inherited more than once, " +
                            "via '${parentA.simpleName.asString()}' and '${parentB.simpleName.asString()}'.",
                        classDecl,
                    )
                }
            }
        }
    }

    // `nameOwners` tracks, for every member name seen so far (inherited or own), the interface
    // that declares it - `classDecl` itself for an own member, an ancestor for an inherited one.
    // It is what turns `override val a` on an inherited member into the same diagnostic as any
    // other name clash: by the time the own `a` is parsed, `nameOwners["a"]` already points at the
    // parent that declared it.
    val nameOwners = mutableMapOf<String, KSClassDeclaration>()
    val parentModels = mutableListOf<ParentModel>()
    val allMembers = mutableListOf<MemberModel>()

    for (parent in parentDecls) {
        val paramName = parent.generatedBaseName().replaceFirstChar(Char::lowercaseChar)
        parentModels.find { it.paramName == paramName }?.let { clashing ->
            reportError(
                "Parent modules '${clashing.declaration.simpleName.asString()}' and " +
                    "'${parent.simpleName.asString()}' would both bind to the parameter name '$paramName'.",
                classDecl,
            )
        }
        parentModels += ParentModel(parent, parent.toClassName(), paramName)

        for (closureMember in collectAllMembers(parent)) {
            val existingOwner = nameOwners[closureMember.member.name]
            if (existingOwner != null) {
                reportError(
                    "A member named '${closureMember.member.name}' is already declared in " +
                        "'${existingOwner.simpleName.asString()}'.",
                    classDecl,
                )
                continue
            }
            nameOwners[closureMember.member.name] = closureMember.owner
            allMembers += closureMember.member
        }
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

    val ownMembers = mutableListOf<MemberModel>()

    for (decl in classDecl.declarations) {
        when (decl) {
            is KSPropertyDeclaration -> {
                val hasDefault = !decl.isAbstract()
                val name = decl.simpleName.asString()
                var valid = claimName(name, decl)

                if (name.endsWith("Binding")) {
                    reportError("A @DIModule member name may not end with 'Binding': '$name'.", decl)
                    valid = false
                }
                if (name == "copy") {
                    reportError("A @DIModule member may not be named 'copy'.", decl)
                    valid = false
                }
                if (decl.isMutable) {
                    reportError("var properties are not supported: '$name'.", decl)
                    valid = false
                }
                if (decl.extensionReceiver != null) {
                    reportError("Extension members are not supported: '$name'.", decl)
                    valid = false
                }
                if (decl.getVisibility() != Visibility.PUBLIC) {
                    reportError("Non-public members are not supported: '$name'.", decl)
                    valid = false
                }

                if (!valid) continue
                val member = propertyMemberModel(decl, hasDefault)
                ownMembers += member
                allMembers += member
            }

            is KSFunctionDeclaration -> {
                val hasDefault = !decl.isAbstract
                val name = decl.simpleName.asString()
                var valid = claimName(name, decl)

                if (name.endsWith("Binding")) {
                    reportError("A @DIModule member name may not end with 'Binding': '$name'.", decl)
                    valid = false
                }
                if (name == "copy") {
                    reportError("A @DIModule member may not be named 'copy'.", decl)
                    valid = false
                }
                if (decl.extensionReceiver != null) {
                    reportError("Extension members are not supported: '$name'.", decl)
                    valid = false
                }
                if (decl.typeParameters.isNotEmpty()) {
                    reportError("Generic members are not supported: '$name'.", decl)
                    valid = false
                }
                if (decl.getVisibility() != Visibility.PUBLIC) {
                    reportError("Non-public members are not supported: '$name'.", decl)
                    valid = false
                }
                if (decl.parameters.size > 1) {
                    reportError("Members with more than one parameter are not supported: '$name'.", decl)
                    valid = false
                }
                val param = decl.parameters.singleOrNull()
                if (param != null && param.isVararg) {
                    reportError("vararg parameters are not supported: '$name'.", decl)
                    valid = false
                }

                if (!valid) continue
                val member = functionMemberModel(decl, hasDefault)
                ownMembers += member
                allMembers += member
            }

            else -> Unit
        }
    }

    // A @DIModule parent's parameter name may not collide with any member name in the
    // consolidated interface (checked last, since own member names are only known by now).
    for (parentModel in parentModels) {
        if (parentModel.paramName in nameOwners) {
            reportError(
                "Parent module '${parentModel.declaration.simpleName.asString()}' binds to the parameter name " +
                    "'${parentModel.paramName}', which collides with a member name.",
                classDecl,
            )
        }
    }

    if (hasError) return null

    val ancestorDeclarations = LinkedHashMap<String, KSClassDeclaration>().apply {
        for (parent in parentDecls) {
            for ((qualifiedName, decl) in diModuleClosure(parent)) {
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
    )
}
