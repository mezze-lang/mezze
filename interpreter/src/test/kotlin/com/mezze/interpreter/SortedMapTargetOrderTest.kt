package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `sorted_map::map_keys` and `invert` order their result by the TARGET's `Ord`
 * — known-bugs.md BUG 45, the last two of this family.
 *
 * WHAT WAS WRONG, and it was KNOWN. Both declared their constraint —
 * `with Ord 'k2` and `with Ord 'v` — and neither passed it on: the natives set
 * `order = null` outright, with a comment saying "these fall back to natural
 * ordering and are therefore still primitive-only (tracked with the
 * sorted-container gap)". So unlike the six found in `sorted_set`, this was a
 * deliberate deferral rather than an oversight. It is cheap to fix now only
 * because the machinery to drop the operand for a directly-comparable key
 * exists, which it did not when that note was written.
 *
 * Measured first: `map_keys` into a descending newtype gave keys `1, 3, 5` and
 * a `get` that answered NO for two of three — the map holds them and cannot
 * find them, because the array is naturally ordered while the search uses the
 * author's `Ord`.
 *
 * WHY `sorted_map` ESCAPED THE REST OF THE FAMILY, settled by probing rather
 * than assumed: it has NO merge operations — no union, intersect or difference
 * — so nothing natively re-reads its array against a comparator. `sorted_set`
 * broke because it has four. A map built by `empty {}` + `insert` is therefore
 * correct here; that was predicted to be broken and measured not to be.
 */
class SortedMapTargetOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun keysOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    /** `K` is a boxed Long at runtime whose `Ord` is DESCENDING. */
    private val desc = """
        use std::collections::sorted_map
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype K = Int
        let un: { v: K } -> Int = { v } -> match v is
          K.(n) -> n
        impl Hash for K where {
          hash = { it } -> un { v = it }
        }
        impl Eq for K where {
          eq = { it, r } -> un { v = it } == un { v = r },
          neq = { it, r } -> if un { v = it } == un { v = r } then False else True,
        }
        impl Ord for K where {
          lt = { it, r } -> un { v = it } > un { v = r },
          le = { it, r } -> un { v = it } >= un { v = r },
          gt = { it, r } -> un { v = it } < un { v = r },
          ge = { it, r } -> un { v = it } <= un { v = r },
        }
        let ks = { m } -> seq::map {
          xs = list::to_seq { xs = sorted_map::keys { m = m } },
          f = { x } -> un { v = x },
        }
        let plain = sorted_map::from_list { xs = list::from_seq {
          xs = [{ k = 1, v = 10 }, { k = 5, v = 50 }, { k = 3, v = 30 }],
        } }
    """

    /**
     * THE BUG. Before the fix the keys came out `1, 3, 5` — naturally ordered,
     * ignoring K's descending `Ord`.
     */
    @Test
    fun `map_keys orders its result by the target's Ord`() = assertEquals(
        listOf(5L, 3L, 1L),
        keysOf("$desc\nks { m = sorted_map::map_keys { m = plain, f = { val } -> K.(val) } }"),
        "1, 3, 5 means the declared `Ord 'k2` was never threaded and the native" +
            " built the map with a null order",
    )

    /**
     * THE CONSEQUENCE: a naturally-ordered array searched by the author's
     * `Ord`, so the map's own keys go missing. Before the fix this found 1 of 3.
     */
    @Test
    fun `every key of a mapped map is findable`() {
        val r = eval(
            """
            $desc
            do
              let mk = sorted_map::map_keys { m = plain, f = { val } -> K.(val) }
              let hit = { x } -> match sorted_map::get { m = mk, k = K.(x) } is
                Some { val } -> 1
                None -> 0
              let tally = hit { x = 1 } + hit { x = 3 } + hit { x = 5 }
              { n = sorted_map::size { m = mk }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(3L, r.getField("n"))
        assertEquals(
            3L, r.getField("found"),
            "1 means the map is ordered one way and searched the other",
        )
    }

    /** `invert`'s new keys are the old VALUES, so `Ord 'v` is what applies. */
    @Test
    fun `invert orders by the value's Ord`() = assertEquals(
        listOf(50L, 30L, 10L),
        keysOf(
            """
            $desc
            do
              let m = sorted_map::from_list { xs = list::from_seq {
                xs = [{ k = 1, v = K.(10) }, { k = 2, v = K.(50) }, { k = 3, v = K.(30) }],
              } }
              ks { m = sorted_map::invert { m = m } }
            """,
        ),
        "10, 30, 50 means invert used natural order for its new keys",
    )

    /**
     * A map built by `empty {}` + `insert` is CORRECT here, unlike
     * `sorted_set`'s equivalent. Recorded because it was predicted to be broken
     * and measured not to be: nothing in `sorted_map` natively re-reads the
     * array against a comparator, there being no merge operations.
     */
    @Test
    fun `empty plus insert is correct because sorted_map has no merges`() {
        val r = eval(
            """
            $desc
            do
              let e = sorted_map::insert {
                m = sorted_map::insert {
                  m = sorted_map::insert { m = sorted_map::empty {}, k = K.(1), v = 10 },
                  k = K.(5), v = 50,
                },
                k = K.(3), v = 30,
              }
              let hit = { x } -> match sorted_map::get { m = e, k = K.(x) } is
                Some { val } -> 1
                None -> 0
              let tally = hit { x = 1 } + hit { x = 3 } + hit { x = 5 }
              { keys = ks { m = e }, found = tally }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(listOf(5L, 3L, 1L), (r.getField("keys") as Iterable<*>).toList())
        assertEquals(3L, r.getField("found"))
    }

    /**
     * `sorted_map::partition` keeps the source's order in BOTH halves.
     *
     * The key type is unchanged, so it inherits — rebuilding through a bare
     * `empty()` dropped the ordering it was partitioned from. It inherits
     * `comparator()`, the EFFECTIVE one, which is right here and was wrong for
     * `sorted_set`: this map has no instanceof fast paths, so there is no
     * natural-order flag to preserve and nothing to forfeit.
     */
    @Test
    fun `partition keeps the source order in both halves`() {
        val r = eval(
            """
            $desc
            do
              let m = sorted_map::from_list { xs = list::from_seq {
                xs = [{ k = K.(1), v = 10 }, { k = K.(5), v = 50 }, { k = K.(3), v = 30 }],
              } }
              let p = sorted_map::partition { m = m, p = { k, v } -> v > 15 }
              { pass = ks { m = p.pass }, fail = ks { m = p.fail } }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(listOf(5L, 3L), (r.getField("pass") as Iterable<*>).toList())
        assertEquals(listOf(1L), (r.getField("fail") as Iterable<*>).toList())
    }

    /** Plain Int keys keep working — the operand is dropped for them, which is
     *  what holds the cost of all this to nothing on the common case. */

    @Test
    fun `plain Int map_keys is unaffected`() = assertEquals(
        listOf(2L, 6L, 10L),
        (eval(
            """
            use std::collections::sorted_map
            use std::collections::list
            do
              let plain = sorted_map::from_list { xs = list::from_seq {
                xs = [{ k = 1, v = 10 }, { k = 5, v = 50 }, { k = 3, v = 30 }],
              } }
              list::to_seq { xs = sorted_map::keys {
                m = sorted_map::map_keys { m = plain, f = { val } -> val * 2 },
              } }
            """,
        ) as Iterable<*>).toList(),
    )
}
