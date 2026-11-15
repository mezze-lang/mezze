package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Converting INTO a sorted container uses the element's own `Ord` — and does
 * not require it to be a Java `Comparable`. known-bugs.md BUG 45.
 *
 * FOUR CONVERSIONS had `set::to_sorted_set`'s defect: `seq::to_sorted_set`,
 * `seq::to_sorted_map`, `deque::to_sorted_set`, `deque::to_sorted_map`. Each
 * built its result through a bare `empty()` and declared no `Ord`, so a
 * hand-written one was ignored — measured as `1, 3, 5` for a descending
 * newtype, with `contains` finding one element of three.
 *
 * AND `SeqNatives.toSortedSet` HAD A SECOND, DIFFERENT BUG:
 *
 *     result = result.add((Comparable) seq.get(i));
 *
 * `add(A)` is UNBOUNDED and delegates to `addObject`, so the cast bought
 * nothing — and it reintroduced at the call site the very checkcast
 * `MezzeSortedSet`'s class comment records removing: "ClassCastException for
 * any Mezze record or variant, so a sorted set of user values could not even be
 * iterated". A fix to the container's type parameter, undone by one of its
 * callers.
 *
 * WHY IT SURVIVED SO LONG: a `newtype` over Int is a boxed Long at runtime,
 * which IS `Comparable`, so only record and variant elements ever crashed —
 * and nothing tested those.
 */
class SortedConversionOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elemsOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    /** A boxed Long at runtime whose `Ord` is DESCENDING. */
    private val desc = """
        use std::collections::seq
        use std::collections::deque
        use std::collections::sorted_set
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype R = Int
        let un: { v: R } -> Int = { v } -> match v is
          R.(n) -> n
        impl Hash for R where {
          hash = { it } -> un { v = it }
        }
        impl Eq for R where {
          eq = { it, r } -> un { v = it } == un { v = r },
          neq = { it, r } -> if un { v = it } == un { v = r } then False else True,
        }
        impl Ord for R where {
          lt = { it, r } -> un { v = it } > un { v = r },
          le = { it, r } -> un { v = it } >= un { v = r },
          gt = { it, r } -> un { v = it } < un { v = r },
          ge = { it, r } -> un { v = it } <= un { v = r },
        }
        let ints = { s } -> seq::map { xs = sorted_set::to_seq { s = s }, f = { x } -> un { v = x } }
    """

    @Test
    fun `seq to_sorted_set uses the element's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf("$desc\nints { s = seq::to_sorted_set { xs = [R.(1), R.(5), R.(3)] } }"),
        "1, 3, 5 means the conversion built its result with no comparator",
    )

    @Test
    fun `deque to_sorted_set uses the element's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            "$desc\nints { s = deque::to_sorted_set { " +
                "d = deque::from_seq { xs = [R.(1), R.(5), R.(3)] } } }",
        ),
    )

    /** THE CONSEQUENCE: a naturally-ordered array searched by the author's
     *  `Ord`, so the set's own elements go missing. Before: 1 of 3. */
    @Test
    fun `every element of a converted set is findable`() {
        val r = eval(
            """
            $desc
            do
              let s = seq::to_sorted_set { xs = [R.(1), R.(5), R.(3)] }
              let hit = { x } -> if sorted_set::contains { s = s, x = R.(x) } then 1 else 0
              let tally = hit { x = 1 } + hit { x = 3 } + hit { x = 5 }
              { n = sorted_set::size { s = s }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(3L, r.getField("n"))
        assertEquals(3L, r.getField("found"))
    }

    /**
     * THE CAST. A `newtype` over a RECORD is a `MezzeRecord` at runtime, which
     * is not `Comparable` — this threw
     * `ClassCastException: MezzeRecord cannot be cast to java.lang.Comparable`
     * before the cast was removed. The newtype-over-Int cases above never
     * caught it, because a boxed Long passes that cast.
     */
    @Test
    fun `a record element converts without a ClassCastException`() {
        val n = eval(
            """
            use std::collections::seq
            use std::collections::sorted_set
            use std::abilities::compare::{ Eq, Ord, Hash }
            newtype P = { k: Int }
            let pk: { p: P } -> Int = { p } -> match p is
              P.({ k }) -> k
            impl Hash for P where {
              hash = { it } -> pk { p = it }
            }
            impl Eq for P where {
              eq = { it, r } -> pk { p = it } == pk { p = r },
              neq = { it, r } -> if pk { p = it } == pk { p = r } then False else True,
            }
            impl Ord for P where {
              lt = { it, r } -> pk { p = it } < pk { p = r },
              le = { it, r } -> pk { p = it } <= pk { p = r },
              gt = { it, r } -> pk { p = it } > pk { p = r },
              ge = { it, r } -> pk { p = it } >= pk { p = r },
            }
            sorted_set::size { s = seq::to_sorted_set { xs = [P.({ k = 3 }), P.({ k = 1 })] } }
            """,
        )
        assertEquals(2L, n, "a record element must not have to be a Java Comparable")
    }

    /** Plain Int keeps working — the operand is dropped for it, which is what
     *  keeps these conversions as fast as they were. */
    @Test
    fun `plain Int conversions are unaffected`() = assertEquals(
        listOf(1L, 3L, 5L),
        (eval(
            """
            use std::collections::seq
            use std::collections::sorted_set
            sorted_set::to_seq { s = seq::to_sorted_set { xs = [1, 5, 3] } }
            """,
        ) as Iterable<*>).toList(),
    )
}
