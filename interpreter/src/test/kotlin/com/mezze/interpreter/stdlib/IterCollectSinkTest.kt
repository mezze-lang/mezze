package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `from_iter` on every collection that was not reachable from an `Iter`.
 *
 * `Iter` has seven ways IN — `range`, `range_step`, `from_seq`, `from_list`,
 * `unfold`, `once`, `empty` — plus `.to_iter{}` on each of the seven types
 * implementing `Iterable`. It had exactly TWO ways out, `to_seq` and
 * `to_list`, so collecting into a Set, Map, Deque, SortedSet or SortedMap
 * meant spelling the intermediate by hand.
 *
 * ERGONOMICS, NOT SPEED, and the tests say nothing about speed. `to_seq` hands
 * its buffer to the sequence without copying it, so the intermediate was
 * already a wrapper around the buffer the sink had to fill — these are what you
 * would have written.
 *
 * THEY LIVE IN THE COLLECTION MODULES, not in `iter`, because every one of
 * those modules imports `iter` for its `Iterable` impl. Putting `to_set` in
 * `iter` would need `iter` to import `set`, which is a cycle. So the naming
 * follows the siblings already there — `from_seq`, `from_list`, `from_set`,
 * `from_map` — rather than inventing a direction.
 *
 * WHAT EACH TEST IS FOR. A size alone would pass on a sink that dropped or
 * duplicated the wrong things, so the element-collapsing ones go through
 * DUPLICATES: ten elements with three distinct values must be 3 in a Set and
 * 10 in a Deque, which no single off-by-one can satisfy both ways. The map
 * tests read a VALUE back, not just the size, because a key/value swap keeps
 * the size. And the empty chain is tested through each, since a sink that
 * mishandles zero elements is a different code path.
 */
class IterCollectSinkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    /** 0..9 keeping evens gives 0,2,4,6,8. */
    @Test
    fun `set from_iter collects a filtered chain`() {
        assertEquals(5L, run(
            """
            use std::collections::iter
            use std::collections::set
            set::size { s = set::from_iter {
              it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x % 2 == 0 }
            } }
            """
        ))
    }

    /**
     * TEN elements, THREE distinct. A Set must collapse to 3 and a Deque must
     * keep 10 — a sink that dropped or duplicated could not give both.
     */
    @Test
    fun `set collapses duplicates where deque keeps them`() {
        assertEquals("3|10", run(
            """
            use std::collections::iter
            use std::collections::set
            use std::collections::deque
            use std::int
            let dup = {} -> iter::map { it = iter::range { start = 0, end = 10 }, f = { x } -> x % 3 }
            let s = set::size { s = set::from_iter { it = dup {} } }
            let d = deque::size { d = deque::from_iter { it = dup {} } }
            "$(int::to_str { n = s })|$(int::to_str { n = d })"
            """
        ))
    }

    @Test
    fun `sorted_set from_iter collapses duplicates and keeps order`() {
        assertEquals("3|0", run(
            """
            use std::collections::iter
            use std::collections::sorted_set
            use std::int
            let s = sorted_set::from_iter {
              it = iter::map { it = iter::range { start = 0, end = 10 }, f = { x } -> x % 3 }
            }
            let n = sorted_set::size { s = s }
            let lo = match sorted_set::min { s = s } is
              Some { val } -> val
              None -> 0 - 1
            "$(int::to_str { n = n })|$(int::to_str { n = lo })"
            """
        ))
    }

    /**
     * A map wants `{ k, v }` elements. Reading a VALUE back matters: a swap of
     * key and value leaves the size unchanged, so `size` alone cannot see it.
     */
    @Test
    fun `map from_iter keys and values land the right way round`() {
        assertEquals("4|30", run(
            """
            use std::collections::iter
            use std::collections::map
            use std::int
            let m = map::from_iter {
              it = iter::map { it = iter::range { start = 0, end = 4 }, f = { x } -> { k = x, v = x * 10 } }
            }
            "$(int::to_str { n = map::size { m = m } })|$(int::to_str { n = map::get_or { m = m, k = 3, def = 0 - 1 } })"
            """
        ))
    }

    @Test
    fun `sorted_map from_iter keys and values land the right way round`() {
        assertEquals("4|30", run(
            """
            use std::collections::iter
            use std::collections::sorted_map
            use std::int
            let m = sorted_map::from_iter {
              it = iter::map { it = iter::range { start = 0, end = 4 }, f = { x } -> { k = x, v = x * 10 } }
            }
            let got = match sorted_map::get { m = m, k = 3 } is
              Some { val } -> val
              None -> 0 - 1
            "$(int::to_str { n = sorted_map::size { m = m } })|$(int::to_str { n = got })"
            """
        ))
    }

    /** A sink that mishandles zero elements is a separate path in each. */
    @Test
    fun `an empty chain collects to an empty collection`() {
        assertEquals("0|0|0", run(
            """
            use std::collections::iter
            use std::collections::set
            use std::collections::deque
            use std::collections::sorted_set
            use std::int
            let none = {} -> iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x > 100 }
            let a = set::size { s = set::from_iter { it = none {} } }
            let b = deque::size { d = deque::from_iter { it = none {} } }
            let c = sorted_set::size { s = sorted_set::from_iter { it = none {} } }
            "$(int::to_str { n = a })|$(int::to_str { n = b })|$(int::to_str { n = c })"
            """
        ))
    }

    /**
     * Through `unfold`, the universal source, rather than `range` — the whole
     * point of these sinks is reaching a collection from ANY iter, including a
     * user-defined producer. Squares of 0..4, deduplicated to 5.
     */
    @Test
    fun `an unfold source collects too`() {
        assertEquals(5L, run(
            """
            use std::collections::iter
            use std::collections::set
            set::size { s = set::from_iter {
              it = iter::unfold {
                state = 0,
                step = { s } -> if s >= 5 then Done else Yield { val = s * s, next = s + 1 },
              }
            } }
            """
        ))
    }
}
