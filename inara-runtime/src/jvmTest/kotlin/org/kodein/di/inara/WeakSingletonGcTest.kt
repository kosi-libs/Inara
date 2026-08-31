package org.kodein.di.inara

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class GcTestModule

/**
 * Confirms that a `weakSingleton` actually lets its value be garbage-collected and
 * rebuilds it on the next `get()` -- as opposed to [ConcurrentBindingsTest] and
 * [BindingsTest], which only ever check the binding while a strong reference is held.
 *
 * This is a best-effort test: [System.gc] is only ever a hint, so the assertions are
 * skipped (never failed) if the JVM declines to collect within the attempt budget. The
 * `Published` case is the direct regression test for the fix in
 * `BindingImpls.PublishedWeakSingle.get`, which used to store the raw rebuilt value
 * instead of wrapping it in a new `WeakRef`, causing a `ClassCastException` on the read
 * that follows a successful rebuild.
 */
class WeakSingletonGcTest {

    @Test
    fun weakSingleton_synchronized_rebuildsAfterCollection() =
        assertRebuildsAfterCollection(SingleThreadSafety.Synchronized)

    @Test
    fun weakSingleton_publication_rebuildsAfterCollection() =
        assertRebuildsAfterCollection(SingleThreadSafety.Published)

    private fun assertRebuildsAfterCollection(safety: SingleThreadSafety) {
        var builds = 0
        val binding = Binding.weakSingle<GcTestModule, Any>(safety) {
            builds++
            Any()
        }
        val module = GcTestModule()

        var strongRef: Any? = binding.get(module, Unit)
        val firstIdentity = System.identityHashCode(strongRef)
        val weakRef = WeakReference(strongRef)
        strongRef = null

        var collected = false
        var attempts = 0
        while (attempts < 20 && !collected) {
            if (weakRef.get() == null) {
                collected = true
            } else {
                System.gc()
                Thread.sleep(50)
            }
            attempts++
        }

        if (!collected) {
            println("weakSingleton_rebuildsAfterCollection ($safety): value was never collected in $attempts attempts, skipping")
            return
        }

        val rebuilt = binding.get(module, Unit)
        assertTrue(builds >= 2, "expected a rebuild after collection, but builder ran $builds time(s)")
        assertNotEquals(firstIdentity, System.identityHashCode(rebuilt))
    }
}
