package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `sorted_map::size`, `sorted_set::size`, `deque::size` and `deque::first` go
 * through `InlineMapSize` / `InlineDequeFirst`, and must still report the right
 * thing.
 *
 * WHY. `bench/cases/dsmore` put these on the record-ABI `HANDLERS` map — a
 * STRING-KEYED HashMap lookup per call — with no `BytecodeCompiler` special
 * case, which is the exact position `map::size` was in at 4 022 ps before its
 * inline operation took it to 429. Interleaved against HEAD, three readings
 * each at two sizes of n:
 *
 *     sorted_map::size   8 540 ->   416   20.5x   (parity with map::size)
 *     deque::size        8 865 ->   420   21.1x
 *     deque::first      12 343 -> 5 478    2.25x
 *
 * `deque::first` gains less, and that is expected rather than disappointing: it
 * also allocates a `MezzeVariant` for the `Some`, which cannot go away while
 * the signature returns `Option a`.
 *
 * CONTROLS, all flat: `sorted_map::get`, `sorted_map::contains_key`,
 * `sorted_map::insert` and `deque::get` stay on the HANDLERS path (+0.6% to
 * +1.3%) because they take a comparator and were not touched. The ordered
 * LOOKUPS are a separate and much larger problem — 29-60x the hash equivalents,
 * of which a measured 10-13x is `ElementComparator.compare` calling back into
 * guest code once per comparison. Nothing here addresses that.
 *
 * WHAT THESE GUARD. `InlineMapSize` now carries FIVE specializations — map,
 * set, sorted map, sorted set, deque — selected on the receiver's runtime
 * class. A receiver reaching the wrong one would report another container's
 * size, so the tests below put several container kinds in one program and check
 * each reports its own. The size progression is exercised because these
 * structures change representation as they grow.
 */
class OrderedSizeInlineTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::sorted_map
        use std::collections::sorted_set
        use std::collections::deque
        use std::option
    """.trimIndent()

    @Test
    fun `sorted_map size counts distinct keys and ignores repeats`() {
        assertEquals(2L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert {
                m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = 1 }, k = 2, v = 2 },
                k = 1, v = 99 }
            sorted_map::size { m = m }
        """.trimIndent()))
    }

    @Test
    fun `sorted_map size across the growth progression`() {
        // 0 + 1 + 2 + 64, each built independently.
        assertEquals(67L, MezzeTestHelper.run("""
            $prelude
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = sorted_map::insert { m = m, k = i, v = i } }
            let a = sorted_map::size { m = sorted_map::empty {} }
            let b = sorted_map::size { m = fill { i = 1, m = sorted_map::empty {} } }
            let c = sorted_map::size { m = fill { i = 2, m = sorted_map::empty {} } }
            let d = sorted_map::size { m = fill { i = 64, m = sorted_map::empty {} } }
            a + b + c + d
        """.trimIndent()))
    }

    @Test
    fun `sorted_set size counts distinct elements`() {
        assertEquals(3L, MezzeTestHelper.run("""
            $prelude
            let s = sorted_set::add { s = sorted_set::add {
                s = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 5 }, x = 7 },
                x = 5 }, x = 9 }
            sorted_set::size { s = s }
        """.trimIndent()))
    }

    @Test
    fun `deque size counts both ends`() {
        assertEquals(5L, MezzeTestHelper.run("""
            $prelude
            let d = deque::push_front { d = deque::push_back { d = deque::push_back {
                d = deque::push_front { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 },
                x = 3 }, x = 4 }, x = 5 }
            deque::size { d = d }
        """.trimIndent()))
    }

    @Test
    fun `deque size shrinks after a pop`() {
        assertEquals(2L, MezzeTestHelper.run("""
            $prelude
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            match deque::pop_front { d = d } is
              Some { val } -> deque::size { d = val.tail }
              None -> 0 - 1
        """.trimIndent()))
    }

    /**
     * THE SPECIALIZATION-CONFUSION TEST. Four container kinds of DIFFERENT
     * sizes in one program, each asked for its own size. A receiver reaching
     * another kind's specialization would report the wrong number, and the
     * sizes are chosen so that every wrong answer changes the total.
     *
     * sorted map 1, sorted set 2, deque 3 -> 1 + 20 + 300 = 321.
     */
    @Test
    fun `each container kind reports its own size`() {
        assertEquals(321L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = 1 }
            let s = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            sorted_map::size { m = m } + (sorted_set::size { s = s } * 10) + (deque::size { d = d } * 100)
        """.trimIndent()))
    }

    @Test
    fun `deque first returns the front element, not the back`() {
        assertEquals(7L, MezzeTestHelper.run("""
            $prelude
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 7 }, x = 9 }
            option::get_or { opt = deque::first { d = d }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `deque first follows a push_front`() {
        assertEquals(3L, MezzeTestHelper.run("""
            $prelude
            let d = deque::push_front { d = deque::push_back { d = deque::empty {}, x = 7 }, x = 3 }
            option::get_or { opt = deque::first { d = d }, def = 0 }
        """.trimIndent()))
    }

    @Test
    fun `deque first on an empty deque is None`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            match deque::first { d = deque::empty {} } is
              Some { val } -> 0 - val
              None -> 1
        """.trimIndent()))
    }

    /** Warm, so the figures are taken on the compiled path as well. */
    @Test
    fun `the inline operations stay correct in a warm loop`() {
        // per iteration: (1 + 1) + (2 + 4) = 8, so 1000 x 8 = 8 000.
        assertEquals(8000L, MezzeTestHelper.run("""
            $prelude
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = 1 }
            let s = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 4 }, x = 5 }
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let one = sorted_map::size { m = m } + sorted_set::size { s = s }
                let two = deque::size { d = d } + option::get_or { opt = deque::first { d = d }, def = 0 }
                go { i = i - 1, acc = acc + one + two }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
