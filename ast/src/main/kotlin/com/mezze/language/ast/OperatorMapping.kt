package com.mezze.language.ast

object OperatorMapping {

    data class BinaryOp(val ability: String, val method: String)

    val BINARY_OPERATORS: Map<String, BinaryOp> = mapOf(
        "+" to BinaryOp("NumAdd", "add"),
        "-" to BinaryOp("NumSub", "sub"),
        "*" to BinaryOp("NumMul", "mul"),
        "/" to BinaryOp("NumDiv", "div"),
        "%" to BinaryOp("NumMod", "mod"),
        "==" to BinaryOp("Eq", "eq"),
        "!=" to BinaryOp("Eq", "neq"),
        "<" to BinaryOp("Ord", "lt"),
        ">" to BinaryOp("Ord", "gt"),
        "<=" to BinaryOp("Ord", "le"),
        ">=" to BinaryOp("Ord", "ge"),
        "&&" to BinaryOp("LogicalAnd", "and"),
        "||" to BinaryOp("LogicalOr", "or")
    )

    fun getBinaryOp(operator: String): BinaryOp? = BINARY_OPERATORS[operator]
}
