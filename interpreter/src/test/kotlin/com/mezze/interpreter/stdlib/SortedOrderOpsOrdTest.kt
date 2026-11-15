package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * The ordered operations that are NOT `get`/`insert`/`remove` — `floor_key`,
 * `ceiling_key`, `range`, `head_map`, `tail_map`, `sub_map` — must honour the
 * program's `Ord`, and must work at all for a key type that is not
 * `Comparable` on the host.
 *
 * WHY THIS NEEDS ITS OWN FILE. Those six natives take NO comparator argument.
 * They search with the comparator STORED on the map, which used to be installed
 * as a side effect of `insert`: the old `insert` called
 * `withComparator(ElementComparator.of(cmpFn))` and the map it returned carried
 * the result. Once `insert` stopped going through the native that did that, the
 * only comparator a map could have is the natural-order lambda `empty {}` is
 * built with — so for any key whose ordering is not the host's, these six would
 * either answer by the wrong order or throw `ClassCastException` on a cast to
 * `Comparable`.
 *
 * A `newtype` with a DESCENDING `Ord` is the probe for both halves at once: it
 * is not host-comparable, and its order disagrees with the natural one
 * everywhere, so an answer computed the wrong way cannot coincide with the
 * right one.
 */
class SortedOrderOpsOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /**
     * `Down` orders by the REVERSE of its payload, so a map holding 1,3,5 is
     * laid out 5,3,1 and `min_key` is 5.
     */
    private val down = listOf(
        "use std::collections::sorted_map",
        "use std::option",
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
        "let m1 = sorted_map::insert { m = m0, k = Down.({ v = 1 }), v = 10 }",
        "let m2 = sorted_map::insert { m = m1, k = Down.({ v = 3 }), v = 30 }",
        "let m3 = sorted_map::insert { m = m2, k = Down.({ v = 5 }), v = 50 }",
        "let m4 = sorted_map::insert { m = m3, k = Down.({ v = 7 }), v = 70 }",
        "let m = m4",
    )

    private fun run(vararg parts: List<String>): Any =
        MezzeTestHelper.run(parts.flatMap { it }.joinToString("\n"))

    /** The layout, established without any comparator: index 0 and index n-1. */
    @Test
    fun `the descending order really is the layout`() {
        assertEquals(1L, run(down, listOf(
            "let lo = match sorted_map::min_key { m = m } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "let hi = match sorted_map::max_key { m = m } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "if lo == 7 && hi == 1 then 1 else 0",
        )))
    }

    @Test
    fun `floor_key follows the program's Ord`() {
        // Under `Down`, keys <= Down(4) are the ones numerically >= 4, i.e.
        // 5 and 7, and the GREATEST of those in this order is 5.
        assertEquals(5L, run(down, listOf(
            "match sorted_map::floor_key { m = m, k = Down.({ v = 4 }) } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
        )))
    }

    @Test
    fun `ceiling_key follows the program's Ord`() {
        // Keys >= Down(4) are those numerically <= 4, i.e. 3 and 1; the LEAST
        // in this order is 3.
        assertEquals(3L, run(down, listOf(
            "match sorted_map::ceiling_key { m = m, k = Down.({ v = 4 }) } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
        )))
    }

    @Test
    fun `head_map follows the program's Ord`() {
        // Keys strictly before Down(3) in this order are 7 and 5.
        assertEquals(1L, run(down, listOf(
            "let r = sorted_map::head_map { m = m, to = Down.({ v = 3 }) }",
            "let n = sorted_map::size { m = r }",
            "let lo = match sorted_map::min_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "let hi = match sorted_map::max_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "if n == 2 && lo == 7 && hi == 5 then 1 else 0",
        )))
    }

    @Test
    fun `tail_map follows the program's Ord`() {
        // Keys from Down(3) onward in this order are 3 and 1.
        assertEquals(1L, run(down, listOf(
            "let r = sorted_map::tail_map { m = m, from = Down.({ v = 3 }) }",
            "let n = sorted_map::size { m = r }",
            "let lo = match sorted_map::min_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "let hi = match sorted_map::max_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "if n == 2 && lo == 3 && hi == 1 then 1 else 0",
        )))
    }

    @Test
    fun `range follows the program's Ord`() {
        // [Down(5), Down(3)] inclusive spans 5 and 3 in this order.
        assertEquals(1L, run(down, listOf(
            "let r = sorted_map::range { m = m, lo = Down.({ v = 5 }), hi = Down.({ v = 3 }) }",
            "let n = sorted_map::size { m = r }",
            "let lo = match sorted_map::min_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "let hi = match sorted_map::max_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "if n == 2 && lo == 5 && hi == 3 then 1 else 0",
        )))
    }

    @Test
    fun `sub_map follows the program's Ord`() {
        // [Down(5), Down(1)) excludes 1, so it spans 5 and 3.
        assertEquals(1L, run(down, listOf(
            "let r = sorted_map::sub_map { m = m, lo = Down.({ v = 5 }), hi = Down.({ v = 1 }) }",
            "let n = sorted_map::size { m = r }",
            "let lo = match sorted_map::min_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "let hi = match sorted_map::max_key { m = r } is",
            "  Some { val } -> dv { d = val }",
            "  None -> 0",
            "if n == 2 && lo == 5 && hi == 3 then 1 else 0",
        )))
    }
}
