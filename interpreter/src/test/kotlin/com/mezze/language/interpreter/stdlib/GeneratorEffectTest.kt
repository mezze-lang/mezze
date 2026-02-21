package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/generator module.
 * Tests generator/yield pattern for lazy sequences.
 */
class GeneratorEffectTest {

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

    // === Basic Yield Tests ===

    @Test
    fun `test basic yield creates suspended state`() {
        val result = eval("""
            use std::effects::generator::{yield}

            let test = {} -> do
                yield { value = 42 }

            test {}
        """)
        assertTrue(result is MezzeContinuationSuspended)
    }

    @Test
    fun `test yield with integer value`() {
        val result = eval("""
            use std::effects::generator::{yield}

            let test = {} -> do
                yield { value = 123 }

            test {}
        """)
        assertTrue(result is MezzeContinuationSuspended)
        assertEquals(123L, ((result as MezzeContinuationSuspended).suspendedValue as MezzeInt).value)
    }

    @Test
    fun `test yield with string value`() {
        val result = eval("""
            use std::effects::generator::{yield, to_list}

            let gen = {} -> do
                yield { value = "hello" }

            let g = gen {}
            to_list { gen = g }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals("hello", (list.get(0) as MezzeString).value)
    }

    // === next Function Tests ===

    @Test
    fun `test next returns Some for valid generator`() {
        val result = eval("""
            use std::effects::generator::{yield, next}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            let result = next { gen = g }
            result.value
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test next extracts yielded value`() {
        val result = eval("""
            use std::effects::generator::{yield, next}

            let gen = {} -> do
                yield { value = 999 }

            let g = gen {}
            let result = next { gen = g }
            match result.value is
                Some { val } -> val
                None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(999L, (result as MezzeInt).value)
    }

    @Test
    fun `test next on exhausted generator returns None`() {
        val result = eval("""
            use std::effects::generator::{next}

            let result = next { gen = {} }
            result.value
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructor)
    }

    // === to_list Function Tests ===

    @Test
    fun `test to_list with single yield`() {
        val result = eval("""
            use std::effects::generator::{yield, to_list}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            to_list { gen = g }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals(42L, (list.get(0) as MezzeInt).value)
    }

    @Test
    fun `test to_list with boolean value`() {
        val result = eval("""
            use std::effects::generator::{yield, to_list}

            let gen = {} -> do
                yield { value = True }

            let g = gen {}
            to_list { gen = g }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertTrue((list.get(0) as MezzeBool).value)
    }

    // === take Function Tests ===

    @Test
    fun `test take with n equals 0`() {
        val result = eval("""
            use std::effects::generator::{yield, take}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            let result = take { gen = g, n = 0 }
            result.values
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(0, list.size())
    }

    @Test
    fun `test take with n equals 1`() {
        val result = eval("""
            use std::effects::generator::{yield, take}

            let gen = {} -> do
                yield { value = 77 }

            let g = gen {}
            let result = take { gen = g, n = 1 }
            result.values
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals(77L, (list.get(0) as MezzeInt).value)
    }

    @Test
    fun `test take with n larger than available`() {
        val result = eval("""
            use std::effects::generator::{yield, take}

            let gen = {} -> do
                yield { value = 1 }

            let g = gen {}
            let result = take { gen = g, n = 10 }
            result.values
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
    }

    // === map Function Tests ===

    @Test
    fun `test map transforms values`() {
        val result = eval("""
            use std::effects::generator::{yield, map}

            let gen = {} -> do
                yield { value = 5 }

            let g = gen {}
            map { gen = g, f = { x } -> x * 2 }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals(10L, (list.get(0) as MezzeInt).value)
    }

    // === filter Function Tests ===

    @Test
    fun `test filter keeps matching values`() {
        val result = eval("""
            use std::effects::generator::{yield, filter}

            let gen = {} -> do
                yield { value = 10 }

            let g = gen {}
            filter { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals(10L, (list.get(0) as MezzeInt).value)
    }

    @Test
    fun `test filter removes non-matching values`() {
        val result = eval("""
            use std::effects::generator::{yield, filter}

            let gen = {} -> do
                yield { value = 3 }

            let g = gen {}
            filter { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(0, list.size())
    }

    // === fold Function Tests ===

    @Test
    fun `test fold accumulates value`() {
        val result = eval("""
            use std::effects::generator::{yield, fold}

            let gen = {} -> do
                yield { value = 10 }

            let g = gen {}
            fold { gen = g, init = 5, f = { acc, x } -> acc + x }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    // === count Function Tests ===

    @Test
    fun `test count returns number of values`() {
        val result = eval("""
            use std::effects::generator::{yield, count}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            count { gen = g }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    // === find Function Tests ===

    @Test
    fun `test find returns Some when found`() {
        val result = eval("""
            use std::effects::generator::{yield, find}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            find { gen = g, p = { x } -> x == 42 }
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test find returns None when not found`() {
        val result = eval("""
            use std::effects::generator::{yield, find}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            find { gen = g, p = { x } -> x == 100 }
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructor)
    }

    // === any Function Tests ===

    @Test
    fun `test any returns true when predicate matches`() {
        val result = eval("""
            use std::effects::generator::{yield, any}

            let gen = {} -> do
                yield { value = 10 }

            let g = gen {}
            any { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test any returns false when no match`() {
        val result = eval("""
            use std::effects::generator::{yield, any}

            let gen = {} -> do
                yield { value = 3 }

            let g = gen {}
            any { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === all Function Tests ===

    @Test
    fun `test all returns true when all match`() {
        val result = eval("""
            use std::effects::generator::{yield, all}

            let gen = {} -> do
                yield { value = 10 }

            let g = gen {}
            all { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test all returns false when one does not match`() {
        val result = eval("""
            use std::effects::generator::{yield, all}

            let gen = {} -> do
                yield { value = 3 }

            let g = gen {}
            all { gen = g, p = { x } -> x > 5 }
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === Generator with Computation ===

    @Test
    fun `test generator with arithmetic computation`() {
        val result = eval("""
            use std::effects::generator::{yield, to_list}

            let mathGen = { x } -> do
                let squared = x * x
                let doubled = x * 2
                let combined = squared + doubled
                yield { value = combined }

            let g = mathGen { x = 5 }
            to_list { gen = g }
        """)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(1, list.size())
        assertEquals(35L, (list.get(0) as MezzeInt).value)
    }

    // === Multiple Generator Instances ===

    @Test
    fun `test multiple independent generators`() {
        val result = eval("""
            use std::effects::generator::{yield, next}

            let makeGen = { value } -> do
                yield { value = value }

            let g1 = makeGen { value = 10 }
            let g2 = makeGen { value = 20 }
            let g3 = makeGen { value = 30 }

            let r1 = next { gen = g1 }
            let r2 = next { gen = g2 }
            let r3 = next { gen = g3 }

            let v1 = match r1.value is
                Some { val } -> val
                None -> 0
            let v2 = match r2.value is
                Some { val } -> val
                None -> 0
            let v3 = match r3.value is
                Some { val } -> val
                None -> 0

            v1 + v2 + v3
        """)
        assertTrue(result is MezzeInt)
        assertEquals(60L, (result as MezzeInt).value)
    }
}
