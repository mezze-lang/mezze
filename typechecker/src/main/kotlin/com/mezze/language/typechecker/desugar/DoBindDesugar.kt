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
 *   - wrap the do-block's result in `Ok {val = result}`
 *   - wrap the whole do-block in `perform ExceptionToResult in ...`
 *
 * Once rewritten, the typechecker sees an ordinary user-written perform and
 * types it through the existing effect-handling machinery — no special-case
 * logic. Each `let` binding's type carries `<Exception>` (because `.try`
 * declares that effect), and the outer perform discharges it, producing
 * `Result _ _`. See `docs/design/do-bind-proposal.md` for the full design.
 *
 * The only "magic" is name resolution: the synthetic perform references
 * `ExceptionToResult` and `Exception` by name even when the user didn't
 * `use std::effects::exception::{...}`. `TypeChecker.injectExceptionToResultForBind`
 * loads those names into the import context for modules that contain `<-`.
 *
 * Recurses into all nested expressions: each `ParsedDo` is desugared
 * independently. Nested do-blocks with `<-` each get their own synthetic
 * wrap; do-blocks without `<-` are left alone.
 *
 * Pure value-shape rewrite — no type information used. If the RHS doesn't
 * impl Tryable, the typechecker produces a "type X does not implement
 * Tryable" error against `expr.try`. Users use plain `let` for
 * `<Exception>`-throwing calls.
 */
object DoBindDesugar {

    /**
     * Result of running the do-bind desugar on a module.
     *
     * @param module the rewritten module (Bind → Let; result wrapped in `Ok`).
     * @param bindLetLocs locs of every `ParsedStatement.Let` synthesized from
     *   a `pat <- expr` bind. Used by LSP to recognize bind-pattern hover
     *   positions (the synthesized value spans the whole bind line, confusing
     *   the standard name-region check). NO semantic effect. Empty when the
     *   module contains no `<-`, which TypeChecker uses to skip the
     *   `Exception` + `ExceptionToResult` import-context injection.
     */
    data class Result(
        val module: ParsedModule,
        val bindLetLocs: Set<Loc> = emptySet(),
    )

    fun desugar(module: ParsedModule): Result {
        val locs = mutableSetOf<Loc>()
        val rewritten = module.copy(decls = module.decls.map { decl ->
            when (decl) {
                is ParsedLetDecl -> decl.copy(value = desugarBindsInExpr(decl.value) { locs.add(it) })
                is ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op ->
                    op.copy(implementation = desugarBindsInExpr(op.implementation) { locs.add(it) })
                })
                is ParsedTopLevelExpr -> decl.copy(expression = desugarBindsInExpr(decl.expression) { locs.add(it) })
                else -> decl
            }
        })
        return Result(module = rewritten, bindLetLocs = locs)
    }

    // ------------------------------------------------------------------
    // Implementation
    // ------------------------------------------------------------------

    private fun desugarBindsInExpr(expr: ParsedExpr, onBindFound: (Loc) -> Unit): ParsedExpr = when (expr) {
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
        // Leaf nodes — no children to recurse into. Listed explicitly (not
        // `else -> expr`) so that adding a new ParsedExpr subtype forces a
        // touch here; otherwise `<-` inside a new node would silently not
        // desugar.
        is com.mezze.language.parsedast.ParsedIntLit,
        is com.mezze.language.parsedast.ParsedNatLit,
        is com.mezze.language.parsedast.ParsedFloatLit,
        is com.mezze.language.parsedast.ParsedFracLit,
        is com.mezze.language.parsedast.ParsedCharLit,
        is com.mezze.language.parsedast.ParsedIdentifier,
        is com.mezze.language.parsedast.ParsedPipePlaceholder -> expr
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
     * Name resolution: `ExceptionToResult` must resolve even when the user
     * didn't `use std::effects::exception::{ExceptionToResult}`. That's
     * handled by `TypeChecker.injectExceptionToResultForBind`, which adds
     * `Exception` + `ExceptionToResult` to the import context for any module
     * whose `bindLetLocs` is non-empty.
     */
    private fun rewriteDoWithBinds(
        doExpr: ParsedDo,
        onBindFound: (Loc) -> Unit,
    ): ParsedPerform {
        // 1. Rewrite each Bind statement to a `let pat = expr.try {}`,
        //    notifying the caller of every original Bind loc so it can be
        //    flagged on the emitted TypedStatement.Let later.
        val rewrittenStatements = doExpr.statements.map { stmt ->
            when (stmt) {
                is ParsedStatement.Bind -> {
                    onBindFound(stmt.loc)
                    ParsedStatement.Let(
                        pattern = stmt.pattern,
                        typeAnnotation = null,
                        value = buildTryableTryCall(stmt.expr, stmt.loc),
                        loc = stmt.loc,
                    )
                }
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
     * Performer name emitted by the synthetic perform wrapping a do-block
     * with `<-`. Resolved through the import context (Exception + this
     * performer are injected by `TypeChecker.injectExceptionToResultForBind`).
     */
    const val EXCEPTION_TO_RESULT_NAME = "ExceptionToResult"

    /**
     * Effect name discharged by the synthetic perform. Same injection rule
     * as [EXCEPTION_TO_RESULT_NAME].
     */
    const val EXCEPTION_EFFECT_NAME = "Exception"

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
