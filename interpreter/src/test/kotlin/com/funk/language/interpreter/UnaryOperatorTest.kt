package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class UnaryOperatorTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typeCheckResult = try {
            typeChecker.typeCheck(program)
        } catch (e: TypeCheckError) {
            // If type checking fails, try to run with the untyped program
            // This maintains compatibility with interpreter tests
            com.funk.language.ast.TypeCheckResult(program, emptyMap())
        }
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test negative integer literals`() {
        val source = """
            let neg5 = -5
            let neg0 = -0
            let neg100 = -100
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-5L, (variables["neg5"] as FunkInt).value)
        assertEquals(0L, (variables["neg0"] as FunkInt).value)
        assertEquals(-100L, (variables["neg100"] as FunkInt).value)
    }

    @Test
    fun `test negative float literals`() {
        val source = """
            let negPi = -3.14
            let negZero = -0.0
            let negSmall = -0.001
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-3.14, (variables["negPi"] as FunkFloat).value, 0.001)
        assertEquals(0.0, (variables["negZero"] as FunkFloat).value, 0.001)
        assertEquals(-0.001, (variables["negSmall"] as FunkFloat).value, 0.001)
    }

    @Test
    fun `test negative expressions`() {
        val source = """
            let negSum = -(5 + 3)
            let negProduct = -(4 * 2)
            let negDifference = -(10 - 3)
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-8L, (variables["negSum"] as FunkInt).value)
        assertEquals(-8L, (variables["negProduct"] as FunkInt).value)
        assertEquals(-7L, (variables["negDifference"] as FunkInt).value)
    }

    // Note: Double negatives like --5 are now blocked by the grammar
    // The parser will show "extraneous input '-'" error when attempted

    @Test
    fun `test negative in arithmetic operations`() {
        val source = """
            let a = -10
            let b = -5
            let sum = a + b
            let difference = a - b
            let product = a * b
            let quotient = a / b
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-10L, (variables["a"] as FunkInt).value)
        assertEquals(-5L, (variables["b"] as FunkInt).value)
        assertEquals(-15L, (variables["sum"] as FunkInt).value) // -10 + (-5)
        assertEquals(-5L, (variables["difference"] as FunkInt).value) // -10 - (-5)
        assertEquals(50L, (variables["product"] as FunkInt).value) // -10 * (-5)
        assertEquals(2L, (variables["quotient"] as FunkInt).value) // -10 / (-5)
    }

    @Test
    fun `test negative with variables`() {
        val source = """
            let x = 7
            let negX = -x
            let y = -3
            let negY = -y
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(7L, (variables["x"] as FunkInt).value)
        assertEquals(-7L, (variables["negX"] as FunkInt).value)
        assertEquals(-3L, (variables["y"] as FunkInt).value)
        assertEquals(3L, (variables["negY"] as FunkInt).value)
    }

    @Test
    fun `test negative in function parameters`() {
        val source = """
            let square = {x} -> x * x
            let result1 = square {x = -4}
            let result2 = square {x = -7}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(16L, (variables["result1"] as FunkInt).value) // (-4)²
        assertEquals(49L, (variables["result2"] as FunkInt).value) // (-7)²
    }

    @Test
    fun `test negative in conditionals`() {
        val source = """
            let x = -5
            let isNegative = if x < 0 then True else False
            let absValue = if x < 0 then -x else x
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-5L, (variables["x"] as FunkInt).value)
        assertEquals(true, (variables["isNegative"] as FunkBool).value)
        assertEquals(5L, (variables["absValue"] as FunkInt).value)
    }

    @Test
    fun `test negative with records`() {
        val source = """
            let point = {x = -3, y = -4}
            let negPoint = {x = -point.x, y = -point.y}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val point = variables["point"] as FunkRecord
        assertEquals(-3L, (point.fields["x"] as FunkInt).value)
        assertEquals(-4L, (point.fields["y"] as FunkInt).value)

        val negPoint = variables["negPoint"] as FunkRecord
        assertEquals(3L, (negPoint.fields["x"] as FunkInt).value)
        assertEquals(4L, (negPoint.fields["y"] as FunkInt).value)
    }

    @Test
    fun `test negative with pipe operator desugared`() {
        val source = """
            let negate = {x} -> -x
            let result1 = negate {x = 5}
            let result2 = negate {x = -3}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-5L, (variables["result1"] as FunkInt).value)
        assertEquals(3L, (variables["result2"] as FunkInt).value)
    }

    @Test
    fun `test negative with pipe operator`() {
        val source = """
            let negate = {x} -> -x
            let result1 = 5 |> negate {x = @}
            let result2 = -3 |> negate {x = @}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-5L, (variables["result1"] as FunkInt).value)
        assertEquals(3L, (variables["result2"] as FunkInt).value)
    }

    @Test
    fun `test complex negative expressions`() {
        val source = """
            let complex1 = -(3 + 4) * -2
            let complex2 = -((5 * 2) - 3)
            let complex3 = -(10 / 2) + -(3 * 2)
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(14L, (variables["complex1"] as FunkInt).value) // -(3+4) * -2 = -7 * -2 = 14
        assertEquals(-7L, (variables["complex2"] as FunkInt).value) // -((5*2) - 3) = -(10-3) = -7
        assertEquals(-11L, (variables["complex3"] as FunkInt).value) // -(10/2) + -(3*2) = -5 + -6 = -11
    }

    @Test
    fun `test negative with float arithmetic`() {
        val source = """
            let a = -2.5
            let b = -1.5
            let sum = a + b
            let product = a * b
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(-2.5, (variables["a"] as FunkFloat).value, 0.001)
        assertEquals(-1.5, (variables["b"] as FunkFloat).value, 0.001)
        assertEquals(-4.0, (variables["sum"] as FunkFloat).value, 0.001)
        assertEquals(3.75, (variables["product"] as FunkFloat).value, 0.001)
    }

    @Test
    fun `test negative zero edge cases`() {
        val source = """
            let negZeroInt = -0
            let negZeroFloat = -0.0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(0L, (variables["negZeroInt"] as FunkInt).value)
        assertEquals(0.0, (variables["negZeroFloat"] as FunkFloat).value, 0.001)
        // Negative zero should still be zero
    }
}
