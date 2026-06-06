package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/sorted_map module.
 * Migrated from V1 to V2 with unboxed values.
 */
class SortedMapModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== Construction =====

    @Test
    fun `test sorted_map empty`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
        """)
        assertEquals(true, result)
    }

    // ===== Basic Operations =====

    @Test
    fun `test sorted_map insert and get`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            match sorted_map::get { m = m, k = 1 } is
                Some { val } -> val
                None -> "not found"
        """)
        assertEquals("one", result)
    }

    @Test
    fun `test sorted_map get missing key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            match sorted_map::get { m = m, k = 99 } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map remove`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 2, v = "two" }
            let m3 = sorted_map::remove { m = m2, k = 1 }
            sorted_map::size { m = m3 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_map contains_key - true`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::contains_key { m = m, k = 1 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map contains_key - false`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::contains_key { m = m, k = 99 }
        """)
        assertEquals(false, result)
    }

    // ===== Query =====

    @Test
    fun `test sorted_map size`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 2, v = "two" }
            let m3 = sorted_map::insert { m = m2, k = 3, v = "three" }
            sorted_map::size { m = m3 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test sorted_map is_empty - true`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map is_empty - false`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::is_empty { m = m }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test sorted_map keys`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 3, v = "three" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "two" }
            let ks = sorted_map::keys { m = m3 }
            match ks is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_map values`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 2, v = "two" }
            let vs = sorted_map::values { m = m2 }
            match vs is
                Cons { head, tail } -> True
                Nil -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map entries`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 2, v = "two" }
            let es = sorted_map::entries { m = m2 }
            match es is
                Cons { head, tail } -> head.k
                Nil -> 0
        """)
        assertEquals(1L, result)
    }

    // ===== Min/Max Operations =====

    @Test
    fun `test sorted_map min_key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::min_key { m = m3 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test sorted_map min_key empty`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::min_key { m = sorted_map::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map max_key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::max_key { m = m3 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test sorted_map max_key empty`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::max_key { m = sorted_map::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map min_entry`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::min_entry { m = m3 } is
                Some { val } -> val.v
                None -> "not found"
        """)
        assertEquals("one", result)
    }

    @Test
    fun `test sorted_map max_entry`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::max_entry { m = m3 } is
                Some { val } -> val.v
                None -> "not found"
        """)
        assertEquals("ten", result)
    }

    // ===== Conversions =====

    @Test
    fun `test sorted_map from_map`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::empty {}, k = 2, v = "two" }, k = 1, v = "one" }
            let sm = sorted_map::from_map { m = m }
            sorted_map::size { m = sm }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test sorted_map to_map`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::map
            let sm1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let sm2 = sorted_map::insert { m = sm1, k = 2, v = "two" }
            let m = sorted_map::to_map { m = sm2 }
            map::size { m = m }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test sorted_map from_map preserves sorted order`() {
        val result = eval("""
            use std::collections::sorted_map
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = 3, v = "three" }, k = 1, v = "one" }, k = 2, v = "two" }
            let sm = sorted_map::from_map { m = m }
            match sorted_map::min_key { m = sm } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(1L, result)
    }

    // ===== Equality =====

    @Test
    fun `test sorted_map equals - true`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }, k = 2, v = "two" }
            let m2 = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = "two" }, k = 1, v = "one" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test sorted_map equals - false different values`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "ONE" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test sorted_map equals - false different keys`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = "one" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test sorted_map equals - empty maps`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::equals { m1 = sorted_map::empty {}, m2 = sorted_map::empty {} }
        """)
        assertEquals(true, result)
    }

    // ===== String Keys =====

    @Test
    fun `test sorted_map with string keys`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = "b", v = 2 }
            let m2 = sorted_map::insert { m = m1, k = "a", v = 1 }
            let m3 = sorted_map::insert { m = m2, k = "c", v = 3 }
            match sorted_map::min_key { m = m3 } is
                Some { val } -> val
                None -> "not found"
        """)
        assertEquals("a", result)
    }

    // ===== Overwrite Key =====

    @Test
    fun `test sorted_map overwrite key`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "ONE" }
            match sorted_map::get { m = m2, k = 1 } is
                Some { val } -> val
                None -> "not found"
        """)
        assertEquals("ONE", result)
    }
}
