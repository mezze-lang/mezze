package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.WarningCode

/**
 * #84 linter: purely-syntactic dead / redundant code lints. Walks the parsed
 * module (before any desugaring — `if` still exists as [ParsedIf], operators as
 * [ParsedBinaryOp]) and flags patterns that are legal but almost
 * always a mistake. No type information is needed, so this runs independent of
 * constraint solving.
 *
 * Emits, on by default and non-fatal:
 *  - [WarningCode.W0301_DISCARDED_VALUE]   — a pure value computed as a bare
 *    do-block statement and thrown away.
 *  - [WarningCode.W0302_CONSTANT_CONDITION] — an `if`/`match` whose condition is
 *    a boolean literal, so one branch is dead.
 *  - [WarningCode.W0303_REDUNDANT_BOOLEAN]  — `!(!b)`, `if b then True else
 *    False`, `x == True`, etc.
 */
object DeadCodeLinter {

    /** A single lint hit — turned into a TypeCheckWarning by the caller. */
    data class Finding(val message: String, val loc: Loc, val code: WarningCode)

    private val BOOL_LITERALS = setOf("True", "False")

    fun lint(module: ParsedModule): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (decl in module.decls) {
            declBody(decl)?.let { walkExpr(it, findings) }
        }
        return findings
    }

    /** The expression body of a declaration, if it has one. */
    private fun declBody(decl: ParsedDecl): ParsedExpr? = when (decl) {
        is ParsedLetDecl -> decl.value
        is ParsedTopLevelExpr -> decl.expression
        else -> null
    }

    private fun isBoolLiteral(e: ParsedExpr): Boolean =
        e is ParsedVariantConstruct && e.value == null && e.constructor in BOOL_LITERALS

    /**
     * A syntactically PURE value: computing it has no effect, so discarding it
     * as a bare statement is always pointless. Deliberately conservative —
     * anything that might perform an effect or call user code (function calls,
     * method calls, `perform`, pipes, `do`) is NOT pure and is left alone, so
     * side-effecting statements never warn.
     */
    private fun isPureValue(e: ParsedExpr): Boolean = when (e) {
        is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
        is ParsedStringLit, is ParsedCharLit -> true
        is ParsedIdentifier -> true
        is ParsedBinaryOp -> isPureValue(e.left) && isPureValue(e.right)
        is ParsedParens -> isPureValue(e.expr)
        is ParsedMemberAccess -> isPureValue(e.receiver) // plain field access
        is ParsedRecordLit -> e.entries.all { entry ->
            when (entry) {
                is ParsedRecordEntry.Field -> entry.value?.let { isPureValue(it) } ?: true
                is ParsedRecordEntry.Spread -> isPureValue(entry.expr)
            }
        }
        else -> false
    }

    private fun walkExpr(expr: ParsedExpr, findings: MutableList<Finding>) {
        checkRedundantBoolean(expr, findings)
        checkConstantCondition(expr, findings)

        when (expr) {
            is ParsedDo -> {
                for (stmt in expr.statements) {
                    if (stmt is ParsedStatement.Expr && isPureValue(stmt.expr)) {
                        findings.add(Finding(
                            message = "This expression's result is computed and then discarded. " +
                                "Bind it with `let`, or remove it if it has no effect.",
                            loc = stmt.loc,
                            code = WarningCode.W0301_DISCARDED_VALUE,
                        ))
                    }
                }
            }
            else -> {}
        }

        for (child in expr.children()) walkExpr(child, findings)
    }

    private fun checkConstantCondition(expr: ParsedExpr, findings: MutableList<Finding>) {
        val condition = when (expr) {
            is ParsedIf -> expr.condition
            is ParsedMatch -> expr.scrutinee
            else -> null
        } ?: return
        if (isBoolLiteral(condition)) {
            val kind = if (expr is ParsedIf) "`if`" else "`match`"
            findings.add(Finding(
                message = "The condition of this $kind is the constant " +
                    "`${(condition as ParsedVariantConstruct).constructor}`, so one branch can never run. " +
                    "Simplify to the branch that always executes.",
                loc = expr.loc,
                code = WarningCode.W0302_CONSTANT_CONDITION,
            ))
        }
    }

    private fun checkRedundantBoolean(expr: ParsedExpr, findings: MutableList<Finding>) {
        val msg: String? = when {
            // (b.not {}).not {} -> b
            isBoolNot(expr) && unwrap(boolNotReceiver(expr)!!).let { isBoolNot(it) } ->
                "`(b.not {}).not {}` is just `b` — remove the double negation."

            // if b then True else False  -> b   (and the inverted form)
            expr is ParsedIf && isBoolLiteral(expr.thenBranch) && isBoolLiteral(expr.elseBranch) ->
                "This `if` returns a boolean literal in both branches — it can be replaced by the " +
                "condition itself (or its negation)."

            // x == True / True == x / x == False / False == x
            expr is ParsedBinaryOp && expr.op == "==" &&
                (isBoolLiteral(expr.left) || isBoolLiteral(expr.right)) ->
                "Comparing a boolean to a boolean literal is redundant — `x == True` is `x`, " +
                "`x == False` is `x.not {}`."

            else -> null
        }
        if (msg != null) {
            findings.add(Finding(message = msg, loc = expr.loc, code = WarningCode.W0303_REDUNDANT_BOOLEAN))
        }
    }

    private fun unwrap(e: ParsedExpr): ParsedExpr = if (e is ParsedParens) unwrap(e.expr) else e

    /** True when `e` is a `<recv>.not {}` method call (the boolean-negation form). */
    private fun isBoolNot(e: ParsedExpr): Boolean {
        val call = unwrap(e) as? ParsedFunctionCall ?: return false
        val member = call.function as? ParsedMemberAccess ?: return false
        return member.member == "not"
    }

    /** The receiver of a `<recv>.not {}` call, or null if `e` isn't one. */
    private fun boolNotReceiver(e: ParsedExpr): ParsedExpr? {
        val call = unwrap(e) as? ParsedFunctionCall ?: return null
        val member = call.function as? ParsedMemberAccess ?: return null
        return if (member.member == "not") member.receiver else null
    }
}
