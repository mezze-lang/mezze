package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import com.mezze.interpreter.v2.values.MezzeList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/choice module.
 * Tests nondeterministic choice with backtracking using AllPaths handler.
 * Migrated from V1 to V2 with unboxed values.
 */
class ChoiceEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Helper to check collection length in V2 (MezzeList or Object[])
    private fun assertCollectionLength(expected: Int, result: Any?) {
        assertNotNull(result)
        val length = when {
            result is MezzeList -> result.size()
            result!!.javaClass.isArray -> java.lang.reflect.Array.getLength(result)
            else -> fail("Expected collection (MezzeList or array), got ${result.javaClass.name}")
        }
        assertEquals(expected, length)
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
        assertCollectionLength(2, result)
    }

    @Test
    fun `test choose from three options`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                Choice.choose { options = [10, 20, 30] }

            computation {}
        """)
        assertCollectionLength(3, result)
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
        assertCollectionLength(4, result)
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
        assertCollectionLength(2, result)
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
        assertCollectionLength(3, result)
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
        assertCollectionLength(4, result)
    }

    // === run_all_paths Helper ===

    @Test
    fun `test run_all_paths helper`() {
        val result = eval("""
            use std::effects::choice::{Choice, run_all_paths}
            use std::collections::array

            let results = run_all_paths { computation = {} -> do
                let x = Choice.choose { options = [1, 2] }
                x
            }
            array::length {xs = results}
        """)
        assertEquals(2L, result)
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
        assertCollectionLength(2, result)
    }

    @Test
    fun testChoiceWithPureCode() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}

            let pureFunc = {x} -> x * 2

            let computation = {} -> perform AllPaths in do
                let choice = Choice.choose { options = [5, 10] }
                pureFunc { x = choice }

            computation {}
        """)
        assertCollectionLength(2, result)
    }

    @Test
    fun testNestedChoice() {
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
        // Results should be arrays of arrays: [[11, 12], [21, 22]]
        assertCollectionLength(2, result)
    }
}
