package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression gate for #280 (CoopAsync.is_cancelled).
 *
 * Before the §1 work, CoopAsync.is_cancelled was hardcoded to False
 * (async.mz:260), so a coop task could never observe its own
 * cancellation. The fix wires CoopScheduler.isCancelledCurrent
 * through __native_scheduler_is_cancelled.
 *
 * Surfaced a SECOND pre-existing bug during this fix: the helpers
 * asyncCancelledResult / asyncFailedResult in AsyncNatives.java
 * returned the raw AsyncError variant instead of wrapping in
 * Err { error = ... }. Users pattern-matching on Result got a tag
 * mismatch. Fixed in the same change.
 */
class CoopAsyncCancelTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * Task parked on `is_cancelled` predicate — must terminate when
     * cancelled, returning its String result via Ok.
     */
    @Test
    fun `CoopAsync is_cancelled flips after cancel`() {
        val source = """
            use std::effects::async::{Async, CoopAsync, AsyncError}

            perform CoopAsync in do
              let child = Async.spawn { f = {} -> do
                Async.park { predicate = {} -> Async.is_cancelled {} }
                "saw-cancel"
              }
              Async.cancel { p = child, reason = UserRequested { msg = "cancel test" } }
              match Async.await { p = child } is
                Ok  { val = v }                      -> v
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled-via-err"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
        """
        // Either "saw-cancel" (body exits cleanly after observing cancel)
        // or "cancelled-via-err" (body was preempted by tick-loop catch).
        // The BROKEN behaviour would deadlock or pattern-mismatch.
        val result = eval(source) as String
        assert(result == "saw-cancel" || result == "cancelled-via-err") {
            "expected saw-cancel or cancelled-via-err, got: $result"
        }
    }

    /**
     * Async.check {} throws under Coop when running task is cancelled.
     * Body parks until cancel; resumes; calls check; check throws
     * CancellationException; tick-loop catches it and marks promise
     * CANCELLED. Awaiter sees Err AsyncCancelled.
     */
    @Test
    fun `CoopAsync check throws after cancel`() {
        val source = """
            use std::effects::async::{Async, CoopAsync, AsyncError}

            perform CoopAsync in do
              let child = Async.spawn { f = {} -> do
                Async.park { predicate = {} -> Async.is_cancelled {} }
                Async.check {}
                "should-not-reach"
              }
              Async.cancel { p = child, reason = UserRequested { msg = "cancel test" } }
              match Async.await { p = child } is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Ok  { val = v }                      -> v
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
        """
        assertEquals("cancelled", eval(source))
    }
}
