package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ErrorContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.printing.TypeDiffPrinter
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.ModuleResolver
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
     * Track associated type resolution constraints that are waiting for their
     * primary type variable to become concrete.
     *
     * When the primary type is resolved, we can look up the impl and unify
     * the associated type variables with their concrete bindings.
     */
    private val associatedTypeResolutionConstraints = mutableMapOf<TypeVar, MutableList<Constraint.AssociatedTypeResolution>>()

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

    // ========================================================================
    // OPTIMIZATION: Constructor-to-Variant Index for O(1) lookup
    // ========================================================================

    /**
     * Index from constructor name to (TypeDeclaration, VariantDefinition) pairs.
     * Built lazily on first use of findVariantWithConstructors.
     * Enables O(1) lookup instead of O(n) iteration over all type declarations.
     */
    private val constructorIndex: Map<String, List<Pair<TypeDeclaration, VariantDefinition>>> by lazy {
        buildConstructorIndex()
    }

    /**
     * Build the constructor-to-variant index from type declarations.
     */
    private fun buildConstructorIndex(): Map<String, List<Pair<TypeDeclaration, VariantDefinition>>> {
        val index = mutableMapOf<String, MutableList<Pair<TypeDeclaration, VariantDefinition>>>()
        for ((_, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is VariantDefinition) {
                for (constructor in definition.constructors) {
                    index.getOrPut(constructor.name) { mutableListOf() }
                        .add(Pair(typeDecl, definition))
                }
            }
        }
        return index
    }

    // ========================================================================
    // OPTIMIZATION: typeMatchesAnnotation memoization cache
    // ========================================================================

    /**
     * Cache for typeMatchesAnnotation results.
     * Key: Pair of type hashCode and annotation hashCode
     * Value: Boolean result
     *
     * This avoids repeated matching of the same type/annotation pairs during
     * ability constraint validation.
     */
    private val typeMatchesAnnotationCache = mutableMapOf<Pair<Int, Int>, Boolean>()

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
     *
     * OPTIMIZATION: Uses constructor index for O(1) lookup instead of O(n) iteration.
     */
    private fun findVariantWithConstructors(
        constructors: Set<String>,
        constructorPayloads: Map<String, MezzeType?> = emptyMap()
    ): VariantInferenceResult? {
        // OPTIMIZATION: Use constructor index for O(1) lookup of candidate variants
        // Collect all variant declarations that have at least one of the constructors
        val candidateVariants = mutableSetOf<Pair<TypeDeclaration, VariantDefinition>>()
        for (constructorName in constructors) {
            constructorIndex[constructorName]?.let { variants ->
                candidateVariants.addAll(variants)
            }
        }

        // If no candidates found, it's an anonymous/structural variant
        if (candidateVariants.isEmpty()) {
            return null
        }

        // Check each candidate variant
        for ((typeDecl, definition) in candidateVariants) {
            val typeName = typeDecl.name
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
                    // Provide helpful suggestions for each invalid constructor
                    val suggestions = invalidConstructors.map { invalid ->
                        val suggestion = com.mezze.language.typechecker.util.StringDistance.suggestDidYouMean(
                            invalid,
                            declaredConstructors.toList()
                        )
                        if (suggestion != null) "'$invalid' ($suggestion)" else "'$invalid'"
                    }
                    val errorMsg = "Constructor(s) ${suggestions.joinToString()} not found in closed variant '$typeName'. " +
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
        // Note: inferenceErrors can only be non-empty if there were HasConstructor constraints,
        // which means constraints must also be non-empty
        if (inferenceErrors.isNotEmpty() && constraints.isNotEmpty()) {
            val contextConstraint = constraints.first()  // Safe: we checked non-empty
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

        // PHASE 0.5: Process MustBeOpen constraints to track which types must stay open
        val (mustBeOpenConstraints, constraintsAfterOpen) = allConstraints.partition { it is Constraint.MustBeOpen }
        for (constraint in mustBeOpenConstraints) {
            val result = solveConstraint(constraint, substitution)
            // MustBeOpen constraints don't fail or produce substitutions
        }

        // PHASE 1: Process AbilityRequired and AssociatedTypeResolution constraints FIRST
        // This is CRITICAL: we need to track which abilities are required on which type variables
        // BEFORE we solve Equality constraints that bind those type variables.
        val (abilityConstraints, nonAbilityConstraints) = constraintsAfterOpen.partition {
            it is Constraint.AbilityRequired || it is Constraint.AssociatedTypeResolution
        }

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
                        // Terminal errors (e.g., built-in constructors with wrong payload) should not be deferred
                        if (constraintType is MezzeType.VarType && !result.terminal) {
                            // Still a type variable - might be resolved later
                        } else {
                            // Concrete type OR terminal error - this is a real error
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
        // OPTIMIZATION: Partition constraints by priority instead of sorting (O(n) vs O(n log n)).
        // Equality constraints are simpler and faster to solve, and they bind type variables
        // that other constraints depend on. Processing them first reduces iterations.
        val allRemainingConstraints = hasConstructorWorklist + otherConstraints
        val sortedConstraints = buildList {
            // Priority 0: Equality constraints (bind type variables)
            addAll(allRemainingConstraints.filterIsInstance<Constraint.Equality>())
            // Priority 1: HasField constraints
            addAll(allRemainingConstraints.filterIsInstance<Constraint.HasField>())
            // Priority 2: HasConstructor constraints
            addAll(allRemainingConstraints.filterIsInstance<Constraint.HasConstructor>())
            // Priority 3-5: Remaining constraints (exhaustive - no else needed)
            for (c in allRemainingConstraints) {
                when (c) {
                    is Constraint.Equality,
                    is Constraint.HasField,
                    is Constraint.HasConstructor -> { /* already added */ }
                    is Constraint.AbilityRequired,
                    is Constraint.AssociatedTypeResolution,
                    is Constraint.EffectUsed,
                    is Constraint.Subsumes,
                    is Constraint.MustBeOpen,
                    is Constraint.RecordPatternExhaustive -> add(c)
                }
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
                                    // OPTIMIZATION: Use boolean flag instead of string parsing
                                    val isMatchContext = originalConstraint.isMatchCaseContext

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
                                            rowVar = mergedRowVar,
                                            typeName = leftType.typeName ?: rightType.typeName
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

        // Resolve deferred associated type constraints now that type variables are bound
        // CRITICAL: Take a copy of entries to avoid ConcurrentModificationException
        // (solveAssociatedTypeResolution may add new entries if constraint is still unresolved)
        val deferredEntries = associatedTypeResolutionConstraints.toList()
        DebugLogger.debug(Category.ConstraintSolver) {
            "Processing ${deferredEntries.size} deferred associated type constraints"
        }
        for ((typeVar, deferredConstraints) in deferredEntries) {
            DebugLogger.debug(Category.ConstraintSolver) {
                "  TypeVar: $typeVar, ${deferredConstraints.size} constraints"
            }
            // Also take a copy of constraints list
            for (constraint in deferredConstraints.toList()) {
                // Re-solve the constraint with the updated substitution
                val result = solveAssociatedTypeResolution(constraint, substitution)
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
                        // Still deferred - type variable not resolved, ignore
                    }
                }
            }
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

        data class Failure(
            val message: String,
            /** If true, this error should not be deferred even for VarType constraints */
            val terminal: Boolean = false
        ) : ConstraintResult()

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
            is Constraint.AssociatedTypeResolution -> solveAssociatedTypeResolution(constraint, currentSubst)
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
                // Generate better error message based on structured context
                val betterMessage = generateBetterErrorMessage(constraint, result)
                ConstraintResult.Failure(betterMessage)
            }
        }
    }

    /**
     * Generate a user-friendly error message based on structured error context.
     *
     * Uses ErrorContext when available, falls back to string-based context parsing
     * for backwards compatibility.
     */
    private fun generateBetterErrorMessage(
        constraint: Constraint.Equality,
        failure: UnificationResult.Failure
    ): String {
        val type1 = constraint.left
        val type2 = constraint.right
        val errorContext = constraint.errorContext
        val stringContext = constraint.context ?: ""

        // Handle structured error context
        when (errorContext) {
            is ErrorContext.FunctionArgument -> {
                val argName = errorContext.argumentName
                val funcName = errorContext.functionName
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return if (argName != null && funcName != null) {
                    "Type mismatch for argument '$argName' in call to '$funcName': expected $expected but got $actual"
                } else if (argName != null) {
                    "Type mismatch for argument '$argName': expected $expected but got $actual"
                } else if (funcName != null) {
                    "Type mismatch in call to '$funcName': expected $expected but got $actual"
                } else {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
            }
            is ErrorContext.TypeAnnotation -> {
                val bindingName = errorContext.bindingName
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return if (errorContext.isReturnType) {
                    "Return type mismatch in '$bindingName': declared as $expected but returns $actual"
                } else {
                    "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual"
                }
            }
            is ErrorContext.FieldAccess -> {
                val fieldName = errorContext.fieldName
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return "Field '$fieldName' has type $actual but expected $expected"
            }
            is ErrorContext.PatternMatch -> {
                return generatePatternMatchError(type1, type2, errorContext, stringContext)
            }
            is ErrorContext.BinaryOperation -> {
                val op = errorContext.operator
                val side = if (errorContext.isLeftOperand) "left" else "right"
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return "Type mismatch in '$op' operator ($side operand): expected $expected but got $actual"
            }
            is ErrorContext.SeqElement -> {
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return "Seq element at index ${errorContext.elementIndex} has type $actual but seq expects $expected"
            }
            is ErrorContext.ReturnType -> {
                val funcName = errorContext.functionName
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return if (funcName != null) {
                    "Return type mismatch in function '$funcName': expected $expected but got $actual"
                } else {
                    "Return type mismatch: expected $expected but got $actual"
                }
            }
            is ErrorContext.StringInterpolation -> {
                val actual = TypeDiffPrinter.formatType(type1)
                return "String interpolation requires String type, but expression has type $actual"
            }
            is ErrorContext.Simple -> {
                // Fall through to string-based handling
            }
            null -> {
                // Fall through to string-based handling
            }
        }

        // Backwards-compatible string-based context parsing
        return generateErrorFromStringContext(type1, type2, stringContext, failure)
    }

    /**
     * Generate error message from string-based context (backwards compatibility).
     */
    private fun generateErrorFromStringContext(
        type1: MezzeType,
        type2: MezzeType,
        stringContext: String,
        failure: UnificationResult.Failure
    ): String {
        // Detect literal vs variant mismatch for better error message
        if (stringContext.contains("Literal pattern")) {
            // Check if one side is a variant type
            val variantType = when {
                type1 is MezzeType.VariantType -> type1
                type2 is MezzeType.VariantType -> type2
                else -> null
            }

            if (variantType != null) {
                val firstConstructor = variantType.constructors.keys.firstOrNull() ?: "ConstructorName"
                val sortedConstructors = variantType.constructors.keys.sorted()
                return "Cannot match variant type [${sortedConstructors.joinToString(", ")}] with a literal pattern. " +
                    "Use a constructor pattern like '$firstConstructor' instead."
            }

            // Check for TypeApplication (named type like Option, Result)
            val typeApp = when {
                type1 is MezzeType.TypeApplication -> type1
                type2 is MezzeType.TypeApplication -> type2
                else -> null
            }

            if (typeApp != null) {
                return "Cannot match type '${typeApp.constructor}' with a literal pattern. " +
                    "Use a constructor pattern instead."
            }
        }

        // Check for List vs Seq confusion
        if (stringContext.contains("Seq pattern")) {
            if (type1 is MezzeType.ListType || type2 is MezzeType.ListType) {
                return "Cannot use seq pattern syntax [a, b, ...] with List type. " +
                    "Use constructor patterns 'Cons { head, tail }' and 'Nil' instead."
            }
        }

        // Improve generic messages by extracting context info
        if (stringContext.isNotEmpty()) {
            val expected = TypeDiffPrinter.formatType(type2)
            val actual = TypeDiffPrinter.formatType(type1)

            // Extract useful context information
            return when {
                stringContext.contains("Function application argument") -> {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
                stringContext.contains("Type annotation on") -> {
                    val bindingName = extractBindingName(stringContext)
                    if (bindingName != null) {
                        "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual"
                    } else {
                        failure.message
                    }
                }
                stringContext.contains("Field access") -> {
                    val fieldName = extractFieldName(stringContext)
                    if (fieldName != null) {
                        "Field '$fieldName' type mismatch: expected $expected but got $actual"
                    } else {
                        failure.message
                    }
                }
                else -> failure.message
            }
        }

        return failure.message
    }

    /**
     * Generate pattern match error message.
     */
    private fun generatePatternMatchError(
        type1: MezzeType,
        type2: MezzeType,
        context: ErrorContext.PatternMatch,
        stringContext: String
    ): String {
        val expected = TypeDiffPrinter.formatType(type2)
        val actual = TypeDiffPrinter.formatType(type1)

        // Check for variant type matching
        val variantType = when {
            type1 is MezzeType.VariantType -> type1
            type2 is MezzeType.VariantType -> type2
            else -> null
        }

        if (variantType != null && context.constructorName != null) {
            return "Pattern '${context.constructorName}' expects $expected but scrutinee has type $actual"
        }

        return "Pattern match type mismatch: expected $expected but got $actual"
    }

    /**
     * Extract binding name from context string like "Type annotation on myFunc"
     */
    private fun extractBindingName(context: String): String? {
        val prefix = "Type annotation on "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
    }

    /**
     * Extract field name from context string like "Field access fieldName"
     */
    private fun extractFieldName(context: String): String? {
        val prefix = "Field access "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
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
                    // Compare by ID or name since different TypeVar instances may represent the same variable
                    val concreteTypeArgs: List<MezzeType> = typeArgs.map { typeArg ->
                        if (typeArg is MezzeType.VarType &&
                            (typeArg.variable.id == typeVar.id ||
                             typeArg.variable.name.removePrefix("'") == typeVar.name.removePrefix("'"))) {
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
                        // When 'a = Int, we can look for impl Convert for Int String and infer 'b = String
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
        // For single-parameter abilities: typeArgs = ['a], trackingArgs = ['a]
        // For multi-parameter abilities from method calls: typeVar='from, typeArgs=['to], trackingArgs = ['from, 'to]
        // For multi-parameter abilities from annotations: typeArgs = [Json, 'a], trackingArgs = [Json, 'a]
        //
        // If typeArgs already contains a type variable matching the constrained one by NAME (from annotations),
        // use typeArgs directly. Otherwise prepend typeVar (from method calls).
        val constraintVarName = constraint.typeVar.name.removePrefix("'")
        val typeVarInArgs = typeArgs.any { arg ->
            arg is MezzeType.VarType && arg.variable.name.removePrefix("'") == constraintVarName
        }
        val trackingArgs = if (typeVarInArgs) {
            typeArgs  // Already contains all args in order
        } else {
            listOf(MezzeType.VarType(constraint.typeVar)) + typeArgs  // Prepend the constrained var
        }
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
     * Solve an associated type resolution constraint.
     *
     * When an ability has associated types (e.g., `Container 'c having 'item`),
     * this looks up the matching impl and unifies the associated type variables
     * with their concrete bindings.
     *
     * Example: For `Container.get {container = intArray}` where `intArray: [Int]`
     * and `impl Container for [Int] having 'item = Int`:
     * - primaryTypeVar resolves to [Int]
     * - Look up impl for Container [Int]
     * - Unify 'item with Int
     */
    private fun solveAssociatedTypeResolution(
        constraint: Constraint.AssociatedTypeResolution,
        currentSubst: Substitution
    ): ConstraintResult {
        val abilityName = constraint.abilityName
        val primaryType = currentSubst.apply(MezzeType.VarType(constraint.primaryTypeVar))

        DebugLogger.debug(Category.ConstraintSolver) {
            "solveAssociatedTypeResolution: ability=$abilityName, primaryTypeVar=${constraint.primaryTypeVar}, resolved=$primaryType"
        }

        // If primary type is still a type variable, defer resolution
        if (primaryType is MezzeType.VarType) {
            // Track for later resolution when the type becomes concrete
            DebugLogger.debug(Category.ConstraintSolver) {
                "  Deferring - primary type still a variable: ${primaryType.variable}"
            }
            associatedTypeResolutionConstraints.getOrPut(primaryType.variable) { mutableListOf() }
                .add(constraint)
            return ConstraintResult.Success(Substitution.empty)
        }

        // Primary type is concrete - look up the matching impl
        val matchingImpl = findMatchingAbilityImpl(abilityName, primaryType)
        DebugLogger.debug(Category.ConstraintSolver) {
            "  matchingImpl: ${matchingImpl?.abilityName} with bindings ${matchingImpl?.associatedTypeBindings}"
        }

        if (matchingImpl == null) {
            // No impl found - this is handled by AbilityRequired constraint
            // Just return success here (the error will be reported elsewhere)
            return ConstraintResult.Success(Substitution.empty)
        }

        // Extract associated type bindings from the impl
        val associatedBindings = matchingImpl.associatedTypeBindings
        if (associatedBindings.isEmpty()) {
            return ConstraintResult.Success(Substitution.empty)
        }

        // Build substitution to unify associated type variables with their bindings
        var resultSubst = Substitution.empty

        for ((assocTypeName, typeVar) in constraint.associatedTypeVars) {
            // Look up the binding for this associated type
            // Keys might be with or without ' prefix
            val binding = associatedBindings[assocTypeName]
                ?: associatedBindings["'$assocTypeName"]

            if (binding != null) {
                // Convert the TypeAnnotation to MezzeType
                val concreteType = typeAnnotationToMezzeType(binding)

                // CRITICAL: Apply current substitution to see what the type variable resolves to
                // If it's already bound to a different type, unification will fail
                val resolvedTypeVar = currentSubst.apply(MezzeType.VarType(typeVar))

                // Unify the (possibly already resolved) type with the concrete type
                val unifyResult = unify(resolvedTypeVar, concreteType)
                when (unifyResult) {
                    is UnificationResult.Success -> {
                        resultSubst = unifyResult.substitution.compose(resultSubst)
                    }
                    is UnificationResult.Failure -> {
                        return ConstraintResult.Failure(
                            "Type mismatch for associated type '$assocTypeName': expected $concreteType but got $resolvedTypeVar"
                        )
                    }
                }
            }
        }

        return ConstraintResult.Success(resultSubst)
    }

    /**
     * Find a matching ability impl for the given ability and concrete type.
     */
    private fun findMatchingAbilityImpl(
        abilityName: String,
        primaryType: MezzeType
    ): TypingContext.AbilityImplInfo? {
        val allImpls = context.getAbilityImpls(abilityName)

        return allImpls.find { impl ->
            val annotations = impl.targetTypeAnnotations ?: return@find false

            // For abilities with associated types, only match the primary type
            // (associated types are handled separately)
            if (annotations.size == 1) {
                val typeVarBindings = mutableMapOf<String, MezzeType>()
                typeMatchesAnnotation(primaryType, annotations.first(), impl.typeVars, typeVarBindings)
            } else {
                false
            }
        }
    }

    /**
     * Convert a TypeAnnotation to MezzeType.
     * Used for resolving associated type bindings.
     */
    private fun typeAnnotationToMezzeType(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check primitive types first
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                // Type application with no args
                MezzeType.TypeApplication(annotation.name, emptyList())
            }
            is TypeApplicationAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { typeAnnotationToMezzeType(it) }
                )
            }
            is SeqTypeAnnotation -> {
                MezzeType.SeqType(typeAnnotationToMezzeType(annotation.elementType))
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to typeAnnotationToMezzeType(entry.type)
                    }
                )
            }
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = typeAnnotationToMezzeType(annotation.paramType),
                    returnType = typeAnnotationToMezzeType(annotation.returnType)
                )
            }
            is VariantTypeAnnotation -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { constructor ->
                        constructor.name to constructor.dataType?.let { typeAnnotationToMezzeType(it) }
                    }
                )
            }
            else -> {
                // For other annotation types, create a type application
                MezzeType.TypeApplication(annotation.toString(), emptyList())
            }
        }
    }

    /**
     * Check if an ability implementation exists for the given types.
     *
     * Looks up implementation in context using the pattern: "AbilityName_Type1_Type2_..."
     * Also checks for built-in ability implementations.
     */
    private fun checkAbilityImplementation(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        // Derivation + fallback for abilities that can't come from context alone:
        // - Inspect: universally available for ALL types
        // - VarType pass-through: polymorphic types are constrained at call site
        // - Eq/Ord derivation for composite types (records, variants, lists)
        // - Bool variant matching for logical operators
        // - Primitive type impls as fallback (needed when stdlib modules don't have
        //   cross-type impls in scope, e.g. std::str using == on Int)
        if (typeArgs.size == 1) {
            if (canDeriveAbility(abilityName, typeArgs.first())) {
                return true
            }
        }

        // Check codec abilities (Encode, EncodeRepr, Decode, DecodeRepr, StreamEncode, StreamDecode)
        // For Roc-style design, EncodeRepr/DecodeRepr take 1 type arg (data type)
        // The format type comes from Format/DecodeFormat impl in scope
        if (isCodecAbility(abilityName)) {
            if (typeArgs.size == 2) {
                // Old-style: 2 type args (format, data)
                val formatType = typeArgs[0]
                val dataType = typeArgs[1]
                if (checkCodecAbilityImplementation(abilityName, formatType, dataType)) {
                    return true
                }
            } else if (typeArgs.size == 1 && abilityName in setOf("EncodeRepr", "DecodeRepr")) {
                // Roc-style single-parameter ability: EncodeRepr 'a or DecodeRepr 'a
                // The format is provided at the method call site, not at constraint time.
                // We just need to verify the data type is serializable.
                val dataType = typeArgs[0]
                if (codecGenerator.isSerializable(dataType) && !containsVarType(dataType)) {
                    return true
                }
            }
        }

        // Search for implementation using structural type matching
        val impls = context.getAbilityImpls(abilityName)

        val hasImpl = impls.any { implInfo ->
            // Use structural matching - compare actual types against type annotations
            val annotations = implInfo.targetTypeAnnotations ?: return@any false

            if (annotations.size != typeArgs.size) return@any false

            // For generic impls, track type variable bindings during matching
            val typeVarBindings = mutableMapOf<String, MezzeType>()

            val matches = typeArgs.zip(annotations).all { (actualType, annotation) ->
                typeMatchesAnnotation(actualType, annotation, implInfo.typeVars, typeVarBindings)
            }

            if (!matches) return@any false

            // If this is a generic impl, check method-level constraints with resolved bindings
            if (typeVarBindings.isNotEmpty() && implInfo.operationConstraints.isNotEmpty()) {
                implInfo.operationConstraints.all { constraint ->
                    // Resolve the constraint's type args using bindings
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is IdentifierTypeAnnotation -> {
                                val bound = typeVarBindings[typeArg.name]
                                if (bound != null) bound
                                else typeAnnotationToMezzeType(typeArg)
                            }
                            else -> typeAnnotationToMezzeType(typeArg)
                        }
                    }
                    checkAbilityImplementation(constraint.abilityName, resolvedTypeArgs)
                }
            } else {
                true
            }
        }

        return hasImpl
    }

    /**
     * Check if a concrete MezzeType matches a TypeAnnotation.
     *
     * This enables structural matching for ability implementations.
     * For example, checking if `VariantType({Just: RecordType({val: Int})})`
     * matches `TypeApplicationAnnotation("Maybe", [IdentifierTypeAnnotation("Int")])`.
     *
     * OPTIMIZATION: Uses memoization cache to avoid repeated matching of same pairs.
     * Note: caching is skipped when type variables are in play (bindings are mutable).
     */
    private fun typeMatchesAnnotation(
        type: MezzeType,
        annotation: TypeAnnotation,
        typeVars: Set<String> = emptySet(),
        typeVarBindings: MutableMap<String, MezzeType> = mutableMapOf()
    ): Boolean {
        // Skip caching when type variables are involved (bindings are stateful)
        if (typeVars.isNotEmpty()) {
            return typeMatchesAnnotationImpl(type, annotation, typeVars, typeVarBindings)
        }
        // OPTIMIZATION: Check cache first
        val cacheKey = Pair(System.identityHashCode(type), System.identityHashCode(annotation))
        typeMatchesAnnotationCache[cacheKey]?.let { return it }

        val result = typeMatchesAnnotationImpl(type, annotation, typeVars, typeVarBindings)
        typeMatchesAnnotationCache[cacheKey] = result
        return result
    }

    /**
     * Implementation of typeMatchesAnnotation without caching.
     */
    private fun typeMatchesAnnotationImpl(
        type: MezzeType,
        annotation: TypeAnnotation,
        typeVars: Set<String> = emptySet(),
        typeVarBindings: MutableMap<String, MezzeType> = mutableMapOf()
    ): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check if this is a type variable from a generic impl
                if (annotation.name in typeVars) {
                    val existing = typeVarBindings[annotation.name]
                    if (existing != null) {
                        // Already bound — check consistency
                        existing == type
                    } else {
                        // First occurrence — bind it
                        typeVarBindings[annotation.name] = type
                        true
                    }
                }
                // Check if annotation refers to a primitive type
                else when (annotation.name) {
                    "Int" -> type is MezzeType.IntType
                    "Float" -> type is MezzeType.FloatType
                    "Str" -> type is MezzeType.StringType
                    "Bool" -> type is MezzeType.BoolType || isBoolVariant(type)
                    "Nat" -> type is MezzeType.NatType
                    "Frac" -> type is MezzeType.FracType
                    "Char" -> type is MezzeType.CharType
                    "Bytes" -> type is MezzeType.BytesType
                    "ByteBuffer" -> type is MezzeType.ByteBufferType
                    "Ordering" -> type is MezzeType.OrderingType
                    else -> {
                        // Check if annotation refers to a type alias
                        val typeDecl = typeDeclarations[annotation.name]
                        if (typeDecl != null) {
                            typeMatchesDeclaration(type, typeDecl)
                        } else {
                            // Check by type name
                            when (type) {
                                is MezzeType.RecordType -> type.typeName == annotation.name
                                is MezzeType.VariantType -> type.typeName == annotation.name
                                is MezzeType.TypeApplication -> type.constructor == annotation.name
                                else -> false
                            }
                        }
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Match type applications like Maybe Int
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        // Check type arguments
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation, typeVars, typeVarBindings)
                        }
                    }
                    type is MezzeType.VariantType && type.typeName == null -> {
                        // Anonymous variant - check if it matches the type application
                        // e.g., Just {val = 42} matching Maybe Int
                        val typeDecl = typeDeclarations[annotation.constructor]
                        if (typeDecl != null && typeDecl.definition is VariantDefinition) {
                            val definition = typeDecl.definition as VariantDefinition
                            val declConstructors = definition.constructors.map { it.name }.toSet()

                            // Check if variant's constructors are subset of declaration's constructors
                            if (!declConstructors.containsAll(type.constructors.keys)) {
                                return false
                            }

                            // Infer type arguments and check they match
                            val inferredArgs = inferTypeArgsFromVariant(type, typeDecl, definition)
                            if (inferredArgs != null && inferredArgs.size == annotation.args.size) {
                                inferredArgs.zip(annotation.args).all { (inferredType, argAnnotation) ->
                                    typeMatchesAnnotation(inferredType, argAnnotation, typeVars, typeVarBindings)
                                }
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                    type is MezzeType.SeqType && annotation.constructor == "Seq" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings)
                    }
                    type is MezzeType.ListType && annotation.constructor == "List" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings)
                    }
                    type is MezzeType.SetType && annotation.constructor == "Set" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings)
                    }
                    type is MezzeType.MapType && annotation.constructor == "Map" -> {
                        annotation.args.size == 2 &&
                        typeMatchesAnnotation(type.keyType, annotation.args[0], typeVars, typeVarBindings) &&
                        typeMatchesAnnotation(type.valueType, annotation.args[1], typeVars, typeVarBindings)
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (type !is MezzeType.RecordType) return false
                if (type.fields.size != annotation.entries.size) return false

                annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name] ?: return false
                    typeMatchesAnnotation(fieldType, entry.type, typeVars, typeVarBindings)
                }
            }
            is VariantTypeAnnotation -> {
                if (type !is MezzeType.VariantType) return false

                // Check that all annotation constructors exist in the type
                annotation.constructors.all { constructor ->
                    val concretePayload = type.constructors[constructor.name]
                    val dataType = constructor.dataType
                    if (dataType == null) {
                        concretePayload == null
                    } else if (concretePayload == null) {
                        false
                    } else {
                        typeMatchesAnnotation(concretePayload, dataType, typeVars, typeVarBindings)
                    }
                }
            }
            is SeqTypeAnnotation -> {
                type is MezzeType.SeqType && typeMatchesAnnotation(type.elementType, annotation.elementType, typeVars, typeVarBindings)
            }
            else -> false
        }
    }

    /**
     * Check if a type matches a type declaration (for non-parametric types).
     */
    private fun typeMatchesDeclaration(type: MezzeType, typeDecl: TypeDeclaration): Boolean {
        // Handle TypeApplication - check if the constructor matches the type declaration name
        if (type is MezzeType.TypeApplication) {
            return type.constructor == typeDecl.name && type.args.isEmpty()
        }

        return when (val definition = typeDecl.definition) {
            is RecordDefinition -> {
                if (type !is MezzeType.RecordType) return false
                type.typeName == typeDecl.name || type.fields.keys == definition.fields.map { it.name }.toSet()
            }
            is VariantDefinition -> {
                if (type !is MezzeType.VariantType) return false
                type.typeName == typeDecl.name ||
                definition.constructors.map { it.name }.toSet().containsAll(type.constructors.keys)
            }
            else -> false
        }
    }

    /**
     * Infer type arguments from a variant's concrete payload types by matching against
     * the type declaration's constructor patterns.
     *
     * For example, given:
     * - variantType with constructors {"Just" -> RecordType({val: Int})}
     * - typeDecl with typeParams = ["a"]
     * - definition with constructors [Just {val: 'a}]
     *
     * Returns [IntType] because 'a maps to Int.
     */
    private fun inferTypeArgsFromVariant(
        variantType: MezzeType.VariantType,
        typeDecl: TypeDeclaration,
        definition: VariantDefinition
    ): List<MezzeType>? {
        // Map type parameter names to their inferred types
        val typeArgMap = mutableMapOf<String, MezzeType>()

        // For each constructor in the variant, try to match against the type declaration
        for ((constructorName, concretePayload) in variantType.constructors) {
            // Find the matching constructor in the type declaration
            val declConstructor = definition.constructors.find { it.name == constructorName } ?: continue

            // If the constructor has a data type annotation, try to extract type parameter mappings
            val dataTypeAnnotation = declConstructor.dataType ?: continue
            if (concretePayload == null) continue

            // Extract type parameter mappings by comparing the annotation with the concrete type
            extractTypeParamMappings(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
        }

        // Return the type arguments in order
        return if (typeArgMap.size == typeDecl.typeParams.size) {
            typeDecl.typeParams.map { typeArgMap[it] ?: return null }
        } else {
            null
        }
    }

    /**
     * Extract type parameter mappings by comparing a type annotation with a concrete type.
     *
     * For example, matching annotation `{val: 'a}` with type `RecordType({val: Int})`
     * extracts mapping 'a -> Int.
     */
    private fun extractTypeParamMappings(
        annotation: TypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        mappings: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                // If this is a type parameter reference like 'a
                if (annotation.name in typeParams) {
                    // Map this type parameter to the concrete type
                    mappings[annotation.name] = concreteType
                }
            }
            is RecordTypeAnnotation -> {
                // Match record fields
                if (concreteType is MezzeType.RecordType) {
                    for (entry in annotation.entries) {
                        val fieldType = concreteType.fields[entry.name] ?: continue
                        extractTypeParamMappings(entry.type, fieldType, typeParams, mappings)
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Match type applications like List 'a
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((annArg, concreteArg) in annotation.args.zip(concreteType.args)) {
                        extractTypeParamMappings(annArg, concreteArg, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.SeqType && annotation.constructor == "Seq") {
                    // Handle Seq type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.ListType && annotation.constructor == "List") {
                    // Handle List type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                }
            }
            is VariantTypeAnnotation -> {
                // Match variant constructors
                if (concreteType is MezzeType.VariantType) {
                    for (constructor in annotation.constructors) {
                        val concretePayload = concreteType.constructors[constructor.name] ?: continue
                        val annPayload = constructor.dataType ?: continue
                        extractTypeParamMappings(annPayload, concretePayload, typeParams, mappings)
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                // Match function parameter and return types
                if (concreteType is MezzeType.FunctionType) {
                    extractTypeParamMappings(annotation.paramType, concreteType.paramType, typeParams, mappings)
                    extractTypeParamMappings(annotation.returnType, concreteType.returnType, typeParams, mappings)
                }
            }
            is SeqTypeAnnotation -> {
                // Match seq element types
                if (concreteType is MezzeType.SeqType) {
                    extractTypeParamMappings(annotation.elementType, concreteType.elementType, typeParams, mappings)
                }
            }
            // These annotation types don't contain type parameters we need to extract
            is QualifiedTypeAnnotation,
            is EffectRowAnnotation -> { /* no type parameters to extract */ }
        }
    }

    /**
     * Check if an ability name is a codec ability.
     */
    private fun isCodecAbility(abilityName: String): Boolean {
        return abilityName in listOf("Encode", "EncodeRepr", "Decode", "DecodeRepr", "StreamEncode", "StreamDecode")
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
            "Encode", "EncodeRepr" -> codecGenerator.generateEncodeImpl(formatType, dataType)
            "Decode", "DecodeRepr" -> codecGenerator.generateDecodeImpl(formatType, dataType)
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
            is MezzeType.SeqType -> containsVarType(type.elementType)
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
     *
     * OPTIMIZATION: Checks if already closed before rebuilding to avoid unnecessary allocations.
     */
    private fun closeOpenRecordsForCodec(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.RecordType -> {
                // OPTIMIZATION: Check if already closed and no fields need closing
                val needsClosing = type.rowVar != null || type.fields.values.any { needsClosingForCodec(it) }
                if (!needsClosing) {
                    return type
                }
                // Close the record (remove row variable) and recursively close nested types
                val closedFields = type.fields.mapValues { (_, fieldType) ->
                    closeOpenRecordsForCodec(fieldType)
                }
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.TypeApplication -> {
                // OPTIMIZATION: Check if any args need closing
                if (type.args.none { needsClosingForCodec(it) }) {
                    return type
                }
                // For Result {name: String} DecodeError, close nested records in args
                val closedArgs = type.args.map { closeOpenRecordsForCodec(it) }
                MezzeType.TypeApplication(type.constructor, closedArgs)
            }
            is MezzeType.VariantType -> {
                // OPTIMIZATION: Check if already closed and no payloads need closing
                val needsClosing = type.rowVar != null || type.constructors.values.any { it != null && needsClosingForCodec(it) }
                if (!needsClosing) {
                    return type
                }
                // Close records in variant payloads
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecordsForCodec(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.ListType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.ListType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SeqType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SeqType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SetType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SetType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SortedSetType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SortedSetType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.MapType -> {
                if (!needsClosingForCodec(type.keyType) && !needsClosingForCodec(type.valueType)) return type
                MezzeType.MapType(
                    closeOpenRecordsForCodec(type.keyType),
                    closeOpenRecordsForCodec(type.valueType)
                )
            }
            is MezzeType.SortedMapType -> {
                if (!needsClosingForCodec(type.keyType) && !needsClosingForCodec(type.valueType)) return type
                MezzeType.SortedMapType(
                    closeOpenRecordsForCodec(type.keyType),
                    closeOpenRecordsForCodec(type.valueType)
                )
            }
            is MezzeType.FunctionType -> {
                if (!needsClosingForCodec(type.paramType) && !needsClosingForCodec(type.returnType)) return type
                MezzeType.FunctionType(
                    paramType = closeOpenRecordsForCodec(type.paramType),
                    returnType = closeOpenRecordsForCodec(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.SuspendedType -> {
                if (!needsClosingForCodec(type.valueType)) return type
                MezzeType.SuspendedType(
                    valueType = closeOpenRecordsForCodec(type.valueType),
                    effects = type.effects
                )
            }
            // Types that don't contain nested types - return as-is
            is MezzeType.VarType -> type
            is MezzeType.EffectRow -> type
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> type
        }
    }

    /**
     * Check if a type needs closing for codec generation.
     * Returns true if the type contains any open records or variants.
     */
    private fun needsClosingForCodec(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.RecordType -> type.rowVar != null || type.fields.values.any { needsClosingForCodec(it) }
            is MezzeType.VariantType -> type.rowVar != null || type.constructors.values.any { it != null && needsClosingForCodec(it) }
            is MezzeType.TypeApplication -> type.args.any { needsClosingForCodec(it) }
            is MezzeType.ListType -> needsClosingForCodec(type.elementType)
            is MezzeType.SeqType -> needsClosingForCodec(type.elementType)
            is MezzeType.SetType -> needsClosingForCodec(type.elementType)
            is MezzeType.SortedSetType -> needsClosingForCodec(type.elementType)
            is MezzeType.MapType -> needsClosingForCodec(type.keyType) || needsClosingForCodec(type.valueType)
            is MezzeType.SortedMapType -> needsClosingForCodec(type.keyType) || needsClosingForCodec(type.valueType)
            is MezzeType.FunctionType -> needsClosingForCodec(type.paramType) || needsClosingForCodec(type.returnType)
            is MezzeType.SuspendedType -> needsClosingForCodec(type.valueType)
            is MezzeType.VarType -> false  // Type variables don't need closing
            is MezzeType.EffectRow -> false  // Effect rows don't need closing
            // Concrete primitive types don't need closing
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> false
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
     * Check if an ability can be derived or is intrinsically available for a type.
     * Handles:
     * - Inspect: universally available for ALL types
     * - VarType pass-through: polymorphic types are constrained at call site
     * - Eq/Ord derivation for composite types (records, variants, lists, seqs)
     * - Bool variant matching for logical operators
     * - Primitive type fallback: needed when .mz impl isn't in scope (e.g. stdlib
     *   modules that use == on Int without importing std::int)
     */
    /**
     * Check if an ability can be derived for a type.
     *
     * Handles:
     * - Inspect: universally available for ALL types
     * - VarType pass-through: polymorphic types are constrained at call site
     * - Eq/Ord derivation for composite types (records, variants, lists, seqs)
     * - Bool variant matching for logical operators
     * - Primitive type checks: needed because stdlib modules type-check without
     *   the prelude, and imported impls sometimes aren't in the ConstraintSolver's
     *   context during module loading. These are redundant when the prelude is
     *   loaded but harmless — they just short-circuit the context lookup.
     *
     * TODO: Remove primitive checks once the context propagation for imported
     * ability impls is fixed in ModuleTypeChecker/ConstraintSolver.
     */
    private fun canDeriveAbility(abilityName: String, type: MezzeType): Boolean {
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        if (unwrappedType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(unwrappedType, typeDeclarations)
            if (expanded != null) {
                unwrappedType = expanded
            }
        }

        return when (abilityName) {
            "Inspect" -> true

            "NumAdd", "NumMul", "NumDiv", "NumMod" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType || unwrappedType is MezzeType.FracType
            }
            "NumSub" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.FracType
            }

            "Eq" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType || unwrappedType is MezzeType.BoolType ||
                unwrappedType is MezzeType.NatType || unwrappedType is MezzeType.FracType ||
                unwrappedType is MezzeType.OrderingType || unwrappedType is MezzeType.BytesType ||
                unwrappedType is MezzeType.ByteBufferType ||
                unwrappedType is MezzeType.VarType || isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Eq", it) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Eq", it) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Eq", unwrappedType.elementType)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Eq", unwrappedType.elementType))
            }

            "Ord" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType || unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType ||
                unwrappedType is MezzeType.VarType ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Ord", it) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Ord", it) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Ord", unwrappedType.elementType)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Ord", unwrappedType.elementType))
            }

            "LogicalAnd", "LogicalOr", "LogicalNot" -> {
                unwrappedType is MezzeType.BoolType || isBoolVariant(unwrappedType) ||
                isBoolLikeVariant(unwrappedType)
            }

            "NumZero", "NumOne" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType || unwrappedType is MezzeType.FracType
            }
            "Default" -> {
                unwrappedType is MezzeType.IntType || unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType || unwrappedType is MezzeType.FracType ||
                unwrappedType is MezzeType.StringType || unwrappedType is MezzeType.BoolType ||
                unwrappedType is MezzeType.SeqType || unwrappedType is MezzeType.ListType
            }

            else -> false
        }
    }

    /**
     * Check if a type has an ability via derivation OR via an explicit impl in context.
     * Used for recursive Eq/Ord derivation on composite types.
     */
    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType): Boolean {
        return canDeriveAbility(abilityName, type) || checkAbilityImplementation(abilityName, listOf(type))
    }

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
                // NumNeg removed — neg is a method of NumSub
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
            "Decode", "DecodeRepr" -> {
                // Special handling for Decode/DecodeRepr - check if the data type is a type variable
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type for $abilityName call.
  The result type 'a in Result 'a DecodeError could not be determined from context.

  Hint: Add a type annotation to specify the expected type:
    let person: {name: String, age: Int} = DecodeRepr.decode_repr {val = json, fmt = json_fmt, config = default_config}

  Or use the result in a way that constrains its type:
    let person = DecodeRepr.decode_repr {val = json, fmt = json_fmt, config = default_config}
    person.name  # This would constrain person to have a 'name' field"""
                } else {
                    "Cannot decode $typeNames. Ensure the format implements DecodeFormat and the data type is serializable."
                }
            }
            "Encode", "EncodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    "Cannot infer encode source type for $abilityName call. Add a type annotation to the value being encoded."
                } else {
                    "Cannot encode $typeNames. Ensure the format implements Format and the data type is serializable."
                }
            }
            else -> "$typeNames does not implement '$abilityName'. To enable this, implement: impl $abilityName $primaryType where { ... }"
        }
    }

    private fun isBoolVariant(type: MezzeType): Boolean {
        return BuiltInAbilities.isBoolVariant(type)
    }

    /** Matches partial Bool variants (e.g., just {True} or just {False}) during constraint solving. */
    private fun isBoolLikeVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val keys = type.constructors.keys
        return keys.all { it == "True" || it == "False" } &&
               keys.isNotEmpty() &&
               type.constructors.values.all { it == null }
    }

    /**
     * Check if a type is concrete (not a type variable).
     */
    private fun isConcrete(type: MezzeType): Boolean {
        return when (type) {
            // Primitive types are always concrete
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> true
            // Compound types
            is MezzeType.VariantType -> type.rowVar == null && type.constructors.values.all { it == null || isConcrete(it) }
            is MezzeType.RecordType -> type.rowVar == null && type.fields.values.all { isConcrete(it) }
            is MezzeType.FunctionType -> isConcrete(type.paramType) && isConcrete(type.returnType)
            is MezzeType.TypeApplication -> type.args.all { isConcrete(it) }
            is MezzeType.SeqType -> isConcrete(type.elementType)
            is MezzeType.ListType -> isConcrete(type.elementType)
            is MezzeType.SetType -> isConcrete(type.elementType)
            is MezzeType.SortedSetType -> isConcrete(type.elementType)
            is MezzeType.MapType -> isConcrete(type.keyType) && isConcrete(type.valueType)
            is MezzeType.SortedMapType -> isConcrete(type.keyType) && isConcrete(type.valueType)
            is MezzeType.SuspendedType -> isConcrete(type.valueType)
            // Non-concrete types
            is MezzeType.VarType -> false
            is MezzeType.EffectRow -> false  // Effect rows are not value types
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
        // Special handling for DecodeRepr with unknown data type (Roc-style single-parameter ability)
        // This enables: let person = DecodeRepr.decode_repr {val = json_data, fmt = json_fmt, config = default_config}
        // where the result type is inferred from downstream usage
        if (abilityName == "DecodeRepr" && typeArgs.size == 1) {
            val dataType = typeArgs[0]

            // If data is a type variable, try to infer from generated impls
            if (dataType is MezzeType.VarType) {
                // Look for generated DecodeRepr impls - the data type is in targetTypes[0]
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    impl.abilityName == "DecodeRepr" &&
                    impl.targetTypes.isNotEmpty()
                }

                if (matchingImpls.size == 1) {
                    // Exactly one impl - we can infer the data type!
                    val impl = matchingImpls.first()
                    // Extract the data type from the impl's target types using structural conversion
                    val inferredType = convertAnnotationToType(impl.targetTypes[0])

                    if (inferredType != null) {
                        return Substitution.single(dataType.variable, inferredType)
                    }
                }
                // Multiple impls or none found - cannot infer unambiguously
                // This will result in an error asking for type annotation
            }
        }
        // Also handle old-style Decode with 2 params for backward compatibility
        if (abilityName == "Decode" && typeArgs.size == 2) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]

            // If format is concrete and data is a type variable, try to infer from generated impls
            if (formatType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                // Look for generated Decode impls for this format using STRUCTURAL matching
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    impl.abilityName == "Decode" &&
                    impl.targetTypes.size >= 2 &&
                    typeMatchesAnnotation(formatType, impl.targetTypes[0])
                }

                if (matchingImpls.size == 1) {
                    // Exactly one impl - we can infer the data type!
                    val impl = matchingImpls.first()
                    // Extract the data type from the impl's target types using structural conversion
                    val inferredType = convertAnnotationToType(impl.targetTypes[1])

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

        // Find implementations that match the pattern using STRUCTURAL matching
        // For NumAdd 'b Int, we want to find NumAdd T Int where T is concrete
        // Uses targetTypeAnnotations for structural comparison (not string-based targetTypes)
        val matchingImpls = mutableListOf<List<TypeAnnotation>>()

        for (impl in allImpls) {
            // Prefer structural matching via targetTypeAnnotations
            val annotations = impl.targetTypeAnnotations
            if (annotations == null || annotations.size != typeArgs.size) {
                continue  // Wrong arity or no structural type info
            }

            // Check if this implementation matches the pattern
            var matches = true
            for (i in typeArgs.indices) {
                val constraintType = typeArgs[i]

                // If constraint has a concrete type, impl must match structurally
                if (constraintType !is MezzeType.VarType) {
                    if (!typeMatchesAnnotation(constraintType, annotations[i])) {
                        matches = false
                        break
                    }
                }
                // If constraint has a type variable, impl can be anything (we'll infer it)
            }

            if (matches) {
                matchingImpls.add(annotations)
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
        val implAnnotations = matchingImpls.first()
        val substitutionMap = mutableMapOf<TypeVar, MezzeType>()

        for (i in typeArgs.indices) {
            val constraintType = typeArgs[i]
            if (constraintType is MezzeType.VarType) {
                // Infer this type variable from the implementation annotation
                // Convert TypeAnnotation to MezzeType structurally
                val inferredType = convertAnnotationToType(implAnnotations[i])
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
     * Convert a TypeAnnotation to MezzeType for bidirectional inference.
     * This is the structural alternative to parseTypeName().
     */
    private fun convertAnnotationToType(annotation: TypeAnnotation): MezzeType? {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check primitive types first
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                // For non-primitive identifiers, create a TypeApplication
                // (the type may be from an imported module, so we don't require it in typeDeclarations)
                MezzeType.TypeApplication(annotation.name, emptyList())
            }
            is TypeApplicationAnnotation -> {
                val args = annotation.args.mapNotNull { convertAnnotationToType(it) }
                if (args.size != annotation.args.size) {
                    null  // Some args couldn't be converted
                } else {
                    MezzeType.TypeApplication(annotation.constructor, args)
                }
            }
            is SeqTypeAnnotation -> {
                convertAnnotationToType(annotation.elementType)?.let { MezzeType.SeqType(it) }
            }
            is RecordTypeAnnotation -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in annotation.entries) {
                    val fieldType = convertAnnotationToType(entry.type) ?: return null
                    fields[entry.name] = fieldType
                }
                MezzeType.RecordType(fields)
            }
            is VariantTypeAnnotation -> {
                val constructors = mutableMapOf<String, MezzeType?>()
                for (ctor in annotation.constructors) {
                    val payload = ctor.dataType?.let { convertAnnotationToType(it) }
                    constructors[ctor.name] = payload
                }
                MezzeType.VariantType(constructors)
            }
            is FunctionTypeAnnotation -> {
                val paramType = convertAnnotationToType(annotation.paramType) ?: return null
                val returnType = convertAnnotationToType(annotation.returnType) ?: return null
                MezzeType.FunctionType(paramType, returnType)
            }
            // These don't convert to simple MezzeType
            is QualifiedTypeAnnotation,
            is EffectRowAnnotation -> null
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
                // Use principled expansion
                when (val expansion = expandTypeApplication(resolvedType, typeDeclarations)) {
                    is TypeExpansionResult.Record -> expansion.recordType
                    is TypeExpansionResult.Newtype -> {
                        // For newtypes, check if payload is a record
                        expansion.payloadType as? MezzeType.RecordType
                            ?: return ConstraintResult.Success(Substitution.empty)
                    }
                    else -> {
                        // Not a record type - this will be caught by HasField constraints
                        return ConstraintResult.Success(Substitution.empty)
                    }
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

        // If record type is a SuspendedType, unwrap it and solve on the inner value type
        // This handles cases like: (effectFn {}).fieldName
        // where effectFn returns SuspendedType(RecordType, effects)
        // and we need to check that fieldName exists on the inner RecordType.
        if (recordType is MezzeType.SuspendedType) {
            return solveHasField(
                constraint.copy(recordType = recordType.valueType),
                currentSubst
            )
        }

        // If record type is a TypeApplication (e.g., Person), expand it first
        if (recordType is MezzeType.TypeApplication) {
            // Use principled expansion based on definition type
            when (val expansion = expandTypeApplication(recordType, typeDeclarations)) {
                is TypeExpansionResult.Record -> {
                    return solveHasField(
                        constraint.copy(recordType = expansion.recordType),
                        currentSubst
                    )
                }
                is TypeExpansionResult.Variant -> {
                    // Recursively solve with the expanded variant type
                    // The code below handles single-constructor variants
                    return solveHasField(
                        constraint.copy(recordType = expansion.variantType),
                        currentSubst
                    )
                }
                is TypeExpansionResult.Newtype -> {
                    // Newtype: delegate field access to the payload
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
                    // Field must exist - provide helpful error with suggestions
                    val availableFields = recordType.fields.keys.toList()
                    val suggestion = com.mezze.language.typechecker.util.StringDistance.suggestOrList(
                        fieldName,
                        availableFields,
                        "fields"
                    )
                    return ConstraintResult.Failure(
                        "Record type does not have field '$fieldName'. $suggestion"
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

        // If variant type is a SuspendedType, unwrap it and solve on the inner value type
        // This handles cases like: if cond then throw {error} else Ok {val = x}
        // where the if-then-else unifies to SuspendedType(VariantType([Ok, Err]), <Exception>)
        // and we need to check that Ok is a valid constructor for the inner variant type.
        if (variantType is MezzeType.SuspendedType) {
            return solveHasConstructor(
                constraint.copy(variantType = variantType.valueType),
                currentSubst,
                originalConstraint ?: constraint
            )
        }

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
                    // Bool constructors don't take a payload - reject if one is provided
                    if (expectedPayloadType != null) {
                        return ConstraintResult.Failure(
                            "Constructor '$constructorName' does not take a payload. " +
                            "Use '$constructorName' instead of '$constructorName { ... }'."
                        )
                    }

                    // Use the closed Bool variant type
                    val boolType = MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null,  // Bool is closed
                        typeName = "Bool"
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

                    // For polymorphic types (with type params), don't set typeName
                    // because the actual type is a TypeApplication, not just the base variant.
                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                        typeName = if (declaringType.typeParams.isEmpty()) declaringType.name else null
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
                // Bool constructors don't take a payload - reject if one is provided
                if (expectedPayloadType != null) {
                    return ConstraintResult.Failure(
                        "Constructor '$constructorName' does not take a payload. " +
                        "Use '$constructorName' instead of '$constructorName { ... }'."
                    )
                }

                val boolType = MezzeType.VariantType(
                    constructors = mapOf("True" to null, "False" to null),
                    rowVar = null,  // Bool is closed
                    typeName = "Bool"
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

                    // For polymorphic types (with type params), don't set typeName
                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                        typeName = if (declaringTypeExpr.typeParams.isEmpty()) declaringTypeExpr.name else null
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
            // EXPRESSION CONTEXT: Check if constructor belongs to a declared type
            // If so, validate payload requirements and use the declared type
            if (!isPatternContext) {
                // Look up any type declaration that contains this constructor
                val typeForConstructor = findTypeDeclarationForConstructor(constructorName)

                if (typeForConstructor != null) {
                    val variantDef = typeForConstructor.definition as VariantDefinition
                    val constructorDef = variantDef.constructors.find { it.name == constructorName }

                    if (constructorDef != null) {
                        val declaredPayload = constructorDef.dataType

                        // GENERALIZED PAYLOAD VALIDATION:
                        // If the declared constructor has a payload, usage must have a payload
                        // If the declared constructor has no payload, usage must not have a payload
                        if (declaredPayload != null && expectedPayloadType == null) {
                            // Constructor requires payload but none provided
                            // Extract field names from the declared payload for a helpful error message
                            val fieldNames = when (declaredPayload) {
                                is RecordTypeAnnotation -> declaredPayload.entries.map { it.name }
                                else -> listOf("...")
                            }
                            val fieldList = fieldNames.joinToString(", ")
                            return ConstraintResult.Failure(
                                "Constructor '$constructorName' from type '${typeForConstructor.name}' requires a payload. " +
                                "Use '$constructorName { $fieldList }' instead of bare '$constructorName'.",
                                terminal = true
                            )
                        }

                        if (declaredPayload == null && expectedPayloadType != null) {
                            // Constructor has no payload but one was provided
                            return ConstraintResult.Failure(
                                "Constructor '$constructorName' from type '${typeForConstructor.name}' does not take a payload. " +
                                "Use '$constructorName' without '{ ... }'.",
                                terminal = true
                            )
                        }

                        // Payload presence matches - now check if payload structure matches (if applicable)
                        // For constructors with payload, verify the fields match
                        if (declaredPayload != null && expectedPayloadType != null) {
                            // Convert declared payload to MezzeType for field comparison
                            val typeParamMapping = typeForConstructor.typeParams.associate { param ->
                                param to MezzeType.VarType(TypeVar.fresh())
                            }
                            val declaredPayloadType = convertTypeAnnotation(declaredPayload, typeParamMapping)

                            // Check if expected payload has the required fields
                            if (declaredPayloadType is MezzeType.RecordType && expectedPayloadType is MezzeType.RecordType) {
                                val missingFields = declaredPayloadType.fields.keys - expectedPayloadType.fields.keys
                                if (missingFields.isNotEmpty()) {
                                    return ConstraintResult.Failure(
                                        "Constructor '$constructorName' payload is missing required fields: ${missingFields.joinToString(", ")}",
                                        terminal = true
                                    )
                                }
                            }
                        }

                        // Create the full declared variant type with all constructors
                        val typeParamMapping = typeForConstructor.typeParams.associate { param ->
                            param to MezzeType.VarType(TypeVar.fresh())
                        }

                        val constructors = variantDef.constructors.associate { ctor ->
                            val payloadType = ctor.dataType?.let { dataTypeAnnotation ->
                                convertTypeAnnotation(dataTypeAnnotation, typeParamMapping)
                            }
                            ctor.name to payloadType
                        }

                        // For polymorphic types (with type params), don't set typeName
                        // because the actual type is a TypeApplication, not just the base variant.
                        // This allows ability lookups to work correctly with Maybe Int, etc.
                        val declaredVariant = MezzeType.VariantType(
                            constructors = constructors,
                            rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                            typeName = if (typeForConstructor.typeParams.isEmpty()) typeForConstructor.name else null
                        )

                        var resultSubst = Substitution.single(variantType.variable, declaredVariant)

                        // Unify payload types if present
                        val declaredCtorPayload = constructors[constructorName]
                        if (expectedPayloadType != null && declaredCtorPayload != null) {
                            val unifyResult = unify(expectedPayloadType, declaredCtorPayload, typeDeclarations)
                            when (unifyResult) {
                                is UnificationResult.Success -> {
                                    resultSubst = resultSubst.compose(unifyResult.substitution)
                                }
                                is UnificationResult.Failure -> {
                                    return ConstraintResult.Failure(
                                        "Constructor '$constructorName' payload type mismatch: ${unifyResult.message}"
                                    )
                                }
                            }
                        }

                        return ConstraintResult.Success(resultSubst)
                    }
                }

                // No type declaration found for this constructor - fall through to create open variant
                // This handles truly anonymous constructors that don't belong to any declared type
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

            // Create variant with appropriate openness based on context:
            // - EXPRESSION CONTEXT: Create CLOSED variant (e.g., `Info` -> `| Info |`)
            //   A bare constructor in an expression is a specific value, not a row-polymorphic type
            // - PATTERN CONTEXT: Create open variant for row polymorphism
            //   Patterns may need to unify with other patterns via row variables

            if (isPatternContext) {
                // PATTERN CONTEXT: Create an open variant for row polymorphism
                // Patterns may need to unify with other patterns via row variables
                val newRowVar = RowVar.fresh()
                val newVariant = MezzeType.VariantType(
                    constructors = mapOf(constructorName to expectedPayloadType),
                    rowVar = newRowVar  // Open for row polymorphism
                )

                // Track the row var so it can be closed later
                // Pattern-created variants should be closed unless there's a wildcard (MustBeOpen)
                patternBasedRowVars.add(newRowVar)

                return ConstraintResult.Success(
                    Substitution.single(variantType.variable, newVariant)
                )
            } else {
                // EXPRESSION CONTEXT: Create a CLOSED variant
                // A bare constructor in an expression is a specific value, not a row-polymorphic type
                // e.g., `MathError {code = "x", message = "y"}` should be `| MathError {...} |` (closed)
                val newVariant = MezzeType.VariantType(
                    constructors = mapOf(constructorName to expectedPayloadType),
                    rowVar = null  // Closed - expression context creates a specific value
                )

                return ConstraintResult.Success(
                    Substitution.single(variantType.variable, newVariant)
                )
            }
        }

        // Special handling for BoolType - allows pattern matching with True/False
        // Bool is internally BoolType for optimization, but users can pattern match
        // on it as if it were `type Bool = | True | False |`
        if (variantType is MezzeType.BoolType) {
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
                        rowVar = variantType.rowVar,  // Keep the row variable
                        typeName = variantType.typeName  // Preserve type name
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
                    val availableConstructors = variantType.constructors.keys.toList()
                    val suggestion = com.mezze.language.typechecker.util.StringDistance.suggestOrList(
                        constructorName,
                        availableConstructors,
                        "constructors"
                    )
                    return ConstraintResult.Failure(
                        "Variant type does not have constructor '$constructorName'. $suggestion"
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
                    rowVar = if (shouldClose) null else type.rowVar,
                    // When closing a pattern-based variant that doesn't have a typeName,
                    // set a synthetic "<inferred>" name. This prevents the closed variant
                    // from merging with other variants during later unification.
                    // Example: `let f = {c} -> match c is Red -> 1 | Blue -> 2`
                    // creates `| Red, Blue |` which should reject `f { c = Green }`.
                    typeName = if (shouldClose && type.typeName == null) "<inferred>" else type.typeName
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
            is MezzeType.SeqType -> {
                MezzeType.SeqType(closeRowVarsInType(type.elementType, rowVarsToClose))
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
            is MezzeType.BoolType,
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
                sourceLocation = constraint.sourceLocation,
                isMatchCaseContext = constraint.isMatchCaseContext
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

            is Constraint.AssociatedTypeResolution -> Constraint.AssociatedTypeResolution(
                abilityName = constraint.abilityName,
                primaryTypeVar = constraint.primaryTypeVar,  // Type vars don't change
                associatedTypeVars = constraint.associatedTypeVars,  // Type vars don't change
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
            is Constraint.AssociatedTypeResolution -> this.context
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
        // Check primitive types first
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> "'${('a'.code + (type.variable.id % 26)).toChar()}"  // Show as 'a, 'b, etc.
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
            is MezzeType.SeqType -> "Seq ${formatType(type.elementType)}"
            is MezzeType.ListType -> "List ${formatType(type.elementType)}"
            is MezzeType.SetType -> "Set ${formatType(type.elementType)}"
            is MezzeType.SortedMapType -> "SortedMap ${formatType(type.keyType)} ${formatType(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet ${formatType(type.elementType)}"
            is MezzeType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
            is MezzeType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
            is MezzeType.EffectRow -> "<${type.effects.take(3).joinToString()}>"
            else -> type.toString()  // Fallback for any types handled by PrimitiveTypes
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
            is MezzeType.SeqType -> collectTypeVars(type.elementType)
            is MezzeType.ListType -> collectTypeVars(type.elementType)
            is MezzeType.SetType -> collectTypeVars(type.elementType)
            is MezzeType.SortedMapType -> collectTypeVars(type.keyType) + collectTypeVars(type.valueType)
            is MezzeType.SortedSetType -> collectTypeVars(type.elementType)
            is MezzeType.MapType -> collectTypeVars(type.keyType) + collectTypeVars(type.valueType)
            is MezzeType.SuspendedType -> collectTypeVars(type.valueType)
            // Primitives have no type variables
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
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
                rowVar = type.rowVar,
                typeName = type.typeName
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyVarSubstitution(it, substitution) }
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
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
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
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
            // For VarType, assume the field might exist - actual structure
            // will be verified during unification when we create the built-in type
            is MezzeType.VarType -> true
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
                // Check primitive types first (includes Bool -> MezzeType.BoolType)
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

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
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType, typeParamSubst),
                    returnType = convertTypeAnnotation(annotation.returnType, typeParamSubst),
                    effects = annotation.effects?.let { effectRow ->
                        // Validate hermetic effects (e.g., Mutation cannot coexist with other effects)
                        // Use allEffectNames to include both simple effects and effect annotations
                        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), null, effectRow.isPerformed)
                    },
                    abilityConstraints = emptyList()
                )
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type, typeParamSubst)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
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
            is SeqTypeAnnotation -> {
                MezzeType.SeqType(
                    elementType = convertTypeAnnotation(annotation.elementType, typeParamSubst)
                )
            }
            is EffectRowAnnotation -> {
                val rowVar = if (annotation.isPolymorphic) RowVar.fresh() else null
                MezzeType.EffectRow.simple(annotation.effects.toSet(), rowVar, annotation.isPerformed)
            }
            is QualifiedTypeAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.qualifiedName,
                    args = annotation.args.map { convertTypeAnnotation(it, typeParamSubst) }
                )
            }
        }
    }

}
