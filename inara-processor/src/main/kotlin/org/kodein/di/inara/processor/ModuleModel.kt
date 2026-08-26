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

internal data class ModuleModel(
    val declaration: KSClassDeclaration,
    val moduleClassName: ClassName,
    val simpleName: String,
    val generatedBaseName: String,
    val visibility: Visibility,
    val members: List<MemberModel>,
)

/**
 * The interface's simple name prefixed with every enclosing class/interface/object's simple name,
 * e.g. `OuterInner` for `Outer.Inner`. Used as the base for the generated file/class name so two
 * same-named nested interfaces in different enclosing scopes can't collide.
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

    for (superType in classDecl.superTypes) {
        val resolved = superType.resolve()
        if (resolved.declaration.qualifiedName?.asString() != "kotlin.Any") {
            reportError("A @DIModule interface may not extend other interfaces.", superType)
        }
    }

    if (classDecl.typeParameters.isNotEmpty()) {
        reportError("A @DIModule interface may not have type parameters.", classDecl)
    }

    val members = mutableListOf<MemberModel>()
    val seenNames = mutableSetOf<String>()

    for (decl in classDecl.declarations) {
        when (decl) {
            is KSPropertyDeclaration -> {
                val hasDefault = !decl.isAbstract()
                val name = decl.simpleName.asString()
                var valid = true

                if (name in seenNames) {
                    reportError("Overloading is not supported: multiple members are named '$name'.", decl)
                    valid = false
                }
                seenNames += name

                if (name.endsWith("Binding")) {
                    reportError("A @DIModule member name may not end with 'Binding': '$name'.", decl)
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
                members += MemberModel(
                    name = name,
                    isFunction = false,
                    isSuspend = false,
                    paramName = null,
                    argType = null,
                    returnType = decl.type.resolve(),
                    hasDefaultImplementation = hasDefault,
                )
            }

            is KSFunctionDeclaration -> {
                val hasDefault = !decl.isAbstract
                val name = decl.simpleName.asString()
                var valid = true

                if (name in seenNames) {
                    reportError("Overloading is not supported: multiple members are named '$name'.", decl)
                    valid = false
                }
                seenNames += name

                if (name.endsWith("Binding")) {
                    reportError("A @DIModule member name may not end with 'Binding': '$name'.", decl)
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
                members += MemberModel(
                    name = name,
                    isFunction = true,
                    isSuspend = Modifier.SUSPEND in decl.modifiers,
                    paramName = param?.name?.asString(),
                    argType = param?.type?.resolve(),
                    returnType = decl.returnType!!.resolve(),
                    hasDefaultImplementation = hasDefault,
                )
            }

            else -> Unit
        }
    }

    if (hasError) return null

    return ModuleModel(
        declaration = classDecl,
        moduleClassName = classDecl.toClassName(),
        simpleName = classDecl.simpleName.asString(),
        generatedBaseName = classDecl.generatedBaseName(),
        visibility = classDecl.getVisibility(),
        members = members,
    )
}
