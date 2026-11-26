package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedBoolLit
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedCharLit
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedFloatLit
import com.mezze.language.typedast.TypedFracLit
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedNatLit
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedStringLit
import com.mezze.language.typedast.TypedStringPart
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild

/**
 * W0301 for a statement that is a CALL performing nothing.
 *
 * A `do` statement's value is dropped; what makes it worth writing is what it
 * performs. [DeadCodeLinter] catches the syntactically pure statements (a literal,
 * a name, arithmetic) but cannot see a call's effects, so `double { x = 3 }` on its
 * own line — a pure call whose answer is thrown away — went unreported. With the
 * types solved, a call's effect row says it: a CLOSED, EMPTY row performs nothing.
 *
 * Conservative by construction, because a warning on code that does something is
 * worse than a missed one: only a call whose callee's solved arrow has a closed
 * empty row, whose arguments are plain values (literals, names, field reads,
 * records of those), and which is not a `__native` primitive (stdlib internals do
 * not always state their effects in a row). Anything else is left alone.
 */
object PureStatementLinter {

    data class Finding(val message: String, val loc: Loc, val code: WarningCode)

    fun lint(typed: TypedModule, substitution: Substitution): List<Finding> {
        val findings = mutableListOf<Finding>()

        fun plainValue(e: TypedExpr): Boolean = when (e) {
            is TypedIntLit, is TypedNatLit, is TypedFloatLit, is TypedFracLit,
            is TypedBoolLit, is TypedCharLit, is TypedVar -> true
            is TypedStringLit -> e.parts.all { it !is TypedStringPart.Interpolation || plainValue(it.expr) }
            is TypedFieldAccess -> plainValue(e.record)
            is TypedRecord -> e.entries.all { entry ->
                when (entry) {
                    is TypedRecordEntry.Field -> plainValue(entry.value)
                    is TypedRecordEntry.Spread -> plainValue(entry.expr)
                }
            }
            else -> false
        }

        fun performsNothing(call: TypedCall): Boolean {
            val callee = call.function
            if (callee is TypedVar && callee.name.startsWith("__native")) return false
            val arrow = substitution.apply(callee.type) as? MezzeType.FunctionType ?: return false
            val row = arrow.effects ?: return plainValue(call.arg)
            return row.rowVar == null && row.allEffectNames.isEmpty() && plainValue(call.arg)
        }

        fun walk(e: TypedExpr) {
            if (e is TypedBlock) {
                for (stmt in e.statements) {
                    val expr = (stmt as? TypedStatement.Expr)?.expr as? TypedCall ?: continue
                    if (performsNothing(expr)) findings += Finding(
                        message = "This call performs nothing and its result is dropped, so the " +
                            "statement has no effect. Use the result, or remove the call.",
                        loc = stmt.loc,
                        code = WarningCode.W0301_DISCARDED_VALUE,
                    )
                }
            }
            e.forEachChild { walk(it) }
        }

        for (decl in typed.decls) if (decl is TypedLetDecl) walk(decl.value)
        for (impl in typed.abilityImpls) impl.operations.values.forEach { walk(it) }
        return findings
    }
}
