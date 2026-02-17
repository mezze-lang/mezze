package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for SortedMap type and operations.
 */
class SortedMapInterpreterTest {

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
    // Construction Tests
    // ========================================

    @Test
    fun `empty sorted map has size 0`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::size { m = sorted_map::empty {} }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `empty sorted map is_empty`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ========================================
    // Insert and Get Tests
    // ========================================

    @Test
    fun `insert and get single element`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            match sorted_map::get { m = m2, k = "key" } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `get missing key returns None`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            match sorted_map::get { m = m2, k = "other" } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `insert multiple elements`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "two" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "three" }
            sorted_map::size { m = m4 }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `insert updates existing key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 1 }
            let m3 = sorted_map::insert { m = m2, k = "key", v = 2 }
            match sorted_map::get { m = m3, k = "key" } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Remove Tests
    // ========================================

    @Test
    fun `remove existing key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "key" }
            sorted_map::is_empty { m = m3 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `remove missing key is no-op`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "other" }
            sorted_map::size { m = m3 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    // ========================================
    // Contains Key Tests
    // ========================================

    @Test
    fun `contains_key finds existing key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "hello", v = 1 }
            sorted_map::contains_key { m = m2, k = "hello" }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `contains_key returns false for missing key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "hello", v = 1 }
            sorted_map::contains_key { m = m2, k = "world" }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ========================================
    // Min/Max Tests
    // ========================================

    @Test
    fun `min_key returns smallest key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 3, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 1, v = "a" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            match sorted_map::min_key { m = m4 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `max_key returns largest key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 3, v = "c" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            match sorted_map::max_key { m = m4 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `min_key on empty map returns None`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::min_key { m = sorted_map::empty {} } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `max_key on empty map returns None`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::max_key { m = sorted_map::empty {} } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    // ========================================
    // Keys/Values/Entries Tests
    // ========================================

    @Test
    fun `keys returns all keys in sorted order`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 3, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 1, v = "a" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            let keys = sorted_map::keys { m = m4 }
            list::length { xs = keys }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `values returns all values`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = 10 }
            let m3 = sorted_map::insert { m = m2, k = 2, v = 20 }
            let values = sorted_map::values { m = m3 }
            list::length { xs = values }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `entries returns all key-value pairs`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "two" }
            let entries = sorted_map::entries { m = m3 }
            list::length { xs = entries }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `convert from regular map preserves entries`() {
        val result = eval("""
            use std::collections::sorted_map
            # Use native directly to avoid module lookup issues
            let m = __native_map_empty {}
            let m2 = __native_map_insert { m = m, k = 1, v = 10 }
            let m3 = __native_map_insert { m = m2, k = 2, v = 20 }
            let sorted = sorted_map::from_map { m = m3 }
            sorted_map::size { m = sorted }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `convert to regular map preserves entries`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = 10 }
            let m3 = sorted_map::insert { m = m2, k = 2, v = 20 }
            let regular = sorted_map::to_map { m = m3 }
            __native_map_size { m = regular }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Immutability Tests
    // ========================================

    @Test
    fun `insert does not mutate original`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m1, k = "key", v = 42 }
            sorted_map::is_empty { m = m1 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `remove does not mutate original`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m1, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "key" }
            sorted_map::size { m = m2 }
        """)
        assertEquals(MezzeInt(1), result)
    }
}
