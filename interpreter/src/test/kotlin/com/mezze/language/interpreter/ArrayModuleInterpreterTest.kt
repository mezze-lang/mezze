package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::collections::array module functions.
 * Tests cover array operations including the new utility functions.
 */
class ArrayModuleInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val parser = com.mezze.language.parser.MezzeParser()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ========================================
    // Basic Operations
    // ========================================

    @Test
    fun `length returns array length`() {
        val code = """
            use std::collections::array
            array::length { xs = [1, 2, 3, 4, 5] }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `is_empty returns true for empty array`() {
        val code = """
            use std::collections::array
            let xs: [Int] = []
            array::is_empty { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `is_empty returns false for non-empty array`() {
        val code = """
            use std::collections::array
            array::is_empty { xs = [1] }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `get returns element at index`() {
        val code = """
            use std::collections::array
            match array::get { xs = [10, 20, 30], i = 1 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(20), result)
    }

    @Test
    fun `get returns None for out of bounds`() {
        val code = """
            use std::collections::array
            match array::get { xs = [10, 20, 30], i = 10 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    // ========================================
    // Higher-Order Functions
    // ========================================

    @Test
    fun `map transforms all elements`() {
        val code = """
            use std::collections::array
            let xs = array::map { xs = [1, 2, 3], f = { x } -> x * 2 }
            match array::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `filter keeps matching elements`() {
        val code = """
            use std::collections::array
            let xs = array::filter { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `fold accumulates values`() {
        val code = """
            use std::collections::array
            array::fold { xs = [1, 2, 3, 4], init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `fold with explicit init combines elements`() {
        val code = """
            use std::collections::array
            array::fold { xs = [1, 2, 3, 4], init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(MezzeInt(10), result)
    }

    // ========================================
    // Predicate Functions
    // ========================================

    @Test
    fun `any returns true when predicate matches`() {
        val code = """
            use std::collections::array
            array::any { xs = [1, 2, 3, 4, 5], p = { x } -> x > 3 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `any returns false when no element matches`() {
        val code = """
            use std::collections::array
            array::any { xs = [1, 2, 3], p = { x } -> x > 10 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `all returns true when all elements match`() {
        val code = """
            use std::collections::array
            array::all { xs = [2, 4, 6, 8], p = { x } -> x % 2 == 0 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `all returns false when any element fails`() {
        val code = """
            use std::collections::array
            array::all { xs = [2, 4, 5, 8], p = { x } -> x % 2 == 0 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `contains finds element`() {
        val code = """
            use std::collections::array
            array::contains { xs = [1, 2, 3, 4, 5], x = 3 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `contains returns false for missing element`() {
        val code = """
            use std::collections::array
            array::contains { xs = [1, 2, 3, 4, 5], x = 10 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    // ========================================
    // Find Operations
    // ========================================

    @Test
    fun `find returns first matching element`() {
        val code = """
            use std::collections::array
            match array::find { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `find returns None when not found`() {
        val code = """
            use std::collections::array
            match array::find { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `find_index returns index of first match`() {
        val code = """
            use std::collections::array
            match array::find_index { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `find_index returns None when not found`() {
        val code = """
            use std::collections::array
            match array::find_index { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `index_of returns index of element`() {
        val code = """
            use std::collections::array
            match array::index_of { xs = [10, 20, 30], x = 20 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    // ========================================
    // Partition
    // ========================================

    @Test
    fun `partition splits by predicate`() {
        val code = """
            use std::collections::array
            let result = array::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            array::length { xs = result.matching }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)  // 3, 4, 5
    }

    @Test
    fun `partition non_matching elements`() {
        val code = """
            use std::collections::array
            let result = array::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            array::length { xs = result.not_matching }
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)  // 1, 2
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    fun `flatten merges nested arrays`() {
        val code = """
            use std::collections::array
            let nested = [[1, 2], [3, 4], [5]]
            let flat = array::flatten { xss = nested }
            array::length { xs = flat }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `flatten preserves order`() {
        val code = """
            use std::collections::array
            let nested = [[1, 2], [3, 4]]
            let flat = array::flatten { xss = nested }
            match array::get { xs = flat, i = 2 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Accessors
    // ========================================

    @Test
    fun `head returns first element`() {
        val code = """
            use std::collections::array
            match array::head { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `last returns last element`() {
        val code = """
            use std::collections::array
            match array::last { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun `last returns None for empty array`() {
        val code = """
            use std::collections::array
            let xs: [Int] = []
            match array::last { xs = xs } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `init returns all but last`() {
        val code = """
            use std::collections::array
            let xs = array::init { xs = [1, 2, 3, 4] }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `tail returns all but first`() {
        val code = """
            use std::collections::array
            let xs = array::tail { xs = [1, 2, 3, 4] }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Slicing
    // ========================================

    @Test
    fun `slice extracts subarray`() {
        val code = """
            use std::collections::array
            let xs = array::slice { xs = [1, 2, 3, 4, 5], start = 1, end = 4 }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `take returns first n elements`() {
        val code = """
            use std::collections::array
            let xs = array::take { xs = [1, 2, 3, 4, 5], n = 3 }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `drop skips first n elements`() {
        val code = """
            use std::collections::array
            let xs = array::drop { xs = [1, 2, 3, 4, 5], n = 2 }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Zip Operations
    // ========================================

    @Test
    fun `zip pairs elements from two arrays`() {
        val code = """
            use std::collections::array
            let zipped = array::zip { xs = [1, 2, 3], ys = ["a", "b", "c"] }
            array::length { xs = zipped }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `zip truncates to shorter length`() {
        val code = """
            use std::collections::array
            let zipped = array::zip { xs = [1, 2], ys = ["a", "b", "c", "d"] }
            array::length { xs = zipped }
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `unzip splits pairs into two arrays`() {
        val code = """
            use std::collections::array
            let pairs = [{ fst = 1, snd = "a" }, { fst = 2, snd = "b" }]
            let result = array::unzip { xs = pairs }
            array::length { xs = result.fsts }
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Unique
    // ========================================

    @Test
    fun `unique removes duplicates`() {
        val code = """
            use std::collections::array
            let xs = array::unique { xs = [1, 2, 1, 3, 2, 4, 1] }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `unique preserves first occurrence`() {
        val code = """
            use std::collections::array
            let xs = array::unique { xs = [3, 1, 2, 1, 3] }
            match array::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Transformations
    // ========================================

    @Test
    fun `reverse reverses array`() {
        val code = """
            use std::collections::array
            let xs = array::reverse { xs = [1, 2, 3] }
            match array::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `sort orders elements`() {
        val code = """
            use std::collections::array
            let xs = array::sort { xs = [3, 1, 4, 1, 5, 9, 2, 6] }
            match array::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `concat joins two arrays`() {
        val code = """
            use std::collections::array
            let xs = array::concat { xs = [1, 2], ys = [3, 4] }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `add appends element`() {
        val code = """
            use std::collections::array
            let xs = array::add { xs = [1, 2], x = 3 }
            array::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `set updates element at index`() {
        val code = """
            use std::collections::array
            match array::set { xs = [1, 2, 3], i = 1, x = 99 } is
                Some { val } -> match array::get { xs = val, i = 1 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """
        val result = eval(code)
        assertEquals(MezzeInt(99), result)
    }

    @Test
    fun `remove deletes element at index`() {
        val code = """
            use std::collections::array
            match array::remove { xs = [1, 2, 3], i = 1 } is
                Some { val } -> array::length { xs = val }
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }
}
