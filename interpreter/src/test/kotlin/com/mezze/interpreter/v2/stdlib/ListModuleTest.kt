package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/list module.
 * Migrated from V1 to V2 with unboxed values.
 */
class ListModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test list empty and is_empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::empty {}
            list::is_empty { xs = xs }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list cons creates non-empty list`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            list::is_empty { xs = xs }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test list length`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            list::length { xs = xs }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test list head - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            match list::head { xs = xs } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test list head - empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::empty {}
            match list::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test list tail - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::tail { xs = xs } is
                Some { val } -> list::length { xs = val }
                None -> 999
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test list uncons`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } }
            match list::uncons { xs = xs } is
                Some { val } -> val.head
                None -> -1
        """)
        assertEquals(1L, result)
    }

    // === List Construction ===

    @Test
    fun `test list singleton`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            list::length { xs = xs }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test list append`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 3 }
            let ys = list::range { start = 3, end = 5 }
            let zs = list::append { xs = xs, ys = ys }
            list::length { xs = zs }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test list range`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::length { xs = xs }
        """)
        assertEquals(4L, result)
    }

    // === Higher-Order Functions ===

    @Test
    fun `test list map`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::map { xs = xs, f = { x } -> x * 2 }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test list filter`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::length { xs = evens }
        """)
        assertEquals(4L, result)  // 2, 4, 6, 8
    }

    @Test
    fun `test list fold_left`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """)
        assertEquals(6L, result)  // 1 + 2 + 3
    }

    @Test
    fun `test list fold_right`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_right { xs = xs, init = 0, f = { x, acc } -> acc * 10 + x }
        """)
        assertEquals(321L, result)
    }

    // === List Predicates ===

    @Test
    fun `test list any - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::any { xs = xs, p = { x } -> x > 5 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list any - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::any { xs = xs, p = { x } -> x > 10 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test list all - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::all { xs = xs, p = { x } -> x < 10 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list all - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::all { xs = xs, p = { x } -> x < 5 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test list contains - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::contains { xs = xs, x = 5 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list contains - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::contains { xs = xs, x = 10 }
        """)
        assertEquals(false, result)
    }

    // === List Transformations ===

    @Test
    fun `test list reverse`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::reverse { xs = xs }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(3L, result)
    }

    // === Conversion ===

    @Test
    fun `test list to_seq and from_seq roundtrip`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            let arr = list::to_seq { xs = xs }
            let ys = list::from_seq { xs = arr }
            list::length { xs = ys }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test list of_seq`() {
        val result = eval("""
            use std::collections::list
            let arr = [1, 2, 3, 4, 5]
            let xs = list::of_seq { elements = arr }
            list::length { xs = xs }
        """)
        assertEquals(5L, result)
    }

    // === Additional tests for missing coverage ===

    @Test
    fun `test list push`() {
        val result = eval("""
            use std::collections::list
            let xs = list::empty {}
            let ys = list::push { x = 42, xs = xs }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test list prepend`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 2, end = 4 }
            let ys = list::prepend { x = 1, xs = xs }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test list get - valid index`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 10, end = 15 }
            match list::get { xs = xs, index = 2 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(12L, result)
    }

    @Test
    fun `test list get - invalid index`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::get { xs = xs, index = 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test list take`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let ys = list::take { xs = xs, n = 3 }
            list::length { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test list take - more than length`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::take { xs = xs, n = 10 }
            list::length { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test list drop`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let ys = list::drop { xs = xs, n = 3 }
            list::length { xs = ys }
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test list drop - more than length`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::drop { xs = xs, n = 10 }
            list::is_empty { xs = ys }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list reduce - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """)
        assertEquals(10L, result)  // 1 + 2 + 3 + 4
    }

    @Test
    fun `test list concat`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = list::range { start = 1, end = 3 } }
            let ys = list::cons { head = list::range { start = 3, end = 5 }, tail = xs }
            let zs = list::concat { xss = ys }
            list::length { xs = zs }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test list flat_map`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::flat_map { xs = xs, f = { x } -> list::range { start = 1, end = x + 1 } }
            list::length { xs = ys }
        """)
        assertEquals(6L, result)  // [1] ++ [1,2] ++ [1,2,3] = 1+2+3 = 6
    }

    @Test
    fun `test list zip`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::range { start = 10, end = 13 }
            let zipped = list::zip { xs = xs, ys = ys }
            match list::head { xs = zipped } is
                Some { val } -> val.fst + val.snd
                None -> -1
        """)
        assertEquals(11L, result)  // 1 + 10
    }

    @Test
    fun `test list zip - different lengths`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let ys = list::range { start = 10, end = 13 }
            let zipped = list::zip { xs = xs, ys = ys }
            list::length { xs = zipped }
        """)
        assertEquals(3L, result)  // min(9, 3) = 3
    }

    @Test
    fun `test list unzip`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::range { start = 10, end = 13 }
            let zipped = list::zip { xs = xs, ys = ys }
            let unzipped = list::unzip { xs = zipped }
            list::length { xs = unzipped.fsts }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test list repeat`() {
        val result = eval("""
            use std::collections::list
            let xs = list::repeat { x = 42, n = 5 }
            list::length { xs = xs }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test list repeat - zero`() {
        val result = eval("""
            use std::collections::list
            let xs = list::repeat { x = 42, n = 0 }
            list::is_empty { xs = xs }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test list intersperse`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::intersperse { xs = xs, sep = 0 }
            list::length { xs = ys }
        """)
        assertEquals(5L, result)  // [1, 0, 2, 0, 3]
    }

    @Test
    fun `test list intersperse - single element`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            let ys = list::intersperse { xs = xs, sep = 0 }
            list::length { xs = ys }
        """)
        assertEquals(1L, result)  // [42] - no separator added
    }

    @Test
    fun `test list sort`() {
        val result = eval("""
            use std::collections::list
            let xs = list::of_seq { elements = [3, 1, 4, 1, 5, 9, 2, 6] }
            let sorted = list::sort { xs = xs }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test list sort_by`() {
        val result = eval("""
            use std::collections::list
            let xs = list::of_seq { elements = [3, 1, 4, 1, 5] }
            let sorted = list::sort_by { xs = xs, cmp = { a, b } ->
                if a > b then 1
                else if a < b then -1
                else 0
            }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test list sort_by - descending`() {
        val result = eval("""
            use std::collections::list
            let xs = list::of_seq { elements = [3, 1, 4, 1, 5] }
            let sorted = list::sort_by { xs = xs, cmp = { a, b } ->
                if a < b then 1
                else if a > b then -1
                else 0
            }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test list find - found`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            match list::find { xs = xs, p = { x } -> x > 5 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test list find - not found`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test list find_index - found`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 10, end = 20 }
            match list::find_index { xs = xs, p = { x } -> x > 14 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(5L, result)  // index of 15
    }

    @Test
    fun `test list find_index - not found`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find_index { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test list partition`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let parts = list::partition { xs = xs, p = { x } -> x > 5 }
            list::length { xs = parts.pass }
        """)
        assertEquals(4L, result)  // 6, 7, 8, 9
    }

    @Test
    fun `test list partition - not matching`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let parts = list::partition { xs = xs, p = { x } -> x > 5 }
            list::length { xs = parts.fail }
        """)
        assertEquals(5L, result)  // 1, 2, 3, 4, 5
    }

    @Test
    fun `test list last - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            match list::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test list last - empty`() {
        val result = eval("""
            use std::collections::list
            let xs: List Int = list::empty {}
            match list::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test list init - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            let ys = list::init { xs = xs }
            list::length { xs = ys }
        """)
        assertEquals(4L, result)  // [1, 2, 3, 4]
    }

    @Test
    fun `test list init - single element`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            let ys = list::init { xs = xs }
            list::is_empty { xs = ys }
        """)
        assertEquals(true, result)
    }

    // === reduce ===

    @Test
    fun `test list reduce - sum`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            match list::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(15L, result)  // 1 + 2 + 3 + 4 + 5 = 15
    }

    @Test
    fun `test list reduce - single element`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            match list::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test list reduce - empty list`() {
        val result = eval("""
            use std::collections::list
            let xs: List Int = list::empty {}
            match list::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)  // None for empty list
    }
}
