package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * Tests for open record type validation.
 *
 * Open records (`{ name: Str, age: Int, ... }`) allow extra fields
 * but should still require all declared fields.
 */
class OpenRecordValidationTest {

    private val parser = MezzeParser()

    private fun shouldPass(source: String) {
        val tc = TypeChecker()
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        try {
            val result = tc.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "Expected success but got failure: ${(result as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message }}")
        } catch (e: TypeCheckError) {
            fail("Expected success but got TypeCheckError: ${e.message}")
        }
    }

    private fun shouldFail(source: String, expectedError: String? = null) {
        val tc = TypeChecker()
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        try {
            val result = tc.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
            if (result is TypeChecker.TypeCheckResult.Failure) {
                if (expectedError != null) {
                    val errors = result.errors
                    assertTrue(errors.any { it.message.contains(expectedError, ignoreCase = true) },
                        "Expected error containing '$expectedError' but got: ${errors.joinToString { it.message }}")
                }
                return // test passed — type-checking failed as expected
            }
            fail("Expected failure but type-checking succeeded")
        } catch (e: TypeCheckError) {
            // Type-check threw — this counts as failure
            if (expectedError != null) {
                assertTrue(e.message?.contains(expectedError, ignoreCase = true) == true,
                    "Expected error containing '$expectedError' but got: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Closed record validation
    // ========================================================================

    @Test
    fun `closed record with all fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice", age = 30 }
        """)
    }

    @Test
    fun `closed record with extra field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice", age = 30, city = "NYC" }
        """, "extra")
    }

    @Test
    fun `closed record with missing field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice" }
        """, "missing")
    }

    // ========================================================================
    // Open record validation (the bug fix)
    // ========================================================================

    @Test
    fun `open record with all fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", age = 30 }
        """)
    }

    @Test
    fun `open record with extra fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", age = 30, city = "NYC" }
        """)
    }

    @Test
    fun `open record with missing declared field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", city = "NYC" }
        """, "missing")
    }

    @Test
    fun `open record with all declared fields missing fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { city = "NYC", country = "NL" }
        """, "missing")
    }

    @Test
    fun `open record with extra fields and all required fields succeeds`() {
        shouldPass("""
            type Config = { host: Str, port: Int, ... }
            let config: Config = { host = "localhost", port = 8080, debug = True, timeout = 30 }
        """)
    }
}
