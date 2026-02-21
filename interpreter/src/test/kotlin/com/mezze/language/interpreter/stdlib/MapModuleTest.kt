package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/map module.
 */
class MapModuleTest {

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

    // ===== Basic Operations =====

    @Test
    fun `test map empty`() {
        val result = eval("""
            use std::collections::map
            map::is_empty { m = map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test map insert and get`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "hello", v = 42 }
            map::get { m = m, k = "hello" }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("42"))
    }

    @Test
    fun `test map get missing key`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "hello", v = 42 }
            map::get { m = m, k = "world" }
        """)
        assertTrue(result.toString().contains("None"))
    }

    @Test
    fun `test map size`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            map::size { m = m3 }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test map contains_key - true`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "hello", v = 42 }
            map::contains_key { m = m, k = "hello" }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test map contains_key - false`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "hello", v = 42 }
            map::contains_key { m = m, k = "world" }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test map remove`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::remove { m = m2, k = "a" }
            map::size { m = m3 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test map is_empty - true`() {
        val result = eval("""
            use std::collections::map
            map::is_empty { m = map::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test map is_empty - false`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 1 }
            map::is_empty { m = m }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ===== Keys and Values =====

    @Test
    fun `test map keys`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let ks = map::keys { m = m2 }
            # Verify keys returns a list by matching on it
            match ks is
                Cons { head, tail } -> True
                Nil -> False
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test map values`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let vs = map::values { m = m2 }
            # Verify values returns a list by matching on it
            match vs is
                Cons { head, tail } -> True
                Nil -> False
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== Higher-Order Functions =====

    @Test
    fun `test map map_values`() {
        val result = eval("""
            use std::collections::map
            let double = {val} -> val * 2
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::map_values { m = m1, f = double }
            map::get { m = m2, k = "a" }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("20"))
    }

    @Test
    fun `test map filter`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let filtered = map::filter { m = m3, p = {k, v} -> v > 1 }
            map::size { m = filtered }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test map fold`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            map::fold { m = m3, init = 0, f = {acc, k, v} -> acc + v }
        """)
        assertEquals(MezzeInt(6), result)
    }

    @Test
    fun `test map from_keys`() {
        val result = eval("""
            use std::collections::map
            let ks = Cons { head = 1, tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } } }
            let m = map::from_keys { ks = ks, f = {val} -> val * 10 }
            map::size { m = m }
        """)
        assertEquals(MezzeInt(3), result)
    }

    // ===== Merge Operations =====

    @Test
    fun `test map merge`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 2 }
            let merged = map::merge { m1 = m1, m2 = m2 }
            map::size { m = merged }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test map merge_with`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 5 }
            let merged = map::merge_with { m1 = m1, m2 = m2, f = {l, r} -> l + r }
            map::get { m = merged, k = "a" }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("15"))
    }

    // ===== Utility Functions =====

    @Test
    fun `test map get_or_default - key exists`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 42 }
            map::get_or_default { m = m, k = "a", def = 0 }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test map get_or_default - key missing`() {
        val result = eval("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 42 }
            map::get_or_default { m = m, k = "b", def = 0 }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `test map update - existing key`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::update { m = m1, k = "a", f = {x} ->
                match x is
                    Some { val } -> Some { val = val + 5 }
                    None -> None
            }
            map::get { m = m2, k = "a" }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("15"))
    }

    @Test
    fun `test map remove_all`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let keys_to_remove = Cons { head = "a", tail = Cons { head = "c", tail = Nil } }
            let m4 = map::remove_all { m = m3, ks = keys_to_remove }
            map::size { m = m4 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test map equals - true`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 1 }
            map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test map equals - false`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 2 }
            map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ===== Integer Keys =====

    @Test
    fun `test map with integer keys`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = "one" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            map::get { m = m2, k = 1 }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("one"))
    }

    @Test
    fun `test map overwrite key`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "a", v = 99 }
            map::get { m = m2, k = "a" }
        """)
        assertTrue(result.toString().contains("Some") && result.toString().contains("99"))
    }
}
