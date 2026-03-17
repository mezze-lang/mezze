package com.mezze.language.parser

/**
 * Canonical operator-to-ability mapping for Mezze.
 *
 * This is the single source of truth for how operators desugar to ability method calls.
 * Used by the parser for desugaring, and exposed publicly for typechecker/interpreter use.
 *
 * ## Binary Operators
 * Binary operators desugar to method-call syntax with the left operand as receiver:
 * - `x + y` -> `x.add {r = y}`  (resolved to NumAdd ability)
 * - `x == y` -> `x.eq {r = y}`  (resolved to Eq ability)
 *
 * ## Unary Operators
 * Unary operators desugar to method-call syntax:
 * - `-x` -> `x.neg {}`  (resolved to NumSub ability)
 * - `!x` -> `x.not {}`  (resolved to LogicalNot ability)
 *
 * ## Special Cases
 * - `//` is NOT an operator - it creates fraction literals (e.g., `1//2` is a Frac)
 * - There is no `NumNeg` ability - unary minus uses `NumSub.neg`
 */
object OperatorMapping {

    /**
     * Binary operator definition.
     * @param ability The ability name (e.g., "NumAdd")
     * @param method The method name (e.g., "add")
     */
    data class BinaryOp(val ability: String, val method: String)

    /**
     * Unary operator definition.
     * @param ability The ability name (e.g., "NumSub")
     * @param method The method name (e.g., "neg")
     * @param fieldName The argument field name (e.g., "n")
     */
    data class UnaryOp(val ability: String, val method: String, val fieldName: String)

    /**
     * All binary operator mappings.
     */
    val BINARY_OPERATORS: Map<String, BinaryOp> = mapOf(
        // Arithmetic
        "+" to BinaryOp("NumAdd", "add"),
        "-" to BinaryOp("NumSub", "sub"),
        "*" to BinaryOp("NumMul", "mul"),
        "/" to BinaryOp("NumDiv", "div"),
        "%" to BinaryOp("NumMod", "mod"),

        // Equality
        "==" to BinaryOp("Eq", "eq"),
        "!=" to BinaryOp("Eq", "neq"),

        // Ordering
        "<" to BinaryOp("Ord", "lt"),
        ">" to BinaryOp("Ord", "gt"),
        "<=" to BinaryOp("Ord", "le"),
        ">=" to BinaryOp("Ord", "ge"),

        // Logical
        "&&" to BinaryOp("LogicalAnd", "and"),
        "||" to BinaryOp("LogicalOr", "or")
    )

    /**
     * All unary operator mappings.
     */
    val UNARY_OPERATORS: Map<String, UnaryOp> = mapOf(
        "-" to UnaryOp("NumSub", "neg", "n"),
        "!" to UnaryOp("LogicalNot", "not", "val")
    )

    /**
     * Get the binary operator definition for an operator symbol.
     */
    fun getBinaryOp(operator: String): BinaryOp? = BINARY_OPERATORS[operator]

    /**
     * Get the unary operator definition for an operator symbol.
     */
    fun getUnaryOp(operator: String): UnaryOp? = UNARY_OPERATORS[operator]

    /**
     * All arithmetic operator symbols.
     */
    val ARITHMETIC_OPERATORS: Set<String> = setOf("+", "-", "*", "/", "%")

    /**
     * All comparison operator symbols.
     */
    val COMPARISON_OPERATORS: Set<String> = setOf("==", "!=", "<", ">", "<=", ">=")

    /**
     * All logical operator symbols.
     */
    val LOGICAL_OPERATORS: Set<String> = setOf("&&", "||", "!")

    /**
     * All ability names used by operators.
     */
    val OPERATOR_ABILITIES: Set<String> = (
        BINARY_OPERATORS.values.map { it.ability } +
        UNARY_OPERATORS.values.map { it.ability }
    ).toSet()

    /**
     * Check if a string is an arithmetic operator.
     */
    fun isArithmeticOperator(op: String): Boolean = op in ARITHMETIC_OPERATORS

    /**
     * Check if a string is a comparison operator.
     */
    fun isComparisonOperator(op: String): Boolean = op in COMPARISON_OPERATORS

    /**
     * Check if a string is a logical operator.
     */
    fun isLogicalOperator(op: String): Boolean = op in LOGICAL_OPERATORS

    /**
     * Check if a string is a binary operator.
     */
    fun isBinaryOperator(op: String): Boolean = op in BINARY_OPERATORS

    /**
     * Check if a string is a unary operator.
     */
    fun isUnaryOperator(op: String): Boolean = op in UNARY_OPERATORS
}
