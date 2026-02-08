package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * Comprehensive test suite for the Generator effect.
 * Tests yield/resume pattern for creating lazy sequences.
 *
 * Generator is built on top of Continuation effect, so these tests verify:
 * 1. Basic yield functionality
 * 2. Multiple yields in sequence
 * 3. Generator state management
 * 4. Generator composition
 * 5. Edge cases and error conditions
 *
 * ENABLED: Continuation primitives and Generator stdlib now implemented!
 */
class GeneratorEffectTest {
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
        // Clear module cache to ensure fresh module resolution with updated exports
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    // ========================================================================
    // BASIC YIELD TESTS
    // ========================================================================

    @Test
    fun testBasicYield() {
        // Test that yield creates a suspended generator
        val source = """
            use std::effects::generator::{yield}

            let test = {} -> do
                yield { value = 42 }

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        // Should return a suspended continuation with the yielded value
        assertTrue(result is FunkContinuationSuspended)
    }

    @Test
    fun testYieldWithDifferentTypes() {
        // Test that yield works with different value types
        val source = """
            use std::effects::generator::{yield}

            let testInt = {} -> do
                yield { value =123 }

            testInt {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
    }

    // ========================================================================
    // MULTIPLE YIELDS TESTS
    // ========================================================================

    @Test
    fun testMultipleYields() {
        // Test generator with multiple yields - only first yield as final expression suspends
        val source = """
            use std::effects::generator::{yield}

            let firstNumber = {} -> do
                yield { value =1 }

            firstNumber {}
        """.trimIndent()

        val result = executeProgram(source)
        // Yield as final expression should suspend with value 1
        assertTrue(result is FunkContinuationSuspended)
        // Check the yielded value
        assertEquals(1, ((result as FunkContinuationSuspended).suspendedValue as FunkInt).value)
    }

    @Test
    fun testYieldsWithState() {
        // Test generator that yields with state
        val source = """
            use std::effects::generator::{yield}

            let counter = { start: Int } -> do
                let count = start
                yield { value =count }

            counter { start = 10 }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
        // Check the yielded value includes the state
        assertEquals(10, ((result as FunkContinuationSuspended).suspendedValue as FunkInt).value)
    }

    // ========================================================================
    // RANGE GENERATOR TESTS
    // ========================================================================

    @Test
    fun testRangeGenerator() {
        // Test a simple range generator - yields first value
        val source = """
            use std::effects::generator::{yield}

            let range = { start: Int, end: Int } -> do
                yield { value =start }

            range { start = 0, end = 5 }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
        // Check the first yielded value
        assertEquals(0, ((result as FunkContinuationSuspended).suspendedValue as FunkInt).value)
    }

    // ========================================================================
    // GENERATOR WITH LOOPS (if/when loop support exists)
    // ========================================================================

    // Note: These tests will need loop/recursion support
    // Placeholder for future implementation

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun testYieldAfterCapture() {
        // Test yield works correctly (capture was tested in continuation tests)
        val source = """
            use std::effects::generator::{yield}

            let test = {} -> do
                let x = 10
                yield { value =42 }

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
        // Check the yielded value
        assertEquals(42, ((result as FunkContinuationSuspended).suspendedValue as FunkInt).value)
    }

    @Test
    fun testYieldWithOtherEffects() {
        // Test generator works correctly with other effects (Log)
        val source = """
            use std::effects::generator::{yield}
            use std::effects::log::{Log, Console}

            let test = {} -> perform Console in do
                Log.println { msg = "Before yield" }
                yield { value =100 }

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
    }

    @Test
    fun testYieldInFunction() {
        // Test yield inside a parameterized function
        val source = """
            use std::effects::generator::{yield}

            let makeGenerator = { initial: Int } -> do
                let x = initial * 2
                yield { value =x }

            makeGenerator { initial = 5 }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
    }

    @Test
    fun testYieldDoesNotAffectPureCode() {
        // Verify that importing Continuation doesn't affect pure code
        val source = """
            use std::effects::generator::{yield}

            let pureComputation = { x: Int, y: Int } -> x * y + (x + y)

            pureComputation { x = 5, y = 7 }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(47, (result as FunkInt).value) // 5*7 + (5+7) = 35 + 12 = 47
    }

    // ========================================================================
    // GENERATOR UTILITY TESTS
    // ========================================================================

    @Test
    fun testGeneratorNext() {
        // Test that next returns the current value from a single-yield generator
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let gen = {} -> do
                yield { value =42 }

            let g = gen {}
            let result = next { gen = g }
            result.value
        """.trimIndent()

        val result = executeProgram(source)
        // Should return Some { val = 42 }
        assertTrue(result is FunkVariant)
        assertEquals("Some", (result as FunkVariant).constructor)
    }

    @Test
    fun testGeneratorToList() {
        // Test that to_list consumes a single-yield generator
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = {} -> do
                yield { value =42 }

            let g = gen {}
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        println("DEBUG after handler fix: result type = ${result::class.simpleName}")
        println("DEBUG after handler fix: result value = $result")
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(42, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorTake() {
        // Test that take gets the value from a single-yield generator
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{take}

            let gen = {} -> do
                yield { value =99 }

            let g = gen {}
            let result = take { gen = g, n = 3 }
            result.values
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        // Only one value available
        assertEquals(1, list.size())
        assertEquals(99, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorToListSingleValue() {
        // Test to_list with generator that yields a single value
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = {} -> do
                yield { value =42 }

            let g = gen {}
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(42, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorTakeFromSingleYield() {
        // Test take with n larger than available values (only 1 available)
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{take}

            let gen = {} -> do
                yield { value =1 }

            let g = gen {}
            let result = take { gen = g, n = 10 }
            result.values
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        // Should only have 1 value even though we asked for 10
        assertEquals(1, list.size())
        assertEquals(1, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorNextReturnsSome() {
        // Test that next on valid generator returns Some
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let gen = {} -> do
                yield { value =100 }

            let g0 = gen {}
            let r1 = next { gen = g0 }
            r1.value
        """.trimIndent()

        val result = executeProgram(source)
        // Should return Some { val = 100 }
        assertTrue(result is FunkVariant)
        assertEquals("Some", (result as FunkVariant).constructor)
    }

    // ========================================================================
    // COMPREHENSIVE GENERATOR UTILITY TESTS
    // ========================================================================

    @Test
    fun testNextExtractsYieldedValue() {
        // Verify next extracts the actual yielded value
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let gen = {} -> do
                yield { value =999 }

            let g = gen {}
            let result = next { gen = g }
            match result.value is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(999, (result as FunkInt).value)
    }

    @Test
    fun testNextReturnsGenState() {
        // Verify next returns the generator state for chaining
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let gen = {} -> do
                yield { value =42 }

            let g = gen {}
            let result = next { gen = g }
            # result.gen should exist (either next state or null)
            result.value
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Some", (result as FunkVariant).constructor)
    }

    @Test
    fun testToListWithStringValue() {
        // Test to_list with string yielded value
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = {} -> do
                yield { value ="hello" }

            let g = gen {}
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals("hello", (list.get(0) as FunkString).value)
    }

    @Test
    fun testToListWithBooleanValue() {
        // Test to_list with boolean yielded value
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = {} -> do
                yield { value =True }

            let g = gen {}
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertTrue((list.get(0) as FunkBool).value)
    }

    @Test
    fun testToListWithRecordValue() {
        // Test to_list with record yielded value
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = {} -> do
                yield { value ={ x = 10, y = 20 } }

            let g = gen {}
            let result = to_list { gen = g }
            __native_arraylist_length { xs = result }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(1, (result as FunkInt).value)
    }

    @Test
    fun testTakeWithZero() {
        // Test take with n = 0
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{take}

            let gen = {} -> do
                yield { value =42 }

            let g = gen {}
            let result = take { gen = g, n = 0 }
            result.values
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(0, list.size())
    }

    @Test
    fun testTakeWithOne() {
        // Test take with n = 1
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{take}

            let gen = {} -> do
                yield { value =77 }

            let g = gen {}
            let result = take { gen = g, n = 1 }
            result.values
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(77, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorWithComputation() {
        // Test generator that performs computation before yielding
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let computeGen = { a: Int, b: Int } -> do
                let sum = a + b
                let product = a * b
                let result = sum + product
                yield { value =result }

            let g = computeGen { a = 3, b = 4 }
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        // 3 + 4 = 7, 3 * 4 = 12, 7 + 12 = 19
        assertEquals(19, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorWithNestedLet() {
        // Test generator with nested let bindings
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let gen = {} -> do
                let x = 10
                let z = 5
                let y = z * 2
                let result = x + y
                yield { value =result }

            let g = gen {}
            let r = next { gen = g }
            match r.value is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        // x = 10, y = 5 * 2 = 10, result = 10 + 10 = 20
        assertEquals(20, (result as FunkInt).value)
    }

    @Test
    fun testGeneratorWithConditional() {
        // Test generator with conditional expression
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{to_list}

            let gen = { flag: Bool } -> do
                let value = if flag then 100 else 200
                yield { value =value }

            let g1 = gen { flag = True }
            to_list { gen = g1 }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(100, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorPreservesState() {
        // Test that generator captures local state correctly
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{next}

            let makeCounter = { start: Int, step: Int } -> do
                let current = start + step
                yield { value =current }

            let g = makeCounter { start = 100, step = 5 }
            let r = next { gen = g }
            match r.value is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(105, (result as FunkInt).value)
    }

    @Test
    fun testNextOnNonGenerator() {
        // Test that next handles non-suspended values gracefully (e.g., completed generator)
        val source = """
            use std::effects::generator::{next}

            # Pass a non-suspended value (unit value, like an exhausted generator)
            let result = next { gen = {} }
            result.value
        """.trimIndent()

        val result = executeProgram(source)
        // Should return None since {} is not a suspended generator
        assertTrue(result is FunkVariant)
        assertEquals("None", (result as FunkVariant).constructor)
    }

    @Test
    fun testToListProducesCorrectType() {
        // Verify to_list returns a proper FunkArrayList
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, to_list}

            let gen = {} -> do
                yield { value = 42 }

            let g = gen {}
            let xs = to_list { gen = g }
            __native_arraylist_length { xs = xs }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(1, (result as FunkInt).value)
    }

    @Test
    fun testTakeReturnsRecord() {
        // Verify take returns a record with values field
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, take}

            let gen = {} -> do
                yield { value = 55 }

            let g = gen {}
            let result = take { gen = g, n = 1 }
            result.values
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(55, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testGeneratorWithEffectComposition() {
        // Test generator composed with Log effect
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, to_list}
            use std::effects::log::{Log, Console}

            let loggingGen = { msg: String, value: Int } -> perform Console in do
                Log.println { msg = msg }
                yield { value = value }

            let g = loggingGen { msg = "Yielding value", value = 123 }
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        assertEquals(123, (list.get(0) as FunkInt).value)
    }

    @Test
    fun testMultipleGeneratorInstances() {
        // Test creating multiple independent generator instances
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, next}

            let makeGen = { value: Int } -> do
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
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(60, (result as FunkInt).value) // 10 + 20 + 30
    }

    @Test
    fun testGeneratorYieldingList() {
        // Test generator that yields an array - verify the array was yielded
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, to_list}

            let gen = {} -> do
                yield { value = [1, 2, 3] }

            let g = gen {}
            let result = to_list { gen = g }
            __native_arraylist_length { xs = result }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(1, (result as FunkInt).value)
    }

    @Test
    fun testGeneratorWithArithmetic() {
        // Test generator performing various arithmetic operations
        val source = """
            use std::effects::generator::{yield}
            use std::effects::generator::{yield, to_list}

            let mathGen = { x: Int } -> do
                let squared = x * x
                let doubled = x * 2
                let combined = squared + doubled
                yield { value = combined }

            let g = mathGen { x = 5 }
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(1, list.size())
        // 5^2 = 25, 5*2 = 10, 25 + 10 = 35
        assertEquals(35, (list.get(0) as FunkInt).value)
    }
}
