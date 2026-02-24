package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category

/**
 * Constraint solver - solves type constraints to infer types.
 *
 * This is PHASE 3 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver) ← WE ARE HERE
 * - PHASE 4: Validate types (TypeValidator)
 * - PHASE 5: Apply substitution (SubstitutionApplier)
 *
 * The solver takes a list of constraints and finds a substitution
 * that satisfies all of them (or reports an error if impossible).
 *
 * Design:
 * - Pure function (no mutation!)
 * - Sealed SolveResult type (no nulls!)
 * - Exhaustive pattern matching on Constraint types
 * - Accumulates substitution as it solves constraints
 */
class ConstraintSolver(
    private val context: TypingContext,
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    private val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap()
) {
    /**
     * CodecGenerator for auto-deriving Encode/Decode implementations.
     */
    private val codecGenerator = CodecGenerator()

    /**
     * Track generated codec implementations.
     *
     * When we see an Encode or Decode ability constraint for a serializable type
     * that doesn't have an explicit impl, we generate one and store it here.
     * Key: Content hash of the generated ImplAbilityDeclaration
     */
    private val generatedCodecImpls = mutableMapOf<String, ImplAbilityDeclaration>()
    /**
     * Track ability constraints on type variables.
     *
     * When we see AbilityRequired('a, Parse), we store: 'a -> [(Parse, [typeArgs])]
     * Later during unification, when 'a = Int, we check that Int satisfies Parse ability.
     *
     * This is inspired by OldTypeChecker.kt:15318-15328 where ability constraints are tracked
     * on type variables during ability method calls.
     *
     * Each entry contains: (abilityName, typeArgs, sourceLocation)
     */
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<Triple<String, List<MezzeType>, SourceLocation?>>>()

    /**
     * Deferred type inferences from bidirectional ability inference.
     *
     * When we have a multi-parameter ability like Convert 'a 'b and 'a = Int,
     * we can look up the implementation Convert_Int_String and infer 'b = String.
     * These inferences are stored here and applied to the final substitution.
     */
    private val deferredInference = mutableMapOf<TypeVar, MezzeType>()

    /**
     * Track type variables that must be open (have row variables).
     *
     * When a match expression has a wildcard, the scrutinee type must be open
     * because it could have additional constructors beyond those matched.
     */
    private val mustBeOpenTypes = mutableSetOf<TypeVar>()

    /**
     * Track row variables that must stay open.
     *
     * When a mustBeOpenType gets resolved to a variant with a row variable,
     * we track that row variable so it won't be closed.
     */
    private val mustBeOpenRowVars = mutableSetOf<RowVar>()

    /**
     * Track binding names that must have open variants.
     *
     * This is more stable than tracking row variables, since binding names
     * don't change across constraint solves.
     */
    private val mustBeOpenBindings = mutableSetOf<String>()

    /**
     * Track row variables that came from pattern matching contexts.
     *
     * These row vars should be CLOSED at the end of constraint solving
     * (unless they're also in mustBeOpenRowVars due to a wildcard).
     * This ensures anonymous variants from pattern matching are closed:
     *   let f = {x} -> match x is Red -> 1 | Blue -> 2
     * should produce a function that only accepts Red or Blue.
     */
    private val patternBasedRowVars = mutableSetOf<RowVar>()

    /**
     * Get the set of type variables that must remain open (have row variables).
     */
    fun getMustBeOpenTypes(): Set<TypeVar> = mustBeOpenTypes.toSet()

    /**
     * Get the set of row variables that must remain open.
     */
    fun getMustBeOpenRowVars(): Set<RowVar> = mustBeOpenRowVars.toSet()

    /**
     * Get the set of binding names that must have open variants.
     */
    fun getMustBeOpenBindings(): Set<String> = mustBeOpenBindings.toSet()

    /**
     * Result of solving constraints.
     *
     * Sealed type - exhaustive pattern matching, no nulls!
     */
    sealed class SolveResult {
        /**
         * Solving succeeded with a substitution.
         */
        data class Success(
            val substitution: Substitution,
            val warnings: List<String> = emptyList(),
            val resolvedAbilityConstraints: Set<Constraint.AbilityRequired> = emptySet(),
            val mustBeOpenTypes: Set<TypeVar> = emptySet(),
            val mustBeOpenRowVars: Set<RowVar> = emptySet(),
            val mustBeOpenBindings: Set<String> = emptySet(),
            /** Row vars from pattern matching that should be closed (unless also in mustBeOpenRowVars) */
            val patternBasedRowVars: Set<RowVar> = emptySet()
        ) : SolveResult()

        /**
         * Solving failed with errors.
         */
        data class Failure(
            val errors: List<ConstraintError>
        ) : SolveResult()
    }

    /**
     * Error from constraint solving.
     */
    data class ConstraintError(
        val message: String,
        val constraint: Constraint,
        val context: String? = null
    )

    /**
     * Infer variant types from HasConstructor constraints.
     *
     * When we have HasConstructor constraints on a type variable, we can infer
     * which variant type it should be by finding the variant declaration that
     * has all those constructors.
     *
     * For example:
     * - HasConstructor('a, "Cons", ...)
     * - HasConstructor('a, "Nil", ...)
     * => Infer: 'a = List 'element
     *
     * Returns pair of (Equality constraints to add, Errors found)
     *
     * CRITICAL: Also validates that constructors used with CLOSED variants are valid.
     * If we see an invalid constructor for a closed variant, returns an error.
     */
    private fun inferVariantTypes(constraints: List<Constraint>): Pair<List<Constraint.Equality>, List<String>> {
        // Group HasConstructor constraints by their variant type
        // Also collect payload types for creating anonymous variants
        val constructorsByType = mutableMapOf<MezzeType, MutableMap<String, MezzeType?>>()

        for (constraint in constraints) {
            if (constraint is Constraint.HasConstructor) {
                val variantType = constraint.variantType
                // Only infer for type variables
                if (variantType is MezzeType.VarType) {
                    constructorsByType
                        .getOrPut(variantType) { mutableMapOf() }[constraint.constructorName] = constraint.payloadType
                }
            }
        }

        // For each type variable with constructors, find matching variant type
        val inferredEqualities = mutableListOf<Constraint.Equality>()
        val errors = mutableListOf<String>()

        for ((typeVar, constructorsWithPayloads) in constructorsByType) {
            val constructorNames = constructorsWithPayloads.keys
            // Find variant type declaration that has ALL these constructors
            val result = findVariantWithConstructors(constructorNames, constructorsWithPayloads)

            when {
                result is VariantInferenceResult.Success -> {
                    // Successfully inferred variant type
                    inferredEqualities.add(
                        Constraint.Equality(
                            left = typeVar,
                            right = result.variantType,
                            context = "Inferred from constructors: ${constructorNames.joinToString()}"
                        )
                    )
                    // Add payload type constraints to specialize type parameters
                    inferredEqualities.addAll(result.payloadConstraints)
                }
                result is VariantInferenceResult.Failure -> {
                    // Found a closed variant with invalid constructors - record error!
                    errors.add(result.errorMessage)
                }
                result == null -> {
                    // No matching variant found - this is an anonymous/structural variant
                    // Don't create the variant here - let HasConstructor constraints handle it
                    // during phase 3 solving. This ensures the payload types are properly resolved
                    // first (via HasField constraints).
                    // The HasConstructor solver will create/extend an open variant as needed.
                }
            }
        }

        return Pair(inferredEqualities, errors)
    }

    /**
     * Result of variant type inference from constructors.
     */
    private sealed class VariantInferenceResult {
        data class Success(
            val variantType: MezzeType,
            val payloadConstraints: List<Constraint.Equality> = emptyList()
        ) : VariantInferenceResult()
        data class Failure(val errorMessage: String) : VariantInferenceResult()
    }

    /**
     * Find a variant type declaration that matches the given constructors.
     *
     * For CLOSED variants: All constructors must match exactly (no extra constructors allowed)
     * For OPEN variants: At least some constructors must match (extra constructors are OK)
     *
     * Returns:
     * - VariantInferenceResult.Success: Found a matching variant
     * - VariantInferenceResult.Failure: Found a closed variant with invalid constructors (ERROR!)
     * - null: No matching variant found (anonymous/structural variant)
     *
     * CRITICAL: This function validates closed variants!
     * For `type Color = | Red, Blue, Green |`, seeing `{Red, Blue, Pink}` will return Failure
     * because Pink is not in the closed variant's constructor list.
     */
    private fun findVariantWithConstructors(
        constructors: Set<String>,
        constructorPayloads: Map<String, MezzeType?> = emptyMap()
    ): VariantInferenceResult? {
        for ((typeName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is VariantDefinition) {
                // Get constructor names from definition
                val declaredConstructors = definition.constructors.map { it.name }.toSet()

                // Check if SOME constructors match (needed for both open and closed)
                val matchingConstructors = constructors.intersect(declaredConstructors)

                if (matchingConstructors.isEmpty()) {
                    // No constructors match - skip this variant
                    continue
                }

                // Create fresh type variables for type parameters
                val typeParams = typeDecl.typeParams
                val freshArgs = typeParams.map { MezzeType.VarType(TypeVar.fresh()) }
                val typeParamSubst = typeParams.zip(freshArgs).toMap()

                // Generate payload constraints to specialize type parameters
                val payloadConstraints = mutableListOf<Constraint.Equality>()
                for (constructorDef in definition.constructors) {
                    val actualPayload = constructorPayloads[constructorDef.name]
                    val declaredPayload = constructorDef.dataType?.let { convertTypeAnnotation(it, typeParamSubst) }

                    if (actualPayload != null && declaredPayload != null) {
                        payloadConstraints.add(
                            Constraint.Equality(
                                left = actualPayload,
                                right = declaredPayload,
                                context = "Constructor '${constructorDef.name}' payload type"
                            )
                        )
                    }
                }

                if (definition.isOpen) {
                    // OPEN variant: Accept if SOME constructors match
                    val variantType = if (freshArgs.isNotEmpty()) {
                        MezzeType.TypeApplication(typeName, freshArgs)
                    } else {
                        MezzeType.TypeApplication(typeName, emptyList())
                    }
                    return VariantInferenceResult.Success(variantType, payloadConstraints)
                } else {
                    // CLOSED variant: ALL constructors must be in the declaration
                    val invalidConstructors = constructors - declaredConstructors

                    if (invalidConstructors.isNotEmpty()) {
                        // Found invalid constructors for this closed variant - ERROR!
                        val errorMsg = "Constructor(s) ${invalidConstructors.joinToString()} not found in closed variant '$typeName'. " +
                                      "Available constructors: ${declaredConstructors.joinToString()}"
                        return VariantInferenceResult.Failure(errorMsg)
                    }

                    // All constructors match - this is the right variant
                    val variantType = if (freshArgs.isNotEmpty()) {
                        MezzeType.TypeApplication(typeName, freshArgs)
                    } else {
                        // Non-parametric variant type
                        MezzeType.TypeApplication(typeName, emptyList())
                    }
                    return VariantInferenceResult.Success(variantType, payloadConstraints)
                }
            }
        }

        // No matching variant found - this is OK (anonymous/structural variant)
        return null
    }

    /**
     * Solve a list of constraints.
     *
     * Returns Success with substitution, or Failure with errors.
     *
     * CRITICAL: Solve constraints in phases to ensure type variables are bound before checking properties:
     * 1. Equality constraints (unification) - bind type variables
     * 2. Structural constraints (HasField, HasConstructor) - check properties after binding
     */
    fun solve(constraints: List<Constraint>): SolveResult {
        var substitution = Substitution.empty
        val errors = mutableListOf<ConstraintError>()
        val warnings = mutableListOf<String>()
        val resolvedAbilities = mutableSetOf<Constraint.AbilityRequired>()

        // PHASE 0: Infer variant types from HasConstructor constraints
        // Before solving, look at HasConstructor constraints on type variables and
        // infer which variant type they should be, then add Equality constraints
        // CRITICAL: This also validates closed variants!
        val (inferredEqualities, inferenceErrors) = inferVariantTypes(constraints)

        // If variant inference found invalid constructors in closed variants, fail immediately
        for (errorMsg in inferenceErrors) {
            errors.add(
                ConstraintError(
                    message = errorMsg,
                    constraint = constraints.first(),  // Use first constraint as context
                    context = "Variant type inference"
                )
            )
        }

        val allConstraints = constraints + inferredEqualities

        // PHASE 0.5: Process MustBeOpen constraints to track which types must stay open
        val (mustBeOpenConstraints, constraintsAfterOpen) = allConstraints.partition { it is Constraint.MustBeOpen }
        for (constraint in mustBeOpenConstraints) {
            val result = solveConstraint(constraint, substitution)
            // MustBeOpen constraints don't fail or produce substitutions
        }

        // PHASE 1: Process AbilityRequired constraints FIRST to track ability constraints on type variables
        // This is CRITICAL: we need to track which abilities are required on which type variables
        // BEFORE we solve Equality constraints that bind those type variables.
        val (abilityConstraints, nonAbilityConstraints) = constraintsAfterOpen.partition { it is Constraint.AbilityRequired }

        for (constraint in abilityConstraints) {
            // Apply current substitution to constraint before solving
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)

            // Solve the constraint (this tracks the ability constraints on type variables)
            val result = solveConstraint(appliedConstraint, substitution)

            when (result) {
                is ConstraintResult.Success -> {
                    // Compose new substitution with existing one (usually empty for abilities)
                    substitution = result.substitution.compose(substitution)
                    warnings.addAll(result.warnings)

                    // Track resolved ability constraints (concrete types with implementations)
                    if (appliedConstraint is Constraint.AbilityRequired) {
                        // If constraint was satisfied (no warnings about deferring), track it as resolved
                        if (result.warnings.none { it.contains("deferred") }) {
                            resolvedAbilities.add(appliedConstraint)
                        }
                    }
                }
                is ConstraintResult.Failure -> {
                    // Collect error and continue (to find all errors)
                    errors.add(
                        ConstraintError(
                            message = result.message,
                            constraint = appliedConstraint,
                            context = appliedConstraint.getContext()
                        )
                    )
                }
                is ConstraintResult.Deferred -> {
                    // Ability constraints shouldn't be deferred, but handle it gracefully
                }
            }
        }

        // PHASE 2: Solve all constraints using a worklist algorithm
        // We iterate until no more progress can be made. This is necessary because:
        // - Equality constraints bind type variables
        // - HasConstructor/HasField constraints establish type relationships
        // - These may depend on each other in arbitrary order
        //
        // For example, in `match opt is Some { val } -> val`:
        // - HasConstructor establishes that val's type comes from Option's payload
        // - Equality constraints unify case result types with the match result
        // Both need to be solved together for correct type checking.

        // PHASE 2a: First solve all HasConstructor constraints to collect variant constructors
        // This allows pattern matching to accumulate all constructors before we close the variants
        val hasConstructorConstraints = nonAbilityConstraints.filterIsInstance<Constraint.HasConstructor>()
        val otherConstraints = nonAbilityConstraints.filter { it !is Constraint.HasConstructor }

        // Solve HasConstructor constraints first
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

                val result = solveHasConstructor(appliedConstraint, substitution, constraint)

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
                        // Check if constraint might succeed later
                        val constraintType = appliedConstraint.variantType
                        if (constraintType is MezzeType.VarType) {
                            // Still a type variable - might be resolved later
                        } else {
                            // Concrete type - this is a real error
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
                        // HasConstructor constraints shouldn't be deferred, but handle gracefully
                    }
                }
            }
        }

        // PHASE 2b: Resolve MustBeOpen types to their row vars AFTER HasConstructor constraints are solved
        // This is necessary because MustBeOpen constraints refer to type variables, and the row vars
        // are only created during HasConstructor constraint solving
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

        // PHASE 2c: Close pattern-based variants now that all constructors are collected
        // Pattern-based row vars should be removed (set to null) to make the variants closed
        // UNLESS they're in mustBeOpenRowVars (due to wildcard patterns)
        substitution = closePatternBasedVariants(substitution)

        // PHASE 2c: Solve remaining constraints (Equality, HasField, etc.)
        // OPTIMIZATION: Sort constraints so Equality constraints come first.
        // Equality constraints are simpler and faster to solve, and they bind type variables
        // that other constraints depend on. Processing them first reduces iterations.
        val sortedConstraints = (hasConstructorWorklist + otherConstraints).sortedBy { constraint ->
            when (constraint) {
                is Constraint.Equality -> 0  // Highest priority
                is Constraint.HasField -> 1
                is Constraint.HasConstructor -> 2
                is Constraint.AbilityRequired -> 3
                else -> 4
            }
        }
        val constraintWorklist = sortedConstraints.toMutableList()
        val processedInThisPass = mutableSetOf<Constraint>()
        var madeProgress = true
        var iterationCount = 0
        val maxIterations = 100  // Safety limit

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

                // Apply current substitution to constraint before solving
                val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)

                // Solve the constraint
                val result = when (constraint) {
                    is Constraint.HasConstructor -> solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                    else -> solveConstraint(appliedConstraint, substitution)
                }

                when (result) {
                    is ConstraintResult.Success -> {
                        val newSubst = result.substitution
                        if (newSubst.mappings.isNotEmpty()) {
                            // Made progress - new substitution bindings
                            substitution = newSubst.compose(substitution)
                            madeProgress = true
                        }
                        warnings.addAll(result.warnings)

                        // Handle variant merging for match expressions (Equality constraints only)
                        if (constraint is Constraint.Equality) {
                            val originalConstraint = constraint
                            val appliedEq = appliedConstraint as Constraint.Equality
                            val leftType = substitution.apply(appliedEq.left)
                            val rightType = substitution.apply(appliedEq.right)

                            if (leftType is MezzeType.VariantType && rightType is MezzeType.VariantType) {
                                val leftCtors = leftType.constructors.keys
                                val rightCtors = rightType.constructors.keys

                                if (leftCtors != rightCtors) {
                                    // CRITICAL: Only merge variants in match expression contexts
                                    // For other contexts (like let bindings with explicit type annotations),
                                    // don't merge - let unification fail properly if types don't match.
                                    //
                                    // Match case contexts have "Match case" in the context string.
                                    val isMatchContext = originalConstraint.context?.contains("Match case") == true

                                    // Also allow merging if BOTH variants are open (have row variables)
                                    // AND neither is a pattern-based variant (which should be closed)
                                    val bothOpen = leftType.rowVar != null && rightType.rowVar != null
                                    val eitherPatternBased = (leftType.rowVar != null && patternBasedRowVars.contains(leftType.rowVar)) ||
                                                             (rightType.rowVar != null && patternBasedRowVars.contains(rightType.rowVar))

                                    // Don't merge if either variant is pattern-based - those should be treated as closed
                                    if (isMatchContext || (bothOpen && !eitherPatternBased)) {
                                        // Merge variants
                                        val mergedConstructors = leftType.constructors.toMutableMap()
                                        for ((name, dataType) in rightType.constructors) {
                                            if (name !in mergedConstructors) {
                                                mergedConstructors[name] = dataType
                                            }
                                        }

                                        val mergedRowVar = leftType.rowVar ?: rightType.rowVar
                                        val mergedVariant = MezzeType.VariantType(
                                            constructors = mergedConstructors,
                                            rowVar = mergedRowVar
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
                                    // If not a match context and not both open, don't merge -
                                    // unification should handle the error via normal type checking
                                }
                            }
                        }

                        // Constraint solved successfully - remove from worklist
                        iterator.remove()
                        processedInThisPass.add(constraint)
                    }
                    is ConstraintResult.Failure -> {
                        // Check if this constraint might succeed with more information
                        // For type variable constraints, we might need to wait for more bindings
                        val constraintType = when (appliedConstraint) {
                            is Constraint.HasField -> appliedConstraint.recordType
                            is Constraint.HasConstructor -> appliedConstraint.variantType
                            else -> null
                        }

                        if (constraintType is MezzeType.VarType) {
                            // Keep in worklist - might resolve later
                        } else {
                            // This is a real error - collect it
                            errors.add(
                                ConstraintError(
                                    message = result.message,
                                    constraint = appliedConstraint,
                                    context = appliedConstraint.getContext()
                                )
                            )
                            iterator.remove()
                        }
                    }
                    is ConstraintResult.Deferred -> {
                        // Constraint needs more information - keep in worklist for later
                        // Don't count as progress, just leave it for next iteration
                    }
                }
            }
        }

        // Any remaining constraints that couldn't be solved are errors
        for (constraint in constraintWorklist) {
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)
            val result = when (constraint) {
                is Constraint.HasConstructor -> solveHasConstructor(appliedConstraint as Constraint.HasConstructor, substitution, constraint)
                else -> solveConstraint(appliedConstraint, substitution)
            }
            if (result is ConstraintResult.Failure) {
                errors.add(
                    ConstraintError(
                        message = result.message,
                        constraint = appliedConstraint,
                        context = appliedConstraint.getContext()
                    )
                )
            }
        }

        // Validate ability constraints with the FULL accumulated substitution
        val validationResult = validateAbilityConstraints(substitution)
        if (validationResult != null) {
            val (validationError, sourceLocation) = validationResult
            errors.add(
                ConstraintError(
                    message = validationError,
                    constraint = Constraint.Equality(MezzeType.IntType, MezzeType.IntType, sourceLocation = sourceLocation),
                    context = "ability constraint validation"
                )
            )
        }

        // Apply deferred inferences from bidirectional ability inference
        // These are types inferred from multi-parameter ability implementations
        if (deferredInference.isNotEmpty()) {
            val deferredSubst = Substitution(deferredInference.toMap())
            substitution = deferredSubst.compose(substitution)
        }

        // Before returning, resolve mustBeOpenTypes to their row variables
        // For each type variable marked as must-be-open, if it resolves to a variant with a row var,
        // track that row var so it won't be closed
        for (typeVar in mustBeOpenTypes) {
            val resolvedType = substitution.apply(MezzeType.VarType(typeVar))
            if (resolvedType is MezzeType.VariantType) {
                resolvedType.rowVar?.let { mustBeOpenRowVars.add(it) }
            }
        }

        // Return result
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

    /**
     * Result of solving a single constraint.
     */
    private sealed class ConstraintResult {
        data class Success(
            val substitution: Substitution,
            val warnings: List<String> = emptyList()
        ) : ConstraintResult()

        data class Failure(val message: String) : ConstraintResult()

        /** Constraint couldn't be solved yet - put back on worklist */
        data class Deferred(val reason: String) : ConstraintResult()
    }

    /**
     * Solve a single constraint.
     *
     * Exhaustive when on Constraint sealed interface.
     */
    private fun solveConstraint(constraint: Constraint, currentSubst: Substitution): ConstraintResult {
        return when (constraint) {
            is Constraint.Equality -> solveEquality(constraint)
            is Constraint.AbilityRequired -> solveAbilityRequired(constraint)
            is Constraint.EffectUsed -> solveEffectUsed(constraint)
            is Constraint.Subsumes -> solveSubsumes(constraint)
            is Constraint.HasField -> solveHasField(constraint, currentSubst)
            is Constraint.HasConstructor -> solveHasConstructor(constraint, currentSubst)
            is Constraint.MustBeOpen -> solveMustBeOpen(constraint, currentSubst)
            is Constraint.RecordPatternExhaustive -> solveRecordPatternExhaustive(constraint, currentSubst)
        }
    }

    /**
     * Solve an equality constraint: t1 = t2
     *
     * Uses unification to find substitution.
     * Note: Ability constraint validation is done AFTER all equality constraints are solved,
     * so we have access to the full accumulated substitution.
     */
    private fun solveEquality(constraint: Constraint.Equality): ConstraintResult {
        DebugLogger.debug(Category.ConstraintSolver) {
            "solveEquality: ${constraint.left} = ${constraint.right} (${constraint.context})"
        }

        val result = unify(constraint.left, constraint.right, typeDeclarations)

        DebugLogger.debug(Category.ConstraintSolver) { "  result: $result" }

        return when (result) {
            is UnificationResult.Success -> {
                // Don't validate ability constraints here - we need the full accumulated substitution
                // Validation happens after all equality constraints are solved in solve()
                ConstraintResult.Success(result.substitution)
            }
            is UnificationResult.Failure -> {
                // Detect literal vs variant mismatch for better error message
                val constraintContext = constraint.context ?: ""
                val type1 = constraint.left
                val type2 = constraint.right

                if (constraintContext.contains("Literal pattern")) {
                    // Check if one side is a variant type
                    val variantType = when {
                        type1 is MezzeType.VariantType -> type1
                        type2 is MezzeType.VariantType -> type2
                        else -> null
                    }

                    if (variantType != null) {
                        val constructors = variantType.constructors.keys.sorted()
                        val firstConstructor = constructors.firstOrNull() ?: "ConstructorName"
                        return ConstraintResult.Failure(
                            "Cannot match variant type [${constructors.joinToString(", ")}] with a literal pattern. " +
                            "Use a constructor pattern like '$firstConstructor' instead."
                        )
                    }

                    // Check for TypeApplication (named type like Option, Result)
                    val typeApp = when {
                        type1 is MezzeType.TypeApplication -> type1
                        type2 is MezzeType.TypeApplication -> type2
                        else -> null
                    }

                    if (typeApp != null) {
                        return ConstraintResult.Failure(
                            "Cannot match type '${typeApp.constructor}' with a literal pattern. " +
                            "Use a constructor pattern instead."
                        )
                    }
                }

                // Check for List vs Array confusion
                if (constraintContext.contains("Array pattern")) {
                    if (type1 is MezzeType.ListType || type2 is MezzeType.ListType) {
                        return ConstraintResult.Failure(
                            "Cannot use array pattern syntax [a, b, ...] with List type. " +
                            "Use constructor patterns 'Cons { head, tail }' and 'Nil' instead."
                        )
                    }
                }

                ConstraintResult.Failure(result.message)
            }
        }
    }

    /**
     * Validate that all ability constraints on type variables are satisfied by the substitution.
     *
     * When a type variable 'a with ability constraints is unified with a concrete type Int,
     * we check that Int has implementations for all abilities required by 'a.
     *
     * Returns (error message, sourceLocation) pair if validation fails, null if all constraints are satisfied.
     * Also returns a substitution for any types inferred through bidirectional inference.
     */
    private fun validateAbilityConstraints(substitution: Substitution): Pair<String, SourceLocation?>? {
        for ((typeVar, abilityConstraints) in typeVarAbilityConstraints) {
            // Check if this type variable was bound to a concrete type
            // Use substitution.apply() to transitively resolve type variable chains
            val varType = MezzeType.VarType(typeVar)
            val boundType = substitution.apply(varType)

            // If the type variable is still a VarType (possibly different), it's not concrete yet
            if (boundType !is MezzeType.VarType) {
                // Type variable was bound to a concrete type - validate abilities
                for ((abilityName, typeArgs, sourceLocation) in abilityConstraints) {
                    // Skip Inspect - it's universally available (already filtered in solveAbilityRequired, but double-check)
                    if (abilityName == "Inspect") continue
                    // Substitute the type variable in typeArgs with the bound type
                    val concreteTypeArgs: List<MezzeType> = typeArgs.map { typeArg ->
                        if (typeArg is MezzeType.VarType && typeArg.variable == typeVar) {
                            boundType
                        } else {
                            substitution.apply(typeArg)
                        }
                    }

                    // For codec abilities with open record types, close them for generation
                    // This enables bidirectional inference from field access: val.name tells us
                    // the record has a "name" field, and we close the record to generate the codec.
                    val closedTypeArgs = if (isCodecAbility(abilityName)) {
                        concreteTypeArgs.map { closeOpenRecordsForCodec(it) }
                    } else {
                        concreteTypeArgs
                    }

                    // Check if all types are now concrete
                    val allConcrete = closedTypeArgs.all { isConcrete(it) }

                    if (allConcrete) {
                        // Check if implementation exists
                        val hasImpl = checkAbilityImplementation(abilityName, closedTypeArgs)
                        if (!hasImpl) {
                            return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs), sourceLocation)
                        }
                    } else {
                        // Some types are still type variables - try bidirectional inference!
                        // This is key for multi-parameter abilities like Convert 'a 'b
                        // When 'a = Int, we can look for impl Convert Int String and infer 'b = String
                        val inferredSubst = tryBidirectionalInference(abilityName, closedTypeArgs)
                        if (inferredSubst != null) {
                            // Store the inferred substitution for later application
                            deferredInference.putAll(inferredSubst.mappings)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Solve an ability constraint: typeVar needs ability
     *
     * Checks if the type has an implementation for the required ability.
     * For concrete types (Int, String, etc.), verify implementation exists.
     * For type variables, track the constraint for later validation during unification.
     */
    private fun solveAbilityRequired(constraint: Constraint.AbilityRequired): ConstraintResult {
        val abilityName = constraint.abilityName
        val typeArgs = constraint.typeArgs

        // CRITICAL: Filter out Inspect - it's universally available and shouldn't constrain types
        // This prevents multiInspect from incorrectly unifying {a: 'a, b: 'b, c: 'c} to {a: 'a, b: 'a, c: 'a}
        // See OldTypeChecker.kt:2651-2652
        if (abilityName == "Inspect") {
            // Inspect is universally available - no constraint needed
            return ConstraintResult.Success(Substitution.empty)
        }

        // CRITICAL: Track ability constraints on type variables!
        // When we see AbilityRequired('a, Parse, [...]), we store this constraint
        // so that later when 'a = Int is unified, we can validate Int has Parse implementation.
        //
        // Build full list of type arguments for the ability
        // For Convert 'a 'b, the constraint has typeVar='a and typeArgs=['b]
        // We need trackingArgs = ['a, 'b] for proper matching against implementations
        val trackingArgs = listOf(MezzeType.VarType(constraint.typeVar)) + typeArgs
        typeVarAbilityConstraints.getOrPut(constraint.typeVar) { mutableListOf() }
            .add(Triple(abilityName, trackingArgs, constraint.sourceLocation))

        // Also track for any type variables in typeArgs
        // CRITICAL: Use trackingArgs (which includes ALL type arguments), not just typeArgs
        // Otherwise, when 'to is resolved in Convert 'from 'to, we'd only check ['to] instead of ['from, 'to]
        for (typeArg in typeArgs) {
            if (typeArg is MezzeType.VarType && typeArg.variable != constraint.typeVar) {
                val constraints = typeVarAbilityConstraints.getOrPut(typeArg.variable) { mutableListOf() }
                constraints.add(Triple(abilityName, trackingArgs, constraint.sourceLocation))
            }
        }

        // Apply substitution to type arguments to get concrete types if available
        // If all type args are concrete (not type variables), we can check the implementation now
        val allConcrete = trackingArgs.all { isConcrete(it) }

        if (allConcrete) {
            // All types are concrete - check if implementation exists
            val hasImpl = checkAbilityImplementation(abilityName, trackingArgs)

            if (!hasImpl) {
                // No implementation found - this is a type error
                // Generate better error messages for specific cases
                val errorMessage = generateAbilityErrorMessage(abilityName, trackingArgs)
                return ConstraintResult.Failure(errorMessage)
            }

            // Implementation exists - constraint is satisfied
            // Return success without warnings (constraint is resolved)
            return ConstraintResult.Success(Substitution.empty)
        } else {
            // Some types are still type variables
            // DON'T do bidirectional inference here! We need to wait until all equality
            // constraints are solved to know the actual types. Otherwise, we might
            // prematurely infer 'a = Int when the usage site wants 'a = Float.
            //
            // Bidirectional inference is only done in validateAbilityConstraints()
            // AFTER all equality constraints are solved.
            // Ability constraint deferred - will be validated later after all equality constraints are solved
            return ConstraintResult.Success(Substitution.empty)
        }
    }

    /**
     * Check if an ability implementation exists for the given types.
     *
     * Looks up implementation in context using the pattern: "AbilityName_Type1_Type2_..."
     * Also checks for built-in ability implementations.
     */
    private fun checkAbilityImplementation(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        // Check if the user has defined their own version of this ability.
        // If so, the user-defined ability SHADOWS the built-in one, and we must NOT
        // use the built-in implementation. The user must provide an explicit impl.
        val isUserDefinedAbility = abilityDeclarations.containsKey(abilityName)

        // Check built-in abilities ONLY if there's no user-defined ability with the same name
        if (!isUserDefinedAbility && typeArgs.size == 1) {
            if (isBuiltInAbilityImplementation(abilityName, typeArgs.first())) {
                return true
            }
        }

        // Check codec abilities (Encode, Decode, StreamEncode, StreamDecode)
        // These take two type arguments: format type and data type
        if (typeArgs.size == 2 && isCodecAbility(abilityName)) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]
            if (checkCodecAbilityImplementation(abilityName, formatType, dataType)) {
                return true
            }
        }

        // Search for implementation by ability name and matching types
        // Context is now keyed by content hash, so we search by ability name and match types
        val typeNames = typeArgs.map { getTypeName(it) }

        // Build alternative type names for types that might match by alias
        val alternativeTypeNames = typeArgs.mapIndexed { index, type ->
            when {
                // For variant types without a typeName, try constructor names
                type is MezzeType.VariantType && type.typeName == null -> {
                    type.constructors.keys.toList()
                }
                // For record types without a typeName, check if any type alias matches
                type is MezzeType.RecordType && type.typeName == null -> {
                    val matchingAliases = findMatchingRecordTypeAliases(type)
                    if (matchingAliases.isNotEmpty()) {
                        listOf(typeNames[index]) + matchingAliases
                    } else {
                        listOf(typeNames[index])
                    }
                }
                else -> listOf(typeNames[index])
            }
        }

        val impls = context.getAbilityImpls(abilityName)

        val hasImpl = impls.any { implInfo ->
            // Check exact match first
            if (implInfo.targetTypes == typeNames) return@any true

            // Check if any combination of alternative names matches
            if (alternativeTypeNames.all { it.size == 1 }) {
                // No alternatives, already checked above
                false
            } else {
                // Check all combinations
                matchesWithAlternatives(implInfo.targetTypes, alternativeTypeNames)
            }
        }

        return hasImpl
    }

    /**
     * Check if impl target types match with alternatives.
     * For each position, checks if any of the alternative names match.
     */
    private fun matchesWithAlternatives(
        implTargetTypes: List<String>,
        alternativeTypeNames: List<List<String>>
    ): Boolean {
        if (implTargetTypes.size != alternativeTypeNames.size) return false

        return implTargetTypes.zip(alternativeTypeNames).all { (implType, alternatives) ->
            alternatives.contains(implType)
        }
    }

    /**
     * Find type aliases that match the given record type structure.
     *
     * When a record literal `{ name = "Alice" }` is created without a type annotation,
     * it has no typeName. But if there's a type alias `Person = { name: String }` in scope,
     * we should be able to find impls for `Person`.
     */
    private fun findMatchingRecordTypeAliases(recordType: MezzeType.RecordType): List<String> {
        val matchingAliases = mutableListOf<String>()

        for ((aliasName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is RecordDefinition) {
                // Check if fields match
                val aliasFields = definition.fields.associate { it.name to it.type }

                // Same number of fields?
                if (aliasFields.size != recordType.fields.size) continue

                // Same field names?
                if (aliasFields.keys != recordType.fields.keys) continue

                // For now, just check field names match (not full type compatibility)
                // Full type checking would require recursive unification
                matchingAliases.add(aliasName)
            }
        }

        return matchingAliases
    }

    /**
     * Check if an ability name is a codec ability.
     */
    private fun isCodecAbility(abilityName: String): Boolean {
        return abilityName in listOf("Encode", "Decode", "StreamEncode", "StreamDecode")
    }

    /**
     * Check and possibly generate a codec ability implementation.
     *
     * Returns true if the data type is serializable and an implementation
     * can be auto-derived for the given format type.
     */
    private fun checkCodecAbilityImplementation(
        abilityName: String,
        formatType: MezzeType,
        dataType: MezzeType
    ): Boolean {
        // Check if the data type is serializable
        if (!codecGenerator.isSerializable(dataType)) {
            return false
        }

        // Check if the types contain any unresolved type variables
        // We can't generate code for unknown types
        if (containsVarType(formatType) || containsVarType(dataType)) {
            return false
        }

        // Generate the implementation based on ability type
        val generatedImpl = when (abilityName) {
            "Encode" -> codecGenerator.generateEncodeImpl(formatType, dataType)
            "Decode" -> codecGenerator.generateDecodeImpl(formatType, dataType)
            "StreamEncode" -> codecGenerator.generateStreamEncodeImpl(formatType, dataType)
            "StreamDecode" -> codecGenerator.generateStreamDecodeImpl(formatType, dataType)
            else -> return false
        }

        // Compute content hash and use it as the key
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(generatedImpl)
        val implWithHash = generatedImpl.copy(contentHash = hash.toFullHexString())
        val implKey = hash.toFullHexString()

        // Check if we've already generated this impl (same content = same hash)
        if (generatedCodecImpls.containsKey(implKey)) {
            return true
        }

        // Store the generated impl keyed by content hash
        generatedCodecImpls[implKey] = implWithHash
        return true
    }

    /**
     * Check if a type contains any unresolved type variables.
     */
    private fun containsVarType(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType -> true
            is MezzeType.RecordType -> type.fields.values.any { containsVarType(it) }
            is MezzeType.VariantType -> type.constructors.values.any { it != null && containsVarType(it) }
            is MezzeType.ListType -> containsVarType(type.elementType)
            is MezzeType.ArrayType -> containsVarType(type.elementType)
            is MezzeType.SetType -> containsVarType(type.elementType)
            is MezzeType.SortedSetType -> containsVarType(type.elementType)
            is MezzeType.MapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is MezzeType.SortedMapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is MezzeType.TypeApplication -> type.args.any { containsVarType(it) }
            is MezzeType.FunctionType -> containsVarType(type.paramType) || containsVarType(type.returnType)
            else -> false
        }
    }

    /**
     * Close open record types for codec generation.
     *
     * When inferring types from field access (e.g., val.name), we get open records
     * with row variables. For codec generation, we close these records (remove the
     * row variable) so they can be treated as concrete types.
     *
     * This enables bidirectional type inference for Decode without explicit annotation:
     * - `let user = Decode.decode {...}` returns Result 'a DecodeError
     * - `match user is Ok { val } -> val.name` constrains 'a to {name: 'b, ...}
     * - We close this to {name: String} (after field type resolution) for codec generation
     */
    private fun closeOpenRecordsForCodec(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.RecordType -> {
                // Close the record (remove row variable) and recursively close nested types
                val closedFields = type.fields.mapValues { (_, fieldType) ->
                    closeOpenRecordsForCodec(fieldType)
                }
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.TypeApplication -> {
                // For Result {name: String} DecodeError, close nested records in args
                val closedArgs = type.args.map { closeOpenRecordsForCodec(it) }
                MezzeType.TypeApplication(type.constructor, closedArgs)
            }
            is MezzeType.VariantType -> {
                // Close records in variant payloads
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecordsForCodec(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.ListType -> MezzeType.ListType(closeOpenRecordsForCodec(type.elementType))
            is MezzeType.ArrayType -> MezzeType.ArrayType(closeOpenRecordsForCodec(type.elementType))
            is MezzeType.SetType -> MezzeType.SetType(closeOpenRecordsForCodec(type.elementType))
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(closeOpenRecordsForCodec(type.elementType))
            is MezzeType.MapType -> MezzeType.MapType(
                closeOpenRecordsForCodec(type.keyType),
                closeOpenRecordsForCodec(type.valueType)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                closeOpenRecordsForCodec(type.keyType),
                closeOpenRecordsForCodec(type.valueType)
            )
            else -> type
        }
    }

    /**
     * Get all generated codec implementations.
     *
     * Called after constraint solving to retrieve auto-derived implementations
     * that need to be added to the program.
     */
    fun getGeneratedCodecImpls(): Map<String, ImplAbilityDeclaration> {
        return generatedCodecImpls.toMap()
    }

    /**
     * Check if this is a built-in ability with built-in implementation.
     *
     * Built-in abilities have implicit implementations for certain types:
     * - NumAdd, NumMul, NumDiv, NumMod: Int, Float, Nat, Frac
     * - NumSub, NumNeg: Int, Float, Frac (NOT Nat - subtraction can go negative)
     * - Eq: Int, Float, String, Bool, Nat, Frac, Ordering
     * - Ord: Int, Float, String, Nat, Frac
     * - Inspect: All primitive types
     */
    private fun isBuiltInAbilityImplementation(abilityName: String, type: MezzeType): Boolean {
        // Unwrap SuspendedType to check the inner value type
        val unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        return when (abilityName) {
            // Numeric abilities that work with Nat
            "NumAdd", "NumMul", "NumDiv", "NumMod" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType
            }
            // NumSub and NumNeg don't work with Nat (result can be negative)
            "NumSub", "NumNeg" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.FracType
            }

            // Equality - implemented for most primitive types and derivable for records/variants/lists
            "Eq" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.BooleanType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType ||
                unwrappedType is MezzeType.OrderingType ||
                unwrappedType is MezzeType.BytesType ||
                unwrappedType is MezzeType.ByteBufferType ||
                isBoolVariant(unwrappedType) ||
                // Derive Eq for records if all fields have Eq
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { isBuiltInAbilityImplementation("Eq", it) }) ||
                // Derive Eq for variants if all constructor payloads have Eq (or no payload)
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || isBuiltInAbilityImplementation("Eq", it) }) ||
                // Derive Eq for lists if element type has Eq
                (unwrappedType is MezzeType.ListType && isBuiltInAbilityImplementation("Eq", unwrappedType.elementType)) ||
                // Derive Eq for arrays if element type has Eq
                (unwrappedType is MezzeType.ArrayType && isBuiltInAbilityImplementation("Eq", unwrappedType.elementType))
            }

            // Ordering - implemented for comparable types and derivable for records/variants/lists
            "Ord" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType ||
                // Derive Ord for records if all fields have Ord
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { isBuiltInAbilityImplementation("Ord", it) }) ||
                // Derive Ord for variants if all constructor payloads have Ord (or no payload)
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || isBuiltInAbilityImplementation("Ord", it) }) ||
                // Derive Ord for lists if element type has Ord
                (unwrappedType is MezzeType.ListType && isBuiltInAbilityImplementation("Ord", unwrappedType.elementType)) ||
                // Derive Ord for arrays if element type has Ord
                (unwrappedType is MezzeType.ArrayType && isBuiltInAbilityImplementation("Ord", unwrappedType.elementType))
            }

            // Inspect - built-in ability for displaying values
            "Inspect" -> true  // All types can be inspected

            else -> false
        }
    }

    /**
     * Check if a type is the Bool variant type.
     */
    /**
     * Generate a user-friendly error message for ability constraint failures.
     * Provides specific messages for common cases like arithmetic with function types.
     */
    private fun generateAbilityErrorMessage(abilityName: String, typeArgs: List<MezzeType>): String {
        // Check if any type argument is a function type
        val hasFunctionType = typeArgs.any { it is MezzeType.FunctionType }
        val typeNames = typeArgs.map { formatType(it) }.joinToString(", ")

        // Get the primary type for impl suggestion
        val primaryType = typeArgs.firstOrNull()?.let { formatType(it) } ?: typeNames

        if (hasFunctionType) {
            // Functions cannot implement abilities
            val operatorName = when (abilityName) {
                "NumAdd" -> "+"
                "NumSub" -> "-"
                "NumMul" -> "*"
                "NumDiv" -> "/"
                "NumMod" -> "%"
                "NumNeg" -> "-"
                else -> null
            }

            return if (operatorName != null) {
                "Cannot use '$operatorName' with a function. Functions cannot implement $abilityName."
            } else {
                "Functions cannot implement '$abilityName'."
            }
        }

        // Provide helpful messages with impl suggestion
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod" -> {
                val operator = when (abilityName) {
                    "NumAdd" -> "+"
                    "NumSub" -> "-"
                    "NumMul" -> "*"
                    "NumDiv" -> "/"
                    "NumMod" -> "%"
                    else -> abilityName
                }
                "Cannot use '$operator' with $typeNames. To enable this, implement: impl $abilityName $primaryType where { ... }"
            }
            "Eq" -> "Cannot compare $typeNames for equality. To enable this, implement: impl Eq $primaryType where { ... }"
            "Ord" -> "Cannot compare $typeNames for ordering. To enable this, implement: impl Ord $primaryType where { ... } (requires Eq)"
            "Show" -> "Cannot convert $typeNames to String. To enable this, implement: impl Show $primaryType where { ... }"
            "Hash" -> "Cannot hash $typeNames. To enable this, implement: impl Hash $primaryType where { ... }"
            "Default" -> "No default value for $typeNames. To enable this, implement: impl Default $primaryType where { ... }"
            "Parse" -> "Cannot parse into $typeNames. To enable this, implement: impl Parse $primaryType where { ... }"
            "Convert" -> "Cannot convert between the given types. To enable this, implement: impl Convert $typeNames where { ... }"
            "Decode" -> {
                // Special handling for Decode - check if the data type is a type variable
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type for Decode.decode call.
  The result type 'a in Result 'a DecodeError could not be determined from context.

  Hint: Add a type annotation to specify the expected type:
    let person: {name: String, age: Int} = json::decode {val = json}

  Or use the result in a way that constrains its type:
    let person = json::decode {val = json}
    person.name  # This would constrain person to have a 'name' field"""
                } else {
                    "Cannot decode $typeNames. Ensure the format implements DecodeFormat and the data type is serializable."
                }
            }
            "Encode" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    "Cannot infer encode source type for Encode.encode call. Add a type annotation to the value being encoded."
                } else {
                    "Cannot encode $typeNames. Ensure the format implements EncodeFormat and the data type is serializable."
                }
            }
            else -> "$typeNames does not implement '$abilityName'. To enable this, implement: impl $abilityName $primaryType where { ... }"
        }
    }

    private fun isBoolVariant(type: MezzeType): Boolean {
        return BuiltInAbilities.isBoolVariant(type)
    }

    /**
     * Check if a type is concrete (not a type variable).
     */
    private fun isConcrete(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BooleanType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> true
            is MezzeType.VariantType -> true
            is MezzeType.RecordType -> type.rowVar == null && type.fields.values.all { isConcrete(it) }
            is MezzeType.FunctionType -> true
            is MezzeType.TypeApplication -> type.args.all { it !is MezzeType.VarType }
            is MezzeType.VarType -> false
            else -> false
        }
    }

    /**
     * Get the type name for ability implementation lookup.
     */
    private fun getTypeName(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BooleanType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.OrderingType -> "Ordering"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.TypeApplication -> {
                // For "List Int" -> "List"
                // The full type would need all args: "List_Int"
                if (type.args.isEmpty()) {
                    type.constructor
                } else {
                    val argNames = type.args.map { getTypeName(it) }
                    "${type.constructor}_${argNames.joinToString("_")}"
                }
            }
            is MezzeType.ArrayType -> "Array_${getTypeName(type.elementType)}"
            is MezzeType.ListType -> "List_${getTypeName(type.elementType)}"
            is MezzeType.SetType -> "Set_${getTypeName(type.elementType)}"
            is MezzeType.SortedMapType -> "SortedMap_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet_${getTypeName(type.elementType)}"
            is MezzeType.MapType -> "Map_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"
            is MezzeType.VariantType -> {
                // Use typeName if available, otherwise check for Bool or use generated name
                type.typeName ?: if (isBoolVariant(type)) {
                    "Bool"
                } else if (type.constructors.size == 1) {
                    // Single constructor variant - likely a type alias, use constructor name
                    type.constructors.keys.first()
                } else {
                    // For other variant types, use a generated name
                    "Variant_${type.constructors.keys.sorted().joinToString("_")}"
                }
            }
            is MezzeType.RecordType -> {
                // Use typeName if available
                type.typeName ?: "Record"
            }
            is MezzeType.FunctionType -> "Function"
            is MezzeType.SuspendedType -> getTypeName(type.valueType)
            else -> type.toString()
        }
    }

    /**
     * Get the type name from a TypeAnnotation for ability implementation lookup.
     * This converts AST type annotations to string names for comparison.
     */
    private fun typeAnnotationToName(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name
            is TypeApplicationAnnotation -> {
                val args = annotation.args.map { typeAnnotationToName(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_${args.joinToString("_")}"
            }
            is FunctionTypeAnnotation -> "Function"
            is RecordTypeAnnotation -> {
                val fieldNames = annotation.entries.map { it.name }.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is VariantTypeAnnotation -> {
                val constructorNames = annotation.constructors.map { it.name }.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            is ArrayTypeAnnotation -> "Array_${typeAnnotationToName(annotation.elementType)}"
            is EffectRowAnnotation -> "EffectRow"
            is QualifiedTypeAnnotation -> {
                val args = annotation.args.map { typeAnnotationToName(it) }
                if (args.isEmpty()) annotation.typeName else "${annotation.typeName}_${args.joinToString("_")}"
            }
        }
    }

    /**
     * Try bidirectional inference for ability constraints with type variables.
     *
     * Example: NumAdd 'b Int => look for implementations NumAdd T Int
     *          If only NumAdd Int Int exists, infer 'b = Int
     *
     * This implements the key insight from the old typechecker (OldTypeChecker.kt:16246-16300):
     * When we have an ability constraint with some concrete types and some type variables,
     * we can look for matching implementations and infer the type variables.
     *
     * Special handling for Decode:
     * - For Decode Json 'a, we look at generated impls to find matching types
     * - If only one impl exists for the given format, we can infer the data type
     *
     * @param abilityName The ability name (e.g., "NumAdd")
     * @param typeArgs The type arguments (may contain type variables)
     * @return Substitution binding type variables to concrete types, or null if inference failed
     */
    private fun tryBidirectionalInference(abilityName: String, typeArgs: List<MezzeType>): Substitution? {
        // Special handling for Decode with concrete format but unknown data type
        // This enables: let person = json::decode {val = json_data}
        // where the result type is inferred from downstream usage
        if (abilityName == "Decode" && typeArgs.size == 2) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]

            // If format is concrete and data is a type variable, try to infer from generated impls
            if (formatType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                val formatTypeName = getTypeName(formatType)

                // Look for generated Decode impls for this format
                // Filter by ability name and format type (first target type)
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    impl.abilityName == "Decode" &&
                    impl.targetTypes.size >= 2 &&
                    typeAnnotationToName(impl.targetTypes[0]) == formatTypeName
                }

                if (matchingImpls.size == 1) {
                    // Exactly one impl - we can infer the data type!
                    val impl = matchingImpls.first()
                    // Extract the data type from the impl's target types
                    val dataTypeName = typeAnnotationToName(impl.targetTypes[1])
                    val inferredType = parseTypeName(dataTypeName)

                    if (inferredType != null) {
                        return Substitution.single(dataType.variable, inferredType)
                    }
                }
                // Multiple impls or none found - cannot infer unambiguously
                // This will result in an error asking for type annotation
            }
        }

        // Get all implementations for this ability from the context
        val allImpls = context.getAbilityImpls(abilityName)

        if (allImpls.isEmpty()) {
            return null  // No implementations at all
        }

        // Find implementations that match the pattern
        // For NumAdd 'b Int, we want to find NumAdd T Int where T is concrete
        val matchingImpls = mutableListOf<List<String>>()

        for (impl in allImpls) {
            if (impl.targetTypes.size != typeArgs.size) {
                continue  // Wrong arity
            }

            // Check if this implementation matches the pattern
            var matches = true
            for (i in typeArgs.indices) {
                val constraintType = typeArgs[i]
                val implType = impl.targetTypes[i]

                // If constraint has a concrete type, impl must match exactly
                if (constraintType !is MezzeType.VarType) {
                    val constraintTypeName = getTypeName(constraintType)
                    if (implType != constraintTypeName) {
                        matches = false
                        break
                    }
                }
                // If constraint has a type variable, impl can be anything (we'll infer it)
            }

            if (matches) {
                matchingImpls.add(impl.targetTypes)
            }
        }

        if (matchingImpls.isEmpty()) {
            return null  // No matching implementations
        }

        if (matchingImpls.size > 1) {
            // Multiple matching implementations - ambiguous, cannot infer
            // Example: NumAdd 'a 'b could match NumAdd Int Int, NumAdd Float Float, etc.
            return null
        }

        // Exactly one matching implementation - infer type variables!
        val implTypes = matchingImpls.first()
        val substitutionMap = mutableMapOf<TypeVar, MezzeType>()

        for (i in typeArgs.indices) {
            val constraintType = typeArgs[i]
            if (constraintType is MezzeType.VarType) {
                // Infer this type variable from the implementation
                val inferredType = parseTypeName(implTypes[i])
                if (inferredType != null) {
                    substitutionMap[constraintType.variable] = inferredType
                }
            }
        }

        return if (substitutionMap.isNotEmpty()) {
            Substitution(substitutionMap)
        } else {
            null
        }
    }

    /**
     * Parse a type name string back to a MezzeType.
     * Used for bidirectional inference.
     *
     * Supports:
     * - Primitive types: Int, Float, String, Bool, etc.
     * - Record types: Record_field1_field2 (field types looked up from impls)
     * - List/Array/Set: List_ElementType, Array_ElementType, etc.
     * - Map: Map_KeyType_ValueType
     * - Option/Result: Option_InnerType, Result_OkType_ErrType
     */
    private fun parseTypeName(typeName: String): MezzeType? {
        return when {
            typeName == "Int" -> MezzeType.IntType
            typeName == "Float" -> MezzeType.FloatType
            typeName == "String" -> MezzeType.StringType
            typeName == "Bool" -> MezzeType.BooleanType
            typeName == "Nat" -> MezzeType.NatType
            typeName == "Frac" -> MezzeType.FracType
            typeName == "Ordering" -> MezzeType.OrderingType
            typeName == "Bytes" -> MezzeType.BytesType
            typeName == "Char" -> MezzeType.CharType

            // Record types: Record_age_name -> look up the impl for field types
            typeName.startsWith("Record_") -> parseRecordTypeName(typeName)

            // List types: List_Int -> List[Int]
            typeName.startsWith("List_") -> {
                val elementTypeName = typeName.removePrefix("List_")
                parseTypeName(elementTypeName)?.let { MezzeType.ListType(it) }
            }

            // Array types: Array_Int -> Array[Int]
            typeName.startsWith("Array_") -> {
                val elementTypeName = typeName.removePrefix("Array_")
                parseTypeName(elementTypeName)?.let { MezzeType.ArrayType(it) }
            }

            // Set types: Set_Int -> Set[Int]
            typeName.startsWith("Set_") -> {
                val elementTypeName = typeName.removePrefix("Set_")
                parseTypeName(elementTypeName)?.let { MezzeType.SetType(it) }
            }

            // Option types: Option_Int -> Option[Int]
            typeName.startsWith("Option_") -> {
                val innerTypeName = typeName.removePrefix("Option_")
                parseTypeName(innerTypeName)?.let {
                    MezzeType.TypeApplication("Option", listOf(it))
                }
            }

            // Type applications (like Json, Person, etc.)
            else -> {
                // Check if this is a known type alias in type declarations
                if (typeDeclarations.containsKey(typeName)) {
                    MezzeType.TypeApplication(typeName, emptyList())
                } else {
                    null
                }
            }
        }
    }

    /**
     * Parse a record type name like "Record_age_name" back to a record type.
     *
     * We look up the generated Decode impl to find the actual field types.
     * The impl key format is: "Decode_FormatType_Record_field1_field2"
     */
    private fun parseRecordTypeName(typeName: String): MezzeType.RecordType? {
        // Extract field names from the type name
        val fieldPart = typeName.removePrefix("Record_")
        if (fieldPart.isEmpty()) {
            return MezzeType.RecordType(emptyMap())
        }

        // Look for a generated impl that matches this record type
        // We need to find the data type (second type arg) for any Decode impl with this pattern
        for ((implKey, impl) in generatedCodecImpls) {
            if (implKey.contains("_$typeName") && implKey.startsWith("Decode_")) {
                // Found a matching impl - extract the data type from the target types
                val dataTypeAnnotation = impl.targetTypes.getOrNull(1)
                if (dataTypeAnnotation is RecordTypeAnnotation) {
                    // Convert the annotation back to MezzeType.RecordType
                    val fields = dataTypeAnnotation.entries.associate { entry ->
                        entry.name to annotationToMezzeType(entry.type)
                    }.filterValues { it != null }.mapValues { it.value!! }
                    return MezzeType.RecordType(fields)
                }
            }
        }

        // If no impl found, we can't reconstruct the field types
        // Return null to indicate inference failed
        return null
    }

    /**
     * Convert a TypeAnnotation back to a MezzeType.
     * Used for extracting types from generated impls during bidirectional inference.
     */
    private fun annotationToMezzeType(annotation: TypeAnnotation): MezzeType? {
        return when (annotation) {
            is IdentifierTypeAnnotation -> when (annotation.name) {
                "Int" -> MezzeType.IntType
                "Float" -> MezzeType.FloatType
                "String" -> MezzeType.StringType
                "Bool" -> MezzeType.BooleanType
                "Nat" -> MezzeType.NatType
                "Frac" -> MezzeType.FracType
                "Char" -> MezzeType.CharType
                "Ordering" -> MezzeType.OrderingType
                "Bytes" -> MezzeType.BytesType
                else -> MezzeType.TypeApplication(annotation.name, emptyList())
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to annotationToMezzeType(entry.type)
                }.filterValues { it != null }.mapValues { it.value!! }
                MezzeType.RecordType(fields)
            }
            is TypeApplicationAnnotation -> {
                val args = annotation.args.mapNotNull { annotationToMezzeType(it) }
                MezzeType.TypeApplication(annotation.constructor, args)
            }
            is ArrayTypeAnnotation -> {
                annotationToMezzeType(annotation.elementType)?.let { MezzeType.ArrayType(it) }
            }
            else -> null
        }
    }

    /**
     * Solve an effect usage constraint.
     *
     * Effects are validated in the validation phase, not here.
     */
    private fun solveEffectUsed(constraint: Constraint.EffectUsed): ConstraintResult {
        // Effect handling is validated later
        // Here we just note that an effect is used
        return ConstraintResult.Success(Substitution.empty)
    }

    /**
     * Solve a subsumption constraint: general ⊒ specific
     *
     * This is for polymorphic instantiation.
     * The general type must be at least as general as the specific type.
     */
    private fun solveSubsumes(constraint: Constraint.Subsumes): ConstraintResult {
        // For now, treat subsumption as equality
        // Full polymorphic subsumption will be added later
        val result = unify(constraint.general, constraint.specific, typeDeclarations)

        return when (result) {
            is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
            is UnificationResult.Failure -> ConstraintResult.Failure(
                "Subsumption failed: ${constraint.general} does not subsume ${constraint.specific}"
            )
        }
    }

    /**
     * Solve a MustBeOpen constraint: type must have a row variable.
     *
     * This is generated when a match expression has a wildcard pattern,
     * indicating the scrutinee may have additional constructors.
     */
    private fun solveMustBeOpen(constraint: Constraint.MustBeOpen, currentSubst: Substitution): ConstraintResult {
        val type = constraint.type

        // Track binding name if available - this is the stable identifier
        constraint.bindingName?.let { mustBeOpenBindings.add(it) }

        // If it's a type variable, track it for later processing
        if (type is MezzeType.VarType) {
            mustBeOpenTypes.add(type.variable)

            // Also try to resolve it NOW and track the row variable if available
            val resolved = currentSubst.apply(type)
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { mustBeOpenRowVars.add(it) }
            }
        }

        // MustBeOpen constraints don't produce substitutions directly,
        // they just mark types that should remain open during final processing
        return ConstraintResult.Success(Substitution.empty)
    }

    /**
     * Solve a record pattern exhaustiveness constraint.
     *
     * Verifies that a closed record pattern (without ...) covers all fields of the record type.
     * This is needed when the record type was unknown at pattern generation time.
     */
    private fun solveRecordPatternExhaustive(
        constraint: Constraint.RecordPatternExhaustive,
        currentSubst: Substitution
    ): ConstraintResult {
        // Resolve the record type through the substitution
        val resolvedType = currentSubst.apply(constraint.recordType)

        // If it's still a type variable, we can't check exhaustiveness yet
        // This will be re-checked later when the type is known
        if (resolvedType is MezzeType.VarType) {
            // Can't determine exhaustiveness yet - defer
            return ConstraintResult.Success(Substitution.empty)
        }

        // Handle TypeApplication (named record types)
        val recordType = when (resolvedType) {
            is MezzeType.RecordType -> resolvedType
            is MezzeType.TypeApplication -> {
                expandTypeApplicationToRecord(resolvedType, typeDeclarations) ?: run {
                    // Not a record type - this will be caught by HasField constraints
                    return ConstraintResult.Success(Substitution.empty)
                }
            }
            else -> {
                // Not a record type - error will be reported by other constraints
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
     * Solve a field access constraint: record has field with type
     *
     * Ensures the record type has the specified field.
     */
    private fun solveHasField(constraint: Constraint.HasField, currentSubst: Substitution = Substitution.empty): ConstraintResult {
        val recordType = constraint.recordType
        val fieldName = constraint.fieldName
        val expectedFieldType = constraint.fieldType

        // If record type is a TypeApplication (e.g., Person), expand it first
        if (recordType is MezzeType.TypeApplication) {
            // First try expanding to RecordType (for record type aliases)
            val expandedRecord = expandTypeApplicationToRecord(recordType, typeDeclarations)
            if (expandedRecord != null) {
                // Recursively solve with the expanded record type
                return solveHasField(
                    constraint.copy(recordType = expandedRecord),
                    currentSubst
                )
            }

            // Then try expanding to VariantType (for single-constructor variants like newtypes)
            // Example: type User = | User { name: String, age: Int } |
            // Field access on User should delegate to the payload
            val expandedVariant = expandTypeApplicationToVariant(recordType, typeDeclarations)
            if (expandedVariant != null) {
                // Recursively solve with the expanded variant type
                // The code below handles single-constructor variants
                return solveHasField(
                    constraint.copy(recordType = expandedVariant),
                    currentSubst
                )
            }

            return ConstraintResult.Failure(
                "Cannot expand type application ${recordType.constructor} to check field '$fieldName'"
            )
        }

        // If record type is a type variable, we can't check yet
        if (recordType is MezzeType.VarType) {
            // CRITICAL: If strictFieldCheck is true, we need to wait until the type is known
            // to properly validate field existence. Return Deferred to put back on worklist.
            if (constraint.strictFieldCheck) {
                return ConstraintResult.Deferred("Waiting for record type to be resolved for strict field check")
            }

            // Create a record type with just this field
            val recordWithField = MezzeType.RecordType(
                fields = mapOf(fieldName to expectedFieldType),
                rowVar = RowVar.fresh() // Open record for extensibility
            )
            // Unify the type variable with this record
            val result = unify(recordType, recordWithField, typeDeclarations)
            return when (result) {
                is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                is UnificationResult.Failure -> ConstraintResult.Failure(result.message)
            }
        }

        // Handle single-constructor variants (newtypes) - allow field access on the payload
        // Example: type User = | User { name: String, age: Int } |
        // Accessing user.name on a User value should work by accessing the payload's field
        if (recordType is MezzeType.VariantType) {
            // Check if it's a single-constructor variant (newtype pattern)
            if (recordType.constructors.size == 1 && recordType.rowVar == null) {
                val (_, payloadType) = recordType.constructors.entries.first()
                if (payloadType != null) {
                    // Delegate field access to the payload type
                    return solveHasField(
                        constraint.copy(recordType = payloadType),
                        currentSubst
                    )
                }
            }
            // Multi-constructor variants or no payload - can't access fields
            return ConstraintResult.Failure(
                "Cannot access field '$fieldName' on variant type: ${formatType(recordType)}"
            )
        }

        // If it's a concrete record type, check if field exists
        if (recordType is MezzeType.RecordType) {
            val actualFieldType = recordType.fields[fieldName]
            if (actualFieldType == null) {
                // Field doesn't exist - check if record is open (has row variable)
                if (recordType.rowVar != null && !constraint.strictFieldCheck) {
                    // Record is open AND this is not a strict field check (record literal context)
                    // We can add the field via row polymorphism
                    // Create extended record with the new field
                    val extendedFields = recordType.fields + (fieldName to expectedFieldType)
                    val extendedRecord = MezzeType.RecordType(
                        fields = extendedFields,
                        rowVar = recordType.rowVar  // Keep the same row variable
                    )

                    // CRITICAL FIX: Find the type variable that maps to this record and update it
                    // This ensures multiple field accesses accumulate fields properly
                    for ((typeVar, mappedType) in currentSubst.mappings) {
                        val resolvedMappedType = currentSubst.apply(mappedType)
                        if (resolvedMappedType == recordType) {
                            // Found the type variable - update its mapping to the extended record
                            return ConstraintResult.Success(Substitution.single(typeVar, extendedRecord))
                        }
                    }

                    // If we can't find the original type variable, still succeed
                    // This happens when the record came from an annotation, not inference
                    return ConstraintResult.Success(Substitution.empty)
                } else {
                    // Record is closed, OR this is a strict field check (record literal context)
                    // Field must exist
                    return ConstraintResult.Failure(
                        "Record type does not have field '$fieldName'. Available fields: ${recordType.fields.keys.joinToString()}"
                    )
                }
            }

            // Field exists - unify actual field type with expected
            val result = unify(actualFieldType, expectedFieldType, typeDeclarations)
            return when (result) {
                is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                is UnificationResult.Failure -> ConstraintResult.Failure(
                    "Field '$fieldName' type mismatch: ${result.message}"
                )
            }
        }

        // Not a record type
        return ConstraintResult.Failure(
            "Cannot access field '$fieldName' on non-record type: ${formatType(recordType)}"
        )
    }

    /**
     * Solve a constructor constraint: variant has constructor with payload
     *
     * Ensures the variant type has the specified constructor.
     *
     * @param constraint The constraint AFTER substitution was applied
     * @param currentSubst The current substitution
     * @param originalConstraint The constraint BEFORE substitution (optional, for tracking original type var)
     */
    private fun solveHasConstructor(
        constraint: Constraint.HasConstructor,
        currentSubst: Substitution = Substitution.empty,
        originalConstraint: Constraint.HasConstructor? = null
    ): ConstraintResult {
        val variantType = constraint.variantType
        val constructorName = constraint.constructorName
        val expectedPayloadType = constraint.payloadType

        // If variant type is a TypeApplication, expand it first
        // This handles non-parametric types like Color (TypeApplication("Color", []))
        // and parametric types like Option Int (TypeApplication("Option", [IntType]))
        if (variantType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(variantType, typeDeclarations)

            if (expanded != null) {
                // Recursively solve with the expanded variant type, preserving original constraint
                return solveHasConstructor(
                    constraint.copy(variantType = expanded),
                    currentSubst,
                    originalConstraint ?: constraint
                )
            } else {
                return ConstraintResult.Failure(
                    "Cannot expand type application ${variantType.constructor} to check constructor '$constructorName'"
                )
            }
        }

        // If variant type is a type variable, check if the constructor belongs to a known type declaration
        // If so, use the declared type (with all its constructors). Otherwise, create an open variant.
        if (variantType is MezzeType.VarType) {
            // CRITICAL: Only use declared type for PATTERN matching contexts, not for expression contexts
            // Pattern contexts need the full type for exhaustiveness checking.
            // Expression contexts should create open variants that can unify with annotated types.
            // Use the isPatternContext flag from the constraint itself
            // This is more reliable than parsing the context string
            val isPatternContext = constraint.isPatternContext

            if (isPatternContext) {
                // SPECIAL CASE: Built-in Bool constructors (True, False)
                // These are handled specially because Bool is a built-in type without a type declaration
                if (constructorName == "True" || constructorName == "False") {
                    // Use the closed Bool variant type
                    val boolType = MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null  // Bool is closed
                    )
                    return ConstraintResult.Success(
                        Substitution.single(variantType.variable, boolType)
                    )
                }

                // Look up type declaration that contains this constructor
                // This ensures exhaustiveness checking works with inferred types
                val declaringType = findTypeDeclarationForConstructor(constructorName)

                if (declaringType != null) {
                    // Constructor belongs to a known type - use the full declared type
                    // This is important for exhaustiveness checking: if the user writes
                    //   match color is Red -> ... | Blue -> ...
                    // and there's a type Color = | Red, Blue, Green |, we should use
                    // the full Color type (with Green) so exhaustiveness checking works.
                    val variantDef = declaringType.definition as VariantDefinition

                    // Create a shared mapping for type parameters so all constructors use the same type vars
                    val typeParamMapping = declaringType.typeParams.associate { param ->
                        param to MezzeType.VarType(TypeVar.fresh())
                    }

                    val constructors = variantDef.constructors.associate { ctor ->
                        val payloadType = ctor.dataType?.let { dataTypeAnnotation ->
                            // Convert annotation to MezzeType using the shared type param mapping
                            convertTypeAnnotation(dataTypeAnnotation, typeParamMapping)
                        }
                        ctor.name to payloadType
                    }

                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null  // Use declared openness
                    )

                    // CRITICAL: Validate payload presence/absence and unify types
                    // This ensures that:
                    // 1. Pattern bindings like `Some { val }` get the correct type for `val`
                    // 2. Patterns without destructuring (like `Blue`) fail if constructor has payload
                    // 3. Patterns with destructuring fail if constructor has no payload
                    var resultSubst = Substitution.single(variantType.variable, declaredVariant)

                    val declaredPayload = constructors[constructorName]

                    if (expectedPayloadType != null && declaredPayload != null) {
                        // Both pattern and constructor have payload - unify them
                        val unifyResult = unify(expectedPayloadType, declaredPayload, typeDeclarations)
                        when (unifyResult) {
                            is UnificationResult.Success -> {
                                resultSubst = resultSubst.compose(unifyResult.substitution)
                            }
                            is UnificationResult.Failure -> {
                                return ConstraintResult.Failure(
                                    "Pattern payload type mismatch for constructor '$constructorName': ${unifyResult.message}"
                                )
                            }
                        }
                    } else if (expectedPayloadType == null && declaredPayload != null) {
                        // Pattern has no destructuring but constructor has payload - ERROR!
                        return ConstraintResult.Failure(
                            "Constructor '$constructorName' has a payload that must be destructured. " +
                            "Use '$constructorName { ... }' to match the payload."
                        )
                    } else if (expectedPayloadType != null && declaredPayload == null) {
                        // Pattern tries to destructure but constructor has no payload - ERROR!
                        return ConstraintResult.Failure(
                            "Constructor '$constructorName' has no payload, but pattern tries to destructure it. " +
                            "Use '$constructorName' without '{ ... }'."
                        )
                    }
                    // else: both null - no payload on either side, which is fine

                    return ConstraintResult.Success(resultSubst)
                }

                // SPECIAL CASE: Built-in List constructors (Cons/Nil) in pattern context
                // When no user-defined type declaration exists for Cons/Nil, they belong to
                // the built-in List type which should produce ListType.
                if (constructorName == "Cons" || constructorName == "Nil") {
                    val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
                    val listType = MezzeType.ListType(elemTypeVar)

                    var resultSubst = Substitution.single(variantType.variable, listType)

                    // For Cons, unify the payload type
                    if (constructorName == "Cons" && expectedPayloadType != null) {
                        val expectedConsPayload = MezzeType.RecordType(mapOf(
                            "head" to elemTypeVar,
                            "tail" to listType
                        ))
                        val unifyResult = unify(expectedPayloadType, expectedConsPayload, typeDeclarations)
                        when (unifyResult) {
                            is UnificationResult.Success -> {
                                resultSubst = resultSubst.compose(unifyResult.substitution)
                            }
                            is UnificationResult.Failure -> {
                                return ConstraintResult.Failure(
                                    "Cons pattern payload type mismatch: ${unifyResult.message}"
                                )
                            }
                        }
                    }

                    return ConstraintResult.Success(resultSubst)
                }
            }

            // SPECIAL CASE: Built-in Bool constructors in expression context
            // Even when not in pattern context, True/False should resolve to Bool
            if (constructorName == "True" || constructorName == "False") {
                val boolType = MezzeType.VariantType(
                    constructors = mapOf("True" to null, "False" to null),
                    rowVar = null  // Bool is closed
                )
                return ConstraintResult.Success(
                    Substitution.single(variantType.variable, boolType)
                )
            }

            // SPECIAL CASE: Cons/Nil constructors in expression context
            // First check if there's a user-defined type declaration. If so, use VariantType.
            // Only fall back to built-in ListType when no type declaration exists.
            if (constructorName == "Cons" || constructorName == "Nil") {
                val declaringTypeExpr = findTypeDeclarationForConstructor(constructorName)

                if (declaringTypeExpr != null) {
                    // User-defined List type - use VariantType
                    val variantDef = declaringTypeExpr.definition as VariantDefinition

                    val typeParamMapping = declaringTypeExpr.typeParams.associate { param ->
                        param to MezzeType.VarType(TypeVar.fresh())
                    }

                    val constructors = variantDef.constructors.associate { ctor ->
                        val payloadType = ctor.dataType?.let { dataTypeAnnotation ->
                            convertTypeAnnotation(dataTypeAnnotation, typeParamMapping)
                        }
                        ctor.name to payloadType
                    }

                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null
                    )

                    var resultSubst = Substitution.single(variantType.variable, declaredVariant)

                    val declaredPayload = constructors[constructorName]

                    if (expectedPayloadType != null && declaredPayload != null) {
                        // Both pattern and constructor have payload - unify them
                        val unifyResult = unify(expectedPayloadType, declaredPayload, typeDeclarations)
                        when (unifyResult) {
                            is UnificationResult.Success -> {
                                resultSubst = resultSubst.compose(unifyResult.substitution)
                            }
                            is UnificationResult.Failure -> {
                                return ConstraintResult.Failure(
                                    "Constructor payload type mismatch for '$constructorName': ${unifyResult.message}"
                                )
                            }
                        }
                    } else if (expectedPayloadType == null && declaredPayload != null) {
                        // Pattern has no destructuring but constructor has payload - ERROR!
                        return ConstraintResult.Failure(
                            "Constructor '$constructorName' has a payload that must be destructured. " +
                            "Use '$constructorName { ... }' to match the payload."
                        )
                    } else if (expectedPayloadType != null && declaredPayload == null) {
                        // Pattern tries to destructure but constructor has no payload - ERROR!
                        return ConstraintResult.Failure(
                            "Constructor '$constructorName' has no payload, but pattern tries to destructure it. " +
                            "Use '$constructorName' without '{ ... }'."
                        )
                    }

                    return ConstraintResult.Success(resultSubst)
                }

                // No user-defined type - use built-in ListType
                val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
                val listType = MezzeType.ListType(elemTypeVar)

                var resultSubst = Substitution.single(variantType.variable, listType)

                // For Cons, unify the payload type
                if (constructorName == "Cons" && expectedPayloadType != null) {
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemTypeVar,
                        "tail" to listType
                    ))
                    val unifyResult = unify(expectedPayloadType, expectedConsPayload, typeDeclarations)
                    when (unifyResult) {
                        is UnificationResult.Success -> {
                            resultSubst = resultSubst.compose(unifyResult.substitution)
                        }
                        is UnificationResult.Failure -> {
                            return ConstraintResult.Failure(
                                "Cons constructor payload type mismatch: ${unifyResult.message}"
                            )
                        }
                    }
                }

                return ConstraintResult.Success(resultSubst)
            }

            // EXPRESSION CONTEXT: Look up type declaration for BUILT-IN types only
            // Built-in types (Result, Option) have constructors that should resolve to the full type
            // because closed variant unification would otherwise incorrectly merge them with other types.
            // User-defined types should still use open variants so unification can properly check
            // against type annotations.
            //
            // IMPORTANT: Only use built-in type resolution when:
            // 1. The constructor has a payload that matches the expected pattern
            // 2. There is NO user-defined type that contains this constructor
            //    (i.e., the constructor belongs to the truly built-in Result/Option)
            if (!isPatternContext) {
                // First check if there's a user-defined type with this constructor
                // If so, use the normal open variant logic
                val userDefinedType = findTypeDeclarationForConstructor(constructorName)
                val isUserDefinedConstructor = userDefinedType != null

                // Only apply built-in resolution if there's no user-defined type
                if (!isUserDefinedConstructor) {
                    // Check if this is a built-in constructor WITH the expected payload pattern
                    val shouldUseBuiltInType = when (constructorName) {
                        "Ok" -> expectedPayloadType != null && hasFieldNamed(expectedPayloadType, "val")
                        "Err" -> expectedPayloadType != null && hasFieldNamed(expectedPayloadType, "error")
                        "Some" -> expectedPayloadType != null && hasFieldNamed(expectedPayloadType, "val")
                        "None" -> true  // None never has a payload
                        else -> false
                    }

                    if (shouldUseBuiltInType) {
                        // Use the built-in Result/Option type
                        // These are registered in ContextBuilder as implicit type declarations
                        val builtInTypeName = when (constructorName) {
                            "Ok", "Err" -> "Result"
                            "Some", "None" -> "Option"
                            else -> null
                        }

                        if (builtInTypeName != null) {
                            // Create the built-in variant type with fresh type parameters
                            val resultVariant = when (builtInTypeName) {
                                "Result" -> {
                                    val valTypeVar = MezzeType.VarType(TypeVar.fresh())
                                    val errTypeVar = MezzeType.VarType(TypeVar.fresh())
                                    MezzeType.VariantType(
                                        constructors = mapOf(
                                            "Ok" to MezzeType.RecordType(mapOf("val" to valTypeVar)),
                                            "Err" to MezzeType.RecordType(mapOf("error" to errTypeVar))
                                        ),
                                        rowVar = null  // Closed
                                    )
                                }
                                "Option" -> {
                                    // For built-in Option, we need to return a TypeApplication
                                    // to match the type annotation form (Option Int)
                                    val valTypeVar = MezzeType.VarType(TypeVar.fresh())

                                    // If there's an expected payload, extract the val type from it
                                    val innerType = if (expectedPayloadType != null && expectedPayloadType is MezzeType.RecordType) {
                                        expectedPayloadType.fields["val"] ?: valTypeVar
                                    } else {
                                        valTypeVar
                                    }

                                    // Return TypeApplication to match Option Int form
                                    val optionType = MezzeType.TypeApplication("Option", listOf(innerType))
                                    return ConstraintResult.Success(
                                        Substitution.single(variantType.variable, optionType)
                                    )
                                }
                                else -> null
                            }

                            if (resultVariant != null && resultVariant is MezzeType.VariantType) {
                                var resultSubst = Substitution.single(variantType.variable, resultVariant)

                                // Validate payload presence/absence and unify types
                                val declaredPayload = resultVariant.constructors[constructorName]

                                if (expectedPayloadType != null && declaredPayload != null) {
                                    // Both pattern and constructor have payload - unify them
                                    val unifyResult = unify(expectedPayloadType, declaredPayload, typeDeclarations)
                                    when (unifyResult) {
                                        is UnificationResult.Success -> {
                                            resultSubst = resultSubst.compose(unifyResult.substitution)
                                        }
                                        is UnificationResult.Failure -> {
                                            return ConstraintResult.Failure(
                                                "Constructor payload type mismatch for '$constructorName': ${unifyResult.message}"
                                            )
                                        }
                                    }
                                } else if (expectedPayloadType == null && declaredPayload != null) {
                                    // Pattern has no destructuring but constructor has payload - ERROR!
                                    return ConstraintResult.Failure(
                                        "Constructor '$constructorName' has a payload that must be destructured. " +
                                        "Use '$constructorName { ... }' to match the payload."
                                    )
                                } else if (expectedPayloadType != null && declaredPayload == null) {
                                    // Pattern tries to destructure but constructor has no payload - ERROR!
                                    return ConstraintResult.Failure(
                                        "Constructor '$constructorName' has no payload, but pattern tries to destructure it. " +
                                        "Use '$constructorName' without '{ ... }'."
                                    )
                                }

                                return ConstraintResult.Success(resultSubst)
                            }
                        }
                    }
                }
            }

            // PATTERN CONTEXT: For pattern matching, we still want to allow row polymorphism
            // The unknown constructor check should only happen when the scrutinee has a
            // DECLARED type (like Color in `{c: Color} -> match c is ...`).
            // When the scrutinee type is inferred (like `{x} -> match x is ...`), we should
            // allow creating open variants for row polymorphism.
            //
            // The detection of "unknown constructor in declared type" happens elsewhere:
            // - In solveHasConstructorForVariantType() when the variant is already resolved
            // - During validation when the scrutinee's type is known
            //
            // Here, the scrutinee is still a type variable, so we create an open variant.
            // This enables row polymorphism where the variant type is inferred from patterns.

            // BOTH PATTERN AND EXPRESSION CONTEXT: Create an open variant during constraint solving
            // This is only for constructors that don't belong to any known type declaration
            // The variant will be closed later based on usage
            val newRowVar = RowVar.fresh()
            val newVariant = MezzeType.VariantType(
                constructors = mapOf(constructorName to expectedPayloadType),
                rowVar = newRowVar  // Open so additional constructors can be added
            )

            // PATTERN CONTEXT: Track the row var so it can be closed later
            // Pattern-created variants should be closed unless there's a wildcard (MustBeOpen)
            if (isPatternContext) {
                patternBasedRowVars.add(newRowVar)
            }

            return ConstraintResult.Success(
                Substitution.single(variantType.variable, newVariant)
            )
        }

        // Special handling for BooleanType - allows pattern matching with True/False
        // Bool is internally BooleanType for optimization, but users can pattern match
        // on it as if it were `type Bool = | True | False |`
        if (variantType is MezzeType.BooleanType) {
            if (constructorName == "True" || constructorName == "False") {
                // Valid Bool constructor with no payload
                if (expectedPayloadType != null) {
                    return ConstraintResult.Failure(
                        "Constructor '$constructorName' does not have a payload"
                    )
                }
                return ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintResult.Failure(
                    "Bool type only has constructors 'True' and 'False', not '$constructorName'"
                )
            }
        }

        // Special handling for OrderingType - allows pattern matching with LT/EQ/GT
        // Ordering is internally OrderingType for optimization, but users can pattern match
        // on it as if it were `type Ordering = | LT | EQ | GT |`
        if (variantType is MezzeType.OrderingType) {
            if (constructorName == "LT" || constructorName == "EQ" || constructorName == "GT") {
                // Valid Ordering constructor with no payload
                if (expectedPayloadType != null) {
                    return ConstraintResult.Failure(
                        "Constructor '$constructorName' does not have a payload"
                    )
                }
                return ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintResult.Failure(
                    "Ordering type only has constructors 'LT', 'EQ', and 'GT', not '$constructorName'"
                )
            }
        }

        // If it's a concrete variant type, check if constructor exists
        if (variantType is MezzeType.VariantType) {
            val actualPayloadType = variantType.constructors[constructorName]

            // If constructor doesn't exist, check if variant is open (has row variable)
            if (!variantType.constructors.containsKey(constructorName)) {
                if (variantType.rowVar != null) {
                    // Variant is open - we can add the constructor
                    // Create a new variant with the additional constructor
                    val newConstructors = variantType.constructors + (constructorName to expectedPayloadType)
                    val extendedVariant = MezzeType.VariantType(
                        constructors = newConstructors,
                        rowVar = variantType.rowVar  // Keep the row variable
                    )

                    // Find the type variable that DIRECTLY maps to this variant (not through a chain)
                    // This is important: we need to update the actual type var holding the variant,
                    // not the original type var (which might just point to the actual one through unification)
                    //
                    // First, look for a DIRECT mapping (where mappedType IS the variant, not another VarType)
                    var directTypeVar: TypeVar? = null
                    var chainedTypeVar: TypeVar? = null

                    for ((typeVar, mappedType) in currentSubst.mappings) {
                        // Check for DIRECT mapping: typeVar -> VariantType
                        if (mappedType == variantType) {
                            directTypeVar = typeVar
                            break // Direct mapping found, no need to search further
                        }

                        // Check for CHAINED mapping: typeVar -> VarType -> ... -> VariantType
                        val resolvedMappedType = currentSubst.apply(mappedType)
                        if (resolvedMappedType == variantType && chainedTypeVar == null) {
                            chainedTypeVar = typeVar
                        }
                    }

                    // Prefer direct mapping over chained mapping
                    val targetTypeVar = directTypeVar ?: chainedTypeVar
                    if (targetTypeVar != null) {
                        return ConstraintResult.Success(
                            Substitution.single(targetTypeVar, extendedVariant)
                        )
                    }
                    // No type variable found - return success
                    return ConstraintResult.Success(Substitution.empty)
                } else {
                    // Variant is closed - constructor must exist
                    return ConstraintResult.Failure(
                        "Variant type does not have constructor '$constructorName'. Available constructors: ${variantType.constructors.keys.joinToString()}"
                    )
                }
            }

            // Constructor exists - validate payload presence/absence and unify types
            if (expectedPayloadType != null && actualPayloadType != null) {
                // Both pattern and constructor have payload - unify them
                //
                // CRITICAL: Only instantiate with fresh vars when the type variables come from a
                // TYPE DECLARATION (created fresh in solveHasConstructor's VarType branch), NOT when
                // they come from a TypeApplication expansion.
                //
                // When expanding `Result 'a DecodeError` to a VariantType, the 'a is the SAME type
                // variable that needs to be connected to usages (like `val.name`). If we replace it
                // with a fresh var, bidirectional type inference breaks because the type variable
                // is no longer connected.
                //
                // The occurs check issue mentioned in the old comment is avoided because type variables
                // from TypeApplications are already instantiated per-use-site (during constraint generation
                // when the TypeApplication is created).
                val payloadTypeToUnify = if (originalConstraint?.variantType is MezzeType.TypeApplication) {
                    // Type variables came from TypeApplication expansion - preserve them for bidirectional inference
                    actualPayloadType
                } else {
                    // Type variables came from type declaration lookup - instantiate with fresh vars
                    instantiateWithFreshVars(actualPayloadType)
                }

                val result = unify(payloadTypeToUnify, expectedPayloadType, typeDeclarations)
                return when (result) {
                    is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                    is UnificationResult.Failure -> ConstraintResult.Failure(
                        "Constructor '$constructorName' payload type mismatch: ${result.message}"
                    )
                }
            } else if (expectedPayloadType == null && actualPayloadType != null) {
                // Pattern has no destructuring but constructor has payload - ERROR!
                return ConstraintResult.Failure(
                    "Constructor '$constructorName' has a payload that must be destructured. " +
                    "Use '$constructorName { ... }' to match the payload."
                )
            } else if (expectedPayloadType != null && actualPayloadType == null) {
                // Pattern tries to destructure but constructor has no payload - ERROR!
                return ConstraintResult.Failure(
                    "Constructor '$constructorName' does not have a payload. " +
                    "Remove the pattern '{ ... }' after '$constructorName' since it's a unit variant."
                )
            } else {
                // Both null - no payload on either side, which is fine
                return ConstraintResult.Success(Substitution.empty)
            }
        }

        // Special handling for ListType - allows pattern matching with Cons/Nil
        // List is internally ListType for optimization, but users can pattern match
        // on it as if it were `type List 'a = | Cons { head: 'a, tail: List 'a } | Nil |`
        if (variantType is MezzeType.ListType) {
            if (constructorName == "Cons") {
                // Cons has payload: { head: 'a, tail: List 'a }
                if (expectedPayloadType != null) {
                    val elemType = variantType.elementType
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemType,
                        "tail" to variantType  // ListType with same element type
                    ))
                    val result = unify(expectedPayloadType, expectedConsPayload, typeDeclarations)
                    return when (result) {
                        is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                        is UnificationResult.Failure -> ConstraintResult.Failure(
                            "Constructor 'Cons' payload type mismatch: ${result.message}"
                        )
                    }
                } else {
                    // Cons always has payload - must be destructured
                    return ConstraintResult.Failure(
                        "Constructor 'Cons' has a payload that must be destructured. " +
                        "Use 'Cons { head, tail }' to match the payload."
                    )
                }
            } else if (constructorName == "Nil") {
                // Nil has no payload
                if (expectedPayloadType != null) {
                    return ConstraintResult.Failure(
                        "Constructor 'Nil' does not have a payload"
                    )
                }
                return ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintResult.Failure(
                    "List type only has constructors 'Cons' and 'Nil', not '$constructorName'"
                )
            }
        }

        // Not a variant type
        return ConstraintResult.Failure(
            "Cannot use constructor '$constructorName' on non-variant type: ${formatType(variantType)}"
        )
    }

    /**
     * Close pattern-based variants in the substitution.
     *
     * After all HasConstructor constraints from patterns have been solved, we close
     * the variants by removing their row variables. This ensures that pattern-derived
     * variants act as closed types during subsequent unification.
     *
     * For example:
     *   let f = {x} -> match x is Red -> 1 | Blue -> 2
     * After solving HasConstructor constraints, the variant | Red, Blue, ... | is closed
     * to | Red, Blue |, so calling f { x = Pink } will fail type checking.
     */
    private fun closePatternBasedVariants(substitution: Substitution): Substitution {
        if (patternBasedRowVars.isEmpty()) return substitution

        // Find row vars that should NOT be closed (have wildcard patterns)
        val rowVarsToKeepOpen = mustBeOpenRowVars.toSet()

        // Row vars to close = pattern-based minus those that must stay open
        val rowVarsToClose = patternBasedRowVars - rowVarsToKeepOpen

        if (rowVarsToClose.isEmpty()) return substitution

        // Update all variant types in the substitution to close pattern-based row vars
        val updatedMappings = substitution.mappings.mapValues { (_, type) ->
            closeRowVarsInType(type, rowVarsToClose)
        }

        return Substitution(updatedMappings)
    }

    /**
     * Recursively close specified row vars in a type.
     */
    private fun closeRowVarsInType(type: MezzeType, rowVarsToClose: Set<RowVar>): MezzeType {
        return when (type) {
            is MezzeType.VariantType -> {
                val shouldClose = type.rowVar != null && rowVarsToClose.contains(type.rowVar)
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, payloadType) ->
                        payloadType?.let { closeRowVarsInType(it, rowVarsToClose) }
                    },
                    rowVar = if (shouldClose) null else type.rowVar
                )
            }
            is MezzeType.RecordType -> {
                MezzeType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        closeRowVarsInType(fieldType, rowVarsToClose)
                    },
                    rowVar = type.rowVar  // Don't close record row vars here
                )
            }
            is MezzeType.FunctionType -> {
                MezzeType.FunctionType(
                    paramType = closeRowVarsInType(type.paramType, rowVarsToClose),
                    returnType = closeRowVarsInType(type.returnType, rowVarsToClose),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.ArrayType -> {
                MezzeType.ArrayType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.ListType -> {
                MezzeType.ListType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.MapType -> {
                MezzeType.MapType(
                    keyType = closeRowVarsInType(type.keyType, rowVarsToClose),
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose)
                )
            }
            is MezzeType.SetType -> {
                MezzeType.SetType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.SortedMapType -> {
                MezzeType.SortedMapType(
                    keyType = closeRowVarsInType(type.keyType, rowVarsToClose),
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose)
                )
            }
            is MezzeType.SortedSetType -> {
                MezzeType.SortedSetType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.TypeApplication -> {
                MezzeType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { closeRowVarsInType(it, rowVarsToClose) }
                )
            }
            is MezzeType.SuspendedType -> {
                MezzeType.SuspendedType(
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose),
                    effects = type.effects
                )
            }
            // Primitive types and EffectRow don't contain row vars
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BooleanType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType,
            is MezzeType.EffectRow -> type
        }
    }

    /**
     * Apply substitution to a constraint.
     *
     * This ensures we always work with the most up-to-date types.
     */
    private fun applySubstitutionToConstraint(constraint: Constraint, substitution: Substitution): Constraint {
        return when (constraint) {
            is Constraint.Equality -> Constraint.Equality(
                left = substitution.apply(constraint.left),
                right = substitution.apply(constraint.right),
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.AbilityRequired -> Constraint.AbilityRequired(
                typeVar = constraint.typeVar, // Type vars don't change
                abilityName = constraint.abilityName,
                typeArgs = constraint.typeArgs.map { substitution.apply(it) },
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.EffectUsed -> constraint // No types to substitute

            is Constraint.Subsumes -> Constraint.Subsumes(
                general = substitution.apply(constraint.general),
                specific = substitution.apply(constraint.specific),
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.HasField -> Constraint.HasField(
                recordType = substitution.apply(constraint.recordType),
                fieldName = constraint.fieldName,
                fieldType = substitution.apply(constraint.fieldType),
                context = constraint.context,
                sourceLocation = constraint.sourceLocation,
                strictFieldCheck = constraint.strictFieldCheck
            )

            is Constraint.HasConstructor -> Constraint.HasConstructor(
                variantType = substitution.apply(constraint.variantType),
                constructorName = constraint.constructorName,
                payloadType = constraint.payloadType?.let { substitution.apply(it) },
                context = constraint.context,
                sourceLocation = constraint.sourceLocation,
                isPatternContext = constraint.isPatternContext
            )

            is Constraint.MustBeOpen -> Constraint.MustBeOpen(
                type = substitution.apply(constraint.type),
                bindingName = constraint.bindingName,
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.RecordPatternExhaustive -> Constraint.RecordPatternExhaustive(
                recordType = substitution.apply(constraint.recordType),
                patternFields = constraint.patternFields,
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )
        }
    }

    /**
     * Get context string from constraint for error messages.
     */
    private fun Constraint.getContext(): String? {
        return when (this) {
            is Constraint.Equality -> this.context
            is Constraint.AbilityRequired -> this.context
            is Constraint.EffectUsed -> this.context
            is Constraint.Subsumes -> this.context
            is Constraint.HasField -> this.context
            is Constraint.HasConstructor -> this.context
            is Constraint.MustBeOpen -> this.context
            is Constraint.RecordPatternExhaustive -> this.context
        }
    }

    /**
     * Format a type for error messages.
     */
    private fun formatType(type: MezzeType): String {
        return when (type) {
            is MezzeType.VarType -> "'${('a'.code + (type.variable.id % 26)).toChar()}"  // Show as 'a, 'b, etc.
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BooleanType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.OrderingType -> "Ordering"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.CharType -> "Char"
            is MezzeType.FunctionType -> "{...} -> ..."
            is MezzeType.RecordType -> {
                // Use typeName if available
                type.typeName ?: "{${type.fields.keys.take(3).joinToString()}}"
            }
            is MezzeType.VariantType -> {
                // Use typeName if available, otherwise recognize Bool variant or use constructor names
                type.typeName ?: if (type.constructors.keys == setOf("True", "False")) {
                    "Bool"
                } else {
                    "[${type.constructors.keys.take(3).joinToString()}]"
                }
            }
            is MezzeType.TypeApplication -> type.constructor
            is MezzeType.ArrayType -> "Array ${formatType(type.elementType)}"
            is MezzeType.ListType -> "List ${formatType(type.elementType)}"
            is MezzeType.SetType -> "Set ${formatType(type.elementType)}"
            is MezzeType.SortedMapType -> "SortedMap ${formatType(type.keyType)} ${formatType(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet ${formatType(type.elementType)}"
            is MezzeType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
            is MezzeType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
            is MezzeType.EffectRow -> "<${type.effects.take(3).joinToString()}>"
        }
    }

    /**
     * Instantiate a type with fresh type variables.
     *
     * This is used when we need independent copies of a polymorphic type to prevent
     * type variable sharing that can cause occurs check failures.
     *
     * Example: For Result type with constructors Ok {val: 'a} and Err {error: 'e},
     * each use of Ok should get fresh type variables so that:
     * - Pattern `Ok { val }` gets `'a1`
     * - Expression `Ok { val = ... }` gets `'a2`
     *
     * Without fresh instantiation, both would share the same `'a`, causing occurs check failures.
     */
    private fun instantiateWithFreshVars(type: MezzeType): MezzeType {
        // Collect all type variables in the type
        val typeVars = collectTypeVars(type)

        // Create substitution mapping each old var to a fresh var
        val substitution = typeVars.associateWith { MezzeType.VarType(TypeVar.fresh()) }

        // Apply substitution
        return applyVarSubstitution(type, substitution)
    }

    /**
     * Collect all type variables in a type.
     */
    private fun collectTypeVars(type: MezzeType): Set<TypeVar> {
        return when (type) {
            is MezzeType.VarType -> setOf(type.variable)
            is MezzeType.FunctionType -> {
                collectTypeVars(type.paramType) + collectTypeVars(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { collectTypeVars(it) }.toSet()
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { collectTypeVars(it) }.toSet()
            }
            is MezzeType.TypeApplication -> {
                type.args.flatMap { collectTypeVars(it) }.toSet()
            }
            is MezzeType.ArrayType -> collectTypeVars(type.elementType)
            is MezzeType.ListType -> collectTypeVars(type.elementType)
            is MezzeType.SetType -> collectTypeVars(type.elementType)
            is MezzeType.SortedMapType -> collectTypeVars(type.keyType) + collectTypeVars(type.valueType)
            is MezzeType.SortedSetType -> collectTypeVars(type.elementType)
            is MezzeType.MapType -> collectTypeVars(type.keyType) + collectTypeVars(type.valueType)
            is MezzeType.SuspendedType -> collectTypeVars(type.valueType)
            // Primitives have no type variables
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BooleanType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> emptySet()
        }
    }

    /**
     * Apply type variable substitution to a type.
     * Maps each TypeVar to a MezzeType (usually a fresh VarType).
     */
    private fun applyVarSubstitution(type: MezzeType, substitution: Map<TypeVar, MezzeType>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                substitution[type.variable] ?: type
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyVarSubstitution(type.paramType, substitution),
                returnType = applyVarSubstitution(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyVarSubstitution(it, substitution) }
            )
            is MezzeType.ArrayType -> MezzeType.ArrayType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyVarSubstitution(type.keyType, substitution),
                valueType = applyVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyVarSubstitution(type.keyType, substitution),
                valueType = applyVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyVarSubstitution(type.valueType, substitution),
                effects = type.effects
            )
            // Primitives unchanged
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BooleanType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    /**
     * Find the type declaration that contains the given constructor.
     *
     * This is used to resolve constructor names to their declaring types,
     * enabling exhaustiveness checking with inferred types.
     *
     * Example: findTypeDeclarationForConstructor("Red") might return
     * TypeDeclaration("Color", VariantDefinition([Red, Blue, Green]))
     */
    private fun findTypeDeclarationForConstructor(constructorName: String): TypeDeclaration? {
        for ((_, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is VariantDefinition) {
                val hasConstructor = definition.constructors.any { it.name == constructorName }
                if (hasConstructor) {
                    return typeDecl
                }
            }
        }
        return null
    }

    /**
     * Check if a type is a record type that has a field with the given name.
     * Used to determine if a constructor payload matches the expected pattern
     * for built-in types like Result (Ok {val}, Err {error}) and Option (Some {val}).
     */
    private fun hasFieldNamed(type: MezzeType, fieldName: String): Boolean {
        return when (type) {
            is MezzeType.RecordType -> type.fields.containsKey(fieldName)
            is MezzeType.VarType -> false // Can't know at constraint solving time
            else -> false
        }
    }

    /**
     * Convert a type annotation to MezzeType for solver use.
     * Creates fresh type variables for declared type parameters.
     */
    private fun convertTypeAnnotationForSolver(
        annotation: TypeAnnotation,
        declaredTypeParams: List<String>
    ): MezzeType {
        // Create fresh type variables for each declared type parameter
        val typeParamSubst = declaredTypeParams.associate { param ->
            param to MezzeType.VarType(TypeVar.fresh())
        }
        return convertTypeAnnotation(annotation, typeParamSubst)
    }

    /**
     * Convert a type annotation to MezzeType with a substitution for type parameters.
     */
    private fun convertTypeAnnotation(
        annotation: TypeAnnotation,
        typeParamSubst: Map<String, MezzeType>
    ): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Bool" -> MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Ordering" -> MezzeType.OrderingType
                    "Char" -> MezzeType.CharType
                    "Bytes" -> MezzeType.BytesType
                    "ByteBuffer" -> MezzeType.ByteBufferType
                    else -> {
                        // Type variable or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            // Look up in substitution first
                            typeParamSubst[annotation.name]
                                ?: typeParamSubst["'" + annotation.name.trimStart('\'')]
                                ?: MezzeType.VarType(TypeVar(annotation.name))
                        } else {
                            MezzeType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType, typeParamSubst),
                    returnType = convertTypeAnnotation(annotation.returnType, typeParamSubst),
                    effects = annotation.effects?.let { effectRow ->
                        MezzeType.EffectRow(effectRow.effects.toSet(), null, effectRow.isPerformed)
                    },
                    abilityConstraints = emptyList()
                )
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type, typeParamSubst)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotation(it, typeParamSubst) }
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotation(it, typeParamSubst) }
                )
            }
            is ArrayTypeAnnotation -> {
                MezzeType.ArrayType(
                    elementType = convertTypeAnnotation(annotation.elementType, typeParamSubst)
                )
            }
            is EffectRowAnnotation -> {
                val rowVar = if (annotation.isPolymorphic) RowVar.fresh() else null
                MezzeType.EffectRow(annotation.effects.toSet(), rowVar, annotation.isPerformed)
            }
            is QualifiedTypeAnnotation -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                MezzeType.TypeApplication(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertTypeAnnotation(it, typeParamSubst) }
                )
            }
        }
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Used for suggesting similar names when a constructor/type is not found.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }
}
