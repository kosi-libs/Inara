@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.kodein.di.inara

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal actual class Lock actual constructor()
@OptIn(ExperimentalContracts::class)
internal actual inline fun <T> locked(lock: Lock, block: () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

/**
 * [MDN Reference](https://developer.mozilla.org/docs/Web/JavaScript/Reference/Global_Objects/WeakRef)
 */
@JsName("WeakRef")
internal external class JsWeakRef<T : Any>(target: T) {
    fun deref(): T?
}

internal actual typealias WeakRef<T> = JsWeakRef<T>
internal actual fun <T : Any> WeakRef(value: T): WeakRef<T> = JsWeakRef(value)
internal actual val <T : Any> WeakRef<T>.value: T? get() = deref()
