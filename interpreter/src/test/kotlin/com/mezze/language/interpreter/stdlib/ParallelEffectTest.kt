package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/parallel module.
 * Tests data parallel operations using Sequential handler for deterministic testing.
 */
class ParallelEffectTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === map Tests ===

    @Test
    fun `test map doubles each element`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.map {xs = [1, 2, 3], f = {x} -> x * 2}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(3, array.size())
        assertEquals(listOf(2L, 4L, 6L), array.toList().map { (it as MezzeInt).value })
    }

    @Test
    fun `test map with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let result = Parallel.map {xs = [], f = {x} -> x * 2}
                __native_arraylist_length {xs = result}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.map {xs = [3, 1, 4, 1, 5], f = {x} -> x + 10}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(listOf(13L, 11L, 14L, 11L, 15L), array.toList().map { (it as MezzeInt).value })
    }

    // === for_each Tests ===

    @Test
    fun `test for_each returns unit`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.for_each {xs = [1, 2, 3], f = {x} -> {}}
                42
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.filter {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2 == 0}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(listOf(2L, 4L, 6L), array.toList().map { (it as MezzeInt).value })
    }

    @Test
    fun `test filter with no matches`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let result = Parallel.filter {xs = [1, 3, 5], f = {x} -> x % 2 == 0}
                __native_arraylist_length {xs = result}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test filter with all matches`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let result = Parallel.filter {xs = [2, 4, 6], f = {x} -> x % 2 == 0}
                __native_arraylist_length {xs = result}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    // === reduce Tests ===

    @Test
    fun `test reduce with sum`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [1, 2, 3, 4, 5], f = {left, right} -> left + right} is
                    Some {val} -> val
                    None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    @Test
    fun `test reduce with product`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [1, 2, 3, 4], f = {left, right} -> left * right} is
                    Some {val} -> val
                    None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(24L, (result as MezzeInt).value)
    }

    @Test
    fun `test reduce with empty array returns None`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [], f = {left, right} -> left + right} is
                    Some {val} -> False
                    None -> True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test reduce with single element`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [42], f = {left, right} -> left + right} is
                    Some {val} -> val
                    None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === fold Tests ===

    @Test
    fun `test fold with sum`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [1, 2, 3],
                    zero = 0,
                    map_fn = {x} -> x,
                    combine = {left, right} -> left + right
                }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    @Test
    fun `test fold with transform and combine`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [1, 2, 3],
                    zero = 0,
                    map_fn = {x} -> x * x,
                    combine = {left, right} -> left + right
                }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(14L, (result as MezzeInt).value) // 1 + 4 + 9 = 14
    }

    @Test
    fun `test fold with empty array returns zero`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [],
                    zero = 100,
                    map_fn = {x} -> x,
                    combine = {left, right} -> left + right
                }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(100L, (result as MezzeInt).value)
    }

    // === both Tests ===

    @Test
    fun `test both runs two computations`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.both {
                    left = {} -> 1 + 2,
                    right = {} -> 3 * 4
                }
                r.left + r.right
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value) // 3 + 12 = 15
    }

    // === both3 Tests ===

    @Test
    fun `test both3 runs three computations`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.both3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                r.a + r.b + r.c
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    // === find Tests ===

    @Test
    fun `test find returns first match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.find {xs = [1, 2, 3, 4, 5], f = {x} -> x > 2} is
                    Some {val} -> val
                    None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test find returns None when no match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.find {xs = [1, 2, 3], f = {x} -> x > 10} is
                    Some {val} -> False
                    None -> True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === any Tests ===

    @Test
    fun `test any returns true when some match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [1, 2, 3, 4], f = {x} -> x > 3}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test any returns false when none match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [1, 2, 3], f = {x} -> x > 10}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test any with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [], f = {x} -> True}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === all Tests ===

    @Test
    fun `test all returns true when all match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [2, 4, 6], f = {x} -> x % 2 == 0}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test all returns false when some don't match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [2, 3, 6], f = {x} -> x % 2 == 0}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test all with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [], f = {x} -> False}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value) // vacuously true
    }

    // === partition Tests ===

    @Test
    fun `test partition separates by predicate`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.partition {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
                __native_arraylist_length {xs = r.matching}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value) // [2, 4]
    }

    @Test
    fun `test partition returns not_matching`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.partition {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
                __native_arraylist_length {xs = r.not_matching}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value) // [1, 3, 5]
    }

    // === group_by Tests ===

    @Test
    fun `test group_by groups by key`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let groups = Parallel.group_by {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2}
                __native_arraylist_length {xs = groups}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value) // 2 groups: even and odd
    }

    // === Helper Function Tests ===

    @Test
    fun `test sum helper`() {
        val result = eval("""
            use std::effects::parallel::{sum, Sequential}

            perform Sequential in sum {xs = [1, 2, 3, 4, 5]}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    @Test
    fun `test sum with empty array`() {
        val result = eval("""
            use std::effects::parallel::{sum, Sequential}

            perform Sequential in sum {xs = []}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test product helper`() {
        val result = eval("""
            use std::effects::parallel::{product, Sequential}

            perform Sequential in product {xs = [1, 2, 3, 4]}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(24L, (result as MezzeInt).value)
    }

    @Test
    fun `test product with empty array`() {
        val result = eval("""
            use std::effects::parallel::{product, Sequential}

            perform Sequential in product {xs = []}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `test count helper`() {
        val result = eval("""
            use std::effects::parallel::{count, Sequential}

            perform Sequential in count {xs = [1, 2, 3, 4, 5], f = {x} -> x > 2}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value) // 3, 4, 5
    }

    @Test
    fun `test flatten helper`() {
        val result = eval("""
            use std::effects::parallel::{flatten, Sequential}

            perform Sequential in do
                let result = flatten {xs = [[1, 2], [3], [4, 5, 6]]}
                __native_arraylist_length {xs = result}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    @Test
    fun `test flat_map helper`() {
        val result = eval("""
            use std::effects::parallel::{flat_map, Sequential}

            perform Sequential in do
                let result = flat_map {xs = [1, 2, 3], f = {x} -> [x, x * 10]}
                __native_arraylist_length {xs = result}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value) // [1,10,2,20,3,30]
    }
}
