package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Behavior tests for the try_* fail-fast async helpers (#169).
 *
 * try_join, try_join3, try_all run their inputs concurrently but
 * short-circuit on the first Err — cancelling any siblings still
 * in flight and returning that error. On the all-Ok path they return
 * the unwrapped values bundled together.
 */
class TryJoinHelpersTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `try_join all ok returns unwrapped pair`() {
        val code = """
            use std::effects::async::{ try_join, run_coop }
            run_coop {
              computation = {} -> match try_join {
                l = {} -> 10,
                r = {} -> 20,
              } is
                Ok  { val = { l, r } }   -> l + r
                Err { error = _ }        -> -1
            }
        """.trimIndent()
        assertEquals(30L, eval(code))
    }

    @Test
    fun `try_join3 all ok returns unwrapped triple`() {
        val code = """
            use std::effects::async::{ try_join3, run_coop }
            run_coop {
              computation = {} -> match try_join3 {
                a = {} -> 1,
                b = {} -> 2,
                c = {} -> 3,
              } is
                Ok  { val = { a, b, c } } -> a + b + c
                Err { error = _ }         -> -1
            }
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    @Test
    fun `try_all all ok returns unwrapped seq`() {
        val code = """
            use std::effects::async::{ Async, try_all, run_coop }
            use std::collections::seq
            run_coop {
              computation = {} -> do
                let p1 = Async.spawn { f = {} -> 1 }
                let p2 = Async.spawn { f = {} -> 2 }
                let p3 = Async.spawn { f = {} -> 3 }
                match try_all { ps = [p1, p2, p3] } is
                  Ok  { val = vs }    -> seq::fold { xs = vs, init = 0, f = { acc, x } -> acc + x }
                  Err { error = _ }   -> -1
            }
        """.trimIndent()
        assertEquals(6L, eval(code))
    }
}
