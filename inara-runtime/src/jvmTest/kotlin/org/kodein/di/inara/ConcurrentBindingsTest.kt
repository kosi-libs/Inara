package org.kodein.di.inara

import org.kodein.di.inara.Binding.Bindings.SingleThreadSafety
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

private class ConcurrentTestModule

private const val THREAD_COUNT = 8
private const val ITERATIONS = 200

/**
 * Exercises the bindings under real concurrent contention. This is the whole reason
 * [Binding.Bindings.single] and [Binding.Bindings.weakSingle] have thread-safety knobs, and the
 * reason [Binding.Bindings.multiplex] is careful to isolate arguments, so these assertions run from
 * multiple threads instead of sequentially.
 */
class ConcurrentBindingsTest {

    @Test
    fun singleton_synchronized_buildsExactlyOnceUnderContention() {
        val builds = AtomicInteger(0)
        val binding = Binding.single<ConcurrentTestModule, Any>(SingleThreadSafety.Synchronized) {
            builds.incrementAndGet()
            Any()
        }

        val results = runConcurrently { binding.get(ConcurrentTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertEquals(1, builds.get())
    }

    @Test
    fun singleton_publication_alwaysConvergesOnOneInstance() {
        val builds = AtomicInteger(0)
        val binding = Binding.single<ConcurrentTestModule, Any>(SingleThreadSafety.Published) {
            builds.incrementAndGet()
            Any()
        }

        val results = runConcurrently { binding.get(ConcurrentTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        // Publication mode trades possible redundant builds for lock-freedom: every
        // caller must still observe the same winning instance, but "loser" builds are
        // allowed -- and expected -- under real contention, so we only assert >= 1.
        assertTrue(builds.get() >= 1)
    }

    @Test
    fun weakSingleton_synchronized_buildsExactlyOnceWhileStronglyReferenced() {
        val builds = AtomicInteger(0)
        val binding = Binding.weakSingle<ConcurrentTestModule, Any>(SingleThreadSafety.Synchronized) {
            builds.incrementAndGet()
            Any()
        }

        val results = runConcurrently { binding.get(ConcurrentTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertEquals(1, builds.get())
    }

    @Test
    fun multiplex_isolatesConcurrentArgs() {
        val argCount = 4
        val binding = Binding.multiplex<ConcurrentTestModule, Int, Any> { arg ->
            Binding.single { arg }
        }
        val module = ConcurrentTestModule()

        val resultsByArg = (0 until argCount).associateWith { arg ->
            runConcurrently { binding.get(module, arg) }
        }

        resultsByArg.forEach { (arg, results) ->
            val first = results.first()
            results.forEach {
                assertSame(first, it)
                assertEquals(arg, it)
            }
        }
    }

    /**
     * Runs [action] from [THREAD_COUNT] threads, [ITERATIONS] times each, all released
     * simultaneously by a barrier so the calls genuinely overlap, and returns every result.
     */
    private fun <T> runConcurrently(action: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(THREAD_COUNT)
        val barrier = CyclicBarrier(THREAD_COUNT)
        val results = CopyOnWriteArrayList<T>()
        try {
            val futures = (0 until THREAD_COUNT).map {
                pool.submit(Runnable {
                    barrier.await()
                    repeat(ITERATIONS) {
                        results.add(action())
                    }
                })
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                fail("thread pool did not terminate in time")
            }
        }
        return results
    }
}
