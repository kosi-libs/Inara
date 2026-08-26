@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalNativeApi::class)

package org.kodein.di.inara

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.ref.WeakReference


// This code is copied from kotlin.native.concurrent

@ThreadLocal
private object CurrentThread { val id = Any() }

@OptIn(ExperimentalAtomicApi::class)
internal actual class Lock actual constructor() {
    private val locker = AtomicInt(0)
    private val reenterCount = AtomicInt(0)

    fun lock() {
        val lockData = CurrentThread.id.hashCode()
        loop@ do {
            val old = locker.compareAndExchange(0, lockData)
            when (old) {
                lockData -> {
                    // Was locked by us already.
                    reenterCount.incrementAndFetch()
                    break@loop
                }
                0 -> {
                    // We just got the lock.
                    break@loop
                }
            }
        } while (true)
    }

    fun unlock() {
        if (reenterCount.load() > 0) {
            reenterCount.decrementAndFetch()
        } else {
            val lockData = CurrentThread.id.hashCode()
            val old = locker.compareAndExchange(lockData, 0)
        }
    }
}
@OptIn(ExperimentalContracts::class)
internal actual inline fun <T> locked(lock: Lock, block: () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}

internal actual typealias WeakRef<T> = WeakReference<T>
internal actual fun <T : Any> WeakRef(value: T): WeakRef<T> = WeakReference(value)
internal actual val <T : Any> WeakRef<T>.value: T? get() = get()
