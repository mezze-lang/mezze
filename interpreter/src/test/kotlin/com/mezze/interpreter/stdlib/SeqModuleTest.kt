package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/seq module.
 * Migrated from V1 to V2 with unboxed values.
 */
class SeqModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test array length`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            seq::size { xs = xs }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test array length - empty`() {
        val result = eval("""
            use std::collections::seq
            let xs: [Int] = []
            seq::size { xs = xs }
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test array get`() {
        val result = eval("""
            use std::collections::seq
            let xs = [10, 20, 30]
            match seq::get { xs = xs, i = 1 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(20L, result)
    }

    @Test
    fun `test array is_empty - true`() {
        val result = eval("""
            use std::collections::seq
            let xs: [Int] = []
            seq::is_empty { xs = xs }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test array is_empty - false`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            seq::is_empty { xs = xs }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test array set`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            match seq::set { xs = xs, i = 1, x = 99 } is
                Some { val = ys } ->
                    match seq::get { xs = ys, i = 1 } is
                        Some { val } -> val
                        None -> -2
                None -> -1
        """)
        assertEquals(99L, result)
    }

    @Test
    fun `test array add`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = seq::add { xs = xs, x = 4 }
            seq::size { xs = ys }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array concat`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2]
            let ys = [3, 4]
            let zs = seq::concat { xs = xs, ys = ys }
            seq::size { xs = zs }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array remove`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            match seq::remove { xs = xs, i = 2 } is
                Some { val } -> seq::size { xs = val }
                None -> -1
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array remove - out of bounds`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            match seq::remove { xs = xs, i = 10 } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test array of`() {
        val result = eval("""
            use std::collections::seq
            let xs = seq::of { x = 1, xs = [2, 3] }
            seq::size { xs = xs }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test array of - prepends element`() {
        val result = eval("""
            use std::collections::seq
            let xs = seq::of { x = 1, xs = [2, 3] }
            match seq::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(1L, result)
    }

    // === Higher-Order Functions ===

    @Test
    fun `test array map`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = seq::map { xs = xs, f = { x } -> x * 2 }
            match seq::get { xs = ys, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test array filter`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5, 6]
            let evens = seq::filter { xs = xs, p = { x } -> x % 2 == 0 }
            seq::size { xs = evens }
        """)
        assertEquals(3L, result)  // 2, 4, 6
    }

    @Test
    fun `test array fold`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4]
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """)
        assertEquals(10L, result)
    }

    // Note: seq::reduce has a bug in the native implementation - it expects 'init' like fold
    // The reduce function should use the first element as the initial accumulator
    // Skipping these tests until the native implementation is fixed

    // === Utility Functions ===

    @Test
    fun `test array contains - true`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            seq::contains { xs = xs, x = 3 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test array contains - false`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            seq::contains { xs = xs, x = 10 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test array find - found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            match seq::find { xs = xs, p = { x } -> x > 3 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array find - not found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            match seq::find { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test array reverse`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = seq::reverse { xs = xs }
            match seq::get { xs = ys, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test array sort`() {
        val result = eval("""
            use std::collections::seq
            let xs = [3, 1, 4, 1, 5, 9, 2, 6]
            let ys = seq::sort { xs = xs }
            match seq::head { xs = ys } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test array sort - preserves length`() {
        val result = eval("""
            use std::collections::seq
            let xs = [3, 1, 4, 1, 5]
            let ys = seq::sort { xs = xs }
            seq::size { xs = ys }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test array sort_by`() {
        val result = eval("""
            use std::collections::seq
            let xs = [3, 1, 4, 1, 5]
            let ys = seq::sort_by { xs = xs, cmp = { a, b } -> a >= b }
            match seq::head { xs = ys } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(5L, result)  // Descending order
    }

    // Note: index_of returns Option with bare value instead of Option with record
    // This is an inconsistency in the stdlib that should be fixed

    // === Slicing ===

    @Test
    fun `test array slice`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            let ys = seq::slice { xs = xs, start = 1, end = 4 }
            seq::size { xs = ys }
        """)
        assertEquals(3L, result)  // [2, 3, 4]
    }

    @Test
    fun `test array take`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            let ys = seq::take { xs = xs, n = 3 }
            seq::size { xs = ys }
        """)
        assertEquals(3L, result)  // [1, 2, 3]
    }

    @Test
    fun `test array drop`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            let ys = seq::drop { xs = xs, n = 2 }
            seq::size { xs = ys }
        """)
        assertEquals(3L, result)  // [3, 4, 5]
    }

    // === Head and Tail ===

    @Test
    fun `test array head - non-empty`() {
        val result = eval("""
            use std::collections::seq
            let xs = [10, 20, 30]
            match seq::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test array head - empty`() {
        val result = eval("""
            use std::collections::seq
            let xs: [Int] = []
            match seq::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test array index_of - found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [10, 20, 30, 40]
            match seq::index_of { xs = xs, x = 30 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test array index_of - not found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [10, 20, 30, 40]
            match seq::index_of { xs = xs, x = 99 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test array tail`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            let ys = seq::tail { xs = xs }
            seq::size { xs = ys }
        """)
        assertEquals(4L, result)  // [2, 3, 4, 5]
    }

    // === Equality ===

    @Test
    fun `test array equals - true`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = [1, 2, 3]
            seq::equals { l = xs, r = ys }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test array equals - false`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = [1, 2, 4]
            seq::equals { l = xs, r = ys }
        """)
        assertEquals(false, result)
    }

    // === reduce ===

    @Test
    fun `test array reduce - sum`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            match seq::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(15L, result)
    }

    @Test
    fun `test array reduce - empty`() {
        val result = eval("""
            use std::collections::seq
            let xs: [Int] = []
            match seq::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    // === zip ===

    @Test
    fun `test array zip`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let ys = ["a", "b", "c"]
            let zipped = seq::zip { xs = xs, ys = ys }
            seq::size { xs = zipped }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test array zip - unequal lengths`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4]
            let ys = ["a", "b"]
            let zipped = seq::zip { xs = xs, ys = ys }
            seq::size { xs = zipped }
        """)
        assertEquals(2L, result)
    }

    // === unzip ===

    @Test
    fun `test array unzip`() {
        val result = eval("""
            use std::collections::seq
            let pairs = [{ fst = 1, snd = "a" }, { fst = 2, snd = "b" }]
            let result = seq::unzip { xs = pairs }
            seq::size { xs = result.fsts }
        """)
        assertEquals(2L, result)
    }

    // === flatten ===

    @Test
    fun `test array flatten`() {
        val result = eval("""
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5]]
            let flat = seq::flatten { xss = nested }
            seq::size { xs = flat }
        """)
        assertEquals(5L, result)
    }

    // === any ===

    @Test
    fun `test array any - true`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            seq::any { xs = xs, p = { x } -> x > 3 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test array any - false`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            seq::any { xs = xs, p = { x } -> x > 10 }
        """)
        assertEquals(false, result)
    }

    // === all ===

    @Test
    fun `test array all - true`() {
        val result = eval("""
            use std::collections::seq
            let xs = [2, 4, 6, 8]
            seq::all { xs = xs, p = { x } -> x % 2 == 0 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test array all - false`() {
        val result = eval("""
            use std::collections::seq
            let xs = [2, 4, 5, 8]
            seq::all { xs = xs, p = { x } -> x % 2 == 0 }
        """)
        assertEquals(false, result)
    }

    // === partition ===

    @Test
    fun `test array partition`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5, 6]
            let result = seq::partition { xs = xs, p = { x } -> x % 2 == 0 }
            seq::size { xs = result.pass }
        """)
        assertEquals(3L, result)  // evens: 2, 4, 6
    }

    // === find_index ===

    @Test
    fun `test array find_index - found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            match seq::find_index { xs = xs, p = { x } -> x > 3 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(3L, result)  // index of 4
    }

    @Test
    fun `test array find_index - not found`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3]
            match seq::find_index { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    // === last ===

    @Test
    fun `test array last - non-empty`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            match seq::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test array last - empty`() {
        val result = eval("""
            use std::collections::seq
            let xs: [Int] = []
            match seq::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    // === init ===

    @Test
    fun `test array init - non-empty`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            let result = seq::init { xs = xs }
            seq::size { xs = result }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array init - single element`() {
        val result = eval("""
            use std::collections::seq
            let xs = [42]
            let result = seq::init { xs = xs }
            seq::size { xs = result }
        """)
        assertEquals(0L, result)
    }

    // === unique ===

    @Test
    fun `test array unique`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 2, 3, 3, 3, 4]
            let result = seq::unique { xs = xs }
            seq::size { xs = result }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test array unique - already unique`() {
        val result = eval("""
            use std::collections::seq
            let xs = [1, 2, 3, 4]
            let result = seq::unique { xs = xs }
            seq::size { xs = result }
        """)
        assertEquals(4L, result)
    }
}
