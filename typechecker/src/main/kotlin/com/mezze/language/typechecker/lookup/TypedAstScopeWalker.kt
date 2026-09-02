package com.mezze.language.typechecker.lookup

import com.mezze.language.ast.BuiltinTypes
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
     * The match- or if-let PATTERN binding whose OWN name-loc contains
     * (line, col), or null. Unlike [bindingsAtPosition] — which reports the
     * bindings in scope for a body — this answers "is the cursor sitting on the
     * binder itself?", so hover on the `val` in `Ok { val } -> …` can show
     * `val`'s type instead of the enclosing match's result type (which
     * [TypedAstLookup.findAtPosition] returns for a cursor on the pattern).
     */
    fun patternBindingAt(module: TypedModule, line: Int, col: Int): ScopeBinding? {
        for (decl in module.decls) {
            if (decl is TypedLetDecl && containsPosition(decl.loc, line, col)) {
                return findPatternBinding(decl.value, line, col)
            }
        }
        return null
    }

    private fun findPatternBinding(expr: TypedExpr, line: Int, col: Int): ScopeBinding? {
        if (!containsPosition(expr.loc, line, col)) return null
        when (expr) {
            is TypedMatch -> {
                findPatternBinding(expr.scrutinee, line, col)?.let { return it }
                for (case in expr.cases) {
                    for (b in case.bindings) {
                        if (containsPosition(b.loc, line, col)) {
                            val ty = bindingType(expr.scrutineeSourceType, case.patterns, b)
                            return ScopeBinding(b.name, ty, b.loc, ScopeBinding.Kind.MATCH_BINDING)
                        }
                    }
                    for (ilb in case.ifLetBindings) {
                        val typed = collectPatternBindings(ilb.pattern)
                        for ((name, tl) in typed) {
                            if (containsPosition(tl.second, line, col)) {
                                return ScopeBinding(name, tl.first, tl.second, ScopeBinding.Kind.IF_LET_BINDING)
                            }
                        }
                        findPatternBinding(ilb.expr, line, col)?.let { return it }
                    }
                    case.guard?.let { g -> findPatternBinding(g, line, col)?.let { return it } }
                    findPatternBinding(case.body, line, col)?.let { return it }
                }
            }
            is TypedLambda -> return findPatternBinding(expr.body, line, col)
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    val r = when (stmt) {
                        is TypedStatement.Let -> findPatternBinding(stmt.value, line, col)
                        is TypedStatement.Expr -> findPatternBinding(stmt.expr, line, col)
                    }
                    if (r != null) return r
                }
                return findPatternBinding(expr.result, line, col)
            }
            is TypedCall -> {
                findPatternBinding(expr.function, line, col)?.let { return it }
                return findPatternBinding(expr.arg, line, col)
            }
            is TypedRecord -> for (e in expr.entries) {
                val r = when (e) {
                    is TypedRecordEntry.Field -> findPatternBinding(e.value, line, col)
                    is TypedRecordEntry.Spread -> findPatternBinding(e.expr, line, col)
                }
                if (r != null) return r
            }
            is TypedFieldAccess -> return findPatternBinding(expr.record, line, col)
            is TypedIf -> {
                findPatternBinding(expr.condition, line, col)?.let { return it }
                findPatternBinding(expr.thenBranch, line, col)?.let { return it }
                return findPatternBinding(expr.elseBranch, line, col)
            }
            is TypedPerform -> return findPatternBinding(expr.body, line, col)
            is TypedPipe -> {
                findPatternBinding(expr.left, line, col)?.let { return it }
                return findPatternBinding(expr.right, line, col)
            }
            is TypedSeq -> for (el in expr.elements) {
                val r = when (el) {
                    is TypedSeqElement.Regular -> findPatternBinding(el.expr, line, col)
                    is TypedSeqElement.Spread -> findPatternBinding(el.expr, line, col)
                }
                if (r != null) return r
            }
            is TypedRecordUpdate -> {
                findPatternBinding(expr.record, line, col)?.let { return it }
                for (e in expr.updates) {
                    val r = when (e) {
                        is TypedRecordEntry.Field -> findPatternBinding(e.value, line, col)
                        is TypedRecordEntry.Spread -> findPatternBinding(e.expr, line, col)
                    }
                    if (r != null) return r
                }
            }
            is TypedVariantConstruct -> expr.payload?.let { return findPatternBinding(it, line, col) }
            is TypedStringLit -> for (part in expr.parts) {
                if (part is TypedStringPart.Interpolation) {
                    findPatternBinding(part.expr, line, col)?.let { return it }
                }
            }
            else -> {}
        }
        return null
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
                        val ty = bindingType(expr.scrutineeSourceType, case.patterns, b)
                        out.add(ScopeBinding(b.name, ty, b.loc, ScopeBinding.Kind.MATCH_BINDING))
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

    /**
     * A match binding's type for display. The solver occasionally leaves a
     * pattern binder's stored type an unsolved var even when the SCRUTINEE is
     * fully resolved (the binder tyvar was never unified with the scrutinee's
     * payload — a constraint-gen gap). When that happens, recover the type
     * structurally from the resolved scrutinee type + the case patterns.
     * Fallback-only: a binder whose stored type is already solved is kept as-is,
     * and an unhandled shape keeps the var (no regression).
     */
    private fun bindingType(scrutinee: MezzeType, patterns: List<TypedPattern>, b: PatternBinding): MezzeType =
        if (b.type is MezzeType.VarType) deriveBindingType(scrutinee, patterns, b.name) ?: b.type else b.type

    private fun deriveBindingType(scrutinee: MezzeType, patterns: List<TypedPattern>, name: String): MezzeType? {
        val out = HashMap<String, MezzeType>()
        for (p in patterns) derivePatternTypes(scrutinee, p, out)
        return out[name]
    }

    /** Bind each variable in [pattern] to its type given the [scrutinee] type. */
    private fun derivePatternTypes(scrutinee: MezzeType, pattern: TypedPattern, out: MutableMap<String, MezzeType>) {
        when (pattern) {
            is TypedVarPattern -> out[pattern.name] = scrutinee
            is TypedVariantPattern -> {
                val payload = pattern.payload ?: return
                val payloadFields: Map<String, MezzeType>? = when (scrutinee) {
                    is MezzeType.VariantType ->
                        (scrutinee.constructors[pattern.tag] as? MezzeType.RecordType)?.fields
                    is MezzeType.TypeApplication -> resultOptionPayloadFields(scrutinee, pattern.tag)
                    else -> null
                }
                if (payload is TypedRecordPattern && payloadFields != null) {
                    for (f in payload.fields) {
                        val ft = payloadFields[f.name] ?: continue
                        val inner = f.pattern
                        if (inner != null) derivePatternTypes(ft, inner, out) else out[f.name] = ft
                    }
                } else if (scrutinee is MezzeType.VariantType) {
                    scrutinee.constructors[pattern.tag]?.let { derivePatternTypes(it, payload, out) }
                }
            }
            is TypedRecordPattern -> {
                val fields = (scrutinee as? MezzeType.RecordType)?.fields
                for (f in pattern.fields) {
                    val ft = fields?.get(f.name)
                    val inner = f.pattern
                    if (inner != null) { if (ft != null) derivePatternTypes(ft, inner, out) } else if (ft != null) out[f.name] = ft
                }
            }
            is TypedSeqPattern -> {
                val elem = (scrutinee as? MezzeType.SeqType)?.elementType
                if (elem != null) for (e in pattern.elements) derivePatternTypes(elem, e, out)
                pattern.rest?.let { out[it] = scrutinee }
            }
            is TypedStringInterpPattern ->
                for (part in pattern.parts) {
                    if (part is TypedStringPatternPart.Capture) out[part.variableName] = MezzeType.StringType
                }
            is TypedWildcardPattern, is TypedLiteralPattern -> {}
        }
    }

    /** Ok/Err/Some payload fields for a resolved `Result`/`Option` application. */
    private fun resultOptionPayloadFields(app: MezzeType.TypeApplication, tag: String): Map<String, MezzeType>? =
        when (app.constructor) {
            BuiltinTypes.RESULT -> when (tag) {
                BuiltinTypes.OK -> app.args.getOrNull(0)?.let { mapOf(BuiltinTypes.VAL_FIELD to it) }
                BuiltinTypes.ERR -> app.args.getOrNull(1)?.let { mapOf(BuiltinTypes.ERROR_FIELD to it) }
                else -> null
            }
            BuiltinTypes.OPTION -> when (tag) {
                BuiltinTypes.SOME -> app.args.getOrNull(0)?.let { mapOf(BuiltinTypes.VAL_FIELD to it) }
                else -> null
            }
            else -> null
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
