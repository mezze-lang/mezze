package com.mezze.interpreter.effects

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * An effect op must resolve against the CURRENT THREAD's handler chain.
 *
 * `PerformD`'s cached specialization lives on a BytecodeNode shared by every
 * thread, while the handler chain is thread-local and the stability
 * `Assumption` is process-global. Without an identity guard re-checking that
 * the cached handler is still this thread's innermost handler, whichever
 * thread re-specialized last wins for all of them — so two tasks performing
 * the same op under different handlers silently read each other's handler.
 *
 * Failure mode is a wrong VALUE, never an exception, and only after the
 * uncached→cached transition — which is why a short-loop test can't see it.
 * The loop below is sized to cross that threshold comfortably.
 */
class ConcurrentHandlerIsolationTest {

    private val prelude = """
        use std::effects::async::{ Async, LoomAsync, await }
        use std::effects::reader::{ Reader, WithReader }
        use std::collections::seq

        let read_sum = { n } ->
          seq::range { start = 0, end = n }
            .fold { init = 0, f = { acc, x } -> acc + Reader.ask {} }

        let task = { v, n } ->
          perform WithReader { val = v } in read_sum { n = n }
    """.trimIndent()

    /** `await` yields `Ok { val = n }`; peel variant/record wrappers down to the Long. */
    private tailrec fun unwrap(v: Any?): Long = when (v) {
        is Long -> v
        is MezzeVariant -> unwrap(v.payloadValue)
        is MezzeRecord -> unwrap(v.getField(0))
        else -> error("unexpected value: $v (${v?.javaClass})")
    }

    private fun field(result: Any, name: String): Long {
        val rec = result as MezzeRecord
        return unwrap(rec.getField(rec.getFieldIndex(name)))
    }

    @Test
    @Timeout(120)
    fun `two threads performing the same op read their own handler, not each other's`() {
        val src = """
            $prelude
            perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> task { v = 1, n = 2000 } }
              let p2 = Async.spawn { f = {} -> task { v = 1000, n = 2000 } }
              let a = await { p = p1 }
              let b = await { p = p2 }
              { a = a, b = b }
        """.trimIndent()

        // Repeat: the interleaving that steals the specialization is racy.
        repeat(5) { round ->
            val r = MezzeTestHelper.run(src)
            assertEquals(2_000L, field(r, "a"), "round $round: task a read another thread's handler")
            assertEquals(2_000_000L, field(r, "b"), "round $round: task b read another thread's handler")
        }
    }

    @Test
    @Timeout(120)
    fun `sequential performs under different handlers stay isolated`() {
        val src = """
            $prelude
            do
              let a = task { v = 1, n = 2000 }
              let b = task { v = 1000, n = 2000 }
              { a = a, b = b }
        """.trimIndent()
        val r = MezzeTestHelper.run(src)
        assertEquals(2_000L, field(r, "a"))
        assertEquals(2_000_000L, field(r, "b"))
    }
}
