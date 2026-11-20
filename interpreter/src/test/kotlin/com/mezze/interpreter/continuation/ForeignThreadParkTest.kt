package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "Nothing should break under any async handler": a cont-capable performer's op reached from
 * a FOREIGN thread — a LoomAsync task, an HTTP handler thread, a polyglot callback — must still
 * be able to `cont.park`. The foreign thread is not a carrier of the machine; it posts its park
 * to the boundary and blocks until the machine delivers the outcome, while the boundary's own
 * tasks keep running one at a time.
 */
class ForeignThreadParkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val parker = """
        use std::effects::async::{ Async, LoomAsync, CoopAsync, ParkOn, WaitSet, ParkOutcome, await }
        use std::result
        effect Wait where { wait: {} -> Int <Wait>, }
        impl Wait for Parker where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          wait = {} -> match cont.park { on = OnPredicates { fs = [{} -> True] }, set = WaitDefault } is
            ParkReady { which } -> 7 + which
            ParkCancelled { reason } -> 0 - 1,
        }
    """

    @Test
    fun `a Loom task parks on an outer cont-capable performer and gets its outcome`() = assertEquals(
        7L,
        eval(
            """
            $parker
            perform Parker in perform LoomAsync in do
              let p = Async.spawn { f = {} -> Wait.wait {} }
              result::get_or { result = await { p = p }, def = -100 }
            """,
        ),
    )

    @Test
    fun `several Loom tasks park on the same boundary while its body keeps running`() = assertEquals(
        "21,3",
        eval(
            """
            $parker
            use std::collections::seq
            perform Parker in perform LoomAsync in do
              let ps = [1, 2, 3].map { f = { x } -> Async.spawn { f = {} -> Wait.wait {} } }
              let body = Wait.wait {} + Wait.wait {} + Wait.wait {} - 18
              let total = seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = -100 } }
              "${'$'}(total.to_str {}),${'$'}(body.to_str {})"
            """,
        ),
    )

    /** The idle verdict vs a promise settling on a host thread: the body host-awaits a Loom task
     *  that finishes within microseconds; before the fix the verdict found the promise already
     *  settled (so "not external"), nothing else parked, and declared a deadlock — 3/3 probe runs.
     *  Sixty rounds here to cross the window reliably. */
    @Test
    fun `a promise settling between the wake check and the idle verdict is not a deadlock`() = assertEquals(
        1830L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, await, yield_now }
            use std::result
            let step = { acc, i } -> if i <= 0 then acc else do
              let r = perform LoomAsync in do
                let p = Async.spawn { f = {} -> i }
                result::get_or { result = await { p = p }, def = 0 - 1 }
              step { acc = acc + r, i = i - 1 }
            perform CoopAsync in do
              let total = step { acc = 0, i = 60 }
              yield_now {}
              total
            """,
        ),
    )

    /** Flaw 2 of the carrier design, fixed: a task parked on a PLAIN predicate over state that a
     *  foreign thread will change was reported as a deadlock (nothing in the machine could wake
     *  it). The machine never guesses now: it re-checks with backoff and only an EMPTY wait set
     *  is an error. */
    @Test
    fun `a predicate flipped by a Loom thread is waited for, not declared a deadlock`() = assertEquals(
        1L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, park, sleep }
            use std::effects::time::{ SystemTime }
            use std::effects::async::atom::{ atom, deref, reset }
            use std::result
            perform SystemTime, CoopAsync in do
              let flag = atom { val = 0 }
              perform LoomAsync in do
                let _ = Async.spawn { f = {} -> do
                  sleep { ms = 30 }
                  reset { atom = flag, val = 1 } }
                {}
              park { predicate = {} -> deref { atom = flag } == 1 }
              deref { atom = flag }
            """,
        ),
    )

    /** A channel fed by a Loom thread and drained by a cooperative task: the send signals the
     *  channel's wait queue from the foreign thread and the parked receiver is woken at once —
     *  no poll, no deadlock verdict. */
    @Test
    fun `a channel fed from a Loom thread wakes the cooperative receiver`() = assertEquals(
        4950L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, await, sleep }
            use std::effects::async::channel::{ chan_bounded, send, recv }
            use std::effects::time::{ SystemTime }
            use std::collections::seq
            use std::result
            perform SystemTime, CoopAsync in do
              let ch = chan_bounded { cap = 4 }
              let producer = perform LoomAsync in Async.spawn { f = {} -> do
                seq::range { start = 0, end = 100 }.for_each { f = { x } -> do
                  sleep { ms = 1 }
                  send { ch = ch, val = x } }
                {} }
              seq::fold { xs = seq::range { start = 0, end = 100 }, init = 0, f = { acc, x } -> acc + recv { ch = ch } }
            """,
        ),
    )

    /** `Time.sleep` inside a cooperative task used to block its carrier (the memory probe's gate
     *  stalled the whole machine for its four seconds): now it parks the task, so three tasks sleeping
     *  60 ms each on ONE carrier finish together. */
    @Test
    fun `a Time sleep inside a cooperative task parks it instead of blocking its carrier`() {
        val ms = eval(
            """
            use std::effects::async::{ Async, CoopAsync, await, sleep }
            use std::effects::time::{ SystemTime, monotonic }
            use std::datetime::{ duration_to_nanos, sub_duration }
            use std::collections::seq
            use std::result
            perform SystemTime, CoopAsync in do
              let t0 = monotonic {}
              let ps = [1, 2, 3].map { f = { x } -> Async.spawn { f = {} -> do
                sleep { ms = 60 }
                x } }
              let total = seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 - 100 } }
              if total == 6 then duration_to_nanos { d = sub_duration { a = monotonic {}, b = t0 } } / 1000000 else 0 - 1
            """,
        ) as Long
        assertTrue(ms in 60..149, "three 60 ms sleeps on one carrier overlap when they PARK, got $ms ms")
    }

    /** The surface a userland reactor needs (an io_uring or any Java-library backend): build a
     *  promise with `promise_from`, hand `complete` to a foreign thread, and let a cooperative task
     *  await it. The machine idles with nothing to do, the foreign thread completes the promise, and
     *  the settlement wakes the idle carrier at once — no poll, no deadlock verdict. */
    @Test
    fun `a promise completed later from a foreign thread wakes the idle cooperative awaiter`() = assertEquals(
        42L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, await, sleep, promise_from }
            use std::effects::time::{ SystemTime }
            use std::result
            perform SystemTime, CoopAsync in do
              let p = promise_from { setup = { complete, fail, on_cancel } -> perform LoomAsync in do
                let _ = Async.spawn { f = {} -> do
                  sleep { ms = 30 }
                  complete { val = 42 } }
                {} }
              result::get_or { result = await { p = p }, def = 0 - 1 }
            """,
        ),
    )

    /** A foreign thread SPAWNING a cooperative task: the Loom task reaches the outer CoopAsync's
     *  `spawn` through `Async.spawn` only if CoopAsync is innermost for it — so spell the outer
     *  one through a handler the inner performer does not shadow: a user effect whose op spawns. */
    @Test
    fun `a Loom task spawns a cooperative task on the outer boundary and awaits it`() = assertEquals(
        7L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, await }
            use std::result
            effect Spawner where { go: {} -> Int <Spawner>, }
            impl Spawner for CoopSpawner where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              go = {} -> do
                let p = Async.spawn { f = {} -> 3 + 4 }
                result::get_or { result = await { p = p }, def = -1 },
            }
            perform CoopAsync in perform CoopSpawner in perform LoomAsync in do
              let p = Async.spawn { f = {} -> Spawner.go {} }
              result::get_or { result = await { p = p }, def = -100 }
            """,
        ),
    )

    @Test
    fun `a Loom task awaiting a Coop promise through the outer scheduler`() = assertEquals(
        42L,
        eval(
            """
            use std::effects::async::{ Async, LoomAsync, CoopAsync, await }
            use std::result
            perform CoopAsync in do
              let coop = Async.spawn { f = {} -> 40 + 2 }
              perform LoomAsync in do
                let p = Async.spawn { f = {} -> result::get_or { result = Async.await { p = coop }, def = -1 } }
                result::get_or { result = await { p = p }, def = -100 }
            """,
        ),
    )
}
