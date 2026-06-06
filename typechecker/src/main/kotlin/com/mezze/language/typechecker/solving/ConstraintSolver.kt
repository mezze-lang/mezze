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
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.TypeAnnotationMatcher

/**
 * Constraint solver - solves type constraints to infer types.
 *
 * This is PHASE 3 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver) ← WE ARE HERE
 * - PHASE 4: Emit TypedAST + V2 Pipeline (TypedAstBuilder, Monomorphizer, Resolver, Lowerer)
 * - PHASE 5: Validate types (TypeValidator, V2Validator)
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
    private val registry: com.mezze.language.typechecker.context.DeclarationRegistry = com.mezze.language.typechecker.context.DeclarationRegistry.EMPTY
) {
    /**
     * CodecGenerator for auto-deriving Encode/Decode implementations.
     */
    private val codecGenerator = CodecGenerator()

    /** Lightweight representation of a generated codec implementation.
     *  Replaces v1 ImplAbilityDeclaration — only stores metadata needed
     *  for dedup (contentHash) and bidirectional inference (abilityName, targetTypes). */
    data class GeneratedCodecImpl(
        val abilityName: String,
        val targetTypes: List<MezzeType>,  // [formatType, dataType]
        val contentHash: com.mezze.language.typedast.Hash
    )

    /**
     * Track generated codec implementations.
     *
     * When we see an Encode or Decode ability constraint for a serializable type
     * that doesn't have an explicit impl, we generate one and store it here.
     * Key: Content hash of the GeneratedCodecImpl
     */
    private val generatedCodecImpls = mutableMapOf<com.mezze.language.typedast.Hash, GeneratedCodecImpl>()

    /** Get the auto-derived codec impls metadata for downstream code generation. */
    fun getGeneratedCodecImpls(): Collection<GeneratedCodecImpl> = generatedCodecImpls.values
    /**
     * Tracked ability constraint entry — carries abilityName, optional content hash, typeArgs, and source location.
     *
     * The abilityHash (content hash of the AbilityDeclaration) is preferred for impl lookup
     * because it is stable across renames; fallback to name-based lookup when null.
     */
    private data class TrackedAbilityConstraint(
        val abilityName: String,
        val abilityHash: com.mezze.language.typedast.Hash?,
        val typeArgs: List<MezzeType>,
        val loc: com.mezze.language.parsedast.Loc?,
        // True when the constraint came from an explicit `with X 'a` annotation;
        // false when it was inferred from a method call. Used to enforce the
        // "annotated constraints can't be satisfied by anonymous structural
        // records" rule (#304) without breaking permissive inference (#216).
        val fromAnnotation: Boolean = false
    )

    /**
     * Track ability constraints on type variables.
     *
     * When we see AbilityRequired('a, Parse), we store: 'a -> [TrackedAbilityConstraint(Parse, hash, [typeArgs])]
     * Later during unification, when 'a = Int, we check that Int satisfies Parse ability.
     *
     * This is inspired by OldTypeChecker.kt:15318-15328 where ability constraints are tracked
     * on type variables during ability method calls.
     */
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<TrackedAbilityConstraint>>()

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

    /**
     * Index from constructor name to (ParsedTypeDecl, ParsedVariantDef) pairs.
     * Built lazily on first use of findVariantWithConstructors.
     * Enables O(1) lookup instead of O(n) iteration over all type declarations.
     */
    private val constructorIndex: Map<String, List<Pair<com.mezze.language.parsedast.ParsedTypeDecl, com.mezze.language.parsedast.ParsedVariantDef>>> by lazy {
        buildConstructorIndex()
    }

    /**
     * Build the constructor-to-variant index from type declarations.
     */
    private fun buildConstructorIndex(): Map<String, List<Pair<com.mezze.language.parsedast.ParsedTypeDecl, com.mezze.language.parsedast.ParsedVariantDef>>> {
        val index = mutableMapOf<String, MutableList<Pair<com.mezze.language.parsedast.ParsedTypeDecl, com.mezze.language.parsedast.ParsedVariantDef>>>()
        for (typeDecl in registry.allTypeDecls) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                for (constructor in definition.constructors) {
                    index.getOrPut(constructor.name) { mutableListOf() }
                        .add(Pair(typeDecl, definition))
                }
            }
        }
        return index
    }

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
     * Get the set of row variables that must remain open.
     */
    fun getMustBeOpenRowVars(): Set<RowVar> = mustBeOpenRowVars.toSet()

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
        val context: String? = null,
        /**
         * Stable error code — see [com.mezze.language.parsedast.diagnostics.ErrorCode].
         * Defaults to `UNCATEGORIZED` during the Phase B migration; sites
         * that have been migrated set a real code so the formatter can
         * surface it.
         */
        val code: com.mezze.language.parsedast.diagnostics.ErrorCode =
            com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED,
        /** Zero or more "what to do next" hints rendered as `= hint: …` lines. */
        val hints: List<String> = emptyList(),
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
        // Track the loc of the first contributing HasConstructor per typevar so
        // the synthesized Equality below carries a user-meaningful source
        // position (otherwise solver errors land at null loc → file:1:1).
        val firstLocByType = mutableMapOf<MezzeType, com.mezze.language.parsedast.Loc?>()

        for (constraint in constraints) {
            if (constraint is Constraint.HasConstructor) {
                val variantType = constraint.variantType
                // Only infer for type variables
                if (variantType is MezzeType.VarType) {
                    constructorsByType
                        .getOrPut(variantType) { mutableMapOf() }[constraint.constructorName] = constraint.payloadType
                    if (variantType !in firstLocByType) firstLocByType[variantType] = constraint.loc
                }
            }
        }

        // For each type variable with constructors, find matching variant type
        val inferredEqualities = mutableListOf<Constraint.Equality>()
        val errors = mutableListOf<String>()

        for ((typeVar, constructorsWithPayloads) in constructorsByType) {
            val constructorNames = constructorsWithPayloads.keys
            // Find variant type declaration that has ALL these constructors
            val result = findVariantWithConstructors(
                constructorNames,
                constructorsWithPayloads,
                loc = firstLocByType[typeVar],
            )

            when {
                result is VariantInferenceResult.Success -> {
                    // Successfully inferred variant type
                    inferredEqualities.add(
                        Constraint.Equality(
                            left = typeVar,
                            right = result.variantType,
                            context = "Inferred from constructors: ${constructorNames.joinToString()}",
                            loc = firstLocByType[typeVar],
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
        constructorPayloads: Map<String, MezzeType?> = emptyMap(),
        loc: com.mezze.language.parsedast.Loc? = null,
    ): VariantInferenceResult? {
        // OPTIMIZATION: Use constructor index for O(1) lookup of candidate variants
        // Collect all variant declarations that have at least one of the constructors
        val rawCandidates = mutableSetOf<Pair<com.mezze.language.parsedast.ParsedTypeDecl, com.mezze.language.parsedast.ParsedVariantDef>>()
        for (constructorName in constructors) {
            constructorIndex[constructorName]?.let { variants ->
                rawCandidates.addAll(variants)
            }
        }

        // #148: filter to aliases whose name is in scope at the use site.
        // The constructorIndex covers EVERY known type declaration (local + all
        // imports including auto-imports for expansion). But an alias only acts
        // as a nominal wall when its name is in scope (locally declared or
        // explicitly imported). If no in-scope alias matches, fall through to
        // the anonymous/structural variant path — letting downstream row closure
        // (closePatternBasedVariants) pin the variant if it's a match scrutinee.
        val inScope = com.mezze.language.typechecker.solving.inScopeAliasNamesForSolverOrNull()
        val candidateVariants = if (inScope != null) {
            rawCandidates.filterTo(mutableSetOf()) { (typeDecl, _) ->
                typeDecl.name in inScope
            }
        } else rawCandidates

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
                            context = "Constructor '${constructorDef.name}' payload type",
                            loc = loc,
                        )
                    )
                }
            }

            if (definition.isOpen) {
                // OPEN variant: Accept if SOME constructors match
                val variantType = if (freshArgs.isNotEmpty()) {
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(typeName, freshArgs, typeHash = typeDecl.contentHash)
                } else {
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(typeName, emptyList(), typeHash = typeDecl.contentHash)
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
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(typeName, freshArgs, typeHash = typeDecl.contentHash)
                } else {
                    // Non-parametric variant type
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(typeName, emptyList(), typeHash = typeDecl.contentHash)
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
            val (validationError, errorLoc) = validationResult
            errors.add(
                ConstraintError(
                    message = validationError,
                    constraint = Constraint.Equality(MezzeType.IntType, MezzeType.IntType, loc = errorLoc),
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
        // Unwrap SuspendedType — substitution can reintroduce it even though
        // generate() strips it at the constraint-generation boundary.
        val left = if (constraint.left is MezzeType.SuspendedType) (constraint.left as MezzeType.SuspendedType).valueType else constraint.left
        val right = if (constraint.right is MezzeType.SuspendedType) (constraint.right as MezzeType.SuspendedType).valueType else constraint.right

        DebugLogger.debug(Category.ConstraintSolver) {
            "solveEquality: $left = $right (${constraint.context})"
        }

        val result = unify(left, right, registry)

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
     * Append a structured "missing/unexpected" reason to a solver header
     * when the unifier failed on a closed-record field-set mismatch
     * (possibly nested inside function/record wrappers).
     *
     * Discriminates structurally on the failed types — NOT on message
     * prefixes. Walks `failure.type1` and `failure.type2` in lockstep,
     * descending through matching function/record wrappers, until it
     * finds the deepest pair of closed records with mismatched field
     * sets. Returns the diff (missing on the expected side, extra on
     * the actual side) as actionable wording.
     *
     * Falls back to the bare header when no such mismatch is found —
     * the header's expected/actual type rendering already says enough
     * for primitive or compatible-shape mismatches.
     */
    private fun appendStructuredReason(header: String, failure: UnificationResult.Failure): String {
        val t1 = failure.type1 ?: return header
        val t2 = failure.type2 ?: return header

        // Prefer the finest-grained reason: a single field whose type
        // disagrees between actual and expected. Falls back to the
        // field-set diff for missing/unexpected fields.
        findFieldTypeMismatch(t1, t2)?.let { (path, actualLeaf, expectedLeaf) ->
            val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
            val expectedStr = TypeDiffPrinter.formatType(expectedLeaf, 0, rename)
            val actualStr = TypeDiffPrinter.formatType(actualLeaf, 0, rename)
            return "$header — field `$path` expects $expectedStr but got $actualStr"
        }
        val mismatch = findClosedRecordMismatch(t1, t2) ?: return header
        val (expected, actual) = mismatch
        val missing = (expected.fields.keys - actual.fields.keys).map { "'$it'" }
        val extra = (actual.fields.keys - expected.fields.keys).map { "'$it'" }
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) parts.add("missing ${missing.joinToString(", ")}")
        if (extra.isNotEmpty()) parts.add("unexpected ${extra.joinToString(", ")}")
        return if (parts.isEmpty()) header else "$header — ${parts.joinToString("; ")}"
    }

    /**
     * Walk two types in lockstep, looking for the deepest record field
     * whose leaf types concretely disagree (e.g. `Int` vs `Str`).
     *
     * Returns (fieldPath, actualLeaf, expectedLeaf) on hit, or null when
     * the disagreement isn't pinpointable to a single field (e.g. tyvars
     * involved, or shapes differ above the field level — which the
     * existing [findClosedRecordMismatch] already handles).
     *
     * Descends through matching function wrappers (param and return type)
     * and matching record wrappers (only when the field sets are identical,
     * so we're past the field-set-diff case).
     */
    private fun findFieldTypeMismatch(
        actual: MezzeType,
        expected: MezzeType,
        path: String = "",
    ): Triple<String, MezzeType, MezzeType>? {
        // Skip when either side is a tyvar — we want concrete disagreement.
        if (actual is MezzeType.VarType || expected is MezzeType.VarType) return null

        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            findFieldTypeMismatch(actual.paramType, expected.paramType, path)?.let { return it }
            findFieldTypeMismatch(actual.returnType, expected.returnType, path)?.let { return it }
            return null
        }
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            if (actual.fields.keys != expected.fields.keys) return null  // field-set diff handled elsewhere
            for (fieldName in actual.fields.keys) {
                val childPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                val a = actual.fields[fieldName]!!
                val e = expected.fields[fieldName]!!
                // Deeper hit takes priority — keep walking even if the
                // current pair already disagrees, so we report the most
                // specific path.
                findFieldTypeMismatch(a, e, childPath)?.let { return it }
                // No deeper record mismatch; if this leaf disagrees and
                // neither side is a tyvar, report it.
                if (a !is MezzeType.RecordType && a !is MezzeType.FunctionType &&
                    e !is MezzeType.RecordType && e !is MezzeType.FunctionType &&
                    a !is MezzeType.VarType && e !is MezzeType.VarType &&
                    a != e
                ) {
                    return Triple(childPath, a, e)
                }
            }
        }
        return null
    }

    /**
     * Walk two types in lockstep looking for the deepest pair of closed
     * records with mismatched field sets. Returns (expected, actual) or
     * null when the shapes don't reveal a record-field mismatch.
     *
     * Convention: `actual` came from the user (the "got" side), `expected`
     * came from the signature (the "wanted" side). Matches the way the
     * solver labels type1=actual / type2=expected at its call sites.
     */
    private fun findClosedRecordMismatch(
        actual: MezzeType,
        expected: MezzeType,
    ): Pair<MezzeType.RecordType, MezzeType.RecordType>? {
        // Both records, both closed, different field sets — found it.
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            if (actual.rowVar == null && expected.rowVar == null && actual.fields.keys != expected.fields.keys) {
                return expected to actual
            }
            // Same field set — descend into matching fields looking for a deeper one.
            if (actual.fields.keys == expected.fields.keys) {
                for (fieldName in actual.fields.keys) {
                    val nested = findClosedRecordMismatch(actual.fields[fieldName]!!, expected.fields[fieldName]!!)
                    if (nested != null) return nested
                }
            }
            return null
        }
        // Both functions — descend into params (most common path), then return type.
        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            findClosedRecordMismatch(actual.paramType, expected.paramType)?.let { return it }
            findClosedRecordMismatch(actual.returnType, expected.returnType)?.let { return it }
            return null
        }
        return null
    }

    /**
     * Generate a user-friendly error message based on structured error context.
     *
     * Uses ErrorContext when available, falls back to string-based context parsing
     * for backwards compatibility.
     */
    /**
     * If [actual] and [expected] are both function types and differ ONLY
     * in their effect rows (same param, same return, no other ability
     * differences), describe the effect diff in actionable English.
     * Returns null when the difference is structural elsewhere.
     *
     * Typical hit: annotation says `{x} -> Str <*>` but body has type
     * `{x} -> Str <Log>` — we want to tell the user "effect `Log` is
     * performed but not declared (or not handled)" instead of dumping
     * both full function types and making them squint at the row.
     */
    private fun describeEffectRowDifference(actual: MezzeType, expected: MezzeType): String? {
        if (actual !is MezzeType.FunctionType || expected !is MezzeType.FunctionType) return null

        // Param + return types must be structurally identical for this
        // to be a pure effect-row mismatch.
        if (actual.paramType != expected.paramType) return null
        if (actual.returnType != expected.returnType) return null
        if (actual.abilityConstraints != expected.abilityConstraints) return null

        val actualEffects = actual.effects?.effects ?: emptySet()
        val expectedEffects = expected.effects?.effects ?: emptySet()
        if (actualEffects == expectedEffects) return null  // identical rows; some other field differs

        val extra = (actualEffects - expectedEffects).sorted()    // body performs, annotation missed
        val missing = (expectedEffects - actualEffects).sorted()  // annotation promises, body doesn't deliver

        return when {
            extra.isNotEmpty() && missing.isEmpty() -> {
                val names = extra.joinToString(", ") { "<$it>" }
                if (extra.size == 1) {
                    "the body performs effect $names but the annotation doesn't allow it. " +
                    "Either add $names to the return type (e.g. `... -> Str $names`), " +
                    "or wrap the call in a `perform <handler> in do ... end` block."
                } else {
                    "the body performs effects $names but the annotation doesn't allow them. " +
                    "Either declare them on the return type, or install handlers."
                }
            }
            missing.isNotEmpty() && extra.isEmpty() -> {
                val names = missing.joinToString(", ") { "<$it>" }
                "the annotation promises effects $names but the body never performs them. " +
                "Drop the unused effects from the annotation."
            }
            else -> {
                val extraTxt = if (extra.isNotEmpty()) "body performs ${extra.joinToString(", ") { "<$it>" }}" else null
                val missingTxt = if (missing.isNotEmpty()) "annotation expects ${missing.joinToString(", ") { "<$it>" }}" else null
                "effect rows don't match — ${listOfNotNull(missingTxt, extraTxt).joinToString("; ")}."
            }
        }
    }

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

                // Prefer a focused field-level message when we can pinpoint
                // a single field whose leaf type disagrees. Skips the noisy
                // full function-type rendering (and any fresh tyvar names
                // like `'m296`) for the common case.
                val fieldMismatch = findFieldTypeMismatch(type1, type2)
                if (fieldMismatch != null) {
                    val (path, actualLeaf, expectedLeaf) = fieldMismatch
                    val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                    val expectedStr = TypeDiffPrinter.formatType(expectedLeaf, 0, rename)
                    val actualStr = TypeDiffPrinter.formatType(actualLeaf, 0, rename)
                    val callSite = when {
                        funcName != null -> " in call to `$funcName`"
                        else -> ""
                    }
                    return "Field `$path` expects $expectedStr but got $actualStr$callSite"
                }

                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

                val header = if (argName != null && funcName != null) {
                    "Type mismatch for argument '$argName' in call to '$funcName': expected $expected but got $actual"
                } else if (argName != null) {
                    "Type mismatch for argument '$argName': expected $expected but got $actual"
                } else if (funcName != null) {
                    "Type mismatch in call to '$funcName': expected $expected but got $actual"
                } else {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
                // Append the unifier's structured reason when it carries
                // user-friendly info (e.g. "record fields don't match:
                // missing 'x'; unexpected 'y'"). Without this, downstream
                // sees the full record types in the header but loses the
                // exact "missing/unexpected" wording that pinpoints the fix.
                return appendStructuredReason(header, failure)
            }
            is ErrorContext.TypeAnnotation -> {
                val bindingName = errorContext.bindingName

                // If the only difference is the effect row, give a much
                // more focused message — users hit this constantly when
                // a function body performs an effect they forgot to
                // declare on the annotation. Dumping both full function
                // types side-by-side just makes them squint to spot
                // the `<Log>` vs `<*>` difference.
                describeEffectRowDifference(type1, type2)?.let { reason ->
                    return "Type annotation mismatch for '$bindingName': $reason"
                }

                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

                return if (errorContext.isReturnType) {
                    "Return type mismatch in '$bindingName': declared as $expected but returns $actual"
                } else {
                    "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual"
                }
            }
            is ErrorContext.FieldAccess -> {
                val fieldName = errorContext.fieldName
                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

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
     * Returns (error message, loc) pair if validation fails, null if all constraints are satisfied.
     * Also returns a substitution for any types inferred through bidirectional inference.
     */
    private fun validateAbilityConstraints(substitution: Substitution): Pair<String, com.mezze.language.parsedast.Loc?>? {
        for ((typeVar, abilityConstraints) in typeVarAbilityConstraints) {
            // Check if this type variable was bound to a concrete type
            // Use substitution.apply() to transitively resolve type variable chains
            val varType = MezzeType.VarType(typeVar)
            val boundType = substitution.apply(varType)

            // If the type variable is still a VarType (possibly different), it's not concrete yet
            if (boundType !is MezzeType.VarType) {
                // Type variable was bound to a concrete type - validate abilities
                for (tracked in abilityConstraints) {
                    val abilityName = tracked.abilityName
                    val typeArgs = tracked.typeArgs
                    val errorLoc = tracked.loc
                    // Skip Inspect - it's universally available
                    val trackedResolvedHash = tracked.abilityHash ?: WellKnownHashes.resolveHash(abilityName)
                    if (WellKnownHashes.isInspect(trackedResolvedHash)) continue
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
                    val closedTypeArgs = if (isCodecAbility(abilityName, tracked.abilityHash)) {
                        concreteTypeArgs.map { closeOpenRecordsForCodec(it) }
                    } else {
                        concreteTypeArgs
                    }

                    // Check if all types are now concrete
                    val allConcrete = closedTypeArgs.all { isConcrete(it) }

                    // Structural-record carriers can never satisfy an ability constraint —
                    // there is no syntax to write `impl X for {field: T}`. If the resolved
                    // primary type is a RecordType (open or closed) AND the constraint came
                    // from an explicit annotation, the constraint is definitively
                    // unsatisfiable. Without this check, an annotation like `with X 'a`
                    // plus a body using a method NOT in X causes 'a to be unified with a
                    // structural record, then deferred indefinitely (#304).
                    //
                    // The annotation guard preserves the permissive closed-record fallback
                    // for unannotated method-call inference (#216): `let f = {xs} -> xs.size {}`
                    // still infers `xs` as `{size: () -> 'r}` and accepts matching records.
                    val primaryArg = closedTypeArgs.firstOrNull()
                    if (tracked.fromAnnotation && primaryArg is MezzeType.RecordType) {
                        return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                    }

                    if (allConcrete) {
                        // Check if implementation exists; pass abilityHash for hash-preferred lookup.
                        val hasImpl = checkAbilityImplementation(abilityName, closedTypeArgs, tracked.abilityHash)
                        if (!hasImpl) {
                            return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                        }
                    } else {
                        // Some types are still type variables - try bidirectional inference!
                        // This is key for multi-parameter abilities like Convert 'a 'b
                        // When 'a = Int, we can look for impl Convert for Int String and infer 'b = String
                        val inferredSubst = tryBidirectionalInference(abilityName, closedTypeArgs, tracked.abilityHash)
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
        val resolvedHash = constraint.abilityHash ?: WellKnownHashes.resolveHash(abilityName)
        if (WellKnownHashes.isInspect(resolvedHash)) {
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
        // Use original constraint hash for tracking (used in impl lookup), not the resolved effectiveHash
        val fromAnnotation = constraint.context?.startsWith("Type annotation on ") == true
        val trackedEntry = TrackedAbilityConstraint(abilityName, constraint.abilityHash, trackingArgs, constraint.loc, fromAnnotation)
        typeVarAbilityConstraints.getOrPut(constraint.typeVar) { mutableListOf() }
            .add(trackedEntry)

        // Also track for any type variables in typeArgs
        // CRITICAL: Use trackingArgs (which includes ALL type arguments), not just typeArgs
        // Otherwise, when 'to is resolved in Convert 'from 'to, we'd only check ['to] instead of ['from, 'to]
        for (typeArg in typeArgs) {
            if (typeArg is MezzeType.VarType && typeArg.variable != constraint.typeVar) {
                val constraints = typeVarAbilityConstraints.getOrPut(typeArg.variable) { mutableListOf() }
                constraints.add(trackedEntry)
            }
        }

        // Apply substitution to type arguments to get concrete types if available
        // If all type args are concrete (not type variables), we can check the implementation now
        val allConcrete = trackingArgs.all { isConcrete(it) }

        if (allConcrete) {
            // All types are concrete - check if implementation exists.
            // Pass original constraint hash for impl lookup (not effectiveHash, which is for identification only).
            val hasImpl = checkAbilityImplementation(abilityName, trackingArgs, constraint.abilityHash)

            if (!hasImpl) {
                // No implementation found - this is a type error
                // Generate better error messages for specific cases
                val errorMessage = generateAbilityErrorMessage(abilityName, trackingArgs, constraint.loc)
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

        // Primary type is concrete - look up the matching impl AND extract
        // the impl's type-parameter bindings (so we can substitute references
        // to impl type-vars in associated-type bindings).
        val matchingImplPair = findMatchingAbilityImpl(abilityName, primaryType)
        DebugLogger.debug(Category.ConstraintSolver) {
            "  matchingImpl: ${matchingImplPair?.first?.abilityName} with bindings ${matchingImplPair?.first?.associatedTypeBindings}, " +
                "implTypeVarBindings: ${matchingImplPair?.second}"
        }

        if (matchingImplPair == null) {
            // No impl found - this is handled by AbilityRequired constraint
            // Just return success here (the error will be reported elsewhere)
            return ConstraintResult.Success(Substitution.empty)
        }

        val matchingImpl = matchingImplPair.first
        val implTypeVarBindings = matchingImplPair.second

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
                // Substitute references to the impl's type parameters first.
                // E.g. for `impl Tryable for Result 'a 'e having 'inner = 'a`,
                // the binding `'a` is the impl's type parameter — `implTypeVarBindings`
                // maps it to the receiver's actual type (e.g. Int).
                val concreteType = substituteAnnotationWithImplBindings(binding, implTypeVarBindings)

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
     * Convert an associated-type binding annotation to a MezzeType, substituting
     * references to the impl's type parameters with the bindings collected during
     * impl-matching.
     *
     * Example: impl Tryable for Result 'a 'e having 'inner = 'a, matched against
     * Result Int Str. The binding annotation `'a` resolves to `Int` via
     * implTypeVarBindings `{'a → Int, 'e → Str}`.
     */
    private fun substituteAnnotationWithImplBindings(
        annotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        implTypeVarBindings: Map<String, MezzeType>,
    ): MezzeType {
        // If the annotation is a bare type-variable name (e.g. `'a`), look it up.
        if (annotation is com.mezze.language.parsedast.ParsedNamedType) {
            val name = annotation.name
            // Try both with and without leading apostrophe.
            implTypeVarBindings[name]?.let { return it }
            implTypeVarBindings["'$name"]?.let { return it }
            if (name.startsWith("'")) {
                implTypeVarBindings[name.removePrefix("'")]?.let { return it }
            }
        }
        return typeAnnotationToMezzeType(annotation)
    }

    /**
     * Find a matching ability impl for the given ability and concrete type,
     * returning the impl's type-parameter bindings alongside it (e.g. for
     * `impl Tryable for Result 'a 'e`, matching against `Result Int Str`
     * returns `{'a → Int, 'e → Str}`). Used by [solveAssociatedTypeResolution]
     * to substitute associated-type bindings that reference the impl's type
     * parameters.
     */
    private fun findMatchingAbilityImpl(
        abilityName: String,
        primaryType: MezzeType
    ): Pair<TypingContext.AbilityImplInfo, Map<String, MezzeType>>? {
        val allImpls = context.getAbilityImpls(abilityName)

        for (impl in allImpls) {
            val annotations = impl.targetTypeAnnotations ?: continue
            if (annotations.size != 1) continue
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            val matched = TypeAnnotationMatcher.typeMatchesAnnotation(
                primaryType, annotations.first(), registry.typeDeclarations, impl.typeVars, typeVarBindings
            )
            if (matched) return impl to typeVarBindings
        }
        return null
    }

    /**
     * Convert a TypeAnnotation to MezzeType.
     * Used for resolving associated type bindings.
     */
    private fun typeAnnotationToMezzeType(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
            is com.mezze.language.parsedast.ParsedTypeApp -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { typeAnnotationToMezzeType(it) })
            is com.mezze.language.parsedast.ParsedSeqType -> MezzeType.SeqType(typeAnnotationToMezzeType(annotation.elementType))
            is com.mezze.language.parsedast.ParsedRecordType -> MezzeType.RecordType(fields = annotation.entries.associate { it.name to typeAnnotationToMezzeType(it.type) })
            is com.mezze.language.parsedast.ParsedFunctionType -> MezzeType.FunctionType(paramType = typeAnnotationToMezzeType(annotation.paramType), returnType = typeAnnotationToMezzeType(annotation.returnType))
            is com.mezze.language.parsedast.ParsedVariantType -> MezzeType.VariantType(constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> typeAnnotationToMezzeType(dt) } })
            is com.mezze.language.parsedast.ParsedQualifiedType -> com.mezze.language.typechecker.util.WellKnownTypeApp.of((annotation.modulePath + annotation.typeName).joinToString("::"), annotation.args.map { typeAnnotationToMezzeType(it) })
        }
    }

    /**
     * Check if an ability implementation exists for the given types.
     *
     * When [abilityHash] is non-null (content hash of the AbilityDeclaration), it is used
     * for hash-based impl lookup via [TypingContext.getAbilityImplsByDeclHash].
     * Falls back to name-based lookup via [TypingContext.getAbilityImpls] only when
     * hash is not available.
     */
    private fun checkAbilityImplementation(abilityName: String, typeArgs: List<MezzeType>, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean {
        val effectiveHash = abilityHash ?: WellKnownHashes.resolveHash(abilityName)
        // Derivation + fallback for abilities that can't come from context alone:
        // - Inspect: universally available for ALL types
        // - VarType pass-through: polymorphic types are constrained at call site
        // - Eq/Ord derivation for composite types (records, variants, lists)
        // - Bool variant matching for logical operators
        // - Primitive type impls as fallback (needed when stdlib modules don't have
        //   cross-type impls in scope, e.g. std::str using == on Int)
        if (typeArgs.size == 1) {
            if (canDeriveAbility(abilityName, typeArgs.first(), effectiveHash)) {
                return true
            }
        }

        // Check codec abilities (Encode, EncodeRepr, Decode, DecodeRepr, StreamEncode, StreamDecode)
        // For Roc-style design, EncodeRepr/DecodeRepr take 1 type arg (data type)
        // The format type comes from Format/DecodeFormat impl in scope
        if (isCodecAbility(abilityName, abilityHash)) {
            if (typeArgs.size == 2) {
                // 2 type args: ability DecodeRepr 'a for 'repr → typeArgs = [dataType, reprType]
                val dataType = typeArgs[0]
                val formatType = typeArgs[1]
                if (checkCodecAbilityImplementation(abilityName, formatType, dataType)) {
                    return true
                }
            } else if (typeArgs.size == 1 && (effectiveHash == WellKnownHashConstants.ABILITY_ENCODEREPR || effectiveHash == WellKnownHashConstants.ABILITY_DECODEREPR)) {
                // Roc-style single-parameter ability: EncodeRepr 'a or DecodeRepr 'a
                // The format is provided at the method call site, not at constraint time.
                // We still need to generate the codec impl so the compiler can emit FunctionIR.
                // The generated code uses Format/DecodeFormat ability dynamically
                // (encodeFormatImpl always returns id("Format")), so format type is irrelevant.
                val dataType = typeArgs[0]
                if (codecGenerator.isSerializable(dataType) && !containsVarType(dataType)) {
                    // Store codec impl metadata with dataType only (formatType=null sentinel).
                    // The TypeChecker will use a dummy format type when calling CodecGenerator.
                    val hashBuilder = com.mezze.language.ast.hashing.MezzeHashBuilder()
                    hashBuilder.append("codec_impl")
                    hashBuilder.append(abilityName)
                    hashBuilder.append("_single_param_")
                    hashBuilder.append(com.mezze.language.typechecker.hashing.ContentHashComputer.mezzeTypeToString(dataType))
                    val implKey = com.mezze.language.typedast.Hash(hashBuilder.build().toFullHexString())
                    if (!generatedCodecImpls.containsKey(implKey)) {
                        generatedCodecImpls[implKey] = GeneratedCodecImpl(
                            abilityName = abilityName,
                            targetTypes = listOf(dataType),  // Single type — no format
                            contentHash = implKey
                        )
                    }
                    return true
                }
            }
        }

        // Search for implementation — hash-based primary, name-based fallback.
        val impls = if (abilityHash != null) {
            val byHash = context.getAbilityImplsByDeclHash(abilityHash)
            if (byHash.isNotEmpty()) byHash else context.getAbilityImpls(abilityName)
        } else {
            context.getAbilityImpls(abilityName)
        }

        // Unwrap SuspendedType for matching — a suspended Int is still an Int for ability purposes
        val unwrappedTypeArgs = typeArgs.map { arg ->
            if (arg is MezzeType.SuspendedType) arg.valueType else arg
        }


        val hasImpl = impls.any { implInfo ->
            // Use structural matching - compare actual types against type annotations
            val annotations = implInfo.targetTypeAnnotations ?: return@any false

            if (annotations.size != unwrappedTypeArgs.size) return@any false

            // For generic impls, track type variable bindings during matching
            val typeVarBindings = mutableMapOf<String, MezzeType>()

            val matches = unwrappedTypeArgs.zip(annotations).all { (actualType, annotation) ->
                TypeAnnotationMatcher.typeMatchesAnnotation(actualType, annotation, registry.typeDeclarations, implInfo.typeVars, typeVarBindings)
            }

            if (!matches) return@any false

            // If this is a generic impl, check method-level constraints with resolved bindings
            if (typeVarBindings.isNotEmpty() && implInfo.operationConstraints.isNotEmpty()) {
                implInfo.operationConstraints.all { constraint ->
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is com.mezze.language.parsedast.ParsedNamedType -> {
                                val bound = typeVarBindings[typeArg.name]
                                if (bound != null) bound
                                else typeAnnotationToMezzeType(typeArg)
                            }
                            else -> typeAnnotationToMezzeType(typeArg)
                        }
                    }
                    checkAbilityImplementation(constraint.abilityName, resolvedTypeArgs, WellKnownHashes.resolveHash(constraint.abilityName))
                }
            } else {
                true
            }
        }

        return hasImpl
    }

    /**
     * Check if an ability is a codec ability (hash-only via WellKnownHashes).
     *
     * TODO: Remove name fallback once all call paths provide abilityHash.
     *       Currently `canDeriveOrHasImpl` calls `checkAbilityImplementation` without hash.
     */
    private fun isCodecAbility(abilityName: String, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean =
        WellKnownHashes.isCodecAbility(abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName))

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

        // isSerializable already verified — codec generation will succeed.
        // No need to actually build v1 Expression trees.

        // Compute content hash and use it as the key — simple structural hash
        val hashBuilder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        hashBuilder.append("codec_impl")
        hashBuilder.append(abilityName)
        hashBuilder.append(com.mezze.language.typechecker.hashing.ContentHashComputer.mezzeTypeToString(formatType))
        hashBuilder.append(com.mezze.language.typechecker.hashing.ContentHashComputer.mezzeTypeToString(dataType))
        val implKey = com.mezze.language.typedast.Hash(hashBuilder.build().toFullHexString())

        // Check if we've already generated this impl (same content = same hash)
        if (generatedCodecImpls.containsKey(implKey)) {
            return true
        }

        // Store lightweight metadata only — no v1 expression trees
        generatedCodecImpls[implKey] = GeneratedCodecImpl(
            abilityName = abilityName,
            targetTypes = listOf(formatType, dataType),
            contentHash = implKey
        )
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
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
            }
            is MezzeType.TypeApplication -> {
                // OPTIMIZATION: Check if any args need closing
                if (type.args.none { needsClosingForCodec(it) }) {
                    return type
                }
                // For Result {name: String} DecodeError, close nested records in args
                val closedArgs = type.args.map { closeOpenRecordsForCodec(it) }
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(type.constructor, closedArgs, type.typeHash)
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
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
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
            is MezzeType.NewtypeType -> type  // Newtypes don't auto-derive codec
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
            is MezzeType.NewtypeType -> false  // Newtypes don't auto-derive codec
        }
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
    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean {
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        // Track if the original type was a newtype — newtypes don't auto-derive Eq/Ord
        var isNewtype = unwrappedType is MezzeType.NewtypeType

        if (unwrappedType is MezzeType.TypeApplication) {
            // Check if this TypeApplication refers to a newtype declaration
            val typeDecl = registry.lookupType(unwrappedType.constructor)
                ?: com.mezze.language.typechecker.ModuleResolver.lookupTypeDeclaration(unwrappedType.constructor)
            if (typeDecl?.definition is com.mezze.language.parsedast.ParsedNewtypeDef) {
                isNewtype = true
            }

            // Try expanding to variant (type Color = | Red, Green |)
            val expandedVariant = expandTypeApplicationToVariant(unwrappedType, registry)
            if (expandedVariant != null) {
                unwrappedType = expandedVariant
            } else {
                // Try expanding to record (type Point = { x: Int, y: Int })
                val expandedRecord = expandTypeApplicationToRecord(unwrappedType, registry)
                if (expandedRecord != null) {
                    unwrappedType = expandedRecord
                }
            }
        }

        // Newtypes NEVER auto-derive Eq/Ord — requires explicit impl.
        // Only Inspect is universally available.
        if (isNewtype && !WellKnownHashes.isInspect(abilityHash)) {
            return false
        }

        return when {
            // Inspect is universally available — no impl needed (hash-only identification)
            WellKnownHashes.isInspect(abilityHash) -> true

            // Eq: VarType pass-through + derivation for composite types
            WellKnownHashes.isEq(abilityHash) -> {
                unwrappedType is MezzeType.VarType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash))
            }

            // Ord: VarType pass-through + derivation for composite types
            WellKnownHashes.isOrd(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash))
            }

            // Logical operators only work on Bool
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALOR ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALNOT -> {
                unwrappedType is MezzeType.BoolType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                isBoolLikeVariant(unwrappedType)
            }

            // All other abilities (NumAdd, NumSub, NumZero, Default, Into, etc.)
            // are resolved via context lookup in checkAbilityImplementation
            else -> false
        }
    }

    /**
     * Check if a type has an ability via derivation OR via an explicit impl in context.
     * Used for recursive Eq/Ord derivation on composite types.
     */
    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean {
        // Try derivation first, then impl lookup.
        // For impl lookup: try hash-based first, fall back to name-based if hash finds nothing
        // (some impls may not have abilityDeclHash populated when ability declaration is not in scope).
        if (canDeriveAbility(abilityName, type, abilityHash)) return true
        if (abilityHash != null && checkAbilityImplementation(abilityName, listOf(type), abilityHash)) return true
        return checkAbilityImplementation(abilityName, listOf(type))
    }

    /**
     * Generate a user-friendly error message for ability constraint failures.
     * Provides specific messages for common cases like arithmetic with function types.
     */
    private fun generateAbilityErrorMessage(abilityName: String, typeArgs: List<MezzeType>, loc: com.mezze.language.parsedast.Loc? = null): String {
        val hasFunctionType = typeArgs.any { it is MezzeType.FunctionType }
        val typeNames = typeArgs.map { formatType(it) }.joinToString(", ")
        val primaryType = typeArgs.firstOrNull()?.let { formatType(it) } ?: typeNames

        // Get original operator from AST (ParsedBinaryOp/ParsedUnaryOp preserved in SyntaxMetadata)
        val operator = loc?.let { com.mezze.language.ast.SyntaxMetadata.getOperatorSyntax(it) }

        if (hasFunctionType) {
            return if (operator != null) {
                "Cannot use '$operator' with a function. Functions cannot implement $abilityName."
            } else {
                "Functions cannot implement '$abilityName'."
            }
        }

        // If we have the original operator from the AST, use it directly.
        // Two cases:
        // (a) No matching impl exists anywhere → tell user how to implement
        //     the ability for their type.
        // (b) An impl for primaryType exists in the current view but isn't
        //     resolving here — the most common cause is a missing
        //     `use std::<module>::{...}` that brings the operator's stdlib
        //     helper into scope. Tell user that instead of suggesting they
        //     implement something they already have. (#221)
        if (operator != null) {
            val implExistsForType = abilityImplExistsForType(abilityName, typeArgs)
            return if (implExistsForType) {
                "Cannot use '$operator' with $typeNames. An `impl $abilityName for $primaryType` exists but is not " +
                    "visible at this call site — most likely the operator's stdlib helper isn't imported. " +
                    "Try adding `use std::${stdModuleForType(primaryType)}::{ * }` (or import the specific symbol used)." +
                    formatAbilityCandidates(abilityName)
            } else {
                "Cannot use '$operator' with $typeNames. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
            }
        }

        // Generic ability error — no hardcoded ability→operator mappings.
        // The operator (if any) comes from SyntaxMetadata above.
        // Special cases only for abilities that need unique hint text.
        return when (abilityName) {
            "Decode", "DecodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type. Add a type annotation:
  let result: MyType = decode {val = data, fmt = fmt}"""
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Encode", "EncodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    "Cannot infer encode source type. Add a type annotation to the value being encoded."
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Tryable" -> {
                // Tryable is almost always reached via `<-` desugar. Frame the
                // error around the user-facing syntax rather than the ability
                // name, but keep the underlying ability mentioned so the user
                // can implement it on a custom failure type if they really want.
                "Cannot use `<-` on $typeNames — no `Tryable` impl. `<-` works " +
                    "on `Option`, `Result`, or any type implementing `Tryable`. " +
                    "If you meant a plain binding, use `let x = ...` instead."
            }
            else -> "$typeNames does not implement '$abilityName'. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
        }
    }

    /**
     * Surface the existing impls of [abilityName] so the user can tell whether
     * (a) they're missing an impl for their type, or (b) they chose the wrong
     * ability. Returns a leading-newline-prefixed block, or "" when no impls
     * are in scope. Caps at 5 entries with an ellipsis to avoid wall-of-text.
     *
     * Audit task #208.
     */
    /**
     * Does any registered `impl <abilityName>` target the user's primary
     * type? Used to decide whether the operator error should suggest
     * "implement" vs "add a `use` import" (#221).
     *
     * Compares by formatted-type-string — same surface form the user
     * sees in their error message and in the "Available impls" line.
     * Conservative: false-positive favours the import message (more
     * helpful for the common case where the impl exists in stdlib).
     */
    private fun abilityImplExistsForType(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        val primaryType = typeArgs.firstOrNull() ?: return false
        val primaryName = formatType(primaryType)
        val impls = context.getAbilityImpls(abilityName)
        return impls.any { info ->
            val targets = info.targetTypeAnnotations
            val rendered = when {
                targets != null && targets.isNotEmpty() -> targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() -> info.targetTypes.joinToString(", ")
                else -> return@any false
            }
            // Exact match (e.g. "Int" == "Int") OR the impl's leading
            // segment matches (e.g. "Result Str Err" starts with "Result"
            // when user has Result of something).
            rendered == primaryName || rendered.split(' ').firstOrNull() == primaryName.split(' ').firstOrNull()
        }
    }

    /**
     * Best-effort guess at the stdlib module a primitive type lives in,
     * used in the "add a `use` import" hint. Returns "<unknown>" for
     * types we don't have a known mapping for — the hint then reads as
     * "use std::<unknown>::{...}" which still nudges the user toward the
     * import direction.
     */
    private fun stdModuleForType(primaryType: String): String = when {
        primaryType.startsWith("Int") -> "int"
        primaryType.startsWith("Float") -> "float"
        primaryType.startsWith("Nat") -> "nat"
        primaryType.startsWith("Bool") -> "bool"
        primaryType.startsWith("Str") -> "str"
        primaryType.startsWith("Char") -> "char"
        primaryType.startsWith("Frac") -> "frac"
        primaryType.startsWith("List") -> "collections::list"
        primaryType.startsWith("Map") -> "collections::map"
        primaryType.startsWith("Set") -> "collections::set"
        primaryType.startsWith("Seq") -> "collections::seq"
        primaryType.startsWith("Bytes") -> "collections::bytes"
        primaryType.startsWith("Option") -> "option"
        primaryType.startsWith("Result") -> "result"
        else -> "<unknown>"
    }

    private fun formatAbilityCandidates(abilityName: String): String {
        val impls = context.getAbilityImpls(abilityName)
        if (impls.isEmpty()) return ""
        // Render each impl's target-type annotation as the user would have
        // written it. Annotations are the source-of-truth; targetTypes is a
        // stringly-typed fallback.
        val rendered = impls.mapNotNull { info ->
            val targets = info.targetTypeAnnotations
            when {
                targets != null && targets.isNotEmpty() ->
                    targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() ->
                    info.targetTypes.joinToString(", ")
                else -> null
            }
        }.distinct()
        if (rendered.isEmpty()) return ""
        val shown = rendered.take(5)
        val suffix = if (rendered.size > shown.size) ", … (${rendered.size - shown.size} more)" else ""
        return "\n  Available impls: " + shown.joinToString("; ") { "impl $abilityName for $it" } + suffix
    }

    /**
     * Render a [ParsedTypeAnnotation] back to user-facing surface syntax.
     * Used by [formatAbilityCandidates] so the "Available impls" hint reads
     * like the user would have written it ("Result Str Err"), not like a
     * data-class dump ("ParsedNamedType(name=Result, loc=…)"). Loc info is
     * intentionally elided.
     */
    private fun renderAnnotation(a: com.mezze.language.parsedast.ParsedTypeAnnotation): String = when (a) {
        is com.mezze.language.parsedast.ParsedNamedType -> a.name
        is com.mezze.language.parsedast.ParsedQualifiedType -> {
            val prefix = a.modulePath.joinToString("::")
            val head = if (prefix.isEmpty()) a.typeName else "$prefix::${a.typeName}"
            if (a.args.isEmpty()) head else "$head ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        }
        is com.mezze.language.parsedast.ParsedTypeApp ->
            if (a.args.isEmpty()) a.constructor else "${a.constructor} ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        is com.mezze.language.parsedast.ParsedRecordType -> {
            val fields = a.entries.joinToString(", ") { "${it.name}: ${renderAnnotation(it.type)}" }
            if (a.isOpen) "{ $fields, ... }" else "{ $fields }"
        }
        is com.mezze.language.parsedast.ParsedVariantType -> {
            val ctors = a.constructors.joinToString(", ") { c ->
                val dt = c.dataType
                if (dt == null) c.name else "${c.name} ${renderAnnotation(dt)}"
            }
            "| $ctors |"
        }
        is com.mezze.language.parsedast.ParsedFunctionType ->
            "${renderAnnotation(a.paramType)} -> ${renderAnnotation(a.returnType)}"
        is com.mezze.language.parsedast.ParsedSeqType -> "[${renderAnnotation(a.elementType)}]"
        else -> a::class.simpleName?.removePrefix("Parsed")?.removeSuffix("Type") ?: "?"
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
            is MezzeType.NewtypeType -> isConcrete(type.wrappedType)
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
    private fun tryBidirectionalInference(abilityName: String, typeArgs: List<MezzeType>, abilityHash: com.mezze.language.typedast.Hash? = null): Substitution? {
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODEREPR && typeArgs.size == 1) {
            val dataType = typeArgs[0]

            // If data is a type variable, try to infer from generated impls
            if (dataType is MezzeType.VarType) {
                // Look for generated DecodeRepr impls - the data type is in targetTypes[0]
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODEREPR &&
                    impl.targetTypes.isNotEmpty()
                }

                if (matchingImpls.size == 1) {
                    // Exactly one impl - we can infer the data type!
                    val impl = matchingImpls.first()
                    // targetTypes stores MezzeType directly — no v1 conversion needed
                    val inferredType = impl.targetTypes[0]
                    return Substitution.single(dataType.variable, inferredType)
                }
                // Multiple impls or none found - cannot infer unambiguously
                // This will result in an error asking for type annotation
            }
        }
        // Also handle old-style Decode with 2 params for backward compatibility
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODE && typeArgs.size == 2) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]

            // If format is concrete and data is a type variable, try to infer from generated impls
            if (formatType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                // Look for generated Decode impls for this format using STRUCTURAL matching
                // targetTypes stores MezzeType directly — no v1 conversion needed
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODE &&
                    impl.targetTypes.size >= 2 &&
                    typeMatchesMezzeType(formatType, impl.targetTypes[0])
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[1]
                    return Substitution.single(dataType.variable, inferredType)
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
        val matchingImpls = mutableListOf<List<com.mezze.language.parsedast.ParsedTypeAnnotation>>()

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
                    if (!TypeAnnotationMatcher.typeMatchesAnnotation(constraintType, annotations[i], registry.typeDeclarations)) {
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
    private fun convertAnnotationToType(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): MezzeType? {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
            is com.mezze.language.parsedast.ParsedTypeApp -> { val args = annotation.args.mapNotNull { convertAnnotationToType(it) }; if (args.size != annotation.args.size) null else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, args) }
            is com.mezze.language.parsedast.ParsedSeqType -> convertAnnotationToType(annotation.elementType)?.let { MezzeType.SeqType(it) }
            is com.mezze.language.parsedast.ParsedRecordType -> { val fields = mutableMapOf<String, MezzeType>(); for (e in annotation.entries) { val ft = convertAnnotationToType(e.type) ?: return null; fields[e.name] = ft }; MezzeType.RecordType(fields) }
            is com.mezze.language.parsedast.ParsedVariantType -> MezzeType.VariantType(annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertAnnotationToType(dt) } })
            is com.mezze.language.parsedast.ParsedFunctionType -> { val p = convertAnnotationToType(annotation.paramType) ?: return null; val r = convertAnnotationToType(annotation.returnType) ?: return null; MezzeType.FunctionType(p, r) }
            is com.mezze.language.parsedast.ParsedQualifiedType -> null
        }
    }

    /** Check if two MezzeTypes match structurally (for impl lookup). */
    private fun typeMatchesMezzeType(expected: MezzeType, actual: MezzeType): Boolean {
        if (expected == actual) return true
        if (expected is MezzeType.TypeApplication && actual is MezzeType.TypeApplication) {
            return expected.constructor == actual.constructor
        }
        return false
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
        val result = unify(constraint.general, constraint.specific, registry)

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
                when (val expansion = expandTypeApplication(resolvedType, registry)) {
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
            when (val expansion = expandTypeApplication(recordType, registry)) {
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
            val result = unify(recordType, recordWithField, registry)
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
            val result = unify(actualFieldType, expectedFieldType, registry)
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
            val expanded = expandTypeApplicationToVariant(variantType, registry)
            if (expanded != null) {
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
                        constructors = mapOf(com.mezze.language.ast.BuiltinTypes.TRUE to null, com.mezze.language.ast.BuiltinTypes.FALSE to null),
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
                    val variantDef = declaringType.definition as com.mezze.language.parsedast.ParsedVariantDef

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

                    // Preserve nominal identity (typeName + typeArgs) even for polymorphic
                    // types. Earlier code dropped typeName when `typeParams.nonEmpty`, which
                    // lost the alias at outer scrutinee positions — visible as inlined
                    // `| Leaf, Node {...} |` in printed types. typeArgs carries the fresh
                    // tyvars/concrete args (in declaration order) so the printer can render
                    // `Tree 'a` or `Tree Int` instead of expanding the body.
                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                        typeName = declaringType.name,
                        typeArgs = typeParamMapping.values.toList(),
                        typeHash = declaringType.contentHash
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
                        val unifyResult = unify(expectedPayloadType, declaredPayload, registry)
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
                if (constructorName == com.mezze.language.ast.BuiltinTypes.CONS || constructorName == com.mezze.language.ast.BuiltinTypes.NIL) {
                    val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
                    val listType = MezzeType.ListType(elemTypeVar)

                    var resultSubst = Substitution.single(variantType.variable, listType)

                    // For Cons, unify the payload type
                    if (constructorName == com.mezze.language.ast.BuiltinTypes.CONS && expectedPayloadType != null) {
                        val expectedConsPayload = MezzeType.RecordType(mapOf(
                            "head" to elemTypeVar,
                            "tail" to listType
                        ))
                        val unifyResult = unify(expectedPayloadType, expectedConsPayload, registry)
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
                    constructors = mapOf(com.mezze.language.ast.BuiltinTypes.TRUE to null, com.mezze.language.ast.BuiltinTypes.FALSE to null),
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
            if (constructorName == com.mezze.language.ast.BuiltinTypes.CONS || constructorName == com.mezze.language.ast.BuiltinTypes.NIL) {
                val declaringTypeExpr = findTypeDeclarationForConstructor(constructorName)

                if (declaringTypeExpr != null) {
                    // User-defined List type - use VariantType
                    val variantDef = declaringTypeExpr.definition as com.mezze.language.parsedast.ParsedVariantDef

                    val typeParamMapping = declaringTypeExpr.typeParams.associate { param ->
                        param to MezzeType.VarType(TypeVar.fresh())
                    }

                    val constructors = variantDef.constructors.associate { ctor ->
                        val payloadType = ctor.dataType?.let { dataTypeAnnotation ->
                            convertTypeAnnotation(dataTypeAnnotation, typeParamMapping)
                        }
                        ctor.name to payloadType
                    }

                    // Preserve nominal identity (typeName + typeArgs) for polymorphic
                    // Cons/Nil-bearing types — see notes at the pattern-context site above.
                    val declaredVariant = MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                        typeName = declaringTypeExpr.name,
                        typeArgs = typeParamMapping.values.toList(),
                        typeHash = declaringTypeExpr.contentHash
                    )

                    var resultSubst = Substitution.single(variantType.variable, declaredVariant)

                    val declaredPayload = constructors[constructorName]

                    if (expectedPayloadType != null && declaredPayload != null) {
                        // Both pattern and constructor have payload - unify them
                        val unifyResult = unify(expectedPayloadType, declaredPayload, registry)
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
                if (constructorName == com.mezze.language.ast.BuiltinTypes.CONS && expectedPayloadType != null) {
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemTypeVar,
                        "tail" to listType
                    ))
                    val unifyResult = unify(expectedPayloadType, expectedConsPayload, registry)
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
                    val variantDef = typeForConstructor.definition as com.mezze.language.parsedast.ParsedVariantDef
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
                                is com.mezze.language.parsedast.ParsedRecordType -> declaredPayload.entries.map { it.name }
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

                        // Preserve nominal identity (typeName + typeArgs) regardless of
                        // polymorphism. Ability lookups key off constructor identity /
                        // typeHash, not the presence-or-absence of typeName, so this is
                        // safe and keeps the nominal name available for printing.
                        val declaredVariant = MezzeType.VariantType(
                            constructors = constructors,
                            rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
                            typeName = typeForConstructor.name,
                            typeArgs = typeParamMapping.values.toList(),
                            typeHash = typeForConstructor.contentHash
                        )

                        var resultSubst = Substitution.single(variantType.variable, declaredVariant)

                        // Unify payload types if present
                        val declaredCtorPayload = constructors[constructorName]
                        if (expectedPayloadType != null && declaredCtorPayload != null) {
                            val unifyResult = unify(expectedPayloadType, declaredCtorPayload, registry)
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
                    // #148: a closed variant whose name is NOT in scope and which
                    // is NOT a nominalWall (inline/inferred ascription) should
                    // behave structurally — we add the new constructor as we would
                    // for an open variant. Alias names from other modules act as
                    // labels, not walls, unless the user imported them by name.
                    val inScope = com.mezze.language.typechecker.solving.inScopeAliasNamesForSolverOrNull()
                    val name = variantType.typeName
                    val nameInScope = variantType.nominalWall ||
                        (name != null && (inScope == null || name in inScope))
                    if (!nameInScope) {
                        val newConstructors = variantType.constructors + (constructorName to expectedPayloadType)
                        val extendedVariant = MezzeType.VariantType(
                            constructors = newConstructors,
                            rowVar = null,  // stay closed
                            typeName = null,  // drop nominal label
                            typeHash = null
                        )
                        // Find the type variable that maps to this variant and update it.
                        var directTypeVar: TypeVar? = null
                        var chainedTypeVar: TypeVar? = null
                        for ((typeVar, mappedType) in currentSubst.mappings) {
                            if (mappedType == variantType) {
                                directTypeVar = typeVar
                                break
                            }
                            val resolvedMappedType = currentSubst.apply(mappedType)
                            if (resolvedMappedType == variantType && chainedTypeVar == null) {
                                chainedTypeVar = typeVar
                            }
                        }
                        val targetTypeVar = directTypeVar ?: chainedTypeVar
                        if (targetTypeVar != null) {
                            return ConstraintResult.Success(Substitution.single(targetTypeVar, extendedVariant))
                        }
                        return ConstraintResult.Success(Substitution.empty)
                    }

                    // Variant is closed AND in scope - constructor must exist
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

                val result = unify(payloadTypeToUnify, expectedPayloadType, registry)
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
            if (constructorName == com.mezze.language.ast.BuiltinTypes.CONS) {
                // Cons has payload: { head: 'a, tail: List 'a }
                if (expectedPayloadType != null) {
                    val elemType = variantType.elementType
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemType,
                        "tail" to variantType  // ListType with same element type
                    ))
                    val result = unify(expectedPayloadType, expectedConsPayload, registry)
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
            } else if (constructorName == com.mezze.language.ast.BuiltinTypes.NIL) {
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
                val pinningInferred = shouldClose && type.typeName == null
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
                    typeName = if (pinningInferred) "<inferred>" else type.typeName,
                    nominalWall = type.nominalWall || pinningInferred // #148
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
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                    constructor = type.constructor,
                    args = type.args.map { closeRowVarsInType(it, rowVarsToClose) },
                    typeHash = type.typeHash
                )
            }
            is MezzeType.SuspendedType -> {
                MezzeType.SuspendedType(
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose),
                    effects = type.effects
                )
            }
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                closeRowVarsInType(type.wrappedType, rowVarsToClose)
            )
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
                loc = constraint.loc,
                isMatchCaseContext = constraint.isMatchCaseContext,
                errorContext = constraint.errorContext,  // preserve structured discriminator
            )

            is Constraint.AbilityRequired -> Constraint.AbilityRequired(
                typeVar = constraint.typeVar, // Type vars don't change
                abilityName = constraint.abilityName,
                abilityHash = constraint.abilityHash,
                typeArgs = constraint.typeArgs.map { substitution.apply(it) },
                context = constraint.context,
                loc = constraint.loc
            )

            is Constraint.EffectUsed -> constraint // No types to substitute

            is Constraint.Subsumes -> Constraint.Subsumes(
                general = substitution.apply(constraint.general),
                specific = substitution.apply(constraint.specific),
                context = constraint.context,
                loc = constraint.loc
            )

            is Constraint.HasField -> Constraint.HasField(
                recordType = substitution.apply(constraint.recordType),
                fieldName = constraint.fieldName,
                fieldType = substitution.apply(constraint.fieldType),
                context = constraint.context,
                loc = constraint.loc,
                strictFieldCheck = constraint.strictFieldCheck
            )

            is Constraint.HasConstructor -> Constraint.HasConstructor(
                variantType = substitution.apply(constraint.variantType),
                constructorName = constraint.constructorName,
                payloadType = constraint.payloadType?.let { substitution.apply(it) },
                context = constraint.context,
                loc = constraint.loc,
                isPatternContext = constraint.isPatternContext
            )

            is Constraint.MustBeOpen -> Constraint.MustBeOpen(
                type = substitution.apply(constraint.type),
                bindingName = constraint.bindingName,
                context = constraint.context,
                loc = constraint.loc
            )

            is Constraint.RecordPatternExhaustive -> Constraint.RecordPatternExhaustive(
                recordType = substitution.apply(constraint.recordType),
                patternFields = constraint.patternFields,
                context = constraint.context,
                loc = constraint.loc
            )

            is Constraint.AssociatedTypeResolution -> Constraint.AssociatedTypeResolution(
                abilityName = constraint.abilityName,
                primaryTypeVar = constraint.primaryTypeVar,  // Type vars don't change
                associatedTypeVars = constraint.associatedTypeVars,  // Type vars don't change
                context = constraint.context,
                loc = constraint.loc
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
     * Delegates to prettyPrintType from TypePrinter.kt.
     */
    private fun formatType(type: MezzeType): String = prettyPrintType(type)

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
            is MezzeType.NewtypeType -> collectTypeVars(type.wrappedType)
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
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.TypeApplication -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                constructor = type.constructor,
                args = type.args.map { applyVarSubstitution(it, substitution) },
                typeHash = type.typeHash
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
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applyVarSubstitution(type.wrappedType, substitution)
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
    private fun findTypeDeclarationForConstructor(constructorName: String): com.mezze.language.parsedast.ParsedTypeDecl? {
        for (typeDecl in registry.allTypeDecls) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
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
    /**
     * Convert a ParsedTypeAnnotation to MezzeType with a substitution for type parameters.
     */
    private fun convertTypeAnnotation(
        annotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        typeParamSubst: Map<String, MezzeType>
    ): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                    typeParamSubst[annotation.name]
                        ?: typeParamSubst["'" + annotation.name.trimStart('\'')]
                        ?: MezzeType.VarType(TypeVar.freshWithName(annotation.name))
                } else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
            }
            is com.mezze.language.parsedast.ParsedFunctionType -> MezzeType.FunctionType(
                paramType = convertTypeAnnotation(annotation.paramType, typeParamSubst),
                returnType = convertTypeAnnotation(annotation.returnType, typeParamSubst),
                effects = annotation.effects?.let { ContextBuilder.validateHermeticEffects(it.allEffectNames); MezzeType.EffectRow.simple(it.effects.toSet(), null, it.isPerformed) }
            )
            is com.mezze.language.parsedast.ParsedRecordType -> MezzeType.RecordType(
                fields = annotation.entries.associate { it.name to convertTypeAnnotation(it.type, typeParamSubst) },
                rowVar = if (annotation.isOpen) RowVar.userOpen() else null
            )
            is com.mezze.language.parsedast.ParsedVariantType -> MezzeType.VariantType(constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertTypeAnnotation(dt, typeParamSubst) } })
            is com.mezze.language.parsedast.ParsedTypeApp -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertTypeAnnotation(it, typeParamSubst) })
            is com.mezze.language.parsedast.ParsedSeqType -> MezzeType.SeqType(convertTypeAnnotation(annotation.elementType, typeParamSubst))
            is com.mezze.language.parsedast.ParsedQualifiedType -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                (annotation.modulePath + annotation.typeName).joinToString("::"),
                annotation.args.map { convertTypeAnnotation(it, typeParamSubst) }
            )
        }
    }

}
