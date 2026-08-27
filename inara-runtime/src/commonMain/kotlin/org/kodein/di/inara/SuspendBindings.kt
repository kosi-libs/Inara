package org.kodein.di.inara

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.inara.BindingImpls.Uninitialized
import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile

/**
 * The `suspend` counterpart to [Binding] - identical shape, generated for any `@DIModule` member
 * declared `suspend fun`. `M`/`A`/`T` mean exactly what they mean on [Binding].
 *
 * Build one through the factories on the [Bindings] companion ([value], [provider], [factory],
 * [single], [weakSingle], [multiplex]) - every [Binding] factory has a `suspend` equivalent here,
 * with `single`/`weakSingle` always using a coroutine `Mutex` rather than [Binding]'s
 * `SingleThreadSafety` choice. An existing non-`suspend` [Binding] can be lifted into one with
 * [suspend].
 */
public interface SuspendBinding<M, A, T> {
    /** Produces the value for [module] and [arg] - the `suspend` counterpart to [Binding.get]. */
    public suspend fun get(module: M, arg: A): T

    /** Every way to build a [SuspendBinding]. */
    @OptIn(InternalDIBindings::class)
    public companion object Bindings {
        /** A [SuspendBinding] that always returns [value] - nothing is computed, ever. */
        public fun <M, T> value(value: T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Value(value)

        /** A [SuspendBinding] that runs [provider] on every [get] call - a fresh result each
         * time. */
        public fun <M, T> provider(provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Provider(provider)

        /** A [SuspendBinding] that runs [factory] on every [get] call, with the caller's
         * argument - the argument-taking equivalent of [provider]. */
        public fun <M, A, T> factory(factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> = SuspendBindingImpls.Factory(factory)

        /** A [SuspendBinding] that runs [builder] once, under a coroutine `Mutex`, and memoizes
         * the result forever - every subsequent [get] returns the same instance without
         * re-running [builder]. */
        public fun <M, T> single(builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Single(builder)

        /** Like [single], but the memoized result is held behind a platform weak reference: once
         * nothing else strongly references it, it may be collected, and the next [get] call
         * rebuilds it. */
        public fun <M, T : Any> weakSingle(builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.WeakSingle(builder)

        /** A [SuspendBinding] that builds and caches one sub-[SuspendBinding] per distinct
         * argument value, via [builder] - the way to give an argument-taking member its own
         * lifecycle instead of the argument-blind [factory]. */
        public fun <M, A, T> multiplex(builder: (A) -> SuspendBinding<M, Unit, T>) : SuspendBinding<M, A, T> = SuspendBindingImpls.Multiplex(builder)
    }
}

/** Adapts an existing non-`suspend` [Binding] into a [SuspendBinding] with the same lifecycle,
 * simply calling through to [Binding.get] from a `suspend` context. */
@OptIn(InternalDIBindings::class)
public fun <M, A, T> Binding<M, A, T>.suspend(): SuspendBinding<M, A, T> = SuspendBindingImpls.SuspendDelegate { this@suspend.get(this, it) }

/**
 * Implementation classes backing every [SuspendBinding] the [SuspendBinding.Bindings] factories
 * produce - the `suspend` counterpart to [BindingImpls]. Not for direct use, same as there -
 * [SuspendDelegate] is public only because a generated `copy(...)` function needs it.
 */
@InternalDIBindings
public class SuspendBindingImpls {
    internal class Value<M, T>(private val value: T) : SuspendBinding<M, Unit, T> { override suspend fun get(module: M, arg: Unit): T = value }

    internal class Provider<M, T>(private val provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> { override suspend fun get(module: M, arg: Unit): T = provider(module) }

    internal class Factory<M, A, T>(private val factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> { override suspend fun get(module: M, arg: A): T = factory(module, arg) }

    /** Reads [delegate]'s *current* value off the receiver on every [get] - functionally
     * identical to [Factory], but under a name that reads correctly in generated `copy(...)`
     * signatures (`= SuspendBindingImpls.SuspendDelegate { this@copy.member() }`). */
    public class SuspendDelegate<M, A, T>(private val delegate: suspend M.(A) -> T) : SuspendBinding<M, A, T> { override suspend fun get(module: M, arg: A): T = delegate(module, arg) }

    internal class Single<M, T>(builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        private var builder: (suspend M.() -> T)? = builder
        @Volatile private var value: Any? = Uninitialized
        private val mutex = Mutex()

        override suspend fun get(module: M, arg: Unit): T {
            value.ifInitialized<T> { return it }
            return mutex.withLock {
                value.ifInitialized<T> { return it }
                builder!!(module).also {
                    builder = null
                    value = it
                }
            }
        }
    }

    internal class WeakSingle<M, T : Any>(val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
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
    }

    internal class Multiplex<M, A, T>(private val delegate: (A) -> SuspendBinding<M, Unit, T>) : SuspendBinding<M, A, T> {
        @Volatile private var bindings: Map<A, SuspendBinding<M, Unit, T>> = HashMap()
        private val mutex = Mutex()

        override suspend fun get(module: M, arg: A): T {
            val binding = bindings[arg] ?: mutex.withLock {
                bindings[arg] ?: delegate(arg).also {
                    bindings = bindings + (arg to it)
                }
            }
            return binding.get(module, Unit)
        }
    }

}
