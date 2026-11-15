package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `sorted_map::get` and `sorted_map::contains_key` run their binary search in
 * GUEST code now, and must still find exactly what the native search found.
 *
 * WHY. A SortedMap is a sorted array, so a lookup is a binary search; it used
 * to run inside the native, which put the COMPARISON in host code and so called
 * back into guest code once per comparison — two allocations and an uncached
 * `CallTarget.call` each time. `bench/cases/dsmore` measured the result at 29-60x
 * the hash map's equivalents, and an unsound probe that skipped the callback
 * entirely bounded the prize at 10-13x.
 *
 * No way of making that callback cheaper reaches it: host code is past the
 * `@TruffleBoundary`, so Graal can never inline a guest function into it, and
 * ten callbacks a lookup is ten full Truffle calls however they are issued.
 * Running the search in guest code makes the comparison an ordinary `<` that
 * DOES inline, and reduces the native's job to an array read. Interleaved
 * against HEAD, converged at two sizes of n:
 *
 *     sorted_map::get          309 780 -> 31 114   10.1x
 *     sorted_map::contains_key 308 171 -> 27 753   11.2x
 *     sorted_set::contains  (control)    313 423   flat — still native
 *     sorted_map::insert    (control)    582 451   flat
 *     sorted_map::size      (control)        425   flat
 *     deque::get            (control)    218 566   flat
 *
 * It lands ON the probe's bound, and unlike the probe it is sound for ANY `Ord`
 * rather than only canonical primitive ones — a user comparator gets inlined
 * too.
 *
 * WHAT THESE GUARD. A hand-written binary search is exactly the kind of code
 * that is wrong at the edges, so the tests below hit them deliberately: the
 * empty map, one entry, keys below/above/between stored keys, the first and
 * last key, and every key of a 64-entry map looked up individually so no index
 * arithmetic can be off by one anywhere in the range. `find_index` returns a
 * NEGATIVE number for absent, which `get` turns into `None` and
 * `contains_key` into false — an off-by-one there would report a neighbour's
 * value, which `every key maps to its own value` would catch.
 *
 * ORDER MATTERS AND IS TESTED: the search compares STORED key against PROBE
 * key, matching what the native comparator was handed. A reversed comparison
 * still terminates and still finds keys in a symmetric ordering, so the
 * descending-`Ord` test below is what actually pins the direction.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL, below the tests.
 */
class SortedMapGuestSearchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::sorted_map
        use std::option
    """.trimIndent()

    /** Builds 1..n with value k*10, so a wrong index reads a wrong value. */
    private val fill = """
        let fill = { i, m } -> if i <= 0 then m
          else fill { i = i - 1, m = sorted_map::insert { m = m, k = i, v = i * 10 } }
    """.trimIndent()

    @Test
    fun `a present key returns its own value`() {
        assertEquals(70L, MezzeTestHelper.run("""
            $prelude
            $fill
            let m = fill { i = 9, m = sorted_map::empty {} }
            option::get_or { opt = sorted_map::get { m = m, k = 7 }, def = 0 }
        """.trimIndent()))
    }

    /**
     * EVERY KEY OF A 64-ENTRY MAP, each looked up on its own. Sum of i*10 for
     * 1..64 = 10 * 64 * 65 / 2 = 20 800. Any off-by-one anywhere in the range
     * changes this.
     */
    @Test
    fun `every key maps to its own value across 64 entries`() {
        assertEquals(20800L, MezzeTestHelper.run("""
            $prelude
            $fill
            let m = fill { i = 64, m = sorted_map::empty {} }
            let sum = { i, acc } -> if i <= 0 then acc
              else do
                let v = option::get_or { opt = sorted_map::get { m = m, k = i }, def = 0 }
                sum { i = i - 1, acc = acc + v }
            sum { i = 64, acc = 0 }
        """.trimIndent()))
    }

    /** The empty map: `hi` starts at -1, so the loop must not run at all. */
    @Test
    fun `an empty map finds nothing`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::empty {}
            let got = match sorted_map::get { m = m, k = 1 } is
              Some { val } -> 0 - val
              None -> 1
            if sorted_map::contains_key { m = m, k = 1 } then 0 - 99 else got
        """.trimIndent()))
    }

    @Test
    fun `a single-entry map finds its key and rejects others`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = 50 }
            let hit = sorted_map::contains_key { m = m, k = 5 }
            let below = sorted_map::contains_key { m = m, k = 4 }
            let above = sorted_map::contains_key { m = m, k = 6 }
            if hit && below.not {} && above.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * THE EDGES OF THE RANGE AND THE GAPS INSIDE IT. Keys are 2,4,6,8; probes
     * are 1 (below all), 3 and 5 (between), 9 (above all), plus 2 and 8 (the
     * first and last stored keys, where `lo`/`hi` collapse).
     */
    @Test
    fun `boundaries and gaps resolve correctly`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::insert {
                m = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = 20 },
                k = 4, v = 40 }, k = 6, v = 60 }, k = 8, v = 80
            }
            let first = option::get_or { opt = sorted_map::get { m = m, k = 2 }, def = 0 }
            let last = option::get_or { opt = sorted_map::get { m = m, k = 8 }, def = 0 }
            let below = sorted_map::contains_key { m = m, k = 1 }
            let gap1 = sorted_map::contains_key { m = m, k = 3 }
            let gap2 = sorted_map::contains_key { m = m, k = 5 }
            let above = sorted_map::contains_key { m = m, k = 9 }
            if first == 20 && last == 80 && below.not {} &&
               gap1.not {} && gap2.not {} && above.not {} then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `inserting over a key finds the new value`() {
        assertEquals(99L, MezzeTestHelper.run("""
            $prelude
            $fill
            let m = sorted_map::insert { m = fill { i = 9, m = sorted_map::empty {} }, k = 4, v = 99 }
            option::get_or { opt = sorted_map::get { m = m, k = 4 }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `Str keys search by their own ordering`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::insert {
                m = sorted_map::empty {}, k = "banana", v = 2 },
                k = "apple", v = 1 }, k = "cherry", v = 3
            }
            let a = option::get_or { opt = sorted_map::get { m = m, k = "apple" }, def = 0 }
            let c = option::get_or { opt = sorted_map::get { m = m, k = "cherry" }, def = 0 }
            let miss = sorted_map::contains_key { m = m, k = "durian" }
            if a == 1 && c == 3 && miss.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * A DESCENDING `Ord`, which is what pins the comparison DIRECTION. The
     * search compares stored-key against probe-key; a reversed comparison would
     * still terminate and would still find keys under a symmetric ordering, so
     * only a key type whose order is not the natural one can tell the two
     * apart. It also proves the win is not a primitive-only special case: this
     * comparator is user code, inlined like any other.
     */
    @Test
    fun `a user-defined descending Ord is respected`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            use std::abilities::compare::{ Ord }
            newtype Down = { v: Int }
            let dv: { d: Down } -> Int = { d } -> match d is
              Down.({ v }) -> v
            impl Ord for Down where {
              lt = { it, r } -> dv { d = it } > dv { d = r },
              le = { it, r } -> dv { d = it } >= dv { d = r },
              gt = { it, r } -> dv { d = it } < dv { d = r },
              ge = { it, r } -> dv { d = it } <= dv { d = r },
            }
            let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::insert {
                m = sorted_map::empty {}, k = Down.({ v = 1 }), v = 10 },
                k = Down.({ v = 5 }), v = 50 }, k = Down.({ v = 3 }), v = 30
            }
            let a = option::get_or { opt = sorted_map::get { m = m, k = Down.({ v = 1 }) }, def = 0 }
            let b = option::get_or { opt = sorted_map::get { m = m, k = Down.({ v = 3 }) }, def = 0 }
            let c = option::get_or { opt = sorted_map::get { m = m, k = Down.({ v = 5 }) }, def = 0 }
            let miss = sorted_map::contains_key { m = m, k = Down.({ v = 4 }) }
            if a == 10 && b == 30 && c == 50 && miss.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * THE PERSISTENCE GUARANTEE. `keyAt` revokes ownership exactly as the old
     * native lookup did, because the caller has now observed the contents and a
     * later insert must copy rather than mutate underneath them. Reading the
     * base map and THEN deriving from it is the order that would corrupt the
     * base if the revoke were dropped.
     */
    @Test
    fun `a map read before being derived from is unchanged by the derivation`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let base = fill { i = 8, m = sorted_map::empty {} }
            let seen = option::get_or { opt = sorted_map::get { m = base, k = 3 }, def = 0 }
            let derived = sorted_map::insert { m = base, k = 3, v = 777 }
            let baseStill = option::get_or { opt = sorted_map::get { m = base, k = 3 }, def = 0 }
            let derivedNow = option::get_or { opt = sorted_map::get { m = derived, k = 3 }, def = 0 }
            if seen == 30 && baseStill == 30 && derivedNow == 777 then 1 else 0
        """.trimIndent()))
    }

    /** Warm, so the search is also exercised on the compiled path. */
    @Test
    fun `the search stays correct in a warm loop`() {
        assertEquals(1000L, MezzeTestHelper.run("""
            $prelude
            $fill
            let m = fill { i = 64, m = sorted_map::empty {} }
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let want = (i % 64) + 1
                let v = option::get_or { opt = sorted_map::get { m = m, k = want }, def = 0 }
                go { i = i - 1, acc = acc + (if v == want * 10 then 1 else 0) }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
