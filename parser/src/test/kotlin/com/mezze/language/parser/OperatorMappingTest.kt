package com.mezze.language.parser

import com.mezze.language.ast.OperatorMapping
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OperatorMappingTest {

    @Test
    fun `test arithmetic binary operators`() {
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.NumAdd, "add"), OperatorMapping.getBinaryOp("+"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.NumSub, "sub"), OperatorMapping.getBinaryOp("-"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.NumMul, "mul"), OperatorMapping.getBinaryOp("*"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.NumDiv, "div"), OperatorMapping.getBinaryOp("/"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.NumMod, "mod"), OperatorMapping.getBinaryOp("%"))
    }

    @Test
    fun `test equality binary operators`() {
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Eq, "eq"), OperatorMapping.getBinaryOp("=="))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Eq, "neq"), OperatorMapping.getBinaryOp("!="))
    }

    @Test
    fun `test ordering binary operators`() {
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Ord, "lt"), OperatorMapping.getBinaryOp("<"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Ord, "gt"), OperatorMapping.getBinaryOp(">"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Ord, "le"), OperatorMapping.getBinaryOp("<="))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.Ord, "ge"), OperatorMapping.getBinaryOp(">="))
    }

    @Test
    fun `test logical binary operators`() {
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.LogicalAnd, "and"), OperatorMapping.getBinaryOp("&&"))
        assertEquals(OperatorMapping.BinaryOp(OperatorMapping.OpAbility.LogicalOr, "or"), OperatorMapping.getBinaryOp("||"))
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
}
