package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `sorted_set::contains` runs its binary search in GUEST code now, and must
 * still agree with the native search it replaced.
 *
 * WHY. The same reason as `sorted_map`, and the same fix: a SortedSet is a
 * sorted array, the search used to run in host code behind a
 * `@TruffleBoundary`, and so every comparison called back out into guest code —
 * which Graal can never inline into host code. Moving the search here makes the
 * comparison an ordinary `<` that inlines and reduces the native's whole job to
 * an array read.
 *
 *     sorted_set::contains   309 600 -> 15 060   20.6x
 *     sorted_map::get  (ctrl)  17 190   17 187   exactly flat
 *     sorted_map::contains_key 15 058   15 085   flat
 *     sorted_set::add  (ctrl) 307 609  305 908   flat — still native
 *
 * The two `sorted_map` rows are the controls that prove the baseline was built
 * from the right commit: they were changed one commit earlier and read
 * identically in both builds. `sorted_set::add` still carries the old
 * comparator-closure path and did not move.
 *
 * WHAT THESE GUARD. The edges of a hand-written binary search, and the SET's
 * own probe — `InlineSortedElementAt` reads `MezzeSortedSet.elements`, and a
 * receiver reaching a sorted MAP's specialization instead would read keys from
 * the wrong array. The ordering direction is pinned by a user-defined
 * descending `Ord`, which also shows the win is not a primitive-only special
 * case.
 *
 * COVERAGE, as the sentinels actually report it rather than as first guessed.
 * A sentinel making the search never find fails **8 of 9** — only
 * `an empty set contains nothing` survives, which no such wrongness can break.
 * Reversing the comparison direction fails **6**; the three survivors are the
 * empty set (no comparison happens), the single-element set (`mid` lands on the
 * element, so direction cannot matter), and the map/set test, whose set holds
 * just two elements that happen to resolve the same way reversed. Union
 * **8 of 9**.
 */
class SortedSetGuestSearchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::sorted_set
    """.trimIndent()

    private val fill = """
        let fill = { i, s } -> if i <= 0 then s
          else fill { i = i - 1, s = sorted_set::add { s = s, x = i } }
    """.trimIndent()

    @Test
    fun `a present element is found`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let s = fill { i = 9, s = sorted_set::empty {} }
            if sorted_set::contains { s = s, x = 7 } then 1 else 0
        """.trimIndent()))
    }

    /**
     * EVERY element of a 64-element set, each tested on its own, plus the 64
     * absent values just above the range. 64 hits and 0 false positives.
     */
    @Test
    fun `every element is found and nothing outside the range is`() {
        assertEquals(64L, MezzeTestHelper.run("""
            $prelude
            $fill
            let s = fill { i = 64, s = sorted_set::empty {} }
            let count = { i, acc } -> if i <= 0 then acc
              else do
                let inside = if sorted_set::contains { s = s, x = i } then 1 else 0
                let outside = if sorted_set::contains { s = s, x = i + 64 } then 1 else 0
                count { i = i - 1, acc = acc + inside - outside }
            count { i = 64, acc = 0 }
        """.trimIndent()))
    }

    /** The empty set: `hi` starts at -1, so the loop must not run at all. */
    @Test
    fun `an empty set contains nothing`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            if sorted_set::contains { s = sorted_set::empty {}, x = 1 } then 0 else 1
        """.trimIndent()))
    }

    @Test
    fun `a single-element set finds its element and rejects others`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let s = sorted_set::add { s = sorted_set::empty {}, x = 5 }
            let hit = sorted_set::contains { s = s, x = 5 }
            let below = sorted_set::contains { s = s, x = 4 }
            let above = sorted_set::contains { s = s, x = 6 }
            if hit && below.not {} && above.not {} then 1 else 0
        """.trimIndent()))
    }

    /** Elements 2,4,6,8; probes below, between, above, and at both ends. */
    @Test
    fun `boundaries and gaps resolve correctly`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let s = sorted_set::add { s = sorted_set::add { s = sorted_set::add {
                s = sorted_set::add { s = sorted_set::empty {}, x = 2 }, x = 4 }, x = 6 }, x = 8 }
            let first = sorted_set::contains { s = s, x = 2 }
            let last = sorted_set::contains { s = s, x = 8 }
            let below = sorted_set::contains { s = s, x = 1 }
            let gap1 = sorted_set::contains { s = s, x = 3 }
            let gap2 = sorted_set::contains { s = s, x = 5 }
            let above = sorted_set::contains { s = s, x = 9 }
            if first && last && below.not {} &&
               gap1.not {} && gap2.not {} && above.not {} then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `Str elements search by their own ordering`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let s = sorted_set::add { s = sorted_set::add { s = sorted_set::add {
                s = sorted_set::empty {}, x = "banana" }, x = "apple" }, x = "cherry" }
            let a = sorted_set::contains { s = s, x = "apple" }
            let c = sorted_set::contains { s = s, x = "cherry" }
            let miss = sorted_set::contains { s = s, x = "durian" }
            if a && c && miss.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * A DESCENDING `Ord`, which pins the comparison DIRECTION — a reversed
     * comparison still terminates and still finds elements under a symmetric
     * ordering, so only an order that is not the natural one tells them apart.
     * It also proves this is not a primitive-only special case: the comparator
     * is user code, inlined like any other.
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
            let s = sorted_set::add { s = sorted_set::add { s = sorted_set::add {
                s = sorted_set::empty {}, x = Down.({ v = 1 }) }, x = Down.({ v = 5 }) },
                x = Down.({ v = 3 }) }
            let a = sorted_set::contains { s = s, x = Down.({ v = 1 }) }
            let b = sorted_set::contains { s = s, x = Down.({ v = 3 }) }
            let c = sorted_set::contains { s = s, x = Down.({ v = 5 }) }
            let miss = sorted_set::contains { s = s, x = Down.({ v = 4 }) }
            if a && b && c && miss.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * THE SPECIALIZATION-CONFUSION TEST. A sorted MAP and a sorted SET live in
     * the same program and both get searched. `InlineSortedElementAt` reads the
     * Set's `elements` while `InlineSortedKeyAt` reads the Map's `keys`; a
     * receiver reaching the wrong one would probe the wrong array. The contents
     * are chosen to disagree, so a crossed probe cannot accidentally be right.
     */
    @Test
    fun `a sorted map and a sorted set in one program search their own contents`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::insert {
                m = sorted_map::empty {}, k = 100, v = 1 }, k = 200, v = 2 }
            let s = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 7 }, x = 9 }
            let mHas100 = sorted_map::contains_key { m = m, k = 100 }
            let mHas7 = sorted_map::contains_key { m = m, k = 7 }
            let sHas7 = sorted_set::contains { s = s, x = 7 }
            let sHas100 = sorted_set::contains { s = s, x = 100 }
            if mHas100 && sHas7 && mHas7.not {} && sHas100.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * ADDING AN ELEMENT ALREADY PRESENT is now decided entirely in guest code:
     * `add` asks `find_index` first and returns the set untouched, never
     * reaching the native. 20.7x on `dsmore` row 6 (306 932 -> 14 786), which
     * is the same cost as `contains` because it is the same search.
     *
     * The risk the short-circuit creates is answering "present" for something
     * absent and silently dropping the insertion, so these check the size and
     * the membership on both sides of the branch.
     */
    @Test
    fun `adding a present element leaves the set and its size alone`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let s = fill { i = 40, s = sorted_set::empty {} }
            let again = sorted_set::add { s = s, x = 25 }
            if sorted_set::size { s = again } == 40 && sorted_set::contains { s = again, x = 25 }
            then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `adding an absent element still grows the set`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let s = fill { i = 40, s = sorted_set::empty {} }
            let grown = sorted_set::add { s = s, x = 999 }
            if sorted_set::size { s = grown } == 41 &&
               sorted_set::contains { s = grown, x = 999 } &&
               sorted_set::contains { s = s, x = 999 }.not {} then 1 else 0
        """.trimIndent()))
    }

    /**
     * Interleaved present and absent adds, so a short-circuit that answered
     * the wrong way round would show up as a wrong SIZE rather than only a
     * wrong membership. 40 present re-adds change nothing; 40 absent ones add
     * 40.
     */
    @Test
    fun `interleaved present and absent adds end at the right size`() {
        assertEquals(80L, MezzeTestHelper.run("""
            $prelude
            $fill
            let go = { i, s } -> if i <= 0 then s
              else go { i = i - 1, s = sorted_set::add { s = sorted_set::add { s = s, x = i }, x = i + 1000 } }
            let s = go { i = 40, s = fill { i = 40, s = sorted_set::empty {} } }
            sorted_set::size { s = s }
        """.trimIndent()))
    }

    /** Warm, so the search is also exercised on the compiled path. */
    @Test
    fun `the search stays correct in a warm loop`() {
        assertEquals(1000L, MezzeTestHelper.run("""
            $prelude
            $fill
            let s = fill { i = 64, s = sorted_set::empty {} }
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let want = (i % 64) + 1
                let ok = if sorted_set::contains { s = s, x = want } then 1 else 0
                go { i = i - 1, acc = acc + ok }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
