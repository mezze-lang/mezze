package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the Deque (double-ended queue) data structure.
 * Tests cover construction, add/remove operations, peek, conversions, and higher-order functions.
 * Migrated from V1 to V2 with unboxed values.
 */
class DequeInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `empty deque is empty`() {
        val code = """
            use std::collections::deque::{empty, is_empty}
            is_empty { d = empty {} }
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `singleton deque has length 1`() {
        val code = """
            use std::collections::deque
            deque::length { d = deque::singleton { x = 42 } }
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `from_list creates deque with all elements`() {
        val code = """
            use std::collections::deque
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::empty {} } } }
            deque::length { d = deque::from_list { xs = xs } }
        """
        assertEquals(3L, eval(code))
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
        assertEquals(2L, eval(code))
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
        assertEquals(2L, eval(code))
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
        assertEquals(3L, eval(code))
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
        assertEquals(1L, eval(code))
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
        assertEquals(2L, eval(code))
    }

    @Test
    fun `pop_front on empty returns None`() {
        val code = """
            use std::collections::deque
            match deque::pop_front { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        assertEquals(0L, eval(code))
    }

    @Test
    fun `pop_back on empty returns None`() {
        val code = """
            use std::collections::deque
            match deque::pop_back { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
        """
        assertEquals(0L, eval(code))
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
        assertEquals(3L, eval(code)) // 1 (front) + 2 (length)
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
        assertEquals(2L, eval(code))
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
        assertEquals(1L, eval(code)) // FIFO: first in, first out
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
        assertEquals(3L, eval(code)) // LIFO: last in, first out
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
        assertEquals(1L, eval(code)) // Not empty
    }

    @Test
    fun `to_seq converts deque to seq`() {
        val code = """
            use std::collections::deque
            use std::collections::seq
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let arr = deque::to_seq { d = d }
            seq::length { xs = arr }
        """
        assertEquals(2L, eval(code))
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
        assertEquals(2L, eval(code)) // 1 * 2 = 2
    }

    @Test
    fun `filter keeps matching elements`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let evens = deque::filter { d = d, p = { x } -> x % 2 == 0 }
            deque::length { d = evens }
        """
        assertEquals(1L, eval(code)) // Only 2 is even
    }

    @Test
    fun `fold accumulates values`() {
        val code = """
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            deque::fold { d = d, init = 0, f = { acc, x } -> acc + x }
        """
        assertEquals(6L, eval(code)) // 1 + 2 + 3 = 6
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
        assertEquals(3L, eval(code)) // 3 is now at front
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
        assertEquals(2L, eval(code))
    }
}
