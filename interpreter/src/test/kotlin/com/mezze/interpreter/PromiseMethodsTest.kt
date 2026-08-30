package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the Promise fluent helpers added in #120:
 * and_then, map_ok, recover, on_complete, try_await.
 *
 * Both free-function and method-form are exercised. Method-form for
 * shape-changing methods (`Promise 'a → Promise 'b`, `Promise 'a →
 * Option 'a`) was previously broken by #175: selective imports
 * (`use std::effects::async::{ ..., await }`) didn't harvest inherent-impl
 * return types, leaving call sites with bare TypeVars. Fixed by
 * harvesting in both selective and path imports.
 */
class PromiseMethodsTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `map_ok transforms a successful value`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, map_ok }
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 10 }
              let q  = map_ok { p = p, f = { val } -> val + 5 }
              let r  = await { p = q }
              result::get_or { result = r, def = -1 }
        """
        assertEquals(15L, eval(source))
    }

    @Test
    fun `and_then chains an async step on success`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, and_then, await }
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 3 }
              let q  = and_then { p = p, f = { val } -> Async.spawn { f = {} -> val + 100 } }
              let r  = await { p = q }
              result::get_or { result = r, def = -1 }
        """
        assertEquals(103L, eval(source))
    }

    @Test
    fun `try_await returns Some on success`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, try_await }
            use std::option

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 42 }
              let o  = try_await { p = p }
              option::get_or { opt = o, def = -1 }
        """
        assertEquals(42L, eval(source))
    }

    @Test
    fun `map_ok via method dispatch`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 10 }
              let q  = p.map_ok { f = { val } -> val + 5 }
              let r  = await { p = q }
              result::get_or { result = r, def = -1 }
        """
        assertEquals(15L, eval(source))
    }

    @Test
    fun `and_then via method dispatch`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 3 }
              let q  = p.and_then { f = { val } -> Async.spawn { f = {} -> val + 100 } }
              let r  = await { p = q }
              result::get_or { result = r, def = -1 }
        """
        assertEquals(103L, eval(source))
    }

    @Test
    fun `try_await via method dispatch`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::option

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 42 }
              let o  = p.try_await {}
              option::get_or { opt = o, def = -1 }
        """
        assertEquals(42L, eval(source))
    }

    @Test
    fun `recover converts failure into success`() {
        val source = """
            use std::effects::async::{ Async, AsyncError, LoomAsync, await }
            use std::result

            perform LoomAsync in do
              # spawn a task that crashes — handler turns it into AsyncFailed
              let p = Async.spawn { f = {} -> crash { msg = "boom" } }
              let q = p.recover { f = { err } -> match err is
                  AsyncFailed { msg = _ }    -> 999
                  AsyncCancelled { reason = _ } -> 0
                  AsyncTimedOut { ms = _ }   -> 0
              }
              let r = await { p = q }
              result::get_or { result = r, def = -1 }
        """
        assertEquals(999L, eval(source))
    }
}
