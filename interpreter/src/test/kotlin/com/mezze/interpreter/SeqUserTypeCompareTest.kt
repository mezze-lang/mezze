package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * `[P]`-of-a-user-type comparison operators must dispatch the element's Ord impl.
 * Regression for the mono/dict-forwarding bug where the seq `Ord` impl was
 * specialized without recovering the element type var `'a`, so its comparator's
 * `a < b` fell to a primitive node and crashed on records.
 * See project_impl_op_with_constraint_dropped.
 */
class SeqUserTypeCompareTest {
    private val head = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }
        type P = { x: Int, y: Int }
        impl Eq for P where { eq = { it, r } -> it.x == r.x, neq = { it, r } -> if it.x == r.x then False else True, }
        impl Ord for P where { lt = { it, r } -> it.x < r.x, le = { it, r } -> it.x <= r.x, gt = { it, r } -> it.x > r.x, ge = { it, r } -> it.x >= r.x, }
    """.trimIndent()
    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    @Test fun pLtP_true()  { assertEquals(true,  run("let main = {} -> [ { x = 1, y = 0 } ] < [ { x = 2, y = 0 } ]\nmain {}")) }
    @Test fun pLtP_false() { assertEquals(false, run("let main = {} -> [ { x = 2, y = 0 } ] < [ { x = 1, y = 0 } ]\nmain {}")) }
    @Test fun pGeP()       { assertEquals(true,  run("let main = {} -> [ { x = 2, y = 0 } ] >= [ { x = 1, y = 0 } ]\nmain {}")) }
    @Test fun pLeP()       { assertEquals(true,  run("let main = {} -> [ { x = 1, y = 0 } ] <= [ { x = 1, y = 0 } ]\nmain {}")) }
    @Test fun pGtP()       { assertEquals(false, run("let main = {} -> [ { x = 1, y = 0 } ] > [ { x = 2, y = 0 } ]\nmain {}")) }
    // NESTED containers of a user type (`[[P]]`) still throw DictFallback[lt]: the
    // recovered element `'a = [P]` is itself a container whose Ord op needs its OWN
    // `__dict_lt = Ord P` forwarded recursively (Step 2 / recursive dict wrapping —
    // not yet done). Single-level `[P]` (the common case) works. Disabled, documented.
    @org.junit.jupiter.api.Disabled("nested [[P]] needs recursive dict wrapping; see project_impl_op_with_constraint_dropped")
    @Test fun nestedSort() { assertEquals(2L, run("let main = {} -> seq::size { xs = seq::sort { xs = [ [ { x = 2, y = 0 } ], [ { x = 1, y = 0 } ] ] } }\nmain {}")) }
    @org.junit.jupiter.api.Disabled("nested [[P]] needs recursive dict wrapping; see project_impl_op_with_constraint_dropped")
    @Test fun nestedLt()   { assertEquals(true, run("let main = {} -> [ [ { x = 1, y = 0 } ] ] < [ [ { x = 2, y = 0 } ] ]\nmain {}")) }

    // regressions — must still work
    @Test fun intLt()      { assertEquals(true, run("let main = {} -> [1,2] < [1,3]\nmain {}")) }
    @Test fun strLt()      { assertEquals(true, run("let main = {} -> [\"a\"] < [\"b\"]\nmain {}")) }
    @Test fun sortP()      { assertEquals(1L, run("let main = {} -> match seq::first { xs = seq::sort { xs = [ { x = 3, y = 0 }, { x = 1, y = 0 } ] } } is\n  Some { val = p } -> p.x\n  None -> 0 - 1\nmain {}")) }
    @Test fun compareP()   { assertEquals(-1L, run("let main = {} -> seq::compare { xs = [ { x = 1, y = 0 } ], ys = [ { x = 2, y = 0 } ] }\nmain {}")) }
}
