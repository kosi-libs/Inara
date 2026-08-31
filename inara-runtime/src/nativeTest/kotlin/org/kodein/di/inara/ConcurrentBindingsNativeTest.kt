@file:OptIn(ExperimentalAtomicApi::class)

package org.kodein.di.inara

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.kodein.di.inara.Binding.Bindings.SingleThreadSafety
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class NativeTestModule

private const val COROUTINE_COUNT = 8
private const val ITERATIONS = 200

/**
 * The native counterpart of `ConcurrentBindingsTest` (jvmTest). On the JVM `Lock` is a
 * `ReentrantLock`; here it is `kotlinx.atomicfu.locks.ReentrantLock`, a parking OS mutex - the only
 * non-trivial synchronization primitive in the project, and previously never exercised under
 * contention by any test.
 */
class ConcurrentBindingsNativeTest {

    @Test
    fun singleton_synchronized_buildsExactlyOnceUnderContention() {
        val builds = AtomicInt(0)
        val binding = Binding.single<NativeTestModule, Any>(SingleThreadSafety.Synchronized) {
            builds.incrementAndFetch()
            Any()
        }

        val results = runConcurrently { binding.get(NativeTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertEquals(1, builds.load())
    }

    @Test
    fun singleton_publication_alwaysConvergesOnOneInstance() {
        val builds = AtomicInt(0)
        val binding = Binding.single<NativeTestModule, Any>(SingleThreadSafety.Published) {
            builds.incrementAndFetch()
            Any()
        }

        val results = runConcurrently { binding.get(NativeTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertTrue(builds.load() >= 1)
    }

    @Test
    fun weakSingleton_synchronized_buildsExactlyOnceWhileStronglyReferenced() {
        val builds = AtomicInt(0)
        val binding = Binding.weakSingle<NativeTestModule, Any>(SingleThreadSafety.Synchronized) {
            builds.incrementAndFetch()
            Any()
        }

        val results = runConcurrently { binding.get(NativeTestModule(), Unit) }

        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertEquals(1, builds.load())
    }

    @Test
    fun multiplex_isolatesConcurrentArgs() {
        val argCount = 4
        val binding = Binding.multiplex<NativeTestModule, Int, Any> { arg ->
            Binding.single { arg }
        }
        val module = NativeTestModule()

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
     * Runs [action] from [COROUTINE_COUNT] coroutines on the multi-threaded default dispatcher,
     * [ITERATIONS] times each, and returns every result.
     */
    private fun <T> runConcurrently(action: () -> T): List<T> = runBlocking {
        (0 until COROUTINE_COUNT)
            .map { async(Dispatchers.Default) { List(ITERATIONS) { action() } } }
            .awaitAll()
            .flatten()
    }
}
