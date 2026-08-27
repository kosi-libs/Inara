package org.kodein.di.inara

import kotlin.reflect.KClass

/**
 * Marks an interface for `Binding`-backed implementation generation.
 *
 * An interface annotated with `@DIModule` that extends other `@DIModule` interfaces inherits their
 * members - a `Binding` is generated for every member, inherited included - but gets no delegation
 * API: pass `composed = true` to also generate an implementation/builder/`copy` set that takes
 * already-built parent module instances.
 *
 * @param composed Additionally requests a delegated implementation/builder/`copy` set for this
 *   interface's `@DIModule` parents: a builder taking one already-built parent module instance per
 *   direct `@DIModule` supertype (implemented via Kotlin interface delegation, `Parent by parent`)
 *   plus a binding per member declared directly on this interface, and a matching `copy(...)`
 *   overload that swaps a whole parent out at once. Requires the interface to extend at least one
 *   `@DIModule` interface.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class DIModule(val composed: Boolean = false)

/**
 * Marks an `internal object` for generating a plain constructor-shaped factory function - the way
 * to build a class that never references Inara at all from a `@DIModule` instance.
 *
 * The processor generates a top-level `fun <Builds>(module: <From>): <Builds>` resolving each
 * argument of [builds]'s selected constructor (see [constructorIndex]) off [from], in priority
 * order: an `arg<Argument>` extension function declared on the annotated object (`fun
 * <From>.argFoo(): T`, for a constructor argument named `foo`) if present, else a uniquely-typed
 * member of [from] (an exact type match, else a subtype match), else a compile error recommending
 * the `arg` function to add. The object may declare nothing but such `arg` functions - anything else
 * is a compile error.
 *
 * @param builds The class to construct. Must be a concrete (non-abstract, non-`sealed`) class with
 *   the constructor selected by [constructorIndex] public or internal, itself public or internal.
 * @param from The `@DIModule` interface the generated function's `module` parameter is built from.
 * @param suspend Whether a `suspend` provider - a `suspend` `arg` function, or a `suspend` member
 *   of [from] - may be used to resolve an argument, and whether the generated function itself is
 *   `suspend`. See [Suspend].
 * @param constructorIndex Which of [builds]'s constructors the generated function calls, in
 *   declaration order: `0` (the default) is the primary constructor, `1` the first secondary
 *   constructor, `2` the second, and so on. The selected constructor's parameters are what
 *   `arg<Argument>` functions and automatic module-member matching resolve against - every other
 *   constructor is ignored entirely.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class DIFactory(
    val builds: KClass<*>,
    val from: KClass<*>,
    val suspend: Suspend = Suspend.Allow,
    val constructorIndex: Int = 0,
) {
    /** How [DIFactory] treats a `suspend` provider for one of [builds]'s constructor arguments. */
    public enum class Suspend {
        /** No provider - `arg` function or module member - may be `suspend`; the generated
         * function never is. A `suspend`-only candidate is treated as no candidate at all. */
        Forbid,

        /** A `suspend` provider is a valid candidate, on equal footing with a non-`suspend` one.
         * The generated function is `suspend` if and only if at least one argument actually
         * resolves to a `suspend` provider. */
        Allow,

        /** Same resolution as [Allow], but the generated function is always `suspend`, whether or
         * not any resolved provider actually is. */
        Force,
    }
}

/**
 * Opts into the parts of Inara's public API that exist only to support generated code -
 * [BindingImpls], [SuspendBindingImpls], and their public members - and are not meant to be used
 * directly.
 */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalDIBindings
