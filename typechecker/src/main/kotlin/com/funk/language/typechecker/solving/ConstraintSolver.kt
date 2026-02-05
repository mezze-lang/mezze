package com.funk.language.typechecker.solving

import com.funk.language.ast.*
import com.funk.language.typechecker.abilities.BuiltInAbilities
import com.funk.language.typechecker.constraints.Constraint
import com.funk.language.typechecker.context.TypingContext

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
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<Triple<String, List<FunkType>, SourceLocation?>>>()

    /**
     * Deferred type inferences from bidirectional ability inference.
     *
     * When we have a multi-parameter ability like Convert 'a 'b and 'a = Int,
     * we can look up the implementation Convert_Int_String and infer 'b = String.
     * These inferences are stored here and applied to the final substitution.
     */
    private val deferredInference = mutableMapOf<TypeVar, FunkType>()

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
            val mustBeOpenBindings: Set<String> = emptySet()
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
        val constructorsByType = mutableMapOf<FunkType, MutableMap<String, FunkType?>>()

        for (constraint in constraints) {
            if (constraint is Constraint.HasConstructor) {
                val variantType = constraint.variantType
                // Only infer for type variables
                if (variantType is FunkType.VarType) {
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
            val variantType: FunkType,
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
        constructorPayloads: Map<String, FunkType?> = emptyMap()
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
                val freshArgs = typeParams.map { FunkType.VarType(TypeVar.fresh()) }
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
                        FunkType.TypeApplication(typeName, freshArgs)
                    } else {
                        FunkType.TypeApplication(typeName, emptyList())
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
                        FunkType.TypeApplication(typeName, freshArgs)
                    } else {
                        // Non-parametric variant type
                        FunkType.TypeApplication(typeName, emptyList())
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

        val constraintWorklist = nonAbilityConstraints.toMutableList()
        val processedInThisPass = mutableSetOf<Constraint>()
        var madeProgress = true
        var iterationCount = 0
        val maxIterations = 100  // Safety limit

        while (madeProgress && constraintWorklist.isNotEmpty() && iterationCount < maxIterations) {
            madeProgress = false
            iterationCount++
            processedInThisPass.clear()

            // DEBUG
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("[DEBUG] Worklist iteration $iterationCount, ${constraintWorklist.size} constraints remaining")
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

                            if (leftType is FunkType.VariantType && rightType is FunkType.VariantType) {
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
                                    // This supports row polymorphism where we accumulate constructors
                                    val bothOpen = leftType.rowVar != null && rightType.rowVar != null

                                    if (isMatchContext || bothOpen) {
                                        // Merge variants
                                        val mergedConstructors = leftType.constructors.toMutableMap()
                                        for ((name, dataType) in rightType.constructors) {
                                            if (name !in mergedConstructors) {
                                                mergedConstructors[name] = dataType
                                            }
                                        }

                                        val mergedRowVar = leftType.rowVar ?: rightType.rowVar
                                        val mergedVariant = FunkType.VariantType(
                                            constructors = mergedConstructors,
                                            rowVar = mergedRowVar
                                        )

                                        val updatedMappings = mutableMapOf<TypeVar, FunkType>()
                                        if (originalConstraint.left is FunkType.VarType) {
                                            updatedMappings[(originalConstraint.left as FunkType.VarType).variable] = mergedVariant
                                        }
                                        if (originalConstraint.right is FunkType.VarType) {
                                            updatedMappings[(originalConstraint.right as FunkType.VarType).variable] = mergedVariant
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

                        if (constraintType is FunkType.VarType) {
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
                    constraint = Constraint.Equality(FunkType.IntType, FunkType.IntType, sourceLocation = sourceLocation),
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
            val resolvedType = substitution.apply(FunkType.VarType(typeVar))
            if (resolvedType is FunkType.VariantType && resolvedType.rowVar != null) {
                mustBeOpenRowVars.add(resolvedType.rowVar!!)
            }
        }

        // Return result
        return if (errors.isEmpty()) {
            SolveResult.Success(substitution, warnings, resolvedAbilities, mustBeOpenTypes.toSet(), mustBeOpenRowVars.toSet(), mustBeOpenBindings.toSet())
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
        // DEBUG: Log equality constraint solving
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("[DEBUG] solveEquality: ${constraint.left} = ${constraint.right} (${constraint.context})")
        }

        val result = unify(constraint.left, constraint.right, typeDeclarations)

        // DEBUG: Log result
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("[DEBUG]   result: $result")
        }

        return when (result) {
            is UnificationResult.Success -> {
                // Don't validate ability constraints here - we need the full accumulated substitution
                // Validation happens after all equality constraints are solved in solve()
                ConstraintResult.Success(result.substitution)
            }
            is UnificationResult.Failure -> ConstraintResult.Failure(result.message)
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
            val varType = FunkType.VarType(typeVar)
            val boundType = substitution.apply(varType)

            // If the type variable is still a VarType (possibly different), it's not concrete yet
            if (boundType !is FunkType.VarType) {
                // Type variable was bound to a concrete type - validate abilities
                for ((abilityName, typeArgs, sourceLocation) in abilityConstraints) {
                    // Skip Inspect - it's universally available (already filtered in solveAbilityRequired, but double-check)
                    if (abilityName == "Inspect") continue
                    // Substitute the type variable in typeArgs with the bound type
                    val concreteTypeArgs: List<FunkType> = typeArgs.map { typeArg ->
                        if (typeArg is FunkType.VarType && typeArg.variable == typeVar) {
                            boundType
                        } else {
                            substitution.apply(typeArg)
                        }
                    }

                    // Check if all types are now concrete
                    val allConcrete = concreteTypeArgs.all { isConcrete(it) }

                    if (allConcrete) {
                        // Check if implementation exists
                        val hasImpl = checkAbilityImplementation(abilityName, concreteTypeArgs)
                        if (!hasImpl) {
                            return Pair(generateAbilityErrorMessage(abilityName, concreteTypeArgs), sourceLocation)
                        }
                    } else {
                        // Some types are still type variables - try bidirectional inference!
                        // This is key for multi-parameter abilities like Convert 'a 'b
                        // When 'a = Int, we can look for impl Convert Int String and infer 'b = String
                        val inferredSubst = tryBidirectionalInference(abilityName, concreteTypeArgs)
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
        val trackingArgs = listOf(FunkType.VarType(constraint.typeVar)) + typeArgs
        typeVarAbilityConstraints.getOrPut(constraint.typeVar) { mutableListOf() }
            .add(Triple(abilityName, trackingArgs, constraint.sourceLocation))

        // Also track for any type variables in typeArgs
        // CRITICAL: Use trackingArgs (which includes ALL type arguments), not just typeArgs
        // Otherwise, when 'to is resolved in Convert 'from 'to, we'd only check ['to] instead of ['from, 'to]
        for (typeArg in typeArgs) {
            if (typeArg is FunkType.VarType && typeArg.variable != constraint.typeVar) {
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
            return ConstraintResult.Success(
                Substitution.empty,
                warnings = listOf("Ability constraint $abilityName deferred (has type variables)")
            )
        }
    }

    /**
     * Check if an ability implementation exists for the given types.
     *
     * Looks up implementation in context using the pattern: "AbilityName_Type1_Type2_..."
     * Also checks for built-in ability implementations.
     */
    private fun checkAbilityImplementation(abilityName: String, typeArgs: List<FunkType>): Boolean {
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

        // Build implementation key: "NumAdd_Int", "Convert_Int_String", etc.
        val typeNames = typeArgs.map { getTypeName(it) }
        val implKey = "${abilityName}_${typeNames.joinToString("_")}"

        // Check if implementation exists in context
        val hasImpl = context.getAbilityImpl(implKey) != null

        return hasImpl
    }

    /**
     * Check if this is a built-in ability with built-in implementation.
     *
     * Built-in abilities have implicit implementations for certain types:
     * - NumAdd, NumSub, NumMul, NumDiv, NumMod, NumNeg: Int, Float, Nat, Frac
     * - Eq: Int, Float, String, Bool, Nat, Frac, Ordering
     * - Ord: Int, Float, String, Nat, Frac
     * - Inspect: All primitive types
     */
    private fun isBuiltInAbilityImplementation(abilityName: String, type: FunkType): Boolean {
        // Unwrap SuspendedType to check the inner value type
        val unwrappedType = if (type is FunkType.SuspendedType) type.valueType else type

        return when (abilityName) {
            // Numeric abilities - implemented for Int, Float, Nat, Frac
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType
            }

            // Equality - implemented for most primitive types
            "Eq" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.StringType ||
                unwrappedType is FunkType.BooleanType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType ||
                unwrappedType is FunkType.OrderingType ||
                isBoolVariant(unwrappedType)
            }

            // Ordering - implemented for comparable types
            "Ord" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.StringType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType
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
    private fun generateAbilityErrorMessage(abilityName: String, typeArgs: List<FunkType>): String {
        // Check if any type argument is a function type
        val hasFunctionType = typeArgs.any { it is FunkType.FunctionType }

        if (hasFunctionType) {
            // Generate special error message for arithmetic with function types
            val operatorName = when (abilityName) {
                "NumAdd" -> "(+)"
                "NumSub" -> "(-)"
                "NumMul" -> "(*)"
                "NumDiv" -> "(/)"
                "NumMod" -> "(%)"
                "NumNeg" -> "(-)"
                else -> abilityName
            }

            val functionType = typeArgs.find { it is FunkType.FunctionType }
            val functionTypeStr = formatType(functionType!!)

            return when (abilityName) {
                "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg" ->
                    "Cannot apply arithmetic operator '$operatorName' to a function type $functionTypeStr - functions don't implement the $abilityName ability"
                else -> {
                    val typeNames = typeArgs.map { formatType(it) }.joinToString(", ")
                    "No implementation of $abilityName for types: $typeNames (function types don't implement abilities)"
                }
            }
        }

        // Default error message
        val typeNames = typeArgs.map { formatType(it) }.joinToString(", ")
        return "No implementation of $abilityName for types: $typeNames"
    }

    private fun isBoolVariant(type: FunkType): Boolean {
        return BuiltInAbilities.isBoolVariant(type)
    }

    /**
     * Check if a type is concrete (not a type variable).
     */
    private fun isConcrete(type: FunkType): Boolean {
        return when (type) {
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType -> true
            is FunkType.VariantType -> true
            is FunkType.RecordType -> type.rowVar == null && type.fields.values.all { isConcrete(it) }
            is FunkType.FunctionType -> true
            is FunkType.TypeApplication -> type.args.all { it !is FunkType.VarType }
            is FunkType.VarType -> false
            else -> false
        }
    }

    /**
     * Get the type name for ability implementation lookup.
     */
    private fun getTypeName(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.OrderingType -> "Ordering"
            is FunkType.TypeApplication -> {
                // For "List Int" -> "List"
                // The full type would need all args: "List_Int"
                if (type.args.isEmpty()) {
                    type.constructor
                } else {
                    val argNames = type.args.map { getTypeName(it) }
                    "${type.constructor}_${argNames.joinToString("_")}"
                }
            }
            is FunkType.ArrayType -> "Array_${getTypeName(type.elementType)}"
            is FunkType.ListType -> "List_${getTypeName(type.elementType)}"
            is FunkType.SetType -> "Set_${getTypeName(type.elementType)}"
            is FunkType.MapType -> "Map_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"
            is FunkType.VariantType -> {
                // Check if this is the Bool type (True | False)
                if (isBoolVariant(type)) {
                    "Bool"
                } else {
                    // For other variant types, use a generated name
                    // This is a simplification - ideally we'd look up the type alias
                    "Variant_${type.constructors.keys.sorted().joinToString("_")}"
                }
            }
            is FunkType.RecordType -> "Record"
            is FunkType.FunctionType -> "Function"
            is FunkType.SuspendedType -> getTypeName(type.valueType)
            else -> type.toString()
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
     * @param abilityName The ability name (e.g., "NumAdd")
     * @param typeArgs The type arguments (may contain type variables)
     * @return Substitution binding type variables to concrete types, or null if inference failed
     */
    private fun tryBidirectionalInference(abilityName: String, typeArgs: List<FunkType>): Substitution? {
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
                if (constraintType !is FunkType.VarType) {
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
        val substitutionMap = mutableMapOf<TypeVar, FunkType>()

        for (i in typeArgs.indices) {
            val constraintType = typeArgs[i]
            if (constraintType is FunkType.VarType) {
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
     * Parse a type name string back to a FunkType.
     * Used for bidirectional inference.
     */
    private fun parseTypeName(typeName: String): FunkType? {
        return when (typeName) {
            "Int" -> FunkType.IntType
            "Float" -> FunkType.FloatType
            "String" -> FunkType.StringType
            "Bool" -> FunkType.BooleanType
            "Nat" -> FunkType.NatType
            "Frac" -> FunkType.FracType
            "Ordering" -> FunkType.OrderingType
            else -> null  // For now, only handle primitive types
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
        return ConstraintResult.Success(
            Substitution.empty,
            warnings = listOf("Effect ${constraint.effect} used (deferred to validation)")
        )
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
        if (type is FunkType.VarType) {
            mustBeOpenTypes.add(type.variable)

            // Also try to resolve it NOW and track the row variable if available
            val resolved = currentSubst.apply(type)
            if (resolved is FunkType.VariantType && resolved.rowVar != null) {
                mustBeOpenRowVars.add(resolved.rowVar!!)
            }
        }

        // MustBeOpen constraints don't produce substitutions directly,
        // they just mark types that should remain open during final processing
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

        // If record type is a TypeApplication (e.g., Person), expand it to RecordType first
        if (recordType is FunkType.TypeApplication) {
            val expanded = expandTypeApplicationToRecord(recordType, typeDeclarations)

            if (expanded != null) {
                // Recursively solve with the expanded record type
                return solveHasField(
                    constraint.copy(recordType = expanded),
                    currentSubst
                )
            } else {
                return ConstraintResult.Failure(
                    "Cannot expand type application ${recordType.constructor} to check field '$fieldName'"
                )
            }
        }

        // If record type is a type variable, we can't check yet
        if (recordType is FunkType.VarType) {
            // Create a record type with just this field
            val recordWithField = FunkType.RecordType(
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
        if (recordType is FunkType.VariantType) {
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
        if (recordType is FunkType.RecordType) {
            val actualFieldType = recordType.fields[fieldName]
            if (actualFieldType == null) {
                // Field doesn't exist - check if record is open (has row variable)
                if (recordType.rowVar != null) {
                    // Record is open - we can add the field via row polymorphism
                    // Create extended record with the new field
                    val extendedFields = recordType.fields + (fieldName to expectedFieldType)
                    val extendedRecord = FunkType.RecordType(
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
                    // Record is closed - field must exist
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
        if (variantType is FunkType.TypeApplication) {
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
        if (variantType is FunkType.VarType) {
            // CRITICAL: Only use declared type for PATTERN matching contexts, not for expression contexts
            // Pattern contexts need the full type for exhaustiveness checking.
            // Expression contexts should create open variants that can unify with annotated types.
            //
            // Pattern context: "Constructor pattern Foo"
            // Expression context: "Variant constructor Foo" or "Variant construction Foo"
            val isPatternContext = constraint.context?.contains("Constructor pattern") == true

            if (isPatternContext) {
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
                    val constructors = variantDef.constructors.associate { ctor ->
                        val payloadType = ctor.dataType?.let { dataTypeAnnotation ->
                            // Convert annotation to FunkType
                            convertTypeAnnotationForSolver(dataTypeAnnotation, declaringType.typeParams)
                        }
                        ctor.name to payloadType
                    }

                    val declaredVariant = FunkType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null  // Use declared openness
                    )

                    return ConstraintResult.Success(
                        Substitution.single(variantType.variable, declaredVariant)
                    )
                }
            }

            // Not a pattern context OR no type declaration found - create an open variant
            // The open variant can be extended by other HasConstructor constraints and
            // can be unified with annotated types like `let y: Color = Yellow`
            val newVariant = FunkType.VariantType(
                constructors = mapOf(constructorName to expectedPayloadType),
                rowVar = RowVar.fresh()  // Open so it can be extended
            )
            return ConstraintResult.Success(
                Substitution.single(variantType.variable, newVariant)
            )
        }

        // Special handling for BooleanType - allows pattern matching with True/False
        // Bool is internally BooleanType for optimization, but users can pattern match
        // on it as if it were `type Bool = | True | False |`
        if (variantType is FunkType.BooleanType) {
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

        // If it's a concrete variant type, check if constructor exists
        if (variantType is FunkType.VariantType) {
            val actualPayloadType = variantType.constructors[constructorName]

            // If constructor doesn't exist, check if variant is open (has row variable)
            if (!variantType.constructors.containsKey(constructorName)) {
                if (variantType.rowVar != null) {
                    // Variant is open - we can add the constructor
                    // Create a new variant with the additional constructor
                    val newConstructors = variantType.constructors + (constructorName to expectedPayloadType)
                    val extendedVariant = FunkType.VariantType(
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

            // Constructor exists - check payload type
            if (expectedPayloadType != null) {
                if (actualPayloadType == null) {
                    return ConstraintResult.Failure(
                        "Constructor '$constructorName' does not have a payload, but expected ${formatType(expectedPayloadType)}"
                    )
                }

                // CRITICAL FIX: Instantiate the actual payload type with fresh type variables
                // This prevents occurs check failures where the same TypeVar instance from the variant
                // definition is shared across multiple uses of the constructor (in patterns and expressions).
                // Each use of a polymorphic constructor should get independent type variables.
                val freshPayloadType = instantiateWithFreshVars(actualPayloadType)

                val result = unify(freshPayloadType, expectedPayloadType, typeDeclarations)
                return when (result) {
                    is UnificationResult.Success -> ConstraintResult.Success(result.substitution)
                    is UnificationResult.Failure -> ConstraintResult.Failure(
                        "Constructor '$constructorName' payload type mismatch: ${result.message}"
                    )
                }
            } else {
                // expectedPayloadType == null means "wildcard - accept any payload or no payload"
                // This allows matching constructors without destructuring their payloads
                // Example: "Some -> ..." (ignoring the payload) or "None -> ..." (no payload)
                return ConstraintResult.Success(Substitution.empty)
            }
        }

        // Not a variant type
        return ConstraintResult.Failure(
            "Cannot use constructor '$constructorName' on non-variant type: ${formatType(variantType)}"
        )
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
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.HasConstructor -> Constraint.HasConstructor(
                variantType = substitution.apply(constraint.variantType),
                constructorName = constraint.constructorName,
                payloadType = constraint.payloadType?.let { substitution.apply(it) },
                context = constraint.context,
                sourceLocation = constraint.sourceLocation
            )

            is Constraint.MustBeOpen -> Constraint.MustBeOpen(
                type = substitution.apply(constraint.type),
                bindingName = constraint.bindingName,
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
        }
    }

    /**
     * Format a type for error messages.
     */
    private fun formatType(type: FunkType): String {
        return when (type) {
            is FunkType.VarType -> type.variable.toString()
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.OrderingType -> "Ordering"
            is FunkType.FunctionType -> "{...} -> ..."
            is FunkType.RecordType -> "{${type.fields.keys.take(3).joinToString()}}"
            is FunkType.VariantType -> {
                // Recognize Bool variant type specially
                if (type.constructors.keys == setOf("True", "False")) {
                    "Bool"
                } else {
                    "[${type.constructors.keys.take(3).joinToString()}]"
                }
            }
            is FunkType.TypeApplication -> type.constructor
            is FunkType.ArrayType -> "Array ${formatType(type.elementType)}"
            is FunkType.ListType -> "List ${formatType(type.elementType)}"
            is FunkType.SetType -> "Set ${formatType(type.elementType)}"
            is FunkType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
            is FunkType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
            is FunkType.EffectRow -> "<${type.effects.take(3).joinToString()}>"
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
    private fun instantiateWithFreshVars(type: FunkType): FunkType {
        // Collect all type variables in the type
        val typeVars = collectTypeVars(type)

        // Create substitution mapping each old var to a fresh var
        val substitution = typeVars.associateWith { FunkType.VarType(TypeVar.fresh()) }

        // Apply substitution
        return applyVarSubstitution(type, substitution)
    }

    /**
     * Collect all type variables in a type.
     */
    private fun collectTypeVars(type: FunkType): Set<TypeVar> {
        return when (type) {
            is FunkType.VarType -> setOf(type.variable)
            is FunkType.FunctionType -> {
                collectTypeVars(type.paramType) + collectTypeVars(type.returnType)
            }
            is FunkType.RecordType -> {
                type.fields.values.flatMap { collectTypeVars(it) }.toSet()
            }
            is FunkType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { collectTypeVars(it) }.toSet()
            }
            is FunkType.TypeApplication -> {
                type.args.flatMap { collectTypeVars(it) }.toSet()
            }
            is FunkType.ArrayType -> collectTypeVars(type.elementType)
            is FunkType.ListType -> collectTypeVars(type.elementType)
            is FunkType.SetType -> collectTypeVars(type.elementType)
            is FunkType.MapType -> collectTypeVars(type.keyType) + collectTypeVars(type.valueType)
            is FunkType.SuspendedType -> collectTypeVars(type.valueType)
            // Primitives have no type variables
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType, is FunkType.EffectRow -> emptySet()
        }
    }

    /**
     * Apply type variable substitution to a type.
     * Maps each TypeVar to a FunkType (usually a fresh VarType).
     */
    private fun applyVarSubstitution(type: FunkType, substitution: Map<TypeVar, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                substitution[type.variable] ?: type
            }
            is FunkType.FunctionType -> FunkType.FunctionType(
                paramType = applyVarSubstitution(type.paramType, substitution),
                returnType = applyVarSubstitution(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
            is FunkType.RecordType -> FunkType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar
            )
            is FunkType.VariantType -> FunkType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar
            )
            is FunkType.TypeApplication -> FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyVarSubstitution(it, substitution) }
            )
            is FunkType.ArrayType -> FunkType.ArrayType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is FunkType.ListType -> FunkType.ListType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is FunkType.SetType -> FunkType.SetType(
                elementType = applyVarSubstitution(type.elementType, substitution)
            )
            is FunkType.MapType -> FunkType.MapType(
                keyType = applyVarSubstitution(type.keyType, substitution),
                valueType = applyVarSubstitution(type.valueType, substitution)
            )
            is FunkType.SuspendedType -> FunkType.SuspendedType(
                valueType = applyVarSubstitution(type.valueType, substitution),
                effects = type.effects
            )
            // Primitives unchanged
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType, is FunkType.EffectRow -> type
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
     * Convert a type annotation to FunkType for solver use.
     * Creates fresh type variables for declared type parameters.
     */
    private fun convertTypeAnnotationForSolver(
        annotation: TypeAnnotation,
        declaredTypeParams: List<String>
    ): FunkType {
        // Create fresh type variables for each declared type parameter
        val typeParamSubst = declaredTypeParams.associate { param ->
            param to FunkType.VarType(TypeVar.fresh())
        }
        return convertTypeAnnotation(annotation, typeParamSubst)
    }

    /**
     * Convert a type annotation to FunkType with a substitution for type parameters.
     */
    private fun convertTypeAnnotation(
        annotation: TypeAnnotation,
        typeParamSubst: Map<String, FunkType>
    ): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    "Ordering" -> FunkType.OrderingType
                    else -> {
                        // Type variable or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            // Look up in substitution first
                            typeParamSubst[annotation.name]
                                ?: typeParamSubst["'" + annotation.name.trimStart('\'')]
                                ?: FunkType.VarType(TypeVar(annotation.name))
                        } else {
                            FunkType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                FunkType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType, typeParamSubst),
                    returnType = convertTypeAnnotation(annotation.returnType, typeParamSubst),
                    effects = annotation.effects?.let { effectRow ->
                        FunkType.EffectRow(effectRow.effects.toSet())
                    },
                    abilityConstraints = emptyList()
                )
            }
            is RecordTypeAnnotation -> {
                FunkType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type, typeParamSubst)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                FunkType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotation(it, typeParamSubst) }
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                FunkType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotation(it, typeParamSubst) }
                )
            }
            is ArrayTypeAnnotation -> {
                FunkType.ArrayType(
                    elementType = convertTypeAnnotation(annotation.elementType, typeParamSubst)
                )
            }
            is EffectRowAnnotation -> {
                FunkType.EffectRow(annotation.effects.toSet())
            }
        }
    }
}
