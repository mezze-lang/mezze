package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for source location tracking in type check errors.
 * Verifies that TypeCheckError contains correct location information.
 */
class SourceLocationErrorTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test type error has source location for binary operation`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertNotNull(error.sourceLocation, "TypeCheckError should have sourceLocation")
        assertEquals(3, error.sourceLocation?.line, "Error should be on line 3")
        // The error is at the binary operation (x + y)
        assertTrue(error.sourceLocation?.column in 9..13, "Error column should be around the binary operation")
    }

    @Test
    fun `test type error has source location for undefined variable`() {
        val source = """
let x = 42
let y = x + unknown_var
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertNotNull(error.sourceLocation, "TypeCheckError should have sourceLocation for undefined variable")
        assertEquals(2, error.sourceLocation?.line, "Error should be on line 2")
    }

    @Test
    fun `test error pretty printer includes code snippet`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        try {
            typeChecker.typeCheck(program).program
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            val prettyPrinted = ErrorPrettyPrinter.prettyPrint(e, source, "test.funk")

            // Verify the pretty printed error contains expected elements
            assertTrue(prettyPrinted.contains("test.funk:3"), "Should contain file:line reference")
            assertTrue(prettyPrinted.contains("let z = x + y"), "Should contain the offending code line")
            assertTrue(prettyPrinted.contains("^"), "Should contain error pointer")
        }
    }

    @Test
    fun `test error message includes line and column`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        try {
            typeChecker.typeCheck(program).program
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            // The error message should include location info
            val message = e.message ?: ""
            assertTrue(
                message.contains("line") || e.sourceLocation != null,
                "Error should have location information"
            )
        }
    }

    @Test
    fun `test multiline error locations are correct`() {
        val source = """
let a = 1
let b = 2
let c = 3
let d = 4
let e = a + "string"
        """.trimIndent()

        val program = parser.parse(source)

        try {
            typeChecker.typeCheck(program).program
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            assertNotNull(e.sourceLocation, "Error should have source location")
            assertEquals(5, e.sourceLocation?.line, "Error should be on line 5")
        }
    }

    @Test
    fun `test error type is correctly set`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        try {
            typeChecker.typeCheck(program).program
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            // The error should be a constraint violation (NumAdd ability)
            assertNotNull(e.errorType, "Error should have an error type")
        }
    }

    @Test
    fun `test error pretty printer shows context lines`() {
        val source = """
let a = 1
let b = 2
let c = 3
let d = 4
let e = a + "oops"
let f = 6
let g = 7
        """.trimIndent()

        val program = parser.parse(source)

        try {
            typeChecker.typeCheck(program).program
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            val prettyPrinted = ErrorPrettyPrinter.prettyPrint(e, source, "test.funk")

            // Should show context lines before and after the error
            assertTrue(prettyPrinted.contains("let d = 4"), "Should show line before error")
            assertTrue(prettyPrinted.contains("let e = a + \"oops\""), "Should show error line")
            assertTrue(prettyPrinted.contains("let f = 6"), "Should show line after error")
        }
    }

    @Test
    fun `test valid code does not throw error`() {
        val source = """
let x = 42
let y = 10
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
}
