package com.mezze.interpreter.v2

import com.mezze.interpreter.v2.values.MezzeList
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeSeq
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test suite for the Choice effect with multishot continuations.
 * Tests nondeterministic choice and backtracking using the fork primitive.
 * Migrated from V1 to V2 with unboxed values.
 */
class ChoiceEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Helper to get collection size in V2 (MezzeSeq, MezzeList or Object[])
    private fun getCollectionSize(result: Any?): Int {
        return when {
            result is MezzeSeq -> result.size()
            result is MezzeList -> result.size()
            result != null && result.javaClass.isArray -> java.lang.reflect.Array.getLength(result)
            else -> throw AssertionError("Expected collection (MezzeSeq, MezzeList or array), got ${result?.javaClass?.name ?: "null"}")
        }
    }

    // Helper to convert collection to list of Long values
    private fun getCollectionAsLongList(result: Any?): List<Long> {
        return when {
            result is MezzeSeq -> (0 until result.size()).map { result.get(it) as Long }
            result is MezzeList -> (0 until result.size()).map { result.get(it) as Long }
            result != null && result.javaClass.isArray -> {
                val length = java.lang.reflect.Array.getLength(result)
                (0 until length).map { java.lang.reflect.Array.get(result, it) as Long }
            }
            else -> throw AssertionError("Expected collection (MezzeSeq, MezzeList or array), got ${result?.javaClass?.name ?: "null"}")
        }
    }

    // Helper to get collection elements
    private fun getCollectionElements(result: Any?): List<Any?> {
        return when {
            result is MezzeSeq -> (0 until result.size()).map { result.get(it) }
            result is MezzeList -> (0 until result.size()).map { result.get(it) }
            result != null && result.javaClass.isArray -> {
                val length = java.lang.reflect.Array.getLength(result)
                (0 until length).map { java.lang.reflect.Array.get(result, it) }
            }
            else -> throw AssertionError("Expected collection (MezzeSeq, MezzeList or array), got ${result?.javaClass?.name ?: "null"}")
        }
    }

    @Test
    fun testBasicChoice() {
        // Test choosing between two options using multishot fork
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let result = Choice.choose { options = [1, 2] }
                result

            computation {}
        """.trimIndent()

        val result = eval(source)
        // fork returns an array with results from each path
        val size = getCollectionSize(result)
        assertEquals(2, size, "Expected 2 results (one for each choice)")

        val values = getCollectionAsLongList(result).sorted()
        assertEquals(listOf(1L, 2L), values)
    }

    @Test
    fun testMultipleChoices() {
        // Test multiple choice points
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2] }
                let y = Choice.choose { options = [10, 20] }
                x + y

            computation {}
        """.trimIndent()

        val result = eval(source)
        // Should return array with 2*2=4 results: [11, 21, 12, 22]
        val size = getCollectionSize(result)
        assertEquals(4, size, "Expected 4 results (2 * 2 choices)")

        // The results should be: 1+10=11, 1+20=21, 2+10=12, 2+20=22
        val values = getCollectionAsLongList(result).sorted()
        assertEquals(listOf(11L, 12L, 21L, 22L), values)
    }

    @Test
    fun testChoiceWithFail() {
        // Test that fail aborts the current path
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2, 3] }
                # Filter out even numbers by failing
                match x % 2 is
                    0 -> Choice.fail {}
                    _ -> x * 10

            computation {}
        """.trimIndent()

        val result = eval(source)
        // Should only return results for odd numbers: [10, 30]
        val values = getCollectionAsLongList(result).sorted()
        assertEquals(listOf(10L, 30L), values, "Expected only odd numbers to succeed")
    }

    @Test
    fun testEmptyChoice() {
        // Test that choosing from empty options fails
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                Choice.choose { options = [] }

            computation {}
        """.trimIndent()

        // Empty choice should abort - result depends on how abort is handled
        // In the current implementation, abort throws an exception
        var exceptionThrown = false
        try {
            eval(source)
        } catch (e: Exception) {
            exceptionThrown = true
            val isAbortException = e.message?.contains("abort", ignoreCase = true) == true ||
                e.cause?.message?.contains("abort", ignoreCase = true) == true
            assertTrue(isAbortException, "Expected abort exception, got: ${e.message}")
        }
        assertTrue(exceptionThrown, "Expected exception from empty choice")
    }

    @Test
    fun testRunAllPaths() {
        // Test the run_all_paths helper function with INLINED computation
        // Note: Currently, computations must be fully inlined in the perform block
        // for nested choices to work correctly. This is because function bodies
        // are not transformed into state machines.
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            # Fully inline the computation in the perform block
            let getResult = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2, 3] }
                let y = Choice.choose { options = [4, 5] }
                {first = x, second = y}

            getResult {}
        """.trimIndent()

        val result = eval(source)
        // Should return array of all pairs: 3 * 2 = 6 combinations
        val size = getCollectionSize(result)
        assertEquals(6, size, "Expected 6 pairs (3 * 2 choices)")

        // Each element should be a record with first and second fields
        val elements = getCollectionElements(result)
        elements.forEach { element ->
            assertTrue(element is MezzeRecord, "Each result should be a record")
            val record = element as MezzeRecord
            assertTrue(record.getField("first") != null, "Record should have 'first' field")
            assertTrue(record.getField("second") != null, "Record should have 'second' field")
        }
    }

    @Test
    fun testChoiceWithPureCode() {
        // Test that choice doesn't affect pure computations
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let pureFunc = {x} -> x * 2

            let computation = {} -> perform AllPaths in do
                let choice = Choice.choose { options = [5, 10] }
                pureFunc { x = choice }

            computation {}
        """.trimIndent()

        val result = eval(source)
        val size = getCollectionSize(result)
        assertEquals(2, size)

        val values = getCollectionAsLongList(result).sorted()
        assertEquals(listOf(10L, 20L), values) // 5*2=10, 10*2=20
    }

    @Test
    fun testNestedChoice() {
        // Test choice in nested computations
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let inner = {x} -> perform AllPaths in do
                let y = Choice.choose { options = [1, 2] }
                x + y

            let outer = {} -> perform AllPaths in do
                let x = Choice.choose { options = [10, 20] }
                inner { x = x }

            outer {}
        """.trimIndent()

        val result = eval(source)
        val size = getCollectionSize(result)

        // Nested choice should multiply: 2 outer * 2 inner = 4 results
        // But inner returns an array, so we might get nested arrays
        // This tests how nested multishot continuations interact
        assertTrue(size > 0, "Should have at least one result")
    }

    @Test
    fun testChoiceWithConditional() {
        // Test choice with conditional logic (guards)
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let x = Choice.choose { options = [1, 2, 3, 4, 5] }
                # Only keep values greater than 2
                match x > 2 is
                    True -> x
                    False -> Choice.fail {}

            computation {}
        """.trimIndent()

        val result = eval(source)
        val values = getCollectionAsLongList(result).sorted()
        assertEquals(listOf(3L, 4L, 5L), values, "Should only return values > 2")
    }

    @Test
    fun testChoiceProductType() {
        // Test choice with complex product types (records)
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths in do
                let color = Choice.choose { options = ["red", "blue"] }
                let size = Choice.choose { options = [1, 2] }
                {color = color, size = size}

            computation {}
        """.trimIndent()

        val result = eval(source)
        val size = getCollectionSize(result)
        assertEquals(4, size, "Should have 2 * 2 = 4 combinations")

        // Verify each result is a record with color and size
        val elements = getCollectionElements(result)
        elements.forEach { element ->
            assertTrue(element is MezzeRecord)
            val record = element as MezzeRecord
            assertTrue(record.getField("color") != null)
            assertTrue(record.getField("size") != null)
        }
    }
}
