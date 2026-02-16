package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Comprehensive tests for effect handling scenarios with perform expressions.
 *
 * These tests verify critical behavior:
 * 1. Handling effects on uncalled functions
 * 2. Handling effects then calling functions
 * 3. Calling functions then handling effects sequentially
 * 4. Partial effect handling (removing only specific effects, not all)
 *
 * CRITICAL: These tests verify the fix for the bug where perform was removing
 * ALL effects instead of only the handled effect.
 */
class ComprehensiveEffectHandlingTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val parser = FunkParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast).program
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val program = parseAndTypeCheck(code)
        val actualType = getVariableType(program, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ===== Category 1: Handling effects on UNCALLED functions =====
    // These tests verify that perform on a function (not a call) transforms the function type

    @Test
    fun `test 1a - perform on uncalled function with single effect removes effect from function type`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let handled = perform Console in myFunc
            handled
            """.trimIndent(),
            "handled",
            "{} -> String <*>"  // <*> marks that effects were performed
        )
    }

    @Test
    fun `test 1b - perform on uncalled function with multiple effects removes only handled effect`() {
        // CRITICAL: This tests the partial effect handling bug fix
        // Before fix: ALL effects were removed
        // After fix: Only the handled effect (Log via Console) is removed, FileSystem remains
        // <*> marks that some effects were already performed
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let partiallyHandled = perform Console in myFunc
            partiallyHandled
            """.trimIndent(),
            "partiallyHandled",
            "{} -> String <FileSystem, *>"  // FileSystem remains, * marks performed
        )
    }

    @Test
    fun `test 1c - sequential perform on uncalled function removes effects one by one`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let step1 = perform Console in myFunc
            let step2 = perform LocalDisk in step1
            step2
            """.trimIndent(),
            "step2",
            "{} -> String <*>"  // All effects handled, * marks performed
        )
    }

    @Test
    fun `test 1d - perform on uncalled function does not mutate original function`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let handled = perform Console in myFunc
            myFunc
            """.trimIndent(),
            "myFunc",
            "{} -> String <Log>"
        )
    }

    // ===== Category 2: Handle effects THEN call function =====
    // These tests verify handling effects on uncalled function, then calling the result

    @Test
    fun `test 2a - handle single effect then call function returns pure value`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let handled = perform Console in myFunc
            let result = handled {}
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    @Test
    fun `test 2b - handle one effect then call returns suspended with remaining effects`() {
        // CRITICAL: This tests the partial effect handling bug fix
        // The result should be String <FileSystem>, not just String
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let partiallyHandled = perform Console in myFunc
            let result = partiallyHandled {}
            result
            """.trimIndent(),
            "result",
            "String <FileSystem>"
        )
    }

    @Test
    fun `test 2c - handle all effects then call returns pure value`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let step1 = perform Console in myFunc
            let step2 = perform LocalDisk in step1
            let result = step2 {}
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    @Test
    fun `test 2d - handle effects in different order then call`() {
        // Order of handling should not matter
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let step1 = perform LocalDisk in myFunc
            let step2 = perform Console in step1
            let result = step2 {}
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    // ===== Category 3: Call function THEN handle effects =====
    // These tests verify calling an effectful function creates SuspendedType,
    // then performing handlers on the suspended value

    @Test
    fun `test 3a - call effectful function creates suspended type`() {
        // CRITICAL: This verifies function calls with effects produce SuspendedType
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let called = myFunc {}
            called
            """.trimIndent(),
            "called",
            "String <FileSystem, Log>"
        )
    }

    @Test
    fun `test 3b - call function then handle single effect`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let called = myFunc {}
            let handled = perform Console in called
            handled
            """.trimIndent(),
            "handled",
            "String"
        )
    }

    @Test
    fun `test 3c - call function then partially handle effects`() {
        // CRITICAL: This tests the partial effect handling bug fix
        // After calling myFunc {}, we get String <FileSystem, Log>
        // After perform Console, we should get String <FileSystem>, NOT just String
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let called = myFunc {}
            let partiallyHandled = perform Console in called
            partiallyHandled
            """.trimIndent(),
            "partiallyHandled",
            "String <FileSystem>"
        )
    }

    @Test
    fun `test 3d - call function then handle effects sequentially`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let called = myFunc {}
            let step1 = perform Console in called
            let step2 = perform LocalDisk in step1
            step2
            """.trimIndent(),
            "step2",
            "String"
        )
    }

    @Test
    fun `test 3e - call function then handle effects in different order`() {
        // CRITICAL: Verify order independence of effect handling
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let called = myFunc {}
            let step1 = perform LocalDisk in called
            let step2 = perform Console in step1
            step2
            """.trimIndent(),
            "step2",
            "String"
        )
    }

    // ===== Category 4: Complex scenarios =====

    @Test
    fun `test 4a - nested perform expressions`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let result = perform LocalDisk in (perform Console in (myFunc {}))
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    @Test
    fun `test 4b - multiple effectful functions with independent handling`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let func1: {} -> String <Log> = {} -> do
                Log.println { msg = "Func1" }
                "Result1"

            let func2: {} -> String <Log> = {} -> do
                Log.println { msg = "Func2" }
                "Result2"

            let result1 = perform Console in (func1 {})
            let result2 = perform Console in (func2 {})
            result1
            """.trimIndent(),
            "result1",
            "String"
        )
    }

    @Test
    fun `test 4c - compose effectful computations`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let func1: {} -> Int <Log> = {} -> do
                Log.println { msg = "Func1" }
                42

            let func2: { val: Int } -> String <Log> = {val} -> do
                Log.println { msg = "Func2" }
                "Result"

            let val1 = func1 {}
            let val2 = func2 { val = val1 }
            let handled = perform Console in val2
            handled
            """.trimIndent(),
            "handled",
            "String"
        )
    }

    @Test
    fun `test 4d - verify intermediate types in two effect sequence`() {
        // This test verifies each intermediate step has the correct type
        // CRITICAL: After handling Log, FileSystem should remain
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let myFunc: {} -> String <FileSystem, Log> = {} -> do
                Log.println { msg = "Hello" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                "Done"

            let called = myFunc {}
            let step1 = perform Console in called
            step1
            """.trimIndent(),
            "step1",
            "String <FileSystem>"
        )
    }

    @Test
    fun `test 4f - handle effect on bound function value`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let bound = myFunc
            let handled = perform Console in bound
            handled
            """.trimIndent(),
            "handled",
            "{} -> String <*>"  // <*> marks that effects were performed
        )
    }

    @Test
    fun `test 4g - handle same effect type multiple times in sequence`() {
        // Different effectful computations with the same effect type
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let func1: {} -> Int <Log> = {} -> do
                Log.println { msg = "Func1" }
                42

            let func2: { val: Int } -> String <Log> = {val} -> do
                Log.println { msg = "Func2" }
                "Result"

            let result1 = perform Console in (func1 {})
            let result2 = perform Console in (func2 { val = result1 })
            result2
            """.trimIndent(),
            "result2",
            "String"
        )
    }

    // ===== Category 5: Edge cases and expected behaviors =====

    @Test
    fun `test 5a - calling effectful function without handling produces suspended type`() {
        // This should typecheck - suspended values are valid
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let result = myFunc {}
            result
            """.trimIndent(),
            "result",
            "String <Log>"
        )
    }

    @Test
    fun `test 5b - suspended value can be bound and returned`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let wrapSuspended = {val} -> val

            let suspended = myFunc {}
            let result = wrapSuspended { val = suspended }
            result
            """.trimIndent(),
            "result",
            "String <Log>"
        )
    }

    @Test
    fun `test 5c - bind effectful computation to variable preserves effects`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}

            let myFunc: {} -> String <Log> = {} -> do
                Log.println { msg = "Hello" }
                "Done"

            let suspended1 = myFunc {}
            let suspended2 = suspended1
            suspended2
            """.trimIndent(),
            "suspended2",
            "String <Log>"
        )
    }
}
