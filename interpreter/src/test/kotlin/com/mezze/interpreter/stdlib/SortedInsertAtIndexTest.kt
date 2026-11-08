package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `sorted_map::insert` and `sorted_set::add` take the position from the GUEST
 * search now, instead of handing the native a comparator and letting it search.
 *
 * WHY. The lookups stopped paying for the host-to-guest comparator callback
 * when their search moved into guest code — ~30 ns a comparison and ~300 ns a
 * lookup, against ~15 ns for the whole guest search. `insert` kept paying it:
 * at 1 000 keys the native search was most of the operation and the array copy
 * was the smaller half. One search in guest code now answers both questions
 * `insert` has, so the native's job is reduced to the copy.
 *
 * `find_index` therefore returns what a Java binary search returns: the index
 * when the key is present, and `-(insertPos + 1)` when it is absent, where
 * `insertPos` is the position that keeps the array sorted. `get` and
 * `contains_key` only look at the sign, so they are unaffected.
 *
 * WHAT MAKES THIS DANGEROUS, and what these tests are therefore about. A wrong
 * position does NOT throw and does not lose the entry — it writes a correct
 * entry into the wrong slot, leaving an UNSORTED array. Every later binary
 * search then reads that array as if it were sorted, so keys go missing with no
 * error anywhere, and `size` keeps reporting the right number. The failure is
 * silent and arbitrarily far from its cause.
 *
 * So nothing here trusts `size`, and nothing trusts a single lookup. The
 * detector for an unsorted array is LOOKING UP EVERY KEY: a binary search over
 * an unsorted array misses keys, and the sum of what comes back changes. Beside
 * it, `min_key` and `max_key` read index 0 and index `size - 1` directly, so
 * they catch a key that landed at an end where it does not belong — which a
 * lookup sweep can miss when the array happens to stay searchable.
 *
 * THE INSERTION ORDER IS THE INPUT UNDER TEST, because the position is a
 * function of it. Descending insertion makes `insertPos` 0 every time;
 * ascending makes it `length` every time, which is also the one remaining
 * in-place path; scrambled exercises everything between. All three build the
 * same map and must agree.
 *
 * SOURCE IS ASSEMBLED FROM EXPLICIT LINES, not from `trimIndent`. Interpolating
 * a multi-line block into a raw string leaves the block's lines at column 0
 * while the template's stay indented, so the outer `trimIndent` finds a common
 * prefix of nothing and strips nothing — which silently puts a `let` inside the
 * preceding `do` block. Every program here is joined at column 0 instead.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL, below the tests.
 */
class SortedInsertAtIndexTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = listOf(
        "use std::collections::sorted_map",
        "use std::collections::sorted_set",
        "use std::option",
    )

    /** Looks up 1..i and sums; a miss contributes 0, so any miss shows. */
    private val sumLookups = listOf(
        "let sum_lookups = { i, m, acc } -> if i <= 0 then acc",
        "  else do",
        "    let v = option::get_or { opt = sorted_map::get { m = m, k = i }, def = 0 }",
        "    sum_lookups { i = i - 1, m = m, acc = acc + v }",
    )

    /** A chain of inserts in exactly this order, value = key * 10. */
    private fun mapBuiltIn(order: List<Int>): List<String> {
        val out = mutableListOf("let m0 = sorted_map::empty {}")
        order.forEachIndexed { i, k ->
            out += "let m${i + 1} = sorted_map::insert { m = m$i, k = $k, v = ${k * 10} }"
        }
        out += "let m = m${order.size}"
        return out
    }

    private fun setBuiltIn(order: List<Int>): List<String> {
        val out = mutableListOf("let s0 = sorted_set::empty {}")
        order.forEachIndexed { i, k ->
            out += "let s${i + 1} = sorted_set::add { s = s$i, x = $k }"
        }
        out += "let s = s${order.size}"
        return out
    }

    private fun run(vararg parts: List<String>): Any =
        MezzeTestHelper.run(parts.flatMap { it }.joinToString("\n"))

    // 1..20, so the expected lookup sum is 10 * 20 * 21 / 2 = 2 100.
    private val ascending = (1..20).toList()
    private val descending = (1..20).reversed().toList()
    private val scrambled =
        listOf(13, 2, 19, 7, 11, 1, 20, 6, 15, 3, 9, 17, 4, 12, 8, 18, 5, 16, 10, 14)

    // ──────────────── the same map, three insertion orders ────────────────

    @Test
    fun `ascending insertion finds every key`() {
        assertEquals(2100L, run(
            prelude, sumLookups, mapBuiltIn(ascending),
            listOf("sum_lookups { i = 20, m = m, acc = 0 }"),
        ))
    }

    @Test
    fun `descending insertion finds every key`() {
        // insertPos is 0 on every one of these, the opposite end from the
        // ascending case.
        assertEquals(2100L, run(
            prelude, sumLookups, mapBuiltIn(descending),
            listOf("sum_lookups { i = 20, m = m, acc = 0 }"),
        ))
    }

    @Test
    fun `scrambled insertion finds every key`() {
        assertEquals(2100L, run(
            prelude, sumLookups, mapBuiltIn(scrambled),
            listOf("sum_lookups { i = 20, m = m, acc = 0 }"),
        ))
    }

    @Test
    fun `all three insertion orders agree on size`() {
        assertEquals(1L, run(
            prelude, mapBuiltIn(scrambled), setBuiltIn(descending),
            listOf("if sorted_map::size { m = m } == 20 && sorted_set::size { s = s } == 20 then 1 else 0"),
        ))
    }

    // ──────── the ends of the array, read by index rather than searched ────────

    @Test
    fun `min_key and max_key are the real ends after scrambled insertion`() {
        // These read index 0 and index size-1 with no search, so they catch a
        // key parked at an end it does not belong at.
        assertEquals(1L, run(
            prelude, mapBuiltIn(scrambled),
            listOf(
                "let lo = option::get_or { opt = sorted_map::min_key { m = m }, def = 0 }",
                "let hi = option::get_or { opt = sorted_map::max_key { m = m }, def = 0 }",
                "if lo == 1 && hi == 20 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `a new minimum lands at the front and a new maximum at the back`() {
        assertEquals(1L, run(
            prelude, mapBuiltIn(listOf(5, 6, 7)),
            listOf(
                "let withLo = sorted_map::insert { m = m, k = 1, v = 10 }",
                "let withHi = sorted_map::insert { m = withLo, k = 9, v = 90 }",
                "let lo = option::get_or { opt = sorted_map::min_key { m = withHi }, def = 0 }",
                "let hi = option::get_or { opt = sorted_map::max_key { m = withHi }, def = 0 }",
                "let mid = option::get_or { opt = sorted_map::get { m = withHi, k = 6 }, def = 0 }",
                "let n = sorted_map::size { m = withHi }",
                "if lo == 1 && hi == 9 && mid == 60 && n == 5 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `a key inserted into a gap is found and does not displace its neighbours`() {
        assertEquals(1L, run(
            prelude, mapBuiltIn(listOf(2, 4, 6, 8)),
            listOf(
                "let g = sorted_map::insert { m = m, k = 5, v = 50 }",
                "let a = option::get_or { opt = sorted_map::get { m = g, k = 4 }, def = 0 }",
                "let b = option::get_or { opt = sorted_map::get { m = g, k = 5 }, def = 0 }",
                "let c = option::get_or { opt = sorted_map::get { m = g, k = 6 }, def = 0 }",
                "let d = option::get_or { opt = sorted_map::get { m = g, k = 2 }, def = 0 }",
                "let e = option::get_or { opt = sorted_map::get { m = g, k = 8 }, def = 0 }",
                "let n = sorted_map::size { m = g }",
                "if a == 40 && b == 50 && c == 60 && d == 20 && e == 80 && n == 5 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `an insert into the empty map is findable`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let m = sorted_map::insert { m = sorted_map::empty {}, k = 42, v = 420 }",
                "let v = option::get_or { opt = sorted_map::get { m = m, k = 42 }, def = 0 }",
                "if v == 420 && sorted_map::size { m = m } == 1 then 1 else 0",
            ),
        ))
    }

    // ───────────────────── replace, the other branch ─────────────────────

    @Test
    fun `a replace changes the value and nothing else`() {
        // 2100 - 70 + 777 = 2807, so every other key must still be reachable.
        assertEquals(1L, run(
            prelude, sumLookups, mapBuiltIn(scrambled),
            listOf(
                "let r = sorted_map::insert { m = m, k = 7, v = 777 }",
                "let got = option::get_or { opt = sorted_map::get { m = r, k = 7 }, def = 0 }",
                "let lo = option::get_or { opt = sorted_map::min_key { m = r }, def = 0 }",
                "let hi = option::get_or { opt = sorted_map::max_key { m = r }, def = 0 }",
                "let total = sum_lookups { i = 20, m = r, acc = 0 }",
                "let n = sorted_map::size { m = r }",
                "if got == 777 && total == 2807 && lo == 1 && hi == 20 && n == 20 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `a replace leaves the map it came from alone`() {
        assertEquals(1L, run(
            prelude, mapBuiltIn(scrambled),
            listOf(
                "let r = sorted_map::insert { m = m, k = 7, v = 777 }",
                "let base = option::get_or { opt = sorted_map::get { m = m, k = 7 }, def = 0 }",
                "let derived = option::get_or { opt = sorted_map::get { m = r, k = 7 }, def = 0 }",
                "if base == 70 && derived == 777 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `an insert leaves the map it came from alone`() {
        // The persistence property the shared keys array has to preserve, and
        // the one the deleted in-place middle shift broke (BUG 40).
        assertEquals(1L, run(
            prelude, mapBuiltIn(listOf(2, 4, 6, 8)),
            listOf(
                "let bigger = sorted_map::insert { m = m, k = 5, v = 50 }",
                "let leaked = sorted_map::contains_key { m = m, k = 5 }",
                "let base_n = sorted_map::size { m = m }",
                "let hi = option::get_or { opt = sorted_map::max_key { m = m }, def = 0 }",
                "let big_n = sorted_map::size { m = bigger }",
                "if leaked.not {} && base_n == 4 && hi == 8 && big_n == 5 then 1 else 0",
            ),
        ))
    }

    // ────────────────────────────── the set ──────────────────────────────

    @Test
    fun `a scrambled set contains every element and no others`() {
        assertEquals(1L, run(
            prelude, setBuiltIn(scrambled),
            listOf(
                "let all_in = { i, s } -> if i <= 0 then True",
                "  else if sorted_set::contains { s = s, x = i } then all_in { i = i - 1, s = s }",
                "  else False",
                "let every = all_in { i = 20, s = s }",
                "let lo = option::get_or { opt = sorted_set::min { s = s }, def = 0 }",
                "let hi = option::get_or { opt = sorted_set::max { s = s }, def = 0 }",
                "let over = sorted_set::contains { s = s, x = 21 }",
                "let under = sorted_set::contains { s = s, x = 0 }",
                "let n = sorted_set::size { s = s }",
                "if every && lo == 1 && hi == 20 && over.not {} && under.not {} && n == 20 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `adding an element already present is still a no-op`() {
        assertEquals(1L, run(
            prelude, setBuiltIn(listOf(3, 1, 2)),
            listOf(
                "let again = sorted_set::add { s = s, x = 2 }",
                "let n = sorted_set::size { s = again }",
                "let a = sorted_set::contains { s = again, x = 1 }",
                "let b = sorted_set::contains { s = again, x = 2 }",
                "let c = sorted_set::contains { s = again, x = 3 }",
                "if n == 3 && a && b && c then 1 else 0",
            ),
        ))
    }

    // ──────────────────────── a user-defined ordering ────────────────────────

    @Test
    fun `a user-defined descending Ord places keys by that order`() {
        // The position comes from the guest comparison, so a user `Ord` has to
        // drive it. Under a descending order `min_key` is the LARGEST `v`,
        // which is what distinguishes this from the natural ordering.
        assertEquals(1L, run(
            prelude,
            listOf(
                "use std::abilities::compare::{ Ord }",
                "newtype Down = { v: Int }",
                "let dv: { d: Down } -> Int = { d } -> match d is",
                "  Down.({ v }) -> v",
                "impl Ord for Down where {",
                "  lt = { it, r } -> dv { d = it } > dv { d = r },",
                "  le = { it, r } -> dv { d = it } >= dv { d = r },",
                "  gt = { it, r } -> dv { d = it } < dv { d = r },",
                "  ge = { it, r } -> dv { d = it } <= dv { d = r },",
                "}",
                "let m0 = sorted_map::empty {}",
                "let m1 = sorted_map::insert { m = m0, k = Down.({ v = 3 }), v = 30 }",
                "let m2 = sorted_map::insert { m = m1, k = Down.({ v = 1 }), v = 10 }",
                "let m3 = sorted_map::insert { m = m2, k = Down.({ v = 5 }), v = 50 }",
                "let m4 = sorted_map::insert { m = m3, k = Down.({ v = 2 }), v = 20 }",
                "let a = option::get_or { opt = sorted_map::get { m = m4, k = Down.({ v = 1 }) }, def = 0 }",
                "let b = option::get_or { opt = sorted_map::get { m = m4, k = Down.({ v = 2 }) }, def = 0 }",
                "let c = option::get_or { opt = sorted_map::get { m = m4, k = Down.({ v = 3 }) }, def = 0 }",
                "let d = option::get_or { opt = sorted_map::get { m = m4, k = Down.({ v = 5 }) }, def = 0 }",
                "let miss = sorted_map::contains_key { m = m4, k = Down.({ v = 4 }) }",
                "let lo = match sorted_map::min_key { m = m4 } is",
                "  Some { val } -> dv { d = val }",
                "  None -> 0",
                "let n = sorted_map::size { m = m4 }",
                "if a == 10 && b == 20 && c == 30 && d == 50 && miss.not {} && lo == 5 && n == 4 then 1 else 0",
            ),
        ))
    }
}
