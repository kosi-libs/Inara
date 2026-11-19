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
interface MultiParamModule {
    fun va(vararg str: String): String
    fun mult(s: List<String>, i: Int): Long
    fun both(vararg ids: Int, flag: Boolean = false): String
}

/**
 * A `@DIModule(composed = true)` whose parent has multi-parameter members. Their `Binding`'s
 * argument type is the `MultiParamModuleBindingArguments.<Name>` data class of [MultiParamModule],
 * which introduces them - shared by this module, so the from-parents builder carries the parent's
 * binding across as it is, exactly like `va` (a lone vararg, whose argument type is the array type).
 */
@DIModule(composed = true)
interface ComposedMultiParamModule : MultiParamModule {
    val tag: String
}
