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
    // NESTED containers of a user type, arbitrary depth. Three pieces make these
    // work: (1) spec-salted lambda hashes — the same source comparator lambda
    // compiles to DIFFERENT FunctionIRs per specialization, and the type-blind
    // expr hash collided them all onto one registry slot; (2) refToDictedOp —
    // an injected dict op that itself needs dicts (`[[[P]]]`: the dict comparing
    // `[P]`-elements is the seq Ord op, which needs `Ord P`) gets a recursive
    // no-capture wrapper supplying them; (3) spec-typeArgs last-resort dict
    // resolution — mono's name-based substitution misses renamed/field-stamped
    // type vars (sort's comparator), so inside a spec an unresolved dict falls
    // back to element-first resolution against the spec's own typeArgs.
    @Test fun nestedLt()    { assertEquals(true, run("let main = {} -> [ [ { x = 1, y = 0 } ] ] < [ [ { x = 2, y = 0 } ] ]\nmain {}")) }
    @Test fun nestedLtFalse() { assertEquals(false, run("let main = {} -> [ [ { x = 2, y = 0 } ] ] < [ [ { x = 1, y = 0 } ] ]\nmain {}")) }
    @Test fun nested3Lt()   { assertEquals(true, run("let main = {} -> [ [ [ { x = 1, y = 0 } ] ] ] < [ [ [ { x = 2, y = 0 } ] ] ]\nmain {}")) }
    @Test fun nestedSort()  { assertEquals(2L, run("let main = {} -> seq::size { xs = seq::sort { xs = [ [ { x = 2, y = 0 } ], [ { x = 1, y = 0 } ] ] } }\nmain {}")) }
    @Test fun nestedSortOrder() { assertEquals(1L, run("let main = {} -> match seq::first { xs = seq::sort { xs = [ [ { x = 2, y = 0 } ], [ { x = 1, y = 0 } ] ] } } is\n  Some { val = inner } -> match seq::first { xs = inner } is\n    Some { val = p } -> p.x\n    None -> 0 - 1\n  None -> 0 - 1\nmain {}")) }
    @Test fun nested3Sort() { assertEquals(2L, run("let main = {} -> seq::size { xs = seq::sort { xs = [ [ [ { x = 2, y = 0 } ] ], [ [ { x = 1, y = 0 } ] ] ] } }\nmain {}")) }

    // USER VARIANT elements (not just records): a variant with a custom Ord
    // (severity ranking) must drive seq comparison and sort the same way.
    private val variantHead = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }
        type Sev = | Low, Mid, High |
        let rank: { s: Sev } -> Int = { s } -> match s is
          Low -> 0
          Mid -> 1
          High -> 2
        impl Eq for Sev where { eq = { it, r } -> rank { s = it } == rank { s = r }, neq = { it, r } -> if rank { s = it } == rank { s = r } then False else True, }
        impl Ord for Sev where { lt = { it, r } -> rank { s = it } < rank { s = r }, le = { it, r } -> rank { s = it } <= rank { s = r }, gt = { it, r } -> rank { s = it } > rank { s = r }, ge = { it, r } -> rank { s = it } >= rank { s = r }, }
    """.trimIndent()
    private fun runV(body: String) = MezzeTestHelper.run("$variantHead\n$body")

    @Test fun variantLt()     { assertEquals(true, runV("let main = {} -> [ Low ] < [ High ]\nmain {}")) }
    @Test fun variantLtFalse(){ assertEquals(false, runV("let main = {} -> [ High ] < [ Low ]\nmain {}")) }
    @Test fun variantSort()   { assertEquals(0L, runV("let main = {} -> match seq::first { xs = seq::sort { xs = [ High, Low, Mid ] } } is\n  Some { val = s } -> rank { s = s }\n  None -> 0 - 1\nmain {}")) }
    @Test fun variantNestedLt(){ assertEquals(true, runV("let main = {} -> [ [ Low ] ] < [ [ High ] ]\nmain {}")) }

    // regressions — must still work
    @Test fun intLt()      { assertEquals(true, run("let main = {} -> [1,2] < [1,3]\nmain {}")) }
    @Test fun strLt()      { assertEquals(true, run("let main = {} -> [\"a\"] < [\"b\"]\nmain {}")) }
    @Test fun sortP()      { assertEquals(1L, run("let main = {} -> match seq::first { xs = seq::sort { xs = [ { x = 3, y = 0 }, { x = 1, y = 0 } ] } } is\n  Some { val = p } -> p.x\n  None -> 0 - 1\nmain {}")) }
    @Test fun compareP()   { assertEquals(-1L, run("let main = {} -> seq::compare { xs = [ { x = 1, y = 0 } ], ys = [ { x = 2, y = 0 } ] }\nmain {}")) }
}
