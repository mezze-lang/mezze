package com.mezze.language.typechecker.desugar

import com.mezze.language.parsedast.*

object DoBindDesugar {

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

    private fun desugarBindsInExpr(expr: ParsedExpr, onBindFound: (Loc) -> Unit): ParsedExpr = when (expr) {
        is ParsedDo -> {
            val newStatements = expr.statements.map { stmt ->
                when (stmt) {
                    is ParsedStatement.Let -> stmt.copy(value = desugarBindsInExpr(stmt.value, onBindFound))
                    is ParsedStatement.Expr -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, onBindFound))
                    is ParsedStatement.Bind -> stmt.copy(expr = desugarBindsInExpr(stmt.expr, onBindFound))
                }
            }
            val newResult = desugarBindsInExpr(expr.result, onBindFound)
            val processedDo = expr.copy(statements = newStatements, result = newResult)

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
        is ParsedIntLit,
        is ParsedNatLit,
        is ParsedFloatLit,
        is ParsedFracLit,
        is ParsedCharLit,
        is ParsedIdentifier,
        is ParsedPipePlaceholder -> expr
    }

    private fun rewriteDoWithBinds(
        doExpr: ParsedDo,
        onBindFound: (Loc) -> Unit,
    ): ParsedPerform {
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

    const val EXCEPTION_TO_RESULT_NAME = "ExceptionToResult"

    const val EXCEPTION_EFFECT_NAME = "Exception"

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
