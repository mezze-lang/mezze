package com.mezze.language.parser

import com.mezze.language.ast.OperatorMapping
import com.mezze.language.ast.OperatorMapping.OpAbility
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OperatorMappingTest {

    @Test
    fun `test arithmetic binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("+", OpAbility.NumAdd, "add"), OperatorMapping.getBinaryOp("+"))
        assertEquals(OperatorMapping.BinaryOp("-", OpAbility.NumSub, "sub"), OperatorMapping.getBinaryOp("-"))
        assertEquals(OperatorMapping.BinaryOp("*", OpAbility.NumMul, "mul"), OperatorMapping.getBinaryOp("*"))
        assertEquals(OperatorMapping.BinaryOp("/", OpAbility.NumDiv, "div"), OperatorMapping.getBinaryOp("/"))
        assertEquals(OperatorMapping.BinaryOp("%", OpAbility.NumMod, "mod"), OperatorMapping.getBinaryOp("%"))
    }

    @Test
    fun `test equality binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("==", OpAbility.Eq, "eq"), OperatorMapping.getBinaryOp("=="))
        assertEquals(OperatorMapping.BinaryOp("!=", OpAbility.Eq, "neq"), OperatorMapping.getBinaryOp("!="))
    }

    @Test
    fun `test ordering binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("<", OpAbility.Ord, "lt"), OperatorMapping.getBinaryOp("<"))
        assertEquals(OperatorMapping.BinaryOp(">", OpAbility.Ord, "gt"), OperatorMapping.getBinaryOp(">"))
        assertEquals(OperatorMapping.BinaryOp("<=", OpAbility.Ord, "le"), OperatorMapping.getBinaryOp("<="))
        assertEquals(OperatorMapping.BinaryOp(">=", OpAbility.Ord, "ge"), OperatorMapping.getBinaryOp(">="))
    }

    @Test
    fun `test logical binary operators`() {
        assertEquals(OperatorMapping.BinaryOp("&&", OpAbility.LogicalAnd, "and"), OperatorMapping.getBinaryOp("&&"))
        assertEquals(OperatorMapping.BinaryOp("||", OpAbility.LogicalOr, "or"), OperatorMapping.getBinaryOp("||"))
    }

    @Test
    fun `test unknown binary operator returns null`() {
        assertNull(OperatorMapping.getBinaryOp("//"))
        assertNull(OperatorMapping.getBinaryOp("~"))
        assertNull(OperatorMapping.getBinaryOp("**"))
    }

    @Test
    fun `test fraction slash is NOT an operator`() {
        assertNull(OperatorMapping.getBinaryOp("//"))
    }

    @Test
    fun `isEquality only for double-equals not not-equals`() {
        assertTrue(OperatorMapping.isEquality("=="))
        assertFalse(OperatorMapping.isEquality("!="))
        assertFalse(OperatorMapping.isEquality("<"))
        assertFalse(OperatorMapping.isEquality("//"))
        assertTrue(OperatorMapping.getBinaryOp("==")!!.isEquality)
        assertFalse(OperatorMapping.getBinaryOp("!=")!!.isEquality)
    }

    @Test
    fun `parse resolves a known operator and preserves its symbol`() {
        val plus = OperatorMapping.parse("+")
        assertEquals("+", plus.symbol)
        assertEquals(OpAbility.NumAdd, plus.resolved?.ability)
        assertEquals("+", plus.toString())
    }

    @Test
    fun `parse of an unknown lexeme round-trips with null resolved`() {
        val bogus = OperatorMapping.parse("<?>")
        assertEquals("<?>", bogus.symbol)
        assertNull(bogus.resolved)
        assertFalse(bogus.isEquality)
        assertEquals("<?>", bogus.toString())
    }
}
