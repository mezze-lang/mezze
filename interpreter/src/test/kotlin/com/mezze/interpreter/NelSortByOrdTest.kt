package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nonemptylist::sort` without a comparator — the THIRD collection to carry
 * `seq::sort`'s defect, and the third dead-and-wrong sort native beside it.
 *
 * `sort` routed through `sort_by`, so each of roughly n log n comparisons ran a
 * guest lambda. MEASURED against `seq::sort` on the same elements, min of warm
 * passes: 4 408 713 ps -> 875 225 at four hundred elements (5.04x), and
 * 1 255 457 -> 200 761 at a hundred (6.25x). It now sits 2.89x above
 * `seq::sort`'s 303 323, which is the BOXING and not a callback: a
 * `MezzeNonEmptyList` holds `Object[]`, so the upgrade buys
 * `Arrays.sort(Object[])` through `Comparable` and not seq's primitive tier. It
 * still beats `list`'s 2.35x, because a NonEmptyList has no cons walk at either
 * end.
 *
 * TWO THINGS THE FIRST MEASUREMENT GOT WRONG, both corrected by warming:
 * `sort_by`'s Bool->Int wrapper looked like 1.82x of the cost and is actually
 * free, and an unwarmed O(1) `size` read 2.6 us against 12 ns warm.
 *
 * AND `NonEmptyListNatives.sort` was DEAD AND WRONG: registered in
 * `NativeDispatch`, called from nowhere, its comparator an instanceof chain
 * ending `return 0`. A NonEmptyList of records would have come back in its
 * original order, reported as sorted. It is now the natural-order target of the
 * rewrite and CHECKS its elements instead.
 */
class NelSortByOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /**
     * A SEQ HAS MORE THAN ONE RUNTIME REPRESENTATION — the canonical
     * `MezzeSeq`, and a bare `Object[]`, which is what
     * `__native_nel_to_seq` hands back. `NativeDispatch.coerceSeq` is the
     * guest-side counterpart of this and accepts exactly the same two.
     */
    private fun elemsOf(source: String): List<Any?> = when (val r = eval(source)) {
        is Array<*> -> r.toList()
        is Iterable<*> -> r.toList()
        else -> error("not a seq: ${r.javaClass.name}")
    }

    private val prelude = """
        use std::collections::nonemptylist
        use std::collections::seq
    """

    /** `from_seq` returns an Option, so every case unwraps it the same way. */
    private fun sorted(literal: String, mapper: String = "{ x } -> x"): String = """
        $prelude
        match nonemptylist::from_seq { xs = $literal } is
          None -> []
          Some { val = n } -> nonemptylist::to_seq {
            xs = nonemptylist::map { xs = nonemptylist::sort { xs = n }, f = $mapper } }
    """

    @Test
    fun `ints sort ascending`() = assertEquals(
        listOf(1L, 3L, 3L, 5L, 7L, 9L),
        elemsOf(sorted("[5, 3, 9, 1, 7, 3]")),
    )

    /** Negatives, which an unsigned or bit-pattern order would reorder. */
    @Test
    fun `negative ints sort below positives`() = assertEquals(
        listOf(-7L, -1L, 0L, 3L, 9L),
        elemsOf(sorted("[3, 0 - 7, 0, 0 - 1, 9]")),
    )

    @Test
    fun `strings sort lexicographically`() = assertEquals(
        listOf("apple", "fig", "pear"),
        elemsOf(sorted("""["pear", "apple", "fig"]""")),
    )

    @Test
    fun `single element and all-equal inputs`() {
        assertEquals(listOf(4L), elemsOf(sorted("[4]")))
        assertEquals(listOf(8L, 8L, 8L), elemsOf(sorted("[8, 8, 8]")))
    }

    /**
     * THE BREAK TEST for the upgrade itself, and the only behavioural one
     * available: the two paths disagree about NaN.
     *
     * `Arrays.sort(Object[])` reaches `Double.compareTo`, which places NaN
     * LAST. The guest path cannot: `it <= r` is FALSE for every comparison
     * involving NaN, including `NaN <= NaN`, so the comparator answers "after"
     * for every pair and contradicts itself. Insertion sort on `[3, NaN, 1]`
     * then leaves `3, NaN, 1` — the input order — where the native gives
     * `1, 3, NaN`.
     *
     * So this failing is the signal that the rewrite stopped firing and the
     * callback came back, which no other assertion here would catch.
     */
    @Test
    fun `a Float NonEmptyList sorts with NaN last`() {
        val xs = elemsOf(sorted("[3.0, 0.0 / 0.0, 1.0]")).map { it as Double }
        assertEquals(3, xs.size)
        assertEquals(
            listOf(1.0, 3.0),
            xs.take(2),
            "3.0 first means the guest `it <= r` comparator ran: it answers" +
                " \"after\" for every NaN pair and leaves the input order",
        )
        assertTrue(xs[2].isNaN(), "NaN sorts last, as in seq::sort and list::sort")
    }

    /**
     * THE SOUNDNESS CASE. `R` is a boxed Long at runtime whose `Ord` is
     * DESCENDING, so the comparator-free native would sort it ascending and
     * silently contradict its author. The upgrade must DECLINE here.
     */
    @Test
    fun `a newtype whose Ord reverses its wrapped type sorts by that Ord`() = assertEquals(
        listOf(9L, 7L, 4L, 2L),
        elemsOf(
            """
            use std::collections::nonemptylist
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
            match nonemptylist::from_seq { xs = [R.(2), R.(9), R.(4), R.(7)] } is
              None -> []
              Some { val = n } -> nonemptylist::to_seq {
                xs = nonemptylist::map {
                  xs = nonemptylist::sort { xs = n },
                  f = { x } -> unwrap { v = x },
                } }
            """,
        ),
        "ascending means the comparator-free native sorted the boxed Long and" +
            " ignored the author's descending Ord",
    )

    /**
     * A RECORD element — what the deleted `return 0` comparator would have
     * answered wrongly rather than slowly, handing back the input order as
     * sorted. It must reach the callback, not the natural-order native.
     */
    @Test
    fun `a record element sorts by a written Ord`() = assertEquals(
        listOf(1L, 2L, 3L),
        elemsOf(
            """
            use std::collections::nonemptylist
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
            match nonemptylist::from_seq { xs = [P.({ k = 3 }), P.({ k = 1 }), P.({ k = 2 })] } is
              None -> []
              Some { val = n } -> nonemptylist::to_seq {
                xs = nonemptylist::map {
                  xs = nonemptylist::sort { xs = n },
                  f = { x } -> key { p = x },
                } }
            """,
        ),
        "3, 1, 2 — the input order — is what a comparator answering 0 for every" +
            " pair would produce",
    )

    /** `sort_by` is a different native and is never upgraded, so an arbitrary
     *  comparator keeps working, including one that disagrees with `Ord`. It is
     *  also the control for the measurement: it must not change. */
    @Test
    fun `sort_by with a descending comparator is untouched`() = assertEquals(
        listOf(5L, 4L, 2L, 1L),
        elemsOf(
            """
            $prelude
            match nonemptylist::from_seq { xs = [1, 4, 2, 5] } is
              None -> []
              Some { val = n } -> nonemptylist::to_seq {
                xs = nonemptylist::sort_by { xs = n, cmp = { l, r } -> l > r } }
            """,
        ),
    )
}
