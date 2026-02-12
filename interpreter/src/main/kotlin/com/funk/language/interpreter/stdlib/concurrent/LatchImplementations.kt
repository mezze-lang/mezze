package com.funk.language.interpreter.stdlib.concurrent

import com.funk.language.interpreter.*
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
    fun registerAll(context: MutableMap<String, FunkValue>) {
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
    private fun registerNew(context: MutableMap<String, FunkValue>) {
        context["__native_latch_new"] = NativeFunkFunction("__native_latch_new") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_latch_new expects FunkRecord")

            val count = (record.fields["count"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_latch_new expects 'count' field of type Int")

            if (count < 0) {
                throw FunkException("Latch count must be non-negative, got: $count")
            }

            FunkLatch(CountDownLatch(count))
        }
    }

    /**
     * __native_latch_count_down: {latch: Latch} -> {}
     * Decrements the latch count by one.
     */
    private fun registerCountDown(context: MutableMap<String, FunkValue>) {
        context["__native_latch_count_down"] = NativeFunkFunction("__native_latch_count_down") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_latch_count_down expects FunkRecord")

            val latch = record.fields["latch"] as? FunkLatch
                ?: throw FunkException("__native_latch_count_down expects 'latch' field of type Latch")

            latch.latch.countDown()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_latch_await: {latch: Latch} -> {}
     * Waits until the latch count reaches zero.
     */
    private fun registerAwait(context: MutableMap<String, FunkValue>) {
        context["__native_latch_await"] = NativeFunkFunction("__native_latch_await") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_latch_await expects FunkRecord")

            val latch = record.fields["latch"] as? FunkLatch
                ?: throw FunkException("__native_latch_await expects 'latch' field of type Latch")

            latch.latch.await()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool
     * Waits until the latch count reaches zero or timeout expires.
     * Returns true if count reached zero, false if timeout expired.
     */
    private fun registerAwaitTimeout(context: MutableMap<String, FunkValue>) {
        context["__native_latch_await_timeout"] = NativeFunkFunction("__native_latch_await_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_latch_await_timeout expects FunkRecord")

            val latch = record.fields["latch"] as? FunkLatch
                ?: throw FunkException("__native_latch_await_timeout expects 'latch' field of type Latch")

            val ms = (record.fields["ms"] as? FunkInt)?.value?.toLong()
                ?: throw FunkException("__native_latch_await_timeout expects 'ms' field of type Int")

            FunkBool(latch.latch.await(ms, TimeUnit.MILLISECONDS))
        }
    }

    /**
     * __native_latch_get_count: {latch: Latch} -> Int
     * Gets the current count of the latch.
     */
    private fun registerGetCount(context: MutableMap<String, FunkValue>) {
        context["__native_latch_get_count"] = NativeFunkFunction("__native_latch_get_count") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_latch_get_count expects FunkRecord")

            val latch = record.fields["latch"] as? FunkLatch
                ?: throw FunkException("__native_latch_get_count expects 'latch' field of type Latch")

            FunkInt(latch.latch.count)
        }
    }
}

/**
 * FunkLatch - wraps a Java CountDownLatch.
 */
data class FunkLatch(
    val latch: CountDownLatch
) : FunkValue() {
    override fun getDisplayString(): String {
        return "<latch:${latch.count} remaining>"
    }
}
