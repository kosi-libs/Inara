package org.kodein.di.inara

import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A recipe for producing a [T] on demand, given the module [M] it belongs to and an argument [A].
 *
 * A `@DIModule`-annotated interface's generated builder function takes one `Binding` per member -
 * `M` is always that module's own (leaf) type, so [get]'s body can reach any other member of the
 * module through its `module` receiver. `A` is `Unit` for a member with no parameter.
 *
 * Never implement this directly - build one through the factories on the [Bindings] companion
 * ([value], [provider], [factory], [single], [weakSingle], [multiplex]), which cover every
 * lifecycle Inara supports.
 */
public interface Binding<M, A, T> {
    /** Produces the value for [module] and [arg]. What "produces" means - recomputed on every
     * call, memoized, cached per argument - depends entirely on which [Bindings] factory built
     * this instance. */
    public fun get(module: M, arg: A): T

    /** Every way to build a [Binding]. */
    @OptIn(InternalDIBindings::class)
    public companion object Bindings {
        /** A [Binding] that always returns [value] - nothing is computed, ever. */
        public fun <M, T> value(value: T) : Binding<M, Unit, T> = BindingImpls.Value(value)

        /** A [Binding] that runs [provider] on every [get] call - a fresh result each time. */
        public fun <M, T> provider(provider: M.() -> T) : Binding<M, Unit, T> = BindingImpls.Provider(provider)

        /** A [Binding] that runs [factory] on every [get] call, with the caller's argument - the
         * argument-taking equivalent of [provider]. */
        public fun <M, A, T> factory(factory: M.(A) -> T) : Binding<M, A, T> = BindingImpls.Factory(factory)

        /** How [single] and [weakSingle] guard their one-time build against concurrent callers. */
        public enum class SingleThreadSafety {
            /** [get] blocks under a lock until the first caller's build finishes - the builder
             * runs exactly once, guaranteed. */
            Synchronized,

            /** [get] never blocks: concurrent callers may each run the builder, but all of them
             * converge on the same winning instance - the builder may run more than once under
             * contention, though only one result is ever kept. */
            Published,
        }

        /**
         * A [Binding] that runs [builder] once and memoizes the result forever - every subsequent
         * [get] returns the same instance without re-running [builder].
         *
         * [safety] controls what happens when the first `get` call is itself concurrent - see
         * [SingleThreadSafety].
         */
        public fun <M, T> single(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedSingle(builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedSingle(builder)
        }

        /**
         * Like [single], but the memoized result is held behind a platform weak reference: once
         * nothing else strongly references it, it may be collected, and the next [get] call
         * rebuilds it. Useful for an expensive-but-recreatable value you don't want to keep alive
         * forever just because a module holds a `Binding` to it.
         */
        public fun <M, T : Any> weakSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedWeakSingle(builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedWeakSingle(builder)
        }

        /**
         * A [Binding] that builds and caches one sub-[Binding] per distinct argument value, via
         * [builder] - the way to give an argument-taking member its own lifecycle (typically
         * wrapping [single] or [provider]) instead of the argument-blind [factory].
         */
        public fun <M, A, T> multiplex(builder: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> = BindingImpls.Multiplex(builder)
    }
}

/**
 * Implementation classes backing every [Binding] the [Binding.Bindings] factories produce.
 *
 * Not for direct use - [Delegate] is public only because a generated `copy(...)` function needs it
 * to express "keep the module's current value for this member" as its parameter default; the rest
 * is `internal`. Gated behind [InternalDIBindings] as a signal, not an enforcement mechanism.
 */
@InternalDIBindings
public class BindingImpls {
    internal class Value<M, T>(private val value: T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = value }

    internal class Provider<M, T>(private val provider: M.() -> T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = provider(module) }

    internal class Factory<M, A, T>(private val factory: M.(A) -> T) : Binding<M, A, T> { override fun get(module: M, arg: A): T = factory(module, arg) }

    /** Reads [delegate]'s *current* value off the receiver on every [get] - functionally identical
     * to [Factory], but under a name that reads correctly in generated `copy(...)` signatures
     * (`= BindingImpls.Delegate { this@copy.member }`). */
    public class Delegate<M, A, T>(private val delegate: M.(A) -> T) : Binding<M, A, T> { override fun get(module: M, arg: A): T = delegate(module, arg) }

    internal object Uninitialized {
        inline fun <T> Any?.ifInitialized(then: (T) -> Unit) {
            @Suppress("UNCHECKED_CAST")
            if (this !== Uninitialized) then(this as T)
        }
    }

    internal class SynchronizedSingle<M, T>(builder: M.() -> T) : Binding<M, Unit, T> {
        private var builder: (M.() -> T)? = builder
        @Volatile private var value: Any? = Uninitialized
        private val lock = Lock()

        override fun get(module: M, arg: Unit): T {
            value.ifInitialized<T> { return it }
            return locked(lock) {
                value.ifInitialized<T> { return it }
                builder!!(module).also {
                    builder = null
                    value = it
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class PublishedSingle<M, T>(builder: M.() -> T) : Binding<M, Unit, T> {
        @Volatile private var builder: (M.() -> T)? = builder
        private val ref: AtomicReference<Any?> = AtomicReference(Uninitialized)

        override fun get(module: M, arg: Unit): T {
            ref.load().ifInitialized<T> { return it }
            val builder = this.builder
            if (builder != null) {
                builder(module).also {
                    if (ref.compareAndSet(Uninitialized, it)) {
                        this.builder = null
                        return it
                    }
                }
            }
            @Suppress("UNCHECKED_CAST") return ref.load() as T
        }
    }

    internal class SynchronizedWeakSingle<M, T : Any>(val builder: M.() -> T) : Binding<M, Unit, T> {
        @Volatile private var ref: Any? = Uninitialized
        private val lock = Lock()

        override fun get(module: M, arg: Unit): T {
            ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
            return locked(lock) {
                ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
                builder(module).also {
                    ref = WeakRef(it)
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class PublishedWeakSingle<M, T : Any>(val builder: M.() -> T) : Binding<M, Unit, T> {
        private val refRef: AtomicReference<Any?> = AtomicReference(Uninitialized)

        override fun get(module: M, arg: Unit): T {
            while (true) {
                val ref = refRef.load()
                ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
                builder(module).also {
                    if (refRef.compareAndSet(ref, WeakRef(it))) {
                        return it
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class Multiplex<M, A, T>(private val delegate: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> {
        private val bindingsRef: AtomicReference<Map<A, Binding<M, Unit, T>>> = AtomicReference(HashMap())

        override fun get(module: M, arg: A): T {
            while (true) {
                val map = bindingsRef.load()
                val binding = map[arg]
                if (binding != null) {
                    return binding.get(module, Unit)
                }
                delegate(arg).also {
                    val newMap = map + (arg to it)
                    if (bindingsRef.compareAndSet(map, newMap)) {
                        return it.get(module, Unit)
                    }
                }
            }
        }
    }
}
