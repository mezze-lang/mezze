package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
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

    private fun parseAndTypeCheck(code: String): Program {
        val parser = FunkParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast)
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

    // ===== Valid Nat assignments =====

    @Test
    fun `test assign positive integer to Nat`() {
        assertTypeChecks(
            """
            let x: Nat = 5
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
            let x: Nat = 0
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
            let x: Nat = 1000000
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
            let addOne: { n: Nat } -> Nat = { n } -> n + 1
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
            let getPositive: {} -> Nat = {} -> 42
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
            let processNat: { n: Nat } -> Nat = { n } -> n + 1
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
            let x: Nat = 10
            let y: Nat = 5
            let result = x + y
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    @Test
    fun `test subtraction of Nat values`() {
        // Subtraction of Nat values may produce Nat (depending on implementation)
        assertTypeChecks(
            """
            let x: Nat = 10
            let y: Nat = 5
            let result = x - y
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    @Test
    fun `test Nat in list`() {
        assertTypeChecks(
            """
            let numbers: [Nat] = [1, 2, 3]
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
            let x: Nat = 100
            let y = x
            y
            """.trimIndent(),
            "y",
            "Nat"
        )
    }
}
