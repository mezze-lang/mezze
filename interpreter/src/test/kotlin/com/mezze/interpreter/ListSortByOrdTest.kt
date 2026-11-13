package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `list::sort` without a comparator, and the gate that keeps it honest.
 *
 * THE SAME DEFECT `seq::sort` HAD, VERBATIM. `list::sort` routed through
 * `sort_by`, so each of roughly n log n comparisons ran a guest lambda:
 * 1 713 883 ps for a hundred descending elements against 1 530 512 for
 * `sort_by` itself — the wrapper was nearly free and the comparator was the
 * cost. And `ListNatives.sort` was registered in BOTH `NativeDispatch` and
 * `ListOps.kt`, called from nowhere, with a comparator ending `return 0`: an
 * instanceof chain identical to the one in `SeqNatives`. A list of records
 * would have come back in its original order reported as sorted.
 *
 * 2.35x once the comparator went — less than seq's 10.2x, and the reason is
 * worth knowing: a `MezzeSeq` of Ints is backed by a `long[]`, so its
 * comparator-free sort is primitive. A `MezzeList` is cons cells of BOXED
 * elements, so the best available is `Arrays.sort(Object[])` through
 * `Comparable`, measured at 3.9x the primitive path's cost.
 *
 * The cases below mirror `SortByOrdTest`'s, because the gate they check is the
 * same one: the upgrade is applied only where the element was PROVED directly
 * comparable, and a decline must still give the author's order.
 */
class ListSortByOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::collections::list
        use std::collections::seq
    """

    /** `list::to_seq` so the result can be compared element-wise. */
    private fun elemsOf(source: String): List<Any?> =
        (eval(source) as Iterable<*>).toList()

    @Test
    fun `ints sort ascending`() = assertEquals(
        listOf(1L, 3L, 3L, 5L, 7L, 9L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::sort { xs = list::from_seq { xs = [5, 3, 9, 1, 7, 3] } } }
            """,
        ),
    )

    /** Negatives, which an unsigned or bit-pattern order would reorder. */
    @Test
    fun `negative ints sort below positives`() = assertEquals(
        listOf(-7L, -1L, 0L, 3L, 9L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::sort { xs = list::from_seq { xs = [3, 0 - 7, 0, 0 - 1, 9] } } }
            """,
        ),
    )

    @Test
    fun `strings sort lexicographically`() = assertEquals(
        listOf("apple", "fig", "pear"),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::sort { xs = list::from_seq { xs = ["pear", "apple", "fig"] } } }
            """,
        ),
    )

    @Test
    fun `empty and single-element lists`() {
        assertEquals(
            0L,
            eval("$prelude\nlist::size { xs = list::sort { xs = list::empty {} } }"),
        )
        assertEquals(
            listOf(4L),
            elemsOf("$prelude\nlist::to_seq { xs = list::sort { xs = list::from_seq { xs = [4] } } }"),
        )
    }

    @Test
    fun `already sorted and all equal inputs`() {
        assertEquals(
            listOf(1L, 2L, 3L),
            elemsOf("$prelude\nlist::to_seq { xs = list::sort { xs = list::from_seq { xs = [1, 2, 3] } } }"),
        )
        assertEquals(
            listOf(8L, 8L, 8L),
            elemsOf("$prelude\nlist::to_seq { xs = list::sort { xs = list::from_seq { xs = [8, 8, 8] } } }"),
        )
    }

    /**
     * THE SOUNDNESS CASE, the same one `seq::sort` has. `R` is a boxed Long at
     * runtime whose `Ord` is DESCENDING, so the comparator-free native would
     * sort it ascending and silently contradict its author.
     */
    private val reversedOrd = """
        use std::collections::list
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
    fun `a newtype whose Ord reverses its wrapped type sorts by that Ord`() = assertEquals(
        listOf(9L, 7L, 4L, 2L),
        elemsOf(
            """
            $reversedOrd
            list::to_seq { xs = list::map {
              xs = list::sort { xs = list::from_seq { xs = [R.(2), R.(9), R.(4), R.(7)] } },
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
     * sorted.
     */
    @Test
    fun `a record element sorts by a written Ord`() = assertEquals(
        listOf(1L, 2L, 3L),
        elemsOf(
            """
            use std::collections::list
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
            list::to_seq { xs = list::map {
              xs = list::sort { xs = list::from_seq { xs = [P.({ k = 3 }), P.({ k = 1 }), P.({ k = 2 })] } },
              f = { x } -> key { p = x },
            } }
            """,
        ),
        "3, 1, 2 — the input order — is what a comparator answering 0 for every" +
            " pair would produce",
    )

    /** `sort_by` is a different native and is never upgraded, so an arbitrary
     *  comparator keeps working, including one that disagrees with `Ord`. */
    @Test
    fun `sort_by with a descending comparator is untouched`() = assertEquals(
        listOf(5L, 4L, 2L, 1L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::sort_by {
              xs = list::from_seq { xs = [1, 4, 2, 5] },
              cmp = { a, b } -> a > b,
            } }
            """,
        ),
    )

    /** Float keeps the rule `seq::sort` states: NaN last, operators unchanged.
     *  `Arrays.sort(Object[])` reaches it through `Double.compareTo`, so the two
     *  collections agree without either being told about the other. */
    @Test
    fun `a Float list sorts with NaN last`() {
        val xs = elemsOf(
            """
            $prelude
            use std::float
            list::to_seq { xs = list::sort { xs = list::from_seq { xs = [3.0, 0.0 / 0.0, 1.0] } } }
            """,
        ).map { it as Double }
        assertEquals(3, xs.size)
        assertEquals(listOf(1.0, 3.0), xs.take(2))
        assertEquals(true, xs[2].isNaN(), "NaN sorts last, as in seq::sort")
    }
}
