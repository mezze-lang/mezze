package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for OperatorMapping - the single source of truth for operator desugaring.
 */
class OperatorMappingTest {

    // ============================================================================
    // Binary Operator Tests
    // ============================================================================

    @Test
    fun `test arithmetic binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("NumAdd", "add"), OperatorMapping.getBinaryOp("+"))
        assertEquals(OperatorMapping.BinaryOp("NumSub", "sub"), OperatorMapping.getBinaryOp("-"))
        assertEquals(OperatorMapping.BinaryOp("NumMul", "mul"), OperatorMapping.getBinaryOp("*"))
        assertEquals(OperatorMapping.BinaryOp("NumDiv", "div"), OperatorMapping.getBinaryOp("/"))
        assertEquals(OperatorMapping.BinaryOp("NumMod", "mod"), OperatorMapping.getBinaryOp("%"))
    }

    @Test
    fun `test equality binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("Eq", "eq"), OperatorMapping.getBinaryOp("=="))
        assertEquals(OperatorMapping.BinaryOp("Eq", "neq"), OperatorMapping.getBinaryOp("!="))
    }

    @Test
    fun `test ordering binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("Ord", "lt"), OperatorMapping.getBinaryOp("<"))
        assertEquals(OperatorMapping.BinaryOp("Ord", "gt"), OperatorMapping.getBinaryOp(">"))
        assertEquals(OperatorMapping.BinaryOp("Ord", "le"), OperatorMapping.getBinaryOp("<="))
        assertEquals(OperatorMapping.BinaryOp("Ord", "ge"), OperatorMapping.getBinaryOp(">="))
    }

    @Test
    fun `test logical binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("LogicalAnd", "and"), OperatorMapping.getBinaryOp("&&"))
        assertEquals(OperatorMapping.BinaryOp("LogicalOr", "or"), OperatorMapping.getBinaryOp("||"))
    }

    @Test
    fun `test unknown binary operator returns null`() {
        assertNull(OperatorMapping.getBinaryOp("//"))  // fraction literal, not operator
        assertNull(OperatorMapping.getBinaryOp("~"))
        assertNull(OperatorMapping.getBinaryOp("**"))
    }

    // ============================================================================
    // Unary Operator Tests
    // ============================================================================

    @Test
    fun `test unary minus uses NumSub neg`() {
        val op = OperatorMapping.getUnaryOp("-")
        assertNotNull(op)
        assertEquals("NumSub", op!!.ability)
        assertEquals("neg", op.method)
        assertEquals("n", op.fieldName)
    }

    @Test
    fun `test unary not uses LogicalNot not`() {
        val op = OperatorMapping.getUnaryOp("!")
        assertNotNull(op)
        assertEquals("LogicalNot", op!!.ability)
        assertEquals("not", op.method)
        assertEquals("val", op.fieldName)
    }

    @Test
    fun `test unknown unary operator returns null`() {
        assertNull(OperatorMapping.getUnaryOp("+"))  // not a unary operator
        assertNull(OperatorMapping.getUnaryOp("~"))  // not a Funk operator
    }

    // ============================================================================
    // Category Tests
    // ============================================================================

    @Test
    fun `test arithmetic operator detection`() {
        assertTrue(OperatorMapping.isArithmeticOperator("+"))
        assertTrue(OperatorMapping.isArithmeticOperator("-"))
        assertTrue(OperatorMapping.isArithmeticOperator("*"))
        assertTrue(OperatorMapping.isArithmeticOperator("/"))
        assertTrue(OperatorMapping.isArithmeticOperator("%"))

        assertFalse(OperatorMapping.isArithmeticOperator("=="))
        assertFalse(OperatorMapping.isArithmeticOperator("&&"))
    }

    @Test
    fun `test comparison operator detection`() {
        assertTrue(OperatorMapping.isComparisonOperator("=="))
        assertTrue(OperatorMapping.isComparisonOperator("!="))
        assertTrue(OperatorMapping.isComparisonOperator("<"))
        assertTrue(OperatorMapping.isComparisonOperator(">"))
        assertTrue(OperatorMapping.isComparisonOperator("<="))
        assertTrue(OperatorMapping.isComparisonOperator(">="))

        assertFalse(OperatorMapping.isComparisonOperator("+"))
        assertFalse(OperatorMapping.isComparisonOperator("&&"))
    }

    @Test
    fun `test logical operator detection`() {
        assertTrue(OperatorMapping.isLogicalOperator("&&"))
        assertTrue(OperatorMapping.isLogicalOperator("||"))
        assertTrue(OperatorMapping.isLogicalOperator("!"))

        assertFalse(OperatorMapping.isLogicalOperator("+"))
        assertFalse(OperatorMapping.isLogicalOperator("=="))
    }

    @Test
    fun `test binary vs unary operator detection`() {
        // - is both binary and unary
        assertTrue(OperatorMapping.isBinaryOperator("-"))
        assertTrue(OperatorMapping.isUnaryOperator("-"))

        // + is only binary
        assertTrue(OperatorMapping.isBinaryOperator("+"))
        assertFalse(OperatorMapping.isUnaryOperator("+"))

        // ! is only unary
        assertFalse(OperatorMapping.isBinaryOperator("!"))
        assertTrue(OperatorMapping.isUnaryOperator("!"))
    }

    // ============================================================================
    // Special Cases
    // ============================================================================

    @Test
    fun `test fraction slash is NOT an operator`() {
        // // is for fraction literals, not an operator
        assertNull(OperatorMapping.getBinaryOp("//"))
        assertFalse(OperatorMapping.isBinaryOperator("//"))
    }

    @Test
    fun `test there is no NumNeg ability`() {
        // Unary minus uses NumSub.neg, not a separate NumNeg
        assertFalse(OperatorMapping.OPERATOR_ABILITIES.contains("NumNeg"))

        val unaryMinus = OperatorMapping.getUnaryOp("-")
        assertEquals("NumSub", unaryMinus?.ability)
    }

    @Test
    fun `test all operator abilities are present`() {
        val expectedAbilities = setOf(
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
            "Eq", "Ord",
            "LogicalAnd", "LogicalOr", "LogicalNot"
        )
        assertEquals(expectedAbilities, OperatorMapping.OPERATOR_ABILITIES)
    }
}
