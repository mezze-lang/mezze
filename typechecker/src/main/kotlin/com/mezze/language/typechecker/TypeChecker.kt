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
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.module.*
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.validation.TypeValidator
import com.mezze.language.typechecker.validation.ValidationResult
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.typechecker.cache.TypeCheckCache
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.phase.*
import java.nio.file.Path
import java.nio.file.Paths

/**
 * New type checker - orchestrates all phases of type checking.
 *
 * ## Phase Architecture:
 *
 * **PHASE 0: Module Resolution** (optional - only if imports present)
 * - Input: ParsedModule with ParsedUseDecl
 * - Output: ModuleImportContext + TypedModules
 * - What: Load and type-check imported modules
 *
 * **PHASE 1: Build Context**
 * - Input: ParsedModule (+ optional ModuleImportContext)
 * - Output: TypingContext (immutable!)
 *
 * **PHASE 2: Generate Constraints**
 * - Input: ParsedModule, TypingContext
 * - Output: List<Constraint>
 *
 * **PHASE 3: Solve Constraints**
 * - Input: List<Constraint>
 * - Output: Substitution
 *
 * **PHASE 4: Validate Constraints**
 * - Input: Constraints, Substitution
 * - Output: ValidationResult
 *
 * **PHASE 5: Emit TypedAST + Compile**
 * - Input: ParsedModule, Substitution, ConstraintResults
 * - Output: TypedModule, FunctionIR list
 *
 * Each phase's logic lives in `phase/`; this file orchestrates.
 */
class TypeChecker(
    internal val parentContext: TypingContext = createInitialContext()
) {
    /** Bridge for PrecompiledStdlib to call loadPreludeModules */
    internal fun loadPreludeModulesPublic(importResolver: ImportResolver): ModuleImportContext {
        return loadPreludeModulesInternal(importResolver, ModuleImportContext.empty())
    }

    /** Bridge for PrecompiledStdlib to call mergeImportContext */
    internal fun mergeImportContextPublic(base: TypingContext, importCtx: ModuleImportContext): TypingContext {
        return mergeImportContext(base, importCtx)
    }

    /** Bridge for PrecompiledStdlib to get cachedPreludeLoadedModules */
    internal fun getCachedPreludeLoadedModules(): Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>? {
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
            val loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule> = emptyMap(),
            val abilityImpls: Map<com.mezze.language.typedast.Hash, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
            val typedModule: com.mezze.language.typedast.TypedModule? = null,
            val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>? = null,
            val loweredPerformers: List<Array<String>> = emptyList(),
            val typeContext: Map<String, MezzeType> = emptyMap(),
            val resolvedParsedModule: com.mezze.language.parsedast.ParsedModule? = null,
            val expectedTypeAt: Map<com.mezze.language.parsedast.Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()

        /**
         * Type checking failed.
         */
        data class Failure(
            val errors: List<TypeCheckError>,
            val typedModule: com.mezze.language.typedast.TypedModule? = null,
            val expectedTypeAt: Map<com.mezze.language.parsedast.Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()
    }

    /**
     * Type checking error.
     */
    data class TypeCheckError(
        val phase: String,
        val message: String,
        val loc: com.mezze.language.parsedast.Loc? = null,
        val errorType: ErrorType = ErrorType.GENERAL,
        val metadata: Map<String, Any> = emptyMap(),
        val code: com.mezze.language.parsedast.diagnostics.ErrorCode =
            com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED,
        val hints: List<String> = emptyList(),
        val fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> = emptyList(),
    )

    /**
     * Type checking warning.
     */
    data class TypeCheckWarning(
        val message: String,
        val loc: com.mezze.language.parsedast.Loc? = null
    )


    /**
     * Type-check a ParsedModule directly (v2 entry point).
     *
     * This is the REAL implementation. Uses the v2 pipeline directly:
     * ParsedModule -> Phase 0 (import resolution) -> Phase 1-5 (all v2).
     */
    fun checkParsedModule(
        module: com.mezze.language.parsedast.ParsedModule,
        currentPath: Path = Paths.get("."),
        currentModulePath: String? = null,
        enablePrelude: Boolean = false
    ): TypeCheckResult {
        // Pre-phase: desugar pipe expressions
        @Suppress("NAME_SHADOWING")
        var module = desugarPipesInParsedModule(module)

        // Pre-phase: desugar do-block <- statements
        val bindDesugarResult = com.mezze.language.typechecker.desugar.DoBindDesugar.desugar(module)
        module = bindDesugarResult.module
        val bindLetLocs = bindDesugarResult.bindLetLocs
        val moduleUsesBind = bindLetLocs.isNotEmpty()

        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        // PHASE 0: Module Resolution
        var importContext: ModuleImportContext? = null
        var loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule> = emptyMap()
        val shouldInjectPrelude = !com.mezze.language.ast.ModulePathConventions.isStdlib(currentModulePath)
        val hasUserImports = hasImportsParsed(module)
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { null }

        val enhancedContext = if (!hasUserImports && shouldInjectPrelude && precompiled != null && !moduleUsesBind) {
            importContext = precompiled.preludeImportContext
            if (cachedPreludeLoadedModules == null) {
                cachedPreludeLoadedModules = precompiled.loadedModules
            }
            precompiled.compiledPrelude.mergeInto(parentContext)
        } else if (hasUserImports || shouldInjectPrelude || moduleUsesBind) {
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
                importResolver.resolveImports(module)
            } else {
                ImportResolutionResult.Success(ModuleImportContext.empty(), emptyMap())
            }

            when (importResult) {
                is ImportResolutionResult.Success -> {
                    importContext = importResult.importContext
                    loadedModules = importResult.loadedModules

                    val localTypeNames = module.decls
                        .filterIsInstance<com.mezze.language.parsedast.ParsedTypeDecl>()
                        .map { it.name }
                        .toSet()
                    val builtinNames = com.mezze.language.ast.BuiltinTypes.allBuiltinTypeNames
                    if (shouldInjectPrelude) {
                        val preludeContext = loadPreludeModulesInternal(importResolver, importContext!!)
                        importContext = importContext!!.merge(preludeContext)
                    }
                    if (moduleUsesBind) {
                        importContext = injectExceptionToResultForBindInternal(importContext!!, importResolver)
                    }
                    val inScopeNames = localTypeNames + importContext!!.explicitlyImportedTypeNames + builtinNames
                    mergeImportContext(parentContext, importContext!!, inScopeNames)
                }
                is ImportResolutionResult.Failure -> {
                    throw importResult.errors.firstOrNull()
                        ?: TypeCheckError("Import failed with unknown error")
                }
            }
        } else {
            parentContext
        }

        // PHASE 1: Build Context
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
        val parsedModule = buildResult.resolvedModule ?: module

        cachedTypeAliases = buildTypeAliasesFromParsedModule(parsedModule, contextBuilder, importContext)

        // PHASE 2: Generate Constraints
        val abilityNames = buildResult.registry.abilityNames +
            (importContext?.importedAbilities?.keys ?: emptySet())
        val effectNames = buildResult.registry.effectNames +
            (importContext?.importedEffects?.keys ?: emptySet())

        DebugLogger.debugSection(Category.TypeChecker, "Abilities and Effects") {
            listOf("abilityNames = $abilityNames", "effectNames = $effectNames")
        }

        // Collect transitive effects and abilities from all loaded modules
        val transitiveEffects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        for ((_, mod) in loadedModules) {
            mod.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        val allEffectDeclarations = transitiveEffects +
            buildResult.registry.effectDeclarations +
            (importContext?.importedEffects ?: emptyMap())
        val allTypeDeclarations = buildResult.registry.typeDeclarations +
            (importContext?.importedTypes ?: emptyMap())

        val importedAbilities = importContext?.importedAbilities ?: emptyMap()
        val transitiveAbilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        for ((_, mod) in loadedModules) {
            mod.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        val allAbilityDeclarations = transitiveAbilities + buildResult.registry.abilityDeclarations + importedAbilities

        val registry = com.mezze.language.typechecker.context.DeclarationRegistry.from(
            allTypeDeclarations, allAbilityDeclarations, allEffectDeclarations,
        )

        val constraintGenerator = ConstraintGenerator.create(
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            performerAddedEffects = buildResult.performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
        )

        val inScopeAliasNames = buildResult.registry.typeNames +
            (importContext?.explicitlyImportedTypeNames ?: emptySet()) +
            com.mezze.language.ast.BuiltinTypes.allBuiltinTypeNames
        val programConstraintResult = com.mezze.language.typechecker.solving.withInScopeAliasNames(inScopeAliasNames) {
            constraintGenerator.generateProgram(parsedModule, context)
        }
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors
        val perBindingSubstitution = programConstraintResult.perBindingSubstitution
        val collectedTypeCheckErrors = programConstraintResult.collectedTypeCheckErrors

        val collectedErrors = mutableListOf<TypeCheckError>()

        if (collectedTypeCheckErrors.isNotEmpty()) {
            collectedErrors.addAll(collectedTypeCheckErrors.map { e ->
                TypeCheckError(
                    phase = "Type Checking",
                    message = e.message ?: "Type checking failed",
                    loc = e.loc, errorType = e.errorType, metadata = e.metadata,
                    code = e.code, hints = e.hints, fixHints = e.fixHints,
                )
            })
        }

        if (perBindingSolveErrors.isNotEmpty()) {
            collectedErrors.addAll(perBindingSolveErrors.map { error ->
                classifyConstraintError(error)
            })
        }

        // Check for undefined variables
        val undefinedVarErrors = checkUndefinedVariables(
            constraintResults = constraintResults,
            context = context,
            registry = registry,
            implDeclarations = buildResult.implDeclarations,
            loadedModules = loadedModules,
            importContext = importContext,
        )
        collectedErrors.addAll(undefinedVarErrors)

        // Check for duplicate bindings
        val duplicateBindings = constraintResults.values.flatMap { it.duplicateBindings }
        if (duplicateBindings.isNotEmpty()) {
            collectedErrors.addAll(duplicateBindings.map { duplicate ->
                TypeCheckError(
                    phase = "Validation",
                    message = "Duplicate binding: '${duplicate.name}' is already defined in this scope",
                    loc = duplicate.loc,
                    code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                )
            })
        }

        // Collect warnings
        val unusedVarWarnings = constraintResults.values.flatMap { it.unusedVariables }.map { unused ->
            TypeCheckWarning(
                message = "Unused variable '${unused.name}'. Prefix with '_' to suppress this warning.",
                loc = unused.loc
            )
        }
        val patternWarnings = constraintResults.values.flatMap { it.patternWarnings }.map { warningMsg ->
            TypeCheckWarning(message = warningMsg, loc = null)
        }

        val allConstraints = constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints } +
              constraintResults.values.flatMap { it.associatedTypeConstraints }

        // PHASE 3: Solve Constraints
        val solver = ConstraintSolver(context, registry)
        val solveResult = com.mezze.language.typechecker.solving.withInScopeAliasNames(inScopeAliasNames) {
            solver.solve(allConstraints)
        }

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                collectedErrors.addAll(solveResult.errors.map { classifyConstraintError(it) })
                val seen = mutableSetOf<Pair<com.mezze.language.parsedast.Loc?, String>>()
                val deduped = collectedErrors.filter { e -> seen.add(e.loc to e.message) }
                val partialTyped = buildPartialTypedModule(
                    parsedModule = parsedModule, substitution = perBindingSubstitution,
                    constraintResults = constraintResults, context = context,
                    buildResult = buildResult, importContext = importContext,
                    registry = registry, bindLetLocs = bindLetLocs,
                )
                val partialExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                        acc + r.expectedTypeAt
                    }
                    .mapValues { (_, type) -> perBindingSubstitution.apply(type) }
                return TypeCheckResult.Failure(
                    errors = deduped, typedModule = partialTyped,
                    expectedTypeAt = partialExpectedTypeAt,
                )
            }
            is ConstraintSolver.SolveResult.Success -> {
                val substitution = solveResult.substitution.compose(perBindingSubstitution)
                val warnings = solveResult.warnings.map { msg ->
                    TypeCheckWarning(message = msg, loc = null)
                }.toMutableList()
                warnings.addAll(unusedVarWarnings)
                warnings.addAll(patternWarnings)

                val mustBeOpenRowVars = solveResult.mustBeOpenRowVars + perBindingMustBeOpenRowVars

                val tempGenerator = ConstraintGenerator.create(com.mezze.language.typechecker.context.DeclarationRegistry.from(buildResult.registry.typeDeclarations, emptyMap(), emptyMap()))
                val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
                    val resolvedType = substitution.apply(result.type)
                    val typeWithAbilities = tempGenerator.addAbilityConstraintsToFunctionType(resolvedType, result.abilityConstraints, substitution)
                    result.copy(type = typeWithAbilities)
                }

                val tailCallLocations = constraintResults.values.flatMap { it.tailCalls }.filterNotNull().toSet()
                val allMethodCallResolutions = constraintResults.values
                    .fold(emptyMap<Pair<com.mezze.language.parsedast.Loc, String>, ConstraintResult.MethodCallInfo>()) { acc, r -> acc + r.methodCallResolutions }
                val allCallReturnTypes = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r -> acc + r.callReturnTypes }
                val allMatchCaseBindings = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, Map<String, com.mezze.language.ast.MezzeType>>()) { acc, r ->
                        acc + r.matchCaseBindings.mapValues { (_, bindings) ->
                            bindings.mapValues { (_, ty) -> substitution.apply(ty) }
                        }
                    }
                val allExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r -> acc + r.expectedTypeAt }
                    .mapValues { (_, type) -> substitution.apply(type) }

                // PHASE 4: Validate Constraints
                val validator = TypeValidator(context, registry, buildResult.implDeclarations)
                val constraintValidation = validator.validateConstraints(allConstraints, substitution)
                when (constraintValidation) {
                    is ValidationResult.Failure -> {
                        collectedErrors.addAll(constraintValidation.errors.map { error ->
                            TypeCheckError(
                                phase = "Validation", message = error.message(), loc = error.location(),
                                code = error.code, hints = error.hints, fixHints = error.fixHints,
                            )
                        })
                    }
                    is ValidationResult.Success -> {
                        constraintValidation.warnings.forEach { msg ->
                            warnings.add(TypeCheckWarning(message = msg, loc = null))
                        }
                    }
                }

                // PHASE 5: Emit TypedAST + Compile
                val isStdlibModule = com.mezze.language.ast.ModulePathConventions.isStdlib(currentModulePath)
                val emitResult = emitTypedModuleAndLower(
                    parsedModule = parsedModule, module = module,
                    substitution = substitution,
                    constraintResults = globallyResolvedConstraintResults,
                    context = context, buildResult = buildResult,
                    importContext = importContext, registry = registry,
                    solver = solver, loadedModules = loadedModules,
                    tailCallLocations = tailCallLocations,
                    mustBeOpenRowVars = mustBeOpenRowVars,
                    methodCallResolutions = allMethodCallResolutions,
                    callReturnTypes = allCallReturnTypes,
                    matchCaseBindings = allMatchCaseBindings,
                    bindLetLocs = bindLetLocs,
                    isStdlibModule = isStdlibModule,
                    currentModulePath = currentModulePath,
                )

                // V2 Validation
                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution)
                if (v2ValidationResult is ValidationResult.Failure) {
                    collectedErrors.addAll(v2ValidationResult.errors.map { error ->
                        TypeCheckError(
                            phase = "Validation", message = error.message(), loc = error.location(),
                            code = error.code, hints = error.hints, fixHints = error.fixHints,
                        )
                    })
                }

                val typeContext = globallyResolvedConstraintResults.mapValues { (_, cr) ->
                    substitution.apply(cr.type)
                }
                val validationWarnings = if (v2ValidationResult is ValidationResult.Success) {
                    v2ValidationResult.warnings.map { TypeCheckWarning(message = it, loc = null) }
                } else emptyList()

                // Final disposition
                if (collectedErrors.isNotEmpty()) {
                    val seen = mutableSetOf<Pair<com.mezze.language.parsedast.Loc?, String>>()
                    val deduped = collectedErrors.filter { e -> seen.add(e.loc to e.message) }
                    return TypeCheckResult.Failure(
                        errors = deduped, typedModule = emitResult.typedModule,
                        expectedTypeAt = allExpectedTypeAt,
                    )
                }
                return TypeCheckResult.Success(
                    substitution = substitution, importContext = importContext,
                    warnings = warnings + validationWarnings,
                    loadedModules = loadedModules,
                    abilityImpls = context.getAllAbilityImpls(),
                    typedModule = emitResult.typedModule,
                    loweredFunctions = emitResult.loweredFunctions,
                    loweredPerformers = emitResult.loweredPerformers,
                    typeContext = typeContext,
                    resolvedParsedModule = parsedModule,
                    expectedTypeAt = allExpectedTypeAt,
                )
            }
        }
    }

    private fun hasImportsParsed(module: ParsedModule): Boolean {
        return module.decls.any { decl ->
            decl is com.mezze.language.parsedast.ParsedUseDecl || decl is com.mezze.language.parsedast.ParsedPubUseDecl
        }
    }

    private fun loadPreludeModulesInternal(
        importResolver: ImportResolver,
        userImportContext: ModuleImportContext
    ): ModuleImportContext {
        val result = loadPreludeModules(
            importResolver = importResolver,
            userImportContext = userImportContext,
            cachedPreludeContext = cachedPreludeContext,
            onPreludeLoaded = { _, loadedMods ->
                cachedPreludeLoadedModules = loadedMods
            },
        )
        cachedPreludeContext = result
        return result
    }

    private fun injectExceptionToResultForBindInternal(
        baseCtx: ModuleImportContext,
        importResolver: ImportResolver,
    ): ModuleImportContext {
        return injectExceptionToResultForBind(
            baseCtx = baseCtx,
            importResolver = importResolver,
            cachedExceptionForBindCtx = cachedExceptionForBindCtx,
            onCached = { cachedExceptionForBindCtx = it },
        )
    }

    // Cached type aliases from the last type check
    private var cachedTypeAliases: Map<String, TypeAliasInfo> = emptyMap()

    fun getTypeAliases(): Map<String, TypeAliasInfo> {
        return cachedTypeAliases
    }

    @Deprecated("Use inferredType on AST nodes instead", ReplaceWith("typedProgram.items"))
    fun getContext(): Map<String, MezzeType> {
        return emptyMap()
    }

    /**
     * Type check a ParsedModule (v2 entry point).
     *
     * @throws TypeCheckError if type checking fails
     */
    @JvmOverloads
    fun typeCheck(module: com.mezze.language.parsedast.ParsedModule, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): com.mezze.language.typechecker.TypeCheckResult {
        com.mezze.language.ast.TypeVar.resetCounter()
        val result = checkParsedModule(module, currentPath, currentModulePath = modulePath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                val resolvedImports = buildResolvedImports(result.importContext)
                val structuredWarnings = result.warnings.map { warning ->
                    com.mezze.language.typechecker.TypeCheckWarning(warning.message, warning.loc)
                }
                com.mezze.language.typechecker.TypeCheckResult(
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                    importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                    typedModule = result.typedModule,
                    performerRecords = result.loweredPerformers,
                    loadedModules = result.loadedModules.mapValues { (_, m) -> m.typedModule }.filterValues { it != null }.mapValues { (_, v) -> v!! },
                    loadedModuleSources = result.loadedModules
                        .mapNotNull { (path, m) ->
                            val fp = m.filePath ?: return@mapNotNull null
                            val sh = m.sourceHash ?: return@mapNotNull null
                            path to com.mezze.language.typechecker.LoadedModuleSource(fp, sh)
                        }.toMap(),
                    expectedTypeAt = result.expectedTypeAt,
                )
            }
            is TypeCheckResult.Failure -> {
                val error = result.errors.firstOrNull()
                    ?: throw TypeCheckError(
                        "Type checking failed with unknown error",
                        code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
                    )
                throw TypeCheckError(
                    error.message, error.loc, error.errorType, error.metadata,
                    code = error.code, hints = error.hints,
                )
            }
        }
    }

    data class ParsedModuleCheckResult(
        val module: com.mezze.language.typedast.TypedModule,
        val loadedModules: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>
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
        val result = checkParsedModule(module, currentPath, currentModulePath = null, enablePrelude = enablePrelude)
        return when (result) {
            is TypeCheckResult.Success -> {
                val typedModule = result.typedModule
                    ?: throw TypeCheckError(
                        "V2 pipeline did not emit a TypedModule",
                        code = ErrorCode.I0002_PIPELINE_NO_TYPED_MODULE,
                    )
                ParsedModuleCheckResult(typedModule, result.loadedModules)
            }
            is TypeCheckResult.Failure -> {
                val error = result.errors.firstOrNull()
                    ?: throw TypeCheckError(
                        "Type checking failed with unknown error",
                        code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
                    )
                throw TypeCheckError(
                    error.message, error.loc, error.errorType, error.metadata,
                    code = error.code, hints = error.hints,
                )
            }
        }
    }

    companion object {
        @Volatile
        private var cachedPreludeContext: ModuleImportContext? = null

        @Volatile
        internal var cachedExceptionForBindCtx: ModuleImportContext? = null

        @Volatile
        @JvmStatic
        var cachedPreludeLoadedModules: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>? = null

        private val v1ToV2ModuleCache = java.util.concurrent.ConcurrentHashMap<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>()

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
            val enhanced = mergeImportContext(parentContext, preludeCtx)
            cachedPreludeEnhancedContext = enhanced
            cachedPreludeEnhancedContextParentId = parentId
            return enhanced
        }

        fun clearPreludeCache() {
            cachedPreludeContext = null
            cachedPreludeLoadedModules = null
            cachedPreludeEnhancedContext = null
        }

        private fun createInitialContext(): TypingContext {
            val nativeFunctions = mutableMapOf<String, MezzeType>()
            NativeFunctionTypes.registerAll(nativeFunctions)

            var context = TypingContext.empty
            for ((name, type) in nativeFunctions) {
                val bindingKey = BindingKey.parse(name)
                val freeVars = collectFreeTypeVars(type)
                if (freeVars.isEmpty()) {
                    context = context.extendMono(bindingKey, type)
                } else {
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

        fun create(): TypeChecker = TypeChecker()
        fun withContext(context: TypingContext): TypeChecker = TypeChecker(context)

        @JvmStatic
        fun clearCache() {
            ModuleResolver.clearCache()
            TypeCheckCache.clearAll()
            v1ToV2ModuleCache.clear()
        }

        @JvmStatic
        @Deprecated("Use ModuleResolver.getFromCache(modulePath)?.parsedModule instead")
        fun getTypedModule(modulePath: String): com.mezze.language.parsedast.ParsedModule? {
            return ModuleResolver.getFromCache(modulePath)?.parsedModule
        }

        @JvmStatic
        fun getAllCachedModules(): Map<String, Any> = emptyMap()

        @JvmStatic
        fun restoreModuleCache(modules: Map<String, Any>) { }

        @JvmStatic
        fun preloadModules(modules: Map<String, com.mezze.language.parsedast.ParsedModule>) {
            val resolver = ModuleResolver.create()
            for ((modulePath, parsedModule) in modules) {
                resolver.updateModuleExportsV2(modulePath, parsedModule)
                val typedModule = com.mezze.language.typechecker.module.TypedModule(
                    modulePath = modulePath,
                    exports = emptyMap(),
                    context = TypingContext.empty,
                    substitution = com.mezze.language.ast.solving.Substitution()
                )
                com.mezze.language.typechecker.module.ModuleTypeChecker.preloadIntoGlobalCache(modulePath, typedModule)
            }
        }
    }
}

/**
 * Format a type check result for display.
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
                val formattedError = ErrorFormatter.format(error, sourceCode, colorize)
                appendLine(formattedError)
            }
        }
    }
}
