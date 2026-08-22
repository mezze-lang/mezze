package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.printing.ErrorMessageBuilder
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.receiverTypeName

class ConstraintSolver(
    private val context: TypingContext,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
    /**
     * Option B (#341/#370). When true, {@link solveEquality} refuses to
     * silently strip a {@link MezzeType.SuspendType} wrapper on either
     * side of an equality when the wrapped effect row is non-empty.
     *
     * <p>The default keeps the pre-Option-B behavior (silent strip) so
     * existing callers are unaffected while we ratchet toward strictness.
     * BuildCommand/RunCommand can flip this to true incrementally as
     * stdlib + tests are cleaned up (Phase 2 of the Option B plan).
     *
     * <p>Env override: MEZZE_STRICT_EFFECT_ROWS=1 forces true, =0 forces
     * false. Used by the test harness to run the whole suite under
     * strict mode and enumerate the failure surface.
     */
    private val strictEffectRows: Boolean =
        System.getenv("MEZZE_STRICT_EFFECT_ROWS") == "1",
    /**
     * Impl-body validation mode. When an operator body (`+`, `<`, …) is solved
     * in isolation to resolve its associated-type `'out` before the return-type
     * check, an [Constraint.AbilityRequired] whose impl EXISTS somewhere but is
     * not import-visible at this site must NOT fail — that obligation is properly
     * checked at the use-site/top-level solve. A genuinely-ABSENT impl still
     * fails, which is what lets the `'out` var stay unresolved and the return
     * check reject (soundness). Only [ImplValidator] sets this.
     */
    private val deferExistingAbilityImpls: Boolean = false
) {
    private fun constraintPriority(c: Constraint): Int = when (c) {
        is Constraint.Equality -> 0
        is Constraint.HasField -> 1
        is Constraint.HasConstructor -> 2
        is Constraint.AbilityRequired,
        is Constraint.AssociatedTypeResolution,
        is Constraint.Subsumes,
        is Constraint.MustBeOpen,
        is Constraint.RecordPatternExhaustive -> 3
        // #160: dispatch by SOLVED receiver type, so run after Equality/HasField
        // have had a chance to pin the receiver tyvar. Deferred until it is
        // concrete (see solveInherentMethodResolve).
        is Constraint.InherentMethodResolve -> 4
    }

    // The "subject" type vars a constraint's failure is really about — the
    // receiver whose wrong type triggers the error — read from the RAW
    // (pre-substitution) constraint so the var survives resolution. A HasField
    // failure is about the *record* var, not the fresh var invented for the
    // field's type; keying on the subject lets repeated failures on the same
    // wrongly-resolved receiver (n.foo, n.bar, ...) collapse to one root cause,
    // while incidental result vars (which differ per access) don't defeat it.
    private fun constraintOriginVars(constraint: Constraint): Set<TypeVar> {
        val vars = LinkedHashSet<TypeVar>()
        fun addVarsOf(type: MezzeType) = vars.addAll(collectFreeTypeVars(type))
        when (constraint) {
            // The record/variant being probed is the subject; the field/payload
            // type is a fresh var per access and must NOT gate cascade detection.
            is Constraint.HasField -> addVarsOf(constraint.recordType)
            is Constraint.HasConstructor -> addVarsOf(constraint.variantType)
            is Constraint.MustBeOpen -> addVarsOf(constraint.type)
            is Constraint.RecordPatternExhaustive -> addVarsOf(constraint.recordType)
            // Both sides of an equality/subsumption are equally the subject.
            is Constraint.Equality -> { addVarsOf(constraint.left); addVarsOf(constraint.right) }
            is Constraint.Subsumes -> { addVarsOf(constraint.general); addVarsOf(constraint.specific) }
            is Constraint.AbilityRequired -> vars.add(constraint.typeVar)
            is Constraint.AssociatedTypeResolution -> {
                vars.add(constraint.primaryTypeVar)
                vars.addAll(constraint.associatedTypeVars.values)
            }
            // #160: the receiver is the subject whose wrong/absent type triggers
            // "no <method> for type <T>"; result/args vars are per-call incidentals.
            is Constraint.InherentMethodResolve -> addVarsOf(constraint.receiverType)
        }
        return vars
    }

    // A failed constraint whose every origin var is already poisoned reports the
    // same underlying error as the failure that poisoned those vars, so it is a
    // cascade we suppress. A failure that mentions at least one non-poisoned var
    // is (potentially) a fresh, independent error and is kept. Pass the ORIGINAL
    // (pre-substitution) constraint so origin vars survive substitution.
    private fun isCascade(constraint: Constraint): Boolean {
        val vars = constraintOriginVars(constraint)
        return vars.isNotEmpty() && vars.all { it in poisonedVars }
    }

    // Record a just-reported failure's origin vars as poisoned so any downstream
    // constraint depending solely on them is recognised as a cascade. Pass the
    // ORIGINAL (pre-substitution) constraint.
    private fun poison(constraint: Constraint) {
        poisonedVars.addAll(constraintOriginVars(constraint))
    }

    private val abilityChecker = AbilityChecker(context, registry, deferExistingAbilityImpls)

    internal fun getGeneratedCodecImpls(): Collection<AbilityChecker.GeneratedCodecImpl> = abilityChecker.getGeneratedCodecImpls()

    private val mustBeOpenTypes = mutableSetOf<TypeVar>()
    private val mustBeOpenRowVars = mutableSetOf<RowVar>()
    private val mustBeOpenBindings = mutableSetOf<String>()
    private val patternBasedRowVars = mutableSetOf<RowVar>()

    // #61 error-recovery: type vars left unresolved by an already-reported
    // failure. A later constraint that fails but depends only on poisoned vars
    // is a cascade of the same root cause, so we suppress its (duplicate) error
    // instead of drowning the real one. This is the "ErrorType sentinel" idea
    // realised as a poisoned-TypeVar set — no new MezzeType variant, so it costs
    // nothing in the exhaustive `when (type)` blocks across the codebase.
    private val poisonedVars = mutableSetOf<TypeVar>()

    // #160: InherentMethodResolve constraints whose receiver hadn't resolved by
    // the end of the main worklist. They may still resolve during later phases
    // (deferred ability inference), so we re-check them after the substitution is
    // complete (see the tail of solve()). Cleared per solve().
    private val deferredInherentLeftovers = mutableListOf<Constraint.InherentMethodResolve>()

    private val variantSolver = VariantSolver(registry, patternBasedRowVars, mustBeOpenRowVars)

    fun getMustBeOpenRowVars(): Set<RowVar> = mustBeOpenRowVars.toSet()

    sealed class SolveResult {
        data class Success(
            val substitution: Substitution,
            val warnings: List<String> = emptyList(),
            val mustBeOpenTypes: Set<TypeVar> = emptySet(),
            val mustBeOpenRowVars: Set<RowVar> = emptySet(),
            val mustBeOpenBindings: Set<String> = emptySet(),
            val patternBasedRowVars: Set<RowVar> = emptySet(),
            // #160: InherentMethodResolve constraints whose receiver never became
            // concrete in THIS solve. The global solve's substitution can lack the
            // per-binding resolution that emit later composes in, so the receiver
            // may still resolve. TypeChecker re-checks these against the COMPOSED
            // (global ∘ per-binding) substitution — only then is a still-tyvar
            // receiver genuinely undispatchable.
            val unresolvedInherentMethods: List<Constraint.InherentMethodResolve> = emptyList(),
            // Ability associated-type bindings resolved this solve, keyed by
            // (abilityName, primary type-var id) → (assocName → resolved type).
            // Populates the inferred function's FunctionAbilityConstraint so
            // ability `having` shows + unifies, mirroring effect associated types.
            val resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>> = emptyMap()
        ) : SolveResult()

        data class Failure(
            val errors: List<ConstraintError>
        ) : SolveResult()
    }

    data class ConstraintError(
        val message: String,
        val constraint: Constraint,
        val context: String? = null,
        val code: ErrorCode = ErrorCode.UNCATEGORIZED,
        val hints: List<String> = emptyList(),
    )

    fun solve(rawConstraints: List<Constraint>): SolveResult {
        // Pre-solve simplification: drop reflexive equalities and exact
        // duplicates so the worklist below has fewer constraints to iterate.
        // Provably meaning-preserving — same solution set (see ConstraintSimplifier).
        val constraints = ConstraintSimplifier.simplify(rawConstraints)

        // #61: poison is scoped to a single solve() so a failure in one binding
        // can't suppress an unrelated failure in the next (the solver instance is
        // reused across per-binding solves).
        poisonedVars.clear()
        deferredInherentLeftovers.clear()
        var substitution = Substitution.empty
        val errors = mutableListOf<ConstraintError>()
        val warnings = mutableListOf<String>()

        val (inferredEqualities, inferenceErrors) = variantSolver.inferVariantTypes(constraints)
        collectInferenceErrors(inferenceErrors, constraints, errors)

        val allConstraints = constraints + inferredEqualities
        val (mustBeOpenConstraints, constraintsAfterOpen) = allConstraints.partition { it is Constraint.MustBeOpen }
        for (constraint in mustBeOpenConstraints) {
            solveConstraint(constraint, substitution)
        }

        val (abilityConstraints, nonAbilityConstraints) = constraintsAfterOpen.partition {
            it is Constraint.AbilityRequired || it is Constraint.AssociatedTypeResolution
        }

        substitution = solveAbilityConstraints(abilityConstraints, substitution, warnings, errors)

        val hcResult = solveHasConstructorFixpoint(nonAbilityConstraints, substitution, warnings, errors)
        substitution = hcResult.substitution
        val remainingHasConstructors = hcResult.remaining
        val otherConstraints = hcResult.other

        resolveMustBeOpenRowVars(substitution)
        DebugLogger.debugSection(Category.ConstraintSolver, "Row Variables") {
            listOf(
                "patternBasedRowVars = $patternBasedRowVars",
                "mustBeOpenRowVars = $mustBeOpenRowVars"
            )
        }
        substitution = variantSolver.closePatternBasedVariants(substitution)

        substitution = solveMainWorklist(remainingHasConstructors + otherConstraints, substitution, warnings, errors)
        substitution = finalizeDeferredAbilities(substitution, errors)
        // #160: now the substitution is complete (incl. deferred ability
        // inference that pins e.g. `help`'s `'a` to Int). Re-check the inherent
        // methods that couldn't dispatch earlier — most now resolve; only a
        // receiver that STILL isn't concrete is genuinely undispatchable.
        substitution = resolveDeferredInherentMethods(substitution, errors)
        resolveMustBeOpenRowVars(substitution)

        return if (errors.isEmpty()) {
            // For each ability associated-type resolution this solve saw, record
            // what each associated var resolved to under the FINAL substitution —
            // concrete (from a matched impl) or still a var (generic fn, prints as
            // `having 'out = 'a`, like effects). Keyed by (abilityName, primary id).
            val resolvedAssociatedBindings = mutableMapOf<Pair<String, Int>, MutableMap<String, MezzeType>>()
            for (c in rawConstraints) {
                if (c !is Constraint.AssociatedTypeResolution) continue
                val bucket = resolvedAssociatedBindings.getOrPut(c.abilityName to c.primaryTypeVar.id) { mutableMapOf() }
                for ((name, tv) in c.associatedTypeVars) {
                    bucket[name.removePrefix("'")] = substitution.apply(MezzeType.VarType(tv))
                }
            }
            SolveResult.Success(
                substitution = substitution,
                warnings = warnings,
                mustBeOpenTypes = mustBeOpenTypes.toSet(),
                mustBeOpenRowVars = mustBeOpenRowVars.toSet(),
                mustBeOpenBindings = mustBeOpenBindings.toSet(),
                patternBasedRowVars = patternBasedRowVars.toSet(),
                unresolvedInherentMethods = deferredInherentLeftovers.toList(),
                resolvedAssociatedBindings = resolvedAssociatedBindings
            )
        } else {
            SolveResult.Failure(errors)
        }
    }

    private fun collectInferenceErrors(
        inferenceErrors: List<String>,
        constraints: List<Constraint>,
        errors: MutableList<ConstraintError>
    ) {
        if (inferenceErrors.isNotEmpty() && constraints.isNotEmpty()) {
            val contextConstraint = constraints.first()
            for (errorMsg in inferenceErrors) {
                errors.add(ConstraintError(
                    message = errorMsg,
                    constraint = contextConstraint,
                    context = "Variant type inference"
                ))
            }
        }
    }

    private fun solveAbilityConstraints(
        abilityConstraints: List<Constraint>,
        inputSubst: Substitution,
        warnings: MutableList<String>,
        errors: MutableList<ConstraintError>,
    ): Substitution {
        var substitution = inputSubst
        for (constraint in abilityConstraints) {
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
            val result = solveConstraint(appliedConstraint, substitution)

            when (result) {
                is ConstraintResult.Success -> {
                    substitution = result.substitution.compose(substitution)
                    warnings.addAll(result.warnings)
                }
                is ConstraintResult.Failure -> {
                    if (!isCascade(constraint)) {
                        errors.add(ConstraintError(
                            message = result.message,
                            constraint = appliedConstraint,
                            context = appliedConstraint.context
                        ))
                        // Poison so later HasField/worklist constraints on the
                        // same unresolved var don't re-report the same failure.
                        poison(constraint)
                    }
                }
                is ConstraintResult.Deferred -> {}
            }
        }
        return substitution
    }

    private class HasConstructorResult(
        val substitution: Substitution,
        val remaining: List<Constraint.HasConstructor>,
        val other: List<Constraint>
    )

    private fun solveHasConstructorFixpoint(
        nonAbilityConstraints: List<Constraint>,
        inputSubst: Substitution,
        warnings: MutableList<String>,
        errors: MutableList<ConstraintError>
    ): HasConstructorResult {
        val hasConstructorConstraints = nonAbilityConstraints.filterIsInstance<Constraint.HasConstructor>()
        val otherConstraints = nonAbilityConstraints.filter { it !is Constraint.HasConstructor }

        var substitution = inputSubst
        val worklist = hasConstructorConstraints.toMutableList()
        var madeProgress = true
        var iterationCount = 0

        while (madeProgress && worklist.isNotEmpty() && iterationCount < 50) {
            madeProgress = false
            iterationCount++

            val iterator = worklist.iterator()
            while (iterator.hasNext()) {
                val constraint = iterator.next()
                val appliedConstraint = applySubstitutionToConstraint(constraint, substitution) as Constraint.HasConstructor
                val result = variantSolver.solveHasConstructor(appliedConstraint, substitution, constraint)

                when (result) {
                    is ConstraintResult.Success -> {
                        val newSubst = result.substitution
                        if (newSubst.mappings.isNotEmpty() || newSubst.rowVarMappings.isNotEmpty()) {
                            substitution = newSubst.compose(substitution)
                            madeProgress = true
                        }
                        warnings.addAll(result.warnings)
                        iterator.remove()
                    }
                    is ConstraintResult.Failure -> {
                        val constraintType = appliedConstraint.variantType
                        if (constraintType is MezzeType.VarType && !result.terminal) {
                        } else {
                            if (!isCascade(constraint)) {
                                errors.add(ConstraintError(
                                    message = result.message,
                                    constraint = appliedConstraint,
                                    context = appliedConstraint.context
                                ))
                                poison(constraint)
                            }
                            iterator.remove()
                        }
                    }
                    is ConstraintResult.Deferred -> {}
                }
            }
        }

        return HasConstructorResult(substitution, worklist, otherConstraints)
    }

    private fun resolveMustBeOpenRowVars(substitution: Substitution) {
        for (typeVar in mustBeOpenTypes) {
            val resolved = substitution.apply(MezzeType.VarType(typeVar))
            DebugLogger.debug(Category.ConstraintSolver) { "MustBeOpen typeVar=$typeVar resolved to $resolved" }
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
                DebugLogger.debug(Category.ConstraintSolver) { "Added rowVar=${resolved.rowVar} to mustBeOpenRowVars" }
            }
        }
    }

    private fun mergeVariantConstructors(
        originalConstraint: Constraint.Equality,
        appliedEq: Constraint.Equality,
        leftType: MezzeType.VariantType,
        rightType: MezzeType.VariantType,
        substitution: Substitution
    ): Substitution? {
        val leftCtors = leftType.constructors.keys
        val rightCtors = rightType.constructors.keys
        if (leftCtors == rightCtors) return null

        val isMatchContext = originalConstraint.isMatchCaseContext
        val bothOpen = leftType.rowVar != null && rightType.rowVar != null
        val eitherPatternBased = (leftType.rowVar != null && patternBasedRowVars.contains(leftType.rowVar)) ||
                                 (rightType.rowVar != null && patternBasedRowVars.contains(rightType.rowVar))

        if (!isMatchContext && !(bothOpen && !eitherPatternBased)) return null

        val mergedConstructors = leftType.constructors.toMutableMap()
        for ((name, dataType) in rightType.constructors) {
            if (name !in mergedConstructors) {
                mergedConstructors[name] = dataType
            }
        }

        val mergedVariant = MezzeType.VariantType(
            constructors = mergedConstructors,
            rowVar = leftType.rowVar ?: rightType.rowVar,
            typeName = leftType.typeName ?: rightType.typeName,
            typeHash = leftType.typeHash ?: rightType.typeHash
        )

        val updatedMappings = mutableMapOf<TypeVar, MezzeType>()
        if (originalConstraint.left is MezzeType.VarType) {
            updatedMappings[(originalConstraint.left as MezzeType.VarType).variable] = mergedVariant
        }
        if (originalConstraint.right is MezzeType.VarType) {
            updatedMappings[(originalConstraint.right as MezzeType.VarType).variable] = mergedVariant
        }
        for ((typeVar, mappedType) in substitution.mappings) {
            if (mappedType == leftType || mappedType == rightType) {
                updatedMappings[typeVar] = mergedVariant
            }
        }

        return if (updatedMappings.isNotEmpty()) Substitution(substitution.mappings + updatedMappings, substitution.rowVarMappings) else null
    }

    private fun solveMainWorklist(
        remainingConstraints: List<Constraint>,
        inputSubst: Substitution,
        warnings: MutableList<String>,
        errors: MutableList<ConstraintError>
    ): Substitution {
        var substitution = inputSubst
        val sortedConstraints = remainingConstraints.sortedBy { constraintPriority(it) }
        val constraintWorklist = sortedConstraints.toMutableList()
        var madeProgress = true
        var iterationCount = 0
        val maxIterations = 100

        while (madeProgress && constraintWorklist.isNotEmpty() && iterationCount < maxIterations) {
            madeProgress = false
            iterationCount++

            DebugLogger.debug(Category.ConstraintSolver) {
                "Worklist iteration $iterationCount, ${constraintWorklist.size} constraints remaining"
            }

            val iterator = constraintWorklist.iterator()
            while (iterator.hasNext()) {
                val constraint = iterator.next()
                val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)

                val result = when (constraint) {
                    is Constraint.HasConstructor -> variantSolver.solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                    else -> solveConstraint(appliedConstraint, substitution)
                }

                when (result) {
                    is ConstraintResult.Success -> {
                        val newSubst = result.substitution
                        if (newSubst.mappings.isNotEmpty() || newSubst.rowVarMappings.isNotEmpty()) {
                            substitution = newSubst.compose(substitution)
                            madeProgress = true
                        }
                        warnings.addAll(result.warnings)

                        if (constraint is Constraint.Equality) {
                            val appliedEq = appliedConstraint as Constraint.Equality
                            val leftType = substitution.apply(appliedEq.left)
                            val rightType = substitution.apply(appliedEq.right)

                            if (leftType is MezzeType.VariantType && rightType is MezzeType.VariantType) {
                                val merged = mergeVariantConstructors(constraint, appliedEq, leftType, rightType, substitution)
                                if (merged != null) {
                                    substitution = merged
                                    madeProgress = true
                                }
                            }
                        }

                        iterator.remove()
                    }
                    is ConstraintResult.Failure -> {
                        val constraintType = when (appliedConstraint) {
                            is Constraint.HasField -> appliedConstraint.recordType
                            is Constraint.HasConstructor -> appliedConstraint.variantType
                            else -> null
                        }

                        if (constraintType is MezzeType.VarType) {
                        } else {
                            // #61: suppress cascades — only the root failure (one
                            // whose origin vars aren't all poisoned yet) is
                            // reported; reporting a failure poisons its origin
                            // vars so dependents are recognised as follow-on noise.
                            if (!isCascade(constraint)) {
                                errors.add(ConstraintError(
                                    message = result.message,
                                    constraint = appliedConstraint,
                                    context = appliedConstraint.context
                                ))
                                poison(constraint)
                            }
                            iterator.remove()
                        }
                    }
                    is ConstraintResult.Deferred -> {}
                }
            }
        }

        // #160: a chain of deferred InherentMethodResolve constraints (e.g.
        // `x.abs {}.to_str {}` — to_str's receiver is abs's result tyvar) may
        // need SEVERAL passes: to_str only becomes concrete after abs resolves.
        // The main loop above can bail once no *other* constraint makes progress,
        // leaving these Deferred. Re-run the leftovers to a fixpoint, composing
        // each Success so the pinned result/return types reach emit. Success and
        // Failure both remove the constraint (so it's reported at most once);
        // only genuinely-stuck Deferred constraints survive to the sweep below.
        var finalProgress = true
        var finalIterations = 0
        while (finalProgress && constraintWorklist.isNotEmpty() && finalIterations < constraintWorklist.size + 2) {
            finalProgress = false
            finalIterations++
            val it = constraintWorklist.iterator()
            while (it.hasNext()) {
                val constraint = it.next()
                val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
                val result = when (constraint) {
                    is Constraint.HasConstructor -> variantSolver.solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                    else -> solveConstraint(appliedConstraint, substitution)
                }
                when (result) {
                    is ConstraintResult.Success -> {
                        if (result.substitution.mappings.isNotEmpty() || result.substitution.rowVarMappings.isNotEmpty()) {
                            substitution = result.substitution.compose(substitution)
                        }
                        finalProgress = true
                        it.remove()
                    }
                    is ConstraintResult.Failure -> {
                        if (!isCascade(constraint)) {
                            errors.add(ConstraintError(
                                message = result.message,
                                constraint = appliedConstraint,
                                context = appliedConstraint.context
                            ))
                            poison(constraint)
                        }
                        finalProgress = true
                        it.remove()
                    }
                    is ConstraintResult.Deferred -> { /* wait for another pass */ }
                }
            }
        }

        // Terminal sweep: whatever is STILL unresolved is a genuine failure.
        // EXCEPTION: a still-Deferred InherentMethodResolve isn't necessarily a
        // failure yet — its receiver may resolve during LATER phases (deferred
        // ability inference pins e.g. help's `'a` to Int only in
        // finalizeDeferredAbilities, AFTER this worklist). Stash those leftovers
        // so solve() can re-check them once the substitution is complete.
        for (constraint in constraintWorklist) {
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
            val result = when (constraint) {
                is Constraint.HasConstructor -> variantSolver.solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                else -> solveConstraint(appliedConstraint, substitution)
            }
            if (result is ConstraintResult.Failure && !isCascade(constraint)) {
                errors.add(ConstraintError(
                    message = result.message,
                    constraint = appliedConstraint,
                    // Stamp a typed code on ability failures so callers can
                    // discriminate them without matching the free-text message
                    // or context (see ConstraintGenerator's top-level-expr filter).
                    code = if (appliedConstraint is Constraint.AbilityRequired)
                        ErrorCode.V0402_ABILITY_NOT_SATISFIED else ErrorCode.UNCATEGORIZED,
                    context = appliedConstraint.context
                ))
                poison(constraint)
            } else if (result is ConstraintResult.Deferred &&
                       constraint is Constraint.InherentMethodResolve) {
                deferredInherentLeftovers.add(constraint)
            }
        }

        return substitution
    }

    /**
     * #160: final re-check of inherent-method dispatch that couldn't resolve
     * during the main worklist. Runs AFTER deferred ability inference, so this
     * solve's substitution is complete. Each leftover is re-solved once:
     *   - Success → compose (pins the call's result/return type for emit).
     *   - Failure → report (a receiver that solved to a non-candidate type).
     *   - Deferred (receiver STILL a tyvar HERE) → not necessarily an error: the
     *     receiver may resolve once the CALLER composes the per-binding
     *     substitution, so it's left in deferredInherentLeftovers for the
     *     TypeChecker's final undispatchability check against the composed subst.
     * Iterated to a fixpoint so method CHAINS (abs then to_str) settle.
     */
    private fun resolveDeferredInherentMethods(
        inputSubst: Substitution,
        errors: MutableList<ConstraintError>
    ): Substitution {
        if (deferredInherentLeftovers.isEmpty()) return inputSubst
        var substitution = inputSubst
        val worklist = deferredInherentLeftovers.toMutableList()

        var progress = true
        var iterations = 0
        while (progress && worklist.isNotEmpty() && iterations < worklist.size + 2) {
            progress = false
            iterations++
            val it = worklist.iterator()
            while (it.hasNext()) {
                val constraint = it.next()
                val applied = applySubstitutionToConstraint(constraint, substitution) as Constraint.InherentMethodResolve
                when (val result = solveInherentMethodResolve(applied, substitution)) {
                    is ConstraintResult.Success -> {
                        if (result.substitution.mappings.isNotEmpty() || result.substitution.rowVarMappings.isNotEmpty()) {
                            substitution = result.substitution.compose(substitution)
                        }
                        progress = true
                        it.remove()
                    }
                    is ConstraintResult.Failure -> {
                        if (!isCascade(constraint)) {
                            errors.add(ConstraintError(
                                message = result.message,
                                constraint = applied,
                                context = applied.context,
                                code = ErrorCode.T0206_METHOD_NOT_FOUND,
                            ))
                            poison(constraint)
                        }
                        progress = true
                        it.remove()
                    }
                    is ConstraintResult.Deferred -> { /* still unresolved — see below */ }
                }
            }
        }

        // Anything STILL Deferred here has a receiver that didn't become
        // concrete in THIS solve's substitution. Don't error yet: the receiver
        // may resolve once the caller composes the per-binding substitution.
        // Keep them in deferredInherentLeftovers for TypeChecker's final check.
        deferredInherentLeftovers.clear()
        deferredInherentLeftovers.addAll(worklist)
        return substitution
    }

    private fun finalizeDeferredAbilities(
        inputSubst: Substitution,
        errors: MutableList<ConstraintError>
    ): Substitution {
        var substitution = inputSubst

        val validationResult = abilityChecker.validateAbilityConstraints(substitution)
        if (validationResult != null) {
            val (validationError, errorLoc) = validationResult
            errors.add(ConstraintError(
                message = validationError,
                // The deferred re-check has no single AbilityRequired constraint to
                // attach (it validates the accumulated set), so it carries a
                // placeholder Equality. The typed code — NOT the constraint shape or
                // context string — is what callers discriminate on.
                constraint = Constraint.Equality(MezzeType.IntType, MezzeType.IntType, loc = errorLoc),
                code = ErrorCode.V0402_ABILITY_NOT_SATISFIED,
                context = "ability constraint validation"
            ))
        }

        val deferredInference = abilityChecker.getDeferredInference()
        if (deferredInference.isNotEmpty()) {
            val deferredSubst = Substitution(deferredInference.toMap())
            substitution = deferredSubst.compose(substitution)
        }

        val deferredEntries = abilityChecker.getAssociatedTypeResolutionConstraints().toList()
        DebugLogger.debug(Category.ConstraintSolver) {
            "Processing ${deferredEntries.size} deferred associated type constraints"
        }
        for ((typeVar, deferredConstraints) in deferredEntries) {
            DebugLogger.debug(Category.ConstraintSolver) {
                "  TypeVar: $typeVar, ${deferredConstraints.size} constraints"
            }
            for (constraint in deferredConstraints.toList()) {
                val result = abilityChecker.solveAssociatedTypeResolution(constraint, substitution)
                DebugLogger.debug(Category.ConstraintSolver) {
                    "  Result: $result"
                }
                when (result) {
                    is ConstraintResult.Success -> {
                        if (result.substitution.mappings.isNotEmpty()) {
                            DebugLogger.debug(Category.ConstraintSolver) {
                                "  Composing substitution: ${result.substitution.mappings}"
                            }
                            substitution = result.substitution.compose(substitution)
                        }
                    }
                    is ConstraintResult.Failure -> {
                        if (!isCascade(constraint)) {
                            errors.add(ConstraintError(
                                message = result.message,
                                constraint = constraint,
                                context = constraint.context
                            ))
                            poison(constraint)
                        }
                    }
                    is ConstraintResult.Deferred -> {}
                }
            }
        }

        return substitution
    }

    internal sealed class ConstraintResult {
        data class Success(
            val substitution: Substitution,
            val warnings: List<String> = emptyList()
        ) : ConstraintResult()

        data class Failure(
            val message: String,
            val terminal: Boolean = false
        ) : ConstraintResult()

        data class Deferred(val reason: String) : ConstraintResult()
    }

    private fun solveConstraint(constraint: Constraint, currentSubst: Substitution): ConstraintResult {
        return when (constraint) {
            is Constraint.Equality -> solveEquality(constraint)
            is Constraint.AbilityRequired -> abilityChecker.solveAbilityRequired(constraint)
            is Constraint.AssociatedTypeResolution -> abilityChecker.solveAssociatedTypeResolution(constraint, currentSubst)
            is Constraint.Subsumes -> solveSubsumes(constraint)
            is Constraint.HasField -> solveHasField(constraint, currentSubst)
            is Constraint.HasConstructor -> variantSolver.solveHasConstructor(constraint, currentSubst)
            is Constraint.MustBeOpen -> solveMustBeOpen(constraint, currentSubst)
            is Constraint.RecordPatternExhaustive -> solveRecordPatternExhaustive(constraint, currentSubst)
            is Constraint.InherentMethodResolve -> solveInherentMethodResolve(constraint, currentSubst)
        }
    }

    /**
     * #160: pick an inherent-method impl by the SOLVED receiver type, then unify
     * the call's args/return against that impl's signature. Deferred until the
     * receiver is concrete; a receiver that resolves to a type with no matching
     * candidate is a real error (dispatch would have no runtime impl).
     */
    private fun solveInherentMethodResolve(
        constraint: Constraint.InherentMethodResolve,
        currentSubst: Substitution
    ): ConstraintResult {
        val receiver = currentSubst.apply(constraint.receiverType)
        if (receiver is MezzeType.VarType) {
            return ConstraintResult.Deferred("Waiting for receiver type of .${constraint.methodName} to resolve")
        }

        // #280/F2: prefer the SOLVED receiver's declaration hash (rename-safe,
        // module-distinct) so two same-named inherent-method candidates from
        // different modules pick correctly instead of by first-name-match. Name
        // only when the receiver has no hash (builtins).
        val receiverHash = (receiver as? MezzeType.VariantType)?.typeHash
            ?: (receiver as? MezzeType.TypeApplication)?.typeHash
        val typeName = receiverTypeName(receiver)
        val picked = (receiverHash?.let { h -> constraint.candidates.firstOrNull { it.targetTypeHash == h } })
            ?: typeName?.let { name -> constraint.candidates.firstOrNull { it.targetTypeName == name } }
        if (picked == null) {
            val shown = typeName ?: prettyPrintType(receiver)
            val known = constraint.candidates.map { it.targetTypeName }.distinct().joinToString(", ")
            return ConstraintResult.Failure(
                "No inherent method '${constraint.methodName}' for type '$shown' (candidates: $known)",
                terminal = true
            )
        }

        val fn = picked.methodType as? MezzeType.FunctionType
            ?: return ConstraintResult.Failure(
                "Inherent method '${constraint.methodName}' on '${picked.targetTypeName}' is not a function",
                terminal = true
            )
        val fullParams = (fn.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
        val strippedParamType = MezzeType.RecordType(
            fields = fullParams.filterKeys { it != Keywords.IT },
            rowVar = null
        )

        var subst = Substitution.empty
        // args must match the picked impl's non-`it` params …
        when (val r = unify(subst.apply(constraint.argsType), subst.apply(strippedParamType), registry)) {
            is UnificationResult.Success -> subst = r.substitution.compose(subst)
            is UnificationResult.Failure -> return ConstraintResult.Failure(
                "Arguments to '${constraint.methodName}' don't match impl for '${picked.targetTypeName}': ${r.message}"
            )
        }
        // … and the call's result type is the picked impl's return type.
        when (val r = unify(subst.apply(constraint.resultType), subst.apply(fn.returnType), registry)) {
            is UnificationResult.Success -> subst = r.substitution.compose(subst)
            is UnificationResult.Failure -> return ConstraintResult.Failure(
                "Return type of '${constraint.methodName}' for '${picked.targetTypeName}' mismatch: ${r.message}"
            )
        }
        return ConstraintResult.Success(subst)
    }

    private fun solveEquality(constraint: Constraint.Equality): ConstraintResult {
        // SuspendType wrappers are stripped because effects are tracked separately
        // in ConstraintResult, not in the Equality constraint itself. The constraint
        // generation may wrap types in SuspendType to carry effect info, but by the
        // time we unify, effect tracking has already been handled upstream.
        //
        // Option B / #341 / #370: under {@link #strictEffectRows}, refuse to
        // silently strip a non-empty effect row. If one side is
        // Suspended&lt;T, r&gt; with r non-empty and the other side is not the
        // same Suspended shape (either plain T, or Suspended with a
        // different row), reject the equality. This is the single
        // fundamental leak (audit #370) that lets `Suspend&lt;T, effects&gt;`
        // decay to `T`. The default (false) keeps pre-Option-B behavior
        // so existing callers are unaffected while the fix is ratcheted in.
        val rawLeft = constraint.left
        val rawRight = constraint.right
        if (strictEffectRows) {
            val leftIsSusp = rawLeft is MezzeType.SuspendType && rawLeft.effects.effects.isNotEmpty()
            val rightIsSusp = rawRight is MezzeType.SuspendType && rawRight.effects.effects.isNotEmpty()
            if (leftIsSusp != rightIsSusp) {
                val plainSide = if (leftIsSusp) rawRight else rawLeft
                val suspendedSide = if (leftIsSusp) rawLeft as MezzeType.SuspendType else rawRight as MezzeType.SuspendType
                val leakedEffects = suspendedSide.effects.effects
                // Skip when an enclosing `perform` will discharge every leaked
                // effect. ExprPerformConstraints.generatePerform stamps
                // handledEffects onto every body Equality (via
                // activeDischargedEffects) so we can distinguish an in-flight
                // unification that WILL discharge (safe) from a real leak
                // (fail). See #376.
                val discharged = constraint.activeDischargedEffects
                val undischargedLeaks = leakedEffects - discharged
                if (undischargedLeaks.isNotEmpty()) {
                    // Additional safety net: also skip in-flight unifications
                    // against unresolved type vars — these fire before the
                    // outer solve strips the wrapper, but any real leak against
                    // a var will re-fire once the var resolves to a ground type.
                    if (plainSide !is MezzeType.VarType) {
                        val effList = undischargedLeaks.joinToString(", ")
                        return ConstraintResult.Failure(
                            "Effect row leak: cannot use a value of type " +
                            "${suspendedSide.valueType} with unhandled effects <$effList> " +
                            "as plain $plainSide. Wrap in `perform HandlerFor<$effList> in ...` " +
                            "to discharge the effect first."
                        )
                    }
                }
            }
        }
        val left = unwrapSuspended(rawLeft)
        val right = unwrapSuspended(rawRight)

        DebugLogger.debug(Category.ConstraintSolver) {
            "solveEquality: $left = $right (${constraint.context})"
        }

        val result = unify(left, right, registry)

        DebugLogger.debug(Category.ConstraintSolver) { "  result: $result" }

        return when (result) {
            is UnificationResult.Success -> {
                ConstraintResult.Success(result.substitution)
            }
            is UnificationResult.Failure -> {
                val betterMessage = ErrorMessageBuilder.generateBetterErrorMessage(constraint, result)
                ConstraintResult.Failure(betterMessage)
            }
        }
    }

    private fun solveSubsumes(constraint: Constraint.Subsumes): ConstraintResult {
        val result = unify(constraint.general, constraint.specific, registry)

        return when (result) {
            is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
            is UnificationResult.Failure -> ConstraintResult.Failure(
                "Type ${prettyPrintType(constraint.specific)} is not compatible with ${prettyPrintType(constraint.general)}: ${result.message}"
            )
        }
    }

    private fun solveMustBeOpen(constraint: Constraint.MustBeOpen, currentSubst: Substitution): ConstraintResult {
        val type = constraint.type

        constraint.bindingName?.let { mustBeOpenBindings.add(it) }

        if (type is MezzeType.VarType) {
            mustBeOpenTypes.add(type.variable)

            val resolved = currentSubst.apply(type)
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
            }
        }

        return ConstraintResult.Success(Substitution.empty)
    }

    private fun solveRecordPatternExhaustive(
        constraint: Constraint.RecordPatternExhaustive,
        currentSubst: Substitution
    ): ConstraintResult {
        val resolvedType = currentSubst.apply(constraint.recordType)

        if (resolvedType is MezzeType.VarType) {
            return ConstraintResult.Success(Substitution.empty)
        }

        val recordType = when (resolvedType) {
            is MezzeType.RecordType -> resolvedType
            is MezzeType.TypeApplication -> {
                when (val expansion = expandTypeApplication(resolvedType, registry)) {
                    is TypeExpansionResult.Record -> expansion.recordType
                    is TypeExpansionResult.Newtype -> {
                        expansion.payloadType as? MezzeType.RecordType
                            ?: return ConstraintResult.Success(Substitution.empty)
                    }
                    else -> {
                        return ConstraintResult.Success(Substitution.empty)
                    }
                }
            }
            else -> {
                return ConstraintResult.Success(Substitution.empty)
            }
        }

        val recordFieldNames = recordType.fields.keys
        val uncoveredFields = recordFieldNames - constraint.patternFields

        if (uncoveredFields.isNotEmpty()) {
            val fieldList = uncoveredFields.sorted().joinToString(", ")
            return ConstraintResult.Failure(
                "Non-exhaustive record pattern: missing field${if (uncoveredFields.size > 1) "s" else ""} $fieldList. " +
                "Use { ${constraint.patternFields.joinToString(", ")}, ... } to ignore extra fields."
            )
        }

        return ConstraintResult.Success(Substitution.empty)
    }

    private fun solveHasField(constraint: Constraint.HasField, currentSubst: Substitution = Substitution.empty): ConstraintResult {
        val recordType = constraint.recordType
        val fieldName = constraint.fieldName
        val expectedFieldType = constraint.fieldType

        if (recordType is MezzeType.SuspendType) {
            return solveHasField(
                constraint.copy(recordType = recordType.valueType),
                currentSubst
            )
        }

        if (recordType is MezzeType.TypeApplication) {
            when (val expansion = expandTypeApplication(recordType, registry)) {
                is TypeExpansionResult.Record -> {
                    return solveHasField(
                        constraint.copy(recordType = expansion.recordType),
                        currentSubst
                    )
                }
                is TypeExpansionResult.Variant -> {
                    return solveHasField(
                        constraint.copy(recordType = expansion.variantType),
                        currentSubst
                    )
                }
                is TypeExpansionResult.Newtype -> {
                    return solveHasField(
                        constraint.copy(recordType = expansion.variantType),
                        currentSubst
                    )
                }
                is TypeExpansionResult.NotFound -> {
                    return ConstraintResult.Failure(
                        "Cannot resolve type '${recordType.constructor}' to check for field '$fieldName'"
                    )
                }
            }
        }

        if (recordType is MezzeType.VarType) {
            if (constraint.strictFieldCheck) {
                return ConstraintResult.Deferred("Waiting for record type to be resolved for strict field check")
            }

            val recordWithField = MezzeType.RecordType(
                fields = mapOf(fieldName to expectedFieldType),
                rowVar = RowVar.fresh()
            )
            val result = unify(recordType, recordWithField, registry)
            return when (result) {
                is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                is UnificationResult.Failure -> ConstraintResult.Failure(result.message)
            }
        }

        if (recordType is MezzeType.VariantType) {
            if (recordType.constructors.size == 1 && recordType.rowVar == null) {
                val (_, payloadType) = recordType.constructors.entries.first()
                if (payloadType != null) {
                    return solveHasField(
                        constraint.copy(recordType = payloadType),
                        currentSubst
                    )
                }
            }
            return ConstraintResult.Failure(
                "Cannot access field '$fieldName' on variant type: ${prettyPrintType(recordType)}"
            )
        }

        if (recordType is MezzeType.RecordType) {
            val actualFieldType = recordType.fields[fieldName]
            if (actualFieldType == null) {
                // Growing an open row by INVENTING the missing field is only sound
                // when the row is solver-inferred (an unannotated param whose shape
                // is still being discovered). A USER-WRITTEN open record
                // (`{ a: Int, ... }`, a `userOpen_` row) is different: the `...`
                // means "the caller may pass extra fields I don't know about" — it
                // does NOT license reading an arbitrary unlisted field here. So for
                // a user-specified open row, a missing field is an error, not an
                // invitation to invent (which let `r.zzz` read a nonexistent field).
                val isUserOpenRow = recordType.rowVar?.isUserSpecified() == true
                if (recordType.rowVar != null && !constraint.strictFieldCheck && !isUserOpenRow) {
                    val extendedFields = recordType.fields + (fieldName to expectedFieldType)
                    val extendedRecord = MezzeType.RecordType(
                        fields = extendedFields,
                        rowVar = recordType.rowVar
                    )

                    for ((typeVar, mappedType) in currentSubst.mappings) {
                        val resolvedMappedType = currentSubst.apply(mappedType)
                        if (resolvedMappedType == recordType) {
                            return ConstraintResult.Success(Substitution.single(typeVar, extendedRecord))
                        }
                    }

                    return ConstraintResult.Success(Substitution.empty)
                } else {
                    val availableFields = recordType.fields.keys.toList()
                    val suggestion = StringDistance.suggestOrList(
                        fieldName,
                        availableFields,
                        "fields"
                    )
                    return ConstraintResult.Failure(
                        "Record type does not have field '$fieldName'. $suggestion"
                    )
                }
            }

            val result = unify(actualFieldType, expectedFieldType, registry)
            return when (result) {
                is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                is UnificationResult.Failure -> ConstraintResult.Failure(
                    "Field '$fieldName' type mismatch: ${result.message}"
                )
            }
        }

        return ConstraintResult.Failure(
            "Cannot access field '$fieldName' on non-record type: ${prettyPrintType(recordType)}"
        )
    }

    private fun applySubstitutionToConstraint(constraint: Constraint, substitution: Substitution): Constraint {
        return constraint.mapTypes { substitution.apply(it) }
    }

}
