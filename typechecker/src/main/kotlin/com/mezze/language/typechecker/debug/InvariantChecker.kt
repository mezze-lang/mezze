package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.constraints.Constraint
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
                    "${type.javaClass.simpleName} → ${applied.javaClass.simpleName}. " +
                    "A stored RHS still mentions a var the same substitution rewrites; " +
                    "the post-solve boundary must saturate() the composed substitution " +
                    "so apply(apply(t)) == apply(t) (S0.4/#373)."
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

    /**
     * S0.2 (#351) — NO-UNRESOLVED-DISPATCH-VAR invariant. Promotes the warn-only
     * [probeUnresolvedVars] into a hard gate, with the ONE precise distinction the
     * post-solve single-pass dispatch (S2.1–S2.3) finally makes expressible.
     *
     * WHAT IS AND ISN'T A DISPATCH POSITION. The dispatch positions this invariant
     * guards are the INHERENT-METHOD resolutions the solver DEFERRED because their
     * receiver was only known after solving ([Constraint.InherentMethodResolve],
     * #160) — a call like `x.abs {}` whose winning impl varies by the receiver's
     * concrete type (Int→Int, Float→Float, …). These carry the REAL solver receiver
     * type. This invariant deliberately does NOT read the raw S2.1
     * `MethodResolveBucket`s: those record a best-effort SYNTACTIC receiver that
     * falls back to a FRESH placeholder tyvar when the receiver isn't syntactically
     * knowable (e.g. `(n % 2) == 0` — the numeric-op result), and that placeholder
     * is never part of the constraint solution, so it would false-positive every
     * ability/builtin op with a computed receiver. Ability/builtin ops with a
     * still-var receiver are LEGITIMATE deferrals (the DispatchResolver itself
     * records no Resolution for them and lets the numeric/annotation default pin
     * them) — never a violation.
     *
     * THE RULE, over the deferred inherent resolutions:
     *  - Resolve each receiver like emit does: the flat [substitution], then the
     *    per-call-site [callReturnTypes] (which can pin a forward-ref / effect-
     *    context receiver the flat subst alone misses).
     *  - Concrete → dispatched; skip.
     *  - Still a [MezzeType.VarType] whose id IS a generalized param of some emitted
     *    decl scheme ([generalizedVarIds]) → legitimate polymorphism: the decl is
     *    genuinely `'a`-polymorphic and dispatch resolves at each instantiation.
     *    OK — skip. This is the `id`/`pair`/`add` case; flagging it would
     *    false-positive every polymorphic decl.
     *  - Still a [MezzeType.VarType] whose id is NOT such a param → an UNRESOLVED
     *    dispatch/receiver var with no polymorphic justification: the "defaults to
     *    Bytes" mis-dispatch class. VIOLATION.
     *
     * The distinction is drawn purely from data the solve already decided: the
     * deferred inherent resolutions ([unresolvedInherentMethods]) are the dispatch
     * positions; the legitimately-generalized vars are exactly the vars free in the
     * emitted decl scheme types ([generalizedVarIds], keyed on stable
     * [com.mezze.language.ast.TypeVar.id]).
     *
     * This is the compiler self-check counterpart of
     * `undispatchableInherentMethodErrors` (which reports the same stuck receivers
     * to the USER as T0206): here it asserts, under the invariant gate, that no
     * such orphan receiver var silently reaches emit.
     *
     * Additive: reads the frozen deferred-resolution list + substitution +
     * callReturnTypes + emitted-scheme free vars; changes no typechecking. Gated by
     * `mezze.invariants=true` (the real gate flag, NOT the probe flag). On violation
     * throws [InvariantViolation] naming the method, the unresolved receiver var,
     * and its [Loc].
     */
    fun checkNoUnresolvedDispatchVar(
        unresolvedInherentMethods: List<Constraint.InherentMethodResolve>,
        substitution: Substitution,
        callReturnTypes: Map<Loc, MezzeType>,
        generalizedVarIds: Set<Int>,
    ) {
        if (!enabled) return
        for (c in unresolvedInherentMethods) {
            // Resolve the receiver exactly like emit (and the T0206 check) does:
            // flat substitution first, then the per-call-site callReturnTypes.
            val fromSubst = substitution.apply(c.receiverType)
            val fromCallSite = c.receiverLoc?.let { callReturnTypes[it] }?.let { substitution.apply(it) }
            val resolved = when {
                fromSubst !is MezzeType.VarType -> fromSubst
                fromCallSite != null && fromCallSite !is MezzeType.VarType -> fromCallSite
                else -> fromSubst
            }
            if (resolved !is MezzeType.VarType) continue // concrete → dispatched
            // A free var that is a generalized param of some emitted decl scheme is
            // legitimate polymorphism (dispatch resolves at each instantiation).
            if (resolved.variable.id in generalizedVarIds) continue
            // Otherwise: an inherent dispatch position whose receiver never got
            // pinned AND isn't a quantified param anywhere — an orphan leak, the
            // "defaults to Bytes" mis-dispatch class.
            throw InvariantViolation(
                "[no-unresolved-dispatch-var] Inherent dispatch of '${c.methodName}' at " +
                "${c.loc} has an UNRESOLVED receiver var '${resolved.variable.name}' " +
                "(id=${resolved.variable.id}): after the final substitution the receiver " +
                "is still a type variable that is NOT a generalized param of any emitted decl's " +
                "scheme. An inherent dispatch position must resolve to a concrete receiver or to " +
                "a legitimately-quantified polymorphic param; an orphan receiver var is the " +
                "'defaults to Bytes' mis-dispatch class."
            )
        }
    }
}
