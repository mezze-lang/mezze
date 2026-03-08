package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/sorted_set module.
 * Migrated from V1 to V2 with unboxed values.
 */
class SortedSetModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== Construction =====

    @Test
    fun `test sorted_set empty`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::is_empty { s = sorted_set::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set singleton`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 42 }
            sorted_set::size { s = s }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set from_list`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::list
            let xs = list::cons { head = 3, tail = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } } }
            let s = sorted_set::from_list { xs = xs }
            sorted_set::size { s = s }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test sorted_set from_list removes duplicates`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } } }
            let s = sorted_set::from_list { xs = xs }
            sorted_set::size { s = s }
        """)
        assertEquals(2L, result)
    }

    // ===== Basic Operations =====

    @Test
    fun `test sorted_set add`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::empty {}
            let s2 = sorted_set::add { s = s1, x = 42 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set add duplicate`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::add { s = s1, x = 42 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set remove`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::remove { s = s1, x = 1 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set remove non-existent`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 42 }
            let s2 = sorted_set::remove { s = s1, x = 99 }
            sorted_set::size { s = s2 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set contains - true`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::add { s = sorted_set::empty {}, x = 42 }
            sorted_set::contains { s = s, x = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set contains - false`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::add { s = sorted_set::empty {}, x = 42 }
            sorted_set::contains { s = s, x = 99 }
        """)
        assertEquals(false, result)
    }

    // ===== Query =====

    @Test
    fun `test sorted_set size`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let s2 = sorted_set::add { s = s1, x = 2 }
            let s3 = sorted_set::add { s = s2, x = 3 }
            sorted_set::size { s = s3 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test sorted_set is_empty - true`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::is_empty { s = sorted_set::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set is_empty - false`() {
        val result = eval("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 1 }
            sorted_set::is_empty { s = s }
        """)
        assertEquals(false, result)
    }

    // ===== Set Operations =====

    @Test
    fun `test sorted_set union`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 2 }, x = 3 }
            let s3 = sorted_set::union { s1 = s1, s2 = s2 }
            sorted_set::size { s = s3 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test sorted_set intersect`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 2 }, x = 3 }
            let s3 = sorted_set::intersect { s1 = s1, s2 = s2 }
            sorted_set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set intersect empty`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let s2 = sorted_set::add { s = sorted_set::empty {}, x = 2 }
            let s3 = sorted_set::intersect { s1 = s1, s2 = s2 }
            sorted_set::is_empty { s = s3 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set difference`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::empty {}, x = 2 }
            let s3 = sorted_set::difference { s1 = s1, s2 = s2 }
            sorted_set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set difference contains correct element`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::empty {}, x = 2 }
            let s3 = sorted_set::difference { s1 = s1, s2 = s2 }
            sorted_set::contains { s = s3, x = 1 }
        """)
        assertEquals(true, result)
    }

    // ===== Min/Max Operations =====

    @Test
    fun `test sorted_set min`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 5 }
            let s2 = sorted_set::add { s = s1, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 10 }
            match sorted_set::min { s = s3 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set min empty`() {
        val result = eval("""
            use std::collections::sorted_set
            match sorted_set::min { s = sorted_set::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set max`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 5 }
            let s2 = sorted_set::add { s = s1, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 10 }
            match sorted_set::max { s = s3 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test sorted_set max empty`() {
        val result = eval("""
            use std::collections::sorted_set
            match sorted_set::max { s = sorted_set::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    // ===== Conversions =====

    @Test
    fun `test sorted_set to_list`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 3 }
            let s2 = sorted_set::add { s = s1, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let xs = sorted_set::to_list { s = s3 }
            match xs is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_set from_set`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::set
            let s = set::insert { s = set::insert { s = set::empty {}, x = 2 }, x = 1 }
            let ss = sorted_set::from_set { s = s }
            sorted_set::size { s = ss }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test sorted_set to_set`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::set
            let ss1 = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let ss2 = sorted_set::add { s = ss1, x = 2 }
            let s = sorted_set::to_set { s = ss2 }
            set::size { s = s }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test sorted_set from_set min check`() {
        val result = eval("""
            use std::collections::sorted_set
            use std::collections::set
            let s = set::insert { s = set::insert { s = set::insert { s = set::empty {}, x = 3 }, x = 1 }, x = 2 }
            let ss = sorted_set::from_set { s = s }
            match sorted_set::min { s = ss } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(1L, result)
    }

    // ===== Equality =====

    @Test
    fun `test sorted_set equals - true`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 2 }, x = 1 }
            sorted_set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_set equals - false`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let s2 = sorted_set::add { s = sorted_set::empty {}, x = 2 }
            sorted_set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test sorted_set equals - empty sets`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::equals { s1 = sorted_set::empty {}, s2 = sorted_set::empty {} }
        """)
        assertEquals(true, result)
    }

    // ===== String Elements =====

    @Test
    fun `test sorted_set with string elements`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = "b" }
            let s2 = sorted_set::add { s = s1, x = "a" }
            let s3 = sorted_set::add { s = s2, x = "c" }
            match sorted_set::min { s = s3 } is
                Some { val } -> val
                None -> "not found"
        """)
        assertEquals("a", result)
    }

    @Test
    fun `test sorted_set with string elements max`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = "b" }
            let s2 = sorted_set::add { s = s1, x = "a" }
            let s3 = sorted_set::add { s = s2, x = "c" }
            match sorted_set::max { s = s3 } is
                Some { val } -> val
                None -> "not found"
        """)
        assertEquals("c", result)
    }
}
