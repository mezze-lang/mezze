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

/**
 * The node's kind: its tag in `std/syntax.mz`'s `Expr`. One definition, read by
 * the syntax encoder that builds the tree and by the occurrence index that makes
 * kinds searchable, so the two cannot name a node differently.
 */
fun ParsedExpr.syntaxKind(): String = when (this) {
    is ParsedIntLit -> "IntExpr"
    is ParsedNatLit -> "NatExpr"
    is ParsedFloatLit -> "FloatExpr"
    is ParsedFracLit -> "FracExpr"
    is ParsedStringLit -> "StrExpr"
    is ParsedCharLit -> "CharExpr"
    is ParsedIdentifier -> "NameExpr"
    is ParsedLambda -> "LambdaExpr"
    is ParsedFunctionCall -> "CallExpr"
    is ParsedBinaryOp -> "BinaryExpr"
    is ParsedPipe -> "PipeExpr"
    is ParsedPipePlaceholder -> "PlaceholderExpr"
    is ParsedRecordLit -> "RecordExpr"
    is ParsedMemberAccess -> "MemberExpr"
    is ParsedVariantConstruct -> "VariantExpr"
    is ParsedNewtypeConstruct -> "NewtypeExpr"
    is ParsedIf -> "IfExpr"
    is ParsedMatch -> "MatchExpr"
    is ParsedDo -> "DoExpr"
    is ParsedSeqLit -> "SeqExpr"
    is ParsedPerform -> "PerformExpr"
    is ParsedEffectCall -> "EffectCallExpr"
    is ParsedParens -> "ParensExpr"
}

/**
 * What the node spells, as written: the operator, the name (qualified as
 * written), the member, the constructor, the newtype, `Effect::op`, an integer's
 * value — and for a `perform`, each handler. Empty for a node that spells
 * nothing of its own (a call, a record, a block…), which is found by its kind.
 */
fun ParsedExpr.syntaxSymbols(): List<String> = when (this) {
    is ParsedIntLit -> listOf(value.toString())
    is ParsedNatLit -> listOf("${value}n")
    is ParsedIdentifier -> listOf(qualifier?.let { "$it$MODULE_PATH_SEPARATOR$localName" } ?: localName)
    is ParsedBinaryOp -> listOf(op.symbol)
    is ParsedMemberAccess -> listOf(member)
    is ParsedVariantConstruct -> listOf(constructor)
    is ParsedNewtypeConstruct -> listOf(typeName)
    is ParsedEffectCall -> listOf(operation.toString())
    is ParsedPerform -> handlers.map { it.performer.toString() }
    is ParsedFloatLit, is ParsedFracLit, is ParsedStringLit, is ParsedCharLit, is ParsedLambda,
    is ParsedFunctionCall, is ParsedPipe, is ParsedPipePlaceholder, is ParsedRecordLit, is ParsedIf,
    is ParsedMatch, is ParsedDo, is ParsedSeqLit, is ParsedParens -> emptyList()
}
