package com.mezze.language.ast

object OperatorMapping {

    /**
     *  Syntactic tag for the ability an operator desugars to. This is NOT a
     *  name-string used for dispatch: downstream identity resolves via the
     *  ability's content hash (WellKnownHashConstants, in the typechecker).
     *  The enum exists only so callers that need the ability identity map the
     *  operator kind → hash at the consumer, never comparing a name string.
     */
    enum class OpAbility {
        NumAdd, NumSub, NumMul, NumDiv, NumMod, Eq, Ord, LogicalAnd, LogicalOr
    }

    data class BinaryOp(val ability: OpAbility, val method: String)

    val BINARY_OPERATORS: Map<String, BinaryOp> = mapOf(
        "+" to BinaryOp(OpAbility.NumAdd, "add"),
        "-" to BinaryOp(OpAbility.NumSub, "sub"),
        "*" to BinaryOp(OpAbility.NumMul, "mul"),
        "/" to BinaryOp(OpAbility.NumDiv, "div"),
        "%" to BinaryOp(OpAbility.NumMod, "mod"),
        "==" to BinaryOp(OpAbility.Eq, "eq"),
        "!=" to BinaryOp(OpAbility.Eq, "neq"),
        "<" to BinaryOp(OpAbility.Ord, "lt"),
        ">" to BinaryOp(OpAbility.Ord, "gt"),
        "<=" to BinaryOp(OpAbility.Ord, "le"),
        ">=" to BinaryOp(OpAbility.Ord, "ge"),
        "&&" to BinaryOp(OpAbility.LogicalAnd, "and"),
        "||" to BinaryOp(OpAbility.LogicalOr, "or")
    )

    fun getBinaryOp(operator: String): BinaryOp? = BINARY_OPERATORS[operator]
}
