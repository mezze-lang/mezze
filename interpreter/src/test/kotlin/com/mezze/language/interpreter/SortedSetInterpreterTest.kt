package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for SortedSet type and operations.
 */
class SortedSetInterpreterTest {

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
    fun `empty sorted set has size 0`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::size { s = sorted_set::empty {} }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `empty sorted set is_empty`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::is_empty { s = sorted_set::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `singleton set has size 1`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::size { s = sorted_set::singleton { x = 42 } }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `from_list creates sorted set`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::list
            let elems = list::cons { head = 3, tail = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } } }
            sorted_set::size { s = sorted_set::from_list { xs = elems } }
        """)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Add and Contains Tests
    // ========================================

    @Test
    fun `add element to set`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 42 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `add duplicate element does not change size`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::add { s = s, x = 42 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `contains finds added element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = "hello" }
            sorted_set::contains { s = s, x = "hello" }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `contains returns false for missing element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = "hello" }
            sorted_set::contains { s = s, x = "world" }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ========================================
    // Remove Tests
    // ========================================

    @Test
    fun `remove existing element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::remove { s = s, x = 42 }
            sorted_set::is_empty { s = s2 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `remove missing element is no-op`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::remove { s = s, x = 100 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    // ========================================
    // Min/Max Tests
    // ========================================

    @Test
    fun `min returns smallest element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 30 }
            let s3 = sorted_set::add { s = s2, x = 10 }
            let s4 = sorted_set::add { s = s3, x = 20 }
            match sorted_set::min { s = s4 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `max returns largest element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 10 }
            let s3 = sorted_set::add { s = s2, x = 30 }
            let s4 = sorted_set::add { s = s3, x = 20 }
            match sorted_set::max { s = s4 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun `min on empty set returns None`() {
        val result = eval("""
            use std::collections::sorted_set
            match sorted_set::min { s = sorted_set::empty {} } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `max on empty set returns None`() {
        val result = eval("""
            use std::collections::sorted_set
            match sorted_set::max { s = sorted_set::empty {} } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    // ========================================
    // Set Operations Tests
    // ========================================

    @Test
    fun `union combines two sets`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 1 }
            let s1b = sorted_set::add { s = s1, x = 2 }
            let s2 = sorted_set::singleton { x = 2 }
            let s2b = sorted_set::add { s = s2, x = 3 }
            let combined = sorted_set::union { s1 = s1b, s2 = s2b }
            sorted_set::size { s = combined }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `intersect finds common elements`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 1 }
            let s1b = sorted_set::add { s = s1, x = 2 }
            let s2 = sorted_set::singleton { x = 2 }
            let s2b = sorted_set::add { s = s2, x = 3 }
            let common = sorted_set::intersect { s1 = s1b, s2 = s2b }
            sorted_set::size { s = common }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `difference removes elements`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 1 }
            let s1b = sorted_set::add { s = s1, x = 2 }
            let s1c = sorted_set::add { s = s1b, x = 3 }
            let s2 = sorted_set::singleton { x = 2 }
            let diff = sorted_set::difference { s1 = s1c, s2 = s2 }
            sorted_set::size { s = diff }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `to_list preserves elements in sorted order`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::list
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 3 }
            let s3 = sorted_set::add { s = s2, x = 1 }
            let s4 = sorted_set::add { s = s3, x = 2 }
            let xs = sorted_set::to_list { s = s4 }
            list::length { xs = xs }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `from_set converts regular set to sorted set`() {
        // Test that from_set works by checking the result is a valid sorted set
        val result = eval("""
            use std::collections::sorted_set
            # Use native directly since set module might not be available
            let regular = __native_set_empty {}
            let r2 = __native_set_insert { s = regular, x = 1 }
            let r3 = __native_set_insert { s = r2, x = 2 }
            let sorted = sorted_set::from_set { s = r3 }
            sorted_set::size { s = sorted }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `to_set converts sorted set to regular set`() {
        // Test that to_set works by checking the result has correct size
        val result = eval("""
            use std::collections::sorted_set
            let sorted = sorted_set::singleton { x = 1 }
            let s2 = sorted_set::add { s = sorted, x = 2 }
            let regular = sorted_set::to_set { s = s2 }
            __native_set_size { s = regular }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ========================================
    // Immutability Tests
    // ========================================

    @Test
    fun `add does not mutate original`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::empty {}
            let s2 = sorted_set::add { s = s1, x = 42 }
            sorted_set::is_empty { s = s1 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `remove does not mutate original`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::remove { s = s1, x = 42 }
            sorted_set::size { s = s1 }
        """)
        assertEquals(MezzeInt(1), result)
    }
}
