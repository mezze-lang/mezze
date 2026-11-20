package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The stall report (`CoopAsyncWith { on_stall }`), the user's answer to "what happens when a
 * wait never ends": the machine prints NOTHING of its own; a program that wants to know says
 * so where it chooses the performer, and the report is a Mezze function run under that
 * perform site's handlers — its effects are the site's, declared in the row.
 *
 * The only error the machine ever raises is an EMPTY wait set (`CoopAsyncSchedulerTest`).
 */
class StallReportTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun lines(source: String): List<String> =
        MezzeTestHelper.runWithOutput(source.trimIndent()).output.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** A promise nobody completes is as stuck as a predicate: after `after`, the report runs ONCE
     *  with the parked task, performs Console (handled OUTSIDE the perform, as its row says), and
     *  completes the promise itself — so the report can also be the thing that unsticks a program.
     *  (`promise_from`'s `complete` is pure, which is why a report may call it.) */
    @Test
    fun `a stalled await is reported once through the site's effects and the report may unstick it`() {
        val out = lines(
            """
            use std::effects::log::{ Console, println }
            use std::effects::async::{ Async, CoopAsyncWith, await, promise_from }
            use std::effects::time::{ SystemTime }
            use std::datetime::{ duration_millis }
            use std::collections::seq
            use std::result
            use std::int
            let main = {} -> perform Console in do
              let box = promise_from { setup = { complete, fail, on_cancel } -> {} }
              let r = perform SystemTime, CoopAsyncWith { on_stall = Some { val = { after = duration_millis { n = 150 }, f = { waiting } -> do
                  println { msg = "stall: $(int::to_str { n = seq::size { xs = waiting } }) parked" } } } } in do
                let p = Async.spawn { f = {} -> result::get_or { result = await { p = box }, def = 0 - 1 } }
                let t = Async.spawn { f = {} -> do
                  Async.park_until { predicate = {} -> False, ms = 400 }
                  7 }
                result::get_or { result = await { p = t }, def = 0 - 2 }
              println { msg = "result $(int::to_str { n = r })" }
            main {}
            """,
        )
        // three parked: the awaiter of `box`, the deadline task, and the perform body awaiting `t`
        assertEquals(listOf("stall: 3 parked", "result 7"), out)
    }

    /** The report completes the promise everyone waits on: the stall ends, the body finishes. */
    @Test
    fun `the report may complete the promise that stalled the machine`() {
        val out = lines(
            """
            use std::effects::log::{ Console, println }
            use std::effects::async::{ Async, CoopAsyncWith, await }
            use std::effects::time::{ SystemTime }
            use std::datetime::{ duration_millis }
            use std::result
            use std::int
            use std::effects::async::{ promise_from }
            let main = {} -> perform Console in do
              let r = perform SystemTime, CoopAsyncWith { on_stall = Some { val = { after = duration_millis { n = 150 }, f = { waiting } -> println { msg = "stalled" } } } } in do
                let p = promise_from { setup = { complete, fail, on_cancel } -> {} }
                let t = Async.spawn { f = {} -> do
                  Async.park_until { predicate = {} -> False, ms = 400 }
                  7 }
                result::get_or { result = await { p = t }, def = 0 - 2 }
              println { msg = "result $(int::to_str { n = r })" }
            main {}
            """,
        )
        assertEquals(listOf("stalled", "result 7"), out)
    }

    /** `None` is `CoopAsync`: nothing fires, the deadline-bounded park completes on its own. */
    @Test
    fun `None is silent`() {
        val out = lines(
            """
            use std::effects::log::{ Console, println }
            use std::effects::async::{ Async, CoopAsyncWith, await }
            use std::effects::time::{ SystemTime }
            use std::result
            use std::int
            let main = {} -> perform Console in do
              let r = perform SystemTime, CoopAsyncWith { on_stall = None } in do
                let t = Async.spawn { f = {} -> do
                  Async.park_until { predicate = {} -> False, ms = 60 }
                  3 }
                result::get_or { result = await { p = t }, def = 0 - 2 }
              println { msg = "result $(int::to_str { n = r })" }
            main {}
            """,
        )
        assertEquals(listOf("result 3"), out)
    }

    /** A failure inside the report is the program's failure: it reaches the entry as the
     *  perform's failure, exactly as a deadlock error does. */
    @Test
    fun `a failing report fails the perform`() {
        val ex = assertThrows(Throwable::class.java) {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, CoopAsyncWith, await }
                use std::effects::time::{ SystemTime }
                use std::datetime::{ duration_millis }
                use std::collections::seq
                use std::result
                perform SystemTime, CoopAsyncWith { on_stall = Some { val = { after = duration_millis { n = 100 }, f = { waiting } -> do
                    let zero = seq::size { xs = waiting } - seq::size { xs = waiting }
                    let _ = 1 / zero
                    {} } } } in do
                  let t = Async.spawn { f = {} -> do
                    Async.park { predicate = {} -> False }
                    1 }
                  result::get_or { result = await { p = t }, def = 0 - 2 }
                """.trimIndent(),
            )
        }
        val msg = (ex.message ?: ex.toString()).lowercase()
        assertTrue(msg.contains("zero") || msg.contains("divi"), "the report's failure must reach the entry: $ex")
    }

    /** ANY cont-capable performer may declare the report as impl-only fields, like `drive`:
     *  `stall_after = {} -> Duration`, `on_stall = { waiting } -> {}`. The report's effects are the
     *  performer's added effects, so `Console` must be handled at the perform site — it is. */
    @Test
    fun `a user performer declares the stall report as impl-only fields`() {
        val out = lines(
            """
            use std::effects::log::{ Console, println }
            use std::effects::async::{ ParkOn, WaitSet, ParkOutcome }
            use std::effects::time::{ SystemTime, now }
            use std::datetime::{ Duration, duration_millis, add, after }
            use std::collections::seq
            use std::int
            effect Wait where { wait_ms: { ms: Int } -> Bool <Wait>, }
            impl Wait for Waiter where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              stall_after = {} -> duration_millis { n = 100 },
              on_stall = { waiting } -> println { msg = "stall: $(int::to_str { n = seq::size { xs = waiting } }) parked" },
              wait_ms = { ms } -> do
                let deadline = add { i = now {}, d = duration_millis { n = ms } }
                match cont.park { on = OnPredicates { fs = [{} -> after { a = now {}, b = deadline }] }, set = WaitDefault } is
                  ParkReady { which } -> True
                  ParkCancelled { reason } -> False,
            }
            let main = {} -> perform Console, SystemTime, Waiter in do
              let _ = Wait.wait_ms { ms = 400 }
              println { msg = "done" }
            main {}
            """,
        )
        assertEquals(listOf("stall: 1 parked", "done"), out)
    }

    /** The report's effects are the performer's added effects: a perform site that installs the
     *  performer without handling Console carries `<Console>` in its row, so a pure annotation on
     *  it is rejected. (The CLI's entry check reports the same for `pub let main`; the test harness
     *  skips entry checks, so the assertion is on the type.) */
    @Test
    fun `an impl-only report's effects must be handled at the perform site`() {
        assertThrows(com.mezze.language.typechecker.TypeCheckError::class.java) {
            MezzeTestHelper.typeCheck(
                """
                use std::effects::log::{ Console, println }
                use std::effects::async::{ ParkOn, WaitSet, ParkOutcome }
                use std::effects::time::{ SystemTime, now }
                use std::datetime::{ Duration, duration_millis, add, after }
                effect Wait where { wait_ms: { ms: Int } -> Bool <Wait>, }
                impl Wait for Waiter where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  stall_after = {} -> duration_millis { n = 100 },
                  on_stall = { waiting } -> println { msg = "stalled" },
                  wait_ms = { ms } -> do
                    let deadline = add { i = now {}, d = duration_millis { n = ms } }
                    match cont.park { on = OnPredicates { fs = [{} -> after { a = now {}, b = deadline }] }, set = WaitDefault } is
                      ParkReady { which } -> True
                      ParkCancelled { reason } -> False,
                }
                let run: {} -> Bool = {} -> perform SystemTime, Waiter in Wait.wait_ms { ms = 10 }
                """.trimIndent(),
            )
        }
    }
}
