package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Tests for main function validation when running as executable.
 *
 * These tests use checkProgramForExecution() which validates that:
 * 1. main exists
 * 2. main is a function
 * 3. main takes {} (empty record) as parameter
 * 4. main returns {} (empty record)
 * 5. main has performed effects <*>
 */
class MainFunctionValidationTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // VALID MAIN TESTS
    // ========================================================================

    @Test
    fun `main with correct type succeeds`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let main = {} -> perform Console in Log.println { msg = "Hello World" }
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success, "main with {} -> {} <*> should succeed")
    }

    @Test
    fun `main with multiple handlers succeeds`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect Random having 'val where {
                next: {} -> 'val
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl Random for SystemRandom having 'val = Int where {
                next = {} -> 42
            }

            let main = {} -> perform Console, SystemRandom in do
                let n = Random.next {}
                Log.println { msg = "Done" }
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success, "main with multiple handlers should succeed")
    }

    @Test
    fun `main calling effectful helper succeeds`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let helper = {} -> perform Console in Log.println { msg = "helper" }

            let main = {} -> helper {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success, "main calling effectful helper should succeed")
    }

    // ========================================================================
    // INVALID MAIN TESTS
    // ========================================================================

    @Test
    fun `main that is pure fails`() {
        val source = """
            let main = {} -> {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Pure main should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "pure", ignoreCase = true, message = "Error should mention 'pure'")
    }

    @Test
    fun `main with unhandled effects fails`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            let main = {} -> Log.println { msg = "Hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "main with unhandled effects should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertTrue(
            errorMsg.contains("unhandled", ignoreCase = true) || errorMsg.contains("Log"),
            "Error should mention unhandled effects or Log: $errorMsg"
        )
    }

    @Test
    fun `main returning non-unit fails`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let main = {} -> perform Console in do
                Log.println { msg = "Hello" }
                42
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "main returning non-unit should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "return", ignoreCase = true, message = "Error should mention return type")
    }

    @Test
    fun `main with parameters fails`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let main = {x} -> perform Console in Log.println { msg = "Hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "main with parameters should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "parameter", ignoreCase = true, message = "Error should mention parameter")
    }

    @Test
    fun `main that is not a function fails`() {
        val source = """
            let main = 42
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "main that is not a function should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "function", ignoreCase = true, message = "Error should mention function")
    }

    @Test
    fun `missing main fails`() {
        val source = """
            let foo = {} -> 42
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Missing main should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "main", ignoreCase = true, message = "Error should mention main")
    }

    @Test
    fun `main with partial effect handling fails`() {
        // This test checks that main must handle ALL effects
        // Here we handle Log but not State, so main has type {} -> 'state <State, *>
        // which fails because:
        // 1. return type is not {} (it's 'state from State.get)
        // 2. still has unhandled State effect
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let main = {} -> perform Console in do
                Log.println { msg = "hello" }
                State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "main with partial handling should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        // The error could be about return type (not {}) or about unhandled effects
        assertTrue(
            errorMsg.contains("State") ||
            errorMsg.contains("unhandled", ignoreCase = true) ||
            errorMsg.contains("return", ignoreCase = true),
            "Error should mention return type or unhandled State effect: $errorMsg"
        )
    }

    // ========================================================================
    // COMPARISON: checkProgram vs checkProgramForExecution
    // ========================================================================

    @Test
    fun `checkProgram allows invalid main, checkProgramForExecution rejects it`() {
        val source = """
            let main = 42
        """.trimIndent()

        val ast = parser.parse(source)

        // checkProgram should succeed (no main validation)
        val normalResult = typeChecker.checkProgram(ast)
        assertTrue(normalResult is TypeChecker.TypeCheckResult.Success,
            "checkProgram should allow main = 42")

        // checkProgramForExecution should fail (validates main)
        val execResult = typeChecker.checkProgramForExecution(ast)
        assertTrue(execResult is TypeChecker.TypeCheckResult.Failure,
            "checkProgramForExecution should reject main = 42")
    }
}
