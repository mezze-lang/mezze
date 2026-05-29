package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the Promise fluent helpers added in #120:
 * and_then, map_ok, recover, on_complete, try_await.
 *
 * Free-function form is exercised here, plus method-form for `recover`
 * (return shape matches receiver). Method-form for the others
 * (`and_then`, `map_ok`, `try_await`, `on_complete`) requires #175 —
 * imported impl methods with shape-changing parametric returns
 * (`Promise 'a → Option 'a`, `Promise 'a → Promise 'b`) lose their
 * return shape because the harvest step in the import pipeline doesn't
 * fire for stdlib loads.
 */
class PromiseMethodsTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `map_ok transforms a successful value`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, map_ok}
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 10 }
              let q  = map_ok { p = p, f = { val } -> val + 5 }
              let r  = Async.await { p = q }
              result::get_or_else { result = r, def = -1 }
        """
        assertEquals(15L, eval(source))
    }

    @Test
    fun `and_then chains an async step on success`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, and_then}
            use std::result

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 3 }
              let q  = and_then { p = p, f = { val } -> Async.spawn { f = {} -> val + 100 } }
              let r  = Async.await { p = q }
              result::get_or_else { result = r, def = -1 }
        """
        assertEquals(103L, eval(source))
    }

    @Test
    fun `try_await returns Some on success`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, try_await}
            use std::option

            perform LoomAsync in do
              let p  = Async.spawn { f = {} -> 42 }
              let o  = try_await { p = p }
              option::get_or_else { opt = o, def = -1 }
        """
        assertEquals(42L, eval(source))
    }

    @Test
    fun `recover converts failure into success`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, AsyncError}
            use std::result

            perform LoomAsync in do
              # spawn a task that crashes — handler turns it into AsyncFailed
              let p = Async.spawn { f = {} -> crash { msg = "boom" } }
              let q = p.recover { f = { err } -> match err is
                  AsyncFailed { msg = _ }    -> 999
                  AsyncCancelled             -> 0
                  AsyncTimedOut { ms = _ }   -> 0
              }
              let r = Async.await { p = q }
              result::get_or_else { result = r, def = -1 }
        """
        assertEquals(999L, eval(source))
    }
}
