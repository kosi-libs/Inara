package org.kodein.di.inara

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
        public enum class ThreadSafety {
            Synchronized,
            Publication,
        }
        public fun <M, T> singleton(
            safety: ThreadSafety = ThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            ThreadSafety.Synchronized -> BindingImpls.SynchronizedSingleton(builder)
            ThreadSafety.Publication -> BindingImpls.PublishedSingleton(builder)
        }
        public fun <M, T : Any> weakSingleton(
            safety: ThreadSafety = ThreadSafety.Synchronized,
            builder: M.() -> T
        ) : Binding<M, Unit, T> = when (safety) {
            ThreadSafety.Synchronized -> BindingImpls.SynchronizedWeakSingleton(builder)
            ThreadSafety.Publication -> BindingImpls.PublishedWeakSingleton(builder)
        }
        public fun <M, A, T> multiplex(builder: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> = BindingImpls.Multiplex(builder)
    }
}

@InternalDIBindings
public class BindingImpls {
    internal class Value<M, Unit, T>(private val value: T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = value }

    internal class Provider<M, Unit, T>(private val provider: M.() -> T) : Binding<M, Unit, T> { override fun get(module: M, arg: Unit): T = provider(module) }

    internal class Factory<M, A, T>(private val factory: M.(A) -> T) : Binding<M, A, T> { override fun get(module: M, arg: A): T = factory(module, arg) }

    // Same as Factory, but exists for Copies.
    internal class Delegate<M, A, T>(private val delegate: M.(A) -> T) : Binding<M, A, T> { override fun get(module: M, arg: A): T = delegate(module, arg) }

    private object Uninitialized

    @OptIn(ExperimentalAtomicApi::class)
    internal class SynchronizedSingleton<M, T>(builder: M.() -> T) : Binding<M, Unit, T> {
        private var builder: (M.() -> T)? = builder
        private var valueRef: AtomicReference<Any?> = AtomicReference(Uninitialized)
        private val lock = Lock()

        override fun get(module: M, arg: Unit): T {
            val v1 = valueRef.load()
            if (v1 !== Uninitialized) {
                @Suppress("UNCHECKED_CAST") return v1 as T
            }
            return locked(lock) {
                val v2 = valueRef.load()
                if (v2 !== Uninitialized) {
                    @Suppress("UNCHECKED_CAST") return v2 as T
                }
                val value = builder!!(module)
                builder = null
                valueRef.store(value)
                value
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class PublishedSingleton<M, T>(builder: M.() -> T) : Binding<M, Unit, T> {
        @Volatile private var builder: (M.() -> T)? = builder
        private val valueRef: AtomicReference<Any?> = AtomicReference(Uninitialized)

        override fun get(module: M, arg: Unit): T {
            val value = valueRef.load()
            if (value !== Uninitialized) {
                @Suppress("UNCHECKED_CAST") return value as T
            }
            val builder = this.builder
            if (builder != null) {
                val newValue = builder(module)
                if (valueRef.compareAndSet(Uninitialized, newValue)) {
                    this.builder = null
                    return newValue
                }
            }
            @Suppress("UNCHECKED_CAST") return valueRef.load() as T
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class SynchronizedWeakSingleton<M, T : Any>(val builder: M.() -> T) : Binding<M, Unit, T> {
        private var valueRef: AtomicReference<Any?> = AtomicReference(Uninitialized)
        private val lock = Lock()

        override fun get(module: M, arg: Unit): T {
            val ref = valueRef.load()
            if (ref !== Uninitialized) {
                @Suppress("UNCHECKED_CAST")
                val value = (ref as WeakRef<T>).value
                if (value != null) {
                    return value
                }
            }
            return locked(lock) {
                val ref = valueRef.load()
                if (ref !== Uninitialized) {
                    @Suppress("UNCHECKED_CAST")
                    val value = (ref as WeakRef<T>).value
                    if (value != null) {
                        return value
                    }
                }
                val value = builder(module)
                valueRef.store(WeakRef(value))
                value
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class PublishedWeakSingleton<M, T : Any>(val builder: M.() -> T) : Binding<M, Unit, T> {
        private val valueRef: AtomicReference<Any?> = AtomicReference(Uninitialized)

        override fun get(module: M, arg: Unit): T {
            while (true) {
                val ref = valueRef.load()
                if (ref !== Uninitialized) {
                    @Suppress("UNCHECKED_CAST")
                    val value = (ref as WeakRef<T>).value
                    if (value != null) {
                        return value
                    }
                }
                val newValue = builder(module)
                if (valueRef.compareAndSet(ref, WeakRef(newValue))) {
                    return newValue
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal class Multiplex<M, A, T>(private val delegate: (A) -> Binding<M, Unit, T>) : Binding<M, A, T> {
        private val bindingsRef: AtomicReference<Map<A, Binding<M, Unit, T>>> = AtomicReference(HashMap())

        override fun get(module: M, arg: A): T {
            var binding: Binding<M, Unit, T>? = null
            while (binding == null) {
                val map = bindingsRef.load()
                binding = map[arg]
                if (binding == null) {
                    val newBinding = delegate(arg)
                    val newMap = map + (arg to newBinding)
                    if (bindingsRef.compareAndSet(map, newMap)) {
                        binding = newBinding
                    }
                }
            }
            return binding.get(module, Unit)
        }
    }
}
