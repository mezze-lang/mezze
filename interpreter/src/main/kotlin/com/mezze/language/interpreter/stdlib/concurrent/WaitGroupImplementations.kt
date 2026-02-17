package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.AbstractQueuedSynchronizer

/**
 * Native implementations for WaitGroup operations.
 *
 * A WaitGroup allows waiting on a dynamic collection of tasks.
 * Unlike CountDownLatch, the count can be incremented as new tasks are added.
 */
object WaitGroupImplementations {

    /**
     * Register all wait group-related native functions.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerAdd(context)
        registerDone(context)
        registerWait(context)
        registerWaitTimeout(context)
        registerGetCount(context)
    }

    /**
     * __native_waitgroup_new: {} -> WaitGroup
     * Creates a new wait group with count 0.
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_new"] = NativeMezzeFunction("__native_waitgroup_new") { _ ->
            MezzeWaitGroup(WaitGroupSync())
        }
    }

    /**
     * __native_waitgroup_add: {wg: WaitGroup, delta: Int} -> {}
     * Adds delta to the wait group counter.
     */
    private fun registerAdd(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_add"] = NativeMezzeFunction("__native_waitgroup_add") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_waitgroup_add expects MezzeRecord")

            val wg = record.fields["wg"] as? MezzeWaitGroup
                ?: throw MezzeException("__native_waitgroup_add expects 'wg' field of type WaitGroup")

            val delta = (record.fields["delta"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_waitgroup_add expects 'delta' field of type Int")

            wg.sync.add(delta)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_waitgroup_done: {wg: WaitGroup} -> {}
     * Decrements the wait group counter by 1.
     */
    private fun registerDone(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_done"] = NativeMezzeFunction("__native_waitgroup_done") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_waitgroup_done expects MezzeRecord")

            val wg = record.fields["wg"] as? MezzeWaitGroup
                ?: throw MezzeException("__native_waitgroup_done expects 'wg' field of type WaitGroup")

            wg.sync.add(-1)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_waitgroup_wait: {wg: WaitGroup} -> {}
     * Waits until the counter reaches zero.
     */
    private fun registerWait(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_wait"] = NativeMezzeFunction("__native_waitgroup_wait") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_waitgroup_wait expects MezzeRecord")

            val wg = record.fields["wg"] as? MezzeWaitGroup
                ?: throw MezzeException("__native_waitgroup_wait expects 'wg' field of type WaitGroup")

            wg.sync.await()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_waitgroup_wait_timeout: {wg: WaitGroup, ms: Int} -> Bool
     * Waits until the counter reaches zero or timeout expires.
     */
    private fun registerWaitTimeout(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_wait_timeout"] = NativeMezzeFunction("__native_waitgroup_wait_timeout") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_waitgroup_wait_timeout expects MezzeRecord")

            val wg = record.fields["wg"] as? MezzeWaitGroup
                ?: throw MezzeException("__native_waitgroup_wait_timeout expects 'wg' field of type WaitGroup")

            val ms = (record.fields["ms"] as? MezzeInt)?.value?.toLong()
                ?: throw MezzeException("__native_waitgroup_wait_timeout expects 'ms' field of type Int")

            MezzeBool(wg.sync.await(ms, TimeUnit.MILLISECONDS))
        }
    }

    /**
     * __native_waitgroup_get_count: {wg: WaitGroup} -> Int
     * Gets the current count.
     */
    private fun registerGetCount(context: MutableMap<String, MezzeValue>) {
        context["__native_waitgroup_get_count"] = NativeMezzeFunction("__native_waitgroup_get_count") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_waitgroup_get_count expects MezzeRecord")

            val wg = record.fields["wg"] as? MezzeWaitGroup
                ?: throw MezzeException("__native_waitgroup_get_count expects 'wg' field of type WaitGroup")

            MezzeInt(wg.sync.count.toLong())
        }
    }
}

/**
 * WaitGroupSync - AQS-based synchronizer for WaitGroup.
 *
 * Unlike CountDownLatch, this allows incrementing the count.
 */
class WaitGroupSync : AbstractQueuedSynchronizer() {

    val count: Int
        get() = state

    /**
     * Add delta to the count. Delta can be positive or negative.
     * Throws if the result would be negative.
     */
    fun add(delta: Int) {
        while (true) {
            val current = state
            val next = current + delta
            if (next < 0) {
                throw MezzeException("WaitGroup counter cannot go negative: $current + $delta = $next")
            }
            if (compareAndSetState(current, next)) {
                // If count reached zero, release all waiters
                if (next == 0) {
                    releaseShared(0)
                }
                return
            }
        }
    }

    /**
     * Wait for count to reach zero.
     */
    fun await() {
        acquireSharedInterruptibly(0)
    }

    /**
     * Wait for count to reach zero with timeout.
     * Returns true if count reached zero, false on timeout.
     */
    fun await(timeout: Long, unit: TimeUnit): Boolean {
        return tryAcquireSharedNanos(0, unit.toNanos(timeout))
    }

    override fun tryAcquireShared(arg: Int): Int {
        return if (state == 0) 1 else -1
    }

    override fun tryReleaseShared(arg: Int): Boolean {
        return true
    }
}

/**
 * MezzeWaitGroup - wraps a WaitGroupSync.
 */
data class MezzeWaitGroup(
    val sync: WaitGroupSync
) : MezzeValue() {
    override fun getDisplayString(): String {
        return "<waitgroup:${sync.count} pending>"
    }
}
