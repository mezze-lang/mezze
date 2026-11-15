package com.mezze.language.truffleir

import com.mezze.language.ast.OperatorMapping

/**
 * Which [InlineOp] an ability operation IS: `add` → `ADD`.
 *
 * `x + y` desugars to `x.add { r = y }` — a call through a field read — and
 * stays that way whenever the receiver's type was not known when the body was
 * compiled. Two places fold it back into arithmetic: the bytecode compiler, at
 * emit time, so a primitive receiver is never read as a record; and
 * [LetCopyPropagation], earlier, so a binding holding one can be substituted
 * and the loop it sits in can qualify for the fused tail-loop tier.
 *
 * ONE TABLE, because two would drift and the drift would be silent — a new
 * operator would simply stop folding, and the only symptom would be that
 * programs using it are slower. The binary half is DERIVED from
 * [OperatorMapping.BINARY_OPERATORS], which is the authoritative
 * operator → ability.method table, so a new operator cannot be added there
 * without appearing here; `InlineOpMappingTest` pins that every one of them has
 * an inline form.
 *
 * The two unary operations are listed on their own because `OperatorMapping`
 * covers binary operators only — `-x` → `NumSub.neg` and `!x` →
 * `LogicalNot.not` are mapped where unary parsing happens.
 */
object InlineOpMapping {

    /** Operator LEXEME → inline form. Keyed by symbol rather than by method so
     *  the derivation below can be checked against the authoritative table. */
    private val BY_SYMBOL: Map<String, InlineOp> = mapOf(
        "+" to InlineOp.ADD,
        "-" to InlineOp.SUB,
        "*" to InlineOp.MUL,
        "/" to InlineOp.DIV,
        "%" to InlineOp.MOD,
        "==" to InlineOp.EQ,
        "!=" to InlineOp.NEQ,
        "<" to InlineOp.LT,
        ">" to InlineOp.GT,
        "<=" to InlineOp.LE,
        ">=" to InlineOp.GE,
        "&&" to InlineOp.AND,
        "||" to InlineOp.OR,
    )

    private val BY_METHOD: Map<String, InlineOp> =
        OperatorMapping.BINARY_OPERATORS.values
            .mapNotNull { op -> BY_SYMBOL[op.symbol]?.let { op.method to it } }
            .toMap() + mapOf(
            "neg" to InlineOp.NEG,
            "not" to InlineOp.NOT,
        )

    /** The inline form of an ability operation, or null when it has none. */
    fun forAbilityMethod(name: String): InlineOp? = BY_METHOD[name]

    /** Operator symbols in the authoritative table with no inline form. Empty
     *  today; the test keeps it that way, so adding an operator that silently
     *  stops folding is a failure rather than a slowdown. */
    fun symbolsWithoutInlineForm(): List<String> =
        OperatorMapping.BINARY_OPERATORS.values
            .filter { BY_SYMBOL[it.symbol] == null }
            .map { it.symbol }

    /**
     * Fold `CallIndirect(ReadField(x, op), MakeRecord{ r = y })` into
     * `InlineBinaryOp(op, x, y)`, recursively, or return [node] unchanged.
     *
     * Deliberately the same shape test the bytecode compiler's own fold uses,
     * including handing a unary op its receiver on both sides, so folding here
     * cannot produce something that would not have been produced there.
     *
     * NOT for a tail call: the caller's position is not the callee's.
     */
    fun foldAbilityOp(node: NodeIR): NodeIR {
        val descended = node.transformChildren { foldAbilityOp(it) }
        if (descended !is CallIndirectIR || descended.isTailCall) return descended
        val fn = descended.function as? ReadFieldIR ?: return descended
        val arg = descended.arg as? MakeRecordIR ?: return descended
        val op = forAbilityMethod(fn.fieldName) ?: return descended
        val recv = fn.record
        if (op == InlineOp.NEG || op == InlineOp.NOT) {
            return InlineBinaryOpIR(op, recv, recv, descended.src)
        }
        val rhs = arg.fields.firstOrNull { it.name == "r" }?.value ?: return descended
        return InlineBinaryOpIR(op, recv, rhs, descended.src)
    }
}
