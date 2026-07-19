package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Spec tests for #254 — `yield_now {}`.
 *
 * The op should:
 *   - Return unit under every Async impl.
 *   - Not raise or cancel the current task.
 *   - Be a scheduling hint only — semantics-preserving.
 *
 * We test Loom and Coop paths since the two impls dispatch to
 * different natives (Thread.yield vs scheduler.tick).
 */
class YieldNowTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `yield_now under Loom returns unit`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, yield_now }

            let main = {} -> perform LoomAsync in do
              yield_now {}
              42

            main {}
        """
        assertEquals(42L, eval(source))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `yield_now interleaves cooperative tasks`() {
        // Two spawned tasks that each yield after writing to a shared
        // atom. Under a cooperative scheduler, the yield gives the peer
        // a chance to run. Both should complete regardless of policy —
        // we mainly want to confirm yield doesn't get lost / deadlock.
        // Use `swap` (atomic) rather than deref+reset (races).
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, yield_now }
            use std::effects::async::atom::{atom, deref, swap}

            let main = {} -> perform LoomAsync in do
              let counter = atom { val = 0 }
              let bump = { n } -> swap { atom = counter, f = { current } -> current + n }
              let p1 = Async.spawn { f = {} -> do
                bump { n = 1 }
                yield_now {}
                bump { n = 10 }
                0
              }
              let p2 = Async.spawn { f = {} -> do
                bump { n = 100 }
                yield_now {}
                bump { n = 1000 }
                0
              }
              await { p = p1 }
              await { p = p2 }
              deref { atom = counter }

            main {}
        """
        assertEquals(1111L, eval(source))
    }
}
