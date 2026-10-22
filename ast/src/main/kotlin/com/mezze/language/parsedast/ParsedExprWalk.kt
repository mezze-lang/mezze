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
    // What compiles: the code that builds the quote, and what the macro produced.
    is ParsedQuote -> listOf(value)
    is ParsedMacroCall -> listOfNotNull((expansion as? ParsedCode.Expr)?.expr)
}

/**
 * The sub-expressions as WRITTEN, which differ from [children] in two places: a
 * quote's are the code it quotes, not the code that builds it, and a macro call
 * has none, because its input is text. What the occurrence index and
 * `std::syntax`'s `children` walk.
 */
fun ParsedExpr.writtenChildren(): List<ParsedExpr> = when (this) {
    is ParsedQuote -> listOfNotNull((template as? ParsedCode.Expr)?.expr)
    is ParsedMacroCall -> emptyList()
    else -> children()
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
    is ParsedQuote -> "QuoteExpr"
    is ParsedMacroCall -> "MacroExpr"
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
    is ParsedMacroCall -> macro.syntaxSymbols()
    is ParsedFloatLit, is ParsedFracLit, is ParsedStringLit, is ParsedCharLit, is ParsedLambda,
    is ParsedFunctionCall, is ParsedPipe, is ParsedPipePlaceholder, is ParsedRecordLit, is ParsedIf,
    is ParsedMatch, is ParsedDo, is ParsedSeqLit, is ParsedParens, is ParsedQuote -> emptyList()
}

/** The code a macro call compiles to: its expansion, which must exist by the time anything compiles. */
fun ParsedMacroCall.compiled(): ParsedExpr = (expansion as? ParsedCode.Expr)?.expr
    ?: throw IllegalStateException(
        "$loc: macro call `${macro.localName}!( … )` has no expression expansion; " +
            "macros are expanded before a module is identified or type checked",
    )

/**
 * This node with each direct sub-expression replaced by [f] of it — the
 * rebuilding counterpart of [children], over the same sub-expressions, so a
 * rewrite reaches exactly what a walk does. A quote's code and a macro's
 * expansion are rebuilt through; a quote's template, which is not compiled, is
 * left as written.
 */
fun ParsedExpr.mapChildren(f: (ParsedExpr) -> ParsedExpr): ParsedExpr = when (this) {
    is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
    is ParsedCharLit, is ParsedIdentifier, is ParsedPipePlaceholder -> this
    is ParsedStringLit -> copy(parts = parts.map { p ->
        if (p is ParsedStringPart.Interpolation) p.copy(expr = f(p.expr)) else p
    })
    is ParsedParens -> copy(expr = f(expr))
    is ParsedSeqLit -> copy(elements = elements.map { el ->
        when (el) {
            is ParsedSeqElement.Regular -> el.copy(expr = f(el.expr))
            is ParsedSeqElement.Spread -> el.copy(expr = f(el.expr))
        }
    })
    is ParsedRecordLit -> mapEntries(f)
    is ParsedMemberAccess -> copy(receiver = f(receiver))
    is ParsedVariantConstruct -> copy(value = value?.let(f))
    is ParsedNewtypeConstruct -> copy(value = f(value))
    is ParsedLambda -> copy(body = f(body))
    is ParsedFunctionCall -> copy(function = f(function), arguments = f(arguments))
    is ParsedBinaryOp -> copy(left = f(left), right = f(right))
    is ParsedPipe -> copy(left = f(left), right = f(right))
    is ParsedIf -> copy(condition = f(condition), thenBranch = f(thenBranch), elseBranch = f(elseBranch))
    is ParsedMatch -> copy(scrutinee = f(scrutinee), cases = cases.map { c ->
        c.copy(
            guard = c.guard?.let(f),
            body = f(c.body),
            ifLetBindings = c.ifLetBindings.map { it.copy(expr = f(it.expr)) },
        )
    })
    is ParsedDo -> copy(
        statements = statements.map { st ->
            when (st) {
                is ParsedStatement.Let -> st.copy(value = f(st.value))
                is ParsedStatement.Expr -> st.copy(expr = f(st.expr))
            }
        },
        result = f(result),
    )
    is ParsedPerform -> copy(
        handlers = handlers.map { h -> h.copy(params = h.params?.mapEntries(f) as ParsedRecordLit?) },
        body = f(body),
    )
    is ParsedEffectCall -> copy(arguments = f(arguments))
    is ParsedQuote -> copy(value = f(value))
    is ParsedMacroCall -> when (val code = expansion) {
        is ParsedCode.Expr -> copy(expansion = ParsedCode.Expr(f(code.expr)))
        is ParsedCode.Decls, null -> this
    }
}

private fun ParsedRecordLit.mapEntries(f: (ParsedExpr) -> ParsedExpr): ParsedRecordLit = copy(entries = entries.map { en ->
    when (en) {
        is ParsedRecordEntry.Field -> en.copy(value = en.value?.let(f))
        is ParsedRecordEntry.Spread -> en.copy(expr = f(en.expr))
    }
})

/** [f] applied to every expression a declaration holds at its top: a `let`'s value, each op's body. */
fun ParsedDecl.mapExprs(f: (ParsedExpr) -> ParsedExpr): ParsedDecl = when (this) {
    is ParsedLetDecl -> copy(value = f(value))
    is ParsedImplAbilityDecl -> copy(operations = operations.map { it.copy(implementation = f(it.implementation)) })
    is ParsedPerformerDecl -> copy(operations = operations.map { it.copy(implementation = f(it.implementation)) })
    is ParsedTopLevelExpr -> copy(expression = f(expression))
    is ParsedTypeDecl, is ParsedAbilityDecl, is ParsedEffectDecl, is ParsedUseDecl, is ParsedPubUseDecl -> this
}
