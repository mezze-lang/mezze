package com.mezze.language.lsp

import com.mezze.language.typechecker.lookup.TypedAstLookup

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*

/**
 * Find every binding in scope at a given (line, col) within a TypedModule.
 *
 * Companion to [TypedAstLookup], which finds the innermost expression at a
 * position. This walker tracks all the *binders* on the path down to that
 * position — lambda params, do-block lets, match-pattern bindings,
 * if-let bindings.
 *
 * Used by:
 *   - Hover (#124): when the user hovers on `let x = …` or on a use of `x`
 *     inside a do-block, we look up `x` in the scope stack to show its type.
 *   - Completion (#125): when typing an identifier, we offer every name in
 *     scope at that position.
 *
 * Top-level decls are NOT included — those are surfaced separately by
 * `getBindingCompletions` and the existing top-level hover paths. The
 * walker is only responsible for *local* (function-internal) bindings.
 */
object TypedAstScopeWalker {

    /** A binding visible at a position. */
    data class ScopeBinding(
        val name: String,
        val type: MezzeType,
        val loc: Loc,
        val kind: Kind
    ) {
        enum class Kind { LAMBDA_PARAM, LET_LOCAL, MATCH_BINDING, IF_LET_BINDING }
    }

    /**
     * Return every binding visible at (line, col) inside the module.
     *
     * Inner-most bindings come *last* in the list — callers that want
     * shadow-resolution should iterate in reverse, or build a Map keyed by
     * name (later overwrites win, which is exactly the shadowing rule).
     */
    fun bindingsAtPosition(module: TypedModule, line: Int, col: Int): List<ScopeBinding> {
        for (decl in module.decls) {
            if (decl is TypedLetDecl && containsPosition(decl.loc, line, col)) {
                val out = mutableListOf<ScopeBinding>()
                walk(decl.value, line, col, out)
                return out
            }
        }
        return emptyList()
    }

    /**
     * Recurse into [expr] looking for the position. As we descend through
     * binders, append to [out]. We don't unwind on the way up — the caller
     * gets the cumulative scope at the deepest node that contained the
     * position.
     *
     * Returns true if the position landed inside this expression.
     */
    private fun walk(expr: TypedExpr, line: Int, col: Int, out: MutableList<ScopeBinding>): Boolean {
        if (!containsPosition(expr.loc, line, col)) return false

        when (expr) {
            is TypedLambda -> {
                // Params come into scope before the body. We add them whether
                // or not the cursor is inside the body — hovering on a param
                // name in `{ a, b } -> …` is also useful.
                for (p in expr.params) {
                    out.add(ScopeBinding(p.name, p.type, p.loc, ScopeBinding.Kind.LAMBDA_PARAM))
                }
                walk(expr.body, line, col, out)
            }
            is TypedBlock -> {
                // Lets bind sequentially. As we walk the statement list:
                //   - if the cursor is INSIDE this statement, descend and
                //     stop (later statements are out of scope at the cursor)
                //   - if the statement ENDS BEFORE the cursor, add it to
                //     scope and continue
                //   - if the statement starts AFTER the cursor, ignore it
                //     (we shouldn't see a `let n` that comes below us)
                // A let's RHS does NOT see its own name, so we visit the RHS
                // first and only add the binding after — a no-op when the
                // cursor is inside the RHS (we return early).
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Let -> {
                            if (containsPosition(stmt.value.loc, line, col)) {
                                walk(stmt.value, line, col, out)
                                return true
                            }
                            // Statement is fully before the cursor — bind it
                            // and keep going. Skip statements that start
                            // after the cursor entirely.
                            if (isLocBefore(stmt.loc, line, col)) {
                                out.add(ScopeBinding(stmt.name, stmt.type, stmt.loc, ScopeBinding.Kind.LET_LOCAL))
                            }
                        }
                        is TypedStatement.Expr -> {
                            if (walk(stmt.expr, line, col, out)) return true
                        }
                    }
                }
                walk(expr.result, line, col, out)
            }
            is TypedMatch -> {
                if (walk(expr.scrutinee, line, col, out)) return true
                for (case in expr.cases) {
                    // Pattern bindings are in scope for both guard and body.
                    // We mutate `out` to add them, then check both — if the
                    // cursor is in this case, we're done; otherwise unwind
                    // the bindings so siblings don't see them.
                    val before = out.size
                    for (b in case.bindings) {
                        out.add(ScopeBinding(b.name, b.type, b.loc, ScopeBinding.Kind.MATCH_BINDING))
                    }
                    // if-let bindings layer on top of pattern bindings. Each
                    // `TypedIfLetBinding` carries the destructuring `pattern`
                    // (every node typed) and `bindingNames`. We recover a REAL
                    // type per name by walking the pattern — same as the
                    // regular `case.bindings` path — rather than the old
                    // best-effort fresh-VarType placeholder (which made hover
                    // and dot-completion on if-let bindings show `'a`).
                    for (ilb in case.ifLetBindings) {
                        if (walk(ilb.expr, line, col, out)) return true
                        val typed = collectPatternBindings(ilb.pattern)
                        for (name in ilb.bindingNames) {
                            val pb = typed[name]
                            out.add(ScopeBinding(
                                name,
                                pb?.first ?: MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(name)),
                                pb?.second ?: ilb.loc,
                                ScopeBinding.Kind.IF_LET_BINDING
                            ))
                        }
                    }
                    val guardHit = case.guard?.let { walk(it, line, col, out) } ?: false
                    if (guardHit) return true
                    if (walk(case.body, line, col, out)) return true
                    // Cursor wasn't in this case — drop the bindings we added.
                    while (out.size > before) out.removeAt(out.size - 1)
                }
            }
            // Recurse without introducing bindings.
            is TypedCall -> {
                if (walk(expr.function, line, col, out)) return true
                walk(expr.arg, line, col, out)
            }
            is TypedRecord -> {
                for (entry in expr.entries) {
                    val hit = when (entry) {
                        is TypedRecordEntry.Field -> walk(entry.value, line, col, out)
                        is TypedRecordEntry.Spread -> walk(entry.expr, line, col, out)
                    }
                    if (hit) return true
                }
            }
            is TypedFieldAccess -> walk(expr.record, line, col, out)
            is TypedRecordUpdate -> {
                if (walk(expr.record, line, col, out)) return true
                for (entry in expr.updates) {
                    val hit = when (entry) {
                        is TypedRecordEntry.Field -> walk(entry.value, line, col, out)
                        is TypedRecordEntry.Spread -> walk(entry.expr, line, col, out)
                    }
                    if (hit) return true
                }
            }
            is TypedVariantConstruct -> { expr.payload?.let { walk(it, line, col, out) } }
            is TypedIf -> {
                if (walk(expr.condition, line, col, out)) return true
                if (walk(expr.thenBranch, line, col, out)) return true
                walk(expr.elseBranch, line, col, out)
            }
            is TypedSeq -> {
                for (elem in expr.elements) {
                    val hit = when (elem) {
                        is TypedSeqElement.Regular -> walk(elem.expr, line, col, out)
                        is TypedSeqElement.Spread -> walk(elem.expr, line, col, out)
                    }
                    if (hit) return true
                }
            }
            is TypedPerform -> walk(expr.body, line, col, out)
            is TypedPipe -> {
                if (walk(expr.left, line, col, out)) return true
                walk(expr.right, line, col, out)
            }
            is TypedStringLit -> {
                for (part in expr.parts) {
                    if (part is TypedStringPart.Interpolation) {
                        if (walk(part.expr, line, col, out)) return true
                    }
                }
            }
            // Leaves — no children, no binders.
            is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
            is TypedBoolLit, is TypedCharLit, is TypedFracLit, is TypedPerformOp -> {}
        }
        return true
    }

    /**
     * Recursively collect every variable a pattern binds, with its real type
     * and loc. Mirrors how the typechecker produces `PatternBinding`s for the
     * regular match path — used to give if-let bindings real types instead of
     * a placeholder tyvar.
     *
     * Record-field shorthand (`{ x }`, where the field pattern is null) binds
     * the field NAME to the field's type, which we read off the enclosing
     * record type's `fields` map.
     */
    private fun collectPatternBindings(pattern: TypedPattern): Map<String, Pair<MezzeType, Loc>> {
        val out = LinkedHashMap<String, Pair<MezzeType, Loc>>()
        fun go(p: TypedPattern) {
            when (p) {
                is TypedVarPattern -> out[p.name] = p.type to p.loc
                is TypedVariantPattern -> p.payload?.let { go(it) }
                is TypedRecordPattern -> {
                    val recFields = (p.type as? MezzeType.RecordType)?.fields
                    for (f in p.fields) {
                        val inner = f.pattern
                        if (inner != null) {
                            go(inner)
                        } else {
                            // shorthand `{ x }` — bind the field name to its type.
                            val t = recFields?.get(f.name)
                                ?: MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(f.name))
                            out[f.name] = t to f.loc
                        }
                    }
                }
                is TypedSeqPattern -> {
                    for (e in p.elements) go(e)
                    p.rest?.let { out[it] = p.type to p.loc }
                }
                is TypedStringInterpPattern -> {
                    for (part in p.parts) {
                        if (part is TypedStringPatternPart.Capture) {
                            out[part.variableName] = MezzeType.StringType to p.loc
                        }
                    }
                }
                is TypedWildcardPattern, is TypedLiteralPattern -> {}
            }
        }
        go(pattern)
        return out
    }

    private fun containsPosition(loc: Loc, line: Int, col: Int): Boolean {
        if (line < loc.startLine || line > loc.endLine) return false
        if (line == loc.startLine && col < loc.startCol) return false
        if (line == loc.endLine && col > loc.endCol) return false
        return true
    }

    /** True if [loc] ends strictly before (line, col). */
    private fun isLocBefore(loc: Loc, line: Int, col: Int): Boolean {
        if (loc.endLine < line) return true
        if (loc.endLine > line) return false
        return loc.endCol < col
    }
}
