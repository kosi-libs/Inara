@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.kodein.di.inara


internal expect class Lock()
internal expect inline fun <T> locked(lock: Lock, block: () -> T): T

internal expect class WeakRef<T : Any>
internal expect fun <T : Any> WeakRef(value: T): WeakRef<T>
internal expect val <T : Any> WeakRef<T>.value: T?
