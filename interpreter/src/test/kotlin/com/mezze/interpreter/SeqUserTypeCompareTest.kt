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
        impl Ord for P where { lt = { it, r } -> it.x < r.x, le = { it, r } -> it.x <= r.x, gt = { it, r } -> it.x > r.x, ge = { it, r } -> it.x >= r.x, }
    """.trimIndent()
    // A record DERIVES Eq (structural, compiler-owned — a hand-written `impl Eq for P`
    // is a duplicate-impl error); Ord is never derived, so the Ord impl is legal.
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
        impl Ord for Sev where { lt = { it, r } -> rank { s = it } < rank { s = r }, le = { it, r } -> rank { s = it } <= rank { s = r }, gt = { it, r } -> rank { s = it } > rank { s = r }, ge = { it, r } -> rank { s = it } >= rank { s = r }, }
    """.trimIndent()
    private fun runV(body: String) = MezzeTestHelper.run("$variantHead\n$body")

    // CONTAINER GENERALITY: `List P == List P` must honour the element's Eq the same
    // way `[P] == [P]` does (was the PRE-#23 structural `__native_list_equals`).
    private val listHead = """
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Ord }
        type P = { x: Int, y: Int }
    """.trimIndent()
    private fun runL(body: String) = MezzeTestHelper.run("$listHead\n$body")

    // NEWTYPE elements — the ONLY legal home for a non-structural Eq/Ord. Records and
    // variants DERIVE Eq and under coherence nothing may replace a derived impl (a
    // hand-written one is rejected as a duplicate). A newtype derives nothing, so its
    // hand-written Eq/Ord is the type's ONE impl — exactly what container Eq/Ord and
    // DERIVED Eq of an enclosing record/variant must dispatch to. Eq compares only `x`.
    private val newtypeHead = """
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Ord }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        impl Eq for P where { eq = { it, r } -> px { p = it } == px { p = r }, neq = { it, r } -> if px { p = it } == px { p = r } then False else True, }
        impl Ord for P where { lt = { it, r } -> px { p = it } < px { p = r }, le = { it, r } -> px { p = it } <= px { p = r }, gt = { it, r } -> px { p = it } > px { p = r }, ge = { it, r } -> px { p = it } >= px { p = r }, }
    """.trimIndent()
    private fun runN(body: String) = MezzeTestHelper.run("$newtypeHead\n$body")

    @Test fun newtypeEq()        { assertEquals(true,  runN("let main = {} -> [ P.({ x = 1, y = 10 }) ] == [ P.({ x = 1, y = 99 }) ]\nmain {}")) }
    @Test fun newtypeEqFalse()   { assertEquals(false, runN("let main = {} -> [ P.({ x = 1, y = 10 }) ] == [ P.({ x = 2, y = 10 }) ]\nmain {}")) }
    @Test fun newtypeNestedEq()  { assertEquals(true,  runN("let main = {} -> [ [ P.({ x = 1, y = 10 }) ] ] == [ [ P.({ x = 1, y = 99 }) ] ]\nmain {}")) }
    @Test fun newtypeNested3Eq() { assertEquals(true,  runN("let main = {} -> [ [ [ P.({ x = 1, y = 10 }) ] ] ] == [ [ [ P.({ x = 1, y = 99 }) ] ] ]\nmain {}")) }
    @Test fun newtypeLt()        { assertEquals(true,  runN("let main = {} -> [ P.({ x = 1, y = 0 }) ] < [ P.({ x = 2, y = 0 }) ]\nmain {}")) }
    @Test fun newtypeNestedLt()  { assertEquals(true,  runN("let main = {} -> [ [ P.({ x = 1, y = 0 }) ] ] < [ [ P.({ x = 2, y = 0 }) ] ]\nmain {}")) }
    @Test fun listOfNewtypesEq() {
        assertEquals(true, runN(
            "let main = {} -> list::from_seq { xs = [ P.({ x = 1, y = 10 }) ] } == list::from_seq { xs = [ P.({ x = 1, y = 99 }) ] }\nmain {}"
        ))
    }

    // DERIVED Eq must be COMPOSITIONAL: a record / variant / Option / Result whose
    // payload is a newtype with a declared Eq (P compares only x) must dispatch to
    // that impl — never a structural deep-equals that ignores it.
    @Test fun derivedRecordOfNewtypeEq()  { assertEquals(true,  runN("type R = { p: P }\nlet mk: { x: Int, y: Int } -> R = { x, y } -> { p = P.({ x = x, y = y }) }\nlet main = {} -> mk { x = 1, y = 10 } == mk { x = 1, y = 99 }\nmain {}")) }
    @Test fun derivedRecordOfNewtypeEqFalse() { assertEquals(false, runN("type R = { p: P }\nlet mk: { x: Int, y: Int } -> R = { x, y } -> { p = P.({ x = x, y = y }) }\nlet main = {} -> mk { x = 1, y = 10 } == mk { x = 2, y = 10 }\nmain {}")) }
    @Test fun derivedAnonRecordOfNewtypeEq() { assertEquals(true,  runN("let main = {} -> { p = P.({ x = 1, y = 10 }) } == { p = P.({ x = 1, y = 99 }) }\nmain {}")) }
    @Test fun derivedVariantOfNewtypeEq() { assertEquals(true,  runN("type W = | Wrap { p: P }, Empty |\nlet main = {} -> Wrap { p = P.({ x = 1, y = 10 }) } == Wrap { p = P.({ x = 1, y = 99 }) }\nmain {}")) }
    @Test fun derivedOptionOfNewtypeEq()  { assertEquals(true,  runN("let main = {} -> Some { val = P.({ x = 1, y = 10 }) } == Some { val = P.({ x = 1, y = 99 }) }\nmain {}")) }
    @Test fun derivedOptionOfNewtypeEqFalse() { assertEquals(false, runN("let main = {} -> Some { val = P.({ x = 1, y = 10 }) } == Some { val = P.({ x = 2, y = 10 }) }\nmain {}")) }
    // Result has TWO type params that both need an Eq dict. A function whose body
    // needs one method at two type variables gets a dict slot PER VARIABLE
    // (`__dict_eq@a`, `__dict_eq@e` — MagicParams.dictKey); the call site binds
    // each through the instance head (`Result 'a 'e` vs `Result P Str`) and inside
    // a mono specialization a receiver still typed `'a` is bound the same way.
    @Test fun derivedResultOkOfIntEq()    { assertEquals(true,  runN("let mk: { v: Int } -> Result Int Str = { v } -> Ok { val = v }\nlet main = {} -> mk { v = 1 } == mk { v = 1 }\nmain {}")) }
    @Test fun derivedResultErrOfStrEq()   { assertEquals(true,  runN("let mk: { e: Str } -> Result Int Str = { e } -> Err { error = e }\nlet main = {} -> mk { e = \"x\" } == mk { e = \"x\" }\nmain {}")) }
    @Test fun derivedResultErrOfNewtypeEq() { assertEquals(true, runN("let mk: { x: Int, y: Int } -> Result Str P = { x, y } -> Err { error = P.({ x = x, y = y }) }\nlet main = {} -> mk { x = 1, y = 10 } == mk { x = 1, y = 99 }\nmain {}")) }
    @Test fun derivedResultOfNewtypeEq()  { assertEquals(true,  runN("let mk: { x: Int, y: Int } -> Result P Str = { x, y } -> Ok { val = P.({ x = x, y = y }) }\nlet main = {} -> mk { x = 1, y = 10 } == mk { x = 1, y = 99 }\nmain {}")) }

    // Records derive Eq structurally: every field counts.
    @Test fun listOfRecordsDerivedEq() {
        assertEquals(true, runL(
            "let main = {} -> list::from_seq { xs = [ { x = 1, y = 10 } ] } == list::from_seq { xs = [ { x = 1, y = 10 } ] }\nmain {}"
        ))
    }
    @Test fun listOfRecordsDerivedEqFalse() {
        assertEquals(false, runL(
            "let main = {} -> list::from_seq { xs = [ { x = 1, y = 10 } ] } == list::from_seq { xs = [ { x = 1, y = 99 } ] }\nmain {}"
        ))
    }

    @Test fun variantLt()     { assertEquals(true, runV("let main = {} -> [ Low ] < [ High ]\nmain {}")) }
    @Test fun variantLtFalse(){ assertEquals(false, runV("let main = {} -> [ High ] < [ Low ]\nmain {}")) }
    @Test fun variantSort()   { assertEquals(0L, runV("let main = {} -> match seq::first { xs = seq::sort { xs = [ High, Low, Mid ] } } is\n  Some { val = s } -> rank { s = s }\n  None -> 0 - 1\nmain {}")) }
    @Test fun variantNestedLt(){ assertEquals(true, runV("let main = {} -> [ [ Low ] ] < [ [ High ] ]\nmain {}")) }

    // The == / != OPERATORS on sequences are elementwise via the element's Eq. For a
    // RECORD element that Eq is the DERIVED (structural) one — every field counts;
    // the newtype cases above (`newtypeEq`, `newtypeNestedEq`, …) are where a
    // non-structural element Eq must win. Was structural __native_seq_equals.
    @Test fun seqEqTrue()   { assertEquals(true,  run("let main = {} -> [ { x = 1, y = 10 } ] == [ { x = 1, y = 10 } ]\nmain {}")) }
    @Test fun seqEqFalse()  { assertEquals(false, run("let main = {} -> [ { x = 1, y = 10 } ] == [ { x = 1, y = 99 } ]\nmain {}")) }
    @Test fun seqNeq()      { assertEquals(true,  run("let main = {} -> [ { x = 1, y = 10 } ] != [ { x = 1, y = 99 } ]\nmain {}")) }
    @Test fun seqEqLenMismatch() { assertEquals(false, run("let main = {} -> [ { x = 1, y = 0 } ] == [ { x = 1, y = 0 }, { x = 2, y = 0 } ]\nmain {}")) }
    @Test fun nestedSeqEq() { assertEquals(true,  run("let main = {} -> [ [ { x = 1, y = 10 } ] ] == [ [ { x = 1, y = 10 } ] ]\nmain {}")) }
    @Test fun nestedSeqEqFalse() { assertEquals(false, run("let main = {} -> [ [ { x = 1, y = 10 } ] ] == [ [ { x = 1, y = 99 } ] ]\nmain {}")) }
    @Test fun nested3SeqEq() { assertEquals(true, run("let main = {} -> [ [ [ { x = 1, y = 10 } ] ] ] == [ [ [ { x = 1, y = 10 } ] ] ]\nmain {}")) }
    @Test fun variantNestedSeqEq() { assertEquals(true, runV("let main = {} -> [ [ Low ] ] == [ [ Low ] ]\nmain {}")) }
    @Test fun seqEqualsFn() { assertEquals(false, run("let main = {} -> seq::equals { l = [ { x = 1, y = 10 } ], r = [ { x = 1, y = 99 } ] }\nmain {}")) }
    @Test fun newtypeSeqEqualsFn() { assertEquals(true, runN("let main = {} -> seq::equals { l = [ P.({ x = 1, y = 10 }) ], r = [ P.({ x = 1, y = 99 }) ] }\nmain {}")) }
    @Test fun newtypeNeq()  { assertEquals(false, runN("let main = {} -> [ P.({ x = 1, y = 10 }) ] != [ P.({ x = 1, y = 99 }) ]\nmain {}")) }
    @Test fun variantSeqEq(){ assertEquals(true,  runV("let main = {} -> [ Low, High ] == [ Low, High ]\nmain {}")) }

    // regressions — must still work
    @Test fun intEq()      { assertEquals(true, run("let main = {} -> [1,2] == [1,2]\nmain {}")) }
    @Test fun intEqFalse() { assertEquals(false, run("let main = {} -> [1,2] == [1,3]\nmain {}")) }
    @Test fun intLt()      { assertEquals(true, run("let main = {} -> [1,2] < [1,3]\nmain {}")) }
    @Test fun strLt()      { assertEquals(true, run("let main = {} -> [\"a\"] < [\"b\"]\nmain {}")) }
    @Test fun sortP()      { assertEquals(1L, run("let main = {} -> match seq::first { xs = seq::sort { xs = [ { x = 3, y = 0 }, { x = 1, y = 0 } ] } } is\n  Some { val = p } -> p.x\n  None -> 0 - 1\nmain {}")) }
    @Test fun compareP()   { assertEquals(-1L, run("let main = {} -> seq::compare { xs = [ { x = 1, y = 0 } ], ys = [ { x = 2, y = 0 } ] }\nmain {}")) }
}
