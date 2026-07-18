package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests for `uninterruptible { f = ... }` — the #251 region combinator.
 *
 * Semantics:
 *   - Inside the region, cancel is CAS-latched but delivery is deferred.
 *   - Async.check is a no-op inside the region.
 *   - Blocking natives (sleep, park, semaphoreAcquire, latchAwait,
 *     waitgroupWait) re-block instead of surfacing AsyncCancelled.
 *   - is_cancelled continues to report truth (the state IS cancelled).
 *   - On region exit, deferred interrupt is re-armed so the next
 *     blocking op / check immediately surfaces AsyncCancelled.
 *   - Nested regions stack — inner exit doesn't clear the flag while
 *     an outer region is still active.
 */
class UninterruptibleTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `check inside region does not throw when cancelled`() {
        // spawn a child, cancel it, and inside its uninterruptible region
        // observe that check() is a no-op even though is_cancelled is
        // true. Verifies P3 semantics.
        val source = """
            use std::effects::async::{Async, LoomAsync, uninterruptible, UserRequested}
            use std::effects::time::{Time, SystemTime}
            perform SystemTime in perform LoomAsync in do
              let ch = Async.spawn { f = {} -> do
                uninterruptible { f = {} -> do
                  Async.sleep { ms = 30 }
                  Async.check {}
                  "ran-to-end"
                } }
              Async.sleep { ms = 5 }
              Async.cancel { p = ch, reason = UserRequested { msg = "test" } }
              # await result — spawned task should have returned normally
              # because check was a no-op inside the region.
              match Async.await { p = ch } is
                Ok { val } -> val
                Err { error = _ } -> "was-cancelled"
        """.trimIndent()
        assertEquals("ran-to-end", eval(source))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `sleep inside region completes full duration despite cancel`() {
        // sleep 60ms inside a region, cancel at 10ms; sleep should still
        // block for ~60ms total (the remaining ~50ms after the interrupt).
        // Verify the total ran ~50ms by measuring around the eval.
        val source = """
            use std::effects::async::{Async, LoomAsync, uninterruptible, UserRequested}
            use std::effects::time::{Time, SystemTime}
            perform SystemTime in perform LoomAsync in do
              let ch = Async.spawn { f = {} -> do
                uninterruptible { f = {} -> do
                  Async.sleep { ms = 60 }
                  "slept-full" } }
              Async.sleep { ms = 10 }
              Async.cancel { p = ch, reason = UserRequested { msg = "test" } }
              match Async.await { p = ch } is
                Ok { val } -> val
                Err { error = _ } -> "cancelled-early"
        """.trimIndent()
        val t0 = System.nanoTime()
        val result = eval(source)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("slept-full", result)
        // The sleep inside was 60ms, cancel at 10ms; the region defers.
        // Total should be at least ~60ms (the sleep's original duration).
        assertTrue(elapsedMs >= 55,
            "expected elapsed >= 55ms (sleep ran full duration); got ${elapsedMs}ms")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `nested regions require both exits to un-defer`() {
        // uninterruptible { uninterruptible { sleep 30 } }, cancel at 5ms
        // — should still sleep full 30ms because inner exit leaves depth
        // at 1 (outer is still active).
        val source = """
            use std::effects::async::{Async, LoomAsync, uninterruptible, UserRequested}
            use std::effects::time::{Time, SystemTime}
            perform SystemTime in perform LoomAsync in do
              let ch = Async.spawn { f = {} ->
                uninterruptible { f = {} ->
                  uninterruptible { f = {} -> do
                    Async.sleep { ms = 40 }
                    "inner-done" } } }
              Async.sleep { ms = 5 }
              Async.cancel { p = ch, reason = UserRequested { msg = "test" } }
              match Async.await { p = ch } is
                Ok { val } -> val
                Err { error = _ } -> "cancelled-early"
        """.trimIndent()
        assertEquals("inner-done", eval(source))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `deferred cancel fires on next check after region exit`() {
        // Cancel mid-region → region completes → check() after region
        // should throw. Verifies the interrupt-pending re-arm logic in
        // loomUninterruptibleExit.
        val source = """
            use std::effects::async::{Async, LoomAsync, uninterruptible, UserRequested}
            use std::effects::time::{Time, SystemTime}
            perform SystemTime in perform LoomAsync in do
              let ch = Async.spawn { f = {} -> do
                uninterruptible { f = {} -> Async.sleep { ms = 30 } }
                # region has exited; interrupt was pending — check
                # should now throw, giving us an AsyncCancelled result.
                Async.check {}
                "did-not-throw"
              }
              Async.sleep { ms = 5 }
              Async.cancel { p = ch, reason = UserRequested { msg = "test" } }
              match Async.await { p = ch } is
                Ok { val } -> val
                Err { error = _ } -> "cancelled-after-region"
        """.trimIndent()
        assertEquals("cancelled-after-region", eval(source))
    }
}
