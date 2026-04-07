package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for Nat type validation.
 *
 * Nat represents natural numbers (non-negative integers: 0, 1, 2, 3, ...).
 * This test suite verifies that:
 * 1. Positive integers can be assigned to Nat
 * 2. Zero can be assigned to Nat
 * 3. Negative integers CANNOT be assigned to Nat
 */
class NatTypeValidationTest {

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ===== Valid Nat assignments =====

    @Test
    fun `test assign positive integer to Nat`() {
        assertTypeChecks(
            """
            let x = 5n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test assign zero to Nat`() {
        assertTypeChecks(
            """
            let x = 0n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test assign large positive integer to Nat`() {
        assertTypeChecks(
            """
            let x = 1000000n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test Nat in function parameter`() {
        assertTypeChecks(
            """
            let addOne: { n: Nat } -> Nat = { n } -> n + 1n
            addOne
            """.trimIndent(),
            "addOne",
            "{ n: Nat } -> Nat"
        )
    }

    @Test
    fun `test Nat in function return type`() {
        assertTypeChecks(
            """
            let getPositive: {} -> Nat = {} -> 42n
            getPositive
            """.trimIndent(),
            "getPositive",
            "{} -> Nat"
        )
    }

    // ===== Invalid Nat assignments (negative numbers) =====

    @Test
    fun `test reject negative integer for Nat`() {
        assertTypeCheckFails(
            """
            let x: Nat = -5
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test reject negative one for Nat`() {
        assertTypeCheckFails(
            """
            let x: Nat = -1
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test reject large negative integer for Nat`() {
        assertTypeCheckFails(
            """
            let x: Nat = -1000000
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test reject negative in function call with Nat parameter`() {
        assertTypeCheckFails(
            """
            let processNat: { n: Nat } -> Nat = { n } -> n + 1n
            let result = processNat { n = -5 }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test reject negative in function return with Nat type`() {
        assertTypeCheckFails(
            """
            let getNegative: {} -> Nat = {} -> -10
            getNegative
            """.trimIndent()
        )
    }

    // ===== Edge cases =====

    @Test
    fun `test Nat with arithmetic operations`() {
        assertTypeChecks(
            """
            let x = 10n
            let y = 5n
            let result = x + y
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    @Test
    fun `test subtraction of Nat values is not allowed`() {
        // Nat - Nat is not allowed because the result can be negative
        // Use nat::subtract_to_int instead which returns Int
        assertTypeCheckFails(
            """
            let x = 10n
            let y = 5n
            let result = x - y
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test Nat in list`() {
        assertTypeChecks(
            """
            let numbers: [Nat] = [1n, 2n, 3n]
            numbers
            """.trimIndent(),
            "numbers",
            "[Nat]"
        )
    }

    @Test
    fun `test explicit Nat type annotation on variable`() {
        assertTypeChecks(
            """
            let x = 100n
            let y = x
            y
            """.trimIndent(),
            "y",
            "Nat"
        )
    }
}
