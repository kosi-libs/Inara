package org.kodein.di.inara

/**
 * Carried by every generated `Inara<Module>` class: what its code was generated against, so that the
 * processor of a module reading it - a `@DIModule(composed = true)` child, compiled later and
 * elsewhere - can tell whether it is still up to date.
 *
 * [format] is the version of the code Inara generates. [dependencies] holds one entry per `@DIModule`
 * the module extends that came from another compilation (a library): its qualified name, a line
 * break, then the signature of that module as seen when this one was compiled.
 *
 * Not for direct use - see [InternalInaraGeneration].
 */
@InternalInaraGeneration
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class InaraGenerated(
    val format: Int,
    val dependencies: Array<String> = [],
)

/**
 * The startup check of the code generated for a module extending `@DIModule`s of other libraries:
 * each one exposes the signature hash of the code it was compiled from, which must still be the one
 * the module was compiled against.
 *
 * Not for direct use - see [InternalInaraGeneration].
 */
@InternalInaraGeneration
public object InaraSignatures {
    /**
     * Throws when [actual], the signature hash the module [dependency] is built with, isn't
     * [expected], the one the module [module] was compiled against.
     */
    public fun check(module: String, dependency: String, expected: String, actual: String) {
        if (expected != actual) {
            throw IllegalStateException(
                "'$module' was compiled against another version of '$dependency' than the one it runs with: " +
                    "recompile the library declaring '$module' against it.",
            )
        }
    }
}
