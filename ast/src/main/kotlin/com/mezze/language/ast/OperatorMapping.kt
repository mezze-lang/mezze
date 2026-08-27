package com.mezze.language.ast

object OperatorMapping {

    /**
     *  Syntactic tag for the ability an operator desugars to. This is NOT a
     *  name-string used for dispatch: downstream identity resolves via the
     *  ability's content hash (WellKnownHashConstants, in the typechecker).
     *  The enum exists only so callers that need the ability identity map the
     *  operator kind → hash at the consumer, never comparing a name string.
     */
    @kotlinx.serialization.Serializable
    enum class OpAbility {
        NumAdd, NumSub, NumMul, NumDiv, NumMod, Eq, Ord, LogicalAnd, LogicalOr
    }

    /**
     * A fully self-describing binary operator: its source [symbol] (the lexeme as
     * written, e.g. `"=="`), the [ability] it desugars to, and the ability [method]
     * it calls. `ParsedBinaryOp` carries one of these instead of a raw string, so a
     * lexeme is never re-recognized downstream — display uses [symbol], dispatch uses
     * [ability]/[method], and the content hasher uses [symbol] (hash-stable). #304
     */
    @kotlinx.serialization.Serializable
    data class BinaryOp(val symbol: String, val ability: OpAbility, val method: String) {
        /** True iff this is the equality operator `==` (Eq.eq), NOT `!=` (Eq.neq). */
        val isEquality: Boolean get() = ability == OpAbility.Eq && method == "eq"
    }

    val BINARY_OPERATORS: Map<String, BinaryOp> = listOf(
        BinaryOp("+", OpAbility.NumAdd, "add"),
        BinaryOp("-", OpAbility.NumSub, "sub"),
        BinaryOp("*", OpAbility.NumMul, "mul"),
        BinaryOp("/", OpAbility.NumDiv, "div"),
        BinaryOp("%", OpAbility.NumMod, "mod"),
        BinaryOp("==", OpAbility.Eq, "eq"),
        BinaryOp("!=", OpAbility.Eq, "neq"),
        BinaryOp("<", OpAbility.Ord, "lt"),
        BinaryOp(">", OpAbility.Ord, "gt"),
        BinaryOp("<=", OpAbility.Ord, "le"),
        BinaryOp(">=", OpAbility.Ord, "ge"),
        BinaryOp("&&", OpAbility.LogicalAnd, "and"),
        BinaryOp("||", OpAbility.LogicalOr, "or"),
    ).associateBy { it.symbol }

    fun getBinaryOp(operator: String): BinaryOp? = BINARY_OPERATORS[operator]

    /**
     * True iff [operator] is the equality operator `==`. Callers ask this instead of
     * comparing the raw lexeme `== "=="`, so the identity comes from [BINARY_OPERATORS]. #304
     */
    fun isEquality(operator: String): Boolean = getBinaryOp(operator)?.isEquality == true

    /**
     * Parse an operator lexeme into a structured [BinaryOperator]. A known operator
     * carries its resolved [BinaryOp] identity; an unrecognized lexeme still round-trips
     * (keeps its [symbol]) with [BinaryOperator.resolved] == null, so display and the
     * "unknown operator" diagnostic are preserved without a raw string escaping. #304
     */
    fun parse(operator: String): BinaryOperator = BinaryOperator(operator, getBinaryOp(operator))
}

/**
 * A binary operator as it appears in the parsed AST: the source [symbol] as written
 * (always present, used for display and content hashing) plus its [resolved] identity
 * when the lexeme names a known operator. Replaces the raw `op: String` on
 * `ParsedBinaryOp` so a lexeme is never re-recognized by string downstream. #304
 */
@kotlinx.serialization.Serializable
data class BinaryOperator(
    val symbol: String,
    val resolved: OperatorMapping.BinaryOp? = null,
) {
    val isEquality: Boolean get() = resolved?.isEquality == true
    override fun toString(): String = symbol
}
