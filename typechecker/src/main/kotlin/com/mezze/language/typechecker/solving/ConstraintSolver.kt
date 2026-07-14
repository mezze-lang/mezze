package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
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

    private val abilityChecker = AbilityChecker(context, registry)

    internal fun getGeneratedCodecImpls(): Collection<AbilityChecker.GeneratedCodecImpl> = abilityChecker.getGeneratedCodecImpls()

    private val mustBeOpenTypes = mutableSetOf<TypeVar>()
    private val mustBeOpenRowVars = mutableSetOf<RowVar>()
    private val mustBeOpenBindings = mutableSetOf<String>()
    private val patternBasedRowVars = mutableSetOf<RowVar>()

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
        var substitution = Substitution.empty
        val errors = mutableListOf<ConstraintError>()
        val warnings = mutableListOf<String>()
        val resolvedAbilities = mutableSetOf<Constraint.AbilityRequired>()

        val (inferredEqualities, inferenceErrors) = variantSolver.inferVariantTypes(constraints)

        if (inferenceErrors.isNotEmpty() && constraints.isNotEmpty()) {
            val contextConstraint = constraints.first()
            for (errorMsg in inferenceErrors) {
                errors.add(
                    ConstraintError(
                        message = errorMsg,
                        constraint = contextConstraint,
                        context = "Variant type inference"
                    )
                )
            }
        }

        val allConstraints = constraints + inferredEqualities

        val (mustBeOpenConstraints, constraintsAfterOpen) = allConstraints.partition { it is Constraint.MustBeOpen }
        for (constraint in mustBeOpenConstraints) {
            val result = solveConstraint(constraint, substitution)
        }

        val (abilityConstraints, nonAbilityConstraints) = constraintsAfterOpen.partition {
            it is Constraint.AbilityRequired || it is Constraint.AssociatedTypeResolution
        }

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
                    errors.add(
                        ConstraintError(
                            message = result.message,
                            constraint = appliedConstraint,
                            context = appliedConstraint.context
                        )
                    )
                }
                is ConstraintResult.Deferred -> {
                }
            }
        }

        val hasConstructorConstraints = nonAbilityConstraints.filterIsInstance<Constraint.HasConstructor>()
        val otherConstraints = nonAbilityConstraints.filter { it !is Constraint.HasConstructor }

        val hasConstructorWorklist = hasConstructorConstraints.toMutableList()
        var hcMadeProgress = true
        var hcIterationCount = 0

        while (hcMadeProgress && hasConstructorWorklist.isNotEmpty() && hcIterationCount < 50) {
            hcMadeProgress = false
            hcIterationCount++

            val iterator = hasConstructorWorklist.iterator()
            while (iterator.hasNext()) {
                val constraint = iterator.next()
                val appliedConstraint = applySubstitutionToConstraint(constraint, substitution) as Constraint.HasConstructor

                val result = variantSolver.solveHasConstructor(appliedConstraint, substitution, constraint)

                when (result) {
                    is ConstraintResult.Success -> {
                        val newSubst = result.substitution
                        if (newSubst.mappings.isNotEmpty()) {
                            substitution = newSubst.compose(substitution)
                            hcMadeProgress = true
                        }
                        warnings.addAll(result.warnings)
                        iterator.remove()
                    }
                    is ConstraintResult.Failure -> {
                        val constraintType = appliedConstraint.variantType
                        if (constraintType is MezzeType.VarType && !result.terminal) {
                        } else {
                            errors.add(
                                ConstraintError(
                                    message = result.message,
                                    constraint = appliedConstraint,
                                    context = appliedConstraint.context
                                )
                            )
                            iterator.remove()
                        }
                    }
                    is ConstraintResult.Deferred -> {
                    }
                }
            }
        }

        for (typeVar in mustBeOpenTypes) {
            val resolved = substitution.apply(MezzeType.VarType(typeVar))
            DebugLogger.debug(Category.ConstraintSolver) { "MustBeOpen typeVar=$typeVar resolved to $resolved" }
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
                DebugLogger.debug(Category.ConstraintSolver) { "Added rowVar=${resolved.rowVar} to mustBeOpenRowVars" }
            }
        }

        DebugLogger.debugSection(Category.ConstraintSolver, "Row Variables") {
            listOf(
                "patternBasedRowVars = $patternBasedRowVars",
                "mustBeOpenRowVars = $mustBeOpenRowVars"
            )
        }

        substitution = variantSolver.closePatternBasedVariants(substitution)

        val allRemainingConstraints = hasConstructorWorklist + otherConstraints
        val sortedConstraints = allRemainingConstraints.sortedBy { constraintPriority(it) }
        val constraintWorklist = sortedConstraints.toMutableList()
        val processedInThisPass = mutableSetOf<Constraint>()
        var madeProgress = true
        var iterationCount = 0
        val maxIterations = 100

        while (madeProgress && constraintWorklist.isNotEmpty() && iterationCount < maxIterations) {
            madeProgress = false
            iterationCount++
            processedInThisPass.clear()

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
                            val originalConstraint = constraint
                            val appliedEq = appliedConstraint as Constraint.Equality
                            val leftType = substitution.apply(appliedEq.left)
                            val rightType = substitution.apply(appliedEq.right)

                            if (leftType is MezzeType.VariantType && rightType is MezzeType.VariantType) {
                                val leftCtors = leftType.constructors.keys
                                val rightCtors = rightType.constructors.keys

                                if (leftCtors != rightCtors) {
                                    val isMatchContext = originalConstraint.isMatchCaseContext

                                    val bothOpen = leftType.rowVar != null && rightType.rowVar != null
                                    val eitherPatternBased = (leftType.rowVar != null && patternBasedRowVars.contains(leftType.rowVar)) ||
                                                             (rightType.rowVar != null && patternBasedRowVars.contains(rightType.rowVar))

                                    if (isMatchContext || (bothOpen && !eitherPatternBased)) {
                                        val mergedConstructors = leftType.constructors.toMutableMap()
                                        for ((name, dataType) in rightType.constructors) {
                                            if (name !in mergedConstructors) {
                                                mergedConstructors[name] = dataType
                                            }
                                        }

                                        val mergedRowVar = leftType.rowVar ?: rightType.rowVar
                                        val mergedVariant = MezzeType.VariantType(
                                            constructors = mergedConstructors,
                                            rowVar = mergedRowVar,
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
                                            val resolved = substitution.apply(mappedType)
                                            if (resolved == leftType || resolved == rightType) {
                                                updatedMappings[typeVar] = mergedVariant
                                            }
                                        }

                                        if (updatedMappings.isNotEmpty()) {
                                            substitution = Substitution(substitution.mappings + updatedMappings)
                                            madeProgress = true
                                        }
                                    }
                                }
                            }
                        }

                        iterator.remove()
                        processedInThisPass.add(constraint)
                    }
                    is ConstraintResult.Failure -> {
                        val constraintType = when (appliedConstraint) {
                            is Constraint.HasField -> appliedConstraint.recordType
                            is Constraint.HasConstructor -> appliedConstraint.variantType
                            else -> null
                        }

                        if (constraintType is MezzeType.VarType) {
                        } else {
                            errors.add(
                                ConstraintError(
                                    message = result.message,
                                    constraint = appliedConstraint,
                                    context = appliedConstraint.context
                                )
                            )
                            iterator.remove()
                        }
                    }
                    is ConstraintResult.Deferred -> {
                    }
                }
            }
        }

        for (constraint in constraintWorklist) {
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
            val result = when (constraint) {
                is Constraint.HasConstructor -> variantSolver.solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                else -> solveConstraint(appliedConstraint, substitution)
            }
            if (result is ConstraintResult.Failure) {
                errors.add(
                    ConstraintError(
                        message = result.message,
                        constraint = appliedConstraint,
                        context = appliedConstraint.context
                    )
                )
            }
        }

        val validationResult = abilityChecker.validateAbilityConstraints(substitution)
        if (validationResult != null) {
            val (validationError, errorLoc) = validationResult
            errors.add(
                ConstraintError(
                    message = validationError,
                    constraint = Constraint.Equality(MezzeType.IntType, MezzeType.IntType, loc = errorLoc),
                    context = "ability constraint validation"
                )
            )
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
                        errors.add(
                            ConstraintError(
                                message = result.message,
                                constraint = constraint,
                                context = constraint.context
                            )
                        )
                    }
                    is ConstraintResult.Deferred -> {
                    }
                }
            }
        }

        for (typeVar in mustBeOpenTypes) {
            val resolvedType = substitution.apply(MezzeType.VarType(typeVar))
            if (resolvedType is MezzeType.VariantType) {
                resolvedType.rowVar?.let { mustBeOpenRowVars.add(it) }
            }
        }

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
                "Subsumption failed: ${constraint.general} does not subsume ${constraint.specific}"
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
                        "Cannot expand type application ${recordType.constructor} to check field '$fieldName'"
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
