package org.kodein.di.inara

import org.kodein.di.inara.DIFactory.Suspend
import kotlin.reflect.KClass

/**
 * Marks an interface for `Binding`-backed implementation generation.
 *
 * An interface annotated with `@DIModule` that extends other `@DIModule` interfaces inherits their
 * members - a `Binding` is generated for every member, inherited included - but gets no delegation
 * API: pass `composed = true` to also generate an implementation/builder/`copy<Module>` set that takes
 * already-built parent module instances.
 *
 * A direct `@DIModule` supertype marked [Required] is one the module needs but does not provide: the
 * module is then *partial*, and additionally gets a generated `<Module>Part` class - the bindings of
 * every member it does provide, without the interface's own implementation - that a
 * `@DIModule(composed = true)` module takes in place of a built instance of it, next to a parent that
 * provides what it requires. See [Required].
 *
 * @param composed Additionally requests a builder/`copy<Module>` set for this interface's `@DIModule`
 *   parents: a builder taking one already-built parent per direct `@DIModule` supertype - a module
 *   instance, or the `<Parent>Part` of a partial parent - plus a binding per member declared directly
 *   on this interface, and a matching `copy<Module>(...)` overload that swaps a whole parent out at
 *   once. Every interface reachable through the parents must be provided by exactly one of them,
 *   whenever a partial parent requires it or more than one parent reaches it. Requires the interface
 *   to extend at least one `@DIModule` interface. On a partial module, it also adds a `<Module>Part`
 *   constructor taking the already-built parents the module provides.
 * @param sets Annotation classes annotated with [DISet], each one generating a
 *   `fun <Module>.setOf<Annotation>(): Set<Of>` extension that collects every member carrying that
 *   annotation - see [DISet]. Called on any module extending this one, it answers with *that*
 *   module's members, so a module may list a set none of its own members belongs to, as the place
 *   its descendants' contributions are gathered from. An annotation may not be listed twice, two
 *   sets may not share a simple name, and no member of the module may already be named
 *   `setOf<Annotation>`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class DIModule(
    val composed: Boolean = false,
    val sets: Array<KClass<out Annotation>> = [],
) {
    /**
     * Marks a direct `@DIModule` supertype of a `@DIModule` interface as *required* rather than
     * provided: the module needs that supertype's members - its bindings may read them through their
     * receiver - but leaves binding them to whichever `@DIModule(composed = true)` module composes it.
     *
     * ```kotlin
     * @DIModule
     * interface FlywayModule : @DIModule.Required JdbcModule {
     *     val flyway: Flyway
     * }
     *
     * @DIModule(composed = true)
     * interface AppModule : JdbcModule, FlywayModule
     *
     * val app = AppModule(
     *     jdbcModule = JdbcModule(dataSource = Binding.single { HikariDataSource() }),
     *     flywayModule = FlywayModulePart(flyway = Binding.single { Flyway(dataSource) }),
     * )
     * ```
     *
     * A module with a required supertype gets a `<Module>Part` class next to its usual generated
     * code: built from a binding per member it provides, it is what a composed module takes for it.
     * Its bindings are copied into every module it is composed into, so that each one builds its own
     * `single`s against its own required members. The module keeps its full builder, binding every
     * member - required ones included - which builds a complete, standalone module.
     *
     * Only meaningful on a direct supertype of a `@DIModule` interface, which must itself be a
     * `@DIModule`; it is ignored anywhere else. No interface reachable through a required supertype
     * may also be reachable through a supertype the module provides.
     */
    @Target(AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.BINARY)
    public annotation class Required
}

/**
 * Marks a `public` or `internal` object for generating a plain constructor-shaped factory function -
 * the way to build a class that never references Inara at all from a `@DIModule` instance.
 *
 * The processor generates an extension `fun <Factory>.<Builds>(module: <From>): <Builds>` on the
 * annotated object, resolving each argument of [builds]'s selected constructor (see
 * [constructorIndex]) off [from], in priority order: an `arg<Argument>` extension function declared
 * on the annotated object (`fun <From>.argFoo(): T`, for a constructor argument named `foo`) if
 * present, else - when [with] / [withNullable] is set - the call-time value itself if its type fits
 * the argument, else - if the parameter can be left out of the call, i.e. it declares a default
 * value or is a `vararg` - that default, else a uniquely-typed member of [from] (an exact type
 * match, else a subtype match), else the argument becomes a parameter of the generated function
 * itself - named `arg<Argument>` (e.g. `argFoo` for a constructor argument named `foo`, exactly like
 * the `arg<Argument>` extension function it would otherwise need) and typed after the constructor
 * parameter, in constructor declaration order, after `module` and `with` - passed straight through
 * to the constructor. The `arg` prefix makes such a pass-through parameter unmistakable by name
 * alone, and never collides with the literal `module` or `with`. The call-time value thus outranks a
 * parameter's default and any [from] member; the default in turn outranks any [from] member, and a
 * defaulted parameter needs no `arg` function and never becomes such a pass-through parameter (a
 * default is a deliberate, local statement of intent - adding one to a constructor parameter that
 * used to be unresolvable therefore removes a parameter from the generated function). The object may
 * declare nothing but such `arg` functions - anything else is a compile error.
 *
 * The generated function has the same visibility as the object. A `public` object whose [from],
 * [builds], selected constructor, [with] / [withNullable] type, or any pass-through parameter's type
 * is `internal` is a compile error (that signature couldn't compile - make the object `internal`,
 * or the type `public`), and every `arg<Argument>` function on a `public` object must be `internal`
 * (they are implementation detail, not public API). A pass-through parameter whose type is
 * `internal` in a *different* module is always a compile error, regardless of the object's own
 * visibility - such a type could never be named from generated code at all.
 *
 * `@DIFactory` is `@Repeatable`: annotate one object several times to generate several functions,
 * all in one file named after the object. An `arg<Argument>` function is shared across the
 * annotations - it serves every `@DIFactory` whose [from] is its extension receiver and whose
 * selected constructor has a matching parameter, and is a compile error only if it fits none. Two
 * annotations that would generate the same signature - same [builds], [from], [with] type, and
 * pass-through parameter types - are a compile error.
 *
 * With [with] (or [withNullable]) set to a type other than `Unit`, the generated function takes a
 * second parameter `fun <Factory>.<Builds>(module: <From>, with: <With>): <Builds>` - nullable under
 * [withNullable] - that value resolves any constructor argument its type fits (ahead of the
 * argument's default and the module), is passed to every `arg<Argument>` function (each of which
 * must then take exactly one parameter of that type), and is passed to any member of [from] taking a
 * single castable parameter, such one-parameter members joining the zero-parameter ones in the
 * automatic-resolution candidate pool. Setting both [with] and [withNullable] is a compile error.
 * See [with].
 *
 * @param builds The class to construct. Must be a concrete (non-abstract, non-`sealed`) class with
 *   the constructor selected by [constructorIndex] public or internal, itself public or internal.
 * @param from The `@DIModule` interface the generated function's `module` parameter is built from.
 * @param with The type of a call-time argument the generated function takes alongside `module`, for
 *   a value that isn't knowable at module level (a request context, a screen route, ...). `Unit`
 *   (the default) generates no such parameter - the function stays `fun <Builds>(module: <From>)`
 *   and `arg<Argument>` functions take nothing, exactly as if this argument didn't exist. Any other
 *   type adds a `with: <With>` parameter: a constructor argument whose type it is assignable to
 *   resolves to it directly (ahead of that argument's own default and any [from] member, so it
 *   shadows a same-typed member - a `vararg` is never resolved this way), every `arg<Argument>`
 *   function on the annotated object must then declare exactly one parameter of that type (no
 *   parameter, or another type, is a compile error), and a member of [from] taking a single
 *   parameter the `with` value casts into - or a `vararg` whose array type it is - becomes eligible
 *   for automatic resolution on equal footing with a zero-parameter member. Use [withNullable]
 *   instead for a nullable parameter.
 * @param withNullable Exactly [with], except the generated parameter's type is nullable (`with:
 *   <With>?`): it resolves only a constructor argument that is itself nullable, every `arg<Argument>`
 *   function must then declare its parameter as `<With>?`, and only a [from] member whose single
 *   parameter accepts `null` is an automatic-resolution candidate (a `vararg` never is - its array
 *   type is never nullable). `Unit` (the default) means unset, exactly as for [with] - it is not a
 *   `Unit?` parameter. Setting both [with] and [withNullable] is a compile error.
 * @param suspend Whether a `suspend` provider - a `suspend` `arg` function, or a `suspend` member
 *   of [from] - may be used to resolve an argument, and whether the generated function itself is
 *   `suspend`. See [Suspend]. Under [Suspend.Forbid], an argument whose only candidate is `suspend`
 *   doesn't error - it simply falls through to a pass-through parameter, like any other argument
 *   nothing else resolves.
 * @param constructorIndex Which of [builds]'s constructors the generated function calls, in
 *   declaration order: `0` (the default) is the primary constructor, `1` the first secondary
 *   constructor, `2` the second, and so on. The selected constructor's parameters are what
 *   `arg<Argument>` functions and automatic module-member matching resolve against - every other
 *   constructor is ignored entirely.
 */
@Repeatable
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class DIFactory(
    val builds: KClass<*>,
    val from: KClass<*>,
    val with: KClass<*> = Unit::class,
    val withNullable: KClass<*> = Unit::class,
    val suspend: Suspend = Suspend.Allow,
    val constructorIndex: Int = 0,
) {
    /**
     * How [DIFactory] treats a `suspend` provider for one of [builds]'s constructor arguments.
     */
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
 * Turns the annotation class it marks into a *set annotation*: a `@DIModule` listing it in
 * [DIModule.sets] gets a generated `fun <Module>.setOf<Annotation>(): Set<Of>` extension (named
 * after the annotation's simple name, as visible as the module) returning the value of every member
 * that carries the annotation, or whose overridden declaration does - inherited members included,
 * in declaration order. A `null` value is left out of the set.
 *
 * The function answers for the module it is actually called on: called on a module extending the
 * one listing the set - typed as the latter or not - it collects the members of the actual module,
 * own and inherited alike, whichever library they come from. A hand-written implementation of the
 * module only answers with the members of the module listing the set.
 *
 * ```kotlin
 * @DISet(of = AppService::class)
 * annotation class AppServices
 *
 * @DIModule(sets = [AppServices::class])
 * interface AppModule {
 *     @AppServices val server: Server
 *     @AppServices fun database(): Database? = null
 * }
 *
 * val services: Set<AppService> = appModule.setOfAppServices()
 * ```
 *
 * The annotation may not have a `SOURCE` retention, and its `@Target`, when declared, must include
 * `FUNCTION` or `PROPERTY`. It may only annotate a non-private function or property of an
 * interface, and such a member must be one Inara binds (not a `var`, an extension member or a
 * generic function), callable without arguments (a `val`, or a function whose every parameter is
 * `vararg` or has a default value), and typed as a subtype of [of] (nullable or not).
 *
 * @param of The element type of the generated `Set`. A generic type is star-projected
 *   (`Set<Plugin<*>>`). It must be `public` or `internal` - `public` for a `public` module.
 * @param suspend Whether the generated function is `suspend`. When `false`, the default, no
 *   `suspend` member may carry the annotation. It is decided by the set alone, never by the members
 *   it collects: the function is declared once, where the set is listed, but answers with members
 *   that only the modules extending it know.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class DISet(
    val of: KClass<*>,
    val suspend: Boolean = false,
)

/**
 * Opts into the parts of Inara's public API that exist only to support generated code -
 * [BindingImpls], [SuspendBindingImpls] and their public members, the `Inara<...>` module
 * implementation classes the processor emits, the binding-holding properties of the
 * `<Module>Part` classes it emits for partial modules, and the signature checks of modules extending
 * modules of other libraries ([InaraGenerated], [InaraSignatures] and the `inaraSignatureOf<Module>`
 * properties) - and are not meant to be used directly.
 */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalInaraGeneration
