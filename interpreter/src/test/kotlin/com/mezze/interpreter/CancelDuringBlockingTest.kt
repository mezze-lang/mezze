package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression gate for §1 Cancel (#232, #233, #235).
 *
 * Each test spawns a task blocked in a specific native, fires
 * `cancel`, and asserts the awaiter sees
 * `Err { error = AsyncCancelled }` — NOT a `RuntimeException`
 * surfaced as task failure.
 *
 * Before the §1 work, every blocking native rethrew
 * `InterruptedException` as `RuntimeException("X interrupted")`,
 * so the awaiter would see `AsyncFailed { msg = ... }` instead of
 * `AsyncCancelled`. The fix translates each native to
 * `CancellationException`, which `runPromiseTask` recognises as
 * cancellation and transitions the promise to CANCELLED.
 */
class CancelDuringBlockingTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * Returns "cancelled" if the awaiter saw Err AsyncCancelled,
     * a distinguishing tag otherwise. Compared against literal
     * "cancelled" so failures surface what we got.
     */
    private fun cancelDuringBlock(blockingExpr: String): String {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await, cancel, park, sleep }
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in perform LoomAsync in do
              let child = Async.spawn { f = {} -> do
                $blockingExpr
                "should-not-reach"
              }
              sleep { ms = 50 }
              cancel { p = child, reason = UserRequested { msg = "cancel test" } }
              match await { p = child } is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
                Ok  { val = _ }                      -> "wrong-ok"
        """
        return eval(source) as String
    }

    @Test
    fun `cancel-during-sleep returns AsyncCancelled`() {
        assertEquals("cancelled", cancelDuringBlock("sleep { ms = 60000 }"))
    }

    @Test
    fun `cancel-during-park returns AsyncCancelled`() {
        assertEquals(
            "cancelled",
            cancelDuringBlock("park { predicate = {} -> False }")
        )
    }

    @Test
    fun `cancel-during-mutex-lock returns AsyncCancelled`() {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await, cancel, park, sleep }
            use std::effects::time::{Time, SystemTime}
            use std::effects::async::mutex::{mutex, with_lock}

            perform SystemTime in perform LoomAsync in do
              let m = mutex {}
              # Holder task acquires the lock and sleeps forever.
              let holder = Async.spawn { f = {} -> with_lock {
                mutex = m,
                f = {} -> do
                  sleep { ms = 60000 }
                  {}
              } }
              # Wait for holder to acquire.
              sleep { ms = 50 }
              # Waiter task tries to lock — will park.
              let waiter = Async.spawn { f = {} -> with_lock {
                mutex = m,
                f = {} -> "ran"
              } }
              sleep { ms = 50 }
              cancel { p = waiter, reason = UserRequested { msg = "cancel test" } }
              let waiter_result = await { p = waiter }
              # Clean up holder.
              cancel { p = holder, reason = UserRequested { msg = "cancel test" } }
              await { p = holder }
              match waiter_result is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
                Ok  { val = _ }                      -> "wrong-ok"
        """
        assertEquals("cancelled", eval(source))
    }

    @Test
    fun `cancel-during-semaphore-acquire returns AsyncCancelled`() {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await, cancel, park, sleep }
            use std::effects::time::{Time, SystemTime}
            use std::effects::async::semaphore::{semaphore, acquire}

            perform SystemTime in perform LoomAsync in do
              # Semaphore with 1 permit, immediately consumed.
              let s = semaphore { permits = 1 }
              acquire { sem = s }
              # Waiter blocks on acquire.
              let waiter = Async.spawn { f = {} -> do
                acquire { sem = s }
                "ran"
              }
              sleep { ms = 50 }
              cancel { p = waiter, reason = UserRequested { msg = "cancel test" } }
              match await { p = waiter } is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
                Ok  { val = _ }                      -> "wrong-ok"
        """
        assertEquals("cancelled", eval(source))
    }

    @Test
    fun `cancel-during-channel-recv returns AsyncCancelled`() {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await, cancel, park, sleep }
            use std::effects::time::{Time, SystemTime}
            use std::effects::async::channel::{chan, recv}

            perform SystemTime in perform LoomAsync in do
              let c = chan {}
              let waiter = Async.spawn { f = {} -> do
                let _ = recv { ch = c }
                "ran"
              }
              sleep { ms = 50 }
              cancel { p = waiter, reason = UserRequested { msg = "cancel test" } }
              match await { p = waiter } is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
                Ok  { val = _ }                      -> "wrong-ok"
        """
        assertEquals("cancelled", eval(source))
    }

    @Test
    fun `cancel-during-latch-await returns AsyncCancelled`() {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await, cancel, park, sleep }
            use std::effects::time::{Time, SystemTime}
            use std::effects::async::latch::{latch, await_latch}

            perform SystemTime in perform LoomAsync in do
              # Latch that never counts down.
              let l = latch { count = 1 }
              let waiter = Async.spawn { f = {} -> do
                await_latch { latch = l }
                "ran"
              }
              sleep { ms = 50 }
              cancel { p = waiter, reason = UserRequested { msg = "cancel test" } }
              match await { p = waiter } is
                Err { error = AsyncCancelled { reason = _ } } -> "cancelled"
                Err { error = AsyncFailed { msg } }  -> "wrong-failed"
                Err { error = AsyncTimedOut { ms } } -> "wrong-timedout"
                Ok  { val = _ }                      -> "wrong-ok"
        """
        assertEquals("cancelled", eval(source))
    }
}
