package org.kodein.di.inara

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.inara.BindingImpls.Uninitialized
import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile

public interface SuspendBinding<M, A, T> {
    public suspend fun get(module: M, arg: A): T

    @OptIn(InternalDIBindings::class)
    public companion object Bindings {
        public fun <M, T> value(value: T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Value(value)
        public fun <M, T> provider(provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Provider(provider)
        public fun <M, A, T> factory(factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> = SuspendBindingImpls.Factory(factory)
        public fun <M, T> single(builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.Single(builder)
        public fun <M, T : Any> weakSingle(builder: suspend M.() -> T) : SuspendBinding<M, Unit, T> = SuspendBindingImpls.WeakSingle(builder)
        public fun <M, A, T> multiplex(builder: (A) -> SuspendBinding<M, Unit, T>) : SuspendBinding<M, A, T> = SuspendBindingImpls.Multiplex(builder)
    }
}

@OptIn(InternalDIBindings::class)
public fun <M, A, T> Binding<M, A, T>.suspend(): SuspendBinding<M, A, T> = SuspendBindingImpls.SuspendDelegate { this@suspend.get(this, it) }

@InternalDIBindings
public class SuspendBindingImpls {
    internal class Value<M, T>(private val value: T) : SuspendBinding<M, Unit, T> { override suspend fun get(module: M, arg: Unit): T = value }

    internal class Provider<M, T>(private val provider: suspend M.() -> T) : SuspendBinding<M, Unit, T> { override suspend fun get(module: M, arg: Unit): T = provider(module) }

    internal class Factory<M, A, T>(private val factory: suspend M.(A) -> T) : SuspendBinding<M, A, T> { override suspend fun get(module: M, arg: A): T = factory(module, arg) }

    // Same as Factory, but exists for Copies.
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
