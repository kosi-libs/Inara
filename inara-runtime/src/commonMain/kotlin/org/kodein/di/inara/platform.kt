@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalContracts::class)

package org.kodein.di.inara

import kotlinx.atomicfu.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** A reentrant, blocking mutex. `ReentrantLock` on the JVM, a parking OS mutex on Native, a no-op on
 * the single-threaded web targets - see `kotlinx.atomicfu.locks`. */
internal typealias Lock = ReentrantLock

internal inline fun <T> locked(lock: Lock, block: () -> T): T {
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

internal expect class WeakRef<T : Any>
internal expect fun <T : Any> WeakRef(value: T): WeakRef<T>
internal expect val <T : Any> WeakRef<T>.value: T?
