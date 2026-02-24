package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native implementations for CountDownLatch operations.
 *
 * A latch allows threads to wait until a count reaches zero.
 * Once the count reaches zero, it cannot be reset.
 */
object LatchImplementations {

    /**
     * Register all latch-related native functions.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerCountDown(context)
        registerAwait(context)
        registerAwaitTimeout(context)
        registerGetCount(context)
    }

    /**
     * __native_latch_new: {count: Int} -> Latch
     * Creates a new latch with specified count.
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_latch_new"] = NativeMezzeFunction("__native_latch_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_latch_new expects MezzeRecord")

            val count = (record.fields["count"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_latch_new expects 'count' field of type Int")

            if (count < 0) {
                throw MezzeException("Latch count must be non-negative, got: $count")
            }

            MezzeLatch(CountDownLatch(count))
        }
    }

    /**
     * __native_latch_count_down: {latch: Latch} -> {}
     * Decrements the latch count by one.
     */
    private fun registerCountDown(context: MutableMap<String, MezzeValue>) {
        context["__native_latch_count_down"] = NativeMezzeFunction("__native_latch_count_down") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_latch_count_down expects MezzeRecord")

            val latch = record.fields["latch"] as? MezzeLatch
                ?: throw MezzeException("__native_latch_count_down expects 'latch' field of type Latch")

            latch.latch.countDown()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_latch_await: {latch: Latch} -> {}
     * Waits until the latch count reaches zero.
     */
    private fun registerAwait(context: MutableMap<String, MezzeValue>) {
        context["__native_latch_await"] = NativeMezzeFunction("__native_latch_await") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_latch_await expects MezzeRecord")

            val latch = record.fields["latch"] as? MezzeLatch
                ?: throw MezzeException("__native_latch_await expects 'latch' field of type Latch")

            latch.latch.await()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool
     * Waits until the latch count reaches zero or timeout expires.
     * Returns true if count reached zero, false if timeout expired.
     */
    private fun registerAwaitTimeout(context: MutableMap<String, MezzeValue>) {
        context["__native_latch_await_timeout"] = NativeMezzeFunction("__native_latch_await_timeout") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_latch_await_timeout expects MezzeRecord")

            val latch = record.fields["latch"] as? MezzeLatch
                ?: throw MezzeException("__native_latch_await_timeout expects 'latch' field of type Latch")

            val ms = (record.fields["ms"] as? MezzeInt)?.value?.toLong()
                ?: throw MezzeException("__native_latch_await_timeout expects 'ms' field of type Int")

            MezzeBool(latch.latch.await(ms, TimeUnit.MILLISECONDS))
        }
    }

    /**
     * __native_latch_get_count: {latch: Latch} -> Int
     * Gets the current count of the latch.
     */
    private fun registerGetCount(context: MutableMap<String, MezzeValue>) {
        context["__native_latch_get_count"] = NativeMezzeFunction("__native_latch_get_count") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_latch_get_count expects MezzeRecord")

            val latch = record.fields["latch"] as? MezzeLatch
                ?: throw MezzeException("__native_latch_get_count expects 'latch' field of type Latch")

            MezzeInt(latch.latch.count)
        }
    }
}

/**
 * MezzeLatch - wraps a Java CountDownLatch.
 */
data class MezzeLatch(
    val latch: CountDownLatch
) : MezzeValue() {
    override fun computeDisplayString(): String {
        return "<latch:${latch.count} remaining>"
    }
}
