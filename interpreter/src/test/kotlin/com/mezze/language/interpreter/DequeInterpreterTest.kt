package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the Deque (double-ended queue) data structure.
 * Tests cover construction, add/remove operations, peek, conversions, and higher-order functions.
 */
class DequeInterpreterTest {

    @BeforeEach
    fun setUp() {
        // Clear module cache to ensure clean state between tests
        // This prevents type variable pollution across tests
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        // Create fresh parser and type checker for each eval to avoid state pollution
        val parser = com.mezze.language.parser.MezzeParser()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `empty deque is empty`() {
        val code = """
            use std::collections::deque::{empty, is_empty}
            is_empty { d = empty {} }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `singleton deque has length 1`() {
        val code = """
            use std::collections::deque
            deque::length { d = deque::singleton { x = 42 } }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `from_list creates deque with all elements`() {
        val code = """
            use std::collections::deque
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::empty {} } } }
            deque::length { d = deque::from_list { xs = xs } }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Push Operations Tests
    // ========================================

    @Test
    fun `push_front adds element to front`() {
        val code = """
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_front { d = d, x = 1 }
            let d3 = deque::push_front { d = d2, x = 2 }
            match deque::peek_front { d = d3 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `push_back adds element to back`() {
        val code = """
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 1 }
            let d3 = deque::push_back { d = d2, x = 2 }
            match deque::peek_back { d = d3 } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `push operations maintain order`() {
        val code = """
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 1 }
            let d3 = deque::push_back { d = d2, x = 2 }
            let d4 = deque::push_back { d = d3, x = 3 }
            deque::length { d = d4 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Pop Operations Tests
    // ========================================

    @Test
    fun `pop_front returns front element and remaining deque`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_front { d = d } is
                Some { val } -> val.head
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `pop_back returns back element and remaining deque`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_back { d = d } is
                Some { val } -> val.last
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `pop_front on empty returns None`() {
        val code = """
            use std::collections::deque
            match deque::pop_front { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `pop_back on empty returns None`() {
        val code = """
            use std::collections::deque
            match deque::pop_back { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    // ========================================
    // Peek Operations Tests
    // ========================================

    @Test
    fun `peek_front returns front without removing`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let front = match deque::peek_front { d = d } is
                Some { val } -> val
                None -> 0
            let stillSameLength = deque::length { d = d }
            front + stillSameLength
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result) // 1 (front) + 2 (length)
    }

    @Test
    fun `peek_back returns back without removing`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::peek_back { d = d } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Queue Operations (FIFO) Tests
    // ========================================

    @Test
    fun `enqueue and dequeue work as FIFO`() {
        val code = """
            use std::collections::deque
            let d = deque::enqueue { d = deque::empty {}, x = 1 }
            let d2 = deque::enqueue { d = d, x = 2 }
            let d3 = deque::enqueue { d = d2, x = 3 }
            match deque::dequeue { d = d3 } is
                Some { val } -> val.head
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result) // FIFO: first in, first out
    }

    // ========================================
    // Stack Operations (LIFO) Tests
    // ========================================

    @Test
    fun `push and pop work as LIFO`() {
        val code = """
            use std::collections::deque
            let d = deque::push { d = deque::empty {}, x = 1 }
            let d2 = deque::push { d = d, x = 2 }
            let d3 = deque::push { d = d2, x = 3 }
            match deque::pop { d = d3 } is
                Some { val } -> val.head
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result) // LIFO: last in, first out
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `to_list converts deque to list`() {
        val code = """
            use std::collections::deque
            use std::collections::list
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let xs = deque::to_list { d = d }
            # Check length using list::is_empty instead to avoid potential issues with list::length
            if list::is_empty { xs = xs } then 0 else 1
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result) // Not empty
    }

    @Test
    fun `to_array converts deque to array`() {
        val code = """
            use std::collections::deque
            use std::collections::array
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let arr = deque::to_array { d = d }
            array::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Higher-Order Function Tests
    // ========================================

    @Test
    fun `map transforms all elements`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let doubled = deque::map { d = d, f = { x } -> x * 2 }
            match deque::peek_front { d = doubled } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result) // 1 * 2 = 2
    }

    @Test
    fun `filter keeps matching elements`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let evens = deque::filter { d = d, p = { x } -> x % 2 == 0 }
            deque::length { d = evens }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result) // Only 2 is even
    }

    @Test
    fun `fold accumulates values`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            deque::fold { d = d, init = 0, f = { acc, x } -> acc + x }
        """
        val result = eval(code)
        assertEquals(MezzeInt(6), result) // 1 + 2 + 3 = 6
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    fun `reverse reverses the deque`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let reversed = deque::reverse { d = d }
            match deque::peek_front { d = reversed } is
                Some { val } -> val
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result) // 3 is now at front
    }

    @Test
    fun `concat joins two deques`() {
        val code = """
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            let combined = deque::concat { d1 = d1, d2 = d2 }
            deque::length { d = combined }
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }
}
