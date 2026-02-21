package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/sorted_map module.
 */
class SortedMapModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ===== Construction =====

    @Test
    fun `test sorted_map empty`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeString("one"), result)
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
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test sorted_map contains_key - true`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::contains_key { m = m, k = 1 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test sorted_map contains_key - false`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::contains_key { m = m, k = 99 }
        """)
        assertEquals(MezzeBool(false), result)
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
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test sorted_map is_empty - true`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test sorted_map is_empty - false`() {
        val result = eval("""
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            sorted_map::is_empty { m = m }
        """)
        assertEquals(MezzeBool(false), result)
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
        assertEquals(MezzeInt(1), result)
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
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test sorted_map entries`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = m1, k = 2, v = "two" }
            let es = sorted_map::entries { m = m2 }
            match es is
                Cons { head, tail } -> head.key
                Nil -> 0
        """)
        assertEquals(MezzeInt(1), result)
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
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test sorted_map min_key empty`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::min_key { m = sorted_map::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `test sorted_map max_key empty`() {
        val result = eval("""
            use std::collections::sorted_map
            match sorted_map::max_key { m = sorted_map::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test sorted_map min_entry`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::min_entry { m = m3 } is
                Some { val } -> val.value
                None -> "not found"
        """)
        assertEquals(MezzeString("one"), result)
    }

    @Test
    fun `test sorted_map max_entry`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 5, v = "five" }
            let m2 = sorted_map::insert { m = m1, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            match sorted_map::max_entry { m = m3 } is
                Some { val } -> val.value
                None -> "not found"
        """)
        assertEquals(MezzeString("ten"), result)
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
        assertEquals(MezzeInt(2), result)
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
        assertEquals(MezzeInt(2), result)
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
        assertEquals(MezzeInt(1), result)
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
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test sorted_map equals - false different values`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "ONE" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test sorted_map equals - false different keys`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "one" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = "one" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test sorted_map equals - empty maps`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::equals { m1 = sorted_map::empty {}, m2 = sorted_map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeString("a"), result)
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
        assertEquals(MezzeString("ONE"), result)
    }
}
