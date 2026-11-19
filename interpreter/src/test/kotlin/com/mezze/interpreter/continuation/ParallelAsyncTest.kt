package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.TimingSensitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `ParallelAsync`: CoopAsync's scheduler with a carrier budget (`carriers = {} -> N`, the fourth
 * impl-only field). Tasks EXECUTE concurrently on up to N carriers; the machine's bookkeeping is
 * taken under the boundary's lock, which the single-carrier path never takes. A user performer
 * sets its own budget the same way.
 */
@TimingSensitive
class ParallelAsyncTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val imports = """
        use std::effects::async::{ Async, ParallelAsync, CoopAsync, await, sleep, yield_now }
        use std::effects::time::{ SystemTime, monotonic }
        use std::datetime::{ duration_to_nanos, sub_duration }
        use std::collections::seq
        use std::result
    """

    @Test
    fun `four tasks sleeping 80 ms each finish together under ParallelAsync and in turn under CoopAsync`() {
        fun wallMs(performer: String): Long = eval("""
            $imports
            perform SystemTime, $performer in do
              let t0 = monotonic {}
              let ps = [1, 2, 3, 4].map { f = { x } -> Async.spawn { f = {} -> do
                sleep { ms = 80 }
                x } }
              let total = seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 - 100 } }
              let t1 = monotonic {}
              if total == 10 then duration_to_nanos { d = sub_duration { a = t1, b = t0 } } / 1000000 else 0 - 1
        """) as Long
        val par = wallMs("ParallelAsync")
        val coop = wallMs("CoopAsync")
        assertTrue(par in 80..239, "parallel: four 80 ms sleeps overlap, got $par ms")
        assertTrue(coop >= 300, "cooperative: a sleep on a carrier parks the task, but one carrier runs them in turn, got $coop ms")
    }

    /** A user performer with its own budget, written against the machine's public surface exactly
     *  as CoopAsync is (an Async performer may not wrap another with nothing between them, so it
     *  does not forward to one). */
    @Test
    fun `a user performer with its own budget runs tasks concurrently`() {
        val ms = eval("""
            $imports
            use std::effects::async::{ spawn_task, promise_id, settled_result, inspect, ParkOn, WaitSet, ParkOutcome, CancelOutcome, AsyncError }
            impl Async for Two where {
              carriers = {} -> 2,
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              spawn = { f } -> spawn_task { f = f },
              await = { p } -> match inspect { p = p } is
                PromiseCompleted { val } -> Ok { val = val }
                PromiseFailed { err } -> Err { error = err }
                PromisePending -> match cont.park { on = OnPromises { ids = [promise_id { p = p }] }, set = WaitDefault } is
                  ParkReady { which } -> settled_result { p = p }
                  ParkCancelled { reason } -> Err { error = AsyncCancelled { reason = reason } },
              cancel = { p, reason } -> CancelAlreadyCompleted,
              yield_now = {} -> match cont.park { on = Immediately, set = WaitDefault } is
                ParkReady { which } -> {}
                ParkCancelled { reason } -> {},
              park = { predicate } -> match cont.park { on = OnPredicates { fs = [predicate] }, set = WaitDefault } is
                ParkReady { which } -> {}
                ParkCancelled { reason } -> {},
              park_until = { predicate, ms } -> match cont.park { on = OnPredicates { fs = [predicate] }, set = WaitDefault } is
                ParkReady { which } -> True
                ParkCancelled { reason } -> False,
            }
            perform SystemTime, Two in do
              let t0 = monotonic {}
              let ps = [1, 2].map { f = { x } -> Async.spawn { f = {} -> do
                sleep { ms = 80 }
                x } }
              let total = seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 - 100 } }
              let t1 = monotonic {}
              if total == 3 then duration_to_nanos { d = sub_duration { a = t1, b = t0 } } / 1000000 else 0 - 1
        """) as Long
        assertTrue(ms in 80..159, "two carriers: two 80 ms sleeps overlap, got $ms ms")
    }

    @Test
    fun `two hundred tasks yielding and summing give the same answer as CoopAsync`() {
        val program = { performer: String -> """
            $imports
            perform SystemTime, $performer in do
              let ps = seq::range { start = 1, end = 201 }.map { f = { x } -> Async.spawn { f = {} -> do
                yield_now {}
                let y = x * 2
                yield_now {}
                y } }
              seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 - 100000 } }
        """ }
        assertEquals(40200L, eval(program("CoopAsync")))
        assertEquals(40200L, eval(program("ParallelAsync")))
    }
}
