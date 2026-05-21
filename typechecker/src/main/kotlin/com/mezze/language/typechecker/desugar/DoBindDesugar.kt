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
import com.mezze.language.typechecker.module.ModuleImportContext

/**
 * Do-bind desugar (`<-` syntax) for parsed modules.
 *
 * For every `ParsedDo` containing one or more `ParsedStatement.Bind` statements:
 *   - rewrite each `pat <- expr` to `let pat = expr.try {}`
 *   - wrap the do-block's result expression in `Ok {val = result}`
 *   - wrap the entire ParsedDo in a synthetic `perform ExceptionToResult`
 *   - record the synthetic perform's loc in the returned `syntheticLocs` set
 *
 * The synthetic perform's loc must be recognized later by the constraint
 * generator (via the syntheticPerformLocs threaded through) so it can pass
 * `synthetic = true` to `removeEffectsFromType`, suppressing the `<*>`
 * marker on the enclosing function's signature.
 *
 * `ExceptionToResult` (the performer) and `Exception` (the effect) are NOT
 * carried by the prelude — keeping them out of users' modules. Instead,
 * `injectSyntheticPerformerCtx` adds them to the per-module import context
 * ONLY when the module contains `<-`. Users who want `ExceptionToResult` in
 * their own scope must explicitly `use std::effects::exception::{ExceptionToResult}`.
 *
 * See `docs/design/do-bind-proposal.md` for the full design.
 *
 * Recurses into all nested expressions: each `ParsedDo` is desugared
 * independently. Nested do-blocks with `<-` each get their own synthetic
 * perform; do-blocks without `<-` are left alone.
 *
 * Pure value-shape rewrite — no type information used. `<-` always emits
 * `Tryable.try {}` (via dot-method syntax). If the RHS doesn't impl
 * Tryable (e.g., it's already a value with `<Exception>` in its row), the
 * typechecker produces a clean "type X does not implement Tryable" error.
 * Users use plain `let` for `<Exception>`-throwing calls.
 */
object DoBindDesugar {

    /**
     * Result of running the do-bind desugar on a module.
     *
     * @param module the rewritten module (Bind statements rewritten to Let calls
     *   to `Tryable.try`, wrapped in synthetic perform).
     * @param syntheticLocs the locs of every synthetic perform inserted. Threaded
     *   to the constraint generator so it can suppress `<*>` for those.
     * @param hasBinds true if any `<-` was found in the module. When true, the
     *   typechecker needs to inject `Exception` effect + `ExceptionToResult`
     *   performer into the module's import context.
     */
    data class Result(
        val module: ParsedModule,
        val syntheticLocs: Set<Loc>,
        val hasBinds: Boolean,
    )

    fun desugar(module: ParsedModule): Result {
        val syntheticLocs = mutableSetOf<Loc>()
        val rewritten = module.copy(decls = module.decls.map { decl ->
            when (decl) {
                is ParsedLetDecl -> decl.copy(value = desugarBindsInExpr(decl.value, syntheticLocs))
                is ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op ->
                    op.copy(implementation = desugarBindsInExpr(op.implementation, syntheticLocs))
                })
                is ParsedTopLevelExpr -> decl.copy(expression = desugarBindsInExpr(decl.expression, syntheticLocs))
                else -> decl
            }
        })
        return Result(
            module = rewritten,
            syntheticLocs = syntheticLocs,
            hasBinds = syntheticLocs.isNotEmpty(),
        )
    }

    /**
     * Inject `Exception` effect + `ExceptionToResult` performer into a
     * `ModuleImportContext` ONLY when the module contains `<-`.
     *
     * Pulls these from the precompiled stdlib's prelude context (which has
     * loaded `effects::exception` transitively via `abilities::tryable`).
     * Only `Exception` and `ExceptionToResult` are copied — other performers
     * (ExceptionToOption, etc.) and helpers (`throw`, `unwrap`) are NOT,
     * keeping them out of user scope unless explicitly imported.
     *
     * @param baseCtx the module's current import context (after prelude merge)
     * @param preludeCtx the full prelude context (before stripping); we look
     *   up Exception + ExceptionToResult here
     * @return a new ModuleImportContext with the synthetic-perform machinery
     *   injected if not already present
     */
    fun injectSyntheticPerformerCtx(
        baseCtx: ModuleImportContext,
        preludeCtx: ModuleImportContext,
    ): ModuleImportContext {
        val exceptionEffect = preludeCtx.importedEffects[EXCEPTION_EFFECT_NAME]
        val exceptionToResult = preludeCtx.importedImpls[EXCEPTION_TO_RESULT_NAME]
        if (exceptionEffect == null || exceptionToResult == null) {
            // Shouldn't happen — these come from the stdlib that the prelude
            // always loads. If it does happen, return base unchanged and let
            // the later "Unknown performer" error surface clearly.
            return baseCtx
        }
        return baseCtx.copy(
            importedEffects = baseCtx.importedEffects + (EXCEPTION_EFFECT_NAME to exceptionEffect),
            importedImpls = baseCtx.importedImpls + (EXCEPTION_TO_RESULT_NAME to exceptionToResult),
        )
    }

    private const val EXCEPTION_EFFECT_NAME = "Exception"
    private const val EXCEPTION_TO_RESULT_NAME = "ExceptionToResult"

    // ------------------------------------------------------------------
    // Implementation
    // ------------------------------------------------------------------

    private fun desugarBindsInExpr(expr: ParsedExpr, syntheticLocs: MutableSet<Loc>): ParsedExpr = when (expr) {
        is ParsedDo -> {
            // Recurse into all sub-expressions first.
            val newStatements = expr.statements.map { stmt ->
                when (stmt) {
                    is ParsedStatement.Let -> stmt.copy(value = desugarBindsInExpr(stmt.value, syntheticLocs))
                    is ParsedStatement.Expr -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, syntheticLocs))
                    is ParsedStatement.Bind -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, syntheticLocs))
                }
            }
            val newResult = desugarBindsInExpr(expr.result, syntheticLocs)
            val processedDo = expr.copy(statements = newStatements, result = newResult)

            // If this do-block has any Bind statements, perform the rewrite.
            if (processedDo.statements.any { it is ParsedStatement.Bind }) {
                rewriteDoWithBinds(processedDo, syntheticLocs)
            } else {
                processedDo
            }
        }
        is ParsedFunctionCall -> expr.copy(
            function = desugarBindsInExpr(expr.function, syntheticLocs),
            arguments = desugarBindsInExpr(expr.arguments, syntheticLocs),
        )
        is ParsedLambda -> expr.copy(body = desugarBindsInExpr(expr.body, syntheticLocs))
        is ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
            when (e) {
                is ParsedRecordEntry.Field -> e.copy(value = e.value?.let { desugarBindsInExpr(it, syntheticLocs) })
                is ParsedRecordEntry.Spread -> e.copy(expr = desugarBindsInExpr(e.expr, syntheticLocs))
            }
        })
        is ParsedIf -> expr.copy(
            condition = desugarBindsInExpr(expr.condition, syntheticLocs),
            thenBranch = desugarBindsInExpr(expr.thenBranch, syntheticLocs),
            elseBranch = desugarBindsInExpr(expr.elseBranch, syntheticLocs),
        )
        is ParsedMatch -> expr.copy(
            scrutinee = desugarBindsInExpr(expr.scrutinee, syntheticLocs),
            cases = expr.cases.map { it.copy(body = desugarBindsInExpr(it.body, syntheticLocs)) },
        )
        is ParsedPerform -> expr.copy(body = desugarBindsInExpr(expr.body, syntheticLocs))
        is ParsedMemberAccess -> expr.copy(receiver = desugarBindsInExpr(expr.receiver, syntheticLocs))
        is ParsedParens -> expr.copy(expr = desugarBindsInExpr(expr.expr, syntheticLocs))
        is ParsedVariantConstruct -> expr.copy(value = expr.value?.let { desugarBindsInExpr(it, syntheticLocs) })
        is ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
            when (elem) {
                is ParsedSeqElement.Regular -> elem.copy(expr = desugarBindsInExpr(elem.expr, syntheticLocs))
                is ParsedSeqElement.Spread -> elem.copy(expr = desugarBindsInExpr(elem.expr, syntheticLocs))
            }
        })
        is ParsedEffectCall -> expr.copy(arguments = desugarBindsInExpr(expr.arguments, syntheticLocs))
        is ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
            when (part) {
                is ParsedStringPart.Interpolation -> part.copy(expr = desugarBindsInExpr(part.expr, syntheticLocs))
                else -> part
            }
        })
        is ParsedBinaryOp -> expr.copy(
            left = desugarBindsInExpr(expr.left, syntheticLocs),
            right = desugarBindsInExpr(expr.right, syntheticLocs),
        )
        is ParsedUnaryOp -> expr.copy(operand = desugarBindsInExpr(expr.operand, syntheticLocs))
        is ParsedPipe -> expr.copy(
            left = desugarBindsInExpr(expr.left, syntheticLocs),
            right = desugarBindsInExpr(expr.right, syntheticLocs),
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
     * Output:
     *   perform ExceptionToResult in do
     *     let pat1 = expr1.try {}
     *     stmt2
     *     let pat3 = expr3.try {}
     *     Ok {val = result}
     *
     * The synthetic perform's loc is added to `syntheticLocs` so the
     * constraint generator can pass `synthetic = true` to
     * `removeEffectsFromType` later.
     */
    private fun rewriteDoWithBinds(doExpr: ParsedDo, syntheticLocs: MutableSet<Loc>): ParsedPerform {
        val loc = doExpr.loc

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

        // 3. Build the rewritten inner do-block.
        val rewrittenDo = doExpr.copy(statements = rewrittenStatements, result = wrappedResult)

        // 4. Wrap in `perform ExceptionToResult in <rewrittenDo>`.
        val performHandler = ParsedPerformHandler(
            performer = ParsedQualifiedRef(
                localName = EXCEPTION_TO_RESULT_NAME,
                qualifier = null,
                loc = loc,
            ),
            params = null,
        )
        val syntheticPerform = ParsedPerform(
            handlers = listOf(performHandler),
            body = rewrittenDo,
            loc = loc,
        )

        // 5. Mark this perform as synthetic so the typechecker suppresses <*>.
        syntheticLocs.add(loc)

        return syntheticPerform
    }

    /**
     * Build a `expr.try {}` method-call expression.
     *
     * Uses dot-method syntax (rather than qualified `Tryable.try {it = expr}`)
     * so it goes through the existing method-call dispatch path that handles
     * ability impl resolution via dictionary passing.
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
