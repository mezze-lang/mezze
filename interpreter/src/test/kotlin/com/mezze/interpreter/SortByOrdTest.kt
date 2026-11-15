package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `seq::sort` without a comparator, and the gate that keeps it honest.
 *
 * `seq::sort` routed through `sort_by`, so each of roughly n log n comparisons
 * ran a guest lambda. MEASURED at a hundred descending elements: 1 291 024 ps,
 * against 68 387 for `Arrays.sort(long[])` over the same input — the comparator
 * was the cost, not the sort.
 *
 * So `sort` now calls a native that takes no comparator at all, and
 * `TypedASTCompiler.ordNativeCall` installs it ONLY where the element was
 * proved directly comparable. The direction is the safety property: the stdlib
 * writes the general form, the compiler only ever UPGRADES, and a decline
 * leaves a correct answer that is merely slower.
 *
 * WHAT WOULD GO WRONG WITHOUT THE GATE is what [a newtype whose Ord reverses
 * its wrapped type] tests. `newtype R = Int` with a descending `Ord` is an Int
 * at runtime, so the primitive sort would hand back ASCENDING and contradict
 * the order its author wrote. Not a crash — a wrong answer that looks fine.
 * That is known-bugs.md BUG 10 in min_by's clothing.
 *
 * The native it replaces was DEAD AND WRONG: `__native_seq_sort` was registered
 * in both `NativeDispatch` and `SeqOps.kt` and called from nowhere, and its
 * comparator ended `return 0` — so a seq of records would have come back in its
 * original order, reported as sorted.
 */
class SortByOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elems(out: Any): List<Any?> = (out as Iterable<*>).toList()

    private val prelude = """
        use std::collections::seq
        use std::int
    """

    // ---- the upgraded path: Int, Float and Str ----
    //
    // Int and Str are totally ordered, so dropping the comparator cannot change
    // an answer. Float is a DECIDED RULE rather than a consequence — see the
    // Float section below.

    @Test
    fun `ints sort ascending`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [5, 3, 9, 1, 7, 3] }
            main {}
            """,
        )
        assertEquals(listOf(1L, 3L, 3L, 5L, 7L, 9L), elems(out))
    }

    /** Negatives, because an unsigned or bit-pattern sort would pass on
     *  positives alone and reorder these. */
    @Test
    fun `negative ints sort below positives`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [3, 0 - 7, 0, 0 - 1, 9] }
            main {}
            """,
        )
        assertEquals(listOf(-7L, -1L, 0L, 3L, 9L), elems(out))
    }

    @Test
    fun `floats sort ascending`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [2.5, 0.5, 1.5] }
            main {}
            """,
        )
        assertEquals(listOf(2.5, 0.5, 1.5).sorted(), elems(out))
    }

    @Test
    fun `strings sort lexicographically`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = ["pear", "apple", "fig"] }
            main {}
            """,
        )
        assertEquals(listOf("apple", "fig", "pear"), elems(out))
    }

    @Test
    fun `empty and single-element seqs`() {
        assertEquals(0L, eval(
            """
            $prelude
            let main = {} -> seq::size { xs = seq::sort { xs = [] } }
            main {}
            """,
        ))
        assertEquals(listOf(4L), elems(eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [4] }
            main {}
            """,
        )))
    }

    /** An already-ascending input must come back unchanged, and an all-equal
     *  one must not lose elements — the two shapes a sort gets wrong at the
     *  edges. */
    @Test
    fun `already sorted and all equal inputs`() {
        assertEquals(listOf(1L, 2L, 3L), elems(eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [1, 2, 3] }
            main {}
            """,
        )))
        assertEquals(listOf(8L, 8L, 8L, 8L), elems(eval(
            """
            $prelude
            let main = {} -> seq::sort { xs = [8, 8, 8, 8] }
            main {}
            """,
        )))
    }

    /** A thousand descending elements, so the result is checked on an input
     *  large enough to exercise the sort rather than a special case. */
    @Test
    fun `a thousand descending ints`() {
        val out = eval(
            """
            $prelude
            let build = { i, acc } -> if i <= 0 then acc
              else build { i = i - 1, acc = seq::push { xs = acc, x = i } }
            let main = {} -> do
              let s = seq::sort { xs = build { i = 1000, acc = [] } }
              { first = seq::get { xs = s, i = 0 },
                last = seq::get { xs = s, i = 999 },
                n = seq::size { xs = s } }
            main {}
            """,
        )
        val rec = out as com.mezze.interpreter.values.MezzeRecord
        assertEquals(1000L, rec.getField("n"))
    }

    // ---- Float: SORTING puts NaN LAST; the operators are unchanged ----
    //
    // A decided language rule. Sorting uses a total order with NaN at the end;
    // `<`, `<=`, `>`, `>=` keep IEEE semantics, so `NaN <= x` is still false
    // and `float::is_nan` is still the test. `Arrays.sort(double[])` sorts by
    // IEEE 754's `totalOrder`, so the rule costs nothing to implement.
    //
    // WHAT THE RULE BUYS is not where NaN lands. It is that the OTHER values
    // come out in order regardless of where the NaN sits. Before, one NaN
    // reordered values that had nothing to do with it — measured, both
    // arrangements below — and `is_sorted` returned True on the result, because
    // it compares by negation and NaN passes in both directions. The corruption
    // could not be detected from inside the language.
    //
    // STILL UNFIXED, on purpose: `seq::min`, `seq::max` and `is_sorted` go
    // through the IEEE operators, so `min` of a NaN-first seq is NaN. Making
    // those total would cost a NaN check on every float comparison in the
    // language.

    @Test
    fun `a Float seq without NaN sorts ascending`() {
        val out = eval(
            """
            use std::collections::seq
            use std::float
            let main = {} -> seq::sort { xs = [2.5, 0.5, 1.5] }
            main {}
            """,
        )
        assertEquals(listOf(0.5, 1.5, 2.5), elems(out))
    }

    /**
     * THE CASE THE RULE EXISTS FOR. Seven values, one of them NaN, and the
     * assertion is on the OTHER SIX: they must be in order.
     *
     * Before the rule this returned `2.0, 5.0, NaN, 1.0, 3.0, 8.0, 9.0` — 5.0
     * ahead of 1.0 — because every comparison involving NaN is false and the
     * merge left two runs that never reconciled.
     */
    @Test
    fun `one NaN does not reorder the other values`() {
        val out = eval(
            """
            use std::collections::seq
            use std::float
            let main = {} -> seq::sort { xs = [5.0, 2.0, 0.0 / 0.0, 8.0, 1.0, 9.0, 3.0] }
            main {}
            """,
        )
        val xs = elems(out).map { it as Double }
        assertEquals(7, xs.size)
        assertEquals(
            listOf(1.0, 2.0, 3.0, 5.0, 8.0, 9.0), xs.filter { !it.isNaN() },
            "the six non-NaN values must be sorted — `2.0, 5.0, 1.0, ...` is the" +
                " old behaviour, where one NaN split the sort into runs",
        )
        assertEquals(true, xs[6].isNaN(), "NaN sorts last")
    }

    /**
     * The SAME values with the NaN moved to the front, because the old
     * behaviour was arrangement-dependent: this arrangement happened to order
     * the good values correctly while leaving NaN first. The rule makes both
     * arrangements give the same answer, which is the property worth having.
     */
    @Test
    fun `the arrangement of the NaN does not change the answer`() {
        val out = eval(
            """
            use std::collections::seq
            use std::float
            let main = {} -> seq::sort { xs = [0.0 / 0.0, 5.0, 2.0, 8.0, 1.0, 9.0, 3.0] }
            main {}
            """,
        )
        val xs = elems(out).map { it as Double }
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 8.0, 9.0), xs.filter { !it.isNaN() })
        assertEquals(true, xs[6].isNaN(), "NaN sorts last here too, not first")
    }

    /**
     * The operators are NOT part of the rule and keep IEEE semantics: every
     * comparison involving NaN is false, in both directions. A test because the
     * sorting rule could easily be taken to imply a change here, and it does
     * not.
     */
    @Test
    fun `the comparison operators keep IEEE NaN semantics`() {
        val out = eval(
            """
            use std::collections::seq
            use std::float
            let main = {} -> do
              let nan = 0.0 / 0.0
              { a = if nan <= 1.0 then 1 else 0,
                b = if 1.0 <= nan then 1 else 0,
                c = if nan <= nan then 1 else 0,
                d = if float::is_nan { n = nan } then 1 else 0 }
            main {}
            """,
        )
        val rec = out as com.mezze.interpreter.values.MezzeRecord
        assertEquals(0L, rec.getField("a"), "NaN <= 1.0 stays false")
        assertEquals(0L, rec.getField("b"), "1.0 <= NaN stays false")
        assertEquals(0L, rec.getField("c"), "NaN <= NaN stays false")
        assertEquals(1L, rec.getField("d"), "float::is_nan is the test that works")
    }

    // ---- the DECLINED path: the element's own Ord must still decide ----

    /**
     * A newtype over Int whose `Ord` REVERSES the order of the type it wraps.
     *
     * THIS IS THE CASE THE GATE EXISTS FOR. `R` is a long at runtime, so the
     * comparator-free native would sort it ascending and silently contradict
     * the `Ord` its author wrote. `ordKindOf` answers INSTANCE for a newtype
     * for exactly this reason, so the upgrade must decline and the author's
     * `le` must run.
     *
     * The impl spelling is the built-in ability's own — `it`/`r`, and all four
     * of `lt`/`le`/`gt`/`ge` — or the typechecker reports that the impl
     * declares a different ability sharing the name.
     */
    private val reversedOrd = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }
        newtype R = Int
        let unwrap: { v: R } -> Int = { v } -> match v is
          R.(n) -> n
        impl Ord for R where {
          lt = { it, r } -> unwrap { v = it } > unwrap { v = r },
          le = { it, r } -> unwrap { v = it } >= unwrap { v = r },
          gt = { it, r } -> unwrap { v = it } < unwrap { v = r },
          ge = { it, r } -> unwrap { v = it } <= unwrap { v = r },
        }
    """

    @Test
    fun `a newtype whose Ord reverses its wrapped type sorts by that Ord`() {
        val out = eval(
            """
            $reversedOrd
            let main = {} -> do
              let s = seq::sort { xs = [R.(1), R.(5), R.(3)] }
              match seq::get { xs = s, i = 0 } is
                Some { val } -> unwrap { v = val }
                None -> 0 - 1
            main {}
            """,
        )
        assertEquals(
            5L, out,
            "R's Ord is descending, so the LARGEST must come first — 1 means the" +
                " comparator-free native sorted the underlying long and ignored" +
                " the author's Ord",
        )
    }

    /** The whole order, not just the head: a reversed `Ord` must produce the
     *  full descending sequence. */
    @Test
    fun `the reversed Ord orders every element`() {
        val out = eval(
            """
            $reversedOrd
            let main = {} -> do
              let s = seq::sort { xs = [R.(2), R.(9), R.(4), R.(7)] }
              seq::map { xs = s, f = { x } -> unwrap { v = x } }
            main {}
            """,
        )
        assertEquals(listOf(9L, 7L, 4L, 2L), elems(out))
    }

    /**
     * A RECORD element, which the deleted native would have answered WRONGLY
     * rather than slowly: its comparator ended `return 0`, so TimSort saw every
     * pair as equal and handed back the original order reported as sorted.
     *
     * The `Ord` is written out because a record does not derive one — the first
     * version of this test assumed it did and the typechecker said so plainly,
     * listing the nine impls that exist. The element is still a record, which
     * is the part that matters here.
     */
    @Test
    fun `a record element sorts by a written Ord`() {
        val out = eval(
            """
            use std::collections::seq
            use std::abilities::compare::{ Eq, Ord }
            newtype P = { k: Int }
            let key: { p: P } -> Int = { p } -> match p is
              P.({ k }) -> k
            impl Ord for P where {
              lt = { it, r } -> key { p = it } < key { p = r },
              le = { it, r } -> key { p = it } <= key { p = r },
              gt = { it, r } -> key { p = it } > key { p = r },
              ge = { it, r } -> key { p = it } >= key { p = r },
            }
            let main = {} -> do
              let s = seq::sort { xs = [
                P.({ k = 3 }), P.({ k = 1 }), P.({ k = 2 }),
              ] }
              seq::map { xs = s, f = { x } -> key { p = x } }
            main {}
            """,
        )
        assertEquals(
            listOf(1L, 2L, 3L), elems(out),
            "a comparator answering 0 for every pair would return 3, 1, 2 — the" +
                " input order, reported as sorted",
        )
    }

    /** `sort_by` is a DIFFERENT native and is never upgraded, so an arbitrary
     *  comparator keeps working — including one that disagrees with `Ord`. */
    @Test
    fun `sort_by with a descending comparator is untouched`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::sort_by { xs = [1, 4, 2, 5], cmp = { a, b } -> a > b }
            main {}
            """,
        )
        assertEquals(listOf(5L, 4L, 2L, 1L), elems(out))
    }
}
