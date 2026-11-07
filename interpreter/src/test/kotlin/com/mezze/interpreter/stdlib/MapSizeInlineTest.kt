package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `map::size` and `set::size` go through `InlineMapSize`, and must still be
 * right for every storage shape.
 *
 * WHY THE PATH CHANGED. `str::size` cost 416 ps and `map::size` 4 519 — eleven
 * times as much to read a stored count. Both are registered as identical scalar
 * `NativeOp`s and neither crosses a `@TruffleBoundary`, so the ABI was not the
 * difference; what `str::size` had was a dedicated PE-transparent operation
 * emitted by name from `BytecodeCompiler`. `map::size` now has its peer, and
 * `MezzeMap` caches the count so the inlined body is a field read rather than a
 * call through the ten-implementation `MapStorage` interface. Interleaved
 * against the unmodified build: 4 022 -> 429 ps, 9.4x, reaching parity with
 * `str::size`, with `map::get` and `map::contains_key` flat within 2.5% as
 * controls.
 *
 * WHAT THESE GUARD. Caching a count is only sound because every
 * `MapStorage.size()` returns a stored field, a constant, or `keys.length` over
 * a FINAL array — checked across all ten implementations. A map passes through
 * several storage shapes as it grows (Empty -> SingleEntry -> SmallFlat ->
 * LongHash), so the cases below walk that progression and then mutate, which is
 * where a stale count would show.
 */
class MapSizeInlineTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::map
        use std::collections::set
    """.trimIndent()

    @Test
    fun `size of an empty map is zero`() {
        assertEquals(0L, MezzeTestHelper.run("""
            $prelude
            map::size { m = map::empty {} }
        """.trimIndent()))
    }

    @Test
    fun `size tracks the storage progression as the map grows`() {
        // 0 -> 1 -> 2 -> 8 -> 64 crosses Empty, SingleEntry, SmallFlat and
        // LongHash, summing the size at each step: 0+1+2+8+64 = 75.
        assertEquals(75L, MezzeTestHelper.run("""
            $prelude
            let fill = { i, m } -> if i <= 0 then m
              else fill { i = i - 1, m = map::insert { m = m, k = i, v = i } }
            let sz = { n } -> map::size { m = fill { i = n, m = map::empty {} } }
            sz { n = 0 } + sz { n = 1 } + sz { n = 2 } + sz { n = 8 } + sz { n = 64 }
        """.trimIndent()))
    }

    @Test
    fun `inserting a NEW key raises the size`() {
        assertEquals(4L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            map::size { m = map::insert { m = m, k = 4, v = 4 } }
        """.trimIndent()))
    }

    @Test
    fun `inserting an EXISTING key leaves the size alone`() {
        assertEquals(3L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            map::size { m = map::insert { m = m, k = 2, v = 99 } }
        """.trimIndent()))
    }

    /**
     * THE STALE-COUNT CASE. The base map is kept live while a derived one is
     * built from it, so both wrappers exist at once and each must report its
     * own count: 3 and 4, not 4 and 4.
     */
    @Test
    fun `a base map and a map derived from it each report their own size`() {
        assertEquals(34L, MezzeTestHelper.run("""
            $prelude
            let base = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            let derived = map::insert { m = base, k = 4, v = 4 }
            map::size { m = base } * 10 + map::size { m = derived }
        """.trimIndent()))
    }

    @Test
    fun `remove lowers the size`() {
        assertEquals(2L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            map::size { m = map::remove { m = m, k = 2 } }
        """.trimIndent()))
    }

    @Test
    fun `removing an absent key leaves the size alone`() {
        assertEquals(3L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            map::size { m = map::remove { m = m, k = 99 } }
        """.trimIndent()))
    }

    @Test
    fun `Str keys work too, which is a different storage`() {
        assertEquals(2L, MezzeTestHelper.run("""
            $prelude
            map::size { m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 } }
        """.trimIndent()))
    }

    @Test
    fun `is_empty rides on size and is still right`() {
        assertEquals(true, MezzeTestHelper.run("""
            $prelude
            let full = map::insert { m = map::empty {}, k = 1, v = 1 }
            let emptyIsEmpty = map::is_empty { m = map::empty {} }
            let fullIsEmpty = map::is_empty { m = full }
            emptyIsEmpty && fullIsEmpty.not {}
        """.trimIndent()))
    }

    @Test
    fun `set size follows the same path`() {
        assertEquals(12L, MezzeTestHelper.run("""
            $prelude
            let s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            set::size { s = s } * 10 + set::size { s = set::insert { s = s, x = 3 } } - 11
        """.trimIndent()))
    }

    /** Warm, so the figure is taken on the compiled path as well. */
    @Test
    fun `size is right in a loop`() {
        assertEquals(3000L, MezzeTestHelper.run("""
            $prelude
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 1, v = 1 }, k = 2, v = 2 }, k = 3, v = 3 }
            let go = { i, acc } -> if i <= 0 then acc
              else go { i = i - 1, acc = acc + map::size { m = m } }
            go { i = 1000, acc = 0 }
        """.trimIndent()))
    }
}
