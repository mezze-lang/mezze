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
 * **PHASE 0.5-0.7: DELETED** (previously v1 preprocessing — now handled by parser/ContextBuilder)
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
 * val result = typeChecker.checkParsedModule(parsedModule)
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
            val substitution: Substitution,
            val importContext: ModuleImportContext? = null,
            val warnings: List<TypeCheckWarning> = emptyList(),
            val loadedModules: Map<String, TypedModule> = emptyMap(),
            val abilityImpls: Map<String, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
            val typedModule: com.mezze.language.typedast.TypedModule? = null,       // V2 TypedAST (primary output)
            val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>? = null,  // TruffleIR functions (after mono+resolve+lower)
            val loweredPerformers: List<Array<String>> = emptyList(),  // Performer record info: [hash, effectHash, op1Hash, op1Name, ...]
            /** Extracted type context: binding name -> resolved MezzeType. Built from constraint results + substitution. */
            val typeContext: Map<String, MezzeType> = emptyMap(),
            /** The resolved ParsedModule (after ContextBuilder resolves performer->ability impls). */
            val resolvedParsedModule: com.mezze.language.parsedast.ParsedModule? = null
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
    /**
     * Type-check a ParsedModule directly (v2 entry point).
     *
     * This is the REAL implementation. Uses the v2 pipeline directly:
     * ParsedModule -> Phase 0 (import resolution) -> Phase 1-5 (all v2).
     * Phases 0.5-0.7 (v1 preprocessing) are skipped since the parser
     * and ContextBuilder handle pipe desugaring, impl resolution, and hashing.
     */
    fun checkParsedModule(
        module: com.mezze.language.parsedast.ParsedModule,
        currentPath: Path = Paths.get("."),
        currentModulePath: String? = null,
        enablePrelude: Boolean = false
    ): TypeCheckResult {
        // Desugar pipe expressions in ParsedModule before type checking
        @Suppress("NAME_SHADOWING")
        val module = desugarPipesInParsedModule(module)

        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()

        // Initialize TypeCheckCache in memory mode if not already initialized with SQLite
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        var loadedModules: Map<String, TypedModule> = emptyMap()
        val shouldInjectPrelude = currentModulePath == null || !currentModulePath.startsWith("std::")
        val hasUserImports = hasImportsParsed(module)

        // Try to use PrecompiledStdlib for fast prelude injection
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { null }

        val enhancedContext = if (!hasUserImports && shouldInjectPrelude && precompiled != null) {
            // FAST PATH: no user imports, precompiled prelude available
            importContext = precompiled.preludeImportContext
            if (cachedPreludeLoadedModules == null) {
                cachedPreludeLoadedModules = precompiled.loadedModules
            }
            precompiled.compiledPrelude.mergeInto(parentContext)
        } else if (hasUserImports || shouldInjectPrelude) {
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

            // Use v2 overload for ParsedModule import resolution
            val importResult = if (hasUserImports) {
                importResolver.resolveImports(module)
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

        // Phases 0.5-0.7 (resolveImplDeclarationsWithImports, computeDeclarationHashes,
        // desugarPipeExpressions) are SKIPPED — ContextBuilder.build(ParsedModule) handles
        // impl resolution, TypedAstBuilder handles hashing, and the parser desugars pipes.

        // PHASE 1: Build Context (reads ParsedModule)
        val contextBuilder = ContextBuilder()
        val buildResult = contextBuilder.build(
            module = module,
            parentContext = enhancedContext,
            importedImpls = importContext?.importedImpls ?: emptyMap(),
            importedAbilities = importContext?.importedAbilities ?: emptyMap(),
            importedTypes = importContext?.importedTypes ?: emptyMap(),
            importedEffects = importContext?.importedEffects ?: emptyMap(),
            currentModulePath = currentModulePath
        )
        val context = buildResult.context

        // Use the resolved module (where ParsedPerformerDecl for abilities are replaced with ParsedImplAbilityDecl)
        val parsedModule = buildResult.resolvedModule ?: module

        // Build type aliases for pretty printing (include imported types)
        cachedTypeAliases = buildTypeAliasesFromParsedModule(parsedModule, contextBuilder, importContext)

        // PHASE 2: Generate Constraints
        val abilityNames = buildResult.abilityDeclarations.keys +
            (importContext?.importedAbilities?.keys ?: emptySet())

        val effectNames = buildResult.effectDeclarations.keys +
            (importContext?.importedEffects?.keys ?: emptySet())

        DebugLogger.debugSection(Category.TypeChecker, "Abilities and Effects") {
            listOf(
                "abilityNames = $abilityNames",
                "effectNames = $effectNames"
            )
        }

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
        val programConstraintResult = constraintGenerator.generateProgram(parsedModule, context)
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors
        val perBindingSubstitution = programConstraintResult.perBindingSubstitution

        // Check for per-binding solve errors
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

        // Collect unused variable warnings
        val unusedVarWarnings = constraintResults.values.flatMap { it.unusedVariables }.map { unused ->
            TypeCheckWarning(
                message = "Unused variable '${unused.name}'. Prefix with '_' to suppress this warning.",
                sourceLocation = unused.sourceLocation
            )
        }

        val patternWarnings = constraintResults.values.flatMap { it.patternWarnings }.map { warningMsg ->
            TypeCheckWarning(message = warningMsg, sourceLocation = null)
        }

        val allConstraints = constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints } +
              constraintResults.values.flatMap { it.associatedTypeConstraints }

        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, allTypeDeclarations, allAbilityDeclarations)
        val solveResult = solver.solve(allConstraints)

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
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
                val substitution = solveResult.substitution.compose(perBindingSubstitution)
                val warnings = solveResult.warnings.map { msg ->
                    TypeCheckWarning(message = msg, sourceLocation = null)
                }.toMutableList()

                warnings.addAll(unusedVarWarnings)
                warnings.addAll(patternWarnings)

                val resolvedAbilities = solveResult.resolvedAbilityConstraints
                val mustBeOpenRowVars = solveResult.mustBeOpenRowVars + perBindingMustBeOpenRowVars
                val mustBeOpenBindings = solveResult.mustBeOpenBindings
                val mustBeOpenTypes = solveResult.mustBeOpenTypes

                val tempGenerator = ConstraintGenerator.create(buildResult.typeDeclarations)

                val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
                    val resolvedType = substitution.apply(result.type)
                    val typeWithAbilities = tempGenerator.addAbilityConstraintsToFunctionType(resolvedType, result.abilityConstraints, substitution)
                    result.copy(type = typeWithAbilities)
                }

                val tailCallLocations = constraintResults.values
                    .flatMap { it.tailCalls }
                    .filterNotNull()
                    .toSet()

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

                val constraintValidation = validator.validateConstraints(allConstraints, substitution)
                when (constraintValidation) {
                    is ValidationResult.Failure -> {
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

                // V2 Pipeline: TypedAST -> Mono -> Resolve -> Lower -> TruffleIR
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

                // Phase 2: Mono -> Resolve -> Lower (entry module only)
                if (!isStdlibModule && emittedTypedModule != null) try {
                    val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(emittedTypedModule!!)

                    val preludeV2 = PrecompiledStdlib.v2Modules
                    val allModules = HashMap<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>(preludeV2.size + loadedModules.size + 1)
                    allModules.putAll(preludeV2)
                    allModules[wrapped.hash] = wrapped
                    // Include direct imports
                    if (loadedModules.isNotEmpty()) {
                        for ((_, v1Module) in loadedModules) {
                            val v2Module = v1Module.v2TypedModule
                            if (v2Module != null) {
                                allModules[v2Module.hash] = v2Module
                            }
                        }
                    }
                    // Include transitive dependencies from loaded modules' parsed use-decls
                    // e.g., exception module imports continuation module — we need continuation's functions
                    val allCached = com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()
                    for ((_, v1Module) in loadedModules) {
                        val parsed = v1Module.parsedModule ?: continue
                        for (decl in parsed.decls) {
                            val depPathStr = when (decl) {
                                is com.mezze.language.parsedast.ParsedUseDecl -> when (val p = decl.path) {
                                    is com.mezze.language.parsedast.ParsedUsePath.Module -> p.path.toString()
                                    is com.mezze.language.parsedast.ParsedUsePath.Selective -> p.modulePath.toString()
                                }
                                is com.mezze.language.parsedast.ParsedPubUseDecl -> when (val p = decl.path) {
                                    is com.mezze.language.parsedast.ParsedUsePath.Module -> p.path.toString()
                                    is com.mezze.language.parsedast.ParsedUsePath.Selective -> p.modulePath.toString()
                                }
                                else -> null
                            }
                            if (depPathStr != null) {
                                val cachedDep = allCached[depPathStr]
                                val depV2 = cachedDep?.v2TypedModule
                                if (depV2 != null && depV2.hash !in allModules) {
                                    allModules[depV2.hash] = depV2
                                }
                            }
                        }
                    }

                    val preloweredHashes = PrecompiledStdlib.preloweredFunctions.keys
                    val monoModule = com.mezze.language.typechecker.mono.Monomorphizer().monomorphize(wrapped, allModules, preloweredHashes)

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

                // V2 validation on MonoAST
                if (v2MonoModule != null) {
                    com.mezze.language.typechecker.mono.V2Validator(allTypeDeclarations).validate(v2MonoModule)
                }

                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution)
                if (v2ValidationResult is ValidationResult.Failure) {
                    val mapped = v2ValidationResult.errors.map { error ->
                        TypeCheckError(phase = "Validation", message = error.message(), sourceLocation = error.location())
                    }
                    return TypeCheckResult.Failure(mapped)
                }

                // Build typeContext from constraint results + substitution
                val typeContext = globallyResolvedConstraintResults.mapValues { (_, cr) ->
                    substitution.apply(cr.type)
                }

                val validationWarnings = if (v2ValidationResult is ValidationResult.Success) {
                    v2ValidationResult.warnings.map { TypeCheckWarning(message = it, sourceLocation = null) }
                } else emptyList()
                return TypeCheckResult.Success(
                    substitution = substitution,
                    importContext = importContext,
                    warnings = warnings + validationWarnings,
                    loadedModules = loadedModules,
                    abilityImpls = context.getAllAbilityImpls(),
                    typedModule = emittedTypedModule,
                    loweredFunctions = loweredFns,
                    loweredPerformers = loweredPerformers,
                    typeContext = typeContext,
                    resolvedParsedModule = parsedModule
                )
            }
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

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
        val syntheticModule = if (preludeSource != null) {
            MezzeParser().parseParsedModule(preludeSource, file = "<prelude>")
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
            val syntheticLoc = com.mezze.language.parsedast.Loc("<prelude>", 0, 0, 0, 0)
            com.mezze.language.parsedast.ParsedModule(
                path = "<prelude>",
                decls = preludeModules.map { segments ->
                    com.mezze.language.parsedast.ParsedUseDecl(
                        path = com.mezze.language.parsedast.ParsedUsePath.Module(
                            com.mezze.language.parsedast.ParsedModulePath(segments), syntheticLoc
                        ),
                        loc = syntheticLoc
                    )
                }
            )
        }

        val result = importResolver.resolveImports(syntheticModule)
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

        // No parsedModule available — item not found
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
            context = contextBuilder.addParsedTypeConstructors(context, typeDecl, isImported = true)
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
                val implInfo = TypingContext.AbilityImplInfo(
                    abilityName = implDecl.abilityName,
                    abilityDeclHash = abilityDeclHashForImpl,
                    targetTypes = implDecl.allTypeArgs.map { it.toString() },
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
     * Build type aliases from a ParsedModule (v2 path).
     * Same as buildTypeAliases but reads from ParsedModule instead of v1 Program.
     */
    private fun buildTypeAliasesFromParsedModule(
        module: ParsedModule,
        contextBuilder: ContextBuilder,
        importContext: ModuleImportContext?
    ): Map<String, TypeAliasInfo> {
        val result = mutableMapOf<String, TypeAliasInfo>()

        fun addParsedTypeDecl(typeDecl: com.mezze.language.parsedast.ParsedTypeDecl) {
            val definitionType = when (val def = typeDecl.definition) {
                is com.mezze.language.parsedast.ParsedVariantDef -> {
                    val constructors = def.constructors.associate { ctor ->
                        val dataType = ctor.dataType?.let { contextBuilder.convertParsedTypeAnnotation(it) }
                        ctor.name to dataType
                    }
                    MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = if (def.isOpen) RowVar.fresh() else null,
                        typeName = typeDecl.name
                    )
                }
                is com.mezze.language.parsedast.ParsedRecordDef -> {
                    val fields = def.fields.associate { field ->
                        field.name to contextBuilder.convertParsedTypeAnnotation(field.type)
                    }
                    MezzeType.RecordType(
                        fields = fields,
                        rowVar = if (def.isOpen) RowVar.fresh() else null,
                        typeName = typeDecl.name
                    )
                }
                else -> null
            }
            if (definitionType != null) {
                result[typeDecl.name] = TypeAliasInfo(
                    name = typeDecl.name,
                    typeParams = typeDecl.typeParams,
                    definition = definitionType
                )
            }
        }

        // Add local type declarations from ParsedModule
        for (decl in module.decls) {
            if (decl is com.mezze.language.parsedast.ParsedTypeDecl) {
                addParsedTypeDecl(decl)
            }
        }

        // Add imported type declarations
        importContext?.importedTypes?.values?.forEach { typeDecl ->
            addParsedTypeDecl(typeDecl)
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
        // Reset type variable counter for consistent naming (old behavior)
        com.mezze.language.ast.TypeVar.resetCounter()

        // Delegate directly to checkParsedModule (the real v2 implementation)
        val result = checkParsedModule(module, currentPath, currentModulePath = modulePath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                val resolvedImports = buildResolvedImports(result.importContext)
                val structuredWarnings = result.warnings.map { warning ->
                    com.mezze.language.ast.TypeCheckWarning(warning.message, warning.sourceLocation)
                }
                com.mezze.language.ast.TypeCheckResult(
                    program = Program(emptyList()),
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = emptyMap(),
                    importedEffects = emptyMap(),
                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                    typedModule = result.typedModule,
                    performerRecords = result.loweredPerformers,
                    loadedModules = result.loadedModules.mapValues { (_, m) -> m.v2TypedModule }.filterValues { it != null }.mapValues { (_, v) -> v!! }
                )
            }
            is TypeCheckResult.Failure -> {
                val error = result.errors.firstOrNull()
                    ?: throw TypeCheckError("Type checking failed with unknown error")
                throw TypeCheckError(error.message, error.sourceLocation)
            }
        }
    }

    @Deprecated("Use typeCheck(ParsedModule, ...) instead — v1 Program overload kept for backward compat")
    fun typeCheck(program: Program, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): com.mezze.language.ast.TypeCheckResult {
        // Reset type variable counter for consistent naming (old behavior)
        com.mezze.language.ast.TypeVar.resetCounter()

        // Convert v1 Program to v2 ParsedModule and delegate to the real implementation
        val parsedModule = MezzeParser().programToParsedModule(program, modulePath ?: "<unknown>")
        val result = checkParsedModule(parsedModule, currentPath, currentModulePath = modulePath, enablePrelude = enablePrelude)

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
                    program = program,
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = emptyMap(),
                    importedEffects = emptyMap(),
                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                    typedModule = result.typedModule,
                    performerRecords = result.loweredPerformers
                )
            }
            is TypeCheckResult.Failure -> {
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

    fun checkParsedModuleToTyped(
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
        // Call the 4-param checkParsedModule directly (the real v2 implementation)
        val result = checkParsedModule(module, currentPath, currentModulePath = null, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
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
        @JvmStatic
        var cachedPreludeLoadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>? = null

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
                // Convert v1 Program to v2 ParsedModule and use v2 export path
                val parsedModule = MezzeParser().programToParsedModule(program, modulePath)
                resolver.updateModuleExportsV2(modulePath, parsedModule)

                // Also cache in ModuleTypeChecker's global cache to prevent re-type-checking.
                // Without this, the prelude re-type-checks modules from source, which fails
                // because ability impls aren't in scope during re-validation (e.g., `1 + 2`
                // needs NumAdd for Int, which isn't available during standalone module validation).
                val typedModule = com.mezze.language.typechecker.module.TypedModule(
                    modulePath = modulePath,
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
     * Desugar pipe expressions in a ParsedModule (v2 pipeline).
     * Converts: x |> f { a = @ }  ->  f { a = x }
     * Recursively walks all expressions in declarations.
     */
    private fun desugarPipesInParsedModule(module: com.mezze.language.parsedast.ParsedModule): com.mezze.language.parsedast.ParsedModule {
        // Quick check: does the module even contain pipes?
        fun hasPipes(expr: com.mezze.language.parsedast.ParsedExpr): Boolean = when (expr) {
            is com.mezze.language.parsedast.ParsedPipe -> true
            is com.mezze.language.parsedast.ParsedFunctionCall -> hasPipes(expr.function) || hasPipes(expr.arguments)
            is com.mezze.language.parsedast.ParsedLambda -> hasPipes(expr.body)
            is com.mezze.language.parsedast.ParsedRecordLit -> expr.entries.any { e ->
                when (e) {
                    is com.mezze.language.parsedast.ParsedRecordEntry.Field -> e.value?.let { hasPipes(it) } ?: false
                    is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> hasPipes(e.expr)
                }
            }
            is com.mezze.language.parsedast.ParsedIf -> hasPipes(expr.condition) || hasPipes(expr.thenBranch) || hasPipes(expr.elseBranch)
            is com.mezze.language.parsedast.ParsedMatch -> hasPipes(expr.scrutinee) || expr.cases.any { hasPipes(it.body) }
            is com.mezze.language.parsedast.ParsedDo -> hasPipes(expr.result) || expr.statements.any { stmt ->
                when (stmt) {
                    is com.mezze.language.parsedast.ParsedStatement.Let -> hasPipes(stmt.value)
                    is com.mezze.language.parsedast.ParsedStatement.Expr -> hasPipes(stmt.expr)
                }
            }
            is com.mezze.language.parsedast.ParsedPerform -> hasPipes(expr.body)
            is com.mezze.language.parsedast.ParsedMemberAccess -> hasPipes(expr.receiver)
            is com.mezze.language.parsedast.ParsedParens -> hasPipes(expr.expr)
            is com.mezze.language.parsedast.ParsedVariantConstruct -> expr.value?.let { hasPipes(it) } ?: false
            is com.mezze.language.parsedast.ParsedSeqLit -> expr.elements.any { elem ->
                when (elem) {
                    is com.mezze.language.parsedast.ParsedSeqElement.Regular -> hasPipes(elem.expr)
                    is com.mezze.language.parsedast.ParsedSeqElement.Spread -> hasPipes(elem.expr)
                }
            }
            is com.mezze.language.parsedast.ParsedStringLit -> expr.parts.any { part ->
                part is com.mezze.language.parsedast.ParsedStringPart.Interpolation && hasPipes(part.expr)
            }
            is com.mezze.language.parsedast.ParsedEffectCall -> hasPipes(expr.arguments)
            else -> false
        }
        val hasAnyPipes = module.decls.any { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> hasPipes(decl.value)
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> decl.operations.any { hasPipes(it.implementation) }
                else -> false
            }
        }
        if (!hasAnyPipes) return module

        fun desugar(expr: com.mezze.language.parsedast.ParsedExpr): com.mezze.language.parsedast.ParsedExpr = when (expr) {
            is com.mezze.language.parsedast.ParsedPipe -> {
                val left = desugar(expr.left)
                val right = desugar(expr.right)
                replaceParsedPlaceholder(right, left)
            }
            is com.mezze.language.parsedast.ParsedFunctionCall -> expr.copy(function = desugar(expr.function), arguments = desugar(expr.arguments))
            is com.mezze.language.parsedast.ParsedLambda -> expr.copy(body = desugar(expr.body))
            is com.mezze.language.parsedast.ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
                when (e) {
                    is com.mezze.language.parsedast.ParsedRecordEntry.Field -> e.copy(value = e.value?.let { desugar(it) })
                    is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> e.copy(expr = desugar(e.expr))
                }
            })
            is com.mezze.language.parsedast.ParsedIf -> expr.copy(condition = desugar(expr.condition), thenBranch = desugar(expr.thenBranch), elseBranch = desugar(expr.elseBranch))
            is com.mezze.language.parsedast.ParsedMatch -> expr.copy(scrutinee = desugar(expr.scrutinee), cases = expr.cases.map { it.copy(body = desugar(it.body)) })
            is com.mezze.language.parsedast.ParsedDo -> expr.copy(
                statements = expr.statements.map { stmt ->
                    when (stmt) {
                        is com.mezze.language.parsedast.ParsedStatement.Let -> stmt.copy(value = desugar(stmt.value))
                        is com.mezze.language.parsedast.ParsedStatement.Expr -> stmt.copy(expr = desugar(stmt.expr))
                    }
                },
                result = desugar(expr.result)
            )
            is com.mezze.language.parsedast.ParsedPerform -> expr.copy(body = desugar(expr.body))
            is com.mezze.language.parsedast.ParsedMemberAccess -> expr.copy(receiver = desugar(expr.receiver))
            is com.mezze.language.parsedast.ParsedParens -> expr.copy(expr = desugar(expr.expr))
            is com.mezze.language.parsedast.ParsedVariantConstruct -> expr.copy(value = expr.value?.let { desugar(it) })
            is com.mezze.language.parsedast.ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
                when (elem) {
                    is com.mezze.language.parsedast.ParsedSeqElement.Regular -> elem.copy(expr = desugar(elem.expr))
                    is com.mezze.language.parsedast.ParsedSeqElement.Spread -> elem.copy(expr = desugar(elem.expr))
                }
            })
            is com.mezze.language.parsedast.ParsedEffectCall -> expr.copy(arguments = desugar(expr.arguments))
            else -> expr
        }

        return module.copy(decls = module.decls.map { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> decl.copy(value = desugar(decl.value))
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op -> op.copy(implementation = desugar(op.implementation)) })
                else -> decl
            }
        })
    }

    /**
     * Replace ParsedPipePlaceholder (@) with the left-hand expression in a v2 AST tree.
     */
    private fun replaceParsedPlaceholder(expr: com.mezze.language.parsedast.ParsedExpr, replacement: com.mezze.language.parsedast.ParsedExpr): com.mezze.language.parsedast.ParsedExpr = when (expr) {
        is com.mezze.language.parsedast.ParsedPipePlaceholder -> replacement
        is com.mezze.language.parsedast.ParsedFunctionCall -> expr.copy(
            function = replaceParsedPlaceholder(expr.function, replacement),
            arguments = replaceParsedPlaceholder(expr.arguments, replacement)
        )
        is com.mezze.language.parsedast.ParsedRecordLit -> expr.copy(entries = expr.entries.map { e ->
            when (e) {
                is com.mezze.language.parsedast.ParsedRecordEntry.Field -> e.copy(value = e.value?.let { replaceParsedPlaceholder(it, replacement) })
                is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> e.copy(expr = replaceParsedPlaceholder(e.expr, replacement))
            }
        })
        is com.mezze.language.parsedast.ParsedLambda -> expr.copy(body = replaceParsedPlaceholder(expr.body, replacement))
        is com.mezze.language.parsedast.ParsedIf -> expr.copy(
            condition = replaceParsedPlaceholder(expr.condition, replacement),
            thenBranch = replaceParsedPlaceholder(expr.thenBranch, replacement),
            elseBranch = replaceParsedPlaceholder(expr.elseBranch, replacement)
        )
        is com.mezze.language.parsedast.ParsedParens -> expr.copy(expr = replaceParsedPlaceholder(expr.expr, replacement))
        is com.mezze.language.parsedast.ParsedMemberAccess -> expr.copy(receiver = replaceParsedPlaceholder(expr.receiver, replacement))
        else -> expr
    }

    // V1 pipe desugarer deleted — v2 ParsedModule desugars pipes in desugarPipesInParsedModule()

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
