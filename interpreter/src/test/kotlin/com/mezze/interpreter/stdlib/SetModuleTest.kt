package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/set module.
 * Migrated from V1 to V2 with unboxed values.
 */
class SetModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== Basic Operations =====

    @Test
    fun `test set empty`() {
        val result = eval("""
            use std::collections::set
            set::is_empty { s = set::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set insert and contains`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 42 }
            set::contains { s = s, x = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set contains - missing element`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 42 }
            set::contains { s = s, x = 99 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test set size`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = s1, x = 2 }
            let s3 = set::insert { s = s2, x = 3 }
            set::size { s = s3 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test set size - duplicates not counted`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = s1, x = 1 }
            let s3 = set::insert { s = s2, x = 1 }
            set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test set remove`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = s1, x = 2 }
            let s3 = set::remove { s = s2, x = 1 }
            set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test set is_empty - true`() {
        val result = eval("""
            use std::collections::set
            set::is_empty { s = set::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set is_empty - false`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 1 }
            set::is_empty { s = s }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test set singleton`() {
        val result = eval("""
            use std::collections::set
            let s = set::singleton { x = 42 }
            set::size { s = s }
        """)
        assertEquals(1L, result)
    }

    // ===== Set Operations =====

    @Test
    fun `test set union`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 2 }, x = 3 }
            let s3 = set::union { s1 = s1, s2 = s2 }
            set::size { s = s3 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test set intersection`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 2 }, x = 3 }
            let s3 = set::intersection { s1 = s1, s2 = s2 }
            set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test set difference`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::empty {}, x = 2 }
            let s3 = set::difference { s1 = s1, s2 = s2 }
            set::size { s = s3 }
        """)
        assertEquals(1L, result)
    }

    // ===== Set Queries =====

    @Test
    fun `test set is_subset - true`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            set::is_subset { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set is_subset - false`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 3 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            set::is_subset { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test set is_superset - true`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::empty {}, x = 1 }
            set::is_superset { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set is_disjoint - true`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = set::empty {}, x = 2 }
            set::is_disjoint { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set is_disjoint - false`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::empty {}, x = 2 }
            set::is_disjoint { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    // ===== Conversion Functions =====

    @Test
    fun `test set to_seq`() {
        val result = eval("""
            use std::collections::set
            use std::collections::seq
            let s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let arr = set::to_seq { s = s }
            seq::size { xs = arr }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test set from_seq`() {
        val result = eval("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 2, 1] }
            set::size { s = s }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test set to_list`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let xs = set::to_list { s = s }
            match xs is
                Cons { head, tail } -> True
                Nil -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set from_list`() {
        val result = eval("""
            use std::collections::set
            let xs = Cons { head = 1, tail = Cons { head = 2, tail = Cons { head = 1, tail = Nil } } }
            let s = set::from_list { xs = xs }
            set::size { s = s }
        """)
        assertEquals(2L, result)
    }

    // ===== Higher-Order Functions =====

    @Test
    fun `test set filter`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }, x = 3 }
            let s2 = set::filter { s = s1, p = {val} -> val > 1 }
            set::size { s = s2 }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test set map`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::map { s = s1, f = {val} -> val * 10 }
            set::contains { s = s2, x = 10 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set fold`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }, x = 3 }
            set::fold { s = s, init = 0, f = {acc, x} -> acc + x }
        """)
        assertEquals(6L, result)
    }

    // ===== Bulk Operations =====

    @Test
    fun `test set insert_all`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert_all { s = s1, xs = [2, 3, 4] }
            set::size { s = s2 }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test set remove_all`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }, x = 3 }
            let s2 = set::remove_all { s = s1, xs = [1, 3] }
            set::size { s = s2 }
        """)
        assertEquals(1L, result)
    }

    // ===== Equality =====

    @Test
    fun `test set equals - true`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 2 }, x = 1 }
            set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test set equals - false`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 3 }
            set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    // ===== String Keys =====

    @Test
    fun `test set with string elements`() {
        val result = eval("""
            use std::collections::set
            let s = set::insert { s = set::insert { s = set::empty {}, x = "hello" }, x = "world" }
            set::contains { s = s, x = "hello" }
        """)
        assertEquals(true, result)
    }
}
