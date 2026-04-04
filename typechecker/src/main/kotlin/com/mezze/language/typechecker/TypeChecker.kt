package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.NativeFunctionTypes
import com.mezze.language.typechecker.application.SubstitutionApplier
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintGenerator
import com.mezze.language.typechecker.constraints.ConstraintResult
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
import com.mezze.language.typechecker.continuation.ContinuationDetector
import com.mezze.language.typechecker.cache.TypeCheckCache
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
    internal val parentContext: TypingContext = createInitialContext()
) {
    /** Bridge for PrecompiledStdlib to call loadPreludeModules */
    internal fun loadPreludeModulesPublic(importResolver: ImportResolver): ModuleImportContext {
        return loadPreludeModules(importResolver, ModuleImportContext.empty())
    }

    /** Bridge for PrecompiledStdlib to call mergeImportContext */
    internal fun mergeImportContextPublic(base: TypingContext, importCtx: ModuleImportContext): TypingContext {
        return mergeImportContext(base, importCtx)
    }

    /** Bridge for PrecompiledStdlib to get cachedPreludeLoadedModules */
    internal fun getCachedPreludeLoadedModules(): Map<String, com.mezze.language.typechecker.module.TypedModule>? {
        return cachedPreludeLoadedModules
    }

    // Transformer for converting perform blocks into state machines (for continuation support)
    private val continuationTransformer = ContinuationTransformer()
    // Detector for identifying code that uses continuations
    private val continuationDetector = ContinuationDetector()

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
            val program: Program,                              // Program with inferred types AND continuation transforms
            val substitution: Substitution,
            val monomorphicInstances: Map<String, LetBinding>,
            val importContext: ModuleImportContext? = null,
            val warnings: List<TypeCheckWarning> = emptyList(),
            val loadedModules: Map<String, TypedModule> = emptyMap(),
            val abilityImpls: Map<String, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
            val programBeforeContinuations: Program? = null,   // Program BEFORE StateMachine transform (for v2 emitter)
            val typedModule: com.mezze.language.typedast.TypedModule? = null,       // Stage 1: TypedAST
            val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>? = null,  // Stage 4: TruffleIR (after mono+resolve+lower)
            val loweredPerformers: List<Array<String>> = emptyList()  // Performer record info: [hash, effectHash, op1Hash, op1Name, ...]
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
     * Type checking warning.
     *
     * Warnings are non-fatal issues detected during type checking.
     */
    data class TypeCheckWarning(
        val message: String,
        val sourceLocation: SourceLocation? = null
    )

    /**
     * Result of type checking with error recovery.
     * Contains partial type information even when there are errors.
     */
    data class TypeCheckResultWithRecovery(
        val program: Program?,                              // Program with partial type info (null if parsing failed)
        val errors: List<TypeCheckError>,                   // All errors collected
        val partialSubstitution: Substitution?,             // Partial substitution if solving partially succeeded
        val importContext: ModuleImportContext? = null,     // Resolved imports for IDE support
        val warnings: List<TypeCheckWarning> = emptyList()
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isSuccess: Boolean get() = errors.isEmpty()
    }

    /**
     * Check a program through all 7 phases.
     *
     * This is the main entry point!
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    fun checkProgram(program: Program, currentPath: Path = Paths.get("."), currentModulePath: String? = null, enablePrelude: Boolean = false): TypeCheckResult {
        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()

        // Initialize TypeCheckCache in memory mode if not already initialized with SQLite
        // (SQLite persistence is initialized from CLI/CodebaseHelper when in project mode)
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        var loadedModules: Map<String, TypedModule> = emptyMap()
        // Prelude auto-import: loads inherent impls from all stdlib modules so
        // method syntax works without explicit `use` (e.g., [1,2,3].map {f = ...}).
        val shouldInjectPrelude = currentModulePath == null || !currentModulePath.startsWith("std::")
        val hasUserImports = hasImports(program)

        // Try to use PrecompiledStdlib for fast prelude injection
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { null }

        val enhancedContext = if (!hasUserImports && shouldInjectPrelude && precompiled != null) {
            // FAST PATH: no user imports, precompiled prelude available
            // mergeInto creates fresh TypeVars — safe across compilations
            importContext = precompiled.preludeImportContext
            // Ensure prelude loaded modules are available for v2 pipeline (monomorphizer needs them)
            if (cachedPreludeLoadedModules == null) {
                cachedPreludeLoadedModules = precompiled.loadedModules
            }
            precompiled.compiledPrelude.mergeInto(parentContext)
        } else if (hasUserImports || shouldInjectPrelude) {
            // Reuse cached ModuleLoader/ImportResolver when available — avoids per-test
            // filesystem traversal (findProjectRoot) and object creation overhead.
            val importResolver = if (precompiled != null) {
                precompiled.importResolver
            } else {
                val moduleLoader = ModuleLoader.fromPath(currentPath.toString())
                val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                    parentContext,
                    moduleLoader.getResolver()
                )
                ImportResolver(moduleLoader, moduleTypeChecker)
            }

            val importResult = if (hasUserImports) {
                importResolver.resolveImports(program)
            } else {
                ImportResolutionResult.Success(ModuleImportContext.empty(), emptyMap())
            }

            when (importResult) {
                is ImportResolutionResult.Success -> {
                    importContext = importResult.importContext
                    loadedModules = importResult.loadedModules

                    val ctx = if (shouldInjectPrelude) {
                        val preludeContext = loadPreludeModules(importResolver, importContext!!)
                        importContext = importContext!!.merge(preludeContext)
                        mergeImportContext(parentContext, importContext!!)
                    } else {
                        mergeImportContext(parentContext, importContext!!)
                    }
                    ctx
                }
                is ImportResolutionResult.Failure -> {
                    throw importResult.errors.firstOrNull()
                        ?: TypeCheckError("Import failed with unknown error")
                }
            }
        } else {
            parentContext
        }

        // PHASE 0.5: Resolve cross-module ability impls
        // The parser's resolveImplDeclarations only handles same-file abilities.
        // When an ability is imported from another module, its impl is parsed as ImplDeclaration
        // (effect impl). Convert those to ImplAbilityDeclaration using import context.
        // Must happen BEFORE content hash computation so new ImplAbilityDeclarations get hashed.
        val resolvedProgram = resolveImplDeclarationsWithImports(program, importContext)

        // PHASE 0.6: Compute Content Hashes for Declarations
        // Compute hashes BEFORE building context so AbilityImplInfo.contentHash is populated.
        // This allows SubstitutionApplier to use content hashes directly instead of mangled lookup keys.
        // Pass importContext so effect/ability hashes can be resolved for local impls referencing them
        val hashedProgram = computeDeclarationHashes(resolvedProgram, importContext)

        // PHASE 0.7: Desugar PipeExpressions
        // Convert PipeExpression nodes to FunctionCall by replacing PipePlaceholder with piped value.
        // This must happen before constraint generation since the typechecker doesn't know about pipes.
        val desugaredProgram = desugarPipeExpressions(hashedProgram)

        // Convert to ParsedModule for new pipeline stages
        val parsedModule = MezzeParser().programToParsedModule(desugaredProgram, currentModulePath ?: "<unknown>")

        // PHASE 1: Build Context (reads ParsedModule)
        val contextBuilder = ContextBuilder()
        val buildResult = contextBuilder.build(
            module = parsedModule,
            parentContext = enhancedContext,
            importedImpls = importContext?.importedImpls ?: emptyMap(),
            importedAbilities = importContext?.importedAbilities ?: emptyMap(),
            importedTypes = importContext?.importedTypes ?: emptyMap(),
            importedEffects = importContext?.importedEffects ?: emptyMap(),
            currentModulePath = currentModulePath
        )
        val context = buildResult.context

        // Build type aliases for pretty printing (include imported types)
        cachedTypeAliases = buildTypeAliases(desugaredProgram, contextBuilder, importContext)

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
        val programConstraintResult = constraintGenerator.generateProgram(parsedModule, context)
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors
        val perBindingSubstitution = programConstraintResult.perBindingSubstitution

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
                    // Check if this is a qualified name (module::function) referring to a private item
                    val privateItemMessage = checkIfPrivateQualifiedItem(undefinedVar, loadedModules, importContext)

                    if (privateItemMessage != null) {
                        privateItemMessage
                    } else {
                        val candidateNames = allVariables.keys.map { key -> key.toKeyString() }
                        val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                        buildString {
                            append("Undefined variable or function: ${undefinedVar.name}")
                            if (similarNames.isNotEmpty()) {
                                append(". Did you mean: ${similarNames.joinToString()}")
                            }
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
            TypeCheckWarning(
                message = "Unused variable '${unused.name}'. Prefix with '_' to suppress this warning.",
                sourceLocation = unused.sourceLocation
            )
        }

        // Collect pattern-related warnings (e.g., float literal patterns)
        // Convert string warnings to structured warnings (these don't have locations yet)
        val patternWarnings = constraintResults.values.flatMap { it.patternWarnings }.map { warningMsg ->
            TypeCheckWarning(message = warningMsg, sourceLocation = null)
        }

        // Collect all constraints from all bindings (including ability constraints and associated type constraints)
        // IMPORTANT: Filter out HasConstructor and HasField constraints!
        // These structural constraints are already solved during per-binding constraint solving
        // in ConstraintGenerator (Algorithm W). Re-solving them globally causes issues:
        // - Type variables that were correctly extended to merged variants get re-processed
        // - Different bindings' structural constraints can interfere with each other
        // - The variant extensions done per-binding are lost when global solve creates new variants
        val allConstraints = constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints } +
              constraintResults.values.flatMap { it.associatedTypeConstraints }


        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, allTypeDeclarations, allAbilityDeclarations)
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
                // Compose global substitution with per-binding substitution for bidirectional inference.
                // The per-binding substitution contains HasField/HasConstructor bindings from TopLevelExpression
                // that enable type inference from usage sites (e.g., val.name binding the type variable).
                val substitution = solveResult.substitution.compose(perBindingSubstitution)
                // Convert solver string warnings to structured warnings
                val warnings = solveResult.warnings.map { msg ->
                    TypeCheckWarning(message = msg, sourceLocation = null)
                }.toMutableList()

                // Add unused variable warnings (already structured with source locations)
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

                // Collect method call resolutions from all constraint results
                val allMethodCallResolutions = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo>()) { acc, r ->
                        acc + r.methodCallResolutions
                    }

                // PHASE 4: Validate Constraints (SubstitutionApplier removed — TypedAstBuilder replaces it)
                val validator = TypeValidator(
                    context,
                    allTypeDeclarations,
                    allEffectDeclarations,
                    buildResult.implDeclarations
                )

                // Validate the constraints (like HasConstructor) — keep with early return on failure
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
                        constraintValidation.warnings.forEach { msg ->
                            warnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
                        }
                    }
                }

                // V2 Pipeline FIRST: TypedAST → Mono → Resolve → Lower → TruffleIR
                // If this succeeds, skip the expensive v1 SubstitutionApplier entirely.
                // Skip for stdlib modules — they don't need their own TruffleIR since the
                // monomorphizer pulls reachable stdlib functions into the user module's IR.
                val isStdlibModule = currentModulePath != null && currentModulePath.startsWith("std::")
                var emittedTypedModule: com.mezze.language.typedast.TypedModule? = null
                var loweredFns: List<com.mezze.language.truffleir.FunctionIR>? = null
                var loweredPerformers: List<Array<String>> = emptyList()
                if (!isStdlibModule) try {
                    val builder = com.mezze.language.typechecker.emit.TypedAstBuilder(
                        substitution = substitution,
                        constraintResults = globallyResolvedConstraintResults,
                        abilityImpls = context.getAllAbilityImpls(),
                        typeDeclarations = allTypeDeclarations,
                        abilityDeclarations = allAbilityDeclarations,
                        effectDeclarations = allEffectDeclarations,
                        performerToEffect = buildResult.performerToEffect,
                        importContext = importContext,
                        identifierContentHashes = context.getContentHashes(),
                        implDeclarations = buildResult.implDeclarations,
                        tailCallLocations = tailCallLocations,
                        mustBeOpenRowVars = mustBeOpenRowVars,
                        methodCallResolutions = allMethodCallResolutions
                    )
                    val typed = builder.build(parsedModule)
                    emittedTypedModule = typed

                    val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typed)

                    // Build allModules: entry + user imports + prelude (all cached)
                    val preludeV2 = PrecompiledStdlib.v2Modules
                    val allModules = HashMap<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>(preludeV2.size + loadedModules.size + 1)
                    allModules.putAll(preludeV2)
                    allModules[wrapped.hash] = wrapped
                    if (loadedModules.isNotEmpty()) {
                        val adapter = TypeCheckerV2()
                        for ((path, v1Module) in loadedModules) {
                            val v2Module = adapter.convertV1TypedModule(path, v1Module)
                            val hashKey = v2Module.hash.hex
                            val cached = v1ToV2ModuleCache[hashKey]
                            if (cached != null) {
                                allModules[cached.hash] = cached
                            } else {
                                v1ToV2ModuleCache[hashKey] = v2Module
                                allModules[v2Module.hash] = v2Module
                            }
                        }
                    }

                    val monoModule = com.mezze.language.typechecker.mono.Monomorphizer().monomorphize(wrapped, allModules)
                    val resolved = com.mezze.language.typechecker.mono.Resolver().resolve(monoModule)
                    val lowered = com.mezze.language.typechecker.mono.Lowerer().lower(resolved)
                    loweredFns = lowered.functions
                    loweredPerformers = lowered.performerRecords.map { pr ->
                        val arr = mutableListOf(pr.performerHash, pr.effectHash)
                        for (i in pr.operationHashes.indices) {
                            arr.add(pr.operationHashes[i])
                            arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                        }
                        arr.toTypedArray()
                    }
                } catch (pipelineEx: Exception) {
                    System.err.println("Pipeline error: ${pipelineEx::class.simpleName}: ${pipelineEx.message}")
                }

                // V2 pipeline succeeded — still run validator for error checking
                // (pattern exhaustiveness, unreachable patterns, orphan rules, etc.)
                if (loweredFns != null && loweredFns.isNotEmpty()) {
                    val v2ValidationResult = validator.validate(desugaredProgram, globallyResolvedConstraintResults, substitution)
                    if (v2ValidationResult is ValidationResult.Failure) {
                        // Filter out effect-related errors — v2 pipeline handles effects differently
                        // (the desugaredProgram has raw perform expressions that the v1 SubstitutionApplier
                        // would have transformed before validation)
                        val nonEffectErrors = v2ValidationResult.errors.filter { error ->
                            // Skip effect-related validation errors on v2 path — the desugaredProgram
                            // has raw perform expressions that the v1 SubstitutionApplier would transform
                            error !is com.mezze.language.typechecker.validation.ValidationError.TopLevelPerformedEffects &&
                            error !is com.mezze.language.typechecker.validation.ValidationError.TopLevelExpressionPerformedEffects
                        }
                        if (nonEffectErrors.isNotEmpty()) {
                            val firstError = nonEffectErrors.first()
                            throw TypeCheckError(
                                firstError.message(),
                                firstError.location()
                            )
                        }
                    }
                    val validationWarnings = if (v2ValidationResult is ValidationResult.Success) {
                        v2ValidationResult.warnings.map { TypeCheckWarning(message = it, sourceLocation = null) }
                    } else emptyList()
                    return TypeCheckResult.Success(
                        program = desugaredProgram,
                        substitution = substitution,
                        monomorphicInstances = emptyMap(),
                        importContext = importContext,
                        warnings = warnings + validationWarnings,
                        loadedModules = loadedModules,
                        abilityImpls = context.getAllAbilityImpls(),
                        programBeforeContinuations = null,
                        typedModule = emittedTypedModule,
                        loweredFunctions = loweredFns,
                        loweredPerformers = loweredPerformers
                    )
                }

                // Slow path: v2 pipeline failed — run v1 SubstitutionApplier + Validator
                val validationProgram = try {
                    val applier = SubstitutionApplier(
                        substitution,
                        globallyResolvedConstraintResults,
                        buildResult.performerToEffect,
                        mustBeOpenRowVars,
                        mustBeOpenBindings,
                        mustBeOpenTypes,
                        context.getAllAbilityImpls(),
                        allAbilityDeclarations,
                        allEffectDeclarations,
                        allTypeDeclarations,
                        tailCallLocations,
                        buildResult.implDeclarations,
                        context.getContentHashes(),
                        constraintGenerator.expressionGenerator.nestedBindingTypes
                    )
                    val importedImplBodies = importContext?.importedAbilityImpls
                        ?.map { (hash, impl) -> hash to impl }
                        ?.toMap() ?: emptyMap()
                    applier.registerImportedImplBodies(importedImplBodies)
                    val programWithCodecImpls = if (generatedCodecImpls.isNotEmpty()) {
                        Program(items = generatedCodecImpls.values.toList() + desugaredProgram.items)
                    } else {
                        desugaredProgram
                    }
                    applier.applyToProgram(programWithCodecImpls)
                } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                    throw e
                } catch (_: Exception) {
                    desugaredProgram
                }

                val validationResult = validator.validate(validationProgram, globallyResolvedConstraintResults, substitution)
                when (validationResult) {
                    is ValidationResult.Failure -> {
                        val errors = validationResult.errors.map { error ->
                            TypeCheckError(phase = "Validation", message = error.message(), sourceLocation = error.location())
                        }
                        return TypeCheckResult.Failure(errors)
                    }
                    is ValidationResult.Success -> {
                        validationResult.warnings.forEach { msg ->
                            warnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
                        }
                    }
                }

                val programForExport = if (generatedCodecImpls.isNotEmpty() && validationProgram !== desugaredProgram) {
                    val codecCount = generatedCodecImpls.size
                    Program(items = validationProgram.items.drop(codecCount))
                } else {
                    validationProgram
                }

                return TypeCheckResult.Success(
                    program = programForExport,
                    substitution = substitution,
                    monomorphicInstances = emptyMap(),
                    importContext = importContext,
                    warnings = warnings,
                    loadedModules = loadedModules,
                    abilityImpls = context.getAllAbilityImpls(),
                    programBeforeContinuations = null,
                    typedModule = emittedTypedModule,
                    loweredFunctions = loweredFns
                )
            }
        }
    }

    /**
     * Check a program with error recovery.
     *
     * Unlike checkProgram() which returns on the first error, this method
     * continues checking and collects ALL errors. It also returns partial
     * type information for items that were successfully type-checked.
     *
     * This is useful for IDE support where you want to show all errors at once
     * and still provide type information for hover/completion even when there
     * are errors in other parts of the file.
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    fun checkProgramWithRecovery(program: Program, currentPath: Path = Paths.get(".")): TypeCheckResultWithRecovery {
        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()

        // Initialize TypeCheckCache in memory mode if not already initialized with SQLite
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        val allErrors = mutableListOf<TypeCheckError>()
        val allWarnings = mutableListOf<TypeCheckWarning>()

        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        var loadedModules: Map<String, TypedModule> = emptyMap()
        val shouldInjectPrelude = true  // Always inject prelude in recovery mode (used by LSP)
        val enhancedContext = if (hasImports(program) || shouldInjectPrelude) {
            val moduleLoader = ModuleLoader.fromPath(currentPath.toString())
            val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                parentContext,
                moduleLoader.getResolver()
            )
            val importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

            val importResult = if (hasImports(program)) {
                importResolver.resolveImports(program)
            } else {
                ImportResolutionResult.Success(ModuleImportContext.empty(), emptyMap())
            }

            when (importResult) {
                is ImportResolutionResult.Success -> {
                    importContext = importResult.importContext
                    loadedModules = importResult.loadedModules

                    // Inject prelude for method syntax (same as checkProgram)
                    if (shouldInjectPrelude) {
                        try {
                            val preludeContext = loadPreludeModules(importResolver, importContext!!)
                            importContext = importContext!!.merge(preludeContext)
                        } catch (_: Exception) {
                            // Prelude loading failure is non-fatal in recovery mode
                        }
                    }

                    mergeImportContext(parentContext, importContext!!)
                }
                is ImportResolutionResult.Failure -> {
                    // Collect import errors but continue with parent context
                    importResult.errors.forEach { moduleError ->
                        // Convert ModuleError to TypeCheckError
                        // Extract source location if available
                        @Suppress("USELESS_IS_CHECK")
                        val sourceLocation = when (moduleError) {
                            is ModuleError.ExportNotFound -> moduleError.sourceLocation
                            is ModuleError.InternalModuleAccess -> moduleError.sourceLocation
                            is ModuleError.PrivateExport -> moduleError.sourceLocation
                            else -> null
                        }
                        allErrors.add(TypeCheckError(
                            phase = "Import",
                            message = moduleError.message,
                            sourceLocation = sourceLocation
                        ))
                    }
                    parentContext
                }
            }
        } else {
            parentContext
        }

        // PHASE 0.5: Compute Content Hashes for Declarations
        // PHASE 0.5: Resolve cross-module ability impls
        val resolvedProgram = resolveImplDeclarationsWithImports(program, importContext)

        // PHASE 0.6: Compute Content Hashes
        val hashedProgram = computeDeclarationHashes(resolvedProgram, importContext)

        // PHASE 0.7: Desugar PipeExpressions
        val desugaredProgram = desugarPipeExpressions(hashedProgram)

        // PHASE 1: Build Context
        val contextBuilder = ContextBuilder()
        val buildResult = try {
            contextBuilder.build(
                program = desugaredProgram,
                parentContext = enhancedContext,
                importedImpls = importContext?.importedImpls ?: emptyMap(),
                importedAbilities = importContext?.importedAbilities ?: emptyMap(),
                importedTypes = importContext?.importedTypes ?: emptyMap(),
                importedEffects = importContext?.importedEffects ?: emptyMap()
            )
        } catch (e: Exception) {
            // Context building failed - return with error
            allErrors.add(TypeCheckError(
                phase = "Context Building",
                message = e.message ?: "Unknown error building context",
                sourceLocation = null
            ))
            return TypeCheckResultWithRecovery(
                program = desugaredProgram,
                errors = allErrors,
                partialSubstitution = null,
                importContext = importContext,
                warnings = allWarnings
            )
        }
        val context = buildResult.context

        // Build type aliases for pretty printing
        cachedTypeAliases = buildTypeAliases(desugaredProgram, contextBuilder, importContext)

        // PHASE 2: Generate Constraints
        val abilityNames = buildResult.abilityDeclarations.keys +
            (importContext?.importedAbilities?.keys ?: emptySet())
        val effectNames = buildResult.effectDeclarations.keys +
            (importContext?.importedEffects?.keys ?: emptySet())

        val allEffectDeclarations = buildResult.effectDeclarations +
            (importContext?.importedEffects ?: emptyMap())
        val allTypeDeclarations = buildResult.typeDeclarations +
            (importContext?.importedTypes ?: emptyMap())
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
        val parsedModuleForConstraints2 = MezzeParser().programToParsedModule(desugaredProgram, "<unknown>")
        val programConstraintResult = constraintGenerator.generateProgram(parsedModuleForConstraints2, context)
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors

        // Collect per-binding solve errors (don't return early)
        perBindingSolveErrors.forEach { error ->
            allErrors.add(TypeCheckError(
                phase = "Constraint Solving",
                message = error.message,
                sourceLocation = error.constraint.sourceLocation
            ))
        }

        // Collect undefined variable errors (don't return early)
        val undefinedVars = constraintResults.values.flatMap { it.undefinedVariables }
        undefinedVars.forEach { undefinedVar ->
            val isBuiltInAbility = com.mezze.language.typechecker.abilities.BuiltInAbilities.isBuiltInAbility(undefinedVar.name)
            val isUserDefinedAbility = allAbilityDeclarations.containsKey(undefinedVar.name)
            val isEffect = allEffectDeclarations.containsKey(undefinedVar.name)

            val matchingAbilityOp = allAbilityDeclarations.entries.firstOrNull { (_, ability) ->
                ability.operations.any { it.name == undefinedVar.name }
            }
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
                val abilityName = matchingAbilityOp.key
                "Undefined variable: ${undefinedVar.name}. " +
                "Did you mean to call an ability method? Use: $abilityName.${undefinedVar.name}"
            } else if (matchingEffectOp != null) {
                val effectName = matchingEffectOp.key
                "Undefined variable: ${undefinedVar.name}. " +
                "Did you mean to call an effect operation? Use: $effectName.${undefinedVar.name}"
            } else {
                // Check if this is a qualified name (module::function) referring to a private item
                val privateItemMessage = checkIfPrivateQualifiedItem(undefinedVar, loadedModules, importContext)

                if (privateItemMessage != null) {
                    privateItemMessage
                } else {
                    val validator = TypeValidator(context, allTypeDeclarations, allEffectDeclarations, buildResult.implDeclarations)
                    val allVariables = context.getAllBindings()
                    val candidateNames = allVariables.keys.map { key -> key.toKeyString() }
                    val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                    buildString {
                        append("Undefined variable or function: ${undefinedVar.name}")
                        if (similarNames.isNotEmpty()) {
                            append(". Did you mean: ${similarNames.joinToString()}")
                        }
                    }
                }
            }
            allErrors.add(TypeCheckError(
                phase = "Validation",
                message = message,
                sourceLocation = undefinedVar.sourceLocation
            ))
        }

        // Collect duplicate binding errors (don't return early)
        val duplicateBindings = constraintResults.values.flatMap { it.duplicateBindings }
        duplicateBindings.forEach { duplicate ->
            allErrors.add(TypeCheckError(
                phase = "Validation",
                message = "Duplicate binding: '${duplicate.name}' is already defined in this scope",
                sourceLocation = duplicate.sourceLocation
            ))
        }

        // Collect warnings with proper source locations
        val unusedVarWarnings = constraintResults.values.flatMap { it.unusedVariables }.map { unused ->
            TypeCheckWarning(
                message = "Unused variable '${unused.name}'. Prefix with '_' to suppress this warning.",
                sourceLocation = unused.sourceLocation
            )
        }
        allWarnings.addAll(unusedVarWarnings)
        // Convert pattern warnings (strings) to structured warnings
        constraintResults.values.flatMap { it.patternWarnings }.forEach { msg ->
            allWarnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
        }

        // Collect all constraints (filter structural constraints, include associated type constraints)
        val allConstraints = constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints } +
              constraintResults.values.flatMap { it.associatedTypeConstraints }

        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, allTypeDeclarations, allAbilityDeclarations)
        val solveResult = solver.solve(allConstraints)

        // Process solve result
        val substitution = when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                // Collect solve errors but try to continue with partial information
                solveResult.errors.forEach { error ->
                    allErrors.add(TypeCheckError(
                        phase = "Constraint Solving",
                        message = error.message,
                        sourceLocation = error.constraint.sourceLocation
                    ))
                }
                // Use empty substitution for partial results
                Substitution.empty
            }
            is ConstraintSolver.SolveResult.Success -> {
                // Convert solver string warnings to structured warnings
                solveResult.warnings.forEach { msg ->
                    allWarnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
                }
                solveResult.substitution
            }
        }

        // Get generated codec implementations
        val generatedCodecImpls = solver.getGeneratedCodecImpls().mapValues { (_, impl) ->
            if (impl.contentHash == null) {
                val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(impl)
                impl.copy(contentHash = hash.toFullHexString())
            } else {
                impl
            }
        }

        val mustBeOpenRowVars = when (solveResult) {
            is ConstraintSolver.SolveResult.Success -> solveResult.mustBeOpenRowVars + perBindingMustBeOpenRowVars
            else -> perBindingMustBeOpenRowVars
        }
        val mustBeOpenBindings: Set<String> = when (solveResult) {
            is ConstraintSolver.SolveResult.Success -> solveResult.mustBeOpenBindings
            else -> emptySet()
        }
        val mustBeOpenTypes: Set<TypeVar> = when (solveResult) {
            is ConstraintSolver.SolveResult.Success -> solveResult.mustBeOpenTypes
            else -> emptySet()
        }

        // Apply global substitution to constraint results
        val tempGenerator = ConstraintGenerator.create(buildResult.typeDeclarations)
        val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
            val resolvedType = substitution.apply(result.type)
            val typeWithAbilities = tempGenerator.addAbilityConstraintsToFunctionType(resolvedType, result.abilityConstraints, substitution)
            result.copy(type = typeWithAbilities)
        }

        // TCO: Collect tail call locations
        val tailCallLocations = constraintResults.values
            .flatMap { it.tailCalls }
            .filterNotNull()
            .toSet()

        // PHASE 4: Apply Substitution
        val applier = SubstitutionApplier(
            substitution,
            globallyResolvedConstraintResults,
            buildResult.performerToEffect,
            mustBeOpenRowVars,
            mustBeOpenBindings,
            mustBeOpenTypes,
            context.getAllAbilityImpls(),
            allAbilityDeclarations,
            allEffectDeclarations,
            allTypeDeclarations,
            tailCallLocations,
            buildResult.implDeclarations,
            context.getContentHashes()  // Content hashes for identifier resolution
        )
        val programWithCodecImpls = if (generatedCodecImpls.isNotEmpty()) {
            Program(items = generatedCodecImpls.values.toList() + desugaredProgram.items)
        } else {
            desugaredProgram
        }
        val typedProgram = try {
            applier.applyToProgram(programWithCodecImpls)
        } catch (e: Exception) {
            // If substitution application fails, return partial program
            allErrors.add(TypeCheckError(
                phase = "Substitution",
                message = e.message ?: "Failed to apply substitution",
                sourceLocation = null
            ))
            return TypeCheckResultWithRecovery(
                program = programWithCodecImpls,
                errors = allErrors,
                partialSubstitution = substitution,
                importContext = importContext,
                warnings = allWarnings
            )
        }

        // PHASE 5: Validation (only if no errors so far to avoid cascading errors)
        if (allErrors.isEmpty()) {
            val validator = TypeValidator(
                context,
                allTypeDeclarations,
                allEffectDeclarations,
                buildResult.implDeclarations
            )

            val constraintValidation = validator.validateConstraints(allConstraints, substitution)
            when (constraintValidation) {
                is ValidationResult.Failure -> {
                    constraintValidation.errors.forEach { error ->
                        allErrors.add(TypeCheckError(
                            phase = "Validation",
                            message = error.message(),
                            sourceLocation = error.location()
                        ))
                    }
                }
                is ValidationResult.Success -> {
                    constraintValidation.warnings.forEach { msg ->
                        allWarnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
                    }
                }
            }

            val validationResult = validator.validate(typedProgram, globallyResolvedConstraintResults, substitution)
            when (validationResult) {
                is ValidationResult.Failure -> {
                    validationResult.errors.forEach { error ->
                        allErrors.add(TypeCheckError(
                            phase = "Validation",
                            message = error.message(),
                            sourceLocation = error.location()
                        ))
                    }
                }
                is ValidationResult.Success -> {
                    validationResult.warnings.forEach { msg ->
                        allWarnings.add(TypeCheckWarning(message = msg, sourceLocation = null))
                    }
                }
            }
        }

        // Return result with all collected errors and partial type information
        return TypeCheckResultWithRecovery(
            program = typedProgram,
            errors = allErrors,
            partialSubstitution = substitution,
            importContext = importContext,
            warnings = allWarnings
        )
    }

    /**
     * Check a program for execution.
     *
     * This performs normal type-checking AND validates that main has the correct type:
     * {} -> {} <*>
     *
     * TODO: Move main/exec validation to CLI layer. The typechecker should only do type inference.
     * Use checkProgram() for library code or tests that don't require a valid main.
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    @Deprecated("Main validation should be in CLI, not typechecker. Use checkProgram() + validate main separately.")
    fun checkProgramForExecution(program: Program, currentPath: Path = Paths.get("."), enablePrelude: Boolean = false): TypeCheckResult {
        // First, do normal type-checking (with prelude if enabled for CLI/project mode)
        val result = checkProgram(program, currentPath, enablePrelude = enablePrelude)

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

        // Validate main for execution using TypedModule
        // Note: main doesn't need to be pub — look for any let binding named "main"
        val mainTypeFromTypedModule = success.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == "main" }?.scheme?.type

        val mainExistsInTypedModule = mainTypeFromTypedModule != null

        val mainError: com.mezze.language.typechecker.validation.ValidationError? = when {
            mainTypeFromTypedModule != null -> validator.validateMainType(mainTypeFromTypedModule)
            !mainExistsInTypedModule -> com.mezze.language.typechecker.validation.ValidationError.MainFunctionTypeError(
                actualType = MezzeType.RecordType(emptyMap()),
                reason = "no 'main' function found. Define main = {} -> perform Handler in ...",
                sourceLocation = null
            )
            else -> null // main exists but type unknown — skip validation
        }

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
     * Load prelude modules and return a combined ModuleImportContext.
     * Cached globally — computed once per JVM process since stdlib doesn't change.
     */
    private fun loadPreludeModules(
        importResolver: ImportResolver,
        userImportContext: ModuleImportContext
    ): ModuleImportContext {
        // Return cached prelude if available
        cachedPreludeContext?.let { return it }

        // Load prelude module list from _prelude.mz (compiler-internal, not user-importable)
        val preludeSource = ModuleResolver.readStdlibFile("stdlib/std/_prelude.mz")
            ?: ModuleResolver.readStdlibFile("std/_prelude.mz")
        val syntheticProgram = if (preludeSource != null) {
            MezzeParser().parse(preludeSource)
        } else {
            // Fallback: hardcoded list if _prelude.mz not found
            val preludeModules = listOf(
                listOf("std", "int"), listOf("std", "float"), listOf("std", "nat"), listOf("std", "frac"),
                listOf("std", "bool"), listOf("std", "str"), listOf("std", "char"),
                listOf("std", "option"), listOf("std", "result"),
                listOf("std", "collections", "seq"), listOf("std", "collections", "list"),
                listOf("std", "collections", "map"), listOf("std", "collections", "set"),
                listOf("std", "collections", "deque"), listOf("std", "collections", "sorted_map"),
                listOf("std", "collections", "sorted_set"), listOf("std", "collections", "nonemptylist"),
                listOf("std", "collections", "bytes"),
            )
            Program(items = preludeModules.map { UseDeclaration(path = ModulePath(it)) })
        }

        val result = importResolver.resolveImports(syntheticProgram)
        val preludeContext = when (result) {
            is ImportResolutionResult.Success -> {
                // Import inherent impls for method syntax + named ability impls for
                // context-based constraint solving (replacing hardcoded checks).
                // No bindings — prevents name collisions (option::fold vs seq::fold).
                val ctx = result.importContext
                // Cache loaded modules for inclusion in Monomorphizer's allModules
                cachedPreludeLoadedModules = result.loadedModules
                ModuleImportContext(
                    importedBindings = emptyMap(),
                    importedTypes = emptyMap(),
                    importedAbilities = emptyMap(),
                    importedEffects = emptyMap(),
                    importedImpls = emptyMap(),
                    importedAbilityImpls = ctx.importedAbilityImpls
                )
            }
            is ImportResolutionResult.Failure -> ModuleImportContext.empty()
        }

        cachedPreludeContext = preludeContext
        return preludeContext
    }

    // Prelude cache is in the companion object below

    /**
     * Check if a qualified name (like "module::function") refers to an item
     * that exists in the module but isn't public.
     *
     * @return An error message if the item exists but isn't public, null otherwise.
     */
    private fun checkIfPrivateQualifiedItem(
        undefinedVar: ConstraintResult.UndefinedVariable,
        loadedModules: Map<String, TypedModule>,
        importContext: ModuleImportContext?
    ): String? {
        // Only check qualified names - now using structured qualifier from AST
        if (!undefinedVar.isQualified) return null

        val qualifierPath = undefinedVar.qualifier ?: return null
        val itemName = undefinedVar.name.substringAfterLast("::")

        // Find which module this qualifier refers to
        // The qualifier could be:
        // 1. A direct module path (e.g., "std::int")
        // 2. A last-segment alias (e.g., "int" when imported via "use std::int")

        // First, check loaded modules by path using structured path comparison
        // Either full path matches, or the module path ends with the qualifier segments
        val moduleByPath = loadedModules.values.find { module ->
            val modulePath = ModulePath.parse(module.modulePath)
            modulePath == qualifierPath || modulePath.endsWith(qualifierPath)
        }

        if (moduleByPath != null) {
            // Check if the item exists in the module's program but isn't exported
            val existsButNotExported = checkIfItemExistsInModule(moduleByPath, itemName)
            if (existsButNotExported) {
                return "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it."
            }
        }

        // Also check by matching against imported bindings to find the source module
        // Use structured path comparison instead of string startsWith
        val matchingBinding = importContext?.importedBindings?.entries?.find { (bindingName, _) ->
            val bindingPath = ModulePath.parse(bindingName.substringBeforeLast("::"))
            bindingPath == qualifierPath || bindingPath.endsWith(qualifierPath)
        }

        if (matchingBinding != null) {
            val modulePath = matchingBinding.value.modulePath
            val module = loadedModules[modulePath]
            if (module != null) {
                val existsButNotExported = checkIfItemExistsInModule(module, itemName)
                if (existsButNotExported) {
                    return "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it."
                }
            }
        }

        return null
    }

    /**
     * Check if an item exists in a module's program but isn't exported.
     */
    private fun checkIfItemExistsInModule(module: TypedModule, itemName: String): Boolean {
        // Check if it's exported - if so, it's not private
        if (module.exports.containsKey(itemName)) {
            return false
        }

        // Check if it exists in the module's program
        val program = module.program
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    // Only check simple bindings (not destructuring patterns)
                    if (item.isSimpleBinding && item.variableName == itemName) return true
                }
                is TypeDeclaration -> if (item.name == itemName) return true
                is AbilityDeclaration -> if (item.name == itemName) return true
                is EffectDeclaration -> if (item.name == itemName) return true
                is ImplDeclaration -> if (item.performerName == itemName) return true
                is ImplAbilityDeclaration -> {} // These don't have simple names
                is UseDeclaration, is PubUseDeclaration, is TopLevelExpression -> {}
            }
        }

        return false
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
            // Parse the name to handle qualified names like "buffer::empty"
            val bindingKey = BindingKey.parse(name)
            val freeVars = collectFreeTypeVars(binding.type)
            if (freeVars.isEmpty()) {
                // Monomorphic type - use directly
                context = context.extendMono(bindingKey, binding.type, binding.modulePath, binding.contentHash)
            } else {
                // Polymorphic type - wrap in TypeScheme for fresh instantiation
                val scheme = TypeScheme(
                    quantifiedVars = freeVars.toList(),
                    constraints = emptyList(),
                    type = binding.type
                )
                context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
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

        // Add imported types (newtype constructors are private — not imported)
        for ((typeName, typeDecl) in importContext.importedTypes) {
            context = contextBuilder.addTypeConstructors(context, typeDecl, isImported = true)
        }

        // Add imported ability implementations
        // These are needed for ability method dispatch (e.g., EncodeFormat.encode_int)
        for ((implKey, implDecl) in importContext.importedAbilityImpls) {
            if (implDecl.isInherent) {
                // Inherent impls need both AbilityImplInfo AND InherentMethodInfo registered.
                // Use registerImportedInherentImpl to bypass validation (already validated in home module).
                context = contextBuilder.registerImportedInherentImpl(context, implDecl, implKey)
            } else {
                // Named ability impls only need AbilityImplInfo
                // Extract type variable names from abilityTypeArgs for structural matching
                val implTypeVars = implDecl.abilityTypeArgs
                    .filterIsInstance<IdentifierTypeAnnotation>()
                    .filter { it.name.startsWith("'") }
                    .map { it.name }
                    .toSet()
                val abilityDeclHashForImpl = implDecl.abilityName?.let { name ->
                    importContext.importedAbilities[name]?.contentHash
                }
                val implInfo = TypingContext.AbilityImplInfo(
                    abilityName = implDecl.abilityName,
                    abilityDeclHash = abilityDeclHashForImpl,
                    targetTypes = implDecl.allTypeArgs.map { typeAnnotationToString(it) },
                    targetTypeAnnotations = implDecl.allTypeArgs,
                    associatedTypeBindings = implDecl.associatedTypeBindings,
                    sourceModule = null,
                    contentHash = implKey,
                    typeVars = implTypeVars
                )
                context = context.extendWithImpl(implKey, implInfo)
            }
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
     *
     * @param importContext The import context containing imported effects/abilities with content hashes
     */
    private fun computeDeclarationHashes(
        program: Program,
        importContext: ModuleImportContext? = null
    ): Program {
        // Maps for resolving references by name (temporary, for populating resolvedContentHash)
        // These are keyed by the LOCAL NAME (e.g., "Log"), not qualified strings
        val effectHashByName = mutableMapOf<String, String>()
        val abilityHashByName = mutableMapOf<String, String>()

        // Initialize from imported effects/abilities - extract hash from each declaration
        // Store under the ORIGINAL name (the actual effect/ability name, not the import key)
        importContext?.importedEffects?.values?.forEach { decl ->
            decl.contentHash?.let { hash ->
                effectHashByName[decl.name] = hash
            }
        }
        importContext?.importedAbilities?.values?.forEach { decl ->
            decl.contentHash?.let { hash ->
                abilityHashByName[decl.name] = hash
            }
        }

        // PASS 1: Compute all declaration hashes and build lookup maps
        val pass1Items = program.items.map { item ->
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
                    val hashStr: String = if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashEffectDeclaration(item)
                        hash.toFullHexString()
                    } else {
                        item.contentHash!!
                    }
                    effectHashByName[item.name] = hashStr
                    if (item.contentHash == null) {
                        item.copy(contentHash = hashStr)
                    } else {
                        item
                    }
                }
                is AbilityDeclaration -> {
                    val hashStr: String = if (item.contentHash == null) {
                        val hash = com.mezze.language.ast.hashing.ASTHasher.hashAbilityDeclaration(item)
                        hash.toFullHexString()
                    } else {
                        item.contentHash!!
                    }
                    abilityHashByName[item.name] = hashStr
                    if (item.contentHash == null) {
                        item.copy(contentHash = hashStr)
                    } else {
                        item
                    }
                }
                // Items without content hash
                is TopLevelExpression, is UseDeclaration, is PubUseDeclaration -> item
            }
        }

        // PASS 2: Populate resolvedContentHash on QualifiedRefs within impls
        val updatedItems = pass1Items.map { item ->
            when (item) {
                is ImplDeclaration -> {
                    // Resolve effect reference to its content hash using the local name
                    // (e.g., "Log" from "log::Log" or just "Log" for unqualified)
                    val effectHash = effectHashByName[item.effect.localName]
                    if (effectHash != null && item.effect.resolvedContentHash == null) {
                        item.copy(effect = item.effect.withResolvedHash(effectHash))
                    } else {
                        item
                    }
                }
                is ImplAbilityDeclaration -> {
                    // Resolve ability reference to its content hash using the local name
                    val abilityRef = item.abilityRef
                    val abilityHash = item.abilityName?.let { abilityHashByName[it] }
                    if (abilityHash != null && abilityRef != null && abilityRef.resolvedContentHash == null) {
                        item.copy(target = ImplTarget.Named(abilityRef.withResolvedHash(abilityHash)))
                    } else {
                        item
                    }
                }
                else -> item
            }
        }

        return Program(items = updatedItems)
    }

    /**
     * Populate content hashes in UseDeclaration items from import resolution.
     * This enables the interpreter to use content-hash based lookups for imports.
     */
    private fun populateImportContentHashes(
        program: Program,
        importContext: ModuleImportContext?
    ): Program {
        if (importContext == null) return program

        val importedBindings = importContext.importedBindings
        val importedTypes = importContext.importedTypes
        val importedAbilities = importContext.importedAbilities
        val importedEffects = importContext.importedEffects
        val importedImpls = importContext.importedImpls

        val updatedItems = program.items.map { item ->
            when (item) {
                is UseDeclaration -> {
                    when (val path = item.path) {
                        is SelectivePath -> {
                            val updatedItems = path.items.map { importItem ->
                                val localName = importItem.alias ?: importItem.name
                                // Try to find content hash from imported bindings
                                val contentHash = importedBindings[localName]?.contentHash
                                    ?: importedTypes[localName]?.contentHash
                                    ?: importedAbilities[localName]?.contentHash
                                    ?: importedEffects[localName]?.contentHash
                                    ?: importedImpls[localName]?.contentHash
                                if (contentHash != null) {
                                    importItem.copy(contentHash = contentHash)
                                } else {
                                    importItem
                                }
                            }
                            item.copy(path = path.copy(items = updatedItems))
                        }
                        is ModulePath -> item  // Module path imports don't have per-item hashes
                    }
                }
                else -> item
            }
        }
        return Program(items = updatedItems)
    }

    /**
     * Convert a TypeAnnotation to a string for ability impl key.
     * Delegates to ContentHashComputer for consistency with ability lookup keys.
     */
    private fun typeAnnotationToString(typeAnnotation: TypeAnnotation): String {
        return ContentHashComputer.typeAnnotationToString(typeAnnotation)
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
     * @param enablePrelude If true, auto-imports inherent impls from all stdlib modules.
     *                      Used by CLI/project execution, not by unit tests.
     * @throws TypeCheckError if type checking fails
     */
    @JvmOverloads
    fun typeCheck(program: Program, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): com.mezze.language.ast.TypeCheckResult {
        // Reset type variable counter for consistent naming (old behavior)
        com.mezze.language.ast.TypeVar.resetCounter()

        // Note: checkProgram() clears the unification cache internally
        val result = checkProgram(program, currentPath, currentModulePath = modulePath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                // Convert import context to resolved imports for IDE support
                val resolvedImports = buildResolvedImports(result.importContext)

                // Convert internal TypeCheckWarning to AST TypeCheckWarning
                // Source locations are already properly set for each warning
                val structuredWarnings = result.warnings.map { warning ->
                    com.mezze.language.ast.TypeCheckWarning(warning.message, warning.sourceLocation)
                }

                com.mezze.language.ast.TypeCheckResult(
                    program = result.program,
                    monomorphicInstances = result.monomorphicInstances,
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                    importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                    typedModule = result.typedModule,
                    performerRecords = result.loweredPerformers
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

    /**
     * Type-check a ParsedModule and produce a TypedModule.
     *
     * Same algorithm as typeCheck(), just different input/output types.
     * All declarations in the output have content hashes.
     * All cross-references use hashes, not strings.
     *
     * @param module The parsed module to type-check
     * @param currentPath Path for module resolution
     * @param enablePrelude If true, auto-imports stdlib inherent impls
     * @return TypedModule with hashes on all declarations
     * @throws TypeCheckError if type checking fails
     */
    /**
     * Result of checkParsedModule — includes the typed module and all loaded dependencies.
     */
    data class ParsedModuleCheckResult(
        val module: com.mezze.language.typedast.TypedModule,
        val loadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>
    )

    fun checkParsedModule(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        enablePrelude: Boolean = false
    ): com.mezze.language.typedast.TypedModule {
        return checkParsedModuleWithDeps(module, currentPath, enablePrelude).module
    }

    fun checkParsedModuleWithDeps(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        enablePrelude: Boolean = false
    ): ParsedModuleCheckResult {
        // Convert ParsedModule → v1 Program for the existing typechecker
        val adapter = TypeCheckerV2()
        val v1Program = adapter.parsedModuleToV1(module)

        // Run the full typechecker
        val result = checkProgram(v1Program, currentPath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                // Emit from program BEFORE continuation transformation
                // StateMachineExpression is a v1-only IR that the v2 pipeline can't handle
                val emitProgram = result.programBeforeContinuations ?: result.program
                val typedModule = com.mezze.language.typechecker.emit.TypedAstEmitter.emit(
                    emitProgram,
                    result.monomorphicInstances,
                    result.importContext,
                    module.path,
                    result.abilityImpls
                )
                ParsedModuleCheckResult(typedModule, result.loadedModules)
            }
            is TypeCheckResult.Failure -> {
                val error = result.errors.firstOrNull()
                    ?: throw TypeCheckError("Type checking failed with unknown error")
                throw TypeCheckError(error.message, error.sourceLocation)
            }
        }
    }

    /**
     * Build resolved imports from import context for IDE support.
     */
    private fun buildResolvedImports(importContext: ModuleImportContext?): List<com.mezze.language.ast.ResolvedImport> {
        if (importContext == null) return emptyList()

        val imports = mutableListOf<com.mezze.language.ast.ResolvedImport>()

        // Add imported bindings (functions/values)
        for ((name, binding) in importContext.importedBindings) {
            imports.add(com.mezze.language.ast.ResolvedImport(
                localName = name,
                originalName = binding.originalName,
                modulePath = binding.modulePath,
                type = binding.type,
                kind = com.mezze.language.ast.ImportKind.FUNCTION
            ))
        }

        // Add imported types - use TypeApplication for type representation
        for ((name, typeDecl) in importContext.importedTypes) {
            val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar(it)) }
            imports.add(com.mezze.language.ast.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",  // TypeDeclaration doesn't track module path
                type = MezzeType.TypeApplication(name, typeParams),
                kind = com.mezze.language.ast.ImportKind.TYPE
            ))
        }

        // Add imported abilities - use simple type application for representation
        for ((name, _) in importContext.importedAbilities) {
            imports.add(com.mezze.language.ast.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = MezzeType.TypeApplication(name, emptyList()),
                kind = com.mezze.language.ast.ImportKind.ABILITY
            ))
        }

        // Add imported effects - use simple type application for representation
        for ((name, _) in importContext.importedEffects) {
            imports.add(com.mezze.language.ast.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = MezzeType.TypeApplication(name, emptyList()),
                kind = com.mezze.language.ast.ImportKind.EFFECT
            ))
        }

        return imports
    }

    companion object {
        /** Cached prelude context — computed once per JVM process since stdlib doesn't change. */
        @Volatile
        private var cachedPreludeContext: ModuleImportContext? = null

        /** Cached prelude loaded modules — shared across all TypeChecker instances */
        @Volatile
        private var cachedPreludeLoadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>? = null

        /** Cached v1→v2 TypedModule conversions, keyed by content hash (module.hash.hex).
         *  Hash-based identity means the same module content at different paths shares one cache entry. */
        private val v1ToV2ModuleCache = java.util.concurrent.ConcurrentHashMap<String, com.mezze.language.typedast.TypedModule>()

        /** Cached prelude-enhanced TypingContext — the parentContext + all prelude bindings merged.
         *  This is the expensive result of mergeImportContext(parentContext, preludeContext).
         *  Keyed by parentContext identity since different TypeChecker instances may have different parents. */
        @Volatile
        private var cachedPreludeEnhancedContext: TypingContext? = null
        @Volatile
        private var cachedPreludeEnhancedContextParentId: Int = 0

        internal fun getOrBuildPreludeEnhancedContext(parentContext: TypingContext): TypingContext? {
            val parentId = System.identityHashCode(parentContext)
            val cached = cachedPreludeEnhancedContext
            if (cached != null && cachedPreludeEnhancedContextParentId == parentId) {
                return cached
            }
            val preludeCtx = cachedPreludeContext ?: return null
            // Use instance method via a temporary TypeChecker
            val tc = TypeChecker()
            val enhanced = tc.mergeImportContext(parentContext, preludeCtx)
            cachedPreludeEnhancedContext = enhanced
            cachedPreludeEnhancedContextParentId = parentId
            return enhanced
        }

        /** Clears prelude cache. Used when stdlib files change. */
        fun clearPreludeCache() {
            cachedPreludeContext = null
            cachedPreludeLoadedModules = null
            cachedPreludeEnhancedContext = null
        }

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
                // Parse the name to handle qualified names like "int::to_string"
                val bindingKey = BindingKey.parse(name)
                val freeVars = collectFreeTypeVars(type)
                if (freeVars.isEmpty()) {
                    // Monomorphic type - use directly
                    context = context.extendMono(bindingKey, type)
                } else {
                    // Polymorphic type - wrap in TypeScheme for fresh instantiation
                    val scheme = TypeScheme(
                        quantifiedVars = freeVars.toList(),
                        constraints = emptyList(),
                        type = type
                    )
                    context = context.extendPoly(bindingKey, scheme)
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
            ModuleResolver.clearCache()
            TypeCheckCache.clearAll()
            v1ToV2ModuleCache.clear()
            // NOTE: prelude cache is NOT cleared — it's self-contained and expensive to recompute
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

                // Also cache in ModuleTypeChecker's global cache to prevent re-type-checking.
                // Without this, the prelude re-type-checks modules from source, which fails
                // because ability impls aren't in scope during re-validation (e.g., `1 + 2`
                // needs NumAdd for Int, which isn't available during standalone module validation).
                val typedModule = com.mezze.language.typechecker.module.TypedModule(
                    modulePath = modulePath,
                    program = program,
                    exports = emptyMap(),  // Actual exports are in ModuleResolver
                    context = TypingContext.empty,
                    substitution = com.mezze.language.typechecker.solving.Substitution(),
                    monomorphicInstances = emptyMap()
                )
                com.mezze.language.typechecker.module.ModuleTypeChecker.preloadIntoGlobalCache(modulePath, typedModule)
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
                // First recursively transform the body
                val transformedBody = transformExpression(expr.body)

                // Then check if the body uses continuations and needs to be a state machine
                val finalBody = when {
                    transformedBody is DoExpression && continuationDetector.usesContinuations(transformedBody) -> {
                        // Transform do-block body to state machine for continuation support
                        continuationTransformer.transformToStateMachine(transformedBody)
                    }
                    else -> transformedBody
                }

                if (finalBody !== expr.body) {
                    expr.copy(body = finalBody)
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
            is SeqLiteral -> {
                val transformedElements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> SeqRegularElement(transformExpression(element.expression))
                        is SeqSpreadElement -> SeqSpreadElement(transformExpression(element.expression))
                    }
                }
                if (transformedElements != expr.elements) {
                    expr.copy(elements = transformedElements)
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
            is FractionLiteral, is Identifier -> expr

            // PipePlaceholder should never reach here - it's desugared in PipeExpression handling
            is PipePlaceholder -> throw TypeCheckError(
                "'@' can only be used inside a pipe expression (|>). " +
                "Use '@' to refer to the piped value: value |> func { arg = @ }",
                expr.sourceLocation
            )

            // PipeExpression - desugar by replacing @ with left value, then continue transform
            is PipeExpression -> {
                // First transform both sides
                val transformedLeft = transformExpression(expr.left)
                // Desugar: replace PipePlaceholder in right with left value
                val desugaredRight = replacePipePlaceholder(expr.right, transformedLeft)
                // Then transform the desugared right side
                transformExpression(desugaredRight)
            }

            // Parenthesized - transform inner
            is ParenthesizedExpression -> {
                val transformed = transformExpression(expr.expression)
                if (transformed !== expr.expression) {
                    expr.copy(expression = transformed)
                } else {
                    expr
                }
            }
        }
    }

    // =========================================================================
    // PIPE EXPRESSION DESUGARING
    // =========================================================================

    /**
     * Desugar all PipeExpressions in a program.
     * Converts: x |> f { a = @ } to f { a = x }
     */
    private fun desugarPipeExpressions(program: Program): Program {
        return program.copy(
            items = program.items.map { desugarPipeInProgramItem(it) }
        )
    }

    /**
     * Resolve ImplDeclarations that reference imported abilities (not effects) into ImplAbilityDeclarations.
     *
     * The parser's resolveImplDeclarations only handles same-file ability names. When an ability
     * is imported from another module, its impl is still parsed as ImplDeclaration (effect impl syntax).
     * This step converts those to ImplAbilityDeclaration using the import context.
     */
    private fun resolveImplDeclarationsWithImports(
        program: Program,
        importContext: ModuleImportContext?
    ): Program {
        if (importContext == null) return program

        val importedAbilities = importContext.importedAbilities
        val importedEffects = importContext.importedEffects
        val localAbilityNames = program.items.filterIsInstance<AbilityDeclaration>().map { it.name }.toSet()
        val localEffectNames = program.items.filterIsInstance<EffectDeclaration>().map { it.name }.toSet()

        // Build lookup sets for both qualified and unqualified names
        val allAbilityNames = localAbilityNames + importedAbilities.keys +
            importedAbilities.values.map { it.name }.toSet()
        val allEffectNames = localEffectNames + importedEffects.keys +
            importedEffects.values.map { it.name }.toSet()

        var changed = false
        val resolvedItems = program.items.map { item ->
            if (item is ImplDeclaration) {
                // Build the full reference name (e.g., "serializer::Serializable" or just "Show")
                val fullRefName = if (item.effect.isQualified) {
                    "${item.effect.qualifier!!.fullPath}::${item.effect.localName}"
                } else {
                    item.effect.localName
                }
                val localName = item.effect.localName

                // Check if this references an ability (by qualified name, unqualified name, or local name)
                val isAbility = fullRefName in allAbilityNames ||
                    localName in allAbilityNames
                val isEffect = fullRefName in allEffectNames ||
                    localName in allEffectNames

                if (isAbility && !isEffect) {
                    changed = true
                    val targetType = IdentifierTypeAnnotation(item.performerName)
                    ImplAbilityDeclaration(
                        target = ImplTarget.Named(item.effect),
                        targetTypes = listOf(targetType),
                        forallVars = emptyList(),
                        associatedTypeBindings = item.associatedTypeBindings,
                        operations = item.operations.map { op ->
                            val paramType = op.paramType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() }
                            val returnType = op.returnType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() }
                            ImplAbilityOperation(
                                name = op.name,
                                paramType = paramType,
                                returnType = returnType,
                                implementation = op.implementation
                            )
                        },
                        inferredType = null,
                        sourceLocation = item.sourceLocation
                    )
                } else {
                    item
                }
            } else {
                item
            }
        }

        return if (changed) program.copy(items = resolvedItems) else program
    }

    private fun desugarPipeInProgramItem(item: ProgramItem): ProgramItem {
        return when (item) {
            is LetBinding -> item.copy(expression = desugarPipeInExpression(item.expression))
            is ImplDeclaration -> item.copy(
                operations = item.operations.map { op ->
                    op.copy(implementation = desugarPipeInExpression(op.implementation))
                }
            )
            is ImplAbilityDeclaration -> item.copy(
                operations = item.operations.map { op ->
                    op.copy(implementation = desugarPipeInExpression(op.implementation))
                }
            )
            is TopLevelExpression -> item.copy(expression = desugarPipeInExpression(item.expression))
            // These don't contain expressions
            is TypeDeclaration, is EffectDeclaration, is AbilityDeclaration,
            is UseDeclaration, is PubUseDeclaration -> item
        }
    }

    /**
     * Recursively desugar PipeExpressions in an expression tree.
     */
    private fun desugarPipeInExpression(expr: Expression): Expression {
        return when (expr) {
            // The main case: desugar PipeExpression
            is PipeExpression -> {
                // First desugar both sides recursively
                val desugaredLeft = desugarPipeInExpression(expr.left)
                val desugaredRight = desugarPipeInExpression(expr.right)
                // Then replace @ placeholder with the left value
                replacePipePlaceholder(desugaredRight, desugaredLeft)
            }

            // Recurse into all other expression types
            is FunctionCall -> expr.copy(
                function = desugarPipeInExpression(expr.function),
                arguments = desugarPipeInExpression(expr.arguments)
            )
            is RecordLiteral -> expr.copy(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { desugarPipeInExpression(it) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = desugarPipeInExpression(entry.expression)
                        )
                    }
                }
            )
            is IfExpression -> expr.copy(
                condition = desugarPipeInExpression(expr.condition),
                thenBranch = desugarPipeInExpression(expr.thenBranch),
                elseBranch = desugarPipeInExpression(expr.elseBranch)
            )
            is LambdaExpression -> expr.copy(
                body = desugarPipeInExpression(expr.body)
            )
            is MatchExpression -> expr.copy(
                expression = desugarPipeInExpression(expr.expression),
                cases = expr.cases.map { case ->
                    case.copy(
                        guard = case.guard?.let { desugarPipeInExpression(it) },
                        expression = desugarPipeInExpression(case.expression),
                        ifLetBindings = case.ifLetBindings.map { binding ->
                            binding.copy(expression = desugarPipeInExpression(binding.expression))
                        }
                    )
                }
            )
            is DoExpression -> expr.copy(
                statements = expr.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> stmt.copy(
                            expression = desugarPipeInExpression(stmt.expression)
                        )
                        is ExpressionStatement -> stmt.copy(
                            expression = desugarPipeInExpression(stmt.expression)
                        )
                    }
                },
                result = desugarPipeInExpression(expr.result)
            )
            is SeqLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> element.copy(
                            expression = desugarPipeInExpression(element.expression)
                        )
                        is SeqSpreadElement -> element.copy(
                            expression = desugarPipeInExpression(element.expression)
                        )
                    }
                }
            )
            is PerformExpression -> expr.copy(
                expression = desugarPipeInExpression(expr.expression)
            )
            is EffectCallExpression -> expr.copy(
                arguments = desugarPipeInExpression(expr.arguments)
            )
            is MemberAccess -> expr.copy(
                receiver = desugarPipeInExpression(expr.receiver)
            )
            is VariantConstruction -> expr.copy(
                value = expr.value?.let { desugarPipeInExpression(it) }
            )
            is ParenthesizedExpression -> expr.copy(
                expression = desugarPipeInExpression(expr.expression)
            )
            is StringLiteral -> {
                val parts = expr.parts
                if (parts != null) {
                    expr.copy(
                        parts = parts.map { part ->
                            when (part) {
                                is ExpressionPart -> ExpressionPart(desugarPipeInExpression(part.expression))
                                else -> part
                            }
                        }
                    )
                } else {
                    expr
                }
            }
            // Leaf nodes - no recursion needed
            is IntLiteral, is NatLiteral, is FloatLiteral, is FractionLiteral,
            is Identifier, is PipePlaceholder, is StateMachineExpression -> expr
        }
    }

    /**
     * Replace PipePlaceholder (@) in an expression with the given value.
     * Used for desugaring PipeExpression: x |> f { a = @ } becomes f { a = x }
     */
    private fun replacePipePlaceholder(expr: Expression, replacement: Expression): Expression {
        return when (expr) {
            is PipePlaceholder -> replacement
            is FunctionCall -> expr.copy(
                function = replacePipePlaceholder(expr.function, replacement),
                arguments = replacePipePlaceholder(expr.arguments, replacement)
            )
            is RecordLiteral -> expr.copy(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { replacePipePlaceholder(it, replacement) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = replacePipePlaceholder(entry.expression, replacement)
                        )
                    }
                }
            )
            is IfExpression -> expr.copy(
                condition = replacePipePlaceholder(expr.condition, replacement),
                thenBranch = replacePipePlaceholder(expr.thenBranch, replacement),
                elseBranch = replacePipePlaceholder(expr.elseBranch, replacement)
            )
            is LambdaExpression -> expr.copy(
                body = replacePipePlaceholder(expr.body, replacement)
            )
            is MatchExpression -> expr.copy(
                expression = replacePipePlaceholder(expr.expression, replacement),
                cases = expr.cases.map { case ->
                    case.copy(
                        guard = case.guard?.let { replacePipePlaceholder(it, replacement) },
                        expression = replacePipePlaceholder(case.expression, replacement)
                    )
                }
            )
            is DoExpression -> expr.copy(
                statements = expr.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> stmt.copy(
                            expression = replacePipePlaceholder(stmt.expression, replacement)
                        )
                        is ExpressionStatement -> stmt.copy(
                            expression = replacePipePlaceholder(stmt.expression, replacement)
                        )
                    }
                },
                result = replacePipePlaceholder(expr.result, replacement)
            )
            is SeqLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> element.copy(
                            expression = replacePipePlaceholder(element.expression, replacement)
                        )
                        is SeqSpreadElement -> element.copy(
                            expression = replacePipePlaceholder(element.expression, replacement)
                        )
                    }
                }
            )
            is PerformExpression -> expr.copy(
                expression = replacePipePlaceholder(expr.expression, replacement)
            )
            is MemberAccess -> expr.copy(
                receiver = replacePipePlaceholder(expr.receiver, replacement)
            )
            is VariantConstruction -> expr.copy(
                value = expr.value?.let { replacePipePlaceholder(it, replacement) }
            )
            is ParenthesizedExpression -> expr.copy(
                expression = replacePipePlaceholder(expr.expression, replacement)
            )
            is PipeExpression -> expr.copy(
                left = replacePipePlaceholder(expr.left, replacement),
                right = replacePipePlaceholder(expr.right, replacement)
            )
            // These don't contain placeholders
            else -> expr
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
