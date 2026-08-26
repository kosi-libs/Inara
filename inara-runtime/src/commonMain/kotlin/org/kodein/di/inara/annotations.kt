package org.kodein.di.inara

/**
 * Marks an interface for `Binding`-backed implementation generation.
 *
 * An interface annotated with `@DIModule` that extends other `@DIModule` interfaces inherits their
 * members - a `Binding` is generated for every member, inherited included - but gets no delegation
 * API: annotate it with [ComposedDIModule] instead to also generate an implementation/builder/`copy`
 * set that takes already-built parent module instances.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class DIModule

/**
 * Marks an interface for `Binding`-backed implementation generation, same as [DIModule], and
 * additionally requests a delegated implementation/builder/`copy` set for its `@DIModule` parents:
 * a builder taking one already-built parent module instance per direct `@DIModule` supertype
 * (implemented via Kotlin interface delegation, `Parent by parent`) plus a binding per member
 * declared directly on this interface, and a matching `copy(...)` overload that swaps a whole
 * parent out at once.
 *
 * `@ComposedDIModule` implies `@DIModule`: an interface annotated with both behaves exactly as one
 * annotated only with `@ComposedDIModule`. Requires the interface to extend at least one `@DIModule`
 * interface.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ComposedDIModule

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalDIBindings
