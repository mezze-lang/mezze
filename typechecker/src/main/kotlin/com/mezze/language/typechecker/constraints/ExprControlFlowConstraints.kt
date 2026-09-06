package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Reject a non-linear pattern — one that binds the same variable name more than
 * once (`A { x = n, y = n }`, `[n, n]`). Without this, the two occurrences are
 * given DIFFERENT types with no unification between them (record-field binding is
 * last-wins), so `n` is typed at one field's type while the runtime may bind the
 * other — silent type confusion. (Or-pattern alternatives are a separate, allowed
 * case: they bind the SAME name across mutually-exclusive arms, and are unified
 * elsewhere. Here we only look WITHIN one alternative pattern.)
 */
private fun rejectDuplicateBinders(pattern: ParsedPattern, loc: Loc) {
    val names = pattern.extractBindings()
    val seen = HashSet<String>()
    val dupes = LinkedHashSet<String>()
    for (n in names) if (!seen.add(n)) dupes.add(n)
    if (dupes.isNotEmpty()) {
        throw TypeCheckError(
            "Pattern binds the same variable more than once: ${dupes.joinToString(", ")}. " +
                "Each binding in a pattern must use a distinct name (use `_` to ignore a value).",
            loc,
            code = ErrorCode.V0606_NON_LINEAR_PATTERN,
            hints = listOf("rename one of the duplicate bindings, or use `_` for the one you don't need"),
        )
    }
}

internal fun ExpressionConstraintGenerator.generateMatch(expr: ParsedMatch, context: ConstraintContext): ConstraintResult {

    val scrutineeResult = generate(expr.scrutinee, context.notInTailPosition())

    val collector = ConstraintCollector()
    collector.merge(scrutineeResult)
    // BUG D (#332): record the scrutinee's inference-time type keyed by its Loc,
    // so exhaustiveness (which runs on the parsed AST) can resolve it via the
    // final substitution for ANY scrutinee expression — a local `let` binding,
    // a field access, a call result — not only a bare function parameter.
    collector.matchScrutineeTypes[expr.scrutinee.loc] = scrutineeResult.type
    val unusedVars = mutableListOf<ConstraintResult.UnusedVariable>()
    val patternWarnings = mutableListOf<String>()
    // #195 mono-fix #1: per-match-case binding types
    val matchCaseBindings = mutableMapOf<Loc, Map<String, MezzeType>>()
    matchCaseBindings.putAll(scrutineeResult.matchCaseBindings)

    val hasWildcard = expr.cases.any { matchCase ->
        matchCase.patterns.any { pattern ->
            pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern
        }
    }
    if (hasWildcard) {
        collector.constraints.add(
            Constraint.MustBeOpen(
                type = scrutineeResult.type,
                bindingName = context.currentBindingName,
                context = "Match has wildcard pattern - scrutinee may have additional constructors",
                loc = expr.loc,
            )
        )
    }

    val patternGenerator = PatternConstraintGenerator()
    val caseResultTypes = mutableListOf<MezzeType>()

    for (matchCase in expr.cases) {

        val firstPattern = matchCase.patterns.first()
        rejectDuplicateBinders(firstPattern, expr.loc)
        val patternResult = patternGenerator.generate(firstPattern, scrutineeResult.type, context, expr.loc)
        collector.constraints.addAll(patternResult.constraints)
        patternWarnings.addAll(patternResult.warnings)

        val firstPatternBindings = patternResult.bindings.keys
        for (pattern in matchCase.patterns.drop(1)) {
            rejectDuplicateBinders(pattern, expr.loc)
            val orPatternResult = patternGenerator.generate(pattern, scrutineeResult.type, context, expr.loc)
            collector.constraints.addAll(orPatternResult.constraints)
            patternWarnings.addAll(orPatternResult.warnings)

            val orPatternBindings = orPatternResult.bindings.keys
            if (orPatternBindings != firstPatternBindings) {
                val missingInOr = firstPatternBindings - orPatternBindings
                val extraInOr = orPatternBindings - firstPatternBindings
                val errorParts = mutableListOf<String>()
                if (missingInOr.isNotEmpty()) {
                    errorParts.add("missing bindings: ${missingInOr.joinToString()}")
                }
                if (extraInOr.isNotEmpty()) {
                    errorParts.add("extra bindings: ${extraInOr.joinToString()}")
                }
                throw TypeCheckError(
                    "Or-pattern alternatives must bind the same variables. ${errorParts.joinToString("; ")}",
                    expr.loc,
                    code = ErrorCode.V0605_OR_PATTERN_BINDS_DIFFERENT_VARS,
                    hints = listOf("all alternatives in an or-pattern must bind the same variables"),
                )
            }

            for ((varName, varType) in orPatternResult.bindings) {
                val firstType = patternResult.bindings[varName]!!
                collector.constraints.add(
                    Constraint.Equality(
                        left = varType,
                        right = firstType,
                        context = "Or-pattern binding '$varName' must have same type in all alternatives",
                        loc = expr.loc,
                    )
                )
            }
        }

        // #392: a receiver bound by a match-arm pattern must dispatch its
        // inherent/ability methods like a `let`- or param-bound one. The do-block
        // `let` path runs a local incremental solve (solveDoBlockBindingLocally +
        // bindLet, below) so the binding's registered type is already CONCRETE when
        // getReceiverConcreteType reads it. A match-arm binding is extended with the
        // raw fresh pattern VarType (unification-critical — see below), so its
        // solved type was unavailable at method-resolve time and the call fell
        // through to a structural field access ("record fields don't match").
        //
        // We do NOT pin the extended binding to its solved type: the binding var is
        // load-bearing for cross-arm unification (e.g. a Nat payload that must stay
        // flexible against an `Int` literal in a sibling arm), and freezing it early
        // regresses that. Instead we run the local solve purely to record each
        // binding's SOLVED type in a side table (matchBindingConcreteTypes) that
        // ONLY getReceiverConcreteType consults for dispatch — never unification. A
        // binding whose type still solves to a VarType is left out (unresolved), the
        // same "still-var ⇒ unresolved" contract getReceiverConcreteType relies on.
        val armSolve = solveDoBlockBindingLocally(collector.constraints, context)
        var caseContext = context
        val armConcreteBindings = mutableMapOf<String, MezzeType>()
        for ((varName, varType) in patternResult.bindings) {
            val resolved = armSolve?.solveResult?.substitution?.apply(varType)
            // Bind the name to its SOLVED type when the local arm solve pinned it
            // to a concrete shape, not the raw fresh HasField var. This matters
            // when the pattern binds an effectful FUNCTION field (e.g. matching a
            // `{ run: {} -> Int <Log> }` value as `{ run }`): if `run` stays an
            // unsolved var in the arm context, checking the CALL `run {}` cannot
            // see the callee's `<Log>` row at constraint-gen time, so the call
            // looks pure and the declared `<Log>` later unifies against an empty
            // performed row — a spurious "actual performs only <>" reject. The
            // equivalent `let run = t.run; run {}` works precisely because its
            // binding already carries the resolved effectful type. (The #392
            // side-channel below stays for method-dispatch specialization.)
            val boundType = if (resolved != null && resolved !is MezzeType.VarType) resolved else varType
            caseContext = caseContext.extend(varName, boundType)
            if (resolved != null && resolved !is MezzeType.VarType) {
                armConcreteBindings[varName] = resolved
            }
        }
        if (armConcreteBindings.isNotEmpty()) {
            caseContext = caseContext.withMatchBindingConcreteTypes(armConcreteBindings)
        }
        // #195 mono-fix #1: stash this case's bindings keyed by the case
        // Loc so TypedAstBuilder can recover the solver's TyVars (not
        // disconnected placeholders) when emitting PatternBinding + the
        // guard/body identifier context.
        matchCaseBindings[matchCase.loc] = patternResult.bindings

        val guard = matchCase.guard
        var guardUsedVariables: Set<String> = emptySet()
        if (guard != null) {
            val guardResult = generate(guard, caseContext.notInTailPosition())
            collector.merge(guardResult)
            guardUsedVariables = guardResult.usedVariables
            // #218: guard's methodCallResolutions and callReturnTypes were
            // being dropped on the floor — every `w.eq` inside a match guard
            // never reached TypedAstBuilder, which fell back to a plain
            // TypedFieldAccess emission with no `it` injection. The mono
            // path then crashed because the impl body's `it` slot stayed
            // null. Same propagation pattern as the case-body's path below.
            collector.methodCallResolutions.putAll(guardResult.methodCallResolutions)
            collector.callReturnTypes.putAll(guardResult.callReturnTypes)

            collector.constraints.add(
                Constraint.Equality(
                    left = guardResult.type,
                    right = MezzeType.VariantType(
                        constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
                        rowVar = null
                    ),
                    context = "Match guard must be Bool",
                    loc = guard.loc,
                )
            )
        }

        for (ifLetBinding in matchCase.ifLetBindings) {
            val bindingExprResult = generate(ifLetBinding.expr, caseContext.notInTailPosition())
            collector.merge(bindingExprResult)

            val ifLetPatternResult = patternGenerator.generate(
                ifLetBinding.pattern,
                bindingExprResult.type,
                caseContext,
                ifLetBinding.loc
            )
            collector.constraints.addAll(ifLetPatternResult.constraints)
            patternWarnings.addAll(ifLetPatternResult.warnings)

            for ((varName, varType) in ifLetPatternResult.bindings) {
                caseContext = caseContext.extend(varName, varType)
            }
        }

        val caseBodyResult = generate(matchCase.body, caseContext)
        collector.merge(caseBodyResult)
        matchCaseBindings.putAll(caseBodyResult.matchCaseBindings)

        val usedVarsInBody = caseBodyResult.usedVariables + guardUsedVariables
        for ((varName, _) in patternResult.bindings) {
            if (!varName.startsWith("_") && varName !in usedVarsInBody) {
                val patternLocation = patternResult.bindingLocations[varName]
                    ?: matchCase.body.loc
                    ?: expr.loc
                unusedVars.add(
                    ConstraintResult.UnusedVariable(
                        name = varName,
                        loc = patternLocation
                    )
                )
            }
        }

        caseResultTypes.add(caseBodyResult.type)
    }

    val resultVar = MezzeType.VarType(TypeVar.fresh())

    for (caseType in caseResultTypes) {
        collector.constraints.add(
            Constraint.Equality(
                left = caseType,
                right = resultVar,
                context = "Match case must have same type as other cases",
                isMatchCaseContext = true,
                loc = expr.loc,
            )
        )
    }

    // Effect soundness: when every arm's value is a concrete FUNCTION type,
    // surface a merged function type (UNIONing the arms' effect rows) as the
    // match result — not the bare `resultVar` VarType. Otherwise, CALLING the
    // match result (`(match … { True -> effectfulFn … }) {}`) sees an unresolved
    // VarType callee, so the call handler's `is FunctionType` effect-harvest is
    // skipped and the selected function's effects are LAUNDERED (a pure-typed
    // caller could perform them untracked). `if`/`else` desugars to `match`, so
    // this covers both. The Equality constraints above still unify every arm
    // with the result, so this only refines the reported type; it never loosens
    // it. Mirrors the concrete-receiver member-access fix in generateMemberAccess.
    val resultType = mergeArmFunctionEffects(caseResultTypes) ?: resultVar

    // Add this match's own unused case bindings to the collector's set (which
    // already holds any unused vars propagated up from nested sub-expressions).
    collector.unusedVars.addAll(unusedVars)
    return collector.toResult(resultType).copy(
        patternWarnings = patternWarnings,
        matchCaseBindings = matchCaseBindings,
    )
}

/**
 * If every arm's value type is a concrete [MezzeType.FunctionType] and at least
 * one carries an effect row, return a single function type whose effect row is
 * the UNION of all arms' effects (so calling the branch result performs any
 * effect any arm's function could). Returns null when the arms aren't all
 * concrete functions (fall back to the fresh result VarType) or none has
 * effects (nothing to preserve — keep inference unconstrained).
 */
private fun mergeArmFunctionEffects(armTypes: List<MezzeType>): MezzeType.FunctionType? {
    if (armTypes.isEmpty()) return null
    val fns = armTypes.map { it as? MezzeType.FunctionType ?: return null }

    val effects = mutableSetOf<String>()
    val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
    var performed = false
    var anyRowVar: RowVar? = null
    for (fn in fns) {
        val row = fn.effects ?: continue
        effects.addAll(row.effects)
        effectInstances.addAll(row.effectInstances)
        performed = performed || row.performed
        if (row.rowVar != null) anyRowVar = row.rowVar
    }
    if (effects.isEmpty() && effectInstances.isEmpty() && !performed && anyRowVar == null) return null

    val mergedRow = MezzeType.EffectRow(
        effects = effects,
        effectInstances = effectInstances,
        rowVar = anyRowVar,
        performed = performed,
    )
    // Use the first arm as the structural template (param/return); the Equality
    // constraints guarantee all arms unify, so any is representative.
    return fns.first().copy(effects = mergedRow)
}

/**
 * S1.2/S1.3 local incremental solve. Runs the solver over the constraints
 * accumulated up to a given do-block statement — mirroring how the top-level runs
 * a per-binding solve (ConstraintGenerator ~:186) — so a do-block `let` sees a
 * concrete binding type (e.g. `let kept = xs.filter{…}` → `[…]`) instead of a raw
 * VarType, and so its solved substitution + result can be handed to the shared
 * bindLet primitive (S1.3). Returns null when no registry is wired (bare/test
 * construction) or solving fails — the global solve surfaces the real error later;
 * without a solve the do-block let path falls back to monomorphic bindings
 * (behaviour unchanged for that binding). Also returns the solver so bindLet can
 * query ability-constraint discharge.
 */
private class DoBlockLocalSolve(
    val solveResult: ConstraintSolver.SolveResult.Success,
    val solver: ConstraintSolver,
)

/** True if [lambda] captures a term variable from the enclosing scope whose type
 *  carries FREE TYPE VARS — i.e. it references a name (in [usedVariables]) that is
 *  not one of its own parameters, is bound in [outerContext], and whose (possibly
 *  polymorphic-scheme-unquantified) type is non-ground. Such a capture can force
 *  the lambda's type equal to an enclosing MONO var that the do-block's local
 *  solve gives a fresh identity to, so generalizing over it would be unsound. A
 *  capture of a fully-ground binding (`p : Int`) is safe and still generalizes. */
private fun doBlockLambdaCapturesFreeVarBinding(
    lambda: ParsedLambda,
    usedVariables: Set<String>,
    outerContext: ConstraintContext,
): Boolean {
    val paramNames = when (val params = lambda.params) {
        is ParsedLambdaParams.Untyped -> params.names.toSet()
        is ParsedLambdaParams.Typed -> params.fields.map { it.name }.toSet()
    }
    return usedVariables.any { name ->
        if (name in paramNames) return@any false
        when (val lr = outerContext.typingContext.lookup(BindingKey.unqualified(name))) {
            is TypingContext.LookupResult.Found -> when (val b = lr.binding) {
                is TypingContext.Binding.Monomorphic -> collectFreeTypeVars(b.type).isNotEmpty()
                is TypingContext.Binding.Polymorphic ->
                    (collectFreeTypeVars(b.scheme.type) - b.scheme.quantifiedVars.toSet()).isNotEmpty()
            }
            else -> false
        }
    }
}

private fun ExpressionConstraintGenerator.solveDoBlockBindingLocally(
    accumulatedConstraints: List<Constraint>,
    outerContext: ConstraintContext,
): DoBlockLocalSolve? {
    val reg = this.registry ?: return null
    val solver = ConstraintSolver(outerContext.typingContext, reg)
    return when (val r = solver.solve(accumulatedConstraints.toList())) {
        is ConstraintSolver.SolveResult.Success -> DoBlockLocalSolve(r, solver)
        is ConstraintSolver.SolveResult.Failure -> null
    }
}

internal fun ExpressionConstraintGenerator.generateDo(expr: ParsedDo, context: ConstraintContext): ConstraintResult {

    var currentContext = context.clearExpectedType().notInTailPosition()
    val collector = ConstraintCollector()

    val patternGenerator = PatternConstraintGenerator()

    // #84 linter: name + location of every do-block `let` binding, checked for
    // use against the collector's accumulated used-set after all statements are
    // processed (a name may be referenced by a *later* statement, so the check
    // can only run once the whole block has been generated).
    val letBindings = mutableListOf<Pair<String, Loc?>>()

    // #86 case-A shadowing: names bound by THIS do-block's own `let`s. Kept
    // local (not in the context) so a NESTED do-block starts fresh — reusing an
    // outer block's let name across the block boundary is legal (case B).
    val blockLets = mutableSetOf<String>()

    for (stmt in expr.statements) {
        when (stmt) {
            is ParsedStatement.Let -> {
                // Context BEFORE this let extends the scope — the context the RHS is
                // type-checked in, and the one against which the RHS's solvability is
                // judged (below, for solvableLetBindings).
                val preLetContext = currentContext

                val annotatedType = stmt.typeAnnotation?.let { annotation ->
                    convertParsedTypeAnnotation(annotation)
                }

                val isRecursiveLetCandidate = stmt.value is ParsedLambda &&
                                               stmt.pattern is ParsedVariablePattern
                val preBindType: MezzeType? = if (isRecursiveLetCandidate) {
                    annotatedType ?: MezzeType.VarType(TypeVar.fresh())
                } else null

                val preBindContext = if (preBindType != null) {
                    val varName = (stmt.pattern as ParsedVariablePattern).name
                    val preBinding = mapOf(
                        BindingKey.unqualified(varName) to TypingContext.Binding.Monomorphic(preBindType)
                    )
                    currentContext.copy(
                        typingContext = currentContext.typingContext.extendAll(preBinding)
                    )
                } else {
                    currentContext
                }

                // Set enclosingFunctionName for local self-recursive lambdas
                // so that tail calls within the lambda body are detected.
                // This mirrors what ConstraintGenerator.generateLetBinding
                // does for top-level let bindings.
                val localFnName = if (isRecursiveLetCandidate)
                    (stmt.pattern as ParsedVariablePattern).name else null

                val exprContext = if (annotatedType != null) {
                    preBindContext.withExpectedType(annotatedType)
                } else {
                    preBindContext
                }.copy(enclosingFunctionName = localFnName)
                val exprResult = generate(stmt.value, exprContext)

                if (preBindType != null && annotatedType == null) {
                    collector.constraints.add(
                        Constraint.Equality(
                            left = preBindType,
                            right = exprResult.type,
                            context = "Recursive let binding in do block",
                            loc = stmt.value.loc
                        )
                    )
                }

                collector.merge(exprResult)

                val bindingType = exprResult.type

                // Record this intermediate binding's surfaced effects so an
                // enclosing `perform` can pin the error channel of a value carrying
                // the discharged effect (e.g. a `.ok {}`-wrapped result) to the
                // handler's discharged associated type — post-solve, never a
                // coercion. See Constraint.DefaultAssociatedType.
                val bindingEffectNames = exprResult.effects +
                    exprResult.effectInstances.map { it.name }.toSet()
                if (bindingEffectNames.isNotEmpty()) {
                    collector.doBindingEffects.add(
                        ConstraintResult.DoBindingEffect(bindingType, bindingEffectNames)
                    )
                }

                if (annotatedType != null) {
                    collector.constraints.add(
                        Constraint.Equality(
                            left = bindingType,
                            right = annotatedType,
                            context = "Type annotation on let binding in do block",
                            loc = stmt.value.loc
                        )
                    )
                }

                val variableNames = stmt.pattern.extractBindings()
                for (varName in variableNames) {
                    nestedBindingTypes[varName] = bindingType
                }

                // #86 case-A shadowing: a `let` that reuses a name already bound
                // in THIS function scope — the enclosing lambda's param, or an
                // earlier `let` in this same do-block — silently overwrites it.
                // Reject it (T0306), the same class of mistake as a duplicate
                // lambda param (#79). `_`-prefixed names opt out (a throwaway
                // may legitimately repeat). Shadowing an outer param/global name
                // is in neither set, so it stays legal (case B); a nested
                // do-block's own lets are tracked in a fresh `blockLets`.
                for (varName in variableNames) {
                    if (varName.startsWith("_")) continue
                    if (varName in currentContext.currentScopeBindings || varName in blockLets) {
                        throw TypeCheckError(
                            message = "'$varName' is already bound in this scope. This `let` " +
                                "silently overwrites the earlier binding (a parameter or an " +
                                "earlier `let` in the same block). Rename one of them.",
                            loc = stmt.pattern.loc,
                            errorType = ErrorType.TYPE_MISMATCH,
                            code = ErrorCode.T0306_SHADOWED_BINDING_IN_SCOPE,
                        )
                    }
                }
                blockLets.addAll(variableNames)

                val patternResult = patternGenerator.generate(
                    stmt.pattern,
                    bindingType,
                    currentContext,
                    stmt.value.loc
                )
                collector.constraints.addAll(patternResult.constraints)

                val typingContext = currentContext.typingContext

                // S1.3: route the do-block `let` through the SAME bindLet primitive
                // the top-level path uses. We run a LOCAL incremental solve over the
                // constraints accumulated up to (and including) this statement — as
                // the top-level runs a per-binding solve — then hand its solved
                // substitution + result to bindLet, which owns the entire binding
                // construction: annotated-over-structural type selection (with alias
                // adoption), let-annotation validation, ability-constraint carry,
                // effect harvest into the arrow, and generalization / value
                // restriction. This subsumes the three bespoke do-block branches that
                // used to live here (explicit ability-constrained function annotation,
                // #76 un-annotated-lambda generalization, and the S1.2 monomorphic
                // fallback). bindLet's generalization is the SAME rule #76 used
                // (quantify solved-type vars free in the binding but not the outer
                // context), so do-block let-polymorphism is preserved; a captured
                // outer type var is free in `currentContext` and so is never
                // quantified, which is the sound version of the old capture guard.
                //
                // Falls back to monomorphic bindings when no registry is wired
                // (bare/test construction), the local solve fails (the global solve
                // surfaces the real error later), or bindLet is not injected — so
                // behaviour is unchanged on those paths.
                // A do-block lambda that captures an outer binding whose type has
                // FREE TYPE VARS is left monomorphic (suppressGeneralization). The
                // danger is a capture that forces the lambda's type equal to an
                // ENCLOSING MONO var (`k : 'a` → `let r: 'a = k`): the do-block's
                // LOCAL solve gives the binding a fresh var identity, so bindLet's
                // `ftv(type) \ ftv(env)` cannot see that it aliases the outer `'a`
                // (different var instances across the local-solve boundary) and
                // would wrongly quantify it — using it at Int then Str would pass
                // unsoundly (PolyAuditProbeTest constraint-capture cases). BUT a
                // capture of a GROUND outer binding (`p : Int`, no free vars) poses
                // no such risk — its param var is genuinely independent — so it must
                // still generalize (#15). Hence: suppress ONLY when a captured
                // binding's type is non-ground. The old flag suppressed on ANY
                // capture, monomorphizing a genuinely-polymorphic param.
                val capturesFreeVarBinding = stmt.pattern is ParsedVariablePattern &&
                    stmt.value is ParsedLambda &&
                    doBlockLambdaCapturesFreeVarBinding(
                        stmt.value as ParsedLambda, exprResult.usedVariables, currentContext)

                // Include the accumulated ABILITY + ASSOCIATED-TYPE constraints in
                // the local solve, mirroring the top-level per-binding solve
                // (ConstraintGenerator ~:192, which solves
                // `result.constraints + result.abilityConstraints +
                // result.associatedTypeConstraints + patternResult.constraints`).
                // Without them the local solve never sees e.g. a `NumAdd Int Int`
                // obligation, so bindLet's discharge oracle can't tell it is
                // satisfied, and a do-block `let f = { n: Int } -> n * 2 + 1`
                // leaks `with NumAdd Int 'b having 'out = 'a` + a free result var
                // onto the enclosing fn — where the identical top-level binding
                // correctly infers `{ n: Int } -> Int`. (merge() keeps ability /
                // associated-type constraints in separate lists from `constraints`,
                // which is why they were omitted here.)
                val localSolveConstraints = collector.constraints +
                    collector.abilityConstraints +
                    collector.associatedTypeConstraints
                val localSolve = solveDoBlockBindingLocally(localSolveConstraints, currentContext)
                val letResult = if (localSolve != null) {
                    bindLetFn(
                        ExpressionConstraintGenerator.BindLetArgs(
                            item = ParsedLetDecl(
                                pattern = stmt.pattern,
                                typeAnnotation = stmt.typeAnnotation,
                                value = stmt.value,
                                loc = stmt.loc,
                            ),
                            result = exprResult,
                            patternResult = patternResult,
                            preConvertedAnnotationType = annotatedType,
                            solveResult = localSolve.solveResult,
                            substitution = localSolve.solveResult.substitution,
                            currentContext = currentContext.typingContext,
                            outerContext = currentContext.typingContext,
                            solver = localSolve.solver,
                            suppressGeneralization = capturesFreeVarBinding,
                            // The do-block sequences a statement's effects into its
                            // own effect row (collector.merge above); the binding
                            // holds the pure value, so bindLet must NOT harvest the
                            // effect onto it (that would leak the effect into later
                            // closures capturing this name). See bindLet step (e).
                            harvestEffectsIntoBinding = false,
                        )
                    )
                } else null

                // #396 (do-block let convergence): record the SOLVED value type
                // this binding got from bindLet — the SAME authoritative type the
                // top-level path threads into `results[name]` — keyed by the RHS
                // expression's Loc. Emit's buildDoBlock reads THIS instead of
                // re-deriving from the erased RHS node type. For a simple variable
                // binding there is exactly one solved binding; a destructure pattern
                // binds several names and has no single RHS value type to stamp on
                // the (synthetic-temp) binding, so it is left to the existing path.
                // The map holds the PURE value type (bindLet ran with
                // harvestEffectsIntoBinding = false); emit's #378 effect-row rider
                // carries the effect provenance separately, so the value facet
                // and the effect facet stay owned by exactly one place each.
                if (letResult != null && stmt.pattern is ParsedVariablePattern) {
                    val varName = (stmt.pattern as ParsedVariablePattern).name
                    letResult.solvedBindingsWithEffects[varName]?.let { solvedValueType ->
                        collector.doBindingSolvedTypes[stmt.value.loc] = solvedValueType
                    }
                }

                val contextBindings = letResult?.contextBindings
                    ?: patternResult.bindings.map { (name, type) ->
                        val rawBound = if (stmt.pattern is ParsedVariablePattern) {
                            annotatedType ?: type
                        } else type
                        BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type = rawBound)
                    }.toMap()

                val extendedTypingContext = typingContext.extendAll(contextBindings)
                currentContext = currentContext.copy(typingContext = extendedTypingContext)

                if (stmt.pattern is ParsedVariablePattern && stmt.value is ParsedRecordLit) {
                    val varName = (stmt.pattern as ParsedVariablePattern).name
                    currentContext = currentContext.withRecordLiteralBinding(varName)
                }

                // Mark a `let x = <rhs>` (bare variable binding) whose RHS is a
                // solve-pinned value (a polymorphic call result, a parenthesized
                // such value, …) so a later `x.to_str {}` may defer its inherent-
                // method impl pick — exactly as the inline `(rhs).to_str {}` does.
                // The RHS predicate is receiverHasSolvableType applied to the value
                // itself; a destructure pattern (`let { n } = r`) is NOT a bare
                // variable and a `let x = p` aliasing a free param is not solvable,
                // so neither is marked and both correctly refine their receiver.
                // See ConstraintContext.solvableLetBindings.
                if (stmt.pattern is ParsedVariablePattern &&
                    receiverHasSolvableType(stmt.value, preLetContext)
                ) {
                    val varName = (stmt.pattern as ParsedVariablePattern).name
                    currentContext = currentContext.withSolvableLetBinding(varName)
                }

                // #84 linter: remember this let's bound names to check for use
                // once the whole block is generated. Skip `_`-prefixed names
                // (intentionally unused) here so they never warn.
                for (varName in variableNames) {
                    if (!varName.startsWith("_")) {
                        letBindings.add(varName to (patternResult.bindingLocations[varName] ?: stmt.loc))
                    }
                }
            }
            is ParsedStatement.Expr -> {
                val exprResult = generate(stmt.expr, currentContext)
                collector.merge(exprResult)
            }
        }
    }

    val resultContext = if (context.expectedType != null) {
        currentContext.withExpectedType(context.expectedType).copy(inTailPosition = context.inTailPosition)
    } else {
        currentContext.copy(inTailPosition = context.inTailPosition)
    }
    val resultResult = generate(expr.result, resultContext)
    collector.merge(resultResult)

    // Effect-model migration (S2): the do-block's result type is BARE. The block's
    // effects are already sequenced into the enclosing arrow via `collector`
    // (returned on this ConstraintResult's `effects`/`performed`), so they need not —
    // and must not — be re-stamped onto the result VALUE type.
    val resultType = resultResult.type

    // #84 linter: a do-block `let` whose name is never referenced by any later
    // statement, the block result, or its own value is unused. `collector.usedVars`
    // has accumulated every referenced name across the whole block by now.
    for ((varName, loc) in letBindings) {
        if (varName !in collector.usedVars) {
            collector.unusedVars.add(ConstraintResult.UnusedVariable(name = varName, loc = loc))
        }
    }

    return collector.toResult(resultType)
}

