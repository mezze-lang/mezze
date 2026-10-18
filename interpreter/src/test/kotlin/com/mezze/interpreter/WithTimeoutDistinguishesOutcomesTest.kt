package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `with_timeout` tells its three outcomes apart.
 *
 * It used to answer `Option`, which collapsed "the deadline elapsed" and "the
 * promise failed or was cancelled" into one `None` and sent the caller back to
 * `inspect` — a second observation of a promise that had already answered. The
 * signature is now `Result 'a AsyncError`, so the deadline is
 * `AsyncTimedOut { ms }` and a failure carries the promise's OWN error.
 *
 * Only the success case was covered before. The whole point of the change is
 * the two unhappy cases being DIFFERENT, so each one is named here: a test that
 * only checks "it was an Err" would pass with the distinction collapsed again.
 */
class WithTimeoutDistinguishesOutcomesTest {

    private val head = """
        use std::effects::async::{ Async, LoomAsync, await, sleep, with_timeout,
                                   cancel, promise_from,
                                   AsyncError, AsyncFailed, AsyncCancelled, AsyncTimedOut,
                                   CancelReason, UserRequested, Timeout }
        use std::effects::time::{ Time, SystemTime }
        use std::int
    """.trimIndent()

    private fun outcome(body: String): Any = MezzeTestHelper.run(
        head + "\n" + body.trimIndent(),
    )

    @Test
    fun `a value that arrives in time is Ok`() {
        val r = outcome(
            """
            perform SystemTime in perform LoomAsync in do
              let p = Async.spawn { f = {} -> 42 }
              match with_timeout { ms = 2000, p = p } is
                Ok { val } -> "ok:$(int::to_str { n = val })"
                Err { error = AsyncTimedOut { ms = _ } } -> "timed-out"
                Err { error = _ } -> "other-error"
            """,
        )
        assertEquals("ok:42", r)
    }

    @Test
    fun `a deadline that elapses is AsyncTimedOut, carrying the ms it was given`() {
        // The distinguishing case. `ms` has to come back, because a caller that
        // retries needs to know which deadline it was.
        val r = outcome(
            """
            perform SystemTime in perform LoomAsync in do
              let p = Async.spawn { f = {} -> do
                sleep { ms = 10000 }
                1 }
              match with_timeout { ms = 50, p = p } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = AsyncTimedOut { ms = m } } -> "timed-out:$(int::to_str { n = m })"
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg = _ } } -> "failed"
            """,
        )
        assertEquals("timed-out:50", r, "the deadline case must be AsyncTimedOut and must carry its ms")
    }

    @Test
    fun `a promise cancelled before the deadline is AsyncCancelled, NOT AsyncTimedOut`() {
        // This is the case `Option` lost. The deadline never elapsed — the
        // promise answered first, with a cancellation — and the caller can see
        // the reason it was given.
        val r = outcome(
            """
            perform SystemTime in perform LoomAsync in do
              let p = Async.spawn { f = {} -> do
                sleep { ms = 10000 }
                1 }
              let _ = cancel { p = p, reason = UserRequested { msg = "mine" } }
              match with_timeout { ms = 5000, p = p } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = AsyncTimedOut { ms = _ } } -> "timed-out"
                Err { error = AsyncCancelled { reason = UserRequested { msg = m } } } -> "cancelled:$(m)"
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled:other"
                Err { error = AsyncFailed { msg = _ } } -> "failed"
            """,
        )
        assertEquals(
            "cancelled:mine", r,
            "a cancellation must not be reported as a timeout, and must carry the caller's own reason",
        )
    }

    @Test
    fun `a promise that fails before the deadline reports its OWN error`() {
        // `promise_from` lets the test fail a promise explicitly rather than
        // relying on a guest crash, so the message is the one asserted on.
        val r = outcome(
            """
            perform SystemTime in perform LoomAsync in do
              let p = promise_from { setup = { complete, fail, on_cancel } ->
                fail { err = AsyncFailed { msg = "boom" } } }
              match with_timeout { ms = 5000, p = p } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = AsyncTimedOut { ms = _ } } -> "timed-out"
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg = m } } -> "failed:$(m)"
            """,
        )
        assertEquals("failed:boom", r, "a failure must surface the promise's own error, not a timeout")
    }
}
