package org.kodein.di.inara

import org.kodein.di.inara.BindingImpls.Uninitialized.ifInitialized
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public interface Binding<M, A, T> {
    public fun get(module: M, arg: A): T

    @OptIn(InternalDIBindings::class)
    public companion object Bindings {
        public fun <M, T> value(value: T) : Binding<M, Unit, T> = BindingImpls.Value(value)
        public fun <M, T> provider(provider: M.() -> T) : Binding<M, Unit, T> = BindingImpls.Provider(provider)
        public fun <M, A, T> factory(factory: M.(A) -> T) : Binding<M, A, T> = BindingImpls.Factory(factory)
        public enum class SingleThreadSafety {
            Synchronized,
            Published,
        }
        public fun <M, T> single(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedSingle(builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedSingle(builder)
        }
        public fun <M, T : Any> weakSingle(
            safety: SingleThreadSafety = SingleThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            SingleThreadSafety.Synchronized -> BindingImpls.SynchronizedWeakSingle(builder)
            SingleThreadSafety.Published -> BindingImpls.PublishedWeakSingle(builder)
        }
        public fun <M, A, T> multiplex(builder: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> = BindingImpls.Multiplex(builder)
    }
}

@InternalDIBindings
public class BindingImpls {
    internal class Value<M, T>(private val value: T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = value }

    internal class Provider<M, T>(private val provider: M.() -> T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = provider(module) }

    internal class Factory<M, A, T>(private val factory: M.(A) -> T) : Binding<M, A, T> { override fun get(module: M, arg: A): T = factory(module, arg) }

    // Same as Factory, but exists for Copies.
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
