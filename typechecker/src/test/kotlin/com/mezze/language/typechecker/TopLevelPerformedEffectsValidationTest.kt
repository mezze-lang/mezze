package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Tests for top-level performed effects validation.
 *
 * These tests verify that:
 * 1. Top-level let bindings cannot have performed effects <*>
 * 2. Top-level bare expressions with <*> are rejected by checkProgramForExecution
 * 3. checkProgram (for tests/REPL) allows top-level expressions with <*>
 * 4. Functions with <*> in their return type are allowed (they're just definitions)
 */
class TopLevelPerformedEffectsValidationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // TOP-LEVEL LET BINDINGS WITH PERFORMED EFFECTS (always rejected)
    // ========================================================================

    @Test
    fun `direct perform at top-level is allowed for testing`() {
        // Direct `perform` at top-level is allowed because:
        // 1. It's a common test pattern
        // 2. The perform is explicit and visible
        // 3. The problematic pattern (calling functions with <*>) is caught separately
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let result = perform Counter in State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Direct perform at top-level is allowed for testing/REPL")
    }

    @Test
    fun `let binding with immediate perform returning unit is allowed`() {
        // Log.print returns {} (unit), so perform Console in Log.print {} has type {}
        // We allow this because:
        // 1. The value is unit - no meaningful data to "leak"
        // 2. It's useful for test setup and module initialization
        // 3. Disallowing it would be too restrictive for common patterns
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let result = perform Console in Log.print {msg = "test"}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Top-level let with perform returning unit is allowed")
    }

    @Test
    fun `let binding calling function with performed effects is rejected`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}
            let result = myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Let calling effectful function should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `let binding with lambda immediately called with perform is rejected`() {
        // This is the pattern: let result = ({} -> perform Handler in expr) {}
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let result = ({} -> perform Console in Log.print {msg = "test"}) {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Lambda with perform immediately called should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `nested let calling effectful function is rejected`() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let getState = {} -> perform Counter in State.get {}
            let result = getState {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Top-level let calling effectful function should fail")
    }

    // ========================================================================
    // VALID CASES: FUNCTION DEFINITIONS WITH <*>
    // ========================================================================

    @Test
    fun `function definition with performed effects is allowed`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Function definition with <*> should be allowed")
    }

    @Test
    fun `multiple function definitions with performed effects are allowed`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let func1 = {} -> perform Console in Log.print {msg = "one"}
            let func2 = {} -> perform Console in Log.print {msg = "two"}
            let func3 = {x: Int} -> perform Console in do
                Log.print {msg = "three"}
                x
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Multiple function definitions with <*> should be allowed")
    }

    @Test
    fun `function returning function with performed effects is allowed`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let makeLogger = {prefix: String} -> {} -> perform Console in
                Log.print {msg = prefix}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Higher-order function returning effectful function should be allowed")
    }

    // ========================================================================
    // TOP-LEVEL EXPRESSIONS (checkProgram vs checkProgramForExecution)
    // ========================================================================

    @Test
    fun `checkProgram allows bare effectful expression for REPL`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}

            myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "checkProgram should allow bare effectful expression (for REPL/tests)")
    }

    @Test
    fun `checkProgramForExecution rejects bare effectful expression`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}

            myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "checkProgramForExecution should reject bare effectful expression")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "module level", ignoreCase = true)
    }

    @Test
    fun `checkProgramForExecution rejects multiple bare effectful expressions`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let func1 = {} -> perform Console in Log.print {msg = "one"}
            let func2 = {} -> perform Console in Log.print {msg = "two"}

            func1 {}
            func2 {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "checkProgramForExecution should reject multiple bare effectful expressions")
    }

    @Test
    fun `bare pure expression is allowed in both modes`() {
        val source = """
            let add = {x: Int, y: Int} -> x + y

            add {x = 1, y = 2}
        """.trimIndent()

        val ast = parser.parse(source)

        // checkProgram should allow it
        val normalResult = typeChecker.checkProgram(ast)
        assertTrue(normalResult is TypeChecker.TypeCheckResult.Success,
            "checkProgram should allow bare pure expression")

        // checkProgramForExecution will fail because there's no main,
        // but NOT because of performed effects
        val execResult = typeChecker.checkProgramForExecution(ast)
        assertTrue(execResult is TypeChecker.TypeCheckResult.Failure)
        val errorMsg = (execResult as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "main", ignoreCase = true,
            message = "Error should be about missing main, not effects")
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `direct perform with operation returning value is allowed`() {
        // Direct perform is allowed - only function calls with <*> are rejected
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let value = perform Counter in State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Direct perform at top-level is allowed")
    }

    @Test
    fun `chained effectful calls at top-level are rejected`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let logAndReturn = {x: Int} -> perform Console in do
                Log.print {msg = "value"}
                x

            let doubled = {x: Int} -> logAndReturn {x = x * 2}

            let result = doubled {x = 21}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Chained effectful calls at top-level should be rejected")
    }

    @Test
    fun `function wrapping effectful call is valid pattern`() {
        // This is the recommended pattern when you need to use effectful code
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let effectfulFunc = {} -> perform Console in Log.print {msg = "test"}

            let getResult = {} -> effectfulFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Wrapping effectful call in function should be valid")
    }

    @Test
    fun `valid main program with effectful helpers passes execution check`() {
        val source = """
            effect Log where {
                print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let helper = {} -> perform Console in Log.print {msg = "helper"}

            let main = {} -> helper {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Valid main calling effectful helper should pass execution check")
    }
}
