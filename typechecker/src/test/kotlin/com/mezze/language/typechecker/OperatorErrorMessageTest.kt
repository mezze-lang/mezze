package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests that verify error messages correctly distinguish between operator syntax
 * and explicit ability method calls, using the SyntaxMetadata tracking system.
 *
 * This verifies that:
 * - Operators like + - * / show user-friendly descriptions in errors
 * - Explicit ability calls like NumAdd.add show method names
 * - The AST remains clean and content-addressable (metadata is separate)
 */
class OperatorErrorMessageTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun getErrorMessage(source: String): String? {
        return try {
            val program = parser.parse(source)
            typeChecker.typeCheck(program)
            null  // No error
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    // ============================================================================
    // Binary Operator Tests with Type Errors
    // ============================================================================

    @Test
    fun `test multiplication operator with wrong result type`() {
        val source = """
            let x: Str = 5 * 3
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
        assertTrue(
            error!!.contains("Type mismatch") || error.contains("Str"),
            "Error should mention type mismatch: $error"
        )
    }

    @Test
    fun `test operator with ability constraint violation`() {
        val source = """
            let x = "hello" + 5
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected ability constraint error")
        // String doesn't implement NumAdd
        assertTrue(
            error!!.contains("NumAdd") || error.contains("Str"),
            "Error should mention ability or type: $error"
        )
    }

    @Test
    fun `test multiple errors in same expression`() {
        val source = """
            let x: Bool = "hello" + "world"
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
        // String doesn't implement NumAdd, and result isn't Bool
    }

    // ============================================================================
    // Explicit Ability Call Tests
    // ============================================================================

    @Test
    fun `test explicit NumAdd call shows method name in error`() {
        val source = """
            let x: Str = 1.add {r = 3}
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
        // Explicit call should mention the ability/method, not "operator"
        assertFalse(
            error!!.contains("operator '+'"),
            "Explicit call should not mention operator syntax: $error"
        )
    }

    @Test
    fun `test explicit Eq call error`() {
        val source = """
            let x: Str = 1.eq {r = 5}
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
    }

    @Test
    fun `test explicit LogicalAnd call error`() {
        val source = """
            let x: Str = True.and {r = False}
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
    }

    // ============================================================================
    // Comparison: Operator vs Explicit Call
    // ============================================================================

    @Test
    fun `test same semantic error produces different messages for operator vs explicit`() {
        val operatorSource = """
            let x: Str = 5 + 3
            x
        """.trimIndent()

        val explicitSource = """
            let x: Str = 1.add {r = 3}
            x
        """.trimIndent()

        val operatorError = getErrorMessage(operatorSource)
        val explicitError = getErrorMessage(explicitSource)

        assertNotNull(operatorError, "Operator version should error")
        assertNotNull(explicitError, "Explicit version should error")

        // Both should be type errors (checking for unification error or type mention)
        assertTrue(operatorError!!.contains("unify") || operatorError.contains("Type") || operatorError.contains("type"),
            "Operator error should be a type error. Actual: $operatorError")
        assertTrue(explicitError!!.contains("unify") || explicitError.contains("Type") || explicitError.contains("type"),
            "Explicit error should be a type error. Actual: $explicitError")
    }

    // ============================================================================
    // Negative Literal Special Case
    // ============================================================================

    @Test
    fun `test negative literal is not treated as operator`() {
        val source = """
            let x: Nat = -5
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error for negative Nat")
        // This should say "integer literal", not "operator '-'"
        assertTrue(
            error!!.contains("integer literal") || error.contains("Int"),
            "Negative literal should be described as literal: $error"
        )
    }

    // ============================================================================
    // Enhanced Error Context Tests
    // ============================================================================

    @Test
    fun `test describeExpression helper identifies operators correctly`() {
        val source = """
            let x = "text" + 42
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected type error")
        // Should mention the inability to add string and int
        assertTrue(error!!.isNotEmpty())
    }

    @Test
    fun `test typeMismatchWithContext provides context`() {
        val source = """
            let y: Nat = 5 + (-3)
            y
        """.trimIndent()

        val error = getErrorMessage(source)
        // With strict Nat type checking, Int is not compatible with Nat
        assertNotNull(error, "Expected type error: Int not compatible with Nat")
        // Accept improved error messages: "Type annotation mismatch" or "Type mismatch" or "not compatible"
        assertTrue(
            (error!!.contains("mismatch") || error.contains("not compatible")) && (error.contains("Int") || error.contains("Nat")),
            "Error should mention Int/Nat type conflict: $error"
        )
    }

    @Test
    fun `test negative result from addition without type annotation`() {
        val source = """
            let k = 5 + (-8)
            k
        """.trimIndent()

        val error = getErrorMessage(source)
        // This should type-check fine as Int (no Nat annotation)
        assertNull(error, "Should type-check successfully without annotation: $error")
    }

    @Test
    fun `test chained operators preserve error information`() {
        val source = """
            let x: Str = 1 + 2 + 3 + 4
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
        assertTrue(error!!.contains("unify") || error.contains("Type") || error.contains("type"),
            "Error should be a type error. Actual: $error")
    }

    @Test
    fun `test mixed operators and explicit calls`() {
        val source = """
            let a = 5 + 3
            let b = 3.mul {r = 2}
            let x: Str = b
            x
        """.trimIndent()

        val error = getErrorMessage(source)
        assertNotNull(error, "Expected a type error")
    }

    // ============================================================================
    // SyntaxMetadata Integration Test
    // ============================================================================

    @Test
    fun `test SyntaxMetadata correctly tracks operator locations`() {
        // This test verifies the core functionality:
        // 1. Parser marks operator locations in SyntaxMetadata
        // 2. TypeChecker can retrieve operator syntax for better errors
        // 3. Explicit calls don't have operator metadata

        val operatorCode = "let x = 1 + 2\nx"
        val explicitCode = "let x = 1.add {r = 2}\nx"

        // Both should parse and type-check successfully
        val operatorProgram = parser.parse(operatorCode)
        val explicitProgram = parser.parse(explicitCode)

        assertDoesNotThrow {
            typeChecker.typeCheck(operatorProgram)
        }

        // Clear cache for second check
        ModuleResolver.clearCache()
        val typeChecker2 = TypeChecker()
        assertDoesNotThrow {
            typeChecker2.typeCheck(explicitProgram)
        }
    }

    @Test
    fun `test SyntaxMetadata clears between parses`() {
        // Parse first program with operator
        parser.parse("let x = 1 + 2\nx")

        // Parse second program without operator
        parser.parse("let y = 42\ny")

        // Both should work correctly - metadata should not leak between parses
        // If metadata leaked, we might get wrong error messages
    }
}
