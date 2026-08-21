package com.mezze.language.typechecker.phase

import com.mezze.language.parsedast.*

internal fun desugarPipesInParsedModule(module: ParsedModule): ParsedModule {
    // Quick check: does the module even contain pipes?
    fun hasPipes(expr: ParsedExpr): Boolean = when (expr) {
        is ParsedPipe -> true
        is ParsedFunctionCall -> hasPipes(expr.function) || hasPipes(expr.arguments)
        is ParsedLambda -> hasPipes(expr.body)
        is ParsedRecordLit -> expr.entries.any { e ->
            when (e) {
                is ParsedRecordEntry.Field -> e.value?.let { hasPipes(it) } ?: false
                is ParsedRecordEntry.Spread -> hasPipes(e.expr)
            }
        }
        is ParsedIf -> hasPipes(expr.condition) || hasPipes(expr.thenBranch) || hasPipes(expr.elseBranch)
        is ParsedMatch -> hasPipes(expr.scrutinee) || expr.cases.any { hasPipes(it.body) }
        is ParsedDo -> hasPipes(expr.result) || expr.statements.any { stmt ->
            when (stmt) {
                is ParsedStatement.Let -> hasPipes(stmt.value)
                is ParsedStatement.Expr -> hasPipes(stmt.expr)
            }
        }
        is ParsedPerform -> hasPipes(expr.body)
        is ParsedMemberAccess -> hasPipes(expr.receiver)
        is ParsedParens -> hasPipes(expr.expr)
        is ParsedVariantConstruct -> expr.value?.let { hasPipes(it) } ?: false
        is ParsedSeqLit -> expr.elements.any { elem ->
            when (elem) {
                is ParsedSeqElement.Regular -> hasPipes(elem.expr)
                is ParsedSeqElement.Spread -> hasPipes(elem.expr)
            }
        }
        is ParsedStringLit -> expr.parts.any { part ->
            part is ParsedStringPart.Interpolation && hasPipes(part.expr)
        }
        is ParsedEffectCall -> hasPipes(expr.arguments)
        is ParsedBinaryOp -> hasPipes(expr.left) || hasPipes(expr.right)
        else -> false
    }
    val hasAnyPipes = module.decls.any { decl ->
        when (decl) {
            is ParsedLetDecl -> hasPipes(decl.value)
            is ParsedImplAbilityDecl -> decl.operations.any { hasPipes(it.implementation) }
            is ParsedPerformerDecl -> decl.operations.any { hasPipes(it.implementation) }
            is ParsedTopLevelExpr -> hasPipes(decl.expression)
            else -> false
        }
    }
    if (!hasAnyPipes) return module

    fun desugar(expr: ParsedExpr): ParsedExpr = when (expr) {
        is ParsedPipe -> {
            val left = desugar(expr.left)
            val right = desugar(expr.right)
            replaceParsedPlaceholder(right, left)
        }
        is ParsedFunctionCall -> expr.copy(function = desugar(expr.function), arguments = desugar(expr.arguments))
        is ParsedLambda -> expr.copy(body = desugar(expr.body))
        is ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
            when (e) {
                is ParsedRecordEntry.Field -> e.copy(value = e.value?.let { desugar(it) })
                is ParsedRecordEntry.Spread -> e.copy(expr = desugar(e.expr))
            }
        })
        is ParsedIf -> expr.copy(condition = desugar(expr.condition), thenBranch = desugar(expr.thenBranch), elseBranch = desugar(expr.elseBranch))
        is ParsedMatch -> expr.copy(scrutinee = desugar(expr.scrutinee), cases = expr.cases.map { it.copy(body = desugar(it.body)) })
        is ParsedDo -> expr.copy(
            statements = expr.statements.map { stmt ->
                when (stmt) {
                    is ParsedStatement.Let -> stmt.copy(value = desugar(stmt.value))
                    is ParsedStatement.Expr -> stmt.copy(expr = desugar(stmt.expr))
                }
            },
            result = desugar(expr.result)
        )
        is ParsedPerform -> expr.copy(body = desugar(expr.body))
        is ParsedMemberAccess -> expr.copy(receiver = desugar(expr.receiver))
        is ParsedParens -> expr.copy(expr = desugar(expr.expr))
        is ParsedVariantConstruct -> expr.copy(value = expr.value?.let { desugar(it) })
        is ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
            when (elem) {
                is ParsedSeqElement.Regular -> elem.copy(expr = desugar(elem.expr))
                is ParsedSeqElement.Spread -> elem.copy(expr = desugar(elem.expr))
            }
        })
        is ParsedEffectCall -> expr.copy(arguments = desugar(expr.arguments))
        is ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
            when (part) {
                is ParsedStringPart.Interpolation -> part.copy(expr = desugar(part.expr))
                else -> part
            }
        })
        is ParsedBinaryOp -> expr.copy(left = desugar(expr.left), right = desugar(expr.right))
        else -> expr
    }

    return module.copy(decls = module.decls.map { decl ->
        when (decl) {
            is ParsedLetDecl -> decl.copy(value = desugar(decl.value))
            is ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op -> op.copy(implementation = desugar(op.implementation)) })
            is ParsedTopLevelExpr -> decl.copy(expression = desugar(decl.expression))
            else -> decl
        }
    })
}

private fun replaceParsedPlaceholder(expr: ParsedExpr, replacement: ParsedExpr): ParsedExpr = when (expr) {
    is ParsedPipePlaceholder -> replacement
    is ParsedFunctionCall -> expr.copy(
        function = replaceParsedPlaceholder(expr.function, replacement),
        arguments = replaceParsedPlaceholder(expr.arguments, replacement)
    )
    is ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
        when (e) {
            is ParsedRecordEntry.Field -> e.copy(value = e.value?.let { replaceParsedPlaceholder(it, replacement) })
            is ParsedRecordEntry.Spread -> e.copy(expr = replaceParsedPlaceholder(e.expr, replacement))
        }
    })
    is ParsedLambda -> expr.copy(body = replaceParsedPlaceholder(expr.body, replacement))
    is ParsedIf -> expr.copy(
        condition = replaceParsedPlaceholder(expr.condition, replacement),
        thenBranch = replaceParsedPlaceholder(expr.thenBranch, replacement),
        elseBranch = replaceParsedPlaceholder(expr.elseBranch, replacement)
    )
    is ParsedParens -> expr.copy(expr = replaceParsedPlaceholder(expr.expr, replacement))
    is ParsedMemberAccess -> expr.copy(receiver = replaceParsedPlaceholder(expr.receiver, replacement))
    is ParsedMatch -> expr.copy(
        scrutinee = replaceParsedPlaceholder(expr.scrutinee, replacement),
        cases = expr.cases.map { it.copy(body = replaceParsedPlaceholder(it.body, replacement)) }
    )
    is ParsedDo -> expr.copy(
        statements = expr.statements.map { stmt ->
            when (stmt) {
                is ParsedStatement.Let -> stmt.copy(value = replaceParsedPlaceholder(stmt.value, replacement))
                is ParsedStatement.Expr -> stmt.copy(expr = replaceParsedPlaceholder(stmt.expr, replacement))
            }
        },
        result = replaceParsedPlaceholder(expr.result, replacement)
    )
    is ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
        when (elem) {
            is ParsedSeqElement.Regular -> elem.copy(expr = replaceParsedPlaceholder(elem.expr, replacement))
            is ParsedSeqElement.Spread -> elem.copy(expr = replaceParsedPlaceholder(elem.expr, replacement))
        }
    })
    is ParsedVariantConstruct -> expr.copy(value = expr.value?.let { replaceParsedPlaceholder(it, replacement) })
    is ParsedPerform -> expr.copy(body = replaceParsedPlaceholder(expr.body, replacement))
    is ParsedEffectCall -> expr.copy(arguments = replaceParsedPlaceholder(expr.arguments, replacement))
    is ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
        when (part) {
            is ParsedStringPart.Interpolation -> part.copy(expr = replaceParsedPlaceholder(part.expr, replacement))
            is ParsedStringPart.Literal -> part
        }
    })
    is ParsedBinaryOp -> expr.copy(
        left = replaceParsedPlaceholder(expr.left, replacement),
        right = replaceParsedPlaceholder(expr.right, replacement)
    )
    is ParsedPipe -> expr.copy(
        left = replaceParsedPlaceholder(expr.left, replacement),
        right = replaceParsedPlaceholder(expr.right, replacement)
    )
    // Leaves — nothing to replace. Exhaustive (no `else`) so a new ParsedExpr
    // variant that can contain `@` forces a compile error here.
    is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
    is ParsedCharLit, is ParsedIdentifier, is ParsedPipePlaceholder -> expr
}
