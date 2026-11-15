package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `map::get`, `map::contains_key` and `set::contains` go through
 * `InlineMapGet` / `InlineMapContainsKey`, and must still answer correctly for
 * every storage shape.
 *
 * WHY THE PATH CHANGED, and in what order. These rows sat at 13.2 / 7.6 / 6.5
 * ns against `map::size`'s 429 ps. The first attempt was to drop the
 * `@TruffleBoundary` from `MapNatives.containsKey`, since `size`/`isEmpty`
 * carry none — interleaved, that read 7 605 against 7 444, no gain. The
 * boundary was not the cost: the GENERIC NATIVE DISPATCH in front of it was.
 * With that removed by these operations the rows measure
 *
 *     map::get (hit)    13 253 -> 10 827   1.22x
 *     map::get (miss)    8 388 ->  6 545   1.28x
 *     map::contains_key  7 550 ->  5 166   1.46x
 *     set::contains      6 473 ->  4 783   1.35x
 *     map::size (ctrl)      447 ->    479  flat
 *
 * and re-testing the boundary removal ON TOP of them gave 5 189 against
 * 5 151 — 0.7%, so the boundary is genuinely not the cost and stays, since
 * dropping one for nothing only adds SVM risk.
 *
 * WHAT THESE GUARD. The operations call the same natives, so nothing inside
 * them changed; what changed is which node reaches them, and a wrong operand
 * order or a missed field name would send the key where the map goes. `get`
 * also has to keep telling an absent key from a present one, which it does with
 * a second probe — so `None` versus `Some` across the storage progression is
 * the case that matters.
 */
class MapLookupInlineTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::map
        use std::collections::set
        use std::option
    """.trimIndent()

    private val m3 = """
        let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 10 }, k = 2, v = 20 }, k = 3, v = 30 }
    """.trimIndent()

    @Test
    fun `contains_key is true for a present key`() {
        assertEquals(true, MezzeTestHelper.run("$prelude\n$m3\nmap::contains_key { m = m, k = 2 }"))
    }

    @Test
    fun `contains_key is false for an absent key`() {
        assertEquals(false, MezzeTestHelper.run("$prelude\n$m3\nmap::contains_key { m = m, k = 99 }"))
    }

    /** The operands must not be swapped: a key where the map goes answers nothing. */
    @Test
    fun `get returns the value stored under the key, not some other key's`() {
        assertEquals(20L, MezzeTestHelper.run("""
            $prelude
            $m3
            option::get_or { opt = map::get { m = m, k = 2 }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `get on an absent key is None`() {
        assertEquals(-1L, MezzeTestHelper.run("""
            $prelude
            $m3
            option::get_or { opt = map::get { m = m, k = 99 }, def = 0 - 1 }
        """.trimIndent()))
    }

    @Test
    fun `get distinguishes every key across the storage progression`() {
        // 0/1/2/8/64 entries crosses Empty, SingleEntry, SmallFlat, LongHash.
        // Each key i maps to i*10, so the sum over 1..64 is 10*64*65/2 = 20800.
        assertEquals(20800L, MezzeTestHelper.run("""
            $prelude
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let big = fill { i = 64, m = map::empty {} }
            let sum = { i, acc } -> if i <= 0 then acc
              else sum { i = i - 1, acc = acc + option::get_or { opt = map::get { m = big, k = i }, def = 0 } }
            sum { i = 64, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `absent keys are None across the storage progression`() {
        assertEquals(0L, MezzeTestHelper.run("""
            $prelude
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let big = fill { i = 64, m = map::empty {} }
            let sum = { i, acc } -> if i <= 0 then acc
              else sum { i = i - 1, acc = acc + option::get_or { opt = map::get { m = big, k = 0 - i }, def = 0 } }
            sum { i = 64, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `Str keys use a different storage and still answer`() {
        assertEquals(7L, MezzeTestHelper.run("""
            $prelude
            let sm = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 3 }, k = "b", v = 4 }
            let a = option::get_or { opt = map::get { m = sm, k = "a" }, def = 0 }
            let bb = option::get_or { opt = map::get { m = sm, k = "b" }, def = 0 }
            let zz = option::get_or { opt = map::get { m = sm, k = "zz" }, def = 0 }
            a + bb + zz
        """.trimIndent()))
    }

    @Test
    fun `set contains is true for present and false for absent`() {
        assertEquals(true, MezzeTestHelper.run("""
            $prelude
            let s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let hit = set::contains { s = s, x = 2 }
            let miss = set::contains { s = s, x = 9 }
            hit && miss.not {}
        """.trimIndent()))
    }

    /** Warm, so the figures are taken on the compiled path too. */
    @Test
    fun `lookups stay correct in a loop`() {
        assertEquals(60000L, MezzeTestHelper.run("""
            $prelude
            $m3
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let v = option::get_or { opt = map::get { m = m, k = 2 }, def = 0 }
                let c = if map::contains_key { m = m, k = 3 } then 40 else 0
                go { i = i - 1, acc = acc + v + c }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
