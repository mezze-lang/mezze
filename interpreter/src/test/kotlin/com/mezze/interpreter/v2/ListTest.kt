package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals

/**
 * Comprehensive tests for List operations
 * Tests the immutable linked list backed by native implementations
 * Migrated from V1 to V2 with unboxed values.
 */
class ListTest {

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.TypeChecker.clearCache()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ============================================================================
    // Basic List Operations
    // ============================================================================

    @Test
    @DisplayName("Empty list creation")
    fun testEmptyList() {
        val code = """
            use std::collections::list::{empty, is_empty}

            let xs = empty {}
            let check = is_empty { xs = xs }

            if check then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List cons operation")
    fun testListCons() {
        val code = """
            use std::collections::list::{empty, cons, head}

            let xs = empty {}
            let ys = cons { head = 42, tail = xs }
            let h_opt = head { xs = ys }

            match h_opt is
                Some { val } -> val
                None -> 0
        """
        assertEquals(42L, eval(code))
    }

    @Test
    @DisplayName("List head and tail")
    fun testHeadTail() {
        val code = """
            use std::collections::list::{empty, cons, head, tail, is_empty}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }

            let check_h = { h } -> if h == 1 then True else False
            let check_h2 = { h2 } -> if h2 == 2 then True else False
            let check_empty = { t2 } -> is_empty { xs = t2 }

            match head { xs = xs } is
                Some { val } ->
                    if check_h { h = val } then
                        match tail { xs = xs } is
                            Some { val } ->
                                match head { xs = val } is
                                    Some { val } ->
                                        if check_h2 { h2 = val } then
                                            match tail { xs = xs } is
                                                Some { val } ->
                                                    match tail { xs = val } is
                                                        Some { val } ->
                                                            if check_empty { t2 = val } then 1 else 0
                                                        None -> 0
                                                None -> 0
                                        else 0
                                    None -> 0
                            None -> 0
                    else 0
                None -> 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List length")
    fun testListLength() {
        val code = """
            use std::collections::list::{empty, cons, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }

            length { xs = xs }
        """
        assertEquals(3L, eval(code))
    }

    // ============================================================================
    // List Construction
    // ============================================================================

    @Test
    @DisplayName("Singleton list")
    fun testSingleton() {
        val code = """
            use std::collections::list::{singleton, length}

            let xs = singleton { x = 42 }

            length { xs = xs }
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List append")
    fun testAppend() {
        val code = """
            use std::collections::list::{empty, cons, append, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 3, tail = cons { head = 4, tail = empty {} } }
            let zs = append { xs = xs, ys = ys }

            length { xs = zs }
        """
        assertEquals(4L, eval(code))
    }

    // ============================================================================
    // Higher-Order Functions
    // ============================================================================

    @Test
    @DisplayName("List map")
    fun testMap() {
        val code = """
            use std::collections::list::{empty, cons, map, head, tail}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let ys = map { xs = xs, f = { x } -> x * 2 }

            let check_h1 = { h1 } -> if h1 == 2 then True else False
            let check_h2 = { h2 } -> if h2 == 4 then True else False

            match head { xs = ys } is
                Some { val } ->
                    if check_h1 { h1 = val } then
                        match tail { xs = ys } is
                            Some { val } ->
                                match head { xs = val } is
                                    Some { val } ->
                                        if check_h2 { h2 = val } then 1 else 0
                                    None -> 0
                            None -> 0
                    else 0
                None -> 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List filter")
    fun testFilter() {
        val code = """
            use std::collections::list::{empty, cons, filter, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = cons { head = 4, tail = empty {} } } } }
            let ys = filter { xs = xs, p = { x } -> x > 2 }

            length { xs = ys }
        """
        assertEquals(2L, eval(code))
    }

    @Test
    @DisplayName("List fold_left")
    fun testFoldLeft() {
        val code = """
            use std::collections::list::{empty, cons, fold_left}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }

            fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """
        assertEquals(6L, eval(code))
    }

    // ============================================================================
    // List Predicates
    // ============================================================================

    @Test
    @DisplayName("List any")
    fun testAny() {
        val code = """
            use std::collections::list::{empty, cons, any}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let has_even = any { xs = xs, p = { x } -> x % 2 == 0 }

            if has_even then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List all")
    fun testAll() {
        val code = """
            use std::collections::list::{empty, cons, all}

            let xs = cons { head = 2, tail = cons { head = 4, tail = cons { head = 6, tail = empty {} } } }
            let all_even = all { xs = xs, p = { x } -> x % 2 == 0 }

            if all_even then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    // ============================================================================
    // List Transformations
    // ============================================================================

    @Test
    @DisplayName("List reverse")
    fun testReverse() {
        val code = """
            use std::collections::list::{empty, cons, reverse, head}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let ys = reverse { xs = xs }

            match head { xs = ys } is
                Some { val } -> val
                None -> 0
        """
        assertEquals(3L, eval(code))
    }

    // ============================================================================
    // Conversion Functions
    // ============================================================================

    @Test
    @DisplayName("List to array conversion")
    fun testToArray() {
        val code = """
            use std::collections::list::{empty, cons, to_array}
            use std::collections::array::{length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let arr = to_array { xs = xs }

            length { xs = arr }
        """
        assertEquals(3L, eval(code))
    }

    @Test
    @DisplayName("Array to list conversion")
    fun testFromArray() {
        val code = """
            use std::collections::array::{of}
            use std::collections::list::{from_array, length}

            let arr = of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
            let xs = from_array { xs = arr }

            length { xs = xs }
        """
        assertEquals(3L, eval(code))
    }

    // ============================================================================
    // List Utilities
    // ============================================================================

    @Test
    @DisplayName("List range")
    fun testRange() {
        val code = """
            use std::collections::list::{range, length}

            let xs = range { start = 0, end = 10 }

            length { xs = xs }
        """
        assertEquals(10L, eval(code))
    }

    @Test
    @DisplayName("List repeat")
    fun testRepeat() {
        val code = """
            use std::collections::list::{repeat, length}

            let xs = repeat { x = 42, n = 5 }

            length { xs = xs }
        """
        assertEquals(5L, eval(code))
    }

    // ============================================================================
    // List Equality (Automatic Derivation)
    // ============================================================================

    @Test
    @DisplayName("List equality - equal lists")
    fun testListEqualityTrue() {
        val code = """
            use std::collections::list::{empty, cons}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let eq_result = xs == ys

            if eq_result then 1 else 0
        """
        assertEquals(1L, eval(code))
    }

    @Test
    @DisplayName("List equality - different lists")
    fun testListEqualityFalse() {
        val code = """
            use std::collections::list::{empty, cons}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 1, tail = cons { head = 3, tail = empty {} } }
            let eq_result = xs == ys

            if eq_result then 1 else 0
        """
        assertEquals(0L, eval(code))
    }
}
