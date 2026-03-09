package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::collections::seq module functions.
 * Tests cover seq operations including the new utility functions.
 */
class SeqModuleInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic Operations
    // ========================================

    @Test
    fun `length returns array length`() {
        val code = """
            use std::collections::seq
            seq::length { xs = [1, 2, 3, 4, 5] }
        """
        val result = eval(code)
        assertEquals(5L, result)
    }

    @Test
    fun `is_empty returns true for empty array`() {
        val code = """
            use std::collections::seq
            let xs: [Int] = []
            seq::is_empty { xs = xs }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_empty returns false for non-empty array`() {
        val code = """
            use std::collections::seq
            seq::is_empty { xs = [1] }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `get returns element at index`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [10, 20, 30], i = 1 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    @Test
    fun `get returns None for out of bounds`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [10, 20, 30], i = 10 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // ========================================
    // Higher-Order Functions
    // ========================================

    @Test
    fun `map transforms all elements`() {
        val code = """
            use std::collections::seq
            let xs = seq::map { xs = [1, 2, 3], f = { x } -> x * 2 }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `filter keeps matching elements`() {
        val code = """
            use std::collections::seq
            let xs = seq::filter { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `fold accumulates values`() {
        val code = """
            use std::collections::seq
            seq::fold { xs = [1, 2, 3, 4], init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `fold with explicit init combines elements`() {
        val code = """
            use std::collections::seq
            seq::fold { xs = [1, 2, 3, 4], init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    // ========================================
    // Predicate Functions
    // ========================================

    @Test
    fun `any returns true when predicate matches`() {
        val code = """
            use std::collections::seq
            seq::any { xs = [1, 2, 3, 4, 5], p = { x } -> x > 3 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `any returns false when no element matches`() {
        val code = """
            use std::collections::seq
            seq::any { xs = [1, 2, 3], p = { x } -> x > 10 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `all returns true when all elements match`() {
        val code = """
            use std::collections::seq
            seq::all { xs = [2, 4, 6, 8], p = { x } -> x % 2 == 0 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `all returns false when any element fails`() {
        val code = """
            use std::collections::seq
            seq::all { xs = [2, 4, 5, 8], p = { x } -> x % 2 == 0 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `contains finds element`() {
        val code = """
            use std::collections::seq
            seq::contains { xs = [1, 2, 3, 4, 5], x = 3 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `contains returns false for missing element`() {
        val code = """
            use std::collections::seq
            seq::contains { xs = [1, 2, 3, 4, 5], x = 10 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Find Operations
    // ========================================

    @Test
    fun `find returns first matching element`() {
        val code = """
            use std::collections::seq
            match seq::find { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `find returns None when not found`() {
        val code = """
            use std::collections::seq
            match seq::find { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `find_index returns index of first match`() {
        val code = """
            use std::collections::seq
            match seq::find_index { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `find_index returns None when not found`() {
        val code = """
            use std::collections::seq
            match seq::find_index { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(-1L, result)
    }

    @Test
    fun `index_of returns index of element`() {
        val code = """
            use std::collections::seq
            match seq::index_of { xs = [10, 20, 30], x = 20 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    // ========================================
    // Partition
    // ========================================

    @Test
    fun `partition splits by predicate`() {
        val code = """
            use std::collections::seq
            let result = seq::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = result.matching }
        """
        val result = eval(code)
        assertEquals(3L, result)  // 3, 4, 5
    }

    @Test
    fun `partition non_matching elements`() {
        val code = """
            use std::collections::seq
            let result = seq::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = result.not_matching }
        """
        val result = eval(code)
        assertEquals(2L, result)  // 1, 2
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    fun `flatten merges nested arrays`() {
        val code = """
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5]]
            let flat = seq::flatten { xss = nested }
            seq::length { xs = flat }
        """
        val result = eval(code)
        assertEquals(5L, result)
    }

    @Test
    fun `flatten preserves order`() {
        val code = """
            use std::collections::seq
            let nested = [[1, 2], [3, 4]]
            let flat = seq::flatten { xss = nested }
            match seq::get { xs = flat, i = 2 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Accessors
    // ========================================

    @Test
    fun `head returns first element`() {
        val code = """
            use std::collections::seq
            match seq::head { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `last returns last element`() {
        val code = """
            use std::collections::seq
            match seq::last { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `last returns None for empty array`() {
        val code = """
            use std::collections::seq
            let xs: [Int] = []
            match seq::last { xs = xs } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `init returns all but last`() {
        val code = """
            use std::collections::seq
            let xs = seq::init { xs = [1, 2, 3, 4] }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `tail returns all but first`() {
        val code = """
            use std::collections::seq
            let xs = seq::tail { xs = [1, 2, 3, 4] }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Slicing
    // ========================================

    @Test
    fun `slice extracts subarray`() {
        val code = """
            use std::collections::seq
            let xs = seq::slice { xs = [1, 2, 3, 4, 5], start = 1, end = 4 }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `take returns first n elements`() {
        val code = """
            use std::collections::seq
            let xs = seq::take { xs = [1, 2, 3, 4, 5], n = 3 }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `drop skips first n elements`() {
        val code = """
            use std::collections::seq
            let xs = seq::drop { xs = [1, 2, 3, 4, 5], n = 2 }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Zip Operations
    // ========================================

    @Test
    fun `zip pairs elements from two arrays`() {
        val code = """
            use std::collections::seq
            let zipped = seq::zip { xs = [1, 2, 3], ys = ["a", "b", "c"] }
            seq::length { xs = zipped }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `zip truncates to shorter length`() {
        val code = """
            use std::collections::seq
            let zipped = seq::zip { xs = [1, 2], ys = ["a", "b", "c", "d"] }
            seq::length { xs = zipped }
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `unzip splits pairs into two arrays`() {
        val code = """
            use std::collections::seq
            let pairs = [{ fst = 1, snd = "a" }, { fst = 2, snd = "b" }]
            let result = seq::unzip { xs = pairs }
            seq::length { xs = result.fsts }
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    // ========================================
    // Unique
    // ========================================

    @Test
    fun `unique removes duplicates`() {
        val code = """
            use std::collections::seq
            let xs = seq::unique { xs = [1, 2, 1, 3, 2, 4, 1] }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(4L, result)
    }

    @Test
    fun `unique preserves first occurrence`() {
        val code = """
            use std::collections::seq
            let xs = seq::unique { xs = [3, 1, 2, 1, 3] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Transformations
    // ========================================

    @Test
    fun `reverse reverses array`() {
        val code = """
            use std::collections::seq
            let xs = seq::reverse { xs = [1, 2, 3] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `sort orders elements`() {
        val code = """
            use std::collections::seq
            let xs = seq::sort { xs = [3, 1, 4, 1, 5, 9, 2, 6] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `concat joins two arrays`() {
        val code = """
            use std::collections::seq
            let xs = seq::concat { xs = [1, 2], ys = [3, 4] }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(4L, result)
    }

    @Test
    fun `add appends element`() {
        val code = """
            use std::collections::seq
            let xs = seq::add { xs = [1, 2], x = 3 }
            seq::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `set updates element at index`() {
        val code = """
            use std::collections::seq
            match seq::set { xs = [1, 2, 3], i = 1, x = 99 } is
                Some { val } -> match seq::get { xs = val, i = 1 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    @Test
    fun `remove deletes element at index`() {
        val code = """
            use std::collections::seq
            match seq::remove { xs = [1, 2, 3], i = 1 } is
                Some { val } -> seq::length { xs = val }
                None -> -1
        """
        val result = eval(code)
        assertEquals(2L, result)
    }
}
