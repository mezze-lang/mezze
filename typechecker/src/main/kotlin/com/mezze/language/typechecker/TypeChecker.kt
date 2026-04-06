package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.NativeFunctionTypes

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
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.solving.Substitution
import com.mezze.language.typechecker.validation.TypeValidator
import com.mezze.language.typechecker.validation.ValidationResult
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.typechecker.cache.TypeCheckCache
import com.mezze.language.typechecker.hashing.ContentHashComputer
import java.nio.file.Path
import java.nio.file.Paths

/**
 * New type checker - orchestrates all 7 phases of type checking.
 *
 * This is the new, clean architecture for the Mezze type checker!
 *
 * ## Phase Architecture:
 *
 * **PHASE 0: Module Resolution** (optional - only if imports present)
 * - Input: Program with UseDeclaration/PubUseDeclaration (or ParsedModule with ParsedUseDecl)
 * - Output: ModuleImportContext + TypedModules
 * - What: Load and type-check imported modules
 * - Files: ModuleLoader.kt, ImportResolver.kt, ModuleTypeChecker.kt
 *
 * **PHASE 0.5-0.7: V1 Preprocessing** (on v1 Program)
 * - Resolve cross-module ability impls (0.5)
 * - Compute content hashes (0.6)
 * - Desugar pipe expressions (0.7)
 * - Convert to ParsedModule for subsequent phases
 *
 * **PHASE 1: Build Context**
 * - Input: ParsedModule (+ optional ModuleImportContext)
 * - Output: TypingContext (immutable!)
 * - What: Build immutable context from type/ability/effect declarations
 * - File: ContextBuilder.kt
 *
 * **PHASE 2: Generate Constraints**
 * - Input: ParsedModule, TypingContext
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
 * **PHASE 4: Emit TypedAST + V2 Pipeline**
 * - Input: ParsedModule, Substitution, ConstraintResults
 * - Output: TypedModule -> MonoModule -> ResolvedModule -> TruffleIR
 * - What: Build typed AST, monomorphize, resolve, lower to TruffleIR
 * - Files: TypedAstBuilder.kt, Monomorphizer.kt, Resolver.kt, Lowerer.kt
 *
 * **PHASE 5: Validate Types**
 * - Input: ParsedModule, Substitution, Constraints
 * - Output: ValidationResult
 * - What: Check abilities, effects, exhaustiveness, variant constructors
 * - Files: TypeValidator.kt, V2Validator.kt
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
            @Deprecated("Use typedModule instead — v1 Program kept for backward compat during migration")
            val program: Program,
            val substitution: Substitution,
            val importContext: ModuleImportContext? = null,
            val warnings: List<TypeCheckWarning> = emptyList(),
            val loadedModules: Map<String, TypedModule> = emptyMap(),
            val abilityImpls: Map<String, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
            @Deprecated("Legacy v1 field — no longer needed since TypedAstEmitter was removed")
            val programBeforeContinuations: Program? = null,
            val typedModule: com.mezze.language.typedast.TypedModule? = null,       // V2 TypedAST (primary output)
            val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>? = null,  // TruffleIR functions (after mono+resolve+lower)
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
     * Check a program through all 7 phases.
     *
     * This is the main entry point!
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
    @Deprecated("Prefer checkParsedModule(ParsedModule, ...) — v1 Program overload kept for backward compat")
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
            // Use precompiled ImportResolver only if the path matches the project root.
            // Tests with custom temp directories need their own resolver.
            val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", "."))
            val importResolver = if (precompiled != null && (currentPath == projectRoot || currentPath.startsWith(projectRoot))) {
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
                // results with the globally solved types before passing to TypedAstBuilder and TypeValidator.


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

                // PHASE 4: Validate Constraints
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

                // V2 Pipeline: TypedAST → Mono → Resolve → Lower → TruffleIR
                // Skip for stdlib modules — they don't need their own TruffleIR since the
                // monomorphizer pulls reachable stdlib functions into the user module's IR.
                val isStdlibModule = currentModulePath != null && currentModulePath.startsWith("std::")
                var emittedTypedModule: com.mezze.language.typedast.TypedModule? = null
                var loweredFns: List<com.mezze.language.truffleir.FunctionIR>? = null
                var loweredPerformers: List<Array<String>> = emptyList()
                var v2MonoModule: com.mezze.language.monoast.MonoModule? = null
                try {
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
                } catch (emitEx: Exception) {
                    System.err.println("V2 TypedAstBuilder (${currentModulePath}): ${emitEx::class.simpleName}: ${emitEx.message}")
                }

                // Phase 2: Mono → Resolve → Lower (entry module only)
                if (!isStdlibModule && emittedTypedModule != null) try {
                    val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(emittedTypedModule!!)

                    // Build allModules: entry + user imports + prelude (all v2 TypedAST)
                    val preludeV2 = PrecompiledStdlib.v2Modules
                    val allModules = HashMap<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>(preludeV2.size + loadedModules.size + 1)
                    allModules.putAll(preludeV2)
                    allModules[wrapped.hash] = wrapped
                    if (loadedModules.isNotEmpty()) {
                        for ((_, v1Module) in loadedModules) {
                            val v2Module = v1Module.v2TypedModule
                            if (v2Module != null) {
                                allModules[v2Module.hash] = v2Module
                            }
                        }
                    }

                    val monoModule = com.mezze.language.typechecker.mono.Monomorphizer().monomorphize(wrapped, allModules)

                    // V2 validation: pattern exhaustiveness and unreachable on MonoAST.
                    v2MonoModule = monoModule

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
                    System.err.println("V2 pipeline: ${pipelineEx::class.simpleName}: ${pipelineEx.message}")
                }

                // V2 validation on MonoAST (pattern exhaustiveness, unreachable patterns).
                // Runs outside pipeline try-catch so validation errors propagate as TypeCheckError.
                if (v2MonoModule != null) {
                    com.mezze.language.typechecker.mono.V2Validator(allTypeDeclarations).validate(v2MonoModule)
                }

                // Validation: TypeValidator for abilities/effects/annotations,
                // Pattern checks handled by V2Validator (on MonoAST) + TypeValidator fallback.
                // Top-level effects: constraint solver wraps performed bindings in SuspendedType.
                // Ability constraints, annotations, effect declarations: TypeValidator.
                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution)
                if (v2ValidationResult is ValidationResult.Failure) {
                    val mapped = v2ValidationResult.errors.map { error ->
                        TypeCheckError(phase = "Validation", message = error.message(), sourceLocation = error.location())
                    }
                    return TypeCheckResult.Failure(mapped)
                }

                // Set inferredType on program bindings so downstream consumers
                // (module exports, tests) can access resolved types.
                val typedItems = desugaredProgram.items.map { item ->
                    when (item) {
                        is LetBinding -> {
                            if (item.inferredType != null) return@map item
                            // Try simple binding name, then destructured pattern key
                            val key = if (item.isSimpleBinding) item.variableName else null
                            val resolved = key?.let { globallyResolvedConstraintResults[it] }?.let { cr ->
                                substitution.apply(cr.type)
                            }
                            if (resolved != null) item.copy(inferredType = resolved) else item
                        }
                        is ImplDeclaration -> {
                            if (item.inferredType != null) return@map item
                            val resolved = globallyResolvedConstraintResults[item.performerName]?.let { cr ->
                                substitution.apply(cr.type)
                            }
                            if (resolved != null) item.copy(inferredType = resolved) else item
                        }
                        else -> item
                    }
                }
                val typedProgram = Program(items = typedItems)

                val validationWarnings = if (v2ValidationResult is ValidationResult.Success) {
                    v2ValidationResult.warnings.map { TypeCheckWarning(message = it, sourceLocation = null) }
                } else emptyList()
                return TypeCheckResult.Success(
                    program = typedProgram,
                    substitution = substitution,
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
        }
    }

    /**
     * Type-check a ParsedModule directly (v2 entry point).
     *
     * This is the preferred entry point. Internally bridges to checkProgram
     * via v1 Program conversion until all phases are migrated to v2.
     */
    /**
     * Type-check a ParsedModule directly (v2 entry point).
     *
     * This is the preferred entry point. Uses the v2 pipeline directly:
     * ParsedModule -> Phase 0 (import resolution) -> Phase 1-5 (all v2).
     * Phases 0.5-0.7 (v1 preprocessing) are skipped since the parser
     * and ContextBuilder handle pipe desugaring, impl resolution, and hashing.
     *
     * Falls back to checkProgram for the v1 bridge (needed for prelude loading
     * and the typed program output) until the full pipeline is v2.
     */
    fun checkParsedModule(
        module: com.mezze.language.parsedast.ParsedModule,
        currentPath: Path = Paths.get("."),
        currentModulePath: String? = null,
        enablePrelude: Boolean = false
    ): TypeCheckResult {
        // TODO: Once Phase 0 import resolution is fully v2, remove this v1 bridge.
        // For now, convert to v1 Program because checkProgram still handles
        // prelude loading and v1 typed program output for downstream consumers.
        @Suppress("DEPRECATION")
        val program = MezzeParser().parsedModuleToProgram(module)
        @Suppress("DEPRECATION")
        return checkProgram(program, currentPath, currentModulePath, enablePrelude)
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if a program has import declarations.
     */
    @Deprecated("Use hasImportsParsed(ParsedModule) instead")
    private fun hasImports(program: Program): Boolean {
        return program.items.any { item ->
            item is UseDeclaration || item is PubUseDeclaration
        }
    }

    /**
     * Check if a ParsedModule has import declarations.
     */
    private fun hasImportsParsed(module: ParsedModule): Boolean {
        return module.decls.any { decl ->
            decl is com.mezze.language.parsedast.ParsedUseDecl || decl is com.mezze.language.parsedast.ParsedPubUseDecl
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

        // Prefer v2 ParsedModule when available
        val parsedModule = module.parsedModule
        if (parsedModule != null) {
            for (decl in parsedModule.decls) {
                when (decl) {
                    is com.mezze.language.parsedast.ParsedLetDecl -> {
                        if (decl.isSimpleBinding && decl.variableName == itemName) return true
                    }
                    is com.mezze.language.parsedast.ParsedTypeDecl -> if (decl.name == itemName) return true
                    is com.mezze.language.parsedast.ParsedAbilityDecl -> if (decl.name == itemName) return true
                    is com.mezze.language.parsedast.ParsedEffectDecl -> if (decl.name == itemName) return true
                    is com.mezze.language.parsedast.ParsedPerformerDecl -> if (decl.performerName == itemName) return true
                    else -> {}
                }
            }
            return false
        }

        // Fallback: v1 Program (for backward compat with preloaded modules)
        @Suppress("DEPRECATION")
        val program = module.program
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    if (item.isSimpleBinding && item.variableName == itemName) return true
                }
                is TypeDeclaration -> if (item.name == itemName) return true
                is AbilityDeclaration -> if (item.name == itemName) return true
                is EffectDeclaration -> if (item.name == itemName) return true
                is ImplDeclaration -> if (item.performerName == itemName) return true
                is ImplAbilityDeclaration -> {}
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
            val tempModule = com.mezze.language.parsedast.ParsedModule("<import-effect>", listOf(effectDecl))
            val tempBuildResult = contextBuilder.build(tempModule, context)
            context = tempBuildResult.context
        }

        // Add imported abilities
        for ((abilityName, abilityDecl) in importContext.importedAbilities) {
            val tempModule = com.mezze.language.parsedast.ParsedModule("<import-ability>", listOf(abilityDecl))
            val tempBuildResult = contextBuilder.build(tempModule, context)
            context = tempBuildResult.context
        }

        // Add imported types (newtype constructors are private — not imported)
        for ((typeName, typeDecl) in importContext.importedTypes) {
            // Convert to v1 for addTypeConstructors (which still takes v1 TypeDeclaration)
            val v1TypeDecl = contextBuilder.parsedTypeDeclToV1(typeDecl)
            context = contextBuilder.addTypeConstructors(context, v1TypeDecl, isImported = true)
        }

        // Add imported ability implementations
        // These are needed for ability method dispatch (e.g., EncodeFormat.encode_int)
        val parserBridge = com.mezze.language.parser.MezzeParser
        for ((implKey, implDecl) in importContext.importedAbilityImpls) {
            if (implDecl.isInherent) {
                // Inherent impls need both AbilityImplInfo AND InherentMethodInfo registered.
                // Use registerImportedInherentImpl to bypass validation (already validated in home module).
                context = contextBuilder.registerImportedInherentImpl(context, implDecl, implKey)
            } else {
                // Named ability impls only need AbilityImplInfo
                // Extract type variable names from abilityTypeArgs for structural matching
                val implTypeVars = implDecl.abilityTypeArgs
                    .filterIsInstance<com.mezze.language.parsedast.ParsedNamedType>()
                    .filter { it.name.startsWith("'") }
                    .map { it.name }
                    .toSet()
                val abilityDeclHashForImpl = implDecl.abilityName?.let { name ->
                    importContext.importedAbilities[name]?.contentHash
                }
                // Convert v2 type annotations to v1 for AbilityImplInfo
                val v1AllTypeArgs = implDecl.allTypeArgs.map { parserBridge.toV1TypeAnnotation(it, "<import>") }
                val v1AssocBindings = implDecl.associatedTypeBindings.mapValues { (_, v) ->
                    parserBridge.toV1TypeAnnotation(v, "<import>")
                }
                val implInfo = TypingContext.AbilityImplInfo(
                    abilityName = implDecl.abilityName,
                    abilityDeclHash = abilityDeclHashForImpl,
                    targetTypes = v1AllTypeArgs.map { typeAnnotationToString(it) },
                    targetTypeAnnotations = v1AllTypeArgs,
                    associatedTypeBindings = v1AssocBindings,
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
     * Generated codec impls get their hashes computed inline in CodecGenerator.
     *
     * @param importContext The import context containing imported effects/abilities with content hashes
     */
    @Deprecated("V1 pipeline only — v2 uses ParsedASTHasher and TypedAstBuilder for hashing")
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

        // Add imported type declarations (v2 ParsedTypeDecl → convert to v1 for addTypeDeclaration)
        importContext?.importedTypes?.values?.forEach { typeDecl ->
            addTypeDeclaration(contextBuilder.parsedTypeDeclToV1(typeDecl))
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
     * // Use typedModule.decls.filterIsInstance<TypedLetDecl>() in v2 pipeline
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
    @Deprecated("Use typeCheck(ParsedModule, ...) instead — v1 Program overload kept for backward compat")
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
    /**
     * Type check a ParsedModule (v2 entry point).
     * Converts to v1 Program and delegates to typeCheck(Program).
     */
    fun typeCheck(module: com.mezze.language.parsedast.ParsedModule, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): com.mezze.language.ast.TypeCheckResult {
        val program = MezzeParser().parsedModuleToProgram(module)
        return typeCheck(program, currentPath, enablePrelude, modulePath)
    }

    @Deprecated("Use typeCheck(ParsedModule, ...) instead — v1 Program overload kept for backward compat")
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
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = emptyMap(),  // v2 types don't fit v1 TypeCheckResult — callers should use typedModule
                    importedEffects = emptyMap(),  // v2 types don't fit v1 TypeCheckResult — callers should use typedModule
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
        // Convert ParsedModule to v1 Program for the existing typechecker
        val v1Program = MezzeParser().parsedModuleToProgram(module)

        // Run the full typechecker
        val result = checkProgram(v1Program, currentPath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                // Use the TypedModule emitted by the v2 pipeline (TypedAstBuilder)
                val typedModule = result.typedModule
                    ?: throw TypeCheckError("V2 pipeline did not emit a TypedModule")
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
        @Deprecated("Use ModuleResolver.getFromCache(modulePath)?.parsedModule instead")
        fun getTypedModule(modulePath: String): Program? {
            // Get from ModuleResolver's cache
            @Suppress("DEPRECATION")
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
                @Suppress("DEPRECATION")
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
                    substitution = com.mezze.language.typechecker.solving.Substitution()
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

    // =========================================================================
    // PIPE EXPRESSION DESUGARING
    // =========================================================================

    /**
     * Desugar all PipeExpressions in a program.
     * Converts: x |> f { a = @ } to f { a = x }
     */
    @Deprecated("V1 pipeline only — v2 ParsedModule desugars pipes in constraint generation")
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
    @Deprecated("V1 pipeline only — v2 ContextBuilder.build(ParsedModule) handles impl resolution")
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
