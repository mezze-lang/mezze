package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for CountDownLatch operations.
 *
 * Every native carries `<Async>` (post-#170).
 *
 * A latch allows threads to wait until a count reaches zero.
 */
object LatchTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        val latchType = WellKnownTypeApp.of("Latch", emptyList())

        // __native_latch_new: {count: Int} -> Latch <Async>
        fn("__native_latch_new") {
            param("count" to int)
            returns(latchType)
            effects("Async")
        }

        // __native_latch_count_down: {latch: Latch} -> {} <Async>
        fn("__native_latch_count_down") {
            param("latch" to latchType)
            returns(unit)
            effects("Async")
        }

        // __native_latch_await: {latch: Latch} -> {} <Async>
        fn("__native_latch_await") {
            param("latch" to latchType)
            returns(unit)
            effects("Async")
        }

        // __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool <Async>
        fn("__native_latch_await_timeout") {
            param("latch" to latchType, "ms" to int)
            returns(bool)
            effects("Async")
        }

        // __native_latch_get_count: {latch: Latch} -> Int <Async>
        fn("__native_latch_get_count") {
            param("latch" to latchType)
            returns(int)
            effects("Async")
        }
    }
}
