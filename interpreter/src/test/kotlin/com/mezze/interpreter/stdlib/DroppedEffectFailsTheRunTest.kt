package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * A program that drops an effect FAILS, rather than answering.
 *
 * C1's remaining hole was never the type system — a named entry performing an
 * undischarged effect is refused, and `mez build` / `mez dev` take their entry
 * from `Mezze.toml`, so no shipping path accepts the shape. The hole was the
 * test harness, where a bare top-level expression is allowed: a spawned `sleep`
 * with no `Time` performer completed, the effect never happened, and the run
 * handed back an answer computed without it. That is how the `with_timeout`
 * flake was built.
 *
 * Reporting it on stderr made it audible. A line on stderr is still something a
 * test runs straight past, so the report is now latched and `run` raises on it.
 */
class DroppedEffectFailsTheRunTest {

    @Test
    fun `a spawned effect nobody awaits fails the run instead of answering`() {
        val boom = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, LoomAsync, sleep, yield_now }
                use std::collections::seq
                perform LoomAsync in do
                  let p = Async.spawn { f = {} -> sleep { ms = 1000 } }
                  ## Give the task a turn, so it reaches the op it cannot
                  ## discharge. Without this the body returns and the context
                  ## closes before there is anything to report.
                  seq::for_each { xs = seq::range { start = 0, end = 200 }, f = { x } -> yield_now {} }
                  7
                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertTrue(
            boom is AssertionError,
            "the run should have failed, but it returned normally — got: $boom",
        )
        assertTrue(
            boom.message!!.contains("dropped an effect"),
            "the failure must say what went wrong; got: ${boom.message}",
        )
        assertTrue(
            boom.message!!.contains("runAllowingDroppedEffects"),
            "the failure must name the opt-out for a test that means to do this; got: ${boom.message}",
        )
    }

    @Test
    fun `a spawned effect that IS awaited does not fail the run`() {
        // The other direction, and the one that matters for not breaking
        // propagation: a spawned task may perform the parent's effects, and
        // `await` is what discharges them. That must stay a normal program.
        val r = MezzeTestHelper.run(
            """
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{ Time, SystemTime }
            perform SystemTime in perform LoomAsync in do
              let p = Async.spawn { f = {} -> do
                sleep { ms = 1 }
                7 }
              match await { p = p } is
                Ok { val } -> val
                Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(7L, r, "an awaited spawn discharges its effect and is not a drop")
    }

    @Test
    fun `the latch does not leak into the next run on the same thread`() {
        // The report is cleared on read. If it were not, this second program —
        // which drops nothing — would inherit the first one's failure, and the
        // whole suite would go red in file order.
        runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, LoomAsync, sleep, yield_now }
                use std::collections::seq
                perform LoomAsync in do
                  let p = Async.spawn { f = {} -> sleep { ms = 1000 } }
                  seq::for_each { xs = seq::range { start = 0, end = 200 }, f = { x } -> yield_now {} }
                  7
                """.trimIndent(),
            )
        }
        val clean = MezzeTestHelper.run("let main = {} -> 1 + 1\nmain {}")
        assertEquals(2L, clean, "a clean program after a dropping one must still pass")
    }
}
