package com.mezze.language.ast

object OperatorMapping {

    data class BinaryOp(val ability: String, val method: String)

    data class UnaryOp(val ability: String, val method: String, val fieldName: String)

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

    val UNARY_OPERATORS: Map<String, UnaryOp> = mapOf(
        "-" to UnaryOp("NumSub", "neg", "n")
    )

    fun getBinaryOp(operator: String): BinaryOp? = BINARY_OPERATORS[operator]

    fun getUnaryOp(operator: String): UnaryOp? = UNARY_OPERATORS[operator]
}
