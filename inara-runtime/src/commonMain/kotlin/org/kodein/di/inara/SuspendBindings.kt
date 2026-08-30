package org.kodein.di.inara

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.inara.BindingImpls.Uninitialized
import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * The `suspend` counterpart to [Binding] - identical shape, generated for any `@DIModule` member
 * declared `suspend fun`. `M`/`A`/`T` mean exactly what they mean on [Binding], variance included.
 *
 * Build one through the factories on the [Bindings] companion ([value], [provider], [factory],
 * [single], [weakSingle], [multiplex]) - every [Binding] factory has a `suspend` equivalent here,
 * with `single`/`weakSingle` always using a coroutine `Mutex` rather than [Binding]'s
 * `SingleThreadSafety` choice. An existing non-`suspend` [Binding] can be lifted into one with
 * [suspend].
 */
public interface SuspendBinding<in M, in A, out T> {
    /** Produces the value for [module] and [arg] - the `suspend` counterpart to [Binding.get]. */
    public suspend fun get(module: M, arg: A): T

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

        /** A [SuspendBinding] that runs [builder] once, under a coroutine `Mutex`, and memoizes
         * the result forever - every subsequent [get] returns the same instance without
         * re-running [builder]. */
        public inline fun <M, reified T> single(noinline builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> =
            SuspendBindingImpls.Single(Binding.cls<T>(), builder)

        /** Like [single], but the memoized result is held behind a platform weak reference: once
         * nothing else strongly references it, it may be collected, and the next [get] call
         * rebuilds it. */
        public inline fun <M, reified T : Any> weakSingle(noinline builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> =
            SuspendBindingImpls.WeakSingle(T::class, builder)

        /** A [SuspendBinding] that builds and caches one sub-[SuspendBinding] per distinct
         * argument value, via [builder] - the way to give an argument-taking member its own
         * lifecycle instead of the argument-blind [factory]. */
        public inline fun <M, reified A, reified T> multiplex(
            noinline builder: (A) -> SuspendBinding<M, Unit, T>
        ) : SuspendBinding<M, A, T> = SuspendBindingImpls.Multiplex(Binding.cls<A>(), Binding.cls<T>(), builder)
    }
}

/** Adapts an existing non-`suspend` [Binding] into a [SuspendBinding] with the same lifecycle,
 * simply calling through to [Binding.get] from a `suspend` context. */
@OptIn(InternalInaraGeneration::class)
public fun <M, A, T> Binding<M, A, T>.suspend(): SuspendBinding<M, A, T> = SuspendBindingImpls.Lifted(this)

/**
 * Implementation classes backing every [SuspendBinding] the [SuspendBinding.Bindings] factories
 * produce - the `suspend` counterpart to [BindingImpls]. Not for direct use, same as there -
 * [SuspendDelegate] is public only because a generated `copy(...)` function needs it.
 */
@InternalInaraGeneration
public class SuspendBindingImpls {
    @PublishedApi
    internal class Value<in M, out T>(private val cls: KClass<T & Any>?, private val value: T) : SuspendBinding<M, Unit, T> {
        override suspend fun get(module: M, arg: Unit): T = value
        override fun toString(): String = "value ( ${cls?.simpleName ?: "?"} )"
    }

    @PublishedApi
    internal class Provider<in M, out T>(private val cls: KClass<T & Any>?, private val provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
        override suspend fun get(module: M, arg: Unit): T = provider(module)
        override fun toString(): String = "provider { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class Factory<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = factory(module, arg)
        override fun toString(): String = "factory { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }

    /** Reads [delegate]'s *current* value off the receiver on every [get] - functionally
     * identical to [Factory], but under a name that reads correctly in generated `copy(...)`
     * signatures (`= SuspendBindingImpls.SuspendDelegate(this@copy::class) { this@copy.member() }`).
     * [cls] is the receiver's own runtime class - see [BindingImpls.Delegate] for why. */
    public class SuspendDelegate<in M : Any, in A, out T>(private val cls: KClass<out M>, private val delegate: suspend M.(A) -> T) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = delegate(module, arg)
        override fun toString(): String = "[delegated to ${cls.simpleName ?: "?"}]"
    }

    /** Wraps a plain [Binding] behind the `suspend` [SuspendBinding.get] signature - backs
     * [Binding.suspend]. Unlike [SuspendDelegate], there is no receiver instance to name here: this
     * doesn't forward to another module implementation, it just calls through to an already-built
     * [Binding], so [toString] mirrors that binding's own description instead of a delegate marker. */
    @PublishedApi
    internal class Lifted<in M, in A, out T>(private val binding: Binding<M, A, T>) : SuspendBinding<M, A, T> {
        override suspend fun get(module: M, arg: A): T = binding.get(module, arg)
        override fun toString(): String = binding.toString()
    }

    @PublishedApi
    internal class Single<in M, out T>(private val cls: KClass<T & Any>?, builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
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
        override fun toString(): String = "single { ${cls?.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class WeakSingle<in M, out T : Any>(private val cls: KClass<T>, private val builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> {
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
        override fun toString(): String = "weakSingle { ${cls.simpleName ?: "?"} }"
    }

    @PublishedApi
    internal class Multiplex<in M, in A, out T>(private val aCls: KClass<A & Any>?, private val tCls: KClass<T & Any>?, private val delegate: (A) -> SuspendBinding<M, Unit, T>) : SuspendBinding<M, A, T> {
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
        override fun toString(): String = "multiplex { ${aCls?.simpleName ?: "?"} -> ${tCls?.simpleName ?: "?"} }"
    }
}
