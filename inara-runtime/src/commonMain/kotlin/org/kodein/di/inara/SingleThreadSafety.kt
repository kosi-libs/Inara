package org.kodein.di.inara

/**
 * How a `single` / `weakSingle` binding guards its one-time build against concurrent first callers.
 *
 * Shared by [Binding.Bindings.single] / [Binding.Bindings.weakSingle] and their `suspend`
 * counterparts [SuspendBinding.Bindings.single] / [SuspendBinding.Bindings.weakSingle] - the same
 * two strategies, the only difference being the lock primitive `Synchronized` uses.
 */
public enum class SingleThreadSafety {
    /** The binding's `get` blocks until the first caller's build finishes - the builder runs
     * exactly once, guaranteed. The lock is a system `ReentrantLock` for [Binding], a coroutine
     * `Mutex` for [SuspendBinding]. */
    Synchronized,

    /** The binding's `get` never blocks: it is a lock-free compare-and-set on an atomic reference.
     * Concurrent first callers may each run the builder, but all of them converge on the same
     * winning instance - the builder may run more than once under contention, though only one
     * result is ever kept. */
    Published,
}
