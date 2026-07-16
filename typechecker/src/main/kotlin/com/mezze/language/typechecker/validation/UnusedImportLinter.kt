package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.WarningCode

/**
 * #84 linter: [WarningCode.W0102_UNUSED_IMPORT] — an imported name that is never
 * referenced anywhere in the module. Computed purely from the parsed AST.
 *
 * Deliberately PERMISSIVE about what counts as "referenced": it collects every
 * name-like token from expressions AND type annotations AND effect/ability
 * clauses. A false negative (missing an unused import) is fine; a false positive
 * (flagging an import that IS used, e.g. one used only in a type signature) is
 * not, so when in doubt we treat a name as referenced.
 *
 * `pub use` re-exports are never flagged — the whole point of a re-export is to
 * expose a name this module doesn't itself use.
 */
object UnusedImportLinter {

    data class Finding(val message: String, val loc: Loc, val code: WarningCode)

    fun lint(module: ParsedModule): List<Finding> {
        val referenced = HashSet<String>()
        for (decl in module.decls) collectReferenced(decl, referenced)

        val out = mutableListOf<Finding>()
        for (decl in module.decls) {
            if (decl !is ParsedUseDecl) continue // ParsedPubUseDecl is a deliberate re-export
            val unused = unusedItems(decl, referenced)
            if (unused.isEmpty()) continue
            val label = if (unused.size == 1) "'${unused.single()}'" else unused.joinToString(", ") { "'$it'" }
            out.add(Finding(
                message = "Unused import: $label is never referenced in this module. " +
                    "Remove it from the `use` clause.",
                loc = decl.loc,
                code = WarningCode.W0102_UNUSED_IMPORT,
            ))
        }
        return out
    }

    /**
     * The local names a `use` decl brings into scope that are NOT referenced.
     *
     * Only SELECTIVE imports (`use x::{a, b}`) are checked — a selective import
     * names specific things the author means to use directly, so an unreferenced
     * item is a real signal. A whole-module import (`use std::int`) is left
     * alone: it commonly exists to bring ability *impls* / instances into scope
     * (e.g. `use std::abilities::sized`, the `_prelude` barrel), which are used
     * implicitly by resolution and never appear by name — flagging those would
     * be a false positive.
     */
    private fun unusedItems(decl: ParsedUseDecl, referenced: Set<String>): List<String> =
        when (val path = decl.path) {
            is ParsedUsePath.Module -> emptyList()
            is ParsedUsePath.Selective -> path.items.mapNotNull { item ->
                val local = item.alias ?: item.name
                if (local in referenced) null else local
            }
        }

    // ── reference collection ─────────────────────────────────────────────

    private fun collectReferenced(decl: ParsedDecl, into: MutableSet<String>) {
        when (decl) {
            is ParsedLetDecl -> {
                decl.typeAnnotation?.let { collectTypeRefs(it, into) }
                collectExprRefs(decl.value, into)
            }
            is ParsedTopLevelExpr -> collectExprRefs(decl.expression, into)
            is ParsedTypeDecl -> collectTypeDefRefs(decl.definition, into)
            is ParsedAbilityDecl -> for (op in decl.operations) {
                collectTypeRefs(op.paramType, into); collectTypeRefs(op.returnType, into)
                op.effects?.let { into.addAll(it.allEffectNames) }
                collectAbilityConstraintRefs(op.abilityConstraints, into)
            }
            is ParsedEffectDecl -> for (op in decl.operations) {
                collectTypeRefs(op.paramType, into); collectTypeRefs(op.returnType, into)
                into.addAll(op.effects)
            }
            is ParsedPerformerDecl -> {
                // The effect this performer implements may itself be an import.
                decl.effect.qualifier?.segments?.firstOrNull()?.let { into.add(it) }
                into.add(decl.effect.localName)
                for (op in decl.operations) collectExprRefs(op.implementation, into)
            }
            is ParsedImplAbilityDecl -> {
                // `impl Format for Str UrlParamFmt where { ... }` references the
                // ability name, the target types, and everything in the bodies —
                // all of which may be selective imports.
                decl.abilityRef?.let {
                    into.add(it.localName)
                    it.qualifier?.segments?.firstOrNull()?.let { q -> into.add(q) }
                }
                for (t in decl.targetTypes) collectTypeRefs(t, into)
                for (t in decl.abilityTypeArgs) collectTypeRefs(t, into)
                for (op in decl.operations) {
                    op.paramType?.let { collectTypeRefs(it, into) }
                    op.returnType?.let { collectTypeRefs(it, into) }
                    collectAbilityConstraintRefs(op.abilityConstraints, into)
                    collectExprRefs(op.implementation, into)
                }
            }
            else -> {}
        }
    }

    private fun collectAbilityConstraintRefs(
        constraints: List<ParsedFnAbilityConstraint>, into: MutableSet<String>
    ) {
        for (ac in constraints) {
            into.add(ac.abilityName)
            for (a in ac.typeArgs) collectTypeRefs(a, into)
        }
    }

    /** Type-annotation refs reachable from a `type`/`newtype` definition. */
    private fun collectTypeDefRefs(def: ParsedTypeDef, into: MutableSet<String>) {
        when (def) {
            is ParsedRecordDef -> for (f in def.fields) collectTypeRefs(f.type, into)
            is ParsedVariantDef -> for (c in def.constructors) c.dataType?.let { collectTypeRefs(it, into) }
            is ParsedNewtypeDef -> collectTypeRefs(def.wrappedType, into)
        }
    }

    private fun collectExprRefs(expr: ParsedExpr, into: MutableSet<String>) {
        when (expr) {
            is ParsedIdentifier -> {
                into.add(expr.localName)
                expr.qualifier?.segments?.firstOrNull()?.let { into.add(it) }
            }
            is ParsedVariantConstruct -> {
                into.add(expr.constructor)
                expr.value?.let { collectExprRefs(it, into) }
            }
            is ParsedLambda -> {
                (expr.params as? ParsedLambdaParams.Typed)?.fields?.forEach { collectTypeRefs(it.type, into) }
                expr.returnType?.let { collectTypeRefs(it, into) }
                collectAbilityConstraintRefs(expr.abilityConstraints, into)
                collectExprRefs(expr.body, into)
            }
            is ParsedFunctionCall -> { collectExprRefs(expr.function, into); collectExprRefs(expr.arguments, into) }
            is ParsedBinaryOp -> { collectExprRefs(expr.left, into); collectExprRefs(expr.right, into) }
            is ParsedUnaryOp -> collectExprRefs(expr.operand, into)
            is ParsedPipe -> { collectExprRefs(expr.left, into); collectExprRefs(expr.right, into) }
            is ParsedParens -> collectExprRefs(expr.expr, into)
            is ParsedMemberAccess -> collectExprRefs(expr.receiver, into)
            is ParsedRecordLit -> for (e in expr.entries) when (e) {
                is ParsedRecordEntry.Field -> e.value?.let { collectExprRefs(it, into) }
                is ParsedRecordEntry.Spread -> collectExprRefs(e.expr, into)
            }
            is ParsedSeqLit -> for (e in expr.elements) when (e) {
                is ParsedSeqElement.Regular -> collectExprRefs(e.expr, into)
                is ParsedSeqElement.Spread -> collectExprRefs(e.expr, into)
            }
            is ParsedIf -> { collectExprRefs(expr.condition, into); collectExprRefs(expr.thenBranch, into); collectExprRefs(expr.elseBranch, into) }
            is ParsedMatch -> {
                collectExprRefs(expr.scrutinee, into)
                for (case in expr.cases) {
                    case.guard?.let { collectExprRefs(it, into) }
                    collectExprRefs(case.body, into)
                    for (b in case.ifLetBindings) collectExprRefs(b.expr, into)
                }
            }
            is ParsedDo -> {
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Let -> {
                        stmt.typeAnnotation?.let { collectTypeRefs(it, into) }
                        collectExprRefs(stmt.value, into)
                    }
                    is ParsedStatement.Expr -> collectExprRefs(stmt.expr, into)
                    is ParsedStatement.Bind -> collectExprRefs(stmt.expr, into)
                }
                collectExprRefs(expr.result, into)
            }
            is ParsedPerform -> {
                for (h in expr.handlers) {
                    into.add(h.performer.localName)
                    h.performer.qualifier?.segments?.firstOrNull()?.let { into.add(it) }
                    h.params?.let { collectExprRefs(it, into) }
                }
                collectExprRefs(expr.body, into)
            }
            is ParsedEffectCall -> {
                into.add(expr.operation.localName)
                expr.operation.qualifier?.segments?.firstOrNull()?.let { into.add(it) }
                collectExprRefs(expr.arguments, into)
            }
            is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
            is ParsedStringLit, is ParsedCharLit, is ParsedPipePlaceholder -> {}
        }
    }

    private fun collectTypeRefs(t: ParsedTypeAnnotation, into: MutableSet<String>) {
        when (t) {
            is ParsedNamedType -> into.add(t.name)
            is ParsedQualifiedType -> {
                t.modulePath.firstOrNull()?.let { into.add(it) }
                into.add(t.typeName)
                for (a in t.args) collectTypeRefs(a, into)
            }
            is ParsedTypeApp -> { into.add(t.constructor); for (a in t.args) collectTypeRefs(a, into) }
            is ParsedRecordType -> for (e in t.entries) collectTypeRefs(e.type, into)
            is ParsedVariantType -> for (c in t.constructors) c.dataType?.let { collectTypeRefs(it, into) }
            is ParsedSeqType -> collectTypeRefs(t.elementType, into)
            is ParsedFunctionType -> {
                collectTypeRefs(t.paramType, into)
                collectTypeRefs(t.returnType, into)
                t.effects?.let { into.addAll(it.allEffectNames) }
                for (ac in t.abilityConstraints) {
                    into.add(ac.abilityName)
                    for (a in ac.typeArgs) collectTypeRefs(a, into)
                }
            }
        }
    }
}
