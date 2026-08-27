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
