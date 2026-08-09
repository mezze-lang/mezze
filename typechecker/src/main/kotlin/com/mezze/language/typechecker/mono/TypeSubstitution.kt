package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.PatternBinding
import com.mezze.language.typedast.TypeInstantiation
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedBoolLit
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedCapture
import com.mezze.language.typedast.TypedCase
import com.mezze.language.typedast.TypedCharLit
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedFieldPattern
import com.mezze.language.typedast.TypedFloatLit
import com.mezze.language.typedast.TypedFracLit
import com.mezze.language.typedast.TypedHandlerRef
import com.mezze.language.typedast.TypedIf
import com.mezze.language.typedast.TypedIfLetBinding
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLiteralPattern
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedNatLit
import com.mezze.language.typedast.TypedParam
import com.mezze.language.typedast.TypedPattern
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedPipe
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedRecordPattern
import com.mezze.language.typedast.TypedRecordUpdate
import com.mezze.language.typedast.TypedSeq
import com.mezze.language.typedast.TypedSeqElement
import com.mezze.language.typedast.TypedSeqPattern
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedStringInterpPattern
import com.mezze.language.typedast.TypedStringLit
import com.mezze.language.typedast.TypedStringPart
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantConstruct
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.TypedVarPattern
import com.mezze.language.typedast.TypedWildcardPattern
import com.mezze.language.typedast.forEachChild

/**
 * Type-variable substitution over TypedExpr / TypedPattern.
 *
 * Used by the Monomorphizer to specialize generic functions: every reference
 * to a quantified TypeVar is replaced with the concrete MezzeType the call
 * site instantiates it to. The transformation is purely structural — node
 * shapes are preserved, only the `type:` field on each node and the
 * `MezzeType` payloads of `TypeInstantiation` get rewritten.
 *
 * **Mono is rank-1**: lambda bodies do not introduce new quantified
 * TypeVars (`forallVars` is empty everywhere — confirmed in the typechecker
 * pipeline). So there is no alpha-renaming concern: every `VarType` we
 * encounter is either already in `substitution` (substitute it) or it's
 * a free variable from an outer scope (leave it). We don't need to mask
 * binders.
 *
 * **Side outputs** the Monomorphizer cares about:
 * - `BindingRef.ByHash` references to *other* generic functions are
 *   collected via [collectByHashRefs] so the worklist can enqueue them
 *   with the substituted type args. We don't rewrite those hashes here —
 *   that's the Monomorphizer's job (it allocates a fresh specialized hash
 *   and emits a second pass that rewrites every `ByHash(generic)` to
 *   `ByHash(specialized)` once it's resolved them all).
 */
object TypeSubstitution {

    /** Apply the substitution to a single MezzeType. Public so the worklist
     *  can rewrite TypeInstantiation entries and specialization keys.
     *
     *  Delegates to [com.mezze.language.ast.solving.Substitution.apply]
     *  which handles cycle detection and effect-row substitution. */
    fun applyType(type: MezzeType, substitution: Map<TypeVar, MezzeType>): MezzeType {
        if (substitution.isEmpty()) return type
        return com.mezze.language.ast.solving.Substitution(substitution).apply(type)
    }

    /** Apply substitution to a TypedExpr, rewriting every `type:` field and
     *  any TypeInstantiation type-arg lists. Tree shape is preserved. */
    fun applyExpr(expr: TypedExpr, sub: Map<TypeVar, MezzeType>): TypedExpr {
        if (sub.isEmpty()) return expr
        return when (expr) {
            is TypedIntLit -> expr.copy(type = applyType(expr.type, sub))
            is TypedNatLit -> expr.copy(type = applyType(expr.type, sub))
            is TypedFloatLit -> expr.copy(type = applyType(expr.type, sub))
            is TypedFracLit -> expr.copy(type = applyType(expr.type, sub))
            is TypedBoolLit -> expr.copy(type = applyType(expr.type, sub))
            is TypedCharLit -> expr.copy(type = applyType(expr.type, sub))

            is TypedStringLit -> expr.copy(
                parts = expr.parts.map { applyStringPart(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedVar -> expr.copy(type = applyType(expr.type, sub))

            is TypedLambda -> expr.copy(
                params = expr.params.map { it.copy(type = applyType(it.type, sub)) },
                body = applyExpr(expr.body, sub),
                captures = expr.captures.map { applyCapture(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedCall -> expr.copy(
                function = applyExpr(expr.function, sub),
                arg = applyExpr(expr.arg, sub),
                instantiation = expr.instantiation?.let { applyInstantiation(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedRecord -> expr.copy(
                entries = expr.entries.map { applyRecordEntry(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedFieldAccess -> expr.copy(
                record = applyExpr(expr.record, sub),
                type = applyType(expr.type, sub)
            )

            is TypedRecordUpdate -> expr.copy(
                record = applyExpr(expr.record, sub),
                updates = expr.updates.map { applyRecordEntry(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedVariantConstruct -> expr.copy(
                payload = expr.payload?.let { applyExpr(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedMatch -> expr.copy(
                scrutinee = applyExpr(expr.scrutinee, sub),
                cases = expr.cases.map { applyCase(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedIf -> expr.copy(
                condition = applyExpr(expr.condition, sub),
                thenBranch = applyExpr(expr.thenBranch, sub),
                elseBranch = applyExpr(expr.elseBranch, sub),
                type = applyType(expr.type, sub)
            )

            is TypedBlock -> expr.copy(
                statements = expr.statements.map { applyStatement(it, sub) },
                result = applyExpr(expr.result, sub),
                type = applyType(expr.type, sub)
            )

            is TypedSeq -> expr.copy(
                elements = expr.elements.map { applySeqElement(it, sub) },
                type = applyType(expr.type, sub)
            )

            is TypedPerform -> expr.copy(
                handlers = expr.handlers.map { applyHandlerRef(it, sub) },
                body = applyExpr(expr.body, sub),
                type = applyType(expr.type, sub)
            )

            is TypedPerformOp -> expr.copy(
                arg = applyExpr(expr.arg, sub),
                type = applyType(expr.type, sub)
            )

            is TypedPipe -> expr.copy(
                left = applyExpr(expr.left, sub),
                right = applyExpr(expr.right, sub),
                type = applyType(expr.type, sub)
            )
        }
    }

    fun applyPattern(pat: TypedPattern, sub: Map<TypeVar, MezzeType>): TypedPattern {
        if (sub.isEmpty()) return pat
        return when (pat) {
            is TypedVarPattern -> pat.copy(type = applyType(pat.type, sub))
            is TypedWildcardPattern -> pat.copy(type = applyType(pat.type, sub))
            is TypedLiteralPattern -> pat.copy(
                value = applyExpr(pat.value, sub),
                type = applyType(pat.type, sub)
            )
            is TypedVariantPattern -> pat.copy(
                payload = pat.payload?.let { applyPattern(it, sub) },
                type = applyType(pat.type, sub)
            )
            is TypedRecordPattern -> pat.copy(
                fields = pat.fields.map { applyFieldPattern(it, sub) },
                type = applyType(pat.type, sub)
            )
            is TypedSeqPattern -> pat.copy(
                elements = pat.elements.map { applyPattern(it, sub) },
                type = applyType(pat.type, sub)
            )
            is TypedStringInterpPattern -> pat.copy(type = applyType(pat.type, sub))
        }
    }

    /**
     * Find every `BindingRef.ByHash` reference in an expression. Used by
     * the Monomorphizer's worklist driver to discover which other generic
     * functions a specialization needs to recursively specialize. The
     * substitution is *not* applied here — the worklist applies it once
     * the dependent specializations have been resolved.
     */
    fun collectByHashRefs(expr: TypedExpr, into: MutableList<Pair<BindingRef.ByHash, TypeInstantiation?>>) {
        collectByHashRefsWithSite(expr) { ref, inst, _, _ -> into.add(ref to inst) }
    }

    /**
     * Like [collectByHashRefs], but also exposes:
     *  - the site's substituted function type at each `TypedCall` (for
     *    unification-based type-arg derivation);
     *  - the enclosing `TypedCall` node itself (or null for non-call
     *    refs) — #424 uses it to harvest closure-arg literals from the
     *    call's record arg.
     */
    fun collectByHashRefsWithSite(
        expr: TypedExpr,
        into: (BindingRef.ByHash, TypeInstantiation?, MezzeType?, com.mezze.language.typedast.TypedCall?) -> Unit
    ) {
        when {
            expr is TypedVar -> {
                val b = expr.binding
                if (b is BindingRef.ByHash) into(b, null, expr.type, null)
            }
            expr is TypedCall && expr.function is TypedVar && (expr.function as TypedVar).binding is BindingRef.ByHash -> {
                val fn = expr.function as TypedVar
                into(fn.binding as BindingRef.ByHash, expr.instantiation, fn.type, expr)
                collectByHashRefsWithSite(expr.arg, into)
            }
            else -> expr.forEachChild { collectByHashRefsWithSite(it, into) }
        }
    }

    // -------- helpers --------

    private fun applyStringPart(part: TypedStringPart, sub: Map<TypeVar, MezzeType>): TypedStringPart =
        when (part) {
            is TypedStringPart.Literal -> part
            is TypedStringPart.Interpolation -> TypedStringPart.Interpolation(applyExpr(part.expr, sub))
        }

    private fun applyCapture(c: TypedCapture, sub: Map<TypeVar, MezzeType>): TypedCapture =
        c.copy(type = applyType(c.type, sub))

    private fun applyInstantiation(i: TypeInstantiation, sub: Map<TypeVar, MezzeType>): TypeInstantiation =
        TypeInstantiation(
            typeArgs = i.typeArgs.map { applyType(it, sub) },
            abilityImpls = i.abilityImpls.map { ref ->
                AbilityImplRef(ref.abilityHash, ref.implHash, applyType(ref.targetType, sub))
            }
        )

    private fun applyRecordEntry(e: TypedRecordEntry, sub: Map<TypeVar, MezzeType>): TypedRecordEntry =
        when (e) {
            is TypedRecordEntry.Field -> e.copy(value = applyExpr(e.value, sub))
            is TypedRecordEntry.Spread -> e.copy(expr = applyExpr(e.expr, sub))
        }

    private fun applyCase(c: TypedCase, sub: Map<TypeVar, MezzeType>): TypedCase =
        c.copy(
            patterns = c.patterns.map { applyPattern(it, sub) },
            bindings = c.bindings.map { applyPatternBinding(it, sub) },
            guard = c.guard?.let { applyExpr(it, sub) },
            body = applyExpr(c.body, sub),
            ifLetBindings = c.ifLetBindings.map { applyIfLetBinding(it, sub) }
        )

    private fun applyPatternBinding(b: PatternBinding, sub: Map<TypeVar, MezzeType>): PatternBinding =
        b.copy(type = applyType(b.type, sub))

    private fun applyIfLetBinding(b: TypedIfLetBinding, sub: Map<TypeVar, MezzeType>): TypedIfLetBinding =
        b.copy(
            pattern = applyPattern(b.pattern, sub),
            expr = applyExpr(b.expr, sub)
        )

    private fun applyStatement(s: TypedStatement, sub: Map<TypeVar, MezzeType>): TypedStatement =
        when (s) {
            is TypedStatement.Let -> s.copy(
                type = applyType(s.type, sub),
                value = applyExpr(s.value, sub)
            )
            is TypedStatement.Expr -> s.copy(expr = applyExpr(s.expr, sub))
        }

    private fun applySeqElement(e: TypedSeqElement, sub: Map<TypeVar, MezzeType>): TypedSeqElement =
        when (e) {
            is TypedSeqElement.Regular -> TypedSeqElement.Regular(applyExpr(e.expr, sub))
            is TypedSeqElement.Spread -> TypedSeqElement.Spread(applyExpr(e.expr, sub))
        }

    private fun applyHandlerRef(h: TypedHandlerRef, sub: Map<TypeVar, MezzeType>): TypedHandlerRef =
        h.copy(params = h.params?.let { applyExpr(it, sub) })

    private fun applyFieldPattern(f: TypedFieldPattern, sub: Map<TypeVar, MezzeType>): TypedFieldPattern =
        f.copy(pattern = f.pattern?.let { applyPattern(it, sub) })
}

/**
 * #424 Piece 3 — splice lambda IR into a specialized body in place of
 * every read of a closure parameter.
 *
 * The mono driver has decided that at this call site the caller passes
 * lambda literal `L` for parameter `f`. We rewrite the specialized
 * generic's body so that:
 *
 *  1. `TypedCall(f {…})` — every direct call of the closure param —
 *     becomes a beta-reduced copy of `L`'s body, with `L`'s own params
 *     bound to the record fields the caller passed.
 *  2. `TypedVar(f)` — every read of the closure that ISN'T being called
 *     (typically as a forwarded record field: `fold_go { …, f = f }`)
 *     becomes a fresh copy of `L` itself. After this rewrite, the
 *     forwarded position holds a lambda literal, so a transitive
 *     specialization of `fold_go` picks up the same closure identity.
 *
 * Purity guard: we only splice when `L`'s body is "pure enough" — no
 * `TypedPerform`, no ability-method call. Impure closures fall through
 * unchanged (the specialization still exists, but reads `f` via the
 * record like the pre-#424 shape). This lets us avoid duplicating an
 * effect handler installation, which would change the semantics.
 */
object LambdaSubstitution {

    /** Entry point used by the Monomorphizer. `closureBodies` maps a
     *  fieldName (which is also the outer lambda's param name) to the
     *  lambda IR to splice for that param. */
    fun substituteClosures(
        body: TypedExpr,
        closureBodies: Map<String, com.mezze.language.typedast.TypedLambda>,
    ): TypedExpr {
        if (closureBodies.isEmpty()) return body
        // Purity guard — bail per-lambda that isn't safe to inline.
        val safe = closureBodies.filterValues { isSafeToInline(it.body) }
        if (safe.isEmpty()) return body
        return substituteInExpr(body, safe)
    }

    // Heuristic purity check — deliberately conservative. A closure body
    // with `perform` or an ability-method call gets punted so we don't
    // duplicate handler installations. Everything else — arithmetic,
    // record construction, field access, plain calls, matches — is OK.
    private fun isSafeToInline(e: TypedExpr): Boolean {
        var safe = true
        fun walk(x: TypedExpr) {
            if (!safe) return
            when (x) {
                is com.mezze.language.typedast.TypedPerform -> safe = false
                is com.mezze.language.typedast.TypedPerformOp -> safe = false
                is TypedCall -> {
                    // A method-style call (fn is a TypedFieldAccess on
                    // a receiver that's not a plain record read) is
                    // likely an ability method — hard to reason about
                    // whether splicing changes dispatch. Punt.
                    val fn = x.function
                    if (fn is com.mezze.language.typedast.TypedFieldAccess) {
                        // A field access on a `TypedVar` is a plain
                        // record read (e.g. `env.x`) — safe.
                        if (fn.record !is TypedVar) {
                            safe = false
                            return
                        }
                    }
                    x.forEachChild { walk(it) }
                }
                else -> x.forEachChild { walk(it) }
            }
        }
        walk(e)
        return safe
    }

    private fun substituteInExpr(
        expr: TypedExpr,
        closures: Map<String, com.mezze.language.typedast.TypedLambda>,
    ): TypedExpr {
        return when (expr) {
            // Beta-reduce `f {arg-record}` where `f` is a closure param.
            is TypedCall -> {
                val fn = expr.function
                if (fn is TypedVar && fn.binding is BindingRef.Param) {
                    val lam = closures[fn.name]
                    if (lam != null) {
                        val argRec = substituteInExpr(expr.arg, closures)
                        return betaReduce(lam, argRec, expr)
                    }
                }
                // Recurse — the closure param might sit inside a
                // sub-expression (e.g. an outer call's arg record).
                expr.copy(
                    function = substituteInExpr(fn, closures),
                    arg = substituteInExpr(expr.arg, closures),
                )
            }
            // Replace bare reads of the closure param with a fresh copy
            // of the lambda literal. Downstream (record forward paths)
            // picks it up as a lambda literal → transitive spec.
            is TypedVar -> {
                if (expr.binding is BindingRef.Param) {
                    val lam = closures[expr.name]
                    if (lam != null) return lam
                }
                expr
            }
            is com.mezze.language.typedast.TypedLambda -> expr.copy(
                body = substituteInExpr(expr.body, closures),
            )
            is com.mezze.language.typedast.TypedRecord -> expr.copy(
                entries = expr.entries.map { substituteInRecordEntry(it, closures) },
            )
            is com.mezze.language.typedast.TypedRecordUpdate -> expr.copy(
                record = substituteInExpr(expr.record, closures),
                updates = expr.updates.map { substituteInRecordEntry(it, closures) },
            )
            is com.mezze.language.typedast.TypedFieldAccess -> expr.copy(
                record = substituteInExpr(expr.record, closures),
            )
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val payload = expr.payload
                if (payload == null) expr
                else expr.copy(payload = substituteInExpr(payload, closures))
            }
            is com.mezze.language.typedast.TypedMatch -> expr.copy(
                scrutinee = substituteInExpr(expr.scrutinee, closures),
                cases = expr.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { substituteInExpr(it, closures) },
                        body = substituteInExpr(c.body, closures),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> expr.copy(
                condition = substituteInExpr(expr.condition, closures),
                thenBranch = substituteInExpr(expr.thenBranch, closures),
                elseBranch = substituteInExpr(expr.elseBranch, closures),
            )
            is com.mezze.language.typedast.TypedBlock -> expr.copy(
                statements = expr.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let ->
                            s.copy(value = substituteInExpr(s.value, closures))
                        is com.mezze.language.typedast.TypedStatement.Expr ->
                            s.copy(expr = substituteInExpr(s.expr, closures))
                    }
                },
                result = substituteInExpr(expr.result, closures),
            )
            is com.mezze.language.typedast.TypedSeq -> expr.copy(
                elements = expr.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular ->
                            el.copy(expr = substituteInExpr(el.expr, closures))
                        is com.mezze.language.typedast.TypedSeqElement.Spread ->
                            el.copy(expr = substituteInExpr(el.expr, closures))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> expr.copy(
                left = substituteInExpr(expr.left, closures),
                right = substituteInExpr(expr.right, closures),
            )
            is com.mezze.language.typedast.TypedPerform -> expr.copy(
                body = substituteInExpr(expr.body, closures),
            )
            is com.mezze.language.typedast.TypedPerformOp -> expr.copy(
                arg = substituteInExpr(expr.arg, closures),
            )
            else -> expr
        }
    }

    private fun substituteInRecordEntry(
        entry: com.mezze.language.typedast.TypedRecordEntry,
        closures: Map<String, com.mezze.language.typedast.TypedLambda>,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field ->
            entry.copy(value = substituteInExpr(entry.value, closures))
        is com.mezze.language.typedast.TypedRecordEntry.Spread ->
            entry.copy(expr = substituteInExpr(entry.expr, closures))
    }

    /**
     * Beta-reduce `lambda applied-to argRecord`. Mezze lambdas take a
     * single record param — the outer TypedLambda destructures it into
     * N named params. To beta-reduce, we build a substitution
     * `paramName → argRecord.fieldName-value` and rewrite the body:
     * every `TypedVar(binding = Param(i))` where `paramNames[i]` is a
     * key becomes the corresponding value.
     *
     * If argRecord isn't a TypedRecord literal (rare — a caller passed
     * a variable-bound record), fall back to `(lambda)(argRecord)` as
     * a plain call so semantics stay correct.
     */
    private fun betaReduce(
        lambda: com.mezze.language.typedast.TypedLambda,
        argRecord: TypedExpr,
        originalCall: TypedCall,
    ): TypedExpr {
        // Only handle the record-literal shape — that's what every
        // lambda-literal call site produces after the typechecker.
        if (argRecord !is com.mezze.language.typedast.TypedRecord) {
            return originalCall.copy(function = lambda, arg = argRecord)
        }
        // Build fieldName → value substitution.
        val fieldValues = HashMap<String, TypedExpr>(lambda.params.size)
        for (entry in argRecord.entries) {
            if (entry !is com.mezze.language.typedast.TypedRecordEntry.Field) continue
            fieldValues[entry.name] = entry.value
        }
        // If any param is missing a matching field, bail — safer than
        // producing a body with unresolved var reads.
        for (p in lambda.params) {
            if (p.name !in fieldValues) return originalCall.copy(function = lambda, arg = argRecord)
        }
        // Emit `let p1 = v1; let p2 = v2; ...; body` so a subsequent
        // compilation can still see the params via slot lookups. That's
        // simpler and safer than rewriting Param(i) refs directly.
        //
        // BUT: the lambda's body still contains `TypedVar(binding =
        // Param(i))` references — they point to the LAMBDA's outer
        // param slot, not the block's local slot. We rewrite these
        // to `BindingRef.Local(depth=0, index=<slot>)` so the compiler
        // resolves them via slot lookup. The block adds one slot per
        // param in declaration order, so paramName → localIndex is
        // just the position in `lambda.params`.
        val paramNameToLocal = HashMap<String, Int>(lambda.params.size)
        for ((idx, p) in lambda.params.withIndex()) paramNameToLocal[p.name] = idx
        val statements = ArrayList<com.mezze.language.typedast.TypedStatement>(lambda.params.size)
        for (p in lambda.params) {
            val v = fieldValues.getValue(p.name)
            val letHash = com.mezze.language.typedast.TypedASTHasher.hashExpr(v)
            statements.add(
                com.mezze.language.typedast.TypedStatement.Let(
                    name = p.name,
                    contentHash = letHash,
                    type = p.type,
                    value = v,
                    loc = p.loc,
                )
            )
        }
        val rewrittenBody = rewriteParamRefs(lambda.body, paramNameToLocal)
        return com.mezze.language.typedast.TypedBlock(
            statements = statements,
            result = rewrittenBody,
            type = rewrittenBody.type,
            loc = originalCall.loc,
        )
    }

    /** Rewrite `TypedVar(binding = Param(i), name = <p>)` inside the
     *  spliced body to `TypedVar(binding = Local(0, slot))` so the
     *  compiler resolves it against the block's local slots. Params
     *  that don't match a splice-time name are left alone — they
     *  might refer to a nested lambda's own params (Param slot in
     *  child frame, not caller's). */
    private fun rewriteParamRefs(
        expr: TypedExpr,
        paramNameToLocal: Map<String, Int>,
    ): TypedExpr {
        return when (expr) {
            is TypedVar -> {
                if (expr.binding is BindingRef.Param) {
                    val slot = paramNameToLocal[expr.name]
                    if (slot != null) {
                        return expr.copy(binding = BindingRef.Local(depth = 0, index = slot))
                    }
                }
                expr
            }
            is com.mezze.language.typedast.TypedLambda -> {
                // Nested lambda: its own params shadow our splice
                // names. Don't rewrite Param refs inside — they refer
                // to the nested frame. But captures might reference
                // our splice slots, so those already resolve at
                // compile time via `collectCaptures` in the compiler;
                // no rewrite needed here.
                expr
            }
            is TypedCall -> expr.copy(
                function = rewriteParamRefs(expr.function, paramNameToLocal),
                arg = rewriteParamRefs(expr.arg, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedRecord -> expr.copy(
                entries = expr.entries.map { rewriteParamRefsInRecordEntry(it, paramNameToLocal) },
            )
            is com.mezze.language.typedast.TypedRecordUpdate -> expr.copy(
                record = rewriteParamRefs(expr.record, paramNameToLocal),
                updates = expr.updates.map { rewriteParamRefsInRecordEntry(it, paramNameToLocal) },
            )
            is com.mezze.language.typedast.TypedFieldAccess -> expr.copy(
                record = rewriteParamRefs(expr.record, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val payload = expr.payload
                if (payload == null) expr
                else expr.copy(payload = rewriteParamRefs(payload, paramNameToLocal))
            }
            is com.mezze.language.typedast.TypedMatch -> expr.copy(
                scrutinee = rewriteParamRefs(expr.scrutinee, paramNameToLocal),
                cases = expr.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { rewriteParamRefs(it, paramNameToLocal) },
                        body = rewriteParamRefs(c.body, paramNameToLocal),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> expr.copy(
                condition = rewriteParamRefs(expr.condition, paramNameToLocal),
                thenBranch = rewriteParamRefs(expr.thenBranch, paramNameToLocal),
                elseBranch = rewriteParamRefs(expr.elseBranch, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedBlock -> expr.copy(
                statements = expr.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let ->
                            s.copy(value = rewriteParamRefs(s.value, paramNameToLocal))
                        is com.mezze.language.typedast.TypedStatement.Expr ->
                            s.copy(expr = rewriteParamRefs(s.expr, paramNameToLocal))
                    }
                },
                result = rewriteParamRefs(expr.result, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedSeq -> expr.copy(
                elements = expr.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular ->
                            el.copy(expr = rewriteParamRefs(el.expr, paramNameToLocal))
                        is com.mezze.language.typedast.TypedSeqElement.Spread ->
                            el.copy(expr = rewriteParamRefs(el.expr, paramNameToLocal))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> expr.copy(
                left = rewriteParamRefs(expr.left, paramNameToLocal),
                right = rewriteParamRefs(expr.right, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedPerform -> expr.copy(
                body = rewriteParamRefs(expr.body, paramNameToLocal),
            )
            is com.mezze.language.typedast.TypedPerformOp -> expr.copy(
                arg = rewriteParamRefs(expr.arg, paramNameToLocal),
            )
            else -> expr
        }
    }

    private fun rewriteParamRefsInRecordEntry(
        entry: com.mezze.language.typedast.TypedRecordEntry,
        paramNameToLocal: Map<String, Int>,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field ->
            entry.copy(value = rewriteParamRefs(entry.value, paramNameToLocal))
        is com.mezze.language.typedast.TypedRecordEntry.Spread ->
            entry.copy(expr = rewriteParamRefs(entry.expr, paramNameToLocal))
    }
}
