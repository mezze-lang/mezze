package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typedast.*

/**
 * #311 — Variant constructors must have a single consistent payload shape per
 * name within a module.
 *
 * Anonymous variant constructors (those used at expression sites without a
 * preceding `type T = | Tag {…} |` declaration) get inferred as closed
 * variants from their first use. Two uses of the same constructor name
 * with different payload shapes would each independently typecheck — the
 * resulting types are mutually unrelated — but the user almost certainly
 * meant them to be the same constructor and made a mistake. This pass walks
 * the typed module after inference, collects the payload shape per
 * constructor tag, and rejects any second use whose shape disagrees.
 *
 * "Same shape" means: same set of field names AND each field's type unifies
 * (or is structurally identical post-substitution). A unit-payload
 * constructor (`Red`) and a record-payload constructor (`Red {val: Int}`)
 * with the same name is a shape conflict.
 *
 * Constructors declared via `type T = | Tag {…} |` are exempt from this
 * pass — DuplicateDeclarationValidator already enforces that all uses match
 * the declared shape (and globally-unique constructor names mean a single
 * declared `Tag` is the only one in the module).
 */
object AnonymousConstructorShapeValidator {
    fun validate(module: TypedModule, declaredConstructors: Set<String>) {
        val firstShape = mutableMapOf<String, ShapeRecord>()
        for (decl in module.decls) {
            walkDecl(decl, declaredConstructors, firstShape)
        }
        for (impl in module.abilityImpls) {
            impl.operations.values.forEach { walk(it, declaredConstructors, firstShape) }
        }
        for (performer in module.performerDecls) {
            performer.operations.values.forEach { walk(it, declaredConstructors, firstShape) }
        }
    }

    private data class ShapeRecord(val type: MezzeType, val loc: Loc?)

    private fun walkDecl(
        decl: TypedDecl,
        declaredConstructors: Set<String>,
        firstShape: MutableMap<String, ShapeRecord>
    ) {
        when (decl) {
            is TypedLetDecl -> walk(decl.value, declaredConstructors, firstShape)
            else -> {}
        }
    }

    private fun walk(
        expr: TypedExpr,
        declaredConstructors: Set<String>,
        firstShape: MutableMap<String, ShapeRecord>
    ) {
        when (expr) {
            is TypedVariantConstruct -> {
                if (expr.tag !in declaredConstructors) {
                    val shape = expr.payload?.type ?: MezzeType.RecordType(emptyMap(), null)
                    val existing = firstShape[expr.tag]
                    if (existing == null) {
                        firstShape[expr.tag] = ShapeRecord(shape, expr.loc)
                    } else if (!shapesMatch(existing.type, shape)) {
                        throw TypeCheckError(
                            "Constructor '${expr.tag}' has conflicting shapes: " +
                                "first use has ${describe(existing.type)}, " +
                                "this use has ${describe(shape)}. " +
                                "An anonymous constructor must use the same payload shape across all uses, " +
                                "or declare a `type` to fix the shape.",
                            expr.loc
                        )
                    }
                }
                expr.payload?.let { walk(it, declaredConstructors, firstShape) }
            }
            is TypedLambda -> walk(expr.body, declaredConstructors, firstShape)
            is TypedCall -> {
                walk(expr.function, declaredConstructors, firstShape)
                walk(expr.arg, declaredConstructors, firstShape)
            }
            is TypedRecord -> expr.entries.forEach { entry ->
                when (entry) {
                    is TypedRecordEntry.Field -> walk(entry.value, declaredConstructors, firstShape)
                    is TypedRecordEntry.Spread -> walk(entry.expr, declaredConstructors, firstShape)
                }
            }
            is TypedRecordUpdate -> {
                walk(expr.record, declaredConstructors, firstShape)
                expr.updates.forEach { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> walk(entry.value, declaredConstructors, firstShape)
                        is TypedRecordEntry.Spread -> walk(entry.expr, declaredConstructors, firstShape)
                    }
                }
            }
            is TypedFieldAccess -> walk(expr.record, declaredConstructors, firstShape)
            is TypedIf -> {
                walk(expr.condition, declaredConstructors, firstShape)
                walk(expr.thenBranch, declaredConstructors, firstShape)
                walk(expr.elseBranch, declaredConstructors, firstShape)
            }
            is TypedMatch -> {
                walk(expr.scrutinee, declaredConstructors, firstShape)
                expr.cases.forEach { case ->
                    case.guard?.let { walk(it, declaredConstructors, firstShape) }
                    case.ifLetBindings.forEach { walk(it.expr, declaredConstructors, firstShape) }
                    walk(case.body, declaredConstructors, firstShape)
                }
            }
            is TypedSeq -> expr.elements.forEach { el ->
                when (el) {
                    is TypedSeqElement.Regular -> walk(el.expr, declaredConstructors, firstShape)
                    is TypedSeqElement.Spread -> walk(el.expr, declaredConstructors, firstShape)
                }
            }
            is TypedBlock -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is TypedStatement.Let -> walk(stmt.value, declaredConstructors, firstShape)
                        is TypedStatement.Expr -> walk(stmt.expr, declaredConstructors, firstShape)
                    }
                }
                walk(expr.result, declaredConstructors, firstShape)
            }
            is TypedPerform -> {
                expr.handlers.forEach { it.params?.let { p -> walk(p, declaredConstructors, firstShape) } }
                walk(expr.body, declaredConstructors, firstShape)
            }
            is TypedPerformOp -> walk(expr.arg, declaredConstructors, firstShape)
            is TypedPipe -> {
                walk(expr.left, declaredConstructors, firstShape)
                walk(expr.right, declaredConstructors, firstShape)
            }
            is TypedStringLit -> expr.parts.forEach { part ->
                if (part is TypedStringPart.Interpolation) walk(part.expr, declaredConstructors, firstShape)
            }
            else -> { /* leaf nodes — TypedVar, literals, etc. */ }
        }
    }

    /**
     * Two payloads are shape-consistent if they unify. This handles:
     *  - same concrete shape: `Red {id: Int}` vs `Red {id: Int}` → ok
     *  - polymorphic instantiations: `Ok {val: 'a}` vs `Ok {val: Int}` → ok
     *  - structural mismatch: `Red {id}` vs `Red {isLight}` → reject (no unifier)
     *  - type mismatch: `Red {id: Int}` vs `Red {id: Str}` → reject (no unifier)
     *
     * The bindings produced are discarded — we only need yes/no.
     */
    private fun shapesMatch(a: MezzeType, b: MezzeType): Boolean =
        unify(a, b) is UnificationResult.Success

    private fun describe(t: MezzeType): String = when (t) {
        is MezzeType.RecordType ->
            if (t.fields.isEmpty()) "no payload"
            else "{${t.fields.keys.sorted().joinToString(", ")}}"
        else -> t.toString()
    }
}
