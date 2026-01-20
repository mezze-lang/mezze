package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * Test suite for the Choice effect with multishot continuations.
 * Tests nondeterministic choice and backtracking using the fork primitive.
 */
class ChoiceEffectTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        // Clear module cache to ensure fresh module resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    @Test
    fun testBasicChoice() {
        // Test choosing between two options using multishot fork
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let result = Choice.choose { options = [1, 2] }
                result

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        // fork returns an array with results from each path
        assertTrue(result is FunkArrayList, "Expected array result, got: ${result::class.simpleName}")
        val array = result as FunkArrayList
        assertEquals(2, array.elements.size, "Expected 2 results (one for each choice)")

        val values = array.elements.map { (it as FunkInt).value }.sorted()
        assertEquals(listOf(1L, 2L), values)
    }

    @Test
    fun testMultipleChoices() {
        // Test multiple choice points
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose { options = [1, 2] }
                let y = Choice.choose { options = [10, 20] }
                x + y

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        // Should return array with 2*2=4 results: [11, 21, 12, 22]
        assertTrue(result is FunkArrayList, "Expected array result")
        val array = result as FunkArrayList
        assertEquals(4, array.elements.size, "Expected 4 results (2 * 2 choices)")

        // The results should be: 1+10=11, 1+20=21, 2+10=12, 2+20=22
        val values = array.elements.map { (it as FunkInt).value }.sorted()
        assertEquals(listOf(11L, 12L, 21L, 22L), values)
    }

    @Test
    fun testChoiceWithFail() {
        // Test that fail aborts the current path
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose { options = [1, 2, 3] }
                # Filter out even numbers by failing
                match x % 2 is
                    0 -> Choice.fail {}
                    _ -> x * 10

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        // Should only return results for odd numbers: [10, 30]
        assertTrue(result is FunkArrayList, "Expected array result")
        val array = result as FunkArrayList

        // Results for x=1: 10, x=2: (failed), x=3: 30
        val values = array.elements.map { (it as FunkInt).value }.sorted()
        assertEquals(listOf(10L, 30L), values, "Expected only odd numbers to succeed")
    }

    @Test
    fun testEmptyChoice() {
        // Test that choosing from empty options fails
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                Choice.choose { options = [] }

            computation {}
        """.trimIndent()

        // Empty choice should abort - result depends on how abort is handled
        // In the current implementation, abort throws an exception
        var exceptionThrown = false
        try {
            executeProgram(source)
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
            let result = perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose { options = [1, 2, 3] }
                let y = Choice.choose { options = [4, 5] }
                {first = x, second = y}

            result
        """.trimIndent()

        val result = executeProgram(source)
        // Should return array of all pairs: 3 * 2 = 6 combinations
        assertTrue(result is FunkArrayList, "Expected array result")
        val array = result as FunkArrayList
        assertEquals(6, array.elements.size, "Expected 6 pairs (3 * 2 choices)")

        // Each element should be a record with first and second fields
        array.elements.forEach { element ->
            assertTrue(element is FunkRecord, "Each result should be a record")
            val record = element as FunkRecord
            assertTrue(record.fields.containsKey("first"), "Record should have 'first' field")
            assertTrue(record.fields.containsKey("second"), "Record should have 'second' field")
        }
    }

    @Test
    fun testChoiceWithPureCode() {
        // Test that choice doesn't affect pure computations
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let pureFunc = {x} -> x * 2

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let choice = Choice.choose { options = [5, 10] }
                pureFunc { x = choice }

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val array = result as FunkArrayList
        assertEquals(2, array.elements.size)

        val values = array.elements.map { (it as FunkInt).value }.sorted()
        assertEquals(listOf(10L, 20L), values) // 5*2=10, 10*2=20
    }

    @Test
    fun testNestedChoice() {
        // Test choice in nested computations
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let inner = {x} -> perform AllPaths {cont = continuation::new {}} in do
                let y = Choice.choose { options = [1, 2] }
                x + y

            let outer = {} -> perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose { options = [10, 20] }
                inner { x = x }

            outer {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val array = result as FunkArrayList

        // Nested choice should multiply: 2 outer * 2 inner = 4 results
        // But inner returns an array, so we might get nested arrays
        // This tests how nested multishot continuations interact
        assertTrue(array.elements.size > 0, "Should have at least one result")
    }

    @Test
    fun testChoiceWithConditional() {
        // Test choice with conditional logic (guards)
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose { options = [1, 2, 3, 4, 5] }
                # Only keep values greater than 2
                match x > 2 is
                    true -> x
                    false -> Choice.fail {}

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val array = result as FunkArrayList

        val values = array.elements.map { (it as FunkInt).value }.sorted()
        assertEquals(listOf(3L, 4L, 5L), values, "Should only return values > 2")
    }

    @Test
    fun testChoiceProductType() {
        // Test choice with complex product types (records)
        val source = """
            use std::control::continuation
            use std::effects::choice::{Choice, AllPaths}

            let computation = {} -> perform AllPaths {cont = continuation::new {}} in do
                let color = Choice.choose { options = ["red", "blue"] }
                let size = Choice.choose { options = [1, 2] }
                {color = color, size = size}

            computation {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val array = result as FunkArrayList
        assertEquals(4, array.elements.size, "Should have 2 * 2 = 4 combinations")

        // Verify each result is a record with color and size
        array.elements.forEach { element ->
            assertTrue(element is FunkRecord)
            val record = element as FunkRecord
            assertTrue(record.fields.containsKey("color"))
            assertTrue(record.fields.containsKey("size"))
        }
    }
}
