package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the List (immutable linked list) data structure.
 * Tests cover construction, deconstruction, predicates, transformation, and conversion functions.
 *
 * NOTE: Many tests use list::range to create lists to avoid type unification issues
 * with nested polymorphic function calls.
 */
class ListInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Variant Pattern Matching Tests
    // ========================================

    @Test
    fun `pattern match Cons on non-empty list`() {
        val code = """
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            match xs is
                Cons { head, tail } -> head
                Nil -> 0
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `pattern match Nil on empty list`() {
        val code = """
            use std::collections::list
            let xs = list::empty {}
            match xs is
                Cons { head, tail } -> head
                Nil -> 99
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    @Test
    fun `recursive pattern matching on list`() {
        val code = """
            use std::collections::list

            let sum = { xs } ->
                match xs is
                    Nil -> 0
                    Cons { head, tail } -> head + sum { xs = tail }

            let xs = list::range { start = 1, end = 4 }
            sum { xs = xs }
        """
        val result = eval(code)
        assertEquals(6L, result)  // 1 + 2 + 3
    }

    @Test
    fun `nested Cons Nil pattern matching`() {
        val code = """
            use std::collections::list

            let count = { xs } ->
                match xs is
                    Nil -> 0
                    Cons { head, tail } ->
                        match tail is
                            Nil -> 1
                            Cons { head = _, tail = rest } -> 2 + count { xs = rest }

            let xs = list::range { start = 1, end = 5 }  # [1,2,3,4]
            count { xs = xs }
        """
        val result = eval(code)
        assertEquals(4L, result)
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `empty list is empty`() {
        val code = """
            use std::collections::list
            list::is_empty { xs = list::empty {} }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `cons creates non-empty list`() {
        val code = """
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::empty {} }
            list::is_empty { xs = xs }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `singleton creates single-element list`() {
        val code = """
            use std::collections::list
            list::length { xs = list::singleton { x = 42 } }
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `range creates list of integers`() {
        val code = """
            use std::collections::list
            list::length { xs = list::range { start = 0, end = 5 } }
        """
        val result = eval(code)
        assertEquals(5L, result) // 0, 1, 2, 3, 4
    }

    // ========================================
    // Deconstruction Tests
    // ========================================

    @Test
    fun `head returns first element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::head { xs = xs } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `head on empty returns None`() {
        val code = """
            use std::collections::list
            match list::head { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `tail returns remaining list`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::tail { xs = xs } is
                Some { val } -> list::length { xs = val }
                None -> 0
        """
        val result = eval(code)
        assertEquals(2L, result) // tail of [1,2,3] is [2,3]
    }

    @Test
    fun `uncons returns head and tail together`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::uncons { xs = xs } is
                Some { val } -> val.head + list::length { xs = val.tail }
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result) // head (1) + tail length (2)
    }

    @Test
    fun `uncons on empty returns None`() {
        val code = """
            use std::collections::list
            match list::uncons { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // ========================================
    // Predicates Tests
    // ========================================

    @Test
    fun `length returns correct count`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 0, end = 10 }
            list::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `contains finds existing element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::contains { xs = xs, x = 5 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `contains returns false for missing element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::contains { xs = xs, x = 10 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    fun `map transforms all elements`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let doubled = list::map { xs = xs, f = { x } -> x * 2 }
            match list::head { xs = doubled } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(2L, result) // first elem 1 * 2 = 2
    }

    @Test
    fun `filter keeps matching elements`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::length { xs = evens }
        """
        val result = eval(code)
        assertEquals(4L, result) // 2, 4, 6, 8
    }

    @Test
    fun `fold_left accumulates left to right`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(6L, result) // 1 + 2 + 3 = 6
    }

    @Test
    fun `fold_right accumulates right to left`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            # Fold from right: start with 0, then 3, 32, 321
            list::fold_right { xs = xs, init = 0, f = { x, acc } -> acc * 10 + x }
        """
        val result = eval(code)
        assertEquals(321L, result)
    }

    // ========================================
    // Combination Tests
    // ========================================

    @Test
    fun `append joins two lists`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 3 }
            let ys = list::range { start = 3, end = 5 }
            list::length { xs = list::append { xs = xs, ys = ys } }
        """
        val result = eval(code)
        assertEquals(4L, result) // [1,2] + [3,4]
    }

    @Test
    fun `reverse reverses the list`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let reversed = list::reverse { xs = xs }
            match list::head { xs = reversed } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(3L, result) // 3 is now at front
    }

    @Test
    fun `zip pairs elements from two lists`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::range { start = 10, end = 13 }
            let zipped = list::zip { xs = xs, ys = ys }
            match list::head { xs = zipped } is
                Some { val } -> val.fst + val.snd
                None -> 0
        """
        val result = eval(code)
        assertEquals(11L, result) // 1 + 10
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `to_seq converts list to seq`() {
        val code = """
            use std::collections::list
            use std::collections::seq
            let xs = list::range { start = 1, end = 4 }
            let arr = list::to_seq { xs = xs }
            seq::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `from_seq converts seq to list`() {
        val code = """
            use std::collections::list
            let arr = [1, 2, 3]
            let xs = list::from_seq { xs = arr }
            list::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `of_seq creates list from seq elements`() {
        val code = """
            use std::collections::list
            let xs = list::of_seq { elements = [10, 20, 30] }
            list::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Utility Tests
    // ========================================

    @Test
    fun `repeat creates list with repeated element`() {
        val code = """
            use std::collections::list
            let xs = list::repeat { x = 42, n = 3 }
            list::length { xs = xs }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `any returns true if any element matches`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::any { xs = xs, p = { x } -> x > 5 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `all returns true if all elements match`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 2, end = 8 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::all { xs = evens, p = { x } -> x % 2 == 0 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `sort orders elements`() {
        val code = """
            use std::collections::list
            let xs = list::reverse { xs = list::range { start = 1, end = 4 } }
            let sorted = list::sort { xs = xs }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result) // Smallest first
    }

    // ========================================
    // Find Operations
    // ========================================

    @Test
    fun `find returns first matching element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            match list::find { xs = xs, p = { x } -> x > 5 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `find returns None when no match`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find { xs = xs, p = { x } -> x > 100 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `find_index returns index of first match`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            match list::find_index { xs = xs, p = { x } -> x > 5 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(5L, result)  // index 5 is value 6
    }

    @Test
    fun `find_index returns None when no match`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find_index { xs = xs, p = { x } -> x > 100 } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(-1L, result)
    }

    // ========================================
    // Partition
    // ========================================

    @Test
    fun `partition splits by predicate matching`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            let result = list::partition { xs = xs, p = { x } -> x > 3 }
            list::length { xs = result.pass }
        """
        val result = eval(code)
        assertEquals(2L, result)  // 4, 5
    }

    @Test
    fun `partition splits by predicate non_matching`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            let result = list::partition { xs = xs, p = { x } -> x > 3 }
            list::length { xs = result.fail }
        """
        val result = eval(code)
        assertEquals(3L, result)  // 1, 2, 3
    }

    // ========================================
    // Last and Init
    // ========================================

    @Test
    fun `last returns last element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::last { xs = xs } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(4L, result)
    }

    @Test
    fun `last returns None for empty list`() {
        val code = """
            use std::collections::list
            match list::last { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `init returns all but last element`() {
        val code = """
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            let inits = list::init { xs = xs }
            list::length { xs = inits }
        """
        val result = eval(code)
        assertEquals(3L, result)  // [1, 2, 3]
    }

    @Test
    fun `init of singleton is empty`() {
        val code = """
            use std::collections::list
            let xs = list::singleton { x = 42 }
            let inits = list::init { xs = xs }
            list::is_empty { xs = inits }
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
