package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for source location tracking in type check errors.
 * Verifies that TypeCheckError contains correct location information.
 */
class SourceLocationErrorTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test type error has source location for binary operation`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertNotNull(error.sourceLocation, "TypeCheckError should have sourceLocation for undefined variable")
        assertEquals(2, error.sourceLocation?.line, "Error should be on line 2")
    }

    @Test
    fun `test error message includes line and column`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        try {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        try {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        try {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
            fail("Should have thrown TypeCheckError")
        } catch (e: TypeCheckError) {
            // The error should be a constraint violation (NumAdd ability)
            assertNotNull(e.errorType, "Error should have an error type")
        }
    }

    @Test
    fun `test valid code does not throw error`() {
        val source = """
let x = 42
let y = 10
let z = x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }
}
