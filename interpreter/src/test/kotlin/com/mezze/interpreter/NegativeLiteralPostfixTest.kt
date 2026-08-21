package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Regressions from removing the unary `-` operator (operator-minimization).
 *
 * B8: `x - -5` (subtract a negative literal) must parse as `x - (-5)`, not be
 *     rejected as an invalid `--` operator.
 * B9: postfix `.field` / `.method {}` must attach to a negative literal, e.g.
 *     `(-5).neg {}` and `-5.abs {}` — the `-N` literal fold must flow through
 *     postfixExpression.
 */
class NegativeLiteralPostfixTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== B8: subtract / add a negative literal =====

    @Test
    fun `subtract a negative int literal`() {
        assertEquals(15L, eval("let x = 10\nx - -5"))   // 10 - (-5) = 15
    }

    @Test
    fun `add a negative int literal`() {
        assertEquals(5L, eval("let x = 10\nx + -5"))    // 10 + (-5) = 5
    }

    @Test
    fun `subtract a negative float literal`() {
        assertEquals(4.0, eval("let x = 2.5\nx - -1.5") as Double, 1e-9)
    }

    @Test
    fun `subtract a negative literal in a chain`() {
        assertEquals(8L, eval("1 - -3 - -4"))           // 1-(-3)-(-4) = 1+3+4 = 8
    }

    // ===== B9: postfix method/field on a negative literal =====

    @Test
    fun `neg method on a parenthesized negative literal`() {
        assertEquals(5L, eval("(-5).neg {}"))
    }

    @Test
    fun `abs method on a negative int literal via std import`() {
        // `.abs {}` is an inherent Int method; -5 must accept postfix `.abs {}`.
        assertEquals(5L, eval("-5.abs {}"))
    }

    @Test
    fun `to_float method on a negative int literal`() {
        assertEquals(-5.0, eval("-5.to_float {}") as Double, 1e-9)
    }

    @Test
    fun `field access on a negative-literal record value`() {
        // negative literal as a record field, then read it back
        assertEquals(-3L, eval("let p = { x = -3, y = -4 }\np.x"))
    }
}
