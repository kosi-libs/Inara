package org.kodein.di.inara

/**
 * Implemented by every generated `Inara<Module>` class whose module can be the receiver of a
 * generated `setOf<Annotation>()` function - one listing a set, or extending a module that does. The
 * function asks the module it is called on for the set, so that it answers with the members of the
 * actual module rather than those of the type it is declared on.
 *
 * [set] is the qualified name of the set annotation. Each function answers `null` for a set the
 * module doesn't know, and only the one matching the set's `suspend`-ness is generated.
 *
 * Not for direct use - see [InternalInaraGeneration].
 */
@InternalInaraGeneration
public interface InaraSets {
    /** The members of a non-`suspend` set (see [DISet.suspend]), or `null`. */
    public fun inaraSetOf(set: String): Set<*>? = null

    /** The members of a `suspend` set (see [DISet.suspend]), or `null`. */
    public suspend fun inaraSuspendSetOf(set: String): Set<*>? = null
}
