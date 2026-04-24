package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OperatorMappingTest {

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
        assertNull(OperatorMapping.getBinaryOp("//"))
        assertNull(OperatorMapping.getBinaryOp("~"))
        assertNull(OperatorMapping.getBinaryOp("**"))
    }

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
        assertNull(OperatorMapping.getUnaryOp("+"))
        assertNull(OperatorMapping.getUnaryOp("~"))
    }

    @Test
    fun `test fraction slash is NOT an operator`() {
        assertNull(OperatorMapping.getBinaryOp("//"))
    }

    @Test
    fun `test unary minus uses NumSub not NumNeg`() {
        val unaryMinus = OperatorMapping.getUnaryOp("-")
        assertEquals("NumSub", unaryMinus?.ability)
    }
}
