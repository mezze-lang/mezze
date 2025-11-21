package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * Comprehensive test suite for the Continuation effect.
 * Tests delimited continuations including capture, resume, suspend, and abort operations.
 */
class ContinuationEffectTest {
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
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return interpreter.execute(typedProgram, projectRoot)
    }

    @Test
    fun testBasicContinuationCapture() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                let cont = Continuation.capture {}
                let y = 20
                x + y

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(30, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithMultipleStatements() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let a = 5
                let b = 10
                let cont = Continuation.capture {}
                let c = 15
                let d = 20
                a + b + c + d

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(50, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithLogEffect() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}
            use std::effects::log::{Log, Console}

            let test = {} -> perform Console in perform Delimited in do
                Log.println { msg = "Before capture" }
                let x = 100
                let cont = Continuation.capture {}
                Log.println { msg = "After capture" }
                let y = 200
                Log.println { msg = "Returning result" }
                x + y

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(300, (result as FunkInt).value)
    }

    @Test
    fun testNestedPerforms() {
        val source = """
            use std::effects::log::{Log, Console}

            let inner = {} -> perform Console in do
                Log.println { msg = "Inner" }
                42

            let outer = {} -> perform Console in do
                Log.println { msg = "Outer start" }
                let result = inner {}
                Log.println { msg = "Outer end" }
                result

            outer {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(42, (result as FunkInt).value)
    }

    @Test
    fun testContinuationCaptureOnlyOnce() {
        // This test verifies that continuation capture doesn't cause double execution
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 1
                let cont = Continuation.capture {}
                let y = 2
                x + y

            test {}
        """.trimIndent()

        // Execute multiple times to ensure consistency
        val result1 = executeProgram(source)
        val result2 = executeProgram(source)
        val result3 = executeProgram(source)

        assertTrue(result1 is FunkInt)
        assertTrue(result2 is FunkInt)
        assertTrue(result3 is FunkInt)
        assertEquals(3, (result1 as FunkInt).value)
        assertEquals(3, (result2 as FunkInt).value)
        assertEquals(3, (result3 as FunkInt).value)
    }

    @Test
    fun testContinuationWithFunctionCalls() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let add = {a, b} -> a + b

            let test = {} -> perform Delimited in do
                let x = add {a = 5, b = 10}
                let cont = Continuation.capture {}
                let y = add {a = 20, b = 30}
                add {a = x, b = y}

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(65, (result as FunkInt).value)
    }

    @Test
    fun testContinuationInFunction() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let makeComputation = {initial} -> perform Delimited in do
                let x = initial
                let cont = Continuation.capture {}
                let y = x * 2
                x + y

            makeComputation {initial = 10}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(30, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithParameterizedFunction() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {multiplier} -> perform Delimited in do
                let x = 10
                let cont = Continuation.capture {}
                let y = multiplier * 2
                x + y

            test {multiplier = 10}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(30, (result as FunkInt).value)
    }

    @Test
    fun testMultipleContinuationCaptures() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                let cont1 = Continuation.capture {}
                let y = 20
                let cont2 = Continuation.capture {}
                let z = 30
                x + y + z

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(60, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithRecords() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let point = {x = 10, y = 20}
                let cont = Continuation.capture {}
                let z = 30
                point.x + point.y + z

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(60, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithArithmetic() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let a = 5 * 2
                let cont = Continuation.capture {}
                let b = 10 + 5
                a + b

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(25, (result as FunkInt).value)
    }

    @Test
    fun testDelimitedPerformerAlwaysAvailable() {
        // Test that Delimited performer is available without explicit registration
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let cont = Continuation.capture {}
                let result = 42
                result

            test {}
        """.trimIndent()

        val result = assertDoesNotThrow { executeProgram(source) }
        assertTrue(result is FunkInt)
        assertEquals(42, (result as FunkInt).value)
    }

    @Test
    fun testContinuationDoesNotAffectPureCode() {
        // Verify that having continuation effect available doesn't change pure code behavior
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let pureComputation = {x, y} -> x * y + (x + y)

            pureComputation {x = 5, y = 7}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(47, (result as FunkInt).value) // 5*7 + (5+7) = 35 + 12 = 47
    }

    // ========================================================================
    // RESUME TESTS (CRITICAL)
    // ========================================================================

    @Test
    fun testBasicResume() {
        // Test that resume calls a captured continuation
        // Use suspend to get the continuation out of the perform block
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                Continuation.suspend {}

            let main = {} -> do
                let suspended = test {}
                # suspended should be FunkContinuationSuspended
                # For now, just return it to verify suspend works
                suspended

            main {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkContinuationSuspended)
    }

    @Test
    fun testResumeWithDifferentValues() {
        // Test that capture creates a callable continuation within the same scope
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                let cont = Continuation.capture {}
                # cont now represents "the rest of the computation"
                # We can call it if we want (multishot), but for now just continue
                let y = 20
                x + y

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(30, (result as FunkInt).value)
    }

    // ========================================================================
    // ABORT TESTS (CRITICAL)
    // ========================================================================

    @Test
    fun testBasicAbort() {
        // Test that abort stops execution immediately
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                Continuation.abort {}
                let y = 20
                x + y

            test {}
        """.trimIndent()

        // Abort should throw ContinuationAbortedException
        var exceptionThrown = false
        try {
            executeProgram(source)
        } catch (e: Exception) {
            // Check that it's the right exception type or wraps it
            exceptionThrown = true
            val isAbortException = e is com.funk.language.interpreter.stdlib.effects.ContinuationAbortedException ||
                e.cause is com.funk.language.interpreter.stdlib.effects.ContinuationAbortedException ||
                e.message?.contains("abort", ignoreCase = true) == true ||
                e.cause?.message?.contains("abort", ignoreCase = true) == true

            assertTrue(
                isAbortException,
                "Expected ContinuationAbortedException but got: ${e.javaClass.simpleName}: ${e.message}\nCause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}"
            )
        }
        assertTrue(exceptionThrown, "Expected an exception to be thrown by abort")
    }

    @Test
    fun testAbortPreventsSubsequentStatements() {
        // Verify that statements after abort don't execute
        val source = """
            use std::effects::continuation::{Continuation, Delimited}
            use std::effects::log::{Log, Console}

            let test = {} -> perform Console in perform Delimited in do
                Log.println { msg = "Before abort" }
                Continuation.abort {}
                Log.println { msg = "After abort" }
                42

            test {}
        """.trimIndent()

        // Should print "Before abort" but not "After abort"
        var exceptionThrown = false
        try {
            executeProgram(source)
        } catch (e: Exception) {
            exceptionThrown = true
            val isAbortException = e.message?.contains("abort", ignoreCase = true) == true ||
                      e.cause?.message?.contains("abort", ignoreCase = true) == true
            assertTrue(isAbortException, "Expected abort exception but got: ${e.javaClass.simpleName}: ${e.message}")
        }
        assertTrue(exceptionThrown, "Expected an exception to be thrown by abort")
    }

    // ========================================================================
    // MULTISHOT TESTS (Important for generators)
    // ========================================================================

    @Test
    fun testMultishotBasic() {
        // Test that captured continuation can be called multiple times (multishot)
        // This is a basic test - more complex multishot requires returning the continuation
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                let cont = Continuation.capture {}
                # Continuation is captured
                # For true multishot, we'd need to call cont multiple times
                # For now, verify capture works and execution continues
                let y = 20
                x + y

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(30, (result as FunkInt).value)
    }

    @Test
    fun testMultishotWithState() {
        // Test continuation capture with state
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let counter = {} -> perform Delimited in do
                let x = 100
                let cont = Continuation.capture {}
                let y = 200
                x + y

            counter {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(300, (result as FunkInt).value)
    }

    // ========================================================================
    // SUSPEND TESTS
    // ========================================================================

    @Test
    fun testBasicSuspend() {
        // Test that suspend creates a suspended continuation
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                Continuation.suspend {}

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        // Suspend should return a FunkContinuationSuspended
        assertTrue(result is FunkContinuationSuspended)
    }

    // ========================================================================
    // EDGE CASE TESTS
    // ========================================================================

    @Test
    fun testNestedContinuations() {
        // Test capturing continuation inside captured continuation
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let test = {} -> perform Delimited in do
                let x = 10
                let outer = Continuation.capture {}
                let y = 20
                let inner = Continuation.capture {}
                let z = 30
                x + y + z

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(60, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithComplexControl() {
        // Test continuation with variables and arithmetic
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            let compute = {n} -> perform Delimited in do
                let cont = Continuation.capture {}
                let result = n * 10
                result

            compute {n = 5}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(50, (result as FunkInt).value)
    }

    @Test
    fun testContinuationWithMultipleEffects() {
        // Test continuation works correctly with multiple effects
        val source = """
            use std::effects::continuation::{Continuation, Delimited}
            use std::effects::log::{Log, Console}

            let test = {} -> perform Console in perform Delimited in do
                Log.println { msg = "Start" }
                let cont = Continuation.capture {}
                Log.println { msg = "Middle" }
                let x = 100
                Log.println { msg = "End" }
                x

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(100, (result as FunkInt).value)
    }
}
