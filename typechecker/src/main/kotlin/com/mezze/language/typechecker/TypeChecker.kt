package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.typechecker.NativeFunctionTypes
import com.mezze.language.typechecker.application.SubstitutionApplier
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintGenerator
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.collectFreeTypeVars
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.module.*
import com.mezze.language.typechecker.monomorphization.MonomorphizationEngine
import com.mezze.language.typechecker.monomorphization.MonomorphizationResult
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.solving.Substitution
import com.mezze.language.typechecker.validation.TypeValidator
import com.mezze.language.typechecker.validation.ValidationResult
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.typechecker.continuation.ContinuationTransformer
import com.mezze.language.typechecker.hashing.ContentHashComputer
import java.nio.file.Path
import java.nio.file.Paths

/**
 * New type checker - orchestrates all 7 phases of type checking.
 *
 * This is the new, clean architecture for the Mezze type checker!
 *
 * ## 7-Phase Architecture:
 *
 * **PHASE 0: Module Resolution** (optional - only if imports present)
 * - Input: Program with UseDeclaration/PubUseDeclaration
 * - Output: ModuleImportContext + TypedModules
 * - What: Load and type-check imported modules
 * - Files: ModuleLoader.kt, ImportResolver.kt, ModuleTypeChecker.kt
 *
 * **PHASE 1: Build Context**
 * - Input: Program (+ optional ModuleImportContext)
 * - Output: TypingContext (immutable!)
 * - What: Build immutable context from type/ability/effect declarations
 * - File: ContextBuilder.kt
 *
 * **PHASE 2: Generate Constraints**
 * - Input: Program, TypingContext
 * - Output: List<Constraint>
 * - What: Generate type constraints without solving them
 * - Files: ConstraintGenerator.kt, ExpressionConstraints.kt, PatternConstraints.kt
 *
 * **PHASE 3: Solve Constraints**
 * - Input: List<Constraint>
 * - Output: Substitution
 * - What: Solve constraints using unification
 * - Files: ConstraintSolver.kt, Unification.kt, Substitution.kt
 *
 * **PHASE 4: Apply Substitution**
 * - Input: Program, Substitution
 * - Output: Program (with inferred types!)
 * - What: Update AST with final types
 * - File: SubstitutionApplier.kt
 *
 * **PHASE 5: Validate Types**
 * - Input: Program (typed), Substitution, Constraints
 * - Output: ValidationResult
 * - What: Check abilities, effects, exhaustiveness, variant constructors
 * - Files: TypeValidator.kt, AbilityResolver.kt, EffectValidator.kt
 *
 * **PHASE 6: Monomorphization**
 * - Input: Program (typed)
 * - Output: Program (with resolved call sites) + Map<String, LetBinding>
 * - What: Generate specialized versions of polymorphic functions
 * - Files: MonomorphizationEngine.kt, InstanceGenerator.kt, CallSiteUpdater.kt
 *
 * ## Design Principles:
 * - Pure functions (no mutation!)
 * - Immutable data structures
 * - Sealed result types (no nulls!)
 * - Exhaustive pattern matching
 * - Clear separation of concerns
 * - Each phase is testable independently
 *
 * ## Usage:
 * ```kotlin
 * val typeChecker = TypeChecker()
 * val result = typeChecker.checkProgram(program)
 *
 * when (result) {
 *     is TypeCheckResult.Success -> {
 *         // result.program has inferred types and resolved call sites!
 *         // result.monomorphicInstances contains specialized functions
 *         println("Type checking succeeded!")
 *     }
 *     is TypeCheckResult.Failure -> {
 *         for (error in result.errors) {
 *             println(error.message)
 *         }
 *     }
 * }
 * ```
 */
class TypeChecker(
    private val parentContext: TypingContext = createInitialContext()
) {
    // Transformer for converting perform blocks into state machines (for continuation support)
    private val continuationTransformer = ContinuationTransformer()

    /**
     * Result of type checking.
     *
     * Sealed type - exhaustive pattern matching, no nulls!
     */
    sealed class TypeCheckResult {
        /**
         * Type checking succeeded.
         */
        data class Success(
            val program: Program,                              // Program with inferred types and resolved call sites
            val substitution: Substitution,                    // Final type substitution
            val monomorphicInstances: Map<String, LetBinding>, // Specialized function instances
            val warnings: List<String> = emptyList()
        ) : TypeCheckResult()

        /**
         * Type checking failed.
         */
        data class Failure(
            val errors: List<TypeCheckError>
        ) : TypeCheckResult()
    }

    /**
     * Type checking error.
     *
     * Wraps errors from different phases.
     */
    data class TypeCheckError(
        val phase: String,
        val message: String,
        val sourceLocation: SourceLocation? = null
    )

    /**
     * Check a program through all 7 phases.
     *
     * This is the main entry point!
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    fun checkProgram(program: Program, currentPath: Path = Paths.get(".")): TypeCheckResult {
        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()

        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        val enhancedContext = if (hasImports(program)) {
            val moduleLoader = ModuleLoader.fromPath(currentPath.toString())
            // CRITICAL FIX: Pass the ModuleResolver to ModuleTypeChecker so it can update
            // the resolver's cache with real inferred types after type-checking
            val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                parentContext,
                moduleLoader.getResolver()
            )
            val importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

            val importResult = importResolver.resolveImports(program)

            when (importResult) {
                is ImportResolutionResult.Success -> {
                    importContext = importResult.importContext
                    // Merge import context with parent context
                    mergeImportContext(parentContext, importResult.importContext)
                }
                is ImportResolutionResult.Failure -> {
                    // Throw the new module error directly - tests will see it
                    throw importResult.errors.firstOrNull()
                        ?: TypeCheckError("Import failed with unknown error")
                }
            }
        } else {
            parentContext
        }

        // PHASE 0.5: Compute Content Hashes for Declarations
        // Compute hashes BEFORE building context so AbilityImplInfo.contentHash is populated.
        // This allows SubstitutionApplier to use content hashes directly instead of mangled lookup keys.
        val hashedProgram = computeDeclarationHashes(program)

        // PHASE 1: Build Context
        // Pass imported impls, abilities, and types to ContextBuilder
        // so performerToEffect map includes them and ability impl validation works with qualified names
        val contextBuilder = ContextBuilder()
        val buildResult = contextBuilder.build(
            program = hashedProgram,
            parentContext = enhancedContext,
            importedImpls = importContext?.importedImpls ?: emptyMap(),
            importedAbilities = importContext?.importedAbilities ?: emptyMap(),
            importedTypes = importContext?.importedTypes ?: emptyMap()
        )
        val context = buildResult.context

        // Build type aliases for pretty printing (include imported types)
        cachedTypeAliases = buildTypeAliases(hashedProgram, contextBuilder, importContext)

        // PHASE 2: Generate Constraints
        // Include both local ability declarations AND imported ability declarations
        val abilityNames = buildResult.abilityDeclarations.keys +
            (importContext?.importedAbilities?.keys ?: emptySet())
        // Include both local effect declarations AND imported effect declarations
        val effectNames = buildResult.effectDeclarations.keys +
            (importContext?.importedEffects?.keys ?: emptySet())

        DebugLogger.debugSection(Category.TypeChecker, "Abilities and Effects") {
            listOf(
                "abilityNames = $abilityNames",
                "effectNames = $effectNames"
            )
        }

        // Merge local and imported effect declarations
        val allEffectDeclarations = buildResult.effectDeclarations +
            (importContext?.importedEffects ?: emptyMap())

        // Merge local and imported type declarations
        // CRITICAL: Imported types must be available for TypeApplication expansion (e.g., Continuation)
        val allTypeDeclarations = buildResult.typeDeclarations +
            (importContext?.importedTypes ?: emptyMap())

        // CRITICAL: Merge local ability declarations with imported abilities
        // Include imported abilities with both simple names and qualified names (module::Ability)
        val importedAbilities = importContext?.importedAbilities ?: emptyMap()
        val allAbilityDeclarations = buildResult.abilityDeclarations + importedAbilities

        val constraintGenerator = ConstraintGenerator.create(
            typeDeclarations = allTypeDeclarations,
            performerToEffect = buildResult.performerToEffect,
            performerAddedEffects = buildResult.performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            abilityDeclarations = allAbilityDeclarations,
            effectDeclarations = allEffectDeclarations
        )
        val programConstraintResult = constraintGenerator.generateProgram(hashedProgram, context)
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors

        // Check for per-binding solve errors (e.g., type mismatches in variant payloads)
        if (perBindingSolveErrors.isNotEmpty()) {
            val errors = perBindingSolveErrors.map { error ->
                TypeCheckError(
                    phase = "Constraint Solving",
                    message = error.message,
                    sourceLocation = error.constraint.sourceLocation
                )
            }
            return TypeCheckResult.Failure(errors)
        }

        // Check for undefined variables BEFORE solving constraints
        // (If we wait until validation, constraint solving might fail first on those type variables)
        val undefinedVars = constraintResults.values.flatMap { it.undefinedVariables }
        if (undefinedVars.isNotEmpty()) {
            val validator = TypeValidator(
                context,
                allTypeDeclarations,
                allEffectDeclarations,
                buildResult.implDeclarations
            )
            val allVariables = context.getAllBindings()
            val errors = undefinedVars.map { undefinedVar ->
                // Special case: Check if this is an ability or effect being used as a value
                // Use allAbilityDeclarations and allEffectDeclarations which include imports
                val isBuiltInAbility = com.mezze.language.typechecker.abilities.BuiltInAbilities.isBuiltInAbility(undefinedVar.name)
                val isUserDefinedAbility = allAbilityDeclarations.containsKey(undefinedVar.name)
                val isEffect = allEffectDeclarations.containsKey(undefinedVar.name)

                // Check if this matches any ability operation name (including imported abilities)
                val matchingAbilityOp = allAbilityDeclarations.entries.firstOrNull { (_, ability) ->
                    ability.operations.any { it.name == undefinedVar.name }
                }

                // Check if this matches any effect operation name (including imported effects)
                val matchingEffectOp = allEffectDeclarations.entries.firstOrNull { (_, effect) ->
                    effect.operations.any { it.name == undefinedVar.name }
                }

                val message = if (isBuiltInAbility || isUserDefinedAbility) {
                    "Ability '${undefinedVar.name}' cannot be used as a value. " +
                    "Did you mean to access a method like ${undefinedVar.name}.method_name?"
                } else if (isEffect) {
                    "Effect '${undefinedVar.name}' cannot be used as a value. " +
                    "Did you mean to access an operation like ${undefinedVar.name}.operation_name?"
                } else if (matchingAbilityOp != null) {
                    // User wrote "show" instead of "Show.show"
                    val abilityName = matchingAbilityOp.key
                    "Undefined variable: ${undefinedVar.name}. " +
                    "Did you mean to call an ability method? Use: $abilityName.${undefinedVar.name}"
                } else if (matchingEffectOp != null) {
                    // User wrote "println" instead of "Log.println"
                    val effectName = matchingEffectOp.key
                    "Undefined variable: ${undefinedVar.name}. " +
                    "Did you mean to call an effect operation? Use: $effectName.${undefinedVar.name}"
                } else {
                    val similarNames = validator.findSimilarNames(undefinedVar.name, allVariables.keys.toList())
                    buildString {
                        append("Undefined variable or function: ${undefinedVar.name}")
                        if (similarNames.isNotEmpty()) {
                            append(". Did you mean: ${similarNames.joinToString()}")
                        }
                    }
                }
                TypeCheckError(
                    phase = "Validation",
                    message = message,
                    sourceLocation = undefinedVar.sourceLocation
                )
            }
            return TypeCheckResult.Failure(errors)
        }

        // Check for duplicate bindings BEFORE solving constraints
        val duplicateBindings = constraintResults.values.flatMap { it.duplicateBindings }
        if (duplicateBindings.isNotEmpty()) {
            val errors = duplicateBindings.map { duplicate ->
                TypeCheckError(
                    phase = "Validation",
                    message = "Duplicate binding: '${duplicate.name}' is already defined in this scope",
                    sourceLocation = duplicate.sourceLocation
                )
            }
            return TypeCheckResult.Failure(errors)
        }

        // Collect unused variable warnings (these are warnings, not errors)
        val unusedVarWarnings = constraintResults.values.flatMap { it.unusedVariables }.map { unused ->
            val locationInfo = unused.sourceLocation?.let { " at line ${it.line}" } ?: ""
            "Unused variable '${unused.name}'$locationInfo. Prefix with '_' to suppress this warning."
        }

        // Collect pattern-related warnings (e.g., float literal patterns)
        val patternWarnings = constraintResults.values.flatMap { it.patternWarnings }

        // Collect all constraints from all bindings (including ability constraints)
        // IMPORTANT: Filter out HasConstructor and HasField constraints!
        // These structural constraints are already solved during per-binding constraint solving
        // in ConstraintGenerator (Algorithm W). Re-solving them globally causes issues:
        // - Type variables that were correctly extended to merged variants get re-processed
        // - Different bindings' structural constraints can interfere with each other
        // - The variant extensions done per-binding are lost when global solve creates new variants
        val allConstraints = constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints }


        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, allTypeDeclarations, buildResult.abilityDeclarations)
        val solveResult = solver.solve(allConstraints)

        // Check if solving failed
        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                // Convert solver errors to type check errors
                // CRITICAL: Extract sourceLocation from the constraint that failed
                val errors = solveResult.errors.map { error ->
                    TypeCheckError(
                        phase = "Constraint Solving",
                        message = error.message,
                        sourceLocation = error.constraint.sourceLocation
                    )
                }
                return TypeCheckResult.Failure(errors)
            }
            is ConstraintSolver.SolveResult.Success -> {
                // Continue to validation
                val substitution = solveResult.substitution
                val warnings = solveResult.warnings.toMutableList()

                // Add unused variable warnings
                warnings.addAll(unusedVarWarnings)

                // Add pattern-related warnings
                warnings.addAll(patternWarnings)

                // Retrieve generated codec implementations (auto-derived Encode/Decode)
                // Compute content hashes for these generated impls immediately
                val generatedCodecImpls = solver.getGeneratedCodecImpls().mapValues { (_, impl) ->
                    if (impl.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(impl)
                        impl.copy(contentHash = hash.toFullHexString())
                    } else {
                        impl
                    }
                }

                val resolvedAbilities = solveResult.resolvedAbilityConstraints
                // CRITICAL: Merge per-binding mustBeOpenRowVars with global solver's results
                // The per-binding solves resolved MustBeOpen constraints to row variables,
                // but the global solver doesn't have access to these because HasConstructor
                // constraints were filtered out. We need both sets.
                val mustBeOpenRowVars = solveResult.mustBeOpenRowVars + perBindingMustBeOpenRowVars
                val mustBeOpenBindings = solveResult.mustBeOpenBindings
                val mustBeOpenTypes = solveResult.mustBeOpenTypes

                // NOTE: We do NOT filter out ability constraints on concrete types!
                // The old typechecker keeps ALL constraints for code generation (monomorphization).
                // Constraints are only filtered if they are on polymorphic types with type variables.
                // See OldTypeChecker.kt:10770-10803 removeRedundantAbilityConstraints()
                //
                // Ability constraints on concrete types (like NumMul Int) are needed for:
                // 1. Resolving which implementation to use during code generation
                // 2. Multi-parameter abilities validation
                //
                // We only keep constraints that implementations were validated during constraint solving.

                // CRITICAL FIX: Apply global substitution to constraint results
                // The per-binding solves in ConstraintGenerator may have left type variables unsolved,
                // but the global solve has the complete substitution. We need to update the constraint
                // results with the globally solved types before passing to SubstitutionApplier.


                // Need a ConstraintGenerator instance to call addAbilityConstraintsToFunctionType
                val tempGenerator = ConstraintGenerator.create(buildResult.typeDeclarations)

                val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
                    // Apply global substitution to get the final type
                    val resolvedType = substitution.apply(result.type)
                    // Add ability constraints to FunctionType
                    val typeWithAbilities = tempGenerator.addAbilityConstraintsToFunctionType(resolvedType, result.abilityConstraints, substitution)
                    result.copy(type = typeWithAbilities)
                }

                // TCO: Collect all tail call locations from constraint results
                val tailCallLocations = constraintResults.values
                    .flatMap { it.tailCalls }
                    .filterNotNull()
                    .toSet()

                // PHASE 4: Apply Substitution (moved before validation so expressions have types)
                val applier = SubstitutionApplier(
                    substitution,
                    globallyResolvedConstraintResults,
                    buildResult.performerToEffect,
                    mustBeOpenRowVars,
                    mustBeOpenBindings,
                    mustBeOpenTypes,
                    context.getAllAbilityImpls(),  // Pass ability implementations for resolving calls
                    allAbilityDeclarations,  // For resolving ability references (including imported)
                    allEffectDeclarations,  // For resolving effect references
                    allTypeDeclarations,  // For resolving variant constructors
                    tailCallLocations,  // TCO: Locations of self-recursive tail calls
                    buildResult.implDeclarations  // For populating resolvedHandlers with content hashes
                )
                // Add generated codec implementations to the program BEFORE applying substitution
                // This ensures they go through the same processing as user-defined impls
                val programWithCodecImpls = if (generatedCodecImpls.isNotEmpty()) {
                    Program(items = generatedCodecImpls.values.toList() + hashedProgram.items)
                } else {
                    hashedProgram
                }
                val typedProgram = applier.applyToProgram(programWithCodecImpls)

                // Note: Content hashes were computed in Phase 0.5 for user-written declarations.
                // Generated codec impls get their hashes computed inline in registerGeneratedCodecImpl.
                // SubstitutionApplier now uses implementationHash directly, so we don't need
                // updateResolvedAbilitiesWithHashes anymore.
                val finalProgram = typedProgram

                // PHASE 5: Validate Types (moved after substitution so we can check inferred types)
                val validator = TypeValidator(
                    context,
                    allTypeDeclarations,
                    allEffectDeclarations,
                    buildResult.implDeclarations
                )

                // First, validate the constraints (like HasConstructor)
                val constraintValidation = validator.validateConstraints(allConstraints, substitution)
                when (constraintValidation) {
                    is ValidationResult.Failure -> {
                        // Convert validation errors to type check errors
                        val errors = constraintValidation.errors.map { error ->
                            TypeCheckError(
                                phase = "Validation",
                                message = error.message(),
                                sourceLocation = error.location()
                            )
                        }
                        return TypeCheckResult.Failure(errors)
                    }
                    is ValidationResult.Success -> {
                        warnings.addAll(constraintValidation.warnings)
                    }
                }

                // Then, validate the typed program (patterns, abilities, effects, etc.)
                val validationResult = validator.validate(finalProgram, globallyResolvedConstraintResults, substitution)

                when (validationResult) {
                    is ValidationResult.Failure -> {
                        // Convert validation errors to type check errors
                        val errors = validationResult.errors.map { error ->
                            TypeCheckError(
                                phase = "Validation",
                                message = error.message(),
                                sourceLocation = error.location()
                            )
                        }
                        return TypeCheckResult.Failure(errors)
                    }
                    is ValidationResult.Success -> {
                        warnings.addAll(validationResult.warnings)

                        // PHASE 5.5: Transform continuations
                        // Convert perform block bodies from DoExpression to StateMachineExpression
                        // This is required for continuation-based effects like Choice with AllPaths
                        val programWithContinuations = transformContinuations(finalProgram)

                        // PHASE 6: Monomorphization
                        val monomorphizationEngine = MonomorphizationEngine.create()
                        val monomorphizationResult = monomorphizationEngine.monomorphize(programWithContinuations)

                        when (monomorphizationResult) {
                            is MonomorphizationResult.Failure -> {
                                // Convert monomorphization errors to type check errors
                                val errors = monomorphizationResult.errors.map { error ->
                                    TypeCheckError(
                                        phase = "Monomorphization",
                                        message = error.message,
                                        sourceLocation = error.sourceLocation
                                    )
                                }
                                return TypeCheckResult.Failure(errors)
                            }
                            is MonomorphizationResult.Success -> {
                                warnings.addAll(monomorphizationResult.warnings)

                                return TypeCheckResult.Success(
                                    program = monomorphizationResult.program,
                                    substitution = substitution,
                                    monomorphicInstances = monomorphizationResult.monomorphicInstances,
                                    warnings = warnings
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check a program for execution.
     *
     * This performs normal type-checking AND validates that main has the correct type:
     * {} -> {} <*>
     *
     * Use this when you want to run the program as an executable.
     * Use checkProgram() for library code or tests that don't require a valid main.
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    fun checkProgramForExecution(program: Program, currentPath: Path = Paths.get(".")): TypeCheckResult {
        // First, do normal type-checking
        val result = checkProgram(program, currentPath)

        // If type-checking failed, return the failure
        if (result is TypeCheckResult.Failure) {
            return result
        }

        val success = result as TypeCheckResult.Success

        val validator = TypeValidator(parentContext)

        // Validate top-level expressions don't have performed effects
        // (they would execute effects at module load time)
        val topLevelErrors = validator.validateTopLevelExpressionsForExecution(
            success.program,
            success.substitution
        )
        if (topLevelErrors.isNotEmpty()) {
            return TypeCheckResult.Failure(topLevelErrors.map { error ->
                TypeCheckError(
                    phase = "Validation",
                    message = error.message(),
                    sourceLocation = error.location()
                )
            })
        }

        // Now validate main for execution
        val mainError = validator.validateMainForExecution(success.program, success.substitution)

        return if (mainError != null) {
            TypeCheckResult.Failure(listOf(
                TypeCheckError(
                    phase = "MainValidation",
                    message = mainError.message(),
                    sourceLocation = mainError.location()
                )
            ))
        } else {
            result
        }
    }

    /**
     * Check a single expression (useful for REPL).
     *
     * Returns the inferred type and typed expression.
     */
    fun checkExpression(
        expr: Expression,
        context: TypingContext = parentContext
    ): ExpressionCheckResult {
        // Create a temporary program with just this expression
        val tempProgram = Program(
            items = listOf(TopLevelExpression(expr))
        )

        // Run full type checking
        val result = checkProgram(tempProgram)

        return when (result) {
            is TypeCheckResult.Success -> {
                // Extract the typed expression
                val firstItem = result.program.items.firstOrNull()
                    ?: return ExpressionCheckResult.Failure(listOf(
                        TypeCheckError(phase = "ExpressionCheck", message = "No items in program after type checking")
                    ))
                val typedExpr = (firstItem as? TopLevelExpression)?.expression
                    ?: return ExpressionCheckResult.Failure(listOf(
                        TypeCheckError(phase = "ExpressionCheck", message = "Expected TopLevelExpression but got ${firstItem::class.simpleName}")
                    ))
                val inferredType = typedExpr.inferredType ?: MezzeType.VarType(TypeVar.fresh())

                ExpressionCheckResult.Success(
                    expression = typedExpr,
                    type = inferredType,
                    substitution = result.substitution,
                    monomorphicInstances = result.monomorphicInstances
                )
            }
            is TypeCheckResult.Failure -> {
                ExpressionCheckResult.Failure(result.errors)
            }
        }
    }

    /**
     * Result of checking a single expression.
     */
    sealed class ExpressionCheckResult {
        data class Success(
            val expression: Expression,
            val type: MezzeType,
            val substitution: Substitution,
            val monomorphicInstances: Map<String, LetBinding> = emptyMap()
        ) : ExpressionCheckResult()

        data class Failure(
            val errors: List<TypeCheckError>
        ) : ExpressionCheckResult()
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if a program has import declarations.
     */
    private fun hasImports(program: Program): Boolean {
        return program.items.any { item ->
            item is UseDeclaration || item is PubUseDeclaration
        }
    }

    /**
     * Merge import context into typing context.
     *
     * This adds all imported bindings, types, abilities, and effects
     * to the typing context.
     */
    private fun mergeImportContext(
        baseContext: TypingContext,
        importContext: ModuleImportContext
    ): TypingContext {
        var context = baseContext

        // Add imported bindings
        // Types with free type variables are registered as polymorphic
        // so they get instantiated with fresh type variables on each use
        for ((name, binding) in importContext.importedBindings) {
            val freeVars = collectFreeTypeVars(binding.type)
            if (freeVars.isEmpty()) {
                // Monomorphic type - use directly
                context = context.extendMono(name, binding.type, binding.modulePath)
            } else {
                // Polymorphic type - wrap in TypeScheme for fresh instantiation
                val scheme = TypeScheme(
                    quantifiedVars = freeVars.toList(),
                    constraints = emptyList(),
                    type = binding.type
                )
                context = context.extendPoly(name, scheme, binding.modulePath)
            }
        }

        // Add imported effects
        // Effects need to be registered with the _ability suffix so they can be looked up
        // during member access (e.g., Log.println)
        val contextBuilder = ContextBuilder()
        for ((effectName, effectDecl) in importContext.importedEffects) {
            // Use ContextBuilder to add effect operations with proper type conversion
            // This creates the RecordType with operation fields and stores it as "{effectName}_ability"
            val tempProgram = Program(items = listOf(effectDecl))
            val tempBuildResult = contextBuilder.build(tempProgram, context)
            context = tempBuildResult.context
        }

        // Add imported abilities
        for ((abilityName, abilityDecl) in importContext.importedAbilities) {
            // Use ContextBuilder to add ability methods
            val tempProgram = Program(items = listOf(abilityDecl))
            val tempBuildResult = contextBuilder.build(tempProgram, context)
            context = tempBuildResult.context
        }

        // Add imported types
        for ((typeName, typeDecl) in importContext.importedTypes) {
            // Use ContextBuilder to add type constructors
            val tempProgram = Program(items = listOf(typeDecl))
            val tempBuildResult = contextBuilder.build(tempProgram, context)
            context = tempBuildResult.context
        }

        // Add imported ability implementations
        // These are needed for ability method dispatch (e.g., EncodeFormat.encode_int)
        for ((implKey, implDecl) in importContext.importedAbilityImpls) {
            val implInfo = TypingContext.AbilityImplInfo(
                abilityName = implDecl.abilityName,
                targetTypes = implDecl.targetTypes.map { typeAnnotationToString(it) },
                sourceModule = null,  // TODO: Track source module
                contentHash = implDecl.contentHash  // Propagate content hash from type-checked module
            )
            context = context.extendWithImpl(implKey, implInfo)
        }

        return context
    }

    /**
     * Compute content hashes for all declarations in a program.
     * This is done early (Phase 0.5) so that AbilityImplInfo.contentHash
     * is populated when ContextBuilder processes the declarations.
     *
     * Note: This only computes hashes for declarations that exist in the source.
     * Generated codec impls get their hashes computed inline in SubstitutionApplier.
     */
    private fun computeDeclarationHashes(program: Program): Program {
        val updatedItems = program.items.map { item ->
            when (item) {
                is ImplAbilityDeclaration -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item  // Already has hash (e.g., from import)
                    }
                }
                is ImplDeclaration -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplDeclaration(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item
                    }
                }
                is LetBinding -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashLetBinding(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item
                    }
                }
                is TypeDeclaration -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashTypeDeclaration(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item
                    }
                }
                is EffectDeclaration -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashEffectDeclaration(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item
                    }
                }
                is AbilityDeclaration -> {
                    if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashAbilityDeclaration(item)
                        item.copy(contentHash = hash.toFullHexString())
                    } else {
                        item
                    }
                }
                // Items without content hash
                is TopLevelExpression, is UseDeclaration, is PubUseDeclaration -> item
            }
        }
        return Program(items = updatedItems)
    }

    /**
     * Convert a TypeAnnotation to a string for ability impl key.
     */
    private fun typeAnnotationToString(typeAnnotation: TypeAnnotation): String {
        return when (typeAnnotation) {
            is IdentifierTypeAnnotation -> typeAnnotation.name
            is TypeApplicationAnnotation -> typeAnnotation.constructor
            is QualifiedTypeAnnotation -> typeAnnotation.typeName
            else -> typeAnnotation.toString()
        }
    }

    // ========================================================================
    // COMPATIBILITY API - Instance Methods
    // ========================================================================

    // Cached type aliases from the last type check
    private var cachedTypeAliases: Map<String, TypeAliasInfo> = emptyMap()

    /**
     * Get type aliases (compatibility API).
     * Returns type alias information collected during type checking.
     */
    fun getTypeAliases(): Map<String, TypeAliasInfo> {
        return cachedTypeAliases
    }

    /**
     * Build type aliases from type declarations.
     * Called during type checking to populate the cache.
     * Includes both local type declarations and imported types.
     */
    private fun buildTypeAliases(
        program: Program,
        contextBuilder: ContextBuilder,
        importContext: ModuleImportContext?
    ): Map<String, TypeAliasInfo> {
        val result = mutableMapOf<String, TypeAliasInfo>()

        // Helper function to add a type declaration to the result
        fun addTypeDeclaration(item: TypeDeclaration) {
            // Convert the type definition to a MezzeType
            val definitionType = when (val def = item.definition) {
                is VariantDefinition -> {
                    // Build VariantType from constructors
                    val constructors = def.constructors.associate { ctor ->
                        val dataType = ctor.dataType?.let { contextBuilder.convertTypeAnnotation(it) }
                        ctor.name to dataType
                    }
                    MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (def.isOpen) RowVar.fresh() else null,
                        typeName = item.name  // Track the type alias name
                    )
                }
                is RecordDefinition -> {
                    // Build RecordType from fields
                    val fields = def.fields.associate { field ->
                        field.name to contextBuilder.convertTypeAnnotation(field.type)
                    }
                    MezzeType.RecordType(
                        fields = fields,
                        rowVar = if (def.isOpen) RowVar.fresh() else null,
                        typeName = item.name  // Track the type alias name
                    )
                }
                else -> null
            }

            if (definitionType != null) {
                result[item.name] = TypeAliasInfo(
                    name = item.name,
                    typeParams = item.typeParams,
                    definition = definitionType
                )
            }
        }

        // Add local type declarations
        for (item in program.items) {
            if (item is TypeDeclaration) {
                addTypeDeclaration(item)
            }
        }

        // Add imported type declarations
        importContext?.importedTypes?.values?.forEach { typeDecl ->
            addTypeDeclaration(typeDecl)
        }

        return result
    }

    /**
     * Get current type context (compatibility API).
     *
     * DEPRECATED: Use typedProgram.items and check inferredType on each item instead.
     * The new type checker stores types directly on AST nodes, not in a separate context.
     *
     * Example:
     * ```kotlin
     * val binding = typedProgram.items.filterIsInstance<LetBinding>()
     *     .find { it.variableName == "x" }
     * val type = binding?.inferredType
     * ```
     */
    @Deprecated("Use inferredType on AST nodes instead", ReplaceWith("typedProgram.items"))
    fun getContext(): Map<String, MezzeType> {
        // Return empty - tests should use inferredType on AST nodes
        return emptyMap()
    }

    /**
     * Test if should use bidirectional inference (compatibility API).
     * Always returns false (new system doesn't use this heuristic).
     */
    fun testShouldUseBidirectionalInference(expr: LambdaExpression): Boolean {
        // New system doesn't use this heuristic
        return false
    }

    // ========================================================================
    // COMPATIBILITY API (for drop-in replacement of old TypeChecker)
    // ========================================================================

    /**
     * Type check a program (compatibility API).
     *
     * This method mimics the old TypeChecker API for backward compatibility.
     * It throws TypeCheckError on failure instead of returning a sealed result.
     *
     * @throws TypeCheckError if type checking fails
     */
    fun typeCheck(program: Program): com.mezze.language.ast.TypeCheckResult {
        return typeCheck(program, Paths.get("."))
    }

    /**
     * Type check a program with module resolution (compatibility API).
     *
     * This method mimics the old TypeChecker API for backward compatibility.
     * It throws TypeCheckError on failure instead of returning a sealed result.
     *
     * @throws TypeCheckError if type checking fails
     */
    fun typeCheck(program: Program, currentPath: Path): com.mezze.language.ast.TypeCheckResult {
        // Reset type variable counter for consistent naming (old behavior)
        com.mezze.language.ast.TypeVar.resetCounter()

        // Note: checkProgram() clears the unification cache internally
        val result = checkProgram(program, currentPath)

        return when (result) {
            is TypeCheckResult.Success -> {
                com.mezze.language.ast.TypeCheckResult(
                    program = result.program,
                    monomorphicInstances = result.monomorphicInstances
                )
            }
            is TypeCheckResult.Failure -> {
                // Throw exception for first error (old behavior)
                val error = result.errors.firstOrNull()
                    ?: throw TypeCheckError("Type checking failed with unknown error")
                throw TypeCheckError(error.message, error.sourceLocation)
            }
        }
    }

    companion object {
        /**
         * Create initial context with native functions.
         *
         * This is the base context that includes all native functions
         * like __native_int_add, __native_float_mul, etc.
         */
        private fun createInitialContext(): TypingContext {
            // Create mutable map for registering native functions
            val nativeFunctions = mutableMapOf<String, MezzeType>()

            // Register all native function types
            NativeFunctionTypes.registerAll(nativeFunctions)

            // Convert to immutable typing context
            // Types with free type variables are registered as polymorphic
            // so they get instantiated with fresh type variables on each use
            var context = TypingContext.empty
            for ((name, type) in nativeFunctions) {
                val freeVars = collectFreeTypeVars(type)
                if (freeVars.isEmpty()) {
                    // Monomorphic type - use directly
                    context = context.extendMono(name, type)
                } else {
                    // Polymorphic type - wrap in TypeScheme for fresh instantiation
                    val scheme = TypeScheme(
                        quantifiedVars = freeVars.toList(),
                        constraints = emptyList(),
                        type = type
                    )
                    context = context.extendPoly(name, scheme)
                }
            }

            return context
        }

        /**
         * Create a type checker with empty context.
         */
        fun create(): TypeChecker {
            return TypeChecker()
        }

        /**
         * Create a type checker with parent context (for modules).
         */
        fun withContext(context: TypingContext): TypeChecker {
            return TypeChecker(context)
        }

        // ====================================================================
        // COMPATIBILITY API - Module Cache Methods
        // ====================================================================

        /**
         * Clear the module cache (compatibility API).
         * Clears the ModuleResolver's global cache.
         */
        @JvmStatic
        fun clearCache() {
            // Clear the ModuleResolver's cache
            ModuleResolver.clearCache()
        }

        /**
         * Get a cached typed module's Program if available (compatibility API).
         * Returns the Program if the module has been type-checked, null otherwise.
         */
        @JvmStatic
        fun getTypedModule(modulePath: String): Program? {
            // Get from ModuleResolver's cache
            return ModuleResolver.getFromCache(modulePath)?.program
        }

        /**
         * Get all cached modules (compatibility API).
         * Returns empty map (new system handles caching internally).
         */
        @JvmStatic
        fun getAllCachedModules(): Map<String, Any> {
            return emptyMap()
        }

        /**
         * Restore module cache (compatibility API).
         * No-op (new system handles caching internally).
         */
        @JvmStatic
        fun restoreModuleCache(modules: Map<String, Any>) {
            // No-op: new system handles caching internally
        }

        /**
         * Preload modules into the cache (for compiled JARs).
         *
         * This is used by compiled JARs to preload stdlib and imported modules
         * to avoid parsing them at runtime. Modules are deserialized from JSON
         * and loaded into the ModuleResolver's cache.
         *
         * @param modules Map of module paths to typed Programs
         */
        @JvmStatic
        fun preloadModules(modules: Map<String, Program>) {
            val resolver = ModuleResolver.create()

            for ((modulePath, program) in modules) {
                // Build type context from the program for exports
                val typeContext = mutableMapOf<String, MezzeType>()

                for (item in program.items) {
                    when (item) {
                        is LetBinding -> {
                            item.inferredType?.let { type ->
                                typeContext[item.variableName] = type
                            }
                        }
                        is TypeDeclaration -> {
                            item.inferredType?.let { type ->
                                typeContext[item.name] = type
                            }
                        }
                        is EffectDeclaration -> {
                            item.inferredType?.let { type ->
                                typeContext[item.name] = type
                            }
                        }
                        is ImplDeclaration -> {
                            item.inferredType?.let { type ->
                                typeContext[item.performerName] = type
                            }
                        }
                        else -> {
                            // Other items don't contribute to type context
                        }
                    }
                }

                // Update ModuleResolver's cache with the preloaded module
                resolver.updateModuleExports(modulePath, program, typeContext)
            }
        }
    }

    private fun debugTypeNTC(type: MezzeType?, depth: Int = 0): String {
        if (type == null) return "null"
        if (depth > 3) return "..."
        return when (type) {
            is MezzeType.FunctionType -> "FunctionType(param=${debugTypeNTC(type.paramType, depth + 1)}, return=${debugTypeNTC(type.returnType, depth + 1)})"
            is MezzeType.RecordType -> "RecordType(fields={${type.fields.map { (k, v) -> "$k: ${debugTypeNTC(v, depth + 1)}" }.joinToString(", ")}}, rowVar=${type.rowVar})"
            is MezzeType.VariantType -> "VariantType(constructors=${type.constructors.keys}, rowVar=${type.rowVar})"
            is MezzeType.VarType -> "VarType(${type.variable})"
            else -> type::class.simpleName ?: type.toString()
        }
    }

    // ====================================================================
    // CONTINUATION TRANSFORMATION
    // ====================================================================

    /**
     * Transform continuation-using code to state machines.
     *
     * This pass runs after type checking and transforms perform block bodies
     * from DoExpression to StateMachineExpression. This is required for
     * continuation-based effects (like Choice with AllPaths) that need to
     * capture and resume continuations.
     */
    private fun transformContinuations(program: Program): Program {
        val transformedItems = program.items.map { item ->
            transformProgramItem(item)
        }
        return program.copy(items = transformedItems)
    }

    private fun transformProgramItem(item: ProgramItem): ProgramItem {
        return when (item) {
            is LetBinding -> {
                val transformedExpr = transformExpression(item.expression)
                if (transformedExpr !== item.expression) {
                    item.copy(expression = transformedExpr)
                } else {
                    item
                }
            }
            is TopLevelExpression -> {
                val transformedExpr = transformExpression(item.expression)
                if (transformedExpr !== item.expression) {
                    item.copy(expression = transformedExpr)
                } else {
                    item
                }
            }
            is ImplDeclaration -> {
                val transformedOps = item.operations.map { op ->
                    val transformedImpl = transformExpression(op.implementation)
                    if (transformedImpl !== op.implementation) {
                        op.copy(implementation = transformedImpl)
                    } else {
                        op
                    }
                }
                if (transformedOps != item.operations) {
                    item.copy(operations = transformedOps)
                } else {
                    item
                }
            }
            is ImplAbilityDeclaration -> {
                val transformedOps = item.operations.map { op ->
                    val transformedImpl = transformExpression(op.implementation)
                    if (transformedImpl !== op.implementation) {
                        op.copy(implementation = transformedImpl)
                    } else {
                        op
                    }
                }
                if (transformedOps != item.operations) {
                    item.copy(operations = transformedOps)
                } else {
                    item
                }
            }
            else -> item
        }
    }

    private fun transformExpression(expr: Expression): Expression {
        return when (expr) {
            is PerformExpression -> {
                // ALWAYS transform perform blocks into state machines
                // Even if we don't detect continuation usage in the code, effect handlers
                // (like AllPaths) might use continuations internally
                val transformedInner = when (val inner = expr.expression) {
                    is DoExpression -> {
                        // This is the main transformation: DoExpression -> StateMachineExpression
                        continuationTransformer.transformToStateMachine(inner)
                    }
                    else -> transformExpression(inner)
                }
                expr.copy(expression = transformedInner)
            }
            is DoExpression -> {
                // Transform statements
                val transformedStmts = expr.statements.map { stmt ->
                    transformStatement(stmt)
                }
                val transformedResult = transformExpression(expr.result)
                if (transformedStmts != expr.statements || transformedResult !== expr.result) {
                    expr.copy(statements = transformedStmts, result = transformedResult)
                } else {
                    expr
                }
            }
            is FunctionCall -> {
                val transformedFunc = transformExpression(expr.function)
                val transformedArgs = transformExpression(expr.arguments)
                if (transformedFunc !== expr.function || transformedArgs !== expr.arguments) {
                    expr.copy(function = transformedFunc, arguments = transformedArgs)
                } else {
                    expr
                }
            }
            is LambdaExpression -> {
                val transformedBody = transformExpression(expr.body)
                if (transformedBody !== expr.body) {
                    expr.copy(body = transformedBody)
                } else {
                    expr
                }
            }
            is IfExpression -> {
                val transformedCond = transformExpression(expr.condition)
                val transformedThen = transformExpression(expr.thenBranch)
                val transformedElse = transformExpression(expr.elseBranch)
                if (transformedCond !== expr.condition || transformedThen !== expr.thenBranch || transformedElse !== expr.elseBranch) {
                    expr.copy(condition = transformedCond, thenBranch = transformedThen, elseBranch = transformedElse)
                } else {
                    expr
                }
            }
            is MatchExpression -> {
                val transformedExpr = transformExpression(expr.expression)
                val transformedCases = expr.cases.map { case ->
                    val transformedCaseExpr = transformExpression(case.expression)
                    val transformedGuard = case.guard?.let { transformExpression(it) }
                    if (transformedCaseExpr !== case.expression || transformedGuard !== case.guard) {
                        case.copy(expression = transformedCaseExpr, guard = transformedGuard)
                    } else {
                        case
                    }
                }
                if (transformedExpr !== expr.expression || transformedCases != expr.cases) {
                    expr.copy(expression = transformedExpr, cases = transformedCases)
                } else {
                    expr
                }
            }
            is RecordLiteral -> {
                val transformedEntries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> {
                            val transformedValue = entry.value?.let { transformExpression(it) }
                            if (transformedValue !== entry.value) {
                                entry.copy(value = transformedValue)
                            } else {
                                entry
                            }
                        }
                        is RecordSpreadEntry -> {
                            val transformedExpr = transformExpression(entry.expression)
                            if (transformedExpr !== entry.expression) {
                                entry.copy(expression = transformedExpr)
                            } else {
                                entry
                            }
                        }
                    }
                }
                if (transformedEntries != expr.entries) {
                    expr.copy(entries = transformedEntries)
                } else {
                    expr
                }
            }
            is MemberAccess -> {
                val transformedReceiver = transformExpression(expr.receiver)
                if (transformedReceiver !== expr.receiver) {
                    expr.copy(receiver = transformedReceiver)
                } else {
                    expr
                }
            }
            is VariantConstruction -> {
                val transformedValue = expr.value?.let { transformExpression(it) }
                if (transformedValue !== expr.value) {
                    expr.copy(value = transformedValue)
                } else {
                    expr
                }
            }
            is ArrayLiteral -> {
                val transformedElements = expr.elements.map { element ->
                    when (element) {
                        is ArrayRegularElement -> ArrayRegularElement(transformExpression(element.expression))
                        is ArraySpreadElement -> ArraySpreadElement(transformExpression(element.expression))
                    }
                }
                if (transformedElements != expr.elements) {
                    expr.copy(elements = transformedElements)
                } else {
                    expr
                }
            }
            is ArrayAccess -> {
                val transformedArray = transformExpression(expr.array)
                val transformedIndex = transformExpression(expr.index)
                if (transformedArray !== expr.array || transformedIndex !== expr.index) {
                    expr.copy(array = transformedArray, index = transformedIndex)
                } else {
                    expr
                }
            }
            is RecordUpdate -> {
                val transformedRecord = transformExpression(expr.record)
                val transformedUpdates = expr.updates.map { update ->
                    val transformedValue = update.value?.let { transformExpression(it) }
                    if (transformedValue !== update.value) {
                        update.copy(value = transformedValue)
                    } else {
                        update
                    }
                }
                if (transformedRecord !== expr.record || transformedUpdates != expr.updates) {
                    expr.copy(record = transformedRecord, updates = transformedUpdates)
                } else {
                    expr
                }
            }
            is RecordUpdateExpression -> {
                val transformedBase = transformExpression(expr.baseRecord)
                val transformedUpdates = expr.updates.map { update ->
                    val transformedValue = transformExpression(update.value)
                    if (transformedValue !== update.value) {
                        update.copy(value = transformedValue)
                    } else {
                        update
                    }
                }
                if (transformedBase !== expr.baseRecord || transformedUpdates != expr.updates) {
                    expr.copy(baseRecord = transformedBase, updates = transformedUpdates)
                } else {
                    expr
                }
            }
            is EffectCallExpression -> {
                val transformedArgs = transformExpression(expr.arguments)
                if (transformedArgs !== expr.arguments) {
                    expr.copy(arguments = transformedArgs)
                } else {
                    expr
                }
            }
            is StringLiteral -> {
                val parts = expr.parts
                if (parts != null) {
                    val transformedParts = parts.map { part ->
                        when (part) {
                            is ExpressionPart -> {
                                val transformedExpr = transformExpression(part.expression)
                                if (transformedExpr !== part.expression) {
                                    ExpressionPart(transformedExpr)
                                } else {
                                    part
                                }
                            }
                            else -> part
                        }
                    }
                    if (transformedParts != expr.parts) {
                        expr.copy(parts = transformedParts)
                    } else {
                        expr
                    }
                } else {
                    expr
                }
            }
            // State machines and literals don't need transformation
            is StateMachineExpression, is IntLiteral, is NatLiteral, is FloatLiteral,
            is BooleanLiteral, is FractionLiteral, is Identifier, is PlaceholderExpression -> expr
        }
    }

    private fun transformStatement(stmt: Statement): Statement {
        return when (stmt) {
            is LetStatement -> {
                val transformedExpr = transformExpression(stmt.expression)
                if (transformedExpr !== stmt.expression) {
                    stmt.copy(expression = transformedExpr)
                } else {
                    stmt
                }
            }
            is ExpressionStatement -> {
                val transformedExpr = transformExpression(stmt.expression)
                if (transformedExpr !== stmt.expression) {
                    stmt.copy(expression = transformedExpr)
                } else {
                    stmt
                }
            }
        }
    }
}

/**
 * Format a type check result for display.
 *
 * @param result The type check result to format
 * @param sourceCode Optional source code for providing error context
 * @param colorize Whether to colorize output (default: false)
 */
fun formatTypeCheckResult(
    result: TypeChecker.TypeCheckResult,
    sourceCode: String? = null,
    colorize: Boolean = false
): String {
    return when (result) {
        is TypeChecker.TypeCheckResult.Success -> buildString {
            appendLine("✓ Type checking succeeded!")

            if (result.monomorphicInstances.isNotEmpty()) {
                appendLine("  Generated ${result.monomorphicInstances.size} specialized function(s)")
                if (result.monomorphicInstances.size <= 10) {
                    for (name in result.monomorphicInstances.keys.sorted()) {
                        appendLine("    - $name")
                    }
                }
            }

            if (result.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                for (warning in result.warnings) {
                    appendLine("  - $warning")
                }
            }
        }
        is TypeChecker.TypeCheckResult.Failure -> buildString {
            appendLine("✗ Type checking failed with ${result.errors.size} error(s):")
            appendLine()

            for ((index, error) in result.errors.withIndex()) {
                if (index > 0) {
                    appendLine()
                    appendLine("━".repeat(60))
                    appendLine()
                }

                // Use ErrorFormatter for beautiful error messages
                val formattedError = ErrorFormatter.format(error, sourceCode, colorize)
                appendLine(formattedError)
            }
        }
    }
}
