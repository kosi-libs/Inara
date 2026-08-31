@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalWasmJsInterop::class)

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
internal external class JsWeakRef<T : JsAny>(target: T) {
    fun deref(): T?
}

internal actual class WeakRef<T : Any>(val js: JsWeakRef<JsReference<T>>)
internal actual fun <T : Any> WeakRef(value: T): WeakRef<T> = WeakRef(JsWeakRef(value.toJsReference()))
internal actual val <T : Any> WeakRef<T>.value: T? get() = js.deref()?.get()
