package com.mezze.language.lsp

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
                        val innerExpr = findInExpr(decl.value, line, col)
                        if (innerExpr != null) return LookupResult.Expr(innerExpr.first, innerExpr.second)
                        return LookupResult.Binding(decl.name, decl.scheme.type, decl.loc)
                    }
                }
                is TypedTypeDecl -> {
                    if (containsPosition(decl.loc, line, col)) {
                        return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar(decl.name)), decl.loc)
                    }
                }
            }
        }

        // Search ability declarations
        for (decl in module.abilityDecls) {
            if (containsPosition(decl.loc, line, col)) {
                return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar(decl.name)), decl.loc)
            }
        }

        // Search effect declarations
        for (decl in module.effectDecls) {
            if (containsPosition(decl.loc, line, col)) {
                return LookupResult.Binding(decl.name, com.mezze.language.ast.MezzeType.VarType(com.mezze.language.ast.TypeVar(decl.name)), decl.loc)
            }
        }

        return LookupResult.NotFound
    }

    /**
     * Find the innermost expression at a position within an expression tree.
     * Returns (expr, parent) or null.
     */
    private fun findInExpr(expr: TypedExpr, line: Int, col: Int, parent: TypedExpr? = null): Pair<TypedExpr, TypedExpr?>? {
        if (!containsPosition(expr.loc, line, col)) return null

        // Try to find a more specific child first
        val childResult = when (expr) {
            is TypedCall -> {
                findInExpr(expr.function, line, col, expr)
                    ?: findInExpr(expr.arg, line, col, expr)
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
