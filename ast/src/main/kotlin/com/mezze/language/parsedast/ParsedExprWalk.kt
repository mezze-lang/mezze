package com.mezze.language.parsedast

/**
 * The direct sub-expressions of a [ParsedExpr]. Exhaustive over every variant,
 * so a new node type is a compile error here until handled — the single place
 * the parsed-expression shape is encoded for recursive walks.
 *
 * Consumers: the typechecker's linters, the LSP's node-at-position lookup and
 * refactors, and anywhere else that needs to recurse into a parsed tree without
 * re-listing the 23 cases.
 */
fun ParsedExpr.children(): List<ParsedExpr> = when (this) {
    is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
    is ParsedCharLit, is ParsedIdentifier,
    is ParsedPipePlaceholder -> emptyList()
    // A string literal's `$( … )` interpolation holes ARE sub-expressions.
    // Omitting them made every children()-driven walk (dependency ordering,
    // linters, LSP node-at-position, refactors) blind to references inside
    // interpolation — e.g. `"$(help {…})"` didn't register `help` as a
    // dependency, so a forward-referenced `help` stayed unresolved (#160).
    is ParsedStringLit -> parts.mapNotNull { part ->
        (part as? ParsedStringPart.Interpolation)?.expr
    }
    is ParsedParens -> listOf(expr)
    is ParsedSeqLit -> elements.map { el ->
        when (el) {
            is ParsedSeqElement.Regular -> el.expr
            is ParsedSeqElement.Spread -> el.expr
        }
    }
    is ParsedRecordLit -> entries.flatMap { entry ->
        when (entry) {
            is ParsedRecordEntry.Field -> listOfNotNull(entry.value)
            is ParsedRecordEntry.Spread -> listOf(entry.expr)
        }
    }
    is ParsedMemberAccess -> listOf(receiver)
    is ParsedVariantConstruct -> listOfNotNull(value)
    is ParsedNewtypeConstruct -> listOf(value)
    is ParsedLambda -> listOf(body)
    is ParsedFunctionCall -> listOf(function, arguments)
    is ParsedBinaryOp -> listOf(left, right)
    is ParsedPipe -> listOf(left, right)
    is ParsedIf -> listOf(condition, thenBranch, elseBranch)
    is ParsedMatch -> listOf(scrutinee) +
        cases.flatMap { case -> listOfNotNull(case.guard) + case.body + case.ifLetBindings.map { it.expr } }
    is ParsedDo -> statements.mapNotNull { stmt ->
        when (stmt) {
            is ParsedStatement.Let -> stmt.value
            is ParsedStatement.Expr -> stmt.expr
        }
    } + result
    is ParsedPerform -> handlers.mapNotNull { it.params } + body
    is ParsedEffectCall -> listOf(arguments)
}
