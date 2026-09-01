package com.mezze.language.typedast

import com.mezze.language.ast.MezzeType

/**
 * Return a copy of this node with its static [TypedExpr.type] replaced by
 * [newType], leaving every other field (and all children) untouched.
 *
 * Used where a static-type refinement must be stamped onto a node whose
 * structure stays byte-for-byte identical — e.g. newtype construction, whose
 * emitted value node is the runtime-erased underlying value but whose STATIC
 * type must remain the `NewtypeType` wrapper (see #395). Runtime lowering reads
 * the node's shape, not this field, so overriding the type is a pure static
 * refinement with no runtime effect.
 */
fun TypedExpr.withType(newType: MezzeType): TypedExpr = when (this) {
    is TypedIntLit -> copy(type = newType)
    is TypedNatLit -> copy(type = newType)
    is TypedFloatLit -> copy(type = newType)
    is TypedFracLit -> copy(type = newType)
    is TypedBoolLit -> copy(type = newType)
    is TypedCharLit -> copy(type = newType)
    is TypedStringLit -> copy(type = newType)
    is TypedVar -> copy(type = newType)
    is TypedLambda -> copy(type = newType)
    is TypedCall -> copy(type = newType)
    is TypedRecord -> copy(type = newType)
    is TypedFieldAccess -> copy(type = newType)
    is TypedRecordUpdate -> copy(type = newType)
    is TypedVariantConstruct -> copy(type = newType)
    is TypedMatch -> copy(type = newType)
    is TypedIf -> copy(type = newType)
    is TypedBlock -> copy(type = newType)
    is TypedSeq -> copy(type = newType)
    is TypedPerform -> copy(type = newType)
    is TypedPerformOp -> copy(type = newType)
    is TypedPipe -> copy(type = newType)
}

/**
 * Visits every direct child [TypedExpr] of `this`, in source order.
 *
 * Container/sequence wrappers (TypedRecordEntry, TypedSeqElement,
 * TypedStringPart, TypedStatement, TypedCase, TypedIfLetBinding,
 * TypedHandlerRef) are transparent — their inner TypedExpr is what
 * gets visited. Leaves (literals) call `f` zero times.
 *
 * Callers that need to visit `this` itself should call `f(this)`
 * themselves, then use `forEachChild(f)` for descendants — the same
 * shape used by every hand-rolled walker this replaces.
 */
inline fun TypedExpr.forEachChild(f: (TypedExpr) -> Unit) {
    when (this) {
        is TypedIntLit, is TypedNatLit, is TypedFloatLit, is TypedFracLit,
        is TypedBoolLit, is TypedCharLit, is TypedVar -> Unit
        is TypedStringLit -> for (p in parts) {
            if (p is TypedStringPart.Interpolation) f(p.expr)
        }
        is TypedLambda -> f(body)
        is TypedCall -> { f(function); f(arg) }
        is TypedRecord -> for (e in entries) when (e) {
            is TypedRecordEntry.Field -> f(e.value)
            is TypedRecordEntry.Spread -> f(e.expr)
        }
        is TypedFieldAccess -> f(record)
        is TypedRecordUpdate -> {
            f(record)
            for (e in updates) when (e) {
                is TypedRecordEntry.Field -> f(e.value)
                is TypedRecordEntry.Spread -> f(e.expr)
            }
        }
        is TypedVariantConstruct -> payload?.let(f)
        is TypedMatch -> {
            f(scrutinee)
            for (c in cases) {
                c.guard?.let(f)
                f(c.body)
                for (b in c.ifLetBindings) f(b.expr)
            }
        }
        is TypedIf -> { f(condition); f(thenBranch); f(elseBranch) }
        is TypedBlock -> {
            for (s in statements) when (s) {
                is TypedStatement.Let -> f(s.value)
                is TypedStatement.Expr -> f(s.expr)
            }
            f(result)
        }
        is TypedSeq -> for (e in elements) when (e) {
            is TypedSeqElement.Regular -> f(e.expr)
            is TypedSeqElement.Spread -> f(e.expr)
        }
        is TypedPerform -> {
            for (h in handlers) h.params?.let(f)
            f(body)
        }
        is TypedPerformOp -> f(arg)
        is TypedPipe -> { f(left); f(right) }
    }
}
