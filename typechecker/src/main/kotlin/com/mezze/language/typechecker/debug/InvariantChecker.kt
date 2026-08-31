package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.forEachChild

object InvariantChecker {

    private val enabled: Boolean
        get() = System.getProperty("mezze.invariants", "false") == "true"

    class InvariantViolation(message: String) : RuntimeException(message)

    fun checkSubstitution(substitution: Substitution, phase: String) {
        if (!enabled) return

        for ((typeVar, type) in substitution.mappings) {
            if (type is MezzeType.VarType && type.variable == typeVar) {
                throw InvariantViolation(
                    "[$phase] Substitution self-mapping: $typeVar → $type"
                )
            }

            if (occursCheck(typeVar, type)) {
                throw InvariantViolation(
                    "[$phase] Occurs-check violation: $typeVar appears in ${type.javaClass.simpleName}"
                )
            }
        }
    }

    fun checkSubstitutionIdempotence(substitution: Substitution, phase: String) {
        if (!enabled) return

        for ((_, type) in substitution.mappings) {
            val applied = substitution.apply(type)
            if (applied != type) {
                throw InvariantViolation(
                    "[$phase] Non-idempotent substitution: applying to RHS changed " +
                    "${type.javaClass.simpleName} → ${applied.javaClass.simpleName}"
                )
            }
        }
    }

    fun checkConstraintResultsConsistent(
        constraintResults: Map<String, ConstraintResult>,
        phase: String,
    ) {
        if (!enabled) return

        for ((key, result) in constraintResults) {
            val freeVars = collectFreeTypeVars(result.type)
            for (tv in freeVars) {
                if (tv.name.startsWith(com.mezze.language.ast.MagicParams.INTERNAL_PREFIX)) {
                    throw InvariantViolation(
                        "[$phase] Internal type variable leaked into constraint result for '$key': $tv"
                    )
                }
            }
        }
    }

    /**
     * S0.2 probe (warn-only, measurement phase). Reports emitted constraint-result
     * types that still contain a bare VarType, so we can distinguish LEGITIMATE
     * polymorphism (`let id = {x} -> x` : `'a -> 'a`, a fine generalized type) from
     * a genuinely UNRESOLVED receiver that will mis-dispatch downstream (the
     * "defaults to Bytes" class). This does NOT throw — enabling it as a hard
     * invariant requires first characterising which VarTypes are expected. Gated
     * behind `mezze.invariants.probe=true` so it never affects the normal gate.
     */
    /**
     * S4.2 (#362) — EFFECT-CONSERVATION invariant.
     *
     * "A decl's emitted function-type effect set ⊇ the set of effects actually
     * PERFORMED in its body." The emitted/declared effect row must NOT be
     * narrower than what the body performs — a narrower row is exactly the
     * effect-laundering hole (#331/#215/#256). This is additive: it only reads
     * the emitted TypedModule and throws under `mezze.invariants=true`; it never
     * changes typechecking.
     *
     * The ⊇ rule, precisely:
     *  - PERFORMED effects = the effect names of every [TypedPerformOp] reachable
     *    in the decl's function body WITHOUT crossing a nested lambda boundary
     *    (a nested lambda's performs belong to ITS row, surfacing at the outer
     *    decl only through the call's type) AND that are NOT discharged by an
     *    enclosing [TypedPerform] handler in the same body. Handler discharge is
     *    what makes "performed" mean performed-AND-NOT-discharged: an effect
     *    caught by an ancestor handler is absorbed and is sound to omit from the
     *    outward row (project_effect_absorption_sound: caughtByAncestor is SOUND).
     *  - EMITTED effects = the union of the effect rows on every arrow of the
     *    decl's (curried) function type. An OPEN row (rowVar != null, i.e. a
     *    polymorphic effect tail) is ⊇ any set, so if ANY arrow's row is open the
     *    decl trivially satisfies the invariant — an open row is never a
     *    violation. Only a CONCRETE emitted set missing a CONCRETE performed
     *    effect is a laundering violation.
     *
     * A performed effectHash we cannot resolve to a name (no entry in the
     * module's effectDecls nor in any emitted row's effectHashes) is skipped
     * rather than flagged — the invariant reports only effects it can prove are
     * both performed and absent from a concrete emitted row.
     */
    fun checkEffectConservation(module: TypedModule) {
        if (!enabled) return

        // hash → effect name, from the module's own effect decls plus every
        // effectHashes map carried on emitted rows (covers imported effects).
        val hashToName = HashMap<Hash, String>()
        for (ed in module.effectDecls) hashToName[ed.hash] = ed.name
        collectRowHashNames(module, hashToName)

        for (decl in module.decls) {
            if (decl !is TypedLetDecl) continue
            val lambda = decl.value as? TypedLambda ?: continue

            // Union of every arrow's row down the curried chain. If any arrow is
            // open (polymorphic effect tail), the decl is ⊇ anything → satisfied.
            val emittedNames = mutableSetOf<String>()
            var anyOpen = false
            var innermostBody: TypedExpr = lambda.body
            var cursor: TypedExpr? = lambda
            while (cursor is TypedLambda) {
                val fnType = cursor.type as? MezzeType.FunctionType
                val row = fnType?.effects
                if (row != null) {
                    if (row.isOpen) anyOpen = true
                    emittedNames += row.allEffectNames
                }
                innermostBody = cursor.body
                cursor = cursor.body as? TypedLambda
            }
            if (anyOpen) continue

            val performed = collectPerformedNotDischarged(innermostBody, hashToName)
            val missing = performed - emittedNames
            if (missing.isNotEmpty()) {
                throw InvariantViolation(
                    "[effect-conservation] Decl '${decl.name}' launders effect(s) " +
                    "${missing.sorted()}: its body performs ${performed.sorted()} but the " +
                    "emitted function-type effect row only promises " +
                    "${emittedNames.sorted().ifEmpty { listOf("(pure)") }}. Emitted row must be ⊇ " +
                    "body-performed (open rows are ⊇ anything; handler-discharged effects are excluded)."
                )
            }
        }
    }

    /** Fold every emitted EffectRow's effectHashes (name→hash) into [into] as hash→name. */
    private fun collectRowHashNames(module: TypedModule, into: MutableMap<Hash, String>) {
        fun scanType(t: MezzeType?) {
            when (t) {
                is MezzeType.FunctionType -> {
                    t.effects?.effectHashes?.forEach { (name, h) -> into.putIfAbsent(h, name) }
                    scanType(t.paramType)
                    scanType(t.returnType)
                }
                is MezzeType.SuspendType -> {
                    t.effects.effectHashes.forEach { (name, h) -> into.putIfAbsent(h, name) }
                    scanType(t.valueType)
                }
                else -> { /* only function/suspend types carry effect rows */ }
            }
        }
        for (decl in module.decls) {
            if (decl is TypedLetDecl) scanType(decl.scheme.type)
        }
    }

    /**
     * Effect names performed in [body] and not discharged by an enclosing
     * [TypedPerform] handler. Does NOT descend into nested lambdas (their
     * performs are their own row). [discharged] carries the effectHashes handled
     * by ancestors along the current path.
     */
    private fun collectPerformedNotDischarged(
        body: TypedExpr,
        hashToName: Map<Hash, String>,
    ): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(expr: TypedExpr, discharged: Set<Hash>) {
            when (expr) {
                is TypedLambda -> { /* nested lambda: its performs belong to ITS row */ }
                is TypedPerform -> {
                    // Handlers discharge their effectHash within this perform's body.
                    val nowDischarged = discharged + expr.handlers.map { it.effectHash }
                    // Handler param bodies are evaluated in the OUTER scope.
                    for (h in expr.handlers) h.params?.let { walk(it, discharged) }
                    walk(expr.body, nowDischarged)
                }
                is TypedPerformOp -> {
                    if (expr.effectHash !in discharged) {
                        hashToName[expr.effectHash]?.let { out.add(it) }
                    }
                    expr.forEachChild { walk(it, discharged) }
                }
                else -> expr.forEachChild { walk(it, discharged) }
            }
        }
        walk(body, emptySet())
        return out
    }

    fun probeUnresolvedVars(
        constraintResults: Map<String, ConstraintResult>,
        phase: String,
    ) {
        if (System.getProperty("mezze.invariants.probe", "false") != "true") return
        for ((key, result) in constraintResults) {
            val vars = collectFreeTypeVars(result.type)
            if (vars.isNotEmpty()) {
                System.err.println(
                    "[invariant-probe $phase] '$key' emitted type has ${vars.size} free var(s): " +
                    vars.joinToString(", ") { it.name } + "  ::  ${result.type}"
                )
            }
        }
    }
}
