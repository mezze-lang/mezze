package com.mezze.language.typechecker.desugar

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedBinaryOp
import com.mezze.language.parsedast.ParsedDo
import com.mezze.language.parsedast.ParsedEffectCall
import com.mezze.language.parsedast.ParsedExpr
import com.mezze.language.parsedast.ParsedFunctionCall
import com.mezze.language.parsedast.ParsedIf
import com.mezze.language.parsedast.ParsedImplAbilityDecl
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedMatch
import com.mezze.language.parsedast.ParsedMemberAccess
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedParens
import com.mezze.language.parsedast.ParsedPerform
import com.mezze.language.parsedast.ParsedPerformHandler
import com.mezze.language.parsedast.ParsedPipe
import com.mezze.language.parsedast.ParsedQualifiedRef
import com.mezze.language.parsedast.ParsedRecordEntry
import com.mezze.language.parsedast.ParsedRecordLit
import com.mezze.language.parsedast.ParsedSeqElement
import com.mezze.language.parsedast.ParsedSeqLit
import com.mezze.language.parsedast.ParsedStatement
import com.mezze.language.parsedast.ParsedStringLit
import com.mezze.language.parsedast.ParsedStringPart
import com.mezze.language.parsedast.ParsedTopLevelExpr
import com.mezze.language.parsedast.ParsedUnaryOp
import com.mezze.language.parsedast.ParsedVariantConstruct

/**
 * Do-bind desugar (`<-` syntax) for parsed modules.
 *
 * For every `ParsedDo` containing one or more `ParsedStatement.Bind` statements:
 *   - rewrite each `pat <- expr` to `let pat = expr.try {}`
 *   - wrap the do-block's result expression in `Ok {val = result}`
 *   - record the do-block's loc in `syntheticBindBlockLocs`
 *
 * The do-block's loc is recognized later by the constraint generator and
 * TypedAstBuilder. The constraint generator subtracts `<Exception>` from
 * the do-block's effect row (the synthetic `ExceptionToResult` "discharges"
 * it). TypedAstBuilder wraps the built TypedBlock in a TypedPerform whose
 * handler references the well-known `ExceptionToResult` performer + `Exception`
 * effect hashes — no `ParsedPerform` is ever emitted into the parsed AST,
 * so the user's namespace never has `ExceptionToResult` or `Exception`
 * resolvable (true scoping: the performer is invoked only by the synthetic
 * desugar machinery).
 *
 * See `docs/design/do-bind-proposal.md` for the full design.
 *
 * Recurses into all nested expressions: each `ParsedDo` is desugared
 * independently. Nested do-blocks with `<-` each get their own synthetic
 * wrap; do-blocks without `<-` are left alone.
 *
 * Pure value-shape rewrite — no type information used. `<-` always emits
 * `expr.try {}` (via dot-method syntax). If the RHS doesn't impl Tryable
 * (e.g., it's already a value with `<Exception>` in its row), the
 * typechecker produces a clean "type X does not implement Tryable" error.
 * Users use plain `let` for `<Exception>`-throwing calls.
 */
object DoBindDesugar {

    /**
     * Result of running the do-bind desugar on a module.
     *
     * @param module the rewritten module (Bind → Let; result wrapped in `Ok`).
     * @param hasBinds true if any `<-` was found in the module. When true,
     *   the TypeChecker injects `Exception` + `ExceptionToResult` into the
     *   import context so the desugar's synthetic `perform ExceptionToResult`
     *   resolves.
     */
    data class Result(
        val module: ParsedModule,
        val hasBinds: Boolean,
    )

    fun desugar(module: ParsedModule): Result {
        var sawBind = false
        val rewritten = module.copy(decls = module.decls.map { decl ->
            when (decl) {
                is ParsedLetDecl -> decl.copy(value = desugarBindsInExpr(decl.value) { sawBind = true })
                is ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op ->
                    op.copy(implementation = desugarBindsInExpr(op.implementation) { sawBind = true })
                })
                is ParsedTopLevelExpr -> decl.copy(expression = desugarBindsInExpr(decl.expression) { sawBind = true })
                else -> decl
            }
        })
        return Result(module = rewritten, hasBinds = sawBind)
    }

    // ------------------------------------------------------------------
    // Implementation
    // ------------------------------------------------------------------

    private fun desugarBindsInExpr(expr: ParsedExpr, onBindFound: () -> Unit): ParsedExpr = when (expr) {
        is ParsedDo -> {
            // Recurse into all sub-expressions first.
            val newStatements = expr.statements.map { stmt ->
                when (stmt) {
                    is ParsedStatement.Let -> stmt.copy(value = desugarBindsInExpr(stmt.value, onBindFound))
                    is ParsedStatement.Expr -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, onBindFound))
                    is ParsedStatement.Bind -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, onBindFound))
                }
            }
            val newResult = desugarBindsInExpr(expr.result, onBindFound)
            val processedDo = expr.copy(statements = newStatements, result = newResult)

            // If this do-block has any Bind statements, perform the rewrite.
            if (processedDo.statements.any { it is ParsedStatement.Bind }) {
                rewriteDoWithBinds(processedDo, onBindFound)
            } else {
                processedDo
            }
        }
        is ParsedFunctionCall -> expr.copy(
            function = desugarBindsInExpr(expr.function, onBindFound),
            arguments = desugarBindsInExpr(expr.arguments, onBindFound),
        )
        is ParsedLambda -> expr.copy(body = desugarBindsInExpr(expr.body, onBindFound))
        is ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
            when (e) {
                is ParsedRecordEntry.Field -> e.copy(value = e.value?.let { desugarBindsInExpr(it, onBindFound) })
                is ParsedRecordEntry.Spread -> e.copy(expr = desugarBindsInExpr(e.expr, onBindFound))
            }
        })
        is ParsedIf -> expr.copy(
            condition = desugarBindsInExpr(expr.condition, onBindFound),
            thenBranch = desugarBindsInExpr(expr.thenBranch, onBindFound),
            elseBranch = desugarBindsInExpr(expr.elseBranch, onBindFound),
        )
        is ParsedMatch -> expr.copy(
            scrutinee = desugarBindsInExpr(expr.scrutinee, onBindFound),
            cases = expr.cases.map { it.copy(body = desugarBindsInExpr(it.body, onBindFound)) },
        )
        is ParsedPerform -> expr.copy(body = desugarBindsInExpr(expr.body, onBindFound))
        is ParsedMemberAccess -> expr.copy(receiver = desugarBindsInExpr(expr.receiver, onBindFound))
        is ParsedParens -> expr.copy(expr = desugarBindsInExpr(expr.expr, onBindFound))
        is ParsedVariantConstruct -> expr.copy(value = expr.value?.let { desugarBindsInExpr(it, onBindFound) })
        is ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
            when (elem) {
                is ParsedSeqElement.Regular -> elem.copy(expr = desugarBindsInExpr(elem.expr, onBindFound))
                is ParsedSeqElement.Spread -> elem.copy(expr = desugarBindsInExpr(elem.expr, onBindFound))
            }
        })
        is ParsedEffectCall -> expr.copy(arguments = desugarBindsInExpr(expr.arguments, onBindFound))
        is ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
            when (part) {
                is ParsedStringPart.Interpolation -> part.copy(expr = desugarBindsInExpr(part.expr, onBindFound))
                else -> part
            }
        })
        is ParsedBinaryOp -> expr.copy(
            left = desugarBindsInExpr(expr.left, onBindFound),
            right = desugarBindsInExpr(expr.right, onBindFound),
        )
        is ParsedUnaryOp -> expr.copy(operand = desugarBindsInExpr(expr.operand, onBindFound))
        is ParsedPipe -> expr.copy(
            left = desugarBindsInExpr(expr.left, onBindFound),
            right = desugarBindsInExpr(expr.right, onBindFound),
        )
        else -> expr
    }

    /**
     * Rewrite a ParsedDo containing Bind statements into the desugared form.
     *
     * Input:
     *   do
     *     pat1 <- expr1
     *     stmt2
     *     pat3 <- expr3
     *     result
     *
     * Output (a real `perform ExceptionToResult in do { ... }`):
     *   perform ExceptionToResult in do
     *     let pat1 = expr1.try {}
     *     stmt2
     *     let pat3 = expr3.try {}
     *     Ok {val = result}
     *
     * From this point on, the typechecker sees an ordinary user-written
     * `perform ExceptionToResult in ...` and types it through the existing
     * effect-handling machinery — no special-case logic. Each `let` binding's
     * type carries `<Exception>` (because `.try {}` declares that effect),
     * and the outer perform discharges it, producing `Result _ _ <*>`.
     *
     * Only "magic" left: the `ExceptionToResult` name itself must resolve
     * even when the user didn't `use std::effects::exception::{ExceptionToResult}`.
     * The do-block loc is registered in `syntheticBindBlockLocs` so the
     * constraint generator can resolve the performer via well-known hashes
     * for performs inserted by this desugar.
     */
    private fun rewriteDoWithBinds(
        doExpr: ParsedDo,
        onBindFound: () -> Unit,
    ): ParsedPerform {
        onBindFound()
        // 1. Rewrite each Bind statement to a `let pat = expr.try {}`.
        val rewrittenStatements = doExpr.statements.map { stmt ->
            when (stmt) {
                is ParsedStatement.Bind -> ParsedStatement.Let(
                    pattern = stmt.pattern,
                    typeAnnotation = null,
                    value = buildTryableTryCall(stmt.expr, stmt.loc),
                    loc = stmt.loc,
                )
                else -> stmt
            }
        }

        // 2. Wrap the result in Ok {val = result}.
        val resultLoc = doExpr.result.loc
        val wrappedResult = ParsedVariantConstruct(
            constructor = "Ok",
            value = ParsedRecordLit(
                entries = listOf(
                    ParsedRecordEntry.Field(name = "val", value = doExpr.result, loc = resultLoc)
                ),
                loc = resultLoc,
            ),
            loc = resultLoc,
        )

        val rewrittenDo = doExpr.copy(statements = rewrittenStatements, result = wrappedResult)

        // 3. Wrap in `perform ExceptionToResult in <rewrittenDo>`.
        val performHandler = ParsedPerformHandler(
            performer = ParsedQualifiedRef(
                localName = EXCEPTION_TO_RESULT_NAME,
                qualifier = null,
                loc = doExpr.loc,
            ),
            params = null,
        )
        return ParsedPerform(
            handlers = listOf(performHandler),
            body = rewrittenDo,
            loc = doExpr.loc,
        )
    }

    /**
     * Performer name resolved at the constraint-generation phase only when
     * the enclosing perform's loc is in syntheticBindBlockLocs. The
     * corresponding hash is looked up via [WellKnownHashConstants.PERFORMER_EXCEPTIONTORESULT].
     */
    const val EXCEPTION_TO_RESULT_NAME = "ExceptionToResult"

    /**
     * Build a `expr.try {}` method-call expression.
     *
     * Uses dot-method syntax (rather than qualified `Tryable.try {it = expr}`)
     * so it goes through the existing method-call dispatch path that handles
     * ability impl resolution via dictionary passing.
     *
     * The member-access + function-call locs use the RHS expression's loc
     * so position-based lookups (LSP, error reporting) point at the user's
     * `<-` line, not at synthetic internals. The receiver keeps its own
     * loc (subexpression of the bind) — this matters when the typechecker
     * builds method-resolution maps keyed by parent-call loc vs receiver loc.
     */
    private fun buildTryableTryCall(expr: ParsedExpr, loc: Loc): ParsedFunctionCall {
        val tryAccess = ParsedMemberAccess(
            receiver = expr,
            member = "try",
            loc = loc,
        )
        val argRecord = ParsedRecordLit(entries = emptyList(), loc = loc)
        return ParsedFunctionCall(function = tryAccess, arguments = argRecord, loc = loc)
    }
}
