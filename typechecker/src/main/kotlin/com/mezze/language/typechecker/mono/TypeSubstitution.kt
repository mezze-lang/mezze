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
     *  Delegates to [com.mezze.language.typechecker.solving.Substitution.apply]
     *  which handles cycle detection and effect-row substitution. */
    fun applyType(type: MezzeType, substitution: Map<TypeVar, MezzeType>): MezzeType {
        if (substitution.isEmpty()) return type
        return com.mezze.language.typechecker.solving.Substitution(substitution).apply(type)
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
        when (expr) {
            is TypedVar -> {
                val b = expr.binding
                if (b is BindingRef.ByHash) into.add(b to null)
            }
            is TypedCall -> {
                // The function expression carries the ByHash; the call's
                // instantiation tells us the type args at this site.
                val fn = expr.function
                if (fn is TypedVar) {
                    val b = fn.binding
                    if (b is BindingRef.ByHash) into.add(b to expr.instantiation)
                    else collectByHashRefs(fn, into)
                } else {
                    collectByHashRefs(fn, into)
                }
                collectByHashRefs(expr.arg, into)
            }
            is TypedLambda -> collectByHashRefs(expr.body, into)
            is TypedRecord -> for (e in expr.entries) when (e) {
                is TypedRecordEntry.Field -> collectByHashRefs(e.value, into)
                is TypedRecordEntry.Spread -> collectByHashRefs(e.expr, into)
            }
            is TypedFieldAccess -> collectByHashRefs(expr.record, into)
            is TypedRecordUpdate -> {
                collectByHashRefs(expr.record, into)
                for (e in expr.updates) when (e) {
                    is TypedRecordEntry.Field -> collectByHashRefs(e.value, into)
                    is TypedRecordEntry.Spread -> collectByHashRefs(e.expr, into)
                }
            }
            is TypedVariantConstruct -> expr.payload?.let { collectByHashRefs(it, into) }
            is TypedMatch -> {
                collectByHashRefs(expr.scrutinee, into)
                for (c in expr.cases) {
                    c.guard?.let { collectByHashRefs(it, into) }
                    collectByHashRefs(c.body, into)
                    for (b in c.ifLetBindings) collectByHashRefs(b.expr, into)
                }
            }
            is TypedIf -> {
                collectByHashRefs(expr.condition, into)
                collectByHashRefs(expr.thenBranch, into)
                collectByHashRefs(expr.elseBranch, into)
            }
            is TypedBlock -> {
                for (s in expr.statements) when (s) {
                    is TypedStatement.Let -> collectByHashRefs(s.value, into)
                    is TypedStatement.Expr -> collectByHashRefs(s.expr, into)
                }
                collectByHashRefs(expr.result, into)
            }
            is TypedSeq -> for (e in expr.elements) when (e) {
                is TypedSeqElement.Regular -> collectByHashRefs(e.expr, into)
                is TypedSeqElement.Spread -> collectByHashRefs(e.expr, into)
            }
            is TypedPerform -> {
                for (h in expr.handlers) h.params?.let { collectByHashRefs(it, into) }
                collectByHashRefs(expr.body, into)
            }
            is TypedPerformOp -> collectByHashRefs(expr.arg, into)
            is TypedPipe -> {
                collectByHashRefs(expr.left, into)
                collectByHashRefs(expr.right, into)
            }
            is TypedStringLit -> for (p in expr.parts) if (p is TypedStringPart.Interpolation) collectByHashRefs(p.expr, into)
            // Literals carry no refs.
            is TypedIntLit, is TypedNatLit, is TypedFloatLit, is TypedFracLit,
            is TypedBoolLit, is TypedCharLit -> Unit
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
