package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.printing.ErrorMessageBuilder
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.receiverTypeName

class ConstraintSolver(
    private val context: TypingContext,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
    /**
     * Impl-body validation mode. When an operator body (`+`, `<`, …) is solved
     * in isolation to resolve its associated-type `'out` before the return-type
     * check, an [Constraint.AbilityRequired] whose impl EXISTS somewhere but is
     * not import-visible at this site must NOT fail — that obligation is properly
     * checked at the use-site/top-level solve. A genuinely-ABSENT impl still
     * fails, which is what lets the `'out` var stay unresolved and the return
     * check reject (soundness). Only [ImplValidator] sets this.
     */
    private val deferExistingAbilityImpls: Boolean = false,
    /**
     * Optional caller-owned codec-impl registry shared across sibling solvers of
     * a single module typecheck. See the field-level note on
     * [AbilityChecker.generatedCodecImpls] for why this must outlive one solver
     * instance. Null means "use a private per-instance map" (safe for isolated
     * solvers whose derivations don't need to reach the emit stage).
     */
    private val sharedGeneratedCodecImpls: MutableMap<com.mezze.language.typedast.Hash, AbilityChecker.GeneratedCodecImpl>? = null,
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
        // Applied post-solve only (applyErrorChannelDefaults); never in the
        // priority-sorted worklist. Ordered last as a harmless backstop.
        is Constraint.DefaultAssociatedType -> 5
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
            // Never solved in the worklist nor a source of failures, so it has no
            // cascade subject; the binding var is the closest thing.
            is Constraint.DefaultAssociatedType -> addVarsOf(constraint.bindingType)
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

    private val abilityChecker = AbilityChecker(context, registry, deferExistingAbilityImpls, sharedGeneratedCodecImpls)

    internal fun getGeneratedCodecImpls(): Collection<AbilityChecker.GeneratedCodecImpl> = abilityChecker.getGeneratedCodecImpls()

    /**
     * What solving concluded about one ability constraint's arguments. The three
     * answers are genuinely different and a caller must not collapse them:
     * DISCHARGED carries no obligation to anyone, UNSATISFIED is an error, and
     * UNDECIDED is neither — it is the ordinary polymorphic case.
     *
     * They WERE collapsed, into a single "is it discharged" boolean, and the two
     * false answers read alike. Whoever wanted to report the failure could not:
     * `Tryable (Result Int HttpError)` answers false because the variant's row is
     * still open, not because Result has no `Tryable`.
     */
    internal enum class AbilityDischarge { DISCHARGED, UNSATISFIED, UNDECIDED }

    /**
     * DISCHARGED when every one of [typeArgs] is concrete AND an impl of
     * [abilityName] exists for that tuple — such a constraint imposes nothing on a
     * caller, so a finalized function type should not keep it as a residual.
     * UNSATISFIED when the args are concrete and no impl matches. UNDECIDED while
     * any argument is still open, including an open ROW inside a variant: nothing
     * can be concluded until the solve pins it.
     * (Structural builtins like Inspect are always satisfied for a concrete type;
     * user abilities require a matching impl.)
     */
    internal fun abilityConstraintDischarge(
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: com.mezze.language.typedast.Hash?,
    ): AbilityDischarge {
        if (typeArgs.isEmpty()) return AbilityDischarge.UNDECIDED
        if (!typeArgs.all { abilityChecker.isConcrete(it) }) return AbilityDischarge.UNDECIDED
        return if (abilityChecker.checkAbilityImplementation(abilityName, typeArgs, abilityHash))
            AbilityDischarge.DISCHARGED else AbilityDischarge.UNSATISFIED
    }

    /**
     * The same sentence the discharge path produces, so a constraint found
     * unsatisfiable while generalizing reads identically to one found at a call
     * site — including the "to enable this, implement …" skeleton.
     */
    internal fun describeMissingAbility(abilityName: String, typeArgs: List<MezzeType>): String =
        abilityChecker.generateAbilityErrorMessage(abilityName, typeArgs)

    private val mustBeOpenTypes = mutableSetOf<TypeVar>()
    private val mustBeOpenRowVars = mutableSetOf<RowVar>()
    private val mustBeOpenBindings = mutableSetOf<String>()
    private val patternBasedRowVars = mutableSetOf<RowVar>()

    // #403 payload carve-out: payload vars of CLOSED record patterns on a fresh
    // scrutinee (anonymous variant payload inferred purely from the pattern). Such
    // a payload's field-access tail is an inference artifact, NOT a promise of
    // row-polymorphism — it must CLOSE to the pattern's fields unless a genuinely
    // wider value flowed in. Tracked so solveMustBeOpen / resolveMustBeOpenRowVars
    // can DECLINE to exempt the tail from closing (the exact inverse of the normal
    // MustBeOpen behaviour, which exempts it). See solveMustBeOpen.
    private val closeablePatternInferredTypes = mutableSetOf<TypeVar>()

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

        // Best-effort error-channel DEFAULTS from discharging performs are held out
        // of the main solve entirely; applyErrorChannelDefaults runs them last, once
        // every binding's type is ground. They never constrain the main solution.
        val (defaultAssocConstraints, solvableConstraints) =
            constraints.partition { it is Constraint.DefaultAssociatedType }

        val (inferredEqualities, inferenceErrors) = variantSolver.inferVariantTypes(solvableConstraints)
        collectInferenceErrors(inferenceErrors, constraints, errors)

        val allConstraints = solvableConstraints + inferredEqualities
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

        // Best-effort error-channel defaults from discharging performs — run LAST,
        // only if the main solve succeeded, so every binding type is ground. Never
        // produces an error (a shape mismatch is simply skipped).
        if (errors.isEmpty() && defaultAssocConstraints.isNotEmpty()) {
            substitution = applyErrorChannelDefaults(defaultAssocConstraints, substitution)
        }

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
                            code = result.code,
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
                                    // A constructor that resolves to nothing is its
                                    // own kind of failure, and callers have to be able
                                    // to tell it from an equality mismatch WITHOUT
                                    // matching on the message text — the top-level-expr
                                    // filter in ConstraintGenerator keeps errors by
                                    // typed code, and without one this failure was
                                    // dropped there and the program ran.
                                    code = ErrorCode.V0704_UNKNOWN_CONSTRUCTOR,
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
            // #403: mirror the record case (open `{ f, ... }` pattern) at the
            // final post-solve pass, so the resolved tail is captured even when the
            // row var was only pinned by later substitution.
            if (resolved is MezzeType.RecordType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
                DebugLogger.debug(Category.ConstraintSolver) { "Added record rowVar=${resolved.rowVar} to mustBeOpenRowVars" }
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

        // L-01: never merge two provably-DISTINCT declared variants (different
        // content hash). unifyVariants already VETOes this pair; without the same
        // guard here, match-arm inference would union a foreign module's variant
        // into another's row var and launder a foreign constructor. Anonymous /
        // UNDETERMINED identities (genuine row-poly) still merge below.
        if (identityVerdict(leftType.identity, rightType.identity) == IdentityVerdict.DISTINCT) return null

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
            identity = if (leftType.identity != TypeIdentity.Anonymous) leftType.identity else rightType.identity
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
                                    code = result.code,
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

    /**
     * Best-effort error-channel DEFAULTS from a discharging `perform`. For each
     * [Constraint.DefaultAssociatedType], resolve both sides under the final
     * substitution; if the solved binding is a [MezzeType.TypeApplication] with
     * the SAME constructor and arity as the handler's suspension shape, unify the
     * two so any still-free var in the binding's argument positions (the error
     * channel) takes the concrete type the shape fixes there.
     *
     * SOUND & NARROW: a unify FAILURE is silently ignored (never an error) and the
     * pass only runs when constructors match, so it can only bind free vars — it
     * never coerces a plain `Int` binding to a Result, never reshapes a
     * non-matching type, and never overrides an already-concrete slot. The shape's
     * own vars were freshened at emit time, so binding them is inert.
     */
    private fun applyErrorChannelDefaults(
        defaults: List<Constraint>,
        inputSubst: Substitution
    ): Substitution {
        var substitution = inputSubst
        for (constraint in defaults) {
            if (constraint !is Constraint.DefaultAssociatedType) continue
            val solvedBinding = substitution.apply(constraint.bindingType)
            val solvedShape = substitution.apply(constraint.shape)
            if (solvedBinding !is MezzeType.TypeApplication || solvedShape !is MezzeType.TypeApplication) continue
            if (solvedBinding.constructor != solvedShape.constructor) continue
            if (solvedBinding.args.size != solvedShape.args.size) continue
            when (val r = unify(solvedBinding, solvedShape, registry)) {
                is UnificationResult.Success -> substitution = r.substitution.compose(substitution)
                is UnificationResult.Failure -> { /* shape mismatch — leave the binding untouched */ }
            }
        }
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
            val terminal: Boolean = false,
            /**
             * Lets a caller tell one KIND of failure from another without reading
             * the message. The top-level-expression filter in ConstraintGenerator
             * keeps errors by typed code, and a `HasField` failure comes in two
             * kinds that must be treated oppositely: the field is ABSENT (the
             * receiver is a scalar, an opaque newtype, or a record without it),
             * which can never become valid later and must be reported; or the
             * field's TYPE has not been reconciled yet, which the global solver
             * owns and which must stay deferred. Only the first carries a code.
             */
            val code: ErrorCode = ErrorCode.UNCATEGORIZED,
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
            // Applied in a dedicated post-solve pass (applyErrorChannelDefaults),
            // never in the main worklist — a no-op here is a defensive backstop.
            is Constraint.DefaultAssociatedType -> ConstraintResult.Success(Substitution.empty)
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
        val receiverHash = ((receiver as? MezzeType.VariantType)?.identity as? TypeIdentity.Declared)?.hash
            ?: ((receiver as? MezzeType.TypeApplication)?.identity as? TypeIdentity.Declared)?.hash
        val typeName = receiverTypeName(receiver)
        // BUG 1: most-specific hash first (applied before constructor), so an
        // `impl Result PyForeign IErr` outranks a generic `impl Result 'a 'e`
        // structurally instead of via the mangled-name ladder below.
        val picked = com.mezze.language.typechecker.util
            .pickByReceiverHash(receiver, constraint.candidates) { it.targetTypeHash }
            ?: run {
                // Specificity ladder: mangled name first (`Result_A_B` for a
                // concrete-arg TypeApplication), constructor-only fallback second
                // (`Result` matches the generic `impl Result 'a 'e`). Keeps the
                // deferred solver in sync with legacyInherentImplHash /
                // DispatchResolver — all three ladders must agree.
                var found: Constraint.InherentMethodCandidate? = null
                for (name in com.mezze.language.typechecker.util.receiverTypeNames(receiver)) {
                    found = constraint.candidates.firstOrNull { it.targetTypeName == name }
                    if (found != null) break
                }
                found
            }
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
        // Effects are tracked separately (on arrows, threaded through the
        // ConstraintResult collector), never on the value types an Equality
        // relates — so unification here concerns only the value shapes. Effect
        // -> pure laundering is caught by arrow unification (#215).
        val left = constraint.left
        val right = constraint.right

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
            // #403 payload carve-out: a CLOSED record pattern on a fresh scrutinee
            // does NOT keep its payload row open — the tail is a pattern-inference
            // artifact, closed by closeGeneratedRowVars like any other generated
            // record tail (declining to exempt it below IS the close). Record the
            // var so the post-solve pass agrees, and skip the exemption. A wider
            // value that genuinely flows in unifies the payload with a RECORD
            // carrying the extra fields; that record's OWN row var (if any) governs
            // openness independently, so the wider-value case is unaffected.
            if (constraint.closeableIfPatternInferred) {
                closeablePatternInferredTypes.add(type.variable)
                return ConstraintResult.Success(Substitution.empty)
            }

            mustBeOpenTypes.add(type.variable)

            val resolved = currentSubst.apply(type)
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
            }
            // #403: an OPEN record pattern (`{ f, ... }`) is the user's explicit
            // request to keep the destructured record's row open, so its
            // field-access-derived tail must NOT be closed. Same treatment as an
            // open variant scrutinee above.
            if (resolved is MezzeType.RecordType) {
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

    /**
     * Receivers a METHOD call grew a row on — see [Constraint.HasField.methodCallee].
     * The per-binding propagation leaves these alone: the shape types the call,
     * but it is not a record the program has.
     */
    internal val methodGrownReceiverVars = mutableSetOf<TypeVar>()

    private fun solveHasField(constraint: Constraint.HasField, currentSubst: Substitution = Substitution.empty): ConstraintResult {
        if (constraint.methodCallee) {
            methodGrownReceiverVars.addAll(collectFreeTypeVars(constraint.recordType))
            methodGrownReceiverVars.addAll(collectFreeTypeVars(currentSubst.apply(constraint.recordType)))
        }
        if (constraint.completionOnly) {
            // Only a receiver that is ALREADY a concrete record with this field
            // can be completed here. Anything else is left alone: the per-binding
            // solve has had its go, and this pass exists to finish what became
            // knowable since, not to infer or to re-judge.
            val recv = currentSubst.apply(constraint.recordType)
            val present = (recv as? MezzeType.RecordType)?.fields?.get(constraint.fieldName)
                ?: return ConstraintResult.Deferred("completion pass: receiver is not a concrete record with this field")
            return when (val r = unify(present, currentSubst.apply(constraint.fieldType), registry)) {
                is UnificationResult.Success -> ConstraintResult.Success(r.substitution)
                is UnificationResult.Failure -> ConstraintResult.Deferred("completion pass: ${r.message}")
            }
        }
        val recordType = constraint.recordType
        val fieldName = constraint.fieldName
        val expectedFieldType = constraint.fieldType

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
                    // ABSOLUTE OPACITY (#401): a newtype is opaque. Field access
                    // must NEVER punch through the wrapper — regardless of the
                    // inner shape (record / variant / primitive / type-var) and
                    // regardless of module (even the defining one). The ONLY way
                    // to the underlying value is an unwrap `match x is Name.(v)`.
                    // Do NOT recurse into the wrapped payload here.
                    return ConstraintResult.Failure(
                        "Field access '.$fieldName' on an opaque newtype '${recordType.constructor}': " +
                        "a newtype hides its underlying value. Unwrap it first with " +
                        "`match x is ${recordType.constructor}.(v) -> ...`, then access the field on `v`.",
                        code = ErrorCode.T0205_FIELD_NOT_FOUND,
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
                "Cannot access field '$fieldName' on variant type: ${prettyPrintType(recordType)}",
                code = ErrorCode.T0205_FIELD_NOT_FOUND,
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
                    // Record the growth AS A ROW BINDING: the row var maps to an
                    // extension carrying the new field and a fresh tail, and
                    // Substitution.apply splices it into every record holding
                    // this row — however that record is reached.
                    //
                    // The var-rebind loop below predates the row channel and is
                    // kept for callers that read a variable's stored binding
                    // directly instead of applying the substitution. On its own
                    // it was the bug: it rebinds the FIRST variable whose binding
                    // compares equal to this record, and when several variables
                    // chain to the same record — or the record is reached through
                    // a constraint rather than a binding — the extension landed
                    // nowhere and the fall-through returned an EMPTY
                    // substitution. The second field accessed on an inferred
                    // record typechecked and then vanished: a decoder derived
                    // from the type carried only the first field, and the
                    // program died at runtime with `Field not found`.
                    val rowBinding = mapOf(
                        recordType.rowVar!! to MezzeType.RecordType(
                            fields = mapOf(fieldName to expectedFieldType),
                            rowVar = RowVar.fresh()
                        )
                    )
                    val extendedFields = recordType.fields + (fieldName to expectedFieldType)
                    val extendedRecord = MezzeType.RecordType(
                        fields = extendedFields,
                        rowVar = recordType.rowVar
                    )

                    for ((typeVar, mappedType) in currentSubst.mappings) {
                        val resolvedMappedType = currentSubst.apply(mappedType)
                        if (resolvedMappedType == recordType) {
                            return ConstraintResult.Success(
                                Substitution(
                                    mappings = mapOf(typeVar to extendedRecord),
                                    recordRowMappings = rowBinding
                                )
                            )
                        }
                    }

                    return ConstraintResult.Success(Substitution(recordRowMappings = rowBinding))
                } else {
                    val availableFields = recordType.fields.keys.toList()
                    val suggestion = StringDistance.suggestOrList(
                        fieldName,
                        availableFields,
                        "fields"
                    )
                    return ConstraintResult.Failure(
                        "Record type does not have field '$fieldName'. $suggestion",
                        code = ErrorCode.T0205_FIELD_NOT_FOUND,
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
            "Cannot access field '$fieldName' on non-record type: ${prettyPrintType(recordType)}",
            code = ErrorCode.T0205_FIELD_NOT_FOUND,
        )
    }

    private fun applySubstitutionToConstraint(constraint: Constraint, substitution: Substitution): Constraint {
        return constraint.mapTypes { substitution.apply(it) }
    }

}
