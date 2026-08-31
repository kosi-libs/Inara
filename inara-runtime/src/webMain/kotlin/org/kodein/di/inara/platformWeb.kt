@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.kodein.di.inara

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
