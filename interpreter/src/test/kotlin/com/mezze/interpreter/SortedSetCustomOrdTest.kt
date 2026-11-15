package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `SortedSet`'s own `Ord` survives its native set operations — known-bugs.md
 * BUG 44.
 *
 * WHAT WAS WRONG. `MezzeSortedSet.compareElements` opened with
 * `instanceof Long/Integer/String` fast paths that answered BEFORE consulting
 * `comparator` — the field whose own comment says it holds "the element's own
 * Ord, installed at construction". A `newtype R = Int` is a boxed Long at
 * runtime, so a set built DESCENDING by its author's `Ord` was merged by
 * `union` as if ASCENDING.
 *
 * THE RESULT WAS NOT MERELY MIS-ORDERED, IT WAS CONCATENATED — the merge
 * assumes both inputs ascend, exhausts the first and appends the second:
 *
 *     union {5,3,1} {6,4,2}  ->  5, 3, 1, 6, 4, 2
 *
 * which breaks the sortedness `contains` binary-searches on. The union reported
 * size 6 while `contains` denied 2, 4 and 6 — every element of the second set,
 * present and unreachable. Silent data loss.
 *
 * THE FIX IS IN TWO HALVES, and both are needed:
 *   - the fast paths are gated on the order actually being natural;
 *   - the compiler hands `from_seq` NO comparator when the element is
 *     `OrdKind.DIRECT`, so Int and Str sets are natural-ordered from the start
 *     and keep the fast path. Without that half the gate was correct but cost
 *     8.2x on union and 9.9x on intersect, because a Mezze set always supplies
 *     a comparator — even for plain Int, where it IS natural order written as a
 *     guest closure.
 *
 * This is the BUG 10 family: a newtype is a primitive at runtime, so a
 * type-based fast path silently contradicts the order its author wrote.
 */
class SortedSetCustomOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elemsOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    /** `R` is a boxed Long at runtime whose `Ord` is DESCENDING — exactly the
     *  shape the `instanceof Long` fast path used to override. */
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
        let a = sorted_set::from_seq { xs = [R.(1), R.(5), R.(3)] }
        let b = sorted_set::from_seq { xs = [R.(2), R.(6), R.(4)] }
    """

    /** The sets themselves were always right — the bug was in the merges. */
    @Test
    fun `from_seq honours a descending Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf("$desc\nints { s = a }"),
    )

    /**
     * THE BUG, in its clearest form. Before the fix this was
     * `5, 3, 1, 6, 4, 2` — concatenation, neither order.
     */
    @Test
    fun `union preserves the author's descending order`() = assertEquals(
        listOf(6L, 5L, 4L, 3L, 2L, 1L),
        elemsOf("$desc\nints { s = sorted_set::union { s1 = a, s2 = b } }"),
        "5, 3, 1, 6, 4, 2 is the old behaviour: the merge took the ascending" +
            " fast path, exhausted the first set and appended the second",
    )

    /**
     * THE CONSEQUENCE, which is what makes this data loss rather than cosmetic.
     * Every element is in the union; before the fix `contains` denied the three
     * that came from `b`, because the binary search ran on an unsorted array.
     */
    @Test
    fun `every element of the union is findable`() {
        val found = eval(
            """
            $desc
            do
              let u = sorted_set::union { s1 = a, s2 = b }
              let hit = { x } -> if sorted_set::contains { s = u, x = R.(x) } then 1 else 0
              let tally = hit { x = 1 } + hit { x = 2 } + hit { x = 3 } + hit { x = 4 } + hit { x = 5 } + hit { x = 6 }
              { n = sorted_set::size { s = u }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(6L, found.getField("n"))
        assertEquals(
            6L, found.getField("found"),
            "3 means contains could not find the elements from the second set —" +
                " present in the array, invisible to the binary search",
        )
    }

    @Test
    fun `intersect preserves the author's descending order`() = assertEquals(
        listOf(5L, 3L),
        elemsOf(
            """
            $desc
            ints { s = sorted_set::intersect {
              s1 = a,
              s2 = sorted_set::from_seq { xs = [R.(3), R.(5), R.(9)] },
            } }
            """,
        ),
    )

    @Test
    fun `difference preserves the author's descending order`() = assertEquals(
        listOf(5L, 1L),
        elemsOf(
            """
            $desc
            ints { s = sorted_set::difference {
              s1 = a,
              s2 = sorted_set::from_seq { xs = [R.(3)] },
            } }
            """,
        ),
    )

    /**
     * A set DERIVED from a natural-ordered one must stay natural-ordered.
     *
     * The flag is remembered as the comparator AS SUPPLIED, not as the
     * effective one — which is never null after construction. Passing the
     * effective comparator to a derived set made every such set look custom and
     * lose the fast path for the rest of its life.
     */
    @Test
    fun `a plain Int set keeps working through add and union`() = assertEquals(
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 9L),
        elemsOf(
            """
            use std::collections::sorted_set
            use std::collections::seq
            do
              let p = sorted_set::add { s = sorted_set::from_seq { xs = [1, 5, 3] }, x = 9 }
              let q = sorted_set::from_seq { xs = [2, 6, 4] }
              sorted_set::to_seq { s = sorted_set::union { s1 = p, s2 = q } }
            """,
        ),
    )

    /** Plain Str, the other element the fast path covers. */
    @Test
    fun `a plain Str set unions in order`() = assertEquals(
        listOf("a", "b", "c", "d"),
        elemsOf(
            """
            use std::collections::sorted_set
            use std::collections::seq
            sorted_set::to_seq { s = sorted_set::union {
              s1 = sorted_set::from_seq { xs = ["c", "a"] },
              s2 = sorted_set::from_seq { xs = ["d", "b"] },
            } }
            """,
        ),
    )
}
