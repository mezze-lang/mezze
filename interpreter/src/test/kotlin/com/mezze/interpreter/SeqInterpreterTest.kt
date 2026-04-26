package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for seq operations in the V2 interpreter.
 * Migrated from V1 to V2 with unboxed values.
 *
 * In V2, seqs are represented as MezzeSeq internally.
 * Tests use Mezze source code to exercise seq operations.
 */
class SeqInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==========================================================================
    // Basic Array Operations
    // ==========================================================================

    @Test
    fun `test empty array length`() {
        val code = """
            use std::collections::seq
            let xs: [Int] = []
            seq::length { xs = xs }
        """
        assertEquals(0L, eval(code))
    }

    @Test
    fun `test empty array is_empty`() {
        val code = """
            use std::collections::seq
            let xs: [Int] = []
            seq::is_empty { xs = xs }
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `test integer array length`() {
        val code = """
            use std::collections::seq
            seq::length { xs = [1, 2, 3, 4, 5] }
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test integer array element access`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [1, 2, 3, 4, 5], i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test integer array element access at index 2`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [1, 2, 3, 4, 5], i = 2 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test integer array element access at last index`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [1, 2, 3, 4, 5], i = 4 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test string array length`() {
        val code = """
            use std::collections::seq
            seq::length { xs = ["Alice", "Bob", "Charlie"] }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test string array element access`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = ["Alice", "Bob", "Charlie"], i = 0 } is
                Some { val } -> val
                None -> "not found"
        """
        assertEquals("Alice", eval(code))
    }

    @Test
    fun `test string array element access at index 1`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = ["Alice", "Bob", "Charlie"], i = 1 } is
                Some { val } -> val
                None -> "not found"
        """
        assertEquals("Bob", eval(code))
    }

    @Test
    fun `test string array element access at index 2`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = ["Alice", "Bob", "Charlie"], i = 2 } is
                Some { val } -> val
                None -> "not found"
        """
        assertEquals("Charlie", eval(code))
    }

    @Test
    fun `test boolean array length`() {
        val code = """
            use std::collections::seq
            seq::length { xs = [True, False, True] }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test boolean array element access at index 0`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [True, False, True], i = 0 } is
                Some { val } -> val
                None -> False
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `test boolean array element access at index 1`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [True, False, True], i = 1 } is
                Some { val } -> val
                None -> True
        """
        assertEquals(false, eval(code))
    }

    @Test
    fun `test boolean array element access at index 2`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [True, False, True], i = 2 } is
                Some { val } -> val
                None -> False
        """
        assertEquals(true, eval(code))
    }

    // ==========================================================================
    // Nested Arrays
    // ==========================================================================

    @Test
    fun `test nested array outer length`() {
        val code = """
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5, 6]]
            seq::length { xs = nested }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test nested array inner length`() {
        val code = """
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5, 6]]
            match seq::get { xs = nested, i = 0 } is
                Some { val } -> seq::length { xs = val }
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test nested array access first inner array element`() {
        // Use array pattern matching instead of seq::get for nested access
        val code = """
            let nested = [[1, 2], [3, 4], [5, 6]]
            match nested is
                [[a, ...rest1], ...rest2] -> a
                _ -> -1
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test nested array access second inner array`() {
        // Use array pattern matching instead of seq::get for nested access
        val code = """
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5, 6]]
            match nested is
                [_, second, ...rest] -> seq::length { xs = second }
                _ -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test nested array access third inner array element`() {
        // Use array pattern matching instead of seq::get for nested access
        val code = """
            let nested = [[1, 2], [3, 4], [5, 6]]
            match nested is
                [_, _, [_, b]] -> b
                _ -> -1
        """
        assertEquals(6L, eval(code))
    }

    // ==========================================================================
    // Computed Elements
    // ==========================================================================

    @Test
    fun `test array with computed elements`() {
        val code = """
            use std::collections::seq
            let arr = [1 + 2, 3 * 4, 10 - 5]
            seq::length { xs = arr }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array with computed elements access index 0`() {
        val code = """
            use std::collections::seq
            let arr = [1 + 2, 3 * 4, 10 - 5]
            match seq::get { xs = arr, i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array with computed elements access index 1`() {
        val code = """
            use std::collections::seq
            let arr = [1 + 2, 3 * 4, 10 - 5]
            match seq::get { xs = arr, i = 1 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(12L, eval(code))
    }

    @Test
    fun `test array with computed elements access index 2`() {
        val code = """
            use std::collections::seq
            let arr = [1 + 2, 3 * 4, 10 - 5]
            match seq::get { xs = arr, i = 2 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(5L, eval(code))
    }

    // ==========================================================================
    // Single Element Array
    // ==========================================================================

    @Test
    fun `test single element array length`() {
        val code = """
            use std::collections::seq
            seq::length { xs = [42] }
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test single element array access`() {
        val code = """
            use std::collections::seq
            match seq::get { xs = [42], i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(42L, eval(code))
    }

    // ==========================================================================
    // Array Operations (map, filter, fold)
    // ==========================================================================

    @Test
    fun `test array map operation`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let mapped = seq::map { xs = arr, f = { x } -> x * 2 }
            match seq::get { xs = mapped, i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array map preserves length`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let mapped = seq::map { xs = arr, f = { x } -> x * 2 }
            seq::length { xs = mapped }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array filter operation`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let filtered = seq::filter { xs = arr, p = { x } -> x > 1 }
            seq::length { xs = filtered }
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array filter first element`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let filtered = seq::filter { xs = arr, p = { x } -> x > 1 }
            match seq::get { xs = filtered, i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array fold operation`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            seq::fold { xs = arr, init = 0, f = { acc, x } -> acc + x }
        """
        assertEquals(6L, eval(code))
    }

    // ==========================================================================
    // Array Equality (via fold comparison)
    // ==========================================================================

    @Test
    fun `test array equality via element comparison`() {
        // Compare arrays using fold - all elements at same positions must be equal
        val code = """
            use std::collections::seq
            let arr1 = [1, 2, 3]
            let arr2 = [1, 2, 3]

            # Compare lengths first, then elements
            let len1 = seq::length { xs = arr1 }
            let len2 = seq::length { xs = arr2 }

            if len1 != len2 then False
            else if len1 == 0 then True
            else do
                # Check first element
                let e1 = match arr1 is [x, ...rest] -> x _ -> 0
                let e2 = match arr2 is [x, ...rest] -> x _ -> 0
                e1 == e2 && do
                    # Check second element
                    let e3 = match arr1 is [_, y, ...rest] -> y _ -> 0
                    let e4 = match arr2 is [_, y, ...rest] -> y _ -> 0
                    e3 == e4 && do
                        # Check third element
                        let e5 = match arr1 is [_, _, z] -> z _ -> 0
                        let e6 = match arr2 is [_, _, z] -> z _ -> 0
                        e5 == e6
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `test array inequality via element comparison`() {
        // Compare arrays - should find difference at position 2
        val code = """
            use std::collections::seq
            let arr1 = [1, 2, 3]
            let arr2 = [1, 2, 4]

            # Check third element differs
            let e1 = match arr1 is [_, _, z] -> z _ -> 0
            let e2 = match arr2 is [_, _, z] -> z _ -> 0
            e1 != e2
        """
        assertEquals(true, eval(code))
    }

    // ==========================================================================
    // Array Immutability
    // ==========================================================================

    @Test
    fun `test array add returns new array`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2]
            let new_arr = seq::add { xs = arr, x = 3 }
            seq::length { xs = new_arr }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array add does not modify original`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2]
            let new_arr = seq::add { xs = arr, x = 3 }
            seq::length { xs = arr }
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array set returns new array`() {
        // Use pattern matching to verify set operation
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let result = seq::set { xs = arr, i = 0, x = 10 }
            match result is
                Some { val } -> match val is
                    [first, ...rest] -> first
                    _ -> -1
                None -> -2
        """
        assertEquals(10L, eval(code))
    }

    @Test
    fun `test array set does not modify original`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let updated = seq::set { xs = arr, i = 0, x = 10 }
            match seq::get { xs = arr, i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test array remove returns new array`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            match seq::remove { xs = arr, i = 0 } is
                Some { val } -> seq::length { xs = val }
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array remove result first element`() {
        // Use pattern matching to verify remove operation
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let result = seq::remove { xs = arr, i = 0 }
            match result is
                Some { val } -> match val is
                    [first, ...rest] -> first
                    _ -> -1
                None -> -2
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array remove does not modify original`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let removed = seq::remove { xs = arr, i = 0 }
            seq::length { xs = arr }
        """
        assertEquals(3L, eval(code))
    }

    // ==========================================================================
    // Array Spread Syntax
    // ==========================================================================

    @Test
    fun `test array spread with append`() {
        val code = """
            use std::collections::seq
            let rest = [1, 2, 3]
            let combined = [...rest, 4, 5]
            seq::length { xs = combined }
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test array spread with append first element`() {
        val code = """
            use std::collections::seq
            let rest = [1, 2, 3]
            let combined = [...rest, 4, 5]
            match seq::get { xs = combined, i = 0 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test array spread with append last element`() {
        val code = """
            use std::collections::seq
            let rest = [1, 2, 3]
            let combined = [...rest, 4, 5]
            match seq::get { xs = combined, i = 4 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test array spread with multiple spreads`() {
        val code = """
            use std::collections::seq
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b]
            seq::length { xs = combined }
        """
        assertEquals(4L, eval(code))
    }

    @Test
    fun `test array spread with multiple spreads element order`() {
        val code = """
            use std::collections::seq
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b]
            match seq::get { xs = combined, i = 2 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array spread with spread only`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let copy = [...arr]
            seq::length { xs = copy }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test array spread copy preserves elements`() {
        val code = """
            use std::collections::seq
            let arr = [1, 2, 3]
            let copy = [...arr]
            match seq::get { xs = copy, i = 1 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test array spread with multiple spreads and append`() {
        val code = """
            use std::collections::seq
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b, 5, 6]
            seq::length { xs = combined }
        """
        assertEquals(6L, eval(code))
    }

    @Test
    fun `test array spread with multiple spreads and append last element`() {
        val code = """
            use std::collections::seq
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b, 5, 6]
            match seq::get { xs = combined, i = 5 } is
                Some { val } -> val
                None -> -1
        """
        assertEquals(6L, eval(code))
    }
}
