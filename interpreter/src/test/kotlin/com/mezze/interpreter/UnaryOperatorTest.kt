package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for unary operators (negation).
 * Migrated from V1 to V2 with unboxed values.
 */
class UnaryOperatorTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test negative integer literals`() {
        assertEquals(-5L, eval("let neg5 = -5\nneg5"))
        assertEquals(0L, eval("let neg0 = -0\nneg0"))
        assertEquals(-100L, eval("let neg100 = -100\nneg100"))
    }

    @Test
    fun `test negative float literals`() {
        assertEquals(-3.14, eval("let negPi = -3.14\nnegPi") as Double, 0.001)
        assertEquals(0.0, eval("let negZero = -0.0\nnegZero") as Double, 0.001)
        assertEquals(-0.001, eval("let negSmall = -0.001\nnegSmall") as Double, 0.001)
    }

    @Test
    fun `test negative expressions`() {
        assertEquals(-8L, eval("let negSum = -(5 + 3)\nnegSum"))
        assertEquals(-8L, eval("let negProduct = -(4 * 2)\nnegProduct"))
        assertEquals(-7L, eval("let negDifference = -(10 - 3)\nnegDifference"))
    }

    @Test
    fun `test negative in arithmetic operations - sum`() {
        val code = """
            let a = -10
            let b = -5
            a + b
        """
        assertEquals(-15L, eval(code)) // -10 + (-5)
    }

    @Test
    fun `test negative in arithmetic operations - difference`() {
        val code = """
            let a = -10
            let b = -5
            a - b
        """
        assertEquals(-5L, eval(code)) // -10 - (-5)
    }

    @Test
    fun `test negative in arithmetic operations - product`() {
        val code = """
            let a = -10
            let b = -5
            a * b
        """
        assertEquals(50L, eval(code)) // -10 * (-5)
    }

    @Test
    fun `test negative in arithmetic operations - quotient`() {
        val code = """
            let a = -10
            let b = -5
            a / b
        """
        assertEquals(2L, eval(code)) // -10 / (-5)
    }

    @Test
    fun `test negative with variables`() {
        val code = """
            let x = 7
            let negX = -x
            negX
        """
        assertEquals(-7L, eval(code))
    }

    @Test
    fun `test double negative with variables`() {
        val code = """
            let y = -3
            let negY = -y
            negY
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test negative in function parameters`() {
        val code = """
            let square = {x} -> x * x
            square {x = -4}
        """
        assertEquals(16L, eval(code)) // (-4)²
    }

    @Test
    fun `test negative in function parameters - second`() {
        val code = """
            let square = {x} -> x * x
            square {x = -7}
        """
        assertEquals(49L, eval(code)) // (-7)²
    }

    @Test
    fun `test negative in conditionals - is negative`() {
        val code = """
            let x = -5
            if x < 0 then True else False
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `test negative in conditionals - abs value`() {
        val code = """
            let x = -5
            if x < 0 then -x else x
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test negative with records`() {
        val code = """
            let point = {x = -3, y = -4}
            point.x
        """
        assertEquals(-3L, eval(code))
    }

    @Test
    fun `test negative with records - negated`() {
        val code = """
            let point = {x = -3, y = -4}
            let negPoint = {x = -point.x, y = -point.y}
            negPoint.x
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test negative with pipe operator`() {
        val code = """
            let negate = {x} -> -x
            5 |> negate {x = @}
        """
        assertEquals(-5L, eval(code))
    }

    @Test
    fun `test negative with pipe operator - double negate`() {
        val code = """
            let negate = {x} -> -x
            -3 |> negate {x = @}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test complex negative expressions - 1`() {
        val code = """
            let complex1 = -(3 + 4) * -2
            complex1
        """
        assertEquals(14L, eval(code)) // -(3+4) * -2 = -7 * -2 = 14
    }

    @Test
    fun `test complex negative expressions - 2`() {
        val code = """
            let complex2 = -((5 * 2) - 3)
            complex2
        """
        assertEquals(-7L, eval(code)) // -((5*2) - 3) = -(10-3) = -7
    }

    @Test
    fun `test complex negative expressions - 3`() {
        val code = """
            let complex3 = -(10 / 2) + -(3 * 2)
            complex3
        """
        assertEquals(-11L, eval(code)) // -(10/2) + -(3*2) = -5 + -6 = -11
    }

    @Test
    fun `test negative with float arithmetic - sum`() {
        val code = """
            let a = -2.5
            let b = -1.5
            a + b
        """
        assertEquals(-4.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test negative with float arithmetic - product`() {
        val code = """
            let a = -2.5
            let b = -1.5
            a * b
        """
        assertEquals(3.75, eval(code) as Double, 0.001)
    }

    @Test
    fun `test negative zero edge cases - int`() {
        val code = """
            let negZeroInt = -0
            negZeroInt
        """
        assertEquals(0L, eval(code))
    }

    @Test
    fun `test negative zero edge cases - float`() {
        val code = """
            let negZeroFloat = -0.0
            negZeroFloat
        """
        assertEquals(0.0, eval(code) as Double, 0.001)
    }
}
