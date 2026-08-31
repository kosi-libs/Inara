@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.kodein.di.inara

import java.lang.ref.WeakReference

internal actual typealias WeakRef<T> = WeakReference<T>
internal actual fun <T : Any> WeakRef(value: T): WeakRef<T> = WeakReference(value)
internal actual val <T : Any> WeakRef<T>.value: T? get() = get()
