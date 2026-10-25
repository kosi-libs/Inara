package org.kodein.di.inara

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

/**
 * A recipe for producing a [T] on demand, given the module [M] it belongs to and an argument [A].
 *
 * A `@DIModule`-annotated interface's generated builder function takes one `Binding` per member -
 * `M` is always that module's own (leaf) type, so [get]'s body can reach any other member of the
 * module through its `module` receiver. `A` is `Unit` for a member with no parameter.
 *
 * [M] is contravariant: it only ever appears as [get]'s `module` parameter, so a `Binding<Parent,
 * A, T>` is usable wherever a `Binding<Child, A, T>` is expected. A `@DIModule(composed = true)`
 * builder relies on this to hand a parent module's own bindings straight to the composed
 * implementation.
 *
 * Never implement this directly - build one through the factories on the [Bindings] companion
 * ([value], [provider], [factory], [single], [weakSingle], [multiplex]), which cover every
 * lifecycle Inara supports.
 *
 * [A] and [T] are likewise declared `in`/`out` for the same reason - neither ever appears in an
 * invariant position on any implementation this file builds.
 */
public interface Binding<in M, in A, out T> {
    /** Produces the value for [module] and [arg]. What "produces" means - recomputed on every
     * call, memoized, cached per argument - depends entirely on which [Bindings] factory built
     * this instance. */
    public fun get(module: M, arg: A): T

    /** Every way to build a [Binding]. */
    @Suppress("UNCHECKED_CAST")
    @OptIn(InternalInaraGeneration::class)
    public companion object Bindings {
        @PublishedApi
        internal inline fun <reified T> cls(): KClass<T & Any>? = typeOf<T>().classifier as? KClass<T & Any>

        /** A [Binding] that always returns [value] - nothing is computed, ever. */
        public inline fun <M, reified T> value(value: T) : Binding<M, Unit, T> =
            BindingImpls.Value(cls<T>(), value)

        /** A [Binding] that runs [provider] on every [get] call - a fresh result each time. */
        public inline fun <M, reified T> provider(noinline provider: M.() -> T) : Binding<M, Unit, T> =
            BindingImpls.Provider(cls<T>(), provider)

        /** A [Binding] that runs [factory] on every [get] call, with the caller's argument - the
         * argument-taking equivalent of [provider]. */
        public inline fun <M, reified A, reified T> factory(noinline factory: M.(A) -> T) : Binding<M, A, T> =
            BindingImpls.Factory(cls<A>(), cls<T>(), factory)

        /**
         * A [Binding] that runs [builder] once and memoizes the result forever - every subsequent
         * [get] returns the same instance without re-running [builder].
         *
         * [safety] controls what happens when the first `get` call is itself concurrent - see
         * [SingleThreadSafety].
         */
        public inline fun <M, reified T> single(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedSingle(cls<T>(), builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedSingle(cls<T>(), builder)
        }

        /**
         * Like [single], but the memoized result is held behind a platform weak reference: once
         * nothing else strongly references it, it may be collected, and the next [get] call
         * rebuilds it. Useful for an expensive-but-recreatable value you don't want to keep alive
         * forever just because a module holds a `Binding` to it.
         */
        public inline fun <M, reified T : Any> weakSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedWeakSingle(T::class, builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedWeakSingle(T::class, builder)
        }

        /**
         * A [Binding] that builds and caches one sub-[Binding] per distinct argument value, via
         * [builder] - the way to give an argument-taking member its own lifecycle (typically
         * wrapping [single] or [provider]) instead of the argument-blind [factory].
         *
         * The cache is never evicted: every distinct argument keeps its sub-[Binding], and whatever
         * that holds, alive for as long as the module is. Use it only for a small, bounded key space
         * - hosts, tenants, an enum. Keyed on something unbounded - a user id, a request id, a
         * timestamp - it is a memory leak; use [factory] there instead. An argument that is an array,
         * or a generated arguments class with an array field, never compares equal to a fresh one, so
         * every call misses and the cache grows by one entry per call.
         */
        public inline fun <M, reified A, reified T> multiplex(
            noinline builder: (A) -> Binding<M, Unit, T>
        ) : Binding<M, A, T> = BindingImpls.Multiplex(cls<A>(), cls<T>(), builder)
    }
}

/**
 * Implementation classes backing every [Binding] the [Binding.Bindings] factories produce.
 *
 * Not for direct use - [Delegate] is public only because a generated `copy(...)` function needs it
 * to express "keep the module's current value for this member" as its parameter default; the rest
 * is `internal`. Gated behind [InternalInaraGeneration] as a signal, not an enforcement mechanism.
 */
@InternalInaraGeneration
public class BindingImpls {
    @PublishedApi
    internal class Value<in M, out T>(private val cls: KClass<T & Any>?, private val value: T) : Binding<M, Unit, T> {
        override fun get(module: M, arg: Unit): T = value
        override fun toString(): String = "value ( ${cls?.simpleName ?: "?"} )"
    }

    @PublishedApi
    internal class Provider<in M, out T>(private val cls: KClass<T & Any>?, private val provider: M.() -> T) : Binding<M, Unit, T> {
        override fun get(module: M, arg: Unit): T = provider(module)
        override fun toString(): String = "provider { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class Factory<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val factory: M.(A) -> T) : Binding<M, A, T> {
        override fun get(module: M, arg: A): T = factory(module, arg)
        override fun toString(): String = "factory { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }

    /** Reads [delegate]'s *current* value off the receiver on every [get] - functionally identical
     * to [Factory], but under a name that reads correctly in generated `copy(...)` signatures
     * (`= BindingImpls.Delegate(this@copy::class) { this@copy.member }`). [cls] is the receiver's own
     * runtime class - always a hand-written implementation or an inherited 2+-parameter member (see
     * `ModuleFileGenerator.parentBindingCode`) or the receiver of a generated `shadow(...)`, which may be the
     * generated implementation itself - so [toString] can name it. */
    public class Delegate<in M : Any, in A, out T>(private val cls: KClass<out M>, private val delegate: M.(A) -> T) : Binding<M, A, T> {
        override fun get(module: M, arg: A): T = delegate(module, arg)
        override fun toString(): String = "[delegated to ${cls.simpleName ?: "?"}]"
    }

    internal object Uninitialized {
        inline fun <T> Any?.ifInitialized(then: (T) -> Unit) {
            @Suppress("UNCHECKED_CAST")
            if (this !== Uninitialized) then(this as T)
        }
    }

    @PublishedApi
    internal class SynchronizedSingle<in M, out T>(private val cls: KClass<T & Any>?, builder: M.() -> T) : Binding<M, Unit, T> {
        private var builder: (M.() -> T)? = builder
        @Volatile private var value: Any? = Uninitialized
        private val lock = ReentrantLock()
        override fun get(module: M, arg: Unit): T {
            value.ifInitialized<T> { return it }
            return lock.withLock {
                value.ifInitialized<T> { return it }
                builder!!(module).also {
                    builder = null
                    value = it
                }
            }
        }
        override fun toString(): String = "single(Synchronized) { ${cls?.simpleName ?: "?"} }"
    }

    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class PublishedSingle<in M, out T>(private val cls: KClass<T & Any>?, builder: M.() -> T) : Binding<M, Unit, T> {
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
        override fun toString(): String = "single(Published) { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class SynchronizedWeakSingle<in M, out T : Any>(private val cls: KClass<T>, private val builder: M.() -> T) : Binding<M, Unit, T> {
        @Volatile private var ref: Any? = Uninitialized
        private val lock = ReentrantLock()
        override fun get(module: M, arg: Unit): T {
            ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
            return lock.withLock {
                ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
                builder(module).also {
                    ref = WeakRef(it)
                }
            }
        }
        override fun toString(): String = "weakSingle(Synchronized) { ${cls.simpleName ?: "?"} }"
    }

    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class PublishedWeakSingle<in M, out T : Any>(private val cls: KClass<T>, private val builder: M.() -> T) : Binding<M, Unit, T> {
        private val refRef: AtomicReference<Any?> = AtomicReference(Uninitialized)
        override fun get(module: M, arg: Unit): T {
            // Built at most once and reused across CAS retries: a lost race must not re-run the
            // caller's builder.
            var newValue: T? = null
            while (true) {
                val ref = refRef.load()
                ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
                if (newValue == null) newValue = builder(module)
                if (refRef.compareAndSet(ref, WeakRef(newValue))) {
                    return newValue
                }
            }
        }
        override fun toString(): String = "weakSingle(Published) { ${cls.simpleName ?: "?"} }"
    }

    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class Multiplex<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val delegate: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> {
        private val bindingsRef: AtomicReference<Map<A, Binding<M, Unit, T>>> = AtomicReference(HashMap())

        /**
         * The cache is an immutable [Map] swapped in by CAS. Adding the k-th distinct argument does
         * `map + (arg to …)`, which copies the whole map, so filling n arguments is O(n²) overall.
         * This is deliberate, not an oversight: an argument that is already cached - the
         * overwhelmingly common case once warm - is then a single lock-free read with no allocation
         * and no contention, and `multiplex` is only supported for a small, bounded key space (see
         * [Binding.Bindings.multiplex]). Taking a lock on every `get` would tax that common path to
         * speed up a one-time cost on a map that is not meant to grow large.
         */
        override fun get(module: M, arg: A): T {
            // Built at most once and reused across CAS retries: a lost race must not re-run the
            // caller's delegate.
            var newBinding: Binding<M, Unit, T>? = null
            while (true) {
                val map = bindingsRef.load()
                val binding = map[arg]
                if (binding != null) {
                    return binding.get(module, Unit)
                }
                if (newBinding == null) newBinding = delegate(arg)
                val newMap = map + (arg to newBinding)
                if (bindingsRef.compareAndSet(map, newMap)) {
                    return newBinding.get(module, Unit)
                }
            }
        }
        override fun toString(): String = "multiplex { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }
}
