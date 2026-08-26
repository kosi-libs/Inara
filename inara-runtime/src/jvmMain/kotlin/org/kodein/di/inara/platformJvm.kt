@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.kodein.di.inara

import java.lang.ref.WeakReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal actual typealias Lock = java.util.concurrent.locks.ReentrantLock
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
