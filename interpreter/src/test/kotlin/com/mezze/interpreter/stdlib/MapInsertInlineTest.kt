package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `map::insert` and `set::insert` go through `InlineMapInsert` /
 * `InlineSetInsert`, and must still build the right structure.
 *
 * WHY. Both had a scalar `NativeOp` registration and no `BytecodeCompiler`
 * special case — the exact position `contains_key` was in before its inline
 * operation was worth 1.46x. Interleaved against the previous commit's build,
 * with `contains_key` and `size` as controls:
 *
 *     map::insert along the owner chain  16 189 -> 13 535   1.20x
 *     set::insert along the owner chain   7 072 ->  6 292   1.12x
 *     map::insert from a SHARED base     unchanged
 *     map::contains_key, map::size       flat
 *
 * `set::insert` needed five interleaved pairs to claim: two pairs read 1.05x,
 * which is inside this bench's resolution, but across five every `ins` reading
 * fell below every `head` reading with no overlap, which is a separation rather
 * than noise.
 *
 * WHAT THIS DID NOT TOUCH, AND WHAT SINCE FIXED IT. Inserting from a base map
 * somebody else still held cost 5.41 µs at 4 000 keys, because
 * `LongHashMapStorage.putLong` cloned both arrays when `ownerToken[0] != this`.
 * That was a datastructures problem and not a dispatch one, and it is now gone:
 * that storage was replaced by `LongTrieMapStorage`, a persistent trie, which
 * also closed BUG 38. The same row reads 63 164 ps at 4 000 keys — 85.6x — and
 * no longer depends on who holds the base. The numbers above still stand; they
 * were taken against the storage of the day.
 *
 * WHAT THESE GUARD. Three operands have to arrive in the right order: a
 * swapped key and value would store the key under the value and every lookup
 * would miss, which the round-trips below would catch. The owner/branch split
 * is also exercised deliberately — a base kept live while a derived map is
 * built — because that is the path where the storage clones rather than mutates.
 */
class MapInsertInlineTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::map
        use std::collections::set
        use std::option
    """.trimIndent()

    @Test
    fun `insert then get round-trips the value, not the key`() {
        assertEquals(42L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::empty {}, k = 7, v = 42 }
            option::get_or { opt = map::get { m = m, k = 7 }, def = 0 }
        """.trimIndent()))
    }

    /** If key and value were swapped, looking up the VALUE would succeed. */
    @Test
    fun `the value is not usable as a key`() {
        assertEquals(false, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::empty {}, k = 7, v = 42 }
            map::contains_key { m = m, k = 42 }
        """.trimIndent()))
    }

    @Test
    fun `inserting an existing key replaces the value and keeps the size`() {
        assertEquals(99L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 1, v = 99 }
            let sz = map::size { m = m }
            let v = option::get_or { opt = map::get { m = m, k = 1 }, def = 0 }
            if sz == 1 then v else 0 - 1
        """.trimIndent()))
    }

    @Test
    fun `a chain of inserts builds every entry across the storage progression`() {
        // 64 entries crosses Empty, SingleEntry, SmallFlat and LongHash; each
        // key i holds i*10, so the sum is 10*64*65/2 = 20800.
        assertEquals(20800L, MezzeTestHelper.run("""
            $prelude
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let big = fill { i = 64, m = map::empty {} }
            let sum = { i, acc } -> if i <= 0 then acc
              else do
                let v = option::get_or { opt = map::get { m = big, k = i }, def = 0 }
                sum { i = i - 1, acc = acc + v }
            sum { i = 64, acc = 0 }
        """.trimIndent()))
    }

    /**
     * THE OWNER/BRANCH SPLIT. `base` is kept live while `derived` is built from
     * it, so the storage clones rather than mutates in place. Both must then
     * report their own contents: base has no key 4, derived does.
     */
    @Test
    fun `a base map is unchanged by a map derived from it`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let base = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }
            let derived = map::insert { m = base, k = 4, v = 4 }
            let baseHas4 = map::contains_key { m = base, k = 4 }
            let derivedHas4 = map::contains_key { m = derived, k = 4 }
            if baseHas4.not {} && derivedHas4 then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `two derived maps from one base do not see each other`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let base = map::insert { m = map::empty {}, k = 1, v = 1 }
            let a = map::insert { m = base, k = 2, v = 2 }
            let bb = map::insert { m = base, k = 3, v = 3 }
            let aHas3 = map::contains_key { m = a, k = 3 }
            let bHas2 = map::contains_key { m = bb, k = 2 }
            if aHas3.not {} && bHas2.not {} then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `Str keys insert and read back`() {
        assertEquals(5L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 2 }, k = "b", v = 3 }
            let a = option::get_or { opt = map::get { m = m, k = "a" }, def = 0 }
            let bb = option::get_or { opt = map::get { m = m, k = "b" }, def = 0 }
            a + bb
        """.trimIndent()))
    }

    @Test
    fun `set insert adds a new element and keeps a present one once`() {
        assertEquals(32L, MezzeTestHelper.run("""
            $prelude
            let s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let grown = set::insert { s = s, x = 3 }
            let again = set::insert { s = s, x = 2 }
            set::size { s = grown } * 10 + set::size { s = again }
        """.trimIndent()))
    }

    /** Warm, so the figures are taken on the compiled path as well. */
    @Test
    fun `inserts stay correct in a loop`() {
        assertEquals(1000L, MezzeTestHelper.run("""
            $prelude
            let base = map::insert { m = map::empty {}, k = 1, v = 1 }
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let m2 = map::insert { m = base, k = 2, v = i }
                let v = option::get_or { opt = map::get { m = m2, k = 2 }, def = 0 }
                go { i = i - 1, acc = acc + (if v == i then 1 else 0) }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
