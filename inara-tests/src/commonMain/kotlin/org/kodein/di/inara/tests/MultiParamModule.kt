package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Exercises a function with more than one parameter, and a `vararg` parameter, through real
 * Gradle/KSP-generated code. `mult` has two plain parameters, wrapped into a generated
 * `MultiParamModuleBindingArguments.Mult` data class; `va` has a single `vararg` parameter, whose
 * `Binding` argument type is the array type directly (no data class); `both` mixes a `vararg` with
 * a defaulted named parameter, also wrapped into a data class.
 */
@DIModule
public interface MultiParamModule {
    public fun va(vararg str: String): String
    public fun mult(s: List<String>, i: Int): Long
    public fun both(vararg ids: Int, flag: Boolean = false): String
}

/**
 * A `@DIModule(composed = true)` whose parent has multi-parameter members. Their `Binding`'s
 * argument type is a *per-module* `<Module>BindingArguments.<Name>` data class, so the parent's
 * binding can't be handed to the composed implementation's slot - the from-parents builder forwards
 * through a delegate instead. `va` (a lone vararg) has a module-independent array argument type and
 * is carried across like any single-parameter member.
 */
@DIModule(composed = true)
public interface ComposedMultiParamModule : MultiParamModule {
    public val tag: String
}
