package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/choice module.
 * Tests nondeterministic choice with backtracking using AllPaths handler.
 */
class ChoiceEffectTest {

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

    // === Basic Choice Tests ===

    @Test
    fun `test basic choose from two options`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                Choice.choose { options = [1, 2] }

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(2, array.size())
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(1L, 2L), values)
    }

    @Test
    fun `test choose from three options`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                Choice.choose { options = [10, 20, 30] }

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(3, array.size())
    }

    // === Multiple Choices (Cartesian Product) ===

    @Test
    fun `test multiple choices produces cartesian product`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2] }
                let y = Choice.choose { options = [10, 20] }
                x + y

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(4, array.size())
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(11L, 12L, 21L, 22L), values)
    }

    // === Fail/Backtracking Tests ===

    @Test
    fun `test fail filters out paths`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2, 3] }
                match x % 2 is
                    0 -> Choice.fail {}
                    _ -> x * 10

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(10L, 30L), values)
    }

    @Test
    fun `test fail with conditional guard`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2, 3, 4, 5] }
                match x > 2 is
                    True -> x
                    False -> Choice.fail {}

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(3L, 4L, 5L), values)
    }

    // === Empty Choice Test ===

    @Test
    fun `test empty choice aborts`() {
        var exceptionThrown = false
        try {
            eval("""
                use std::effects::choice::{Choice, AllPaths}

                let computation = {} -> perform AllPaths in do
                    Choice.choose { options = [] }

                computation {}
            """)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    // === Choice with Records ===

    @Test
    fun `test choice producing records`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let color = Choice.choose { options = ["red", "blue"] }
                let size = Choice.choose { options = [1, 2] }
                {color = color, size = size}

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(4, array.size())
        array.elements.forEach { element ->
            assertTrue(element is MezzeRecord)
            val record = element as MezzeRecord
            assertTrue(record.fields.containsKey("color"))
            assertTrue(record.fields.containsKey("size"))
        }
    }

    // === run_all_paths Helper ===

    @Test
    fun `test run_all_paths helper`() {
        val result = eval("""
            use std::effects::choice::{Choice, run_all_paths}

            let results = run_all_paths { computation = {} -> do
                let x = Choice.choose { options = [1, 2] }
                x
            }
            results
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(2, array.size())
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(1L, 2L), values)
    }

    // === Choice with Pure Functions ===

    @Test
    fun `test choice with pure function composition`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let double = {x} -> x * 2

            let computation = {} -> perform AllPaths in do
                let choice = Choice.choose { options = [5, 10] }
                double { x = choice }

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(2, array.size())
        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(10L, 20L), values)
    }

    // === Additional Tests from Previous Session ===

    @Test
    fun testChoiceWithPureCode() {
        // Test that choice doesn't affect pure computations
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let pureFunc = {x} -> x * 2

            let computation = {} -> perform AllPaths in do
                let choice = Choice.choose { options = [5, 10] }
                pureFunc { x = choice }

            computation {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(2, array.size())

        val values = array.toList().map { (it as MezzeInt).value }.sorted()
        assertEquals(listOf(10L, 20L), values) // 5*2=10, 10*2=20
    }

    @Test
    fun testNestedChoice() {
        // Test choice in nested computations
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let inner = {x} -> perform AllPaths in do
                let y = Choice.choose { options = [1, 2] }
                x + y

            let outer = {} -> perform AllPaths in do
                let x = Choice.choose { options = [10, 20] }
                inner { x = x }

            outer {}
        """)
        assertTrue(result is MezzeArrayList)
        // Results should be arrays of arrays: [[11, 12], [21, 22]]
        val array = result as MezzeArrayList
        assertEquals(2, array.size())
    }
}
