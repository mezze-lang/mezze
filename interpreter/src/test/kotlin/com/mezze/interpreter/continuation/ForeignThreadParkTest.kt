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
