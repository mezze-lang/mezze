package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Replacing a value in a map must not change the map it was derived FROM.
 *
 * WHY THIS IS NOT OBVIOUS. Both map implementations use an owner token: a
 * structure that still "owns" its arrays may mutate them in place and hand the
 * token to the derived structure, because owning is supposed to mean nobody
 * else can observe the contents. For an INSERT of a new key that is safe even
 * if the base is still referenced — the base's own `size`/`length` bounds what
 * it can see, so a write past it is invisible. A REPLACE is different: it
 * writes INSIDE the base's visible extent, so if the base is still live and
 * readable it would observe the new value.
 *
 * These tests state the required behaviour for both implementations rather than
 * assuming either. They were written while making a sorted-map replace take the
 * owner fast path, precisely because that change is the one that could break it
 * — and it did, which is how the Map's own version of the bug was found.
 *
 * STATUS: all twelve pass. The three that were disabled against BUG 38 — the
 * Map replace, the Map new-key insert and the Set insert — pass since the
 * `long`-keyed Map and Set became persistent tries. The other hash storages
 * (String, Double, generic Object) still carry the in-place write and therefore
 * still carry the bug; these tests use Int keys and so never reach them.
 */
class PersistentReplaceSoundnessTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `hash map - a base is unchanged by a replace derived from it`() {
        assertEquals(10L, MezzeTestHelper.run("""
            use std::collections::map
            use std::option
            let base = map::insert { m = map::empty {}, k = 1, v = 10 }
            let derived = map::insert { m = base, k = 1, v = 20 }
            option::get_or { opt = map::get { m = base, k = 1 }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `hash map - the derived map does have the new value`() {
        assertEquals(20L, MezzeTestHelper.run("""
            use std::collections::map
            use std::option
            let base = map::insert { m = map::empty {}, k = 1, v = 10 }
            let derived = map::insert { m = base, k = 1, v = 20 }
            option::get_or { opt = map::get { m = derived, k = 1 }, def = 0 }
        """.trimIndent()))
    }

    /**
     * THE SAME QUESTION AT A SIZE THAT REACHES THE ARRAY STORAGE. This was
     * BUG 38, and it is the test that found it.
     *
     * A one-entry hash map is a SingleEntry storage, which allocates per put and
     * so cannot mutate anything in place; the test above would pass whatever the
     * array storage does, which is why it proves nothing on its own. 64 entries
     * crosses into it, and the open-addressed table that used to live there
     * wrote `workValues[idx]` directly while it owned the arrays and handed the
     * token on — so the base observed a value the program never wrote to it.
     *
     * It passes now: `long`-keyed Maps and Sets are persistent tries
     * (`LongTrieMapStorage`, `LongTrieSetStorage`) with structural sharing, so
     * no version can observe another's write. See `known-bugs.md` BUG 38 for
     * the repro, why the ownership contract never covered READS of the old map,
     * and the diff-chain fix that was correct and had to be discarded because
     * it retained every later version.
     */
    @Test
    fun `hash map - a 64-entry base is unchanged by a replace derived from it`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::map
            use std::option
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 64, m = map::empty {} }
            let derived = map::insert { m = base, k = 30, v = 777 }
            let b = option::get_or { opt = map::get { m = base, k = 30 }, def = 0 }
            let d = option::get_or { opt = map::get { m = derived, k = 30 }, def = 0 }
            if b == 300 && d == 777 then 1 else 0
        """.trimIndent()))
    }

    /**
     * BLAST-RADIUS PROBE 1 — a NEW key, not a replace.
     *
     * The BUG 38 entry reasoned that inserting a new key is safe even with an
     * in-place write, because the base's own `size` bounds what it can see.
     * That is true of a sorted ARRAY, whose search never looks past `length`.
     * It is NOT obviously true of a hash map: `getLong` probes the shared
     * `keys` array by hash until it meets `EMPTY_KEY`, and an in-place insert
     * writes into the first empty slot of exactly that probe sequence.
     */
    @Test
    fun `hash map - a 64-entry base does not acquire a key inserted into a derived map`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::map
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 64, m = map::empty {} }
            let derived = map::insert { m = base, k = 999, v = 1 }
            let baseHas = map::contains_key { m = base, k = 999 }
            let derivedHas = map::contains_key { m = derived, k = 999 }
            if baseHas.not {} && derivedHas then 1 else 0
        """.trimIndent()))
    }

    /** BLAST-RADIUS PROBE 2 — the base's reported size must not move either. */
    @Test
    fun `hash map - a 64-entry base keeps its size when a derived map grows`() {
        assertEquals(64L, MezzeTestHelper.run("""
            use std::collections::map
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 64, m = map::empty {} }
            let derived = map::insert { m = base, k = 999, v = 1 }
            map::size { m = base }
        """.trimIndent()))
    }

    /** BLAST-RADIUS PROBE 3 — the Set, which has the same storage family. */
    @Test
    fun `hash set - a 64-element base does not acquire an element added to a derived set`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::set
            let fill = { i, s } -> if i <= 0 then s
              else fill { i = i - 1, s = set::insert { s = s, x = i } }
            let base = fill { i = 64, s = set::empty {} }
            let derived = set::insert { s = base, x = 999 }
            let baseHas = set::contains { s = base, x = 999 }
            let derivedHas = set::contains { s = derived, x = 999 }
            if baseHas.not {} && derivedHas then 1 else 0
        """.trimIndent()))
    }

    /** BLAST-RADIUS PROBE 4 — removal, the other mutating direction. */
    @Test
    fun `hash map - a 64-entry base keeps a key removed from a derived map`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::map
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 64, m = map::empty {} }
            let derived = map::remove { m = base, k = 30 }
            let baseHas = map::contains_key { m = base, k = 30 }
            let derivedHas = map::contains_key { m = derived, k = 30 }
            if baseHas && derivedHas.not {} then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `sorted map - a base is unchanged by a replace derived from it`() {
        assertEquals(10L, MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::option
            let base = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = 10 }
            let derived = sorted_map::insert { m = base, k = 1, v = 20 }
            option::get_or { opt = sorted_map::get { m = base, k = 1 }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `sorted map - the derived map does have the new value`() {
        assertEquals(20L, MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::option
            let base = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = 10 }
            let derived = sorted_map::insert { m = base, k = 1, v = 20 }
            option::get_or { opt = sorted_map::get { m = derived, k = 1 }, def = 0 }
        """.trimIndent()))
    }

    /**
     * A longer base so the replace lands in the middle of a populated array,
     * and the base is read AFTER the derivation — the order that exposes an
     * in-place write.
     */
    @Test
    fun `sorted map - a mid-array replace leaves the base intact`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::option
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = sorted_map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 8, m = sorted_map::empty {} }
            let derived = sorted_map::insert { m = base, k = 4, v = 777 }
            let b = option::get_or { opt = sorted_map::get { m = base, k = 4 }, def = 0 }
            let d = option::get_or { opt = sorted_map::get { m = derived, k = 4 }, def = 0 }
            if b == 40 && d == 777 then 1 else 0
        """.trimIndent()))
    }

    /**
     * TWO maps derived from ONE base, neither seeing the other's value, and the
     * base seeing neither. This is the shape the owner token is supposed to
     * handle by copying on the second derivation.
     */
    @Test
    fun `sorted map - two replaces from one base are independent`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::option
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = sorted_map::insert { m = m, k = i, v = i * 10 } }
            let base = fill { i = 8, m = sorted_map::empty {} }
            let a = sorted_map::insert { m = base, k = 4, v = 111 }
            let bb = sorted_map::insert { m = base, k = 4, v = 222 }
            let vBase = option::get_or { opt = sorted_map::get { m = base, k = 4 }, def = 0 }
            let vA = option::get_or { opt = sorted_map::get { m = a, k = 4 }, def = 0 }
            let vB = option::get_or { opt = sorted_map::get { m = bb, k = 4 }, def = 0 }
            if vBase == 40 && vA == 111 && vB == 222 then 1 else 0
        """.trimIndent()))
    }

    /**
     * A chain of replaces, which is the case the owner fast path exists for,
     * with every intermediate discarded. Only the last value must survive, and
     * the count must be right.
     */
    @Test
    fun `sorted map - a chain of replaces ends with the last value`() {
        assertEquals(1L, MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::option
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = sorted_map::insert { m = m, k = i, v = i * 10 } }
            let go = { i, m } -> if i <= 0 then m
              else go { i = i - 1, m = sorted_map::insert { m = m, k = 4, v = i } }
            let final = go { i = 50, m = fill { i = 8, m = sorted_map::empty {} } }
            let v = option::get_or { opt = sorted_map::get { m = final, k = 4 }, def = 0 }
            if v == 1 && sorted_map::size { m = final } == 8 then 1 else 0
        """.trimIndent()))
    }
}
