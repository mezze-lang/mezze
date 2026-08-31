package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Runtime companion to BoundHofResultChainDispatchTest (typechecker).
 *
 * A method chained off a `let`-bound HOF result (`let kept = xs.filter {…};
 * kept.map {…}`) must dispatch on the value's Seq type and run — previously it
 * failed at type-check with "expected Bytes". These assert real VALUES.
 */
class BoundHofResultChainInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `bound filter then map runs and yields kept elements`() {
        val code = """
            use std::collections::seq

            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let kept = xs.filter { p = { x } -> x.ok }
              let qtys = kept.map { f = { x } -> x.qty }
              seq::size { xs = qtys }

            main {}
        """
        // Only one record has ok = True, so exactly one element survives.
        assertEquals(1L, eval(code))
    }

    @Test
    fun `bound filter then map preserves the surviving element value`() {
        val code = """
            use std::collections::seq

            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let kept = xs.filter { p = { x } -> x.ok }
              let qtys = kept.map { f = { x } -> x.qty }
              match seq::get { xs = qtys, i = 0 } is
                Some { val } -> val
                None -> -1

            main {}
        """
        // The kept order has qty = 2.
        assertEquals(2L, eval(code))
    }

    @Test
    fun `bound map then filter runs`() {
        val code = """
            use std::collections::seq

            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let qtys = xs.map { f = { x } -> x.qty }
              let big = qtys.filter { p = { x } -> x.gt { r = 1 } }
              seq::size { xs = big }

            main {}
        """
        // Only qty = 2 is > 1.
        assertEquals(1L, eval(code))
    }
}
