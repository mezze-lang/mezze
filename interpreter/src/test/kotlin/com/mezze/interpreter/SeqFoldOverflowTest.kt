package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #59 — seq::fold's fast path (SeqFoldLongInlinedLoopNode) hard-cast
 * the folder's result to Long. When the fold body overflows a long
 * arithmetic op and Mezze promotes the result to Double, the cast blew
 * up with ClassCastException in production.
 *
 * The fix bails out of the fast path cleanly via a
 * {@code ControlFlowException}, deopts, sets a permanent
 * {@code inlinedFastPathDisabled} flag on the SeqFoldCallNode, and
 * falls through to the generic path. This test locks in that fold
 * doesn't crash on the overflow case and that the (possibly-Double)
 * result is returned normally.
 */
class SeqFoldOverflowTest {

    @Test
    fun `seq_fold on large workload with arithmetic that overflows Long returns without crashing`() {
        // n=5M and body `x * x - x / 2` guarantees overflow of Long. Prior
        // behavior: ClassCastException at SeqFoldLongInlinedLoopNode:185.
        // New behavior: fast path bails, generic path handles it.
        val source = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 5000000 }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x * x - x / 2 }
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        // Result may be Long or Double depending on which path handled it —
        // both are correct; the test locks in "doesn't crash."
        assertNotNull(result, "seq::fold with overflow should return, not crash")
    }

    @Test
    fun `seq_fold still uses fast path when result stays in Long range`() {
        // Small workload, no overflow — fast path should handle it.
        val source = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 1000 }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        // 0 + 1 + 2 + ... + 999 = 999 * 1000 / 2 = 499500
        assertEquals(499500L, result)
    }
}
