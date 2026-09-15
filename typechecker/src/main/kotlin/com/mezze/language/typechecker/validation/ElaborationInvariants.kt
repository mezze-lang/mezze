package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*

/**
 * Task 0.2 — measure how often the compiler reaches the end of elaboration
 * without actually knowing a type.
 *
 * The elaboration plan's premise is that types are RECONSTRUCTED rather than
 * carried: when the builder cannot find a node's solved type it mints a fresh
 * variable and something downstream guesses the rest from shape or name. Those
 * guesses are what produce silently-wrong dispatch, so the first job is to see
 * them. This pass walks a finished [TypedModule] and reports every place a type
 * stayed open where it had to be closed.
 *
 * It is a RATCHET, not a pass/fail rule. Today's count is checked in as a
 * baseline; the gate fails if a change introduces a new finding, and fails
 * equally if the baseline is left stale after one is removed — so the number can
 * only go down, and reaching zero is what A.2 turns into a hard error.
 *
 * Boundness is deliberately OVER-approximated: a type variable counts as bound
 * if it appears in ANY enclosing binder — the declaration's scheme, a lambda's
 * parameter or own type, a pattern binding, an impl's forall vars. A genuinely
 * polymorphic body is full of legitimately open variables, and a ratchet that
 * cries wolf is a ratchet people switch off. Under-reporting is the safe error
 * here: everything this pass does report is a variable with no binder anywhere
 * above it, which no amount of polymorphism explains.
 */
object ElaborationInvariants {

    enum class Kind {
        /** A node's type carries a variable bound by no enclosing binder. */
        UNBOUND_TYPE_VAR,

        /** A parameter typed as a bare variable with no binder — the shape an
         *  impl body gets when its params were never given the ability
         *  signature's types. */
        UNTYPED_PARAM,

        /** A field read off a receiver whose type is still a variable. This is
         *  the shape that reaches the runtime as a dispatch with nothing to
         *  dispatch on. */
        VARTYPE_RECEIVER,
    }

    data class Finding(
        val kind: Kind,
        /** Declaration the finding sits in — an impl operation reads as
         *  `impl <target>.<op>`. */
        val where: String,
        val detail: String,
        val loc: Loc?,
    ) {
        /**
         * The baseline key: KIND and DECLARATION only.
         *
         * Everything churn-prone is deliberately left out. Source locations move
         * whenever the file above them is edited. Type-variable names are minted
         * fresh by the solver (`'b49`, `'h553`), so they differ run to run and
         * would rewrite the whole baseline on an unrelated change. Even the count
         * per declaration churns as inference shifts.
         *
         * One line per (kind, declaration) is also the actionable unit: you fix a
         * declaration, not an individual variable, and the line disappears when
         * its last gap is closed. A declaration going from three gaps to two is
         * real progress that this key does not show — which is the cost of a
         * baseline people will actually read.
         */
        fun key(): String = "$kind\t$where"
    }

    fun check(module: TypedModule): List<Finding> {
        val findings = mutableListOf<Finding>()

        for (decl in module.decls) {
            if (decl !is TypedLetDecl) continue
            val bound = HashSet<String>()
            decl.scheme.typeParams.forEach { bound.add(it.name) }
            decl.typeParams.forEach { bound.add(it.name) }
            collectVars(decl.scheme.type, bound)
            walk(decl.value, "let:${decl.name}", bound, findings)
        }

        for (impl in module.abilityImpls) {
            val implBound = HashSet<String>()
            impl.forallVars.forEach { implBound.add(it.name) }
            impl.targetTypes.forEach { collectVars(it, implBound) }
            impl.associatedTypeBindings.values.forEach { collectVars(it, implBound) }
            // NOT abilityHash: it is Hash.ZERO on these records, so every impl
            // in the stdlib would collapse onto one label. The impl's own hash
            // plus its target reads as a place a person can go and look at.
            val target = impl.targetTypes.firstOrNull()
                ?.let { com.mezze.language.typechecker.util.receiverTypeName(it) }
                ?: impl.hash.hex.take(8)
            val label = "impl $target"
            for ((opName, body) in impl.operations) {
                walk(body, "$label.$opName", HashSet(implBound), findings)
            }
        }

        return findings
    }

    // ── walking ──────────────────────────────────────────────────────────────

    /**
     * [bound] is carried BY VALUE into nested scopes that introduce binders, so a
     * lambda's parameter variables do not leak out to its siblings. Within one
     * scope it is mutated in place: a `let` earlier in a block legitimately binds
     * for the statements after it.
     */
    private fun walk(expr: TypedExpr, where: String, bound: MutableSet<String>, out: MutableList<Finding>) {
        if (expr is TypedLambda) {
            // A lambda's own signature BINDS for its body, and binds for itself.
            // Reporting `expr.type` against the outer scope would flag every
            // generic function in the stdlib: a declaration's scheme is
            // normalized (`'a`, `'b`) while its body carries the solver's own
            // names (`'m802`), so the two never match by name even when they
            // describe the same variable. What is left after this is a variable
            // that appears in no signature at all — which is the gap we are after.
            val inner = HashSet(bound)
            for (param in expr.params) collectVars(param.type, inner)
            collectVars(expr.type, inner)
            expr.captures.forEach { collectVars(it.type, inner) }

            reportUnbound(expr.type, expr.loc, where, Kind.UNBOUND_TYPE_VAR, inner, out)
            for (param in expr.params) {
                if (param.type is MezzeType.VarType) {
                    out.add(Finding(
                        Kind.UNTYPED_PARAM, where,
                        "param '${param.name}' has no type beyond a fresh variable",
                        param.loc,
                    ))
                }
            }
            walk(expr.body, where, inner, out)
            return
        }

        reportUnbound(expr.type, expr.loc, where, Kind.UNBOUND_TYPE_VAR, bound, out)

        when (expr) {

            is TypedFieldAccess -> {
                if (expr.record.type is MezzeType.VarType &&
                    !bound.contains(varName(expr.record.type))
                ) {
                    out.add(Finding(
                        Kind.VARTYPE_RECEIVER, where,
                        "field '${expr.field}' read off a receiver with no known type",
                        expr.loc,
                    ))
                }
                walk(expr.record, where, bound, out)
            }

            is TypedMatch -> {
                walk(expr.scrutinee, where, bound, out)
                for (case in expr.cases) {
                    val inner = HashSet(bound)
                    case.bindings.forEach { collectVars(it.type, inner) }
                    case.ifLetBindings.forEach { walk(it.expr, where, inner, out) }
                    case.guard?.let { walk(it, where, inner, out) }
                    walk(case.body, where, inner, out)
                }
            }

            is TypedBlock -> {
                // One scope: each `let` binds for the statements that follow it.
                val inner = HashSet(bound)
                for (statement in expr.statements) {
                    when (statement) {
                        is TypedStatement.Let -> {
                            walk(statement.value, where, inner, out)
                            collectVars(statement.type, inner)
                        }
                        is TypedStatement.Expr -> walk(statement.expr, where, inner, out)
                    }
                }
                walk(expr.result, where, inner, out)
            }

            is TypedCall -> {
                walk(expr.function, where, bound, out)
                walk(expr.arg, where, bound, out)
            }
            is TypedIf -> {
                walk(expr.condition, where, bound, out)
                walk(expr.thenBranch, where, bound, out)
                walk(expr.elseBranch, where, bound, out)
            }
            is TypedPipe -> {
                walk(expr.left, where, bound, out)
                walk(expr.right, where, bound, out)
            }
            is TypedRecord -> expr.entries.forEach { walkEntry(it, where, bound, out) }
            is TypedRecordUpdate -> {
                walk(expr.record, where, bound, out)
                expr.updates.forEach { walkEntry(it, where, bound, out) }
            }
            is TypedSeq -> for (element in expr.elements) {
                when (element) {
                    is TypedSeqElement.Regular -> walk(element.expr, where, bound, out)
                    is TypedSeqElement.Spread -> walk(element.expr, where, bound, out)
                }
            }
            is TypedVariantConstruct -> expr.payload?.let { walk(it, where, bound, out) }
            is TypedPerform -> {
                expr.handlers.forEach { handler -> handler.params?.let { walk(it, where, bound, out) } }
                walk(expr.body, where, bound, out)
            }
            is TypedPerformOp -> walk(expr.arg, where, bound, out)
            is TypedStringLit -> for (part in expr.parts) {
                if (part is TypedStringPart.Interpolation) walk(part.expr, where, bound, out)
            }
            // Leaves: literals and variable references carry a type and no children.
            else -> Unit
        }
    }

    private fun walkEntry(
        entry: TypedRecordEntry, where: String, bound: MutableSet<String>, out: MutableList<Finding>,
    ) = when (entry) {
        is TypedRecordEntry.Field -> walk(entry.value, where, bound, out)
        is TypedRecordEntry.Spread -> walk(entry.expr, where, bound, out)
    }

    // ── type variables ───────────────────────────────────────────────────────

    private fun varName(type: MezzeType): String =
        (type as MezzeType.VarType).variable.name

    private fun reportUnbound(
        type: MezzeType, loc: Loc?, where: String, kind: Kind,
        bound: Set<String>, out: MutableList<Finding>,
    ) {
        val free = HashSet<String>()
        collectVars(type, free)
        val unbound = free.filterNot { bound.contains(it) }.sorted()
        if (unbound.isEmpty()) return
        out.add(Finding(kind, where, "unbound type ${plural(unbound.size)}: ${unbound.joinToString(", ")}", loc))
    }

    private fun plural(n: Int) = if (n == 1) "variable" else "variables"

    /**
     * Every type variable reachable from [type], by NAME.
     *
     * Delegates to [com.mezze.language.ast.collectTypeVariables] rather than
     * walking the type here: that `when` has no `else`, so the compiler fails the
     * build when a new MezzeType variant is added without a traversal arm. A
     * private copy would instead go quietly incomplete, and a position it missed
     * would read as "no variables here" — a finding silently dropped.
     *
     * Names, not identities: instantiation gives a variable a fresh id, so
     * matching on id would make a decl's own scheme variables look unbound
     * throughout its body. Names over-approximate boundness, which is the safe
     * direction for a ratchet — it can hide a finding, never invent one.
     */
    private fun collectVars(type: MezzeType, into: MutableSet<String>) {
        val vars = HashSet<com.mezze.language.ast.TypeVar>()
        com.mezze.language.ast.collectTypeVariables(type, vars)
        vars.forEach { into.add(it.name) }
    }
}
