package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `seq::fold` whose body overflows — and what that now MEANS.
 *
 * ## What this test used to say, and why it changed
 *
 * #59 found `seq::fold`'s fast path (`SeqFoldLongInlinedLoopNode`) hard-casting the
 * folder's result to Long, which blew up with a `ClassCastException` once the body
 * overflowed and the arithmetic node widened the result to a Double. The fix bailed
 * out of the fast path via a `ControlFlowException`, deopted, and let the generic
 * path return the Double — and this test asserted only "doesn't crash", with the
 * comment "Result may be Long or Double depending on which path handled it — both
 * are correct."
 *
 * Both are NOT correct. `seq::fold` over `[Int]` with an `Int` accumulator is typed
 * to return an `Int`; a Double is a value of the wrong type for the declared one,
 * and tolerating it is what let the unsoundness live (S-6). The widening is gone:
 * overflow now raises the same fatal guest error `/ 0` does, so the Double the #59
 * fix was built to survive can no longer be produced.
 *
 * The first case therefore asserts the OPPOSITE of what it used to: the fold fails,
 * and fails as an arithmetic overflow naming the operation — not as a
 * `ClassCastException`, and not as a host error with a JVM stack trace.
 *
 * NOTE for whoever revisits the #59 machinery: the fast-path bailout may now be
 * unreachable for this reason, since nothing produces the Double it exists to
 * escape. It is left in place here rather than removed on inference — proving it
 * dead needs its own pass.
 */
class SeqFoldOverflowTest {

    @Test
    fun `a fold body that overflows fails as an overflow, not as a cast`() {
        // n=5M with `x * x - x / 2` guarantees the accumulator leaves Long range.
        val source = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 5000000 }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x * x - x / 2 }
        """.trimIndent()

        val e = runCatching { MezzeTestHelper.run(source) }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            "overflowed Int" in message,
            "expected an arithmetic-overflow guest error, got: $message",
        )
        // The point of the guest-error channel: NOT a host failure leaking through.
        assertTrue(
            "ClassCastException" !in message,
            "the Double-widening path is back — this is the S-6 regression: $message",
        )
    }

    @Test
    fun `a fold that stays in range still takes the fast path and is exact`() {
        val source = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 1000 }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()
        // 0 + 1 + … + 999 = 499500. Asserting the VALUE, not just "returned":
        // a wrapping or widening `+` would still return something here.
        assertEquals(499500L, MezzeTestHelper.run(source))
    }
}
