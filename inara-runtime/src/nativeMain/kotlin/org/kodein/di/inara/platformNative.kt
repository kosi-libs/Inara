@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalNativeApi::class)

package org.kodein.di.inara

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

internal actual typealias WeakRef<T> = WeakReference<T>
internal actual fun <T : Any> WeakRef(value: T): WeakRef<T> = WeakReference(value)
internal actual val <T : Any> WeakRef<T>.value: T? get() = get()
