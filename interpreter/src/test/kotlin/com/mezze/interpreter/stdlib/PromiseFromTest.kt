package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for user-space Promise constructors (#77):
 *   - promise_from — the primitive; setup callback receives {complete, fail}
 *   - promise_of   — sugar over promise_from that pre-completes
 *   - promise_failed — sugar over promise_from that pre-fails
 *
 * All three produce a Promise 'a that composes with the existing
 * await / inspect / cancel operations. No new Async handler needed.
 */
class PromiseFromTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==================== promise_of ====================

    @Test
    fun `promise_of then await returns Ok val`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_of, await }
            perform LoomAsync in
              match await { p = promise_of { val = 42 } } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `promise_of is immediately done via inspect`() {
        val code = """
            use std::effects::async::{ promise_of, is_done }
            is_done { p = promise_of { val = 7 } }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    // ==================== promise_failed ====================

    @Test
    fun `promise_failed then await returns Err`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, AsyncError, promise_failed, await }
            perform LoomAsync in
              match await {
                p = promise_failed { err = AsyncFailed { msg = "boom" } }
              } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    @Test
    fun `promise_failed is immediately failed`() {
        val code = """
            use std::effects::async::{ promise_failed, is_failed }
            is_failed { p = promise_failed { err = AsyncFailed { msg = "boom" } } }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    // ==================== promise_from — synchronous complete ====================

    @Test
    fun `promise_from setup calls complete synchronously`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await }
            perform LoomAsync in
              match await {
                p = promise_from { setup = { complete, fail, on_cancel } -> complete { val = 99 } }
              } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(99L, eval(code))
    }

    @Test
    fun `promise_from setup calls fail synchronously`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await }
            perform LoomAsync in
              match await {
                p = promise_from { setup = { complete, fail, on_cancel } ->
                  fail { err = AsyncFailed { msg = "oops" } }
                }
              } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== promise_from — CAS idempotency ====================

    @Test
    fun `double complete — first wins`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await }
            perform LoomAsync in
              match await {
                p = promise_from { setup = { complete, fail, on_cancel } -> do
                  complete { val = 1 }
                  complete { val = 2 }
                }
              } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `complete then fail — complete wins`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await }
            perform LoomAsync in
              match await {
                p = promise_from { setup = { complete, fail, on_cancel } -> do
                  complete { val = 5 }
                  fail { err = AsyncFailed { msg = "ignored" } }
                }
              } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    // ==================== promise_from — no capability invoked ====================

    @Test
    fun `setup that calls nothing leaves promise pending`() {
        val code = """
            use std::effects::async::{ promise_from, is_pending }
            is_pending { p = promise_from { setup = { complete, fail, on_cancel } -> {} } }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    // ==================== promise_from — deferred completion via spawn ====================

    @Test
    fun `promise_from with background spawn completes when task finishes`() {
        // The real-world case: setup launches work on another thread that
        // calls `complete` when done. This mirrors what a Kotlin coroutine
        // backend would do — spawn a fiber, resolve the promise from it.
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await, go }
            perform LoomAsync in
              match await {
                p = promise_from { setup = { complete, fail, on_cancel } ->
                  go { f = {} -> complete { val = 123 } }
                }
              } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(123L, eval(code))
    }

    // ==================== on_cancel capability (#80) ====================

    @Test
    fun `on_cancel hook fires when promise is cancelled`() {
        // Register a hook that writes to an atom; verify the atom was
        // updated after Async.cancel fires.
        val code = """
            use std::effects::async::{ Async, LoomAsync, LostRace, promise_from, cancel, is_cancelled }
            use std::effects::async::atom::{ atom, reset, deref }

            perform LoomAsync in do
              let observed = atom { val = 0 }
              let p = promise_from { setup = { complete, fail, on_cancel } ->
                on_cancel { hook = { reason } -> do
                reset { atom = observed, val = 42 }
                {}
              }
              }
              cancel { p = p, reason = LostRace {} }
              deref { atom = observed }
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `on_cancel hook does not fire when promise completes normally`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from, await }
            use std::effects::async::atom::{ atom, reset, deref }

            perform LoomAsync in do
              let observed = atom { val = 0 }
              let p = promise_from { setup = { complete, fail, on_cancel } -> do
                on_cancel { hook = { reason } -> do
                  reset { atom = observed, val = 99 }
                  {}
                }
                complete { val = 7 }
              }
              match await { p = p } is
                Ok { val } -> val + deref { atom = observed }
                Err { error } -> 0 - 1
        """.trimIndent()
        // complete's val=7 + observed=0 (hook never fired) = 7
        assertEquals(7L, eval(code))
    }

    @Test
    fun `on_cancel hook does not fire when promise fails normally`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, promise_from }
            use std::effects::async::atom::{ atom, reset, deref }

            perform LoomAsync in do
              let observed = atom { val = 0 }
              let p = promise_from { setup = { complete, fail, on_cancel } -> do
                on_cancel { hook = { reason } -> do
                  reset { atom = observed, val = 99 }
                  {}
                }
                fail { err = AsyncFailed { msg = "boom" } }
              }
              deref { atom = observed }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `on_cancel hook receives the cancel reason`() {
        // Verify the reason variant reaches the hook. Use LostRace as
        // the marker so we can distinguish it from other CancelReason cases.
        val code = """
            use std::effects::async::{ Async, LoomAsync, LostRace, promise_from, cancel }
            use std::effects::async::atom::{ atom, reset, deref }

            perform LoomAsync in do
              let observed = atom { val = 0 }
              let p = promise_from { setup = { complete, fail, on_cancel } ->
                on_cancel { hook = { reason } -> match reason is
                  LostRace -> do
                    reset { atom = observed, val = 1 }
                    {}
                  _ -> do
                    reset { atom = observed, val = 2 }
                    {}
                }
              }
              cancel { p = p, reason = LostRace {} }
              deref { atom = observed }
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `on_cancel is optional — cancel still works without a hook`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, LostRace, promise_from, cancel, is_cancelled }
            perform LoomAsync in do
              let p = promise_from { setup = { complete, fail, on_cancel } -> {} }
              cancel { p = p, reason = LostRace {} }
              is_cancelled { p = p }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `on_cancel last-writer-wins if called multiple times`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, LostRace, promise_from, cancel }
            use std::effects::async::atom::{ atom, reset, deref }

            perform LoomAsync in do
              let observed = atom { val = 0 }
              let p = promise_from { setup = { complete, fail, on_cancel } -> do
                on_cancel { hook = { reason } -> do
                  reset { atom = observed, val = 1 }
                  {}
                }
                on_cancel { hook = { reason } -> do
                  reset { atom = observed, val = 2 }
                  {}
                }
              }
              cancel { p = p, reason = LostRace {} }
              deref { atom = observed }
        """.trimIndent()
        // Second on_cancel wins → observed = 2
        assertEquals(2L, eval(code))
    }
}
