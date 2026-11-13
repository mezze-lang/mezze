package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The order travels WITH the operation, because a `SortedSet` cannot be relied
 * on to carry it — known-bugs.md BUG 44, the half that gating the fast path did
 * not reach.
 *
 * WHY ASKING THE SET IS WRONG. `from_seq` threads a comparator built from the
 * element's `Ord`, so a set built that way knows its order. `sorted_set::empty {}`
 * threads none — it has no element type yet — and `add` places elements by a
 * GUEST binary search using the author's `Ord`. So that set's ELEMENTS are in
 * the author's order while its comparator says natural. Correct array, wrong
 * label, and any native that asks the set merges a descending array as if it
 * ascended.
 *
 * THE ARRAY IS ALWAYS RIGHT, which is what makes the fix cheap: both
 * construction paths place elements by the author's `Ord`. So the merges
 * RELABEL in O(1) via `MezzeSortedSet.withComparator` — a method that already
 * existed, and whose own doc names this exact situation — rather than rebuild.
 *
 * COST: about 10% on plain-Int merges, because the `OrdKind.DIRECT` route still
 * drops the operand where the element's `Ord` is the compiler's own. Gating the
 * fast path WITHOUT that route was correct and cost 8.2x on union.
 *
 * `map` and `filter_map` are the two that take `Ord 'b` rather than `Ord 'a`:
 * their result elements are `'b`, so the source's order is not unreliable, it
 * is irrelevant.
 */
class SortedSetOrderOperandTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elemsOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    private val desc = """
        use std::collections::sorted_set
        use std::collections::seq
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
        ## BUILT BY `empty {}` + `add`, the path that carries no comparator.
        let e3 = sorted_set::add {
          s = sorted_set::add {
            s = sorted_set::add { s = sorted_set::empty {}, x = R.(1) },
            x = R.(5),
          },
          x = R.(3),
        }
        let b = sorted_set::from_seq { xs = [R.(2), R.(6), R.(4)] }
    """

    /** The set itself was always right — guest search placed these. */
    @Test
    fun `empty plus add orders by the author's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf("$desc\nints { s = e3 }"),
    )

    /**
     * THE HALF THE FIRST FIX MISSED. Gating the fast path corrected sets built
     * by `from_seq`; this one carries no comparator at all, so the merge has to
     * be told. Before this it returned `5, 3, 1, 6, 4, 2` — concatenation.
     */
    @Test
    fun `union of an empty-plus-add set merges correctly`() = assertEquals(
        listOf(6L, 5L, 4L, 3L, 2L, 1L),
        elemsOf("$desc\nints { s = sorted_set::union { s1 = e3, s2 = b } }"),
        "5, 3, 1, 6, 4, 2 means the merge asked the SET for its order, and that" +
            " set was built by `empty {}` which has none",
    )

    @Test
    fun `every element of that union is findable`() {
        val r = eval(
            """
            $desc
            do
              let u = sorted_set::union { s1 = e3, s2 = b }
              let hit = { x } -> if sorted_set::contains { s = u, x = R.(x) } then 1 else 0
              let tally = hit { x = 1 } + hit { x = 2 } + hit { x = 3 } + hit { x = 4 } + hit { x = 5 } + hit { x = 6 }
              { n = sorted_set::size { s = u }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(6L, r.getField("n"))
        assertEquals(6L, r.getField("found"))
    }

    @Test
    fun `intersect and difference of an empty-plus-add set`() {
        assertEquals(
            listOf(5L, 3L),
            elemsOf(
                "$desc\nints { s = sorted_set::intersect { s1 = e3, " +
                    "s2 = sorted_set::from_seq { xs = [R.(3), R.(5), R.(9)] } } }",
            ),
        )
        assertEquals(
            listOf(5L, 1L),
            elemsOf(
                "$desc\nints { s = sorted_set::difference { s1 = e3, " +
                    "s2 = sorted_set::from_seq { xs = [R.(3)] } } }",
            ),
        )
    }

    @Test
    fun `symmetric_difference of an empty-plus-add set`() = assertEquals(
        listOf(6L, 5L, 4L, 2L, 1L),
        elemsOf(
            "$desc\nints { s = sorted_set::symmetric_difference { s1 = e3, " +
                "s2 = sorted_set::from_seq { xs = [R.(3), R.(2), R.(4), R.(6)] } } }",
        ),
    )

    /**
     * `map` TAKES `Ord 'b`, NOT `Ord 'a`. Its result's elements are `'b`, so
     * the source's order says nothing about them — it built a set with no
     * comparator at all and produced `1, 3, 5` for a descending target.
     *
     * LET-BOUND, which is where the fix holds. See the next case for where it
     * does not.
     */
    @Test
    fun `map orders a let-bound result by the target's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $desc
            do
              let mapped = sorted_set::map {
                s = sorted_set::from_seq { xs = [1, 5, 3] },
                f = { val } -> R.(val),
              }
              ints { s = mapped }
            """,
        ),
        "1, 3, 5 means the mapped set was ordered naturally, ignoring R's Ord",
    )

    /**
     * THE SAME MAP IN ARGUMENT POSITION STILL LOSES THE ORDER, and this pins
     * that rather than hiding it.
     *
     * Identical expression, not bound to a `let`: the result is `1, 3, 5` —
     * naturally ordered — where the let-bound form above gives `5, 3, 1`. The
     * comparator IS threaded (the signature carries `Ord 'b` and the stdlib
     * passes it), so what fails is the `Ord 'b` DICTIONARY arriving at the
     * call: in argument position the comparator built is the wrong one. That is
     * the dictionary-plumbing family, not the ordering fix.
     *
     * Verified not to be the compiler's operand-dropping rewrite: removing
     * `map` from `ordNativeFastForm` changes nothing here. It is also not test
     * isolation — it fails run alone.
     *
     * THIS TEST ASSERTS THE BROKEN BEHAVIOUR ON PURPOSE, so that whoever fixes
     * the dictionary arrival is told by a failing test that this got better and
     * must be updated. known-bugs.md BUG 46.
     */
    @Test
    fun `map in argument position still loses the target order — BUG 46`() = assertEquals(
        listOf(1L, 3L, 5L),
        elemsOf(
            """
            $desc
            ints { s = sorted_set::map {
              s = sorted_set::from_seq { xs = [1, 5, 3] },
              f = { val } -> R.(val),
            } }
            """,
        ),
        "if this now reads 5, 3, 1 the dictionary arrives and BUG 46 is fixed —" +
            " delete this test and fold it into the one above",
    )

    @Test
    fun `every element of a let-bound mapped set is findable`() {
        val r = eval(
            """
            $desc
            do
              let m = sorted_set::map { s = sorted_set::from_seq { xs = [1, 5, 3] }, f = { val } -> R.(val) }
              let hit = { x } -> if sorted_set::contains { s = m, x = R.(x) } then 1 else 0
              let tally = hit { x = 1 } + hit { x = 3 } + hit { x = 5 }
              { n = sorted_set::size { s = m }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(3L, r.getField("n"))
        assertEquals(
            3L, r.getField("found"),
            "1 means the mapped set is ordered one way and searched the other",
        )
    }

    /**
     * `from_set` DECLARED `with Ord 'a` and never passed it on — the body
     * called its native bare, which built the set with no comparator. Its
     * sibling `from_list` has had the right shape all along, so this was the
     * one that was never brought along.
     *
     * A declared constraint that is never used is worse than a missing one: it
     * tells every reader the order is handled.
     */
    @Test
    fun `from_set orders by the element's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $desc
            use std::collections::set
            do
              let hs = set::from_seq { xs = [R.(1), R.(5), R.(3)] }
              ints { s = sorted_set::from_set { s = hs } }
            """,
        ),
        "1, 3, 5 means the declared `Ord 'a` was never threaded to the native",
    )

    /**
     * `set::to_sorted_set` said "elements must be comparable" in its DOC
     * COMMENT and carried no constraint at all — the requirement was written
     * for humans and not for the typechecker.
     */
    @Test
    fun `set to_sorted_set orders by the element's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $desc
            use std::collections::set
            do
              let hs = set::from_seq { xs = [R.(1), R.(5), R.(3)] }
              ints { s = set::to_sorted_set { s = hs } }
            """,
        ),
        "1, 3, 5 means the prose requirement never became a constraint",
    )

    /**
     * EVERY NATIVE WHOSE ORDER OPERAND THE COMPILER DROPS, exercised with a
     * plain Int element — which is exactly the case where it IS dropped.
     *
     * This is the shape that caught `from_list`: it read its operand with
     * `getFunction` rather than `getFunctionOrNull`, so once it joined
     * `ordNativeFastForm` every Int set threw `Missing field 'cmp'`. Four
     * existing suites failed and said so, but only because `from_list` happened
     * to be well covered. The rest are covered here on purpose, so adding a
     * table entry without making its field optional fails immediately rather
     * than wherever that native is used next.
     */
    @Test
    fun `every native with a droppable order operand works on plain Ints`() {
        val out = eval(
            """
            use std::collections::sorted_set
            use std::collections::set
            use std::collections::list
            do
              let a = sorted_set::from_seq { xs = [1, 5, 3] }
              let b = sorted_set::from_seq { xs = [2, 6, 4] }
              let fl = sorted_set::from_list { xs = list::from_seq { xs = [9, 7] } }
              let fs = sorted_set::from_set { s = set::from_seq { xs = [8] } }
              let ts = set::to_sorted_set { s = set::from_seq { xs = [11] } }
              { u = sorted_set::size { s = sorted_set::union { s1 = a, s2 = b } },
                i = sorted_set::size { s = sorted_set::intersect { s1 = a, s2 = a } },
                d = sorted_set::size { s = sorted_set::difference { s1 = a, s2 = b } },
                x = sorted_set::size { s = sorted_set::symmetric_difference { s1 = a, s2 = b } },
                fl = sorted_set::size { s = fl },
                fs = sorted_set::size { s = fs },
                ts = sorted_set::size { s = ts } }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(6L, out.getField("u"), "union")
        assertEquals(3L, out.getField("i"), "intersect")
        assertEquals(3L, out.getField("d"), "difference")
        assertEquals(6L, out.getField("x"), "symmetric_difference")
        assertEquals(2L, out.getField("fl"), "from_list")
        assertEquals(1L, out.getField("fs"), "from_set")
        assertEquals(1L, out.getField("ts"), "to_sorted_set")
    }

    /**
     * `filter_map` USES the order it is given.
     *
     * Its stdlib signature carried `Ord 'b` and threaded a comparator while the
     * NATIVE still built the result through a bare `empty()` and ignored the
     * parameter — for one commit, mine. A signature that accepts the order and
     * discards it reads as handled, which is worse than never asking, and no
     * test exercised `filter_map` with a custom `Ord` to say otherwise.
     */
    @Test
    fun `filter_map orders its result by the target's Ord`() = assertEquals(
        listOf(5L, 3L),
        elemsOf(
            """
            $desc
            use std::option
            ints { s = sorted_set::filter_map {
              set = sorted_set::from_seq { xs = [1, 5, 3] },
              f = { val } -> if val > 1 then Some { val = R.(val) } else None,
            } }
            """,
        ),
        "3, 5 means the native ignored the comparator it was handed",
    )

    /** Plain Int keeps working, and keeps the fast path — the operand is
     *  dropped where the element's `Ord` is the compiler's own. */
    @Test
    fun `plain Int merges and maps are unaffected`() {
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            elemsOf(
                """
                use std::collections::sorted_set
                sorted_set::to_seq { s = sorted_set::union {
                  s1 = sorted_set::from_seq { xs = [1, 5, 3] },
                  s2 = sorted_set::from_seq { xs = [2, 6, 4] },
                } }
                """,
            ),
        )
        assertEquals(
            listOf(2L, 10L, 6L).sorted(),
            elemsOf(
                """
                use std::collections::sorted_set
                sorted_set::to_seq { s = sorted_set::map {
                  s = sorted_set::from_seq { xs = [1, 5, 3] },
                  f = { val } -> val * 2,
                } }
                """,
            ),
        )
    }
}
