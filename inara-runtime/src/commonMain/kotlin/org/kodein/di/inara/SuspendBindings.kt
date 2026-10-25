package org.kodein.di.inara

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.inara.Binding.Bindings.cls
import org.kodein.di.inara.BindingImpls.Uninitialized
import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

/**
 * The `suspend` counterpart to [Binding] - identical shape, generated for any `@DIModule` member
 * declared `suspend fun`. `M`/`A`/`T` mean exactly what they mean on [Binding], variance included.
 *
 * Build one through the factories on the [Bindings] companion ([value], [provider], [factory],
 * [single], [weakSingle], [multiplex]) - every [Binding] factory has a `suspend` equivalent here.
 * `single`/`weakSingle` take the same [SingleThreadSafety] as on [Binding]: `Synchronized` guards
 * the one-time build with a coroutine `Mutex`, `Published` is lock-free. (The suspend side used to
 * be `Mutex`-only; a coroutine `Mutex` turned out to be no cheaper than a system lock, so the
 * lock-free mode is worth offering here too.) An existing non-`suspend` [Binding] can be lifted
 * into one with [suspend].
 */
public interface SuspendBinding<in M, in A, out T> {
    /** Produces the value for [module] and [arg] - the `suspend` counterpart to [Binding.get]. */
    public suspend fun get(module: M, arg: A): T

    /** A copy of this binding for a copied module - the `suspend` counterpart to
     * [Binding.copyBinding]: a fresh twin when the binding keeps state, `this` when it doesn't, and
     * no default implementation. */
    public fun copyBinding(): SuspendBinding<M, A, T>

    /** Every way to build a [SuspendBinding]. */
    @OptIn(InternalInaraGeneration::class)
    public companion object Bindings {
        /** A [SuspendBinding] that always returns [value] - nothing is computed, ever. */
        public inline fun <M, reified T> value(value: T) : SuspendBinding<M, Unit, T> =
            SuspendBindingImpls.Value(Binding.cls<T>(), value)

        /** A [SuspendBinding] that runs [provider] on every [get] call - a fresh result each
         * time. */
        public inline fun <M, reified T> provider(noinline provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> =
            SuspendBindingImpls.Provider(Binding.cls<T>(), provider)

        /** A [SuspendBinding] that runs [factory] on every [get] call, with the caller's
         * argument - the argument-taking equivalent of [provider]. */
        public inline fun <M, reified A, reified T> factory(noinline factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> =
            SuspendBindingImpls.Factory(Binding.cls<A>(), Binding.cls<T>(), factory)

        /**
         * A [SuspendBinding] that runs [builder] once and memoizes the result forever - every
         * subsequent [get] returns the same instance without re-running [builder].
         *
         * [safety] controls what happens when the first `get` call is itself concurrent - see
         * [SingleThreadSafety]. `Synchronized` guards the one-time build with a coroutine `Mutex`;
         * `Published` is lock-free.
         */
        public inline fun <M, reified T> single(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: suspend M.() -> T
        ) : SuspendBinding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> SuspendBindingImpls.SynchronizedSingle(cls<T>(), builder)
            SingleThreadSafety.Published -> SuspendBindingImpls.PublishedSingle(cls<T>(), builder)
        }

        /** Like [single], but the memoized result is held behind a platform weak reference: once
         * nothing else strongly references it, it may be collected, and the next [get] call
         * rebuilds it. [safety] controls the first concurrent build, exactly as on [single]. */
        public inline fun <M, reified T : Any> weakSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: suspend M.() -> T
        ) : SuspendBinding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> SuspendBindingImpls.SynchronizedWeakSingle(T::class, builder)
            SingleThreadSafety.Published -> SuspendBindingImpls.PublishedWeakSingle(T::class, builder)
        }

        /** A [SuspendBinding] that builds and caches one sub-[SuspendBinding] per distinct
         * argument value, via [builder] - the way to give an argument-taking member its own
         * lifecycle instead of the argument-blind [factory].
         *
         * The cache is never evicted - see [Binding.Bindings.multiplex]. Use it only for a small,
         * bounded key space; keyed on something unbounded it is a memory leak. */
        public inline fun <M, reified A, reified T> multiplex(
            noinline builder: (A) -> SuspendBinding<M, Unit, T>
        ) : SuspendBinding<M, A, T> = SuspendBindingImpls.Multiplex(Binding.cls<A>(), Binding.cls<T>(), builder)

        /** The `suspend` counterpart of [Binding.Bindings.detachedSingle]: [builder] takes no
         * receiver and the binding is shared by every copy of the module. */
        public inline fun <reified T> detachedSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: suspend () -> T
        ) : SuspendBinding<Any?, Unit, T> = SuspendBindingImpls.Detached(single<Any?, T>(safety) { builder() })

        /** The `suspend` counterpart of [Binding.Bindings.detachedWeakSingle]. */
        public inline fun <reified T : Any> detachedWeakSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            noinline builder: suspend () -> T
        ) : SuspendBinding<Any?, Unit, T> = SuspendBindingImpls.Detached(weakSingle<Any?, T>(safety) { builder() })

        /** The `suspend` counterpart of [Binding.Bindings.detachedMultiplex]: the sub-bindings are
         * given `null` as their module. */
        public inline fun <reified A, reified T> detachedMultiplex(
            noinline builder: (A) -> SuspendBinding<Any?, Unit, T>
        ) : SuspendBinding<Any?, A, T> = SuspendBindingImpls.Detached(multiplex<Any?, A, T>(builder))
    }
}

/** Adapts an existing non-`suspend` [Binding] into a [SuspendBinding] with the same lifecycle,
 * simply calling through to [Binding.get] from a `suspend` context. */
@OptIn(InternalInaraGeneration::class)
public fun <M, A, T> Binding<M, A, T>.suspend(): SuspendBinding<M, A, T> = SuspendBindingImpls.Lifted(this)

/**
 * Implementation classes backing every [SuspendBinding] the [SuspendBinding.Bindings] factories
 * produce - the `suspend` counterpart to [BindingImpls]. Not for direct use, same as there -
 * [SuspendDelegate] and [copyOf] are public only because a generated `copy(...)` or `shadow(...)`
 * function needs them.
 */
@InternalInaraGeneration
public class SuspendBindingImpls {
    public companion object {
        /** The `suspend` counterpart of [BindingImpls.copyOf]: a copy of [binding] with its own,
         * unbuilt state, or [binding] itself when it is stateless (or `null`). */
        @Suppress("UNCHECKED_CAST")
        public fun <B : SuspendBinding<*, *, *>?> copyOf(binding: B): B =
            binding?.copyBinding() as B
    }

    @PublishedApi
    internal class Value<in M, out T>(private val cls: KClass<T & Any>?, private val value: T) : SuspendBinding<M, Unit, T> {
        override suspend fun get(module: M, arg: Unit): T = value
        override fun copyBinding() = this
        override fun toString(): String = "value ( ${cls?.simpleName ?: "?"} )"
    }

    @PublishedApi
    internal class Provider<in M, out T>(private val cls: KClass<T & Any>?, private val provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        override suspend fun get(module: M, arg: Unit): T = provider(module)
        override fun copyBinding() = this
        override fun toString(): String = "provider { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class Factory<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = factory(module, arg)
        override fun copyBinding() = this
        override fun toString(): String = "factory { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }

    /** Reads [delegate]'s *current* value off the receiver on every [get] - functionally
     * identical to [Factory], but under a name that reads correctly in generated `copy(...)`
     * signatures (`= SuspendBindingImpls.SuspendDelegate(this@copy::class) { this@copy.member() }`).
     * [cls] is the receiver's own runtime class - see [BindingImpls.Delegate] for why. */
    public class SuspendDelegate<in M : Any, in A, out T>(private val cls: KClass<out M>, private val delegate: suspend M.(A) -> T) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = delegate(module, arg)
        override fun copyBinding(): SuspendBinding<M, A, T> = this
        override fun toString(): String = "[delegated to ${cls.simpleName ?: "?"}]"
    }

    /** The `suspend` counterpart of [BindingImpls.Detached]. */
    @PublishedApi
    internal class Detached<in A, out T>(private val binding: SuspendBinding<Any?, A, T>) : SuspendBinding<Any?, A, T> {
        override suspend fun get(module: Any?, arg: A): T = binding.get(null, arg)
        override fun copyBinding(): SuspendBinding<Any?, A, T> = this
        override fun toString(): String = "detached $binding"
    }

    /** Wraps a plain [Binding] behind the `suspend` [SuspendBinding.get] signature - backs
     * [Binding.suspend]. Unlike [SuspendDelegate], there is no receiver instance to name here: this
     * doesn't forward to another module implementation, it just calls through to an already-built
     * [Binding], so [toString] mirrors that binding's own description instead of a delegate marker. */
    @PublishedApi
    internal class Lifted<in M, in A, out T>(private val binding: Binding<M, A, T>) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = binding.get(module, arg)
        override fun copyBinding(): SuspendBinding<M, A, T> {
            val copy = binding.copyBinding()
            return if (copy === binding) this else Lifted(copy)
        }
        override fun toString(): String = binding.toString()
    }

    @PublishedApi
    internal class SynchronizedSingle<in M, out T>(private val cls: KClass<T & Any>?, private val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        @Volatile private var value: Any? = Uninitialized
        private val mutex = Mutex()

        override suspend fun get(module: M, arg: Unit): T {
            value.ifInitialized<T> { return it }
            return mutex.withLock {
                value.ifInitialized<T> { return it }
                builder(module).also { value = it }
            }
        }
        override fun copyBinding() = SynchronizedSingle(cls, builder)
        override fun toString(): String = "single(Synchronized) { ${cls?.simpleName ?: "?"} }"
    }

    /** Lock-free counterpart of [SynchronizedSingle], mirroring [BindingImpls.PublishedSingle]:
     * concurrent first callers may each run [builder], but a single CAS on [ref] picks the winner
     * and every caller returns it. See [SuspendBinding] for why the suspend side offers this. */
    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class PublishedSingle<in M, out T>(private val cls: KClass<T & Any>?, private val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        private val ref: AtomicReference<Any?> = AtomicReference(Uninitialized)
        override suspend fun get(module: M, arg: Unit): T {
            ref.load().ifInitialized<T> { return it }
            builder(module).also {
                if (ref.compareAndSet(Uninitialized, it)) return it
            }
            @Suppress("UNCHECKED_CAST") return ref.load() as T
        }
        override fun copyBinding() = PublishedSingle(cls, builder)
        override fun toString(): String = "single(Published) { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class SynchronizedWeakSingle<in M, out T : Any>(private val cls: KClass<T>, private val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        @Volatile private var ref: Any? = Uninitialized
        private val mutex = Mutex()

        override suspend fun get(module: M, arg: Unit): T {
            ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
            return mutex.withLock {
                ref.ifInitialized<WeakRef<T>> { r -> r.value?.let { return it } }
                builder(module).also {
                    ref = WeakRef(it)
                }
            }
        }
        override fun copyBinding() = SynchronizedWeakSingle(cls, builder)
        override fun toString(): String = "weakSingle(Synchronized) { ${cls.simpleName ?: "?"} }"
    }

    /** Lock-free counterpart of [SynchronizedWeakSingle], mirroring [BindingImpls.PublishedWeakSingle].
     * See [SuspendBinding] for why the suspend side offers this. */
    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class PublishedWeakSingle<in M, out T : Any>(private val cls: KClass<T>, private val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        private val refRef: AtomicReference<Any?> = AtomicReference(Uninitialized)
        override suspend fun get(module: M, arg: Unit): T {
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
        override fun copyBinding() = PublishedWeakSingle(cls, builder)
        override fun toString(): String = "weakSingle(Published) { ${cls.simpleName ?: "?"} }"
    }

    /** The `suspend` counterpart of [BindingImpls.Multiplex] - same lock-free copy-on-write cache,
     * same tradeoff (see [get]). */
    @OptIn(ExperimentalAtomicApi::class)
    @PublishedApi
    internal class Multiplex<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val delegate: (A) -> SuspendBinding<M, Unit, T>) : SuspendBinding<M, A, T> {
        private val bindingsRef: AtomicReference<Map<A, SuspendBinding<M, Unit, T>>> = AtomicReference(HashMap())

        /**
         * The cache is an immutable [Map] swapped in by CAS. Adding the k-th distinct argument does
         * `map + (arg to …)`, which copies the whole map, so filling n arguments is O(n²) overall.
         * This is deliberate, not an oversight: an argument that is already cached - the
         * overwhelmingly common case once warm - is then a single lock-free read with no allocation
         * and no contention, and `multiplex` is only supported for a small, bounded key space (see
         * [SuspendBinding.Bindings.multiplex]). Taking a lock on every `get` would tax that common
         * path to speed up a one-time cost on a map that is not meant to grow large.
         */
        override suspend fun get(module: M, arg: A): T {
            // Built at most once and reused across CAS retries: a lost race must not re-run the
            // caller's delegate.
            var newBinding: SuspendBinding<M, Unit, T>? = null
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
        override fun copyBinding(): SuspendBinding<M, A, T> = Multiplex(aCls, tCls, delegate)
        override fun toString(): String = "multiplex { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }
}
