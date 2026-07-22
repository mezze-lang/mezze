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
                p = promise_from { setup = { complete, fail } -> complete { val = 99 } }
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
                p = promise_from { setup = { complete, fail } ->
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
                p = promise_from { setup = { complete, fail } -> do
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
                p = promise_from { setup = { complete, fail } -> do
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
            is_pending { p = promise_from { setup = { complete, fail } -> {} } }
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
                p = promise_from { setup = { complete, fail } ->
                  go { f = {} -> complete { val = 123 } }
                }
              } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(123L, eval(code))
    }
}
