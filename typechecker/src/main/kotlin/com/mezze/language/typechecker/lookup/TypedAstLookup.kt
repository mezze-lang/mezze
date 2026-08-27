package com.mezze.language.typechecker.lookup

import com.mezze.language.typedast.*
import com.mezze.language.parsedast.Loc

/**
 * Position-based lookup into TypedAST.
 *
 * This is the core utility that powers hover, go-to-definition, signature help,
 * and find-references. Given a cursor position (line, col), it finds the
 * innermost TypedExpr node whose Loc contains that position.
 */
object TypedAstLookup {

    /**
     * Result of looking up a position in the TypedAST.
     */
    sealed interface LookupResult {
        /** Found an expression at this position. */
        data class Expr(val expr: TypedExpr, val parent: TypedExpr?) : LookupResult

        /** Found a variable binding (let or lambda param). */
        data class Binding(val name: String, val type: com.mezze.language.ast.MezzeType, val loc: Loc) : LookupResult

        /** Nothing found at this position. */
        data object NotFound : LookupResult
    }

    /**
     * Find the innermost TypedExpr at a given position.
     *
     * @param module The TypedModule to search in
     * @param line 1-indexed line number (matches Loc convention)
     * @param col 1-indexed column number
     * @return The lookup result
     */
    fun findAtPosition(module: TypedModule, line: Int, col: Int): LookupResult {
        // Search through declarations
        for (decl in module.decls) {
            when (decl) {
                is TypedLetDecl -> {
                    // Check if position is on the binding name
                    if (containsPosition(decl.loc, line, col)) {
                        // Nested-let name hit: when the cursor lands on
                        // a `let name = …` inside a do-block (between the
                        // statement start and its value's loc), report it
                        // as a Binding for that name. Without this, the
                        // recursive lookup falls through to the enclosing
                        // TypedBlock/Lambda whose result type is e.g. `{}`,
                        // making hover useless.
                        val nestedLet = findNestedLetAtName(decl.value, line, col)
                        if (nestedLet != null) return nestedLet
                        // Lambda-param name hit: cursor on the `xs` token in
                        // `{ xs } -> body` should hover as `param xs : T`,
                        // not as the enclosing lambda's full type. Same idea
                        // as findNestedLetAtName but for TypedLambda params.
                        val paramHit = findLambdaParamAtName(decl.value, line, col)
                        if (paramHit != null) return paramHit
                        val innerExpr = findInExpr(decl.value, line, col)
                        if (innerExpr != null) return LookupResult.Expr(innerExpr.first, innerExpr.second)
                        return LookupResult.Binding(decl.name, decl.scheme.type, decl.loc)
                    }
                }
                is TypedTypeDecl -> {
                    if (containsPosition(decl.loc, line, col)) {
                        return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(decl.name)), decl.loc)
                    }
                }
            }
        }

        // Search ability declarations
        for (decl in module.abilityDecls) {
            if (containsPosition(decl.loc, line, col)) {
                return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(decl.name)), decl.loc)
            }
        }

        // Search effect declarations
        for (decl in module.effectDecls) {
            if (containsPosition(decl.loc, line, col)) {
                return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(decl.name)), decl.loc)
            }
        }

        return LookupResult.NotFound
    }

    /**
     * Find the OUTERMOST typed expression whose span *ends* exactly at
     * `(line, col)`. Used by dot-completion to resolve the receiver of a
     * chain like `x.is_odd {}.` — the char just before the `.` is the `}`
     * closing the call's argument, and the receiver we want is the whole
     * call `x.is_odd {}` (type `Bool`), NOT the innermost node at that
     * position (which `findAtPosition` returns — the argument record `{ it }`).
     *
     * We walk into children while a child still ends at the target, then
     * unwind: the FIRST ancestor whose end matches but has no
     * same-ending child is the call/index/group we want. Concretely we
     * collect every expr whose `(endLine, endCol)` equals the target and
     * return the one with the widest span (earliest start) — that's the
     * enclosing call, not the inner arg record.
     */
    fun findExprEndingAt(module: TypedModule, line: Int, col: Int): TypedExpr? {
        var best: TypedExpr? = null
        fun consider(e: TypedExpr) {
            if (e.loc.endLine == line && e.loc.endCol == col) {
                val b = best
                if (b == null || startsBefore(e.loc, b.loc)) best = e
            }
        }
        for (decl in module.decls) {
            if (decl is TypedLetDecl) walkExprs(decl.value, ::consider)
        }
        return best
    }

    /** True if `a` starts strictly before `b` (wider span on the left). */
    private fun startsBefore(a: Loc, b: Loc): Boolean {
        if (a.startLine != b.startLine) return a.startLine < b.startLine
        return a.startCol < b.startCol
    }

    /** Pre-order visit of every TypedExpr in the tree. */
    private fun walkExprs(expr: TypedExpr, visit: (TypedExpr) -> Unit) {
        visit(expr)
        when (expr) {
            is TypedCall -> { walkExprs(expr.function, visit); walkExprs(expr.arg, visit) }
            is TypedLambda -> walkExprs(expr.body, visit)
            is TypedRecord -> expr.entries.forEach { entry ->
                when (entry) {
                    is TypedRecordEntry.Field -> walkExprs(entry.value, visit)
                    is TypedRecordEntry.Spread -> walkExprs(entry.expr, visit)
                }
            }
            is TypedFieldAccess -> walkExprs(expr.record, visit)
            is TypedRecordUpdate -> {
                walkExprs(expr.record, visit)
                expr.updates.forEach { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> walkExprs(entry.value, visit)
                        is TypedRecordEntry.Spread -> walkExprs(entry.expr, visit)
                    }
                }
            }
            is TypedVariantConstruct -> expr.payload?.let { walkExprs(it, visit) }
            is TypedMatch -> {
                walkExprs(expr.scrutinee, visit)
                expr.cases.forEach { case ->
                    case.guard?.let { walkExprs(it, visit) }
                    walkExprs(case.body, visit)
                }
            }
            is TypedIf -> {
                walkExprs(expr.condition, visit)
                walkExprs(expr.thenBranch, visit)
                walkExprs(expr.elseBranch, visit)
            }
            is TypedBlock -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is TypedStatement.Let -> walkExprs(stmt.value, visit)
                        is TypedStatement.Expr -> walkExprs(stmt.expr, visit)
                    }
                }
                walkExprs(expr.result, visit)
            }
            is TypedSeq -> expr.elements.forEach { elem ->
                when (elem) {
                    is TypedSeqElement.Regular -> walkExprs(elem.expr, visit)
                    is TypedSeqElement.Spread -> walkExprs(elem.expr, visit)
                }
            }
            is TypedPerform -> walkExprs(expr.body, visit)
            is TypedPipe -> { walkExprs(expr.left, visit); walkExprs(expr.right, visit) }
            is TypedStringLit -> expr.parts.filterIsInstance<TypedStringPart.Interpolation>()
                .forEach { walkExprs(it.expr, visit) }
            is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
            is TypedBoolLit, is TypedCharLit, is TypedFracLit, is TypedPerformOp -> {}
        }
    }

    /**
     * Find the innermost expression at a given position within an expression tree.
     * Returns (expr, parent) or null.
     */
    private fun findInExpr(expr: TypedExpr, line: Int, col: Int, parent: TypedExpr? = null): Pair<TypedExpr, TypedExpr?>? {
        if (!containsPosition(expr.loc, line, col)) return null

        // Try to find a more specific child first
        val childResult = when (expr) {
            is TypedCall -> {
                findInExpr(expr.function, line, col, expr)
                    ?: findInExpr(expr.arg, line, col, expr)
                    // Method calls desugar to `Call(fn=method, arg={it: receiver, …})`.
                    // The wrapped arg record's own loc is the ORIGINAL `{}` args'
                    // span (e.g. cols 55-56), but its `it` field holds the receiver
                    // CHAIN, which spans further LEFT than the record itself. So the
                    // record's containsPosition guard rejects a cursor over the
                    // receiver, stranding the whole chain. Descend into the arg
                    // record's entries directly to reach it.
                    ?: (expr.arg as? TypedRecord)?.entries?.firstNotNullOfOrNull { entry ->
                        when (entry) {
                            is TypedRecordEntry.Field -> findInExpr(entry.value, line, col, expr)
                            is TypedRecordEntry.Spread -> findInExpr(entry.expr, line, col, expr)
                        }
                    }
            }
            is TypedLambda -> findInExpr(expr.body, line, col, expr)
            is TypedRecord -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is TypedRecordEntry.Field -> {
                            val result = findInExpr(entry.value, line, col, expr)
                            if (result != null) return result
                        }
                        is TypedRecordEntry.Spread -> {
                            val result = findInExpr(entry.expr, line, col, expr)
                            if (result != null) return result
                        }
                    }
                }
                null
            }
            is TypedFieldAccess -> findInExpr(expr.record, line, col, expr)
            is TypedRecordUpdate -> {
                findInExpr(expr.record, line, col, expr)
                    ?: expr.updates.firstNotNullOfOrNull { entry ->
                        when (entry) {
                            is TypedRecordEntry.Field -> findInExpr(entry.value, line, col, expr)
                            is TypedRecordEntry.Spread -> findInExpr(entry.expr, line, col, expr)
                        }
                    }
            }
            is TypedVariantConstruct -> expr.payload?.let { findInExpr(it, line, col, expr) }
            is TypedMatch -> {
                findInExpr(expr.scrutinee, line, col, expr)
                    ?: expr.cases.firstNotNullOfOrNull { case ->
                        case.guard?.let { findInExpr(it, line, col, expr) }
                            ?: findInExpr(case.body, line, col, expr)
                    }
            }
            is TypedIf -> {
                findInExpr(expr.condition, line, col, expr)
                    ?: findInExpr(expr.thenBranch, line, col, expr)
                    ?: findInExpr(expr.elseBranch, line, col, expr)
            }
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    val result = when (stmt) {
                        is TypedStatement.Let -> findInExpr(stmt.value, line, col, expr)
                        is TypedStatement.Expr -> findInExpr(stmt.expr, line, col, expr)
                    }
                    if (result != null) return result
                }
                findInExpr(expr.result, line, col, expr)
            }
            is TypedSeq -> {
                expr.elements.firstNotNullOfOrNull { elem ->
                    when (elem) {
                        is TypedSeqElement.Regular -> findInExpr(elem.expr, line, col, expr)
                        is TypedSeqElement.Spread -> findInExpr(elem.expr, line, col, expr)
                    }
                }
            }
            is TypedPerform -> findInExpr(expr.body, line, col, expr)
            is TypedPipe -> {
                findInExpr(expr.left, line, col, expr)
                    ?: findInExpr(expr.right, line, col, expr)
            }
            is TypedStringLit -> {
                expr.parts.filterIsInstance<TypedStringPart.Interpolation>().firstNotNullOfOrNull {
                    findInExpr(it.expr, line, col, expr)
                }
            }
            // Leaf nodes — no children
            is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
            is TypedBoolLit, is TypedCharLit, is TypedFracLit, is TypedPerformOp -> null
        }

        // If a child matched, return it (more specific). Otherwise return this node.
        return childResult ?: Pair(expr, parent)
    }

    /**
     * Walk a typed expression looking for a TypedLambda whose param name
     * region contains the cursor. Lets hover on `{ xs } ->` show
     * `param xs : T` instead of the lambda's full type.
     */
    private fun findLambdaParamAtName(expr: TypedExpr, line: Int, col: Int): LookupResult.Binding? {
        if (!containsPosition(expr.loc, line, col)) return null
        return when (expr) {
            is TypedLambda -> {
                for (param in expr.params) {
                    if (containsPosition(param.loc, line, col)) {
                        return LookupResult.Binding(param.name, param.type, param.loc)
                    }
                }
                findLambdaParamAtName(expr.body, line, col)
            }
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    val hit = when (stmt) {
                        is TypedStatement.Let -> findLambdaParamAtName(stmt.value, line, col)
                        is TypedStatement.Expr -> findLambdaParamAtName(stmt.expr, line, col)
                    }
                    if (hit != null) return hit
                }
                findLambdaParamAtName(expr.result, line, col)
            }
            is TypedPerform -> findLambdaParamAtName(expr.body, line, col)
            is TypedIf -> findLambdaParamAtName(expr.condition, line, col)
                ?: findLambdaParamAtName(expr.thenBranch, line, col)
                ?: findLambdaParamAtName(expr.elseBranch, line, col)
            is TypedMatch -> {
                findLambdaParamAtName(expr.scrutinee, line, col)
                    ?: expr.cases.firstNotNullOfOrNull { case ->
                        case.guard?.let { findLambdaParamAtName(it, line, col) }
                            ?: findLambdaParamAtName(case.body, line, col)
                    }
            }
            is TypedCall -> findLambdaParamAtName(expr.function, line, col)
                ?: findLambdaParamAtName(expr.arg, line, col)
            is TypedRecord -> expr.entries.firstNotNullOfOrNull { entry ->
                when (entry) {
                    is TypedRecordEntry.Field -> findLambdaParamAtName(entry.value, line, col)
                    is TypedRecordEntry.Spread -> findLambdaParamAtName(entry.expr, line, col)
                }
            }
            is TypedFieldAccess -> findLambdaParamAtName(expr.record, line, col)
            is TypedPipe -> findLambdaParamAtName(expr.left, line, col)
                ?: findLambdaParamAtName(expr.right, line, col)
            else -> null
        }
    }

    /**
     * Walk a typed expression looking for a nested-let whose binding name
     * region contains the cursor. The name region is approximated as
     * `[stmt.loc.start, stmt.value.loc.start)` — i.e. everything in the
     * Let statement that comes before the value expression. That covers
     * `let foo` and `let foo = ` (the part the user clicks to query "what
     * is foo's type").
     *
     * Used by hover so a click on the binding name shows `let foo : T`
     * instead of falling through to the enclosing block's result type
     * (which is often `{}`).
     */
    private fun findNestedLetAtName(expr: TypedExpr, line: Int, col: Int): LookupResult.Binding? {
        if (!containsPosition(expr.loc, line, col)) return null
        return when (expr) {
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Let -> {
                            // Binding-name region: cursor is inside the
                            // statement's loc, and BEFORE the value's start.
                            val inStmt = containsPosition(stmt.loc, line, col)
                            val beforeValue = line < stmt.value.loc.startLine ||
                                (line == stmt.value.loc.startLine && col < stmt.value.loc.startCol)
                            if (inStmt && beforeValue) {
                                return LookupResult.Binding(stmt.name, stmt.type, stmt.loc)
                            }
                            findNestedLetAtName(stmt.value, line, col)?.let { return it }
                        }
                        is TypedStatement.Expr -> {
                            findNestedLetAtName(stmt.expr, line, col)?.let { return it }
                        }
                    }
                }
                findNestedLetAtName(expr.result, line, col)
            }
            is TypedLambda -> findNestedLetAtName(expr.body, line, col)
            is TypedPerform -> findNestedLetAtName(expr.body, line, col)
            is TypedIf -> findNestedLetAtName(expr.condition, line, col)
                ?: findNestedLetAtName(expr.thenBranch, line, col)
                ?: findNestedLetAtName(expr.elseBranch, line, col)
            is TypedMatch -> {
                findNestedLetAtName(expr.scrutinee, line, col)
                    ?: expr.cases.firstNotNullOfOrNull { case ->
                        case.guard?.let { findNestedLetAtName(it, line, col) }
                            ?: findNestedLetAtName(case.body, line, col)
                    }
            }
            is TypedCall -> findNestedLetAtName(expr.function, line, col)
                ?: findNestedLetAtName(expr.arg, line, col)
            is TypedRecord -> expr.entries.firstNotNullOfOrNull { entry ->
                when (entry) {
                    is TypedRecordEntry.Field -> findNestedLetAtName(entry.value, line, col)
                    is TypedRecordEntry.Spread -> findNestedLetAtName(entry.expr, line, col)
                }
            }
            is TypedFieldAccess -> findNestedLetAtName(expr.record, line, col)
            is TypedPipe -> findNestedLetAtName(expr.left, line, col)
                ?: findNestedLetAtName(expr.right, line, col)
            else -> null
        }
    }

    /**
     * Check if a Loc contains a given position.
     * Position is 1-indexed (matches Loc convention).
     */
    private fun containsPosition(loc: Loc, line: Int, col: Int): Boolean {
        if (line < loc.startLine || line > loc.endLine) return false
        if (line == loc.startLine && col < loc.startCol) return false
        if (line == loc.endLine && col > loc.endCol) return false
        return true
    }
}
