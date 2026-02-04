package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.typechecker.NativeFunctionTypes
import com.funk.language.typechecker.application.SubstitutionApplier
import com.funk.language.typechecker.constraints.ConstraintGenerator
import com.funk.language.typechecker.context.ContextBuilder
import com.funk.language.typechecker.context.TypingContext
import com.funk.language.typechecker.module.*
import com.funk.language.typechecker.monomorphization.MonomorphizationEngine
import com.funk.language.typechecker.monomorphization.MonomorphizationResult
import com.funk.language.typechecker.solving.ConstraintSolver
import com.funk.language.typechecker.solving.Substitution
import com.funk.language.typechecker.validation.TypeValidator
import com.funk.language.typechecker.validation.ValidationResult
import com.funk.language.typechecker.printing.ErrorFormatter
import java.nio.file.Path
import java.nio.file.Paths

/**
 * New type checker - orchestrates all 7 phases of type checking.
 *
 * This is the new, clean architecture for the Funk type checker!
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
 * val typeChecker = NewTypeChecker()
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
class NewTypeChecker(
    private val parentContext: TypingContext = createInitialContext()
) {
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
     */
    fun checkProgram(program: Program): TypeCheckResult {
        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        val enhancedContext = if (hasImports(program)) {
            val moduleLoader = ModuleLoader.create()
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
                    throw importResult.errors.first()
                }
            }
        } else {
            parentContext
        }

        // PHASE 1: Build Context
        // Pass imported impls to ContextBuilder so performerToEffect map includes them
        val contextBuilder = ContextBuilder()
        val buildResult = contextBuilder.build(
            program = program,
            parentContext = enhancedContext,
            importedImpls = importContext?.importedImpls ?: emptyMap()
        )
        val context = buildResult.context

        // PHASE 2: Generate Constraints
        // Include both local ability declarations AND imported ability declarations
        val abilityNames = buildResult.abilityDeclarations.keys +
            (importContext?.importedAbilities?.keys ?: emptySet())
        // Include both local effect declarations AND imported effect declarations
        val effectNames = buildResult.effectDeclarations.keys +
            (importContext?.importedEffects?.keys ?: emptySet())

        // DEBUG: Print ability and effect names
        if (System.getProperty("funk.debug.abilities") == "true") {
            println("[DEBUG NewTypeChecker] abilityNames = $abilityNames")
            println("[DEBUG NewTypeChecker] effectNames = $effectNames")
        }

        val constraintGenerator = ConstraintGenerator.create(
            typeDeclarations = buildResult.typeDeclarations,
            performerToEffect = buildResult.performerToEffect,
            abilityNames = abilityNames,
            effectNames = effectNames,
            abilityDeclarations = buildResult.abilityDeclarations
        )
        val constraintResults = constraintGenerator.generateProgram(program, context)

        // Check for undefined variables BEFORE solving constraints
        // (If we wait until validation, constraint solving might fail first on those type variables)
        val undefinedVars = constraintResults.values.flatMap { it.undefinedVariables }
        if (undefinedVars.isNotEmpty()) {
            val validator = TypeValidator(context, buildResult.typeDeclarations)
            val allVariables = context.getAllBindings()
            val errors = undefinedVars.map { undefinedVar ->
                // Special case: Check if this is an ability or effect being used as a value
                val isBuiltInAbility = com.funk.language.typechecker.abilities.BuiltInAbilities.isBuiltInAbility(undefinedVar.name)
                val isUserDefinedAbility = buildResult.abilityDeclarations.containsKey(undefinedVar.name)
                val isEffect = buildResult.effectDeclarations.containsKey(undefinedVar.name)

                val message = if (isBuiltInAbility || isUserDefinedAbility) {
                    "Ability '${undefinedVar.name}' cannot be used as a value. " +
                    "Did you mean to access a method like ${undefinedVar.name}.method_name?"
                } else if (isEffect) {
                    "Effect '${undefinedVar.name}' cannot be used as a value. " +
                    "Did you mean to access an operation like ${undefinedVar.name}.operation_name?"
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

        // Collect all constraints from all bindings (including ability constraints)
        val allConstraints = constraintResults.values.flatMap { it.constraints } +
                             constraintResults.values.flatMap { it.abilityConstraints }

        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, buildResult.typeDeclarations, buildResult.abilityDeclarations)
        val solveResult = solver.solve(allConstraints)

        // Check if solving failed
        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                // Convert solver errors to type check errors
                val errors = solveResult.errors.map { error ->
                    TypeCheckError(
                        phase = "Constraint Solving",
                        message = error.message,
                        sourceLocation = null
                    )
                }
                return TypeCheckResult.Failure(errors)
            }
            is ConstraintSolver.SolveResult.Success -> {
                // Continue to validation
                val substitution = solveResult.substitution
                val warnings = solveResult.warnings.toMutableList()
                val resolvedAbilities = solveResult.resolvedAbilityConstraints

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
                val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
                    result.copy(type = substitution.apply(result.type))
                }

                // PHASE 4: Apply Substitution (moved before validation so expressions have types)
                val applier = SubstitutionApplier(
                    substitution,
                    globallyResolvedConstraintResults,
                    buildResult.performerToEffect
                )
                val typedProgram = applier.applyToProgram(program)

                // PHASE 5: Validate Types (moved after substitution so we can check inferred types)
                val validator = TypeValidator(context, buildResult.typeDeclarations)

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
                val validationResult = validator.validate(typedProgram, globallyResolvedConstraintResults, substitution)

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

                        // PHASE 6: Monomorphization
                        val monomorphizationEngine = MonomorphizationEngine.create()
                        val monomorphizationResult = monomorphizationEngine.monomorphize(typedProgram)

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
                val typedExpr = (result.program.items.first() as TopLevelExpression).expression
                val inferredType = typedExpr.inferredType ?: FunkType.VarType(TypeVar.fresh())

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
            val type: FunkType,
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
        for ((name, binding) in importContext.importedBindings) {
            context = context.extendMono(name, binding.type, binding.modulePath)
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

        return context
    }

    // ========================================================================
    // COMPATIBILITY API - Instance Methods
    // ========================================================================

    /**
     * Get type aliases (compatibility API).
     * Returns empty map (new system doesn't expose this directly).
     */
    fun getTypeAliases(): Map<String, TypeAliasInfo> {
        // New system doesn't expose type aliases this way
        return emptyMap()
    }

    /**
     * Get current type context (compatibility API).
     * Returns empty map (new system uses immutable context internally).
     */
    fun getContext(): Map<String, FunkType> {
        // New system uses immutable TypingContext internally
        // Tests shouldn't need this, but for compatibility return empty
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
    fun typeCheck(program: Program): com.funk.language.ast.TypeCheckResult {
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
    fun typeCheck(program: Program, currentPath: Path): com.funk.language.ast.TypeCheckResult {
        // Reset type variable counter for consistent naming (old behavior)
        com.funk.language.ast.TypeVar.resetCounter()

        val result = checkProgram(program)

        return when (result) {
            is TypeCheckResult.Success -> {
                com.funk.language.ast.TypeCheckResult(
                    program = result.program,
                    monomorphicInstances = result.monomorphicInstances
                )
            }
            is TypeCheckResult.Failure -> {
                // Throw exception for first error (old behavior)
                val error = result.errors.first()
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
            val nativeFunctions = mutableMapOf<String, FunkType>()

            // Register all native function types
            NativeFunctionTypes.registerAll(nativeFunctions)

            // Convert to immutable typing context
            var context = TypingContext.empty
            for ((name, type) in nativeFunctions) {
                context = context.extendMono(name, type)
            }

            return context
        }

        /**
         * Create a type checker with empty context.
         */
        fun create(): NewTypeChecker {
            return NewTypeChecker()
        }

        /**
         * Create a type checker with parent context (for modules).
         */
        fun withContext(context: TypingContext): NewTypeChecker {
            return NewTypeChecker(context)
        }

        // ====================================================================
        // COMPATIBILITY API - Module Cache Methods
        // ====================================================================

        /**
         * Clear the module cache (compatibility API).
         * No-op in new system (module caching handled differently).
         */
        @JvmStatic
        fun clearCache() {
            // No-op: new system doesn't use a global cache
            // Module loading is handled per type-check invocation
        }

        /**
         * Get a cached typed module's Program if available (compatibility API).
         * Returns null if the module hasn't been type-checked yet.
         */
        @JvmStatic
        fun getTypedModule(modulePath: String): Program? {
            // The new system uses ModuleLoader's cache internally
            // For compatibility, we return null (cached modules are handled internally)
            return null
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
    result: NewTypeChecker.TypeCheckResult,
    sourceCode: String? = null,
    colorize: Boolean = false
): String {
    return when (result) {
        is NewTypeChecker.TypeCheckResult.Success -> buildString {
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
        is NewTypeChecker.TypeCheckResult.Failure -> buildString {
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
