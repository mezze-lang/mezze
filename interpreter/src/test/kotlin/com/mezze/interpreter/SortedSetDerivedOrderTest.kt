package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A set DERIVED from a sorted set keeps the order it was derived from —
 * known-bugs.md BUG 45.
 *
 * THE CODEBASE ALREADY NAMED THIS FAILURE MODE. `SortedSetNatives.slice`'s doc
 * records that the set's `take` and `drop` "rebuilt through
 * `MezzeSortedSet.empty()` with NO comparator, so the result lost the ordering
 * it was sliced from". Those two were fixed; the identical line survived in six
 * siblings, which this covers:
 *
 *     range, head_set, tail_set, symmetric_difference, filter, partition
 *
 * All PRESERVE the element type, so the result simply has to inherit the
 * source's order — no signature changes.
 *
 * WHAT THE RESULT LOOKED LIKE. A set rebuilt without its comparator is ordered
 * NATURALLY while `contains` still searches it with the author's `Ord` — a
 * guest binary search over an array sorted the other way. Elements present in
 * the set become invisible, which is BUG 44's silent data loss by a different
 * route.
 *
 * THE INHERITED COMPARATOR MUST BE THE ONE AS SUPPLIED, not `comparator()`,
 * which is never null after construction. Rebuilding with the effective one
 * marks the result custom-ordered and forfeits the fast paths — measured at
 * 8.2x on union when BUG 44's gate was first applied without that distinction.
 */
class SortedSetDerivedOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elemsOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    /** A boxed Long at runtime whose `Ord` is DESCENDING. */
    private val desc = """
        use std::collections::sorted_set
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }
        newtype R = Int
        let un: { v: R } -> Int = { v } -> match v is
          R.(n) -> n
        impl Ord for R where {
          lt = { it, r } -> un { v = it } > un { v = r },
          le = { it, r } -> un { v = it } >= un { v = r },
          gt = { it, r } -> un { v = it } < un { v = r },
          ge = { it, r } -> un { v = it } <= un { v = r },
        }
        let ints = { s } -> seq::map { xs = sorted_set::to_seq { s = s }, f = { x } -> un { v = x } }
        let a = sorted_set::from_seq { xs = [R.(1), R.(5), R.(3), R.(7)] }
    """

    @Test
    fun `filter keeps the source order`() = assertEquals(
        listOf(7L, 5L, 3L),
        elemsOf("$desc\nints { s = sorted_set::filter { s = a, p = { val } -> un { v = val } > 1 } }"),
        "1, 3, 5 ascending means the filtered set was rebuilt without its comparator",
    )

    @Test
    fun `symmetric_difference keeps the source order`() = assertEquals(
        listOf(7L, 5L, 2L),
        elemsOf(
            """
            $desc
            ints { s = sorted_set::symmetric_difference {
              s1 = a,
              s2 = sorted_set::from_seq { xs = [R.(1), R.(3), R.(2)] },
            } }
            """,
        ),
    )

    @Test
    fun `partition keeps the source order in both halves`() {
        val both = eval(
            """
            $desc
            do
              let p = sorted_set::partition { s = a, p = { val } -> un { v = val } > 3 }
              { pass = ints { s = p.pass }, fail = ints { s = p.fail } }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(listOf(7L, 5L), (both.getField("pass") as Iterable<*>).toList())
        assertEquals(listOf(3L, 1L), (both.getField("fail") as Iterable<*>).toList())
    }

    /**
     * THE CONSEQUENCE, the same shape BUG 44 had: a derived set whose order was
     * lost is searched by `contains` using the author's `Ord`, so its own
     * elements go missing.
     */
    @Test
    fun `every element of a filtered set is still findable`() {
        val found = eval(
            """
            $desc
            do
              let f = sorted_set::filter { s = a, p = { val } -> un { v = val } > 1 }
              let hit = { x } -> if sorted_set::contains { s = f, x = R.(x) } then 1 else 0
              let tally = hit { x = 3 } + hit { x = 5 } + hit { x = 7 }
              { n = sorted_set::size { s = f }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(3L, found.getField("n"))
        assertEquals(
            3L, found.getField("found"),
            "fewer than 3 means the filtered set is ordered one way and searched" +
                " the other — elements present and invisible",
        )
    }

    /** A plain Int set derives correctly too, and must KEEP the fast path —
     *  which is why the inherited comparator is the one as supplied. */
    @Test
    fun `a plain Int set filters in order`() = assertEquals(
        listOf(3L, 5L, 7L),
        elemsOf(
            """
            use std::collections::sorted_set
            use std::collections::seq
            sorted_set::to_seq { s = sorted_set::filter {
              s = sorted_set::from_seq { xs = [1, 5, 3, 7] },
              p = { val } -> val > 1,
            } }
            """,
        ),
    )
}
