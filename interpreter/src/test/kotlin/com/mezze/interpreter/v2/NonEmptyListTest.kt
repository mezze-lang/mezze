package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals

/**
 * Comprehensive tests for NonEmptyList operations
 * Tests the non-empty list that is guaranteed to have at least one element
 * Migrated from V1 to V2 with unboxed values.
 */
class NonEmptyListTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @DisplayName("NonEmptyList singleton")
    fun testSingleton() {
        val code = """
            use std::collections::nonemptylist::{singleton, head}

            let xs = singleton { x = 42 }

            head { xs = xs }
        """
        assertEquals(42L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList from head and tail")
    fun testFromHeadTail() {
        val code = """
            use std::collections::list::{empty}
            use std::collections::nonemptylist::{from_head_tail, head, length}

            let xs = from_head_tail { head = 1, tail = empty {} }
            let h = head { xs = xs }
            let len = length { xs = xs }

            if h == 1 && len == 1 then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList cons")
    fun testCons() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, head, length}

            let xs = singleton { x = 2 }
            let ys = cons { x = 1, xs = xs }
            let h = head { xs = ys }
            let len = length { xs = ys }

            if h == 1 && len == 2 then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList append")
    fun testAppend() {
        val code = """
            use std::collections::nonemptylist::{singleton, append, length}

            let xs = singleton { x = 1 }
            let ys = singleton { x = 2 }
            let zs = append { xs = xs, ys = ys }

            length { xs = zs }
        """
        assertEquals(2L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList map")
    fun testMap() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, map, head}

            let xs = cons { x = 1, xs = singleton { x = 2 } }
            let ys = map { xs = xs, f = {x} -> x * 2 }

            head { xs = ys }
        """
        assertEquals(2L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList reduce")
    fun testReduce() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, reduce}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }

            reduce { xs = xs, f = { acc, x } -> acc + x }
        """
        assertEquals(6L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList last")
    fun testLast() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, last}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }

            last { xs = xs }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList reverse")
    fun testReverse() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, reverse, head}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }
            let ys = reverse { xs = xs }

            head { xs = ys }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList to_list conversion")
    fun testToList() {
        val code = """
            use std::collections::nonemptylist::{singleton, to_list}
            use std::collections::list::{length}

            let nel = singleton { x = 42 }
            let xs = to_list { xs = nel }

            length { xs = xs }
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList any predicate")
    fun testAny() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, any}

            let xs = cons { x = 1, xs = cons { x = 5, xs = singleton { x = 3 } } }

            # Check if any element is greater than 4
            if any { xs = xs, p = {x} -> x > 4 } then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("NonEmptyList all predicate")
    fun testAll() {
        val code = """
            use std::collections::nonemptylist::{singleton, cons, all}

            let xs = cons { x = 2, xs = cons { x = 4, xs = singleton { x = 6 } } }

            # Check if all elements are even
            if all { xs = xs, p = {x} -> x % 2 == 0 } then 1 else 0
        """
        assertEquals(1L, eval(code))
    }
}
