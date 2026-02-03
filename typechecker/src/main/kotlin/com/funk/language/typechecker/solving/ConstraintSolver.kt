package com.funk.language.typechecker.solving

import com.funk.language.ast.*
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
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap()
) {
    /**
     * Track ability constraints on type variables.
     *
     * When we see AbilityRequired('a, Parse), we store: 'a -> [(Parse, [typeArgs])]
     * Later during unification, when 'a = Int, we check that Int satisfies Parse ability.
     *
     * This is inspired by OldTypeChecker.kt:15318-15328 where ability constraints are tracked
     * on type variables during ability method calls.
     */
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<Pair<String, List<FunkType>>>>()
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
            val resolvedAbilityConstraints: Set<Constraint.AbilityRequired> = emptySet()
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
        val constructorsByType = mutableMapOf<FunkType, MutableSet<String>>()

        for (constraint in constraints) {
            if (constraint is Constraint.HasConstructor) {
                val variantType = constraint.variantType
                // Only infer for type variables
                if (variantType is FunkType.VarType) {
                    constructorsByType
                        .getOrPut(variantType) { mutableSetOf() }
                        .add(constraint.constructorName)
                }
            }
        }

        // For each type variable with constructors, find matching variant type
        val inferredEqualities = mutableListOf<Constraint.Equality>()
        val errors = mutableListOf<String>()

        for ((typeVar, constructors) in constructorsByType) {
            // Find variant type declaration that has ALL these constructors
            val result = findVariantWithConstructors(constructors)

            when {
                result is VariantInferenceResult.Success -> {
                    // Successfully inferred variant type
                    inferredEqualities.add(
                        Constraint.Equality(
                            left = typeVar,
                            right = result.variantType,
                            context = "Inferred from constructors: ${constructors.joinToString()}"
                        )
                    )
                }
                result is VariantInferenceResult.Failure -> {
                    // Found a closed variant with invalid constructors - record error!
                    errors.add(result.errorMessage)
                }
                result == null -> {
                    // No matching variant found - this is OK
                    // It means we're using an anonymous/structural variant type
                    // The constructors will create an inferred variant type
                }
            }
        }

        return Pair(inferredEqualities, errors)
    }

    /**
     * Result of variant type inference from constructors.
     */
    private sealed class VariantInferenceResult {
        data class Success(val variantType: FunkType) : VariantInferenceResult()
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
    private fun findVariantWithConstructors(constructors: Set<String>): VariantInferenceResult? {
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

                if (definition.isOpen) {
                    // OPEN variant: Accept if SOME constructors match
                    // Example: type List 'a = | Nil, Cons {head: 'a, tail: List 'a}, ... |
                    // Can use additional constructors beyond Nil/Cons
                    val typeParams = typeDecl.typeParams
                    val freshArgs = typeParams.map { FunkType.VarType(TypeVar.fresh()) }

                    val variantType = if (freshArgs.isNotEmpty()) {
                        FunkType.TypeApplication(typeName, freshArgs)
                    } else {
                        FunkType.TypeApplication(typeName, emptyList())
                    }
                    return VariantInferenceResult.Success(variantType)
                } else {
                    // CLOSED variant: ALL constructors must be in the declaration
                    // Example: type Color = | Red, Blue, Green |
                    // Cannot use Pink - it's not in the declaration
                    val invalidConstructors = constructors - declaredConstructors

                    if (invalidConstructors.isNotEmpty()) {
                        // Found invalid constructors for this closed variant - ERROR!
                        val errorMsg = "Constructor(s) ${invalidConstructors.joinToString()} not found in closed variant '$typeName'. " +
                                      "Available constructors: ${declaredConstructors.joinToString()}"
                        return VariantInferenceResult.Failure(errorMsg)
                    }

                    // All constructors match - this is the right variant
                    val typeParams = typeDecl.typeParams
                    val freshArgs = typeParams.map { FunkType.VarType(TypeVar.fresh()) }

                    val variantType = if (freshArgs.isNotEmpty()) {
                        FunkType.TypeApplication(typeName, freshArgs)
                    } else {
                        // Non-parametric variant type
                        FunkType.TypeApplication(typeName, emptyList())
                    }
                    return VariantInferenceResult.Success(variantType)
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

        // PHASE 1: Process AbilityRequired constraints FIRST to track ability constraints on type variables
        // This is CRITICAL: we need to track which abilities are required on which type variables
        // BEFORE we solve Equality constraints that bind those type variables.
        val (abilityConstraints, nonAbilityConstraints) = allConstraints.partition { it is Constraint.AbilityRequired }

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

        // PHASE 2: Solve Equality constraints to bind type variables
        // Now that we've tracked ability constraints, we can validate them during unification
        val (equalityConstraints, otherConstraints) = nonAbilityConstraints.partition { it is Constraint.Equality }

        for (constraint in equalityConstraints) {
            // Apply current substitution to constraint before solving
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)

            // Solve the constraint
            val result = solveConstraint(appliedConstraint, substitution)

            when (result) {
                is ConstraintResult.Success -> {
                    // Compose new substitution with existing one
                    substitution = result.substitution.compose(substitution)
                    warnings.addAll(result.warnings)
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

        // PHASE 3: Solve other constraints after type variables are bound
        for (constraint in otherConstraints) {
            // Apply current substitution to constraint before solving
            val appliedConstraint = applySubstitutionToConstraint(constraint, substitution)

            // Solve the constraint
            val result = solveConstraint(appliedConstraint, substitution)

            when (result) {
                is ConstraintResult.Success -> {
                    // Compose new substitution with existing one
                    substitution = result.substitution.compose(substitution)
                    warnings.addAll(result.warnings)
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

        // Return result
        return if (errors.isEmpty()) {
            SolveResult.Success(substitution, warnings, resolvedAbilities)
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
            is Constraint.HasField -> solveHasField(constraint)
            is Constraint.HasConstructor -> solveHasConstructor(constraint)
        }
    }

    /**
     * Solve an equality constraint: t1 = t2
     *
     * Uses unification to find substitution.
     * Also validates ability constraints on type variables being unified.
     */
    private fun solveEquality(constraint: Constraint.Equality): ConstraintResult {
        val result = unify(constraint.left, constraint.right, typeDeclarations)

        return when (result) {
            is UnificationResult.Success -> {
                // CRITICAL: When unification succeeds and binds a type variable to a concrete type,
                // we need to validate that the concrete type satisfies all ability constraints
                // that were tracked on that type variable.
                val validationError = validateAbilityConstraints(result.substitution)
                if (validationError != null) {
                    ConstraintResult.Failure(validationError)
                } else {
                    ConstraintResult.Success(result.substitution)
                }
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
     * Returns error message if validation fails, null if all constraints are satisfied.
     */
    private fun validateAbilityConstraints(substitution: Substitution): String? {
        for ((typeVar, abilityConstraints) in typeVarAbilityConstraints) {
            // Check if this type variable was bound to a concrete type
            val boundType = substitution.mappings[typeVar]
            if (boundType != null && boundType !is FunkType.VarType) {
                // Type variable was bound to a concrete type - validate abilities
                for ((abilityName, typeArgs) in abilityConstraints) {
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
                    val allConcrete = concreteTypeArgs.all { arg ->
                        when (arg) {
                            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
                            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
                            is FunkType.OrderingType -> true
                            is FunkType.TypeApplication -> {
                                arg.args.all { it !is FunkType.VarType }
                            }
                            else -> false
                        }
                    }

                    if (allConcrete) {
                        // Check if implementation exists
                        val hasImpl = checkAbilityImplementation(abilityName, concreteTypeArgs)
                        if (!hasImpl) {
                            val typeNames: String = concreteTypeArgs.joinToString(", ") { formatType(it) }
                            return "No implementation of $abilityName for types: $typeNames"
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
        // For each type argument that is a type variable, track the constraint
        for (typeArg in typeArgs) {
            if (typeArg is FunkType.VarType) {
                val typeVar = typeArg.variable
                val constraints = typeVarAbilityConstraints.getOrPut(typeVar) { mutableListOf() }
                constraints.add(abilityName to typeArgs)
            }
        }

        // Apply substitution to type arguments to get concrete types if available
        // If all type args are concrete (not type variables), we can check the implementation now
        val allConcrete = typeArgs.all { arg ->
            when (arg) {
                is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
                is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
                is FunkType.OrderingType -> true
                is FunkType.TypeApplication -> {
                    // For type applications like "List Int", check if args are concrete
                    arg.args.all { it !is FunkType.VarType }
                }
                else -> false
            }
        }

        if (allConcrete) {
            // All types are concrete - check if implementation exists
            val hasImpl = checkAbilityImplementation(abilityName, typeArgs)

            if (!hasImpl) {
                // No implementation found - this is a type error
                val typeNames = typeArgs.map { formatType(it) }.joinToString(", ")
                return ConstraintResult.Failure(
                    "No implementation of $abilityName for types: $typeNames"
                )
            }

            // Implementation exists - constraint is satisfied
            // Return success without warnings (constraint is resolved)
            return ConstraintResult.Success(Substitution.empty)
        } else {
            // Some types are still type variables - try bidirectional inference!
            // Example: NumAdd 'b Int => if only NumAdd Int Int exists, infer 'b = Int
            val inferredSubstitution = tryBidirectionalInference(abilityName, typeArgs)

            if (inferredSubstitution != null) {
                // Successfully inferred type variable from ability constraint
                return ConstraintResult.Success(inferredSubstitution)
            } else {
                // Could not infer - defer checking to validation
                return ConstraintResult.Success(
                    Substitution.empty,
                    warnings = listOf("Ability constraint $abilityName deferred (has type variables)")
                )
            }
        }
    }

    /**
     * Check if an ability implementation exists for the given types.
     *
     * Looks up implementation in context using the pattern: "AbilityName_Type1_Type2_..."
     */
    private fun checkAbilityImplementation(abilityName: String, typeArgs: List<FunkType>): Boolean {
        // Build implementation key: "NumAdd_Int", "Convert_Int_String", etc.
        val typeNames = typeArgs.map { getTypeName(it) }
        val implKey = "${abilityName}_${typeNames.joinToString("_")}"

        // Check if implementation exists in context
        val hasImpl = context.getAbilityImpl(implKey) != null

        // DEBUG
        if (System.getProperty("funk.debug.abilities") == "true") {
            println("[DEBUG] Checking ability $abilityName for types $typeNames")
            println("[DEBUG] Implementation key: $implKey")
            println("[DEBUG] Has implementation: $hasImpl")
        }

        return hasImpl
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
     * Solve a field access constraint: record has field with type
     *
     * Ensures the record type has the specified field.
     */
    private fun solveHasField(constraint: Constraint.HasField): ConstraintResult {
        val recordType = constraint.recordType
        val fieldName = constraint.fieldName
        val expectedFieldType = constraint.fieldType

        // If record type is a TypeApplication (e.g., Person), expand it to RecordType first
        if (recordType is FunkType.TypeApplication) {
            val expanded = expandTypeApplicationToRecord(recordType, typeDeclarations)

            if (expanded != null) {
                // Recursively solve with the expanded record type
                return solveHasField(
                    constraint.copy(recordType = expanded)
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
                    // The extended record should unify with the original
                    // But since we're building up constraints, we just return success
                    // The row variable will absorb the difference
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
     */
    private fun solveHasConstructor(constraint: Constraint.HasConstructor): ConstraintResult {
        val variantType = constraint.variantType
        val constructorName = constraint.constructorName
        val expectedPayloadType = constraint.payloadType

        // DEBUG - write to file for ALL calls
        try {
            val debugFile = java.io.File("/tmp/constructor_all.txt")
            debugFile.appendText("[ENTRY] solveHasConstructor: $constructorName\n")
        } catch (e: Exception) {
            // Ignore
        }

        // DEBUG
        if (constructorName == "Nil") {
            println("[DEBUG solveHasConstructor] Checking for constructor 'Nil'")
            println("[DEBUG solveHasConstructor] Variant type: ${formatType(variantType)}")
            println("[DEBUG solveHasConstructor] Variant type class: ${variantType::class.simpleName}")
        }

        // If variant type is a TypeApplication, expand it first
        // This handles non-parametric types like Color (TypeApplication("Color", []))
        // and parametric types like Option Int (TypeApplication("Option", [IntType]))
        if (variantType is FunkType.TypeApplication) {
            // DEBUG
            if (constructorName == "Nil" || constructorName == "Cons") {
                println("[DEBUG solveHasConstructor] Expanding TypeApplication ${variantType.constructor} for constructor $constructorName")
            }

            val expanded = expandTypeApplicationToVariant(variantType, typeDeclarations)

            // DEBUG
            if (constructorName == "Nil" || constructorName == "Cons") {
                println("[DEBUG solveHasConstructor] Expanded to: $expanded")
                if (expanded != null) {
                    println("[DEBUG solveHasConstructor] Expanded constructors: ${expanded.constructors.keys}")
                }
            }

            if (expanded != null) {
                // Recursively solve with the expanded variant type
                return solveHasConstructor(
                    constraint.copy(variantType = expanded)
                )
            } else {
                return ConstraintResult.Failure(
                    "Cannot expand type application ${variantType.constructor} to check constructor '$constructorName'"
                )
            }
        }

        // If variant type is a type variable, we can't validate yet
        // The type variable will be unified with a concrete type by Equality constraints
        // OR it represents an anonymous/structural variant type (constructors without declarations)
        if (variantType is FunkType.VarType) {
            // CRITICAL: Don't try to validate or create a variant type here!
            // Multiple constructors from the same variant would create conflicting variants.
            // Instead, let Equality constraints bind the type variable first.
            //
            // If NO Equality constraint was generated (no matching type declaration),
            // this is an anonymous/structural variant - Funk allows using constructors
            // without declaring types, inferring the variant from usage.
            return ConstraintResult.Success(Substitution.empty)
        }

        // If it's a concrete variant type, check if constructor exists
        if (variantType is FunkType.VariantType) {
            // DEBUG
            if (constructorName == "Nil" || constructorName == "Cons") {
                println("[DEBUG solveHasConstructor] VariantType with constructors: ${variantType.constructors.keys}")
                println("[DEBUG solveHasConstructor] Looking for constructor: $constructorName")
                println("[DEBUG solveHasConstructor] rowVar: ${variantType.rowVar}")
            }

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
                    // Unify the original variant with the extended one
                    // This will fail if they're already the same, or succeed with a substitution
                    return ConstraintResult.Success(Substitution.empty)
                } else {
                    // Variant is closed - constructor must exist
                    // DEBUG - write to file
                    try {
                        val debugFile = java.io.File("/tmp/constructor_debug.txt")
                        debugFile.appendText("[DEBUG] FAILING: constructor '$constructorName' not in ${variantType.constructors.keys}\n")
                        debugFile.appendText("[DEBUG] Full variant type: $variantType\n")
                        debugFile.appendText("[DEBUG] Stack trace:\n")
                        Thread.currentThread().stackTrace.take(10).forEach {
                            debugFile.appendText("  $it\n")
                        }
                        debugFile.appendText("\n")
                    } catch (e: Exception) {
                        // Ignore debug errors
                    }

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
                context = constraint.context
            )

            is Constraint.AbilityRequired -> Constraint.AbilityRequired(
                typeVar = constraint.typeVar, // Type vars don't change
                abilityName = constraint.abilityName,
                typeArgs = constraint.typeArgs.map { substitution.apply(it) },
                context = constraint.context
            )

            is Constraint.EffectUsed -> constraint // No types to substitute

            is Constraint.Subsumes -> Constraint.Subsumes(
                general = substitution.apply(constraint.general),
                specific = substitution.apply(constraint.specific),
                context = constraint.context
            )

            is Constraint.HasField -> Constraint.HasField(
                recordType = substitution.apply(constraint.recordType),
                fieldName = constraint.fieldName,
                fieldType = substitution.apply(constraint.fieldType),
                context = constraint.context
            )

            is Constraint.HasConstructor -> Constraint.HasConstructor(
                variantType = substitution.apply(constraint.variantType),
                constructorName = constraint.constructorName,
                payloadType = constraint.payloadType?.let { substitution.apply(it) },
                context = constraint.context
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
            is FunkType.VariantType -> "[${type.constructors.keys.take(3).joinToString()}]"
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
}
