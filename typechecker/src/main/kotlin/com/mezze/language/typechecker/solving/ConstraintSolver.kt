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

class ConstraintSolver(
    private val context: TypingContext,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY
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

    private val abilityChecker = AbilityChecker(context, registry)

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

    private val variantSolver = VariantSolver(registry, patternBasedRowVars, mustBeOpenRowVars)

    fun getMustBeOpenRowVars(): Set<RowVar> = mustBeOpenRowVars.toSet()

    sealed class SolveResult {
        data class Success(
            val substitution: Substitution,
            val warnings: List<String> = emptyList(),
            val resolvedAbilityConstraints: Set<Constraint.AbilityRequired> = emptySet(),
            val mustBeOpenTypes: Set<TypeVar> = emptySet(),
            val mustBeOpenRowVars: Set<RowVar> = emptySet(),
            val mustBeOpenBindings: Set<String> = emptySet(),
            val patternBasedRowVars: Set<RowVar> = emptySet()
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

    fun solve(constraints: List<Constraint>): SolveResult {
        // #61: poison is scoped to a single solve() so a failure in one binding
        // can't suppress an unrelated failure in the next (the solver instance is
        // reused across per-binding solves).
        poisonedVars.clear()
        var substitution = Substitution.empty
        val errors = mutableListOf<ConstraintError>()
        val warnings = mutableListOf<String>()
        val resolvedAbilities = mutableSetOf<Constraint.AbilityRequired>()

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

        substitution = solveAbilityConstraints(abilityConstraints, substitution, warnings, errors, resolvedAbilities)

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
        resolveMustBeOpenRowVars(substitution)

        return if (errors.isEmpty()) {
            SolveResult.Success(
                substitution = substitution,
                warnings = warnings,
                resolvedAbilityConstraints = resolvedAbilities,
                mustBeOpenTypes = mustBeOpenTypes.toSet(),
                mustBeOpenRowVars = mustBeOpenRowVars.toSet(),
                mustBeOpenBindings = mustBeOpenBindings.toSet(),
                patternBasedRowVars = patternBasedRowVars.toSet()
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
        resolvedAbilities: MutableSet<Constraint.AbilityRequired>
    ): Substitution {
        var substitution = inputSubst
        for (constraint in abilityConstraints) {
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
            val result = solveConstraint(appliedConstraint, substitution)

            when (result) {
                is ConstraintResult.Success -> {
                    substitution = result.substitution.compose(substitution)
                    warnings.addAll(result.warnings)
                    if (appliedConstraint is Constraint.AbilityRequired) {
                        if (result.warnings.none { it.contains("deferred") }) {
                            resolvedAbilities.add(appliedConstraint)
                        }
                    }
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
                        if (newSubst.mappings.isNotEmpty()) {
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

        return if (updatedMappings.isNotEmpty()) Substitution(substitution.mappings + updatedMappings) else null
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
                        if (newSubst.mappings.isNotEmpty()) {
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
                    context = appliedConstraint.context
                ))
                poison(constraint)
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
                constraint = Constraint.Equality(MezzeType.IntType, MezzeType.IntType, loc = errorLoc),
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
        }
    }

    private fun solveEquality(constraint: Constraint.Equality): ConstraintResult {
        // SuspendedType wrappers are stripped because effects are tracked separately
        // in ConstraintResult, not in the Equality constraint itself. The constraint
        // generation may wrap types in SuspendedType to carry effect info, but by the
        // time we unify, effect tracking has already been handled upstream.
        val left = if (constraint.left is MezzeType.SuspendedType) (constraint.left as MezzeType.SuspendedType).valueType else constraint.left
        val right = if (constraint.right is MezzeType.SuspendedType) (constraint.right as MezzeType.SuspendedType).valueType else constraint.right

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

        if (recordType is MezzeType.SuspendedType) {
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
                if (recordType.rowVar != null && !constraint.strictFieldCheck) {
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
