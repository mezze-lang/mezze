package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.cache.TypeCheckCache
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintGenerator
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.ConstraintDumper
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.debug.InvariantChecker
import com.mezze.language.typechecker.desugar.DoBindDesugar
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.module.*
import com.mezze.language.typechecker.phase.*
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.solving.clearUnificationCache
import com.mezze.language.typechecker.solving.withInScopeAliasNames
import com.mezze.language.typechecker.validation.TypeValidator
import com.mezze.language.typechecker.validation.ValidationResult
import com.mezze.language.typechecker.validation.DeadCodeLinter
import com.mezze.language.typechecker.validation.UnusedImportLinter
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule as TypedAstModule
import java.nio.file.Path
import java.nio.file.Paths
import com.mezze.language.typechecker.TypeCheckResult as OutputTypeCheckResult
import com.mezze.language.typechecker.TypeCheckWarning as OutputTypeCheckWarning

class TypeChecker(
    internal val parentContext: TypingContext = createInitialContext()
) {
    internal fun loadPreludeModulesPublic(importResolver: ImportResolver): ModuleImportContext {
        return loadPreludeModulesInternal(importResolver, ModuleImportContext.empty())
    }

    internal fun getCachedPreludeLoadedModules(): Map<ModulePath, TypedModule>? {
        return cachedPreludeLoadedModules
    }

    /**
     * #160: an InherentMethodResolve whose receiver never became concrete can't
     * dispatch — there's no impl to pick. We consider it dispatchable if the
     * receiver resolves via EITHER the flat substitution OR the per-call-site
     * callReturnTypes (what emit dispatches on; it pins forward-ref / effect-
     * context receivers the flat subst can miss). Only when BOTH leave it a bare
     * tyvar is it genuinely undispatchable (e.g. `{a} -> (id {v=a}).abs`) → T0206.
     * A receiver that resolved to a NON-candidate type is already reported by the
     * solver, so we don't duplicate that here.
     */
    private fun undispatchableInherentMethodErrors(
        unresolved: List<com.mezze.language.typechecker.constraints.Constraint.InherentMethodResolve>,
        substitution: Substitution,
        callReturnTypes: Map<Loc, MezzeType>,
    ): List<TypeCheckError> = unresolved.mapNotNull { c ->
        val fromSubst = substitution.apply(c.receiverType)
        val fromCallSite = c.receiverLoc?.let { callReturnTypes[it] }?.let { substitution.apply(it) }
        val resolved = when {
            fromSubst !is MezzeType.VarType -> fromSubst
            fromCallSite != null && fromCallSite !is MezzeType.VarType -> fromCallSite
            else -> fromSubst
        }
        if (resolved is MezzeType.VarType) {
            TypeCheckError(
                phase = "Constraint Solving",
                message = "Cannot resolve inherent method '${c.methodName}': the receiver's " +
                    "type could not be determined. Annotate the receiver or call the method " +
                    "on a value of a known type.",
                loc = c.loc,
                code = ErrorCode.T0206_METHOD_NOT_FOUND,
            )
        } else null
    }

    /**
     * #160: when inherent-method dispatch can't resolve, the per-binding solve
     * (which fully instantiates the receiver) and the global solve (where the
     * receiver may stay a polymorphic-scheme tyvar) can each emit a T0206 at the
     * same site — one naming the concrete type ("no `abs` for type `Bool`") and
     * one generic ("could not be determined"). They're the same error; keep the
     * most informative (the concrete-type one) per (loc). No-op unless two T0206s
     * share a loc.
     */
    private fun collapseInherentMethodErrors(errors: List<TypeCheckError>): List<TypeCheckError> {
        val t0206ByLoc = errors
            .withIndex()
            .filter { it.value.code == ErrorCode.T0206_METHOD_NOT_FOUND && it.value.loc != null }
            .groupBy { it.value.loc }
        if (t0206ByLoc.none { it.value.size > 1 }) return errors
        // For each loc with duplicates, keep the "most specific" message — the one
        // that names a concrete type (heuristic: does NOT contain "could not be
        // determined"); fall back to the longest message.
        val dropIndices = mutableSetOf<Int>()
        for ((_, group) in t0206ByLoc) {
            if (group.size <= 1) continue
            // Prefer the concrete-type message (NOT "could not be determined");
            // tie-break by the longer message.
            val keep = group.sortedWith(
                compareBy<IndexedValue<TypeCheckError>> {
                    if (it.value.message.contains("could not be determined")) 1 else 0
                }.thenByDescending { it.value.message.length }
            ).first()
            group.forEach { if (it.index != keep.index) dropIndices.add(it.index) }
        }
        return errors.filterIndexed { i, _ -> i !in dropIndices }
    }

        sealed class TypeCheckResult {
                data class Success(
            val substitution: Substitution,
            val importContext: ModuleImportContext? = null,
            val warnings: List<TypeCheckWarning> = emptyList(),
            val loadedModules: Map<ModulePath, TypedModule> = emptyMap(),
            val abilityImpls: Map<Hash, TypingContext.AbilityImplInfo> = emptyMap(),
            val typedModule: TypedAstModule? = null,
            val loweredFunctions: List<FunctionIR>? = null,
            val loweredPerformers: List<Array<String>> = emptyList(),
            val typeContext: Map<String, MezzeType> = emptyMap(),
            val resolvedParsedModule: ParsedModule? = null,
            val expectedTypeAt: Map<Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()

                data class Failure(
            val errors: List<TypeCheckError>,
            val typedModule: TypedAstModule? = null,
            val expectedTypeAt: Map<Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()
    }

        data class TypeCheckError(
        val phase: String,
        val message: String,
        val loc: Loc? = null,
        val errorType: ErrorType = ErrorType.GENERAL,
        val metadata: Map<String, Any> = emptyMap(),
        val code: ErrorCode =
            ErrorCode.UNCATEGORIZED,
        val hints: List<String> = emptyList(),
        val fixHints: List<FixHint> = emptyList(),
    )

        data class TypeCheckWarning(
        val message: String,
        val loc: Loc? = null,
        val code: com.mezze.language.parsedast.diagnostics.WarningCode? = null
    )


        fun checkParsedModule(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        currentModulePath: String? = null,
        enablePrelude: Boolean = false
    ): TypeCheckResult {
        @Suppress("NAME_SHADOWING")
        var module = desugarPipesInParsedModule(module)

        val bindDesugarResult = DoBindDesugar.desugar(module)
        module = bindDesugarResult.module
        val bindLetLocs = bindDesugarResult.bindLetLocs
        val moduleUsesBind = bindLetLocs.isNotEmpty()

        clearUnificationCache()
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        var importContext: ModuleImportContext? = null
        var loadedModules: Map<ModulePath, TypedModule> = emptyMap()
        val shouldInjectPrelude = !ModulePathConventions.isStdlib(currentModulePath)
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
                        .filterIsInstance<ParsedTypeDecl>()
                        .map { it.name }
                        .toSet()
                    val builtinNames = BuiltinTypes.allBuiltinTypeNames
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

        val abilityNames = buildResult.registry.abilityNames +
            (importContext?.importedAbilities?.keys ?: emptySet())
        val effectNames = buildResult.registry.effectNames +
            (importContext?.importedEffects?.keys ?: emptySet())

        DebugLogger.debugSection(Category.TypeChecker, "Abilities and Effects") {
            listOf("abilityNames = $abilityNames", "effectNames = $effectNames")
        }

        val registry = buildFullRegistry(loadedModules, buildResult, importContext)

        val constraintGenerator = ConstraintGenerator.create(
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            performerAddedEffects = buildResult.performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
        )

        val inScopeAliasNames = buildResult.registry.typeNames +
            (importContext?.explicitlyImportedTypeNames ?: emptySet()) +
            BuiltinTypes.allBuiltinTypeNames
        val programConstraintResult = withInScopeAliasNames(inScopeAliasNames) {
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

        val undefinedVarErrors = checkUndefinedVariables(
            constraintResults = constraintResults,
            context = context,
            registry = registry,
            implDeclarations = buildResult.implDeclarations,
            loadedModules = loadedModules,
            importContext = importContext,
        )
        collectedErrors.addAll(undefinedVarErrors)

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

        val constraintWarnings = collectConstraintWarnings(constraintResults)
        val allConstraints = collectGlobalConstraints(constraintResults)

        ConstraintDumper.dumpGeneratedConstraints(constraintResults)
        ConstraintDumper.dumpSolverInput(allConstraints)

        val solver = ConstraintSolver(context, registry)
        val solveResult = withInScopeAliasNames(inScopeAliasNames) {
            solver.solve(allConstraints)
        }

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                collectedErrors.addAll(solveResult.errors.map { classifyConstraintError(it) })
                val seen = mutableSetOf<Pair<Loc?, String>>()
                val deduped = collapseInherentMethodErrors(collectedErrors)
                    .filter { e -> seen.add(e.loc to e.message) }
                val partialTyped = buildPartialTypedModule(
                    parsedModule = parsedModule, substitution = perBindingSubstitution,
                    constraintResults = constraintResults, context = context,
                    buildResult = buildResult, importContext = importContext,
                    registry = registry, bindLetLocs = bindLetLocs,
                )
                val partialExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<Loc, MezzeType>()) { acc, r ->
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
                InvariantChecker.checkSubstitution(substitution, "post-solve")
                ConstraintDumper.dumpSolverResult(substitution)


                val warnings = solveResult.warnings.map { msg ->
                    TypeCheckWarning(message = msg, loc = null)
                }.toMutableList()
                warnings.addAll(constraintWarnings)
                // #84 linter: purely-syntactic dead/redundant code warnings
                // (discarded value, constant condition, redundant boolean).
                warnings.addAll(DeadCodeLinter.lint(parsedModule).map {
                    TypeCheckWarning(message = it.message, loc = it.loc, code = it.code)
                })
                // #84 linter: unused imports (never-referenced `use` names).
                warnings.addAll(UnusedImportLinter.lint(parsedModule).map {
                    TypeCheckWarning(message = it.message, loc = it.loc, code = it.code)
                })

                val mustBeOpenRowVars = solveResult.mustBeOpenRowVars + perBindingMustBeOpenRowVars

                val tempGenerator = ConstraintGenerator.create(DeclarationRegistry.from(buildResult.registry.typeDeclarations, emptyMap(), emptyMap()))
                val globallyResolvedConstraintResults = constraintResults.mapValues { (_, result) ->
                    val resolvedType = substitution.apply(result.type)
                    val typeWithAbilities = tempGenerator.addAbilityConstraintsToFunctionType(resolvedType, result.abilityConstraints, substitution)
                    result.copy(type = typeWithAbilities)
                }

                InvariantChecker.checkConstraintResultsConsistent(globallyResolvedConstraintResults, "post-resolve")

                val aggregated = aggregateConstraintResults(constraintResults, substitution)

                // #160: inherent-method dispatch the solver left unresolved. A
                // receiver still not concrete — checked BOTH via the flat
                // substitution AND the per-call-site callReturnTypes (which emit
                // dispatches on; it can pin forward-ref / effect-context receivers
                // the flat subst misses) — is genuinely undispatchable (no runtime
                // impl), e.g. `{a} -> (id {v=a}).abs`. Report it as T0206.
                collectedErrors.addAll(
                    undispatchableInherentMethodErrors(
                        solveResult.unresolvedInherentMethods, substitution, aggregated.callReturnTypes
                    )
                )

                val validator = TypeValidator(context, registry, buildResult.implDeclarations)
                val constraintValidation = validator.validateConstraints(allConstraints, substitution)
                collectValidationResult(constraintValidation, collectedErrors, warnings)

                val isStdlibModule = ModulePathConventions.isStdlib(currentModulePath)
                val emitResult = emitTypedModuleAndLower(
                    parsedModule = parsedModule, module = module,
                    substitution = substitution,
                    constraintResults = globallyResolvedConstraintResults,
                    context = context, buildResult = buildResult,
                    importContext = importContext, registry = registry,
                    solver = solver, loadedModules = loadedModules,
                    tailCallLocations = aggregated.tailCallLocations,
                    mustBeOpenRowVars = mustBeOpenRowVars,
                    methodCallResolutions = aggregated.methodCallResolutions,
                    callReturnTypes = aggregated.callReturnTypes,
                    matchCaseBindings = aggregated.matchCaseBindings,
                    bindLetLocs = bindLetLocs,
                    isStdlibModule = isStdlibModule,
                    currentModulePath = currentModulePath,
                )

                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution)
                val v2Warnings = mutableListOf<TypeCheckWarning>()
                collectValidationResult(v2ValidationResult, collectedErrors, v2Warnings)

                val typeContext = globallyResolvedConstraintResults.mapValues { (_, cr) ->
                    substitution.apply(cr.type)
                }

                if (collectedErrors.isNotEmpty()) {
                    val seen = mutableSetOf<Pair<Loc?, String>>()
                    val deduped = collapseInherentMethodErrors(collectedErrors)
                        .filter { e -> seen.add(e.loc to e.message) }
                    return TypeCheckResult.Failure(
                        errors = deduped, typedModule = emitResult.typedModule,
                        expectedTypeAt = aggregated.expectedTypeAt,
                    )
                }
                return TypeCheckResult.Success(
                    substitution = substitution, importContext = importContext,
                    warnings = warnings + v2Warnings,
                    loadedModules = loadedModules,
                    abilityImpls = context.getAllAbilityImpls(),
                    typedModule = emitResult.typedModule,
                    loweredFunctions = emitResult.loweredFunctions,
                    loweredPerformers = emitResult.loweredPerformers,
                    typeContext = typeContext,
                    resolvedParsedModule = parsedModule,
                    expectedTypeAt = aggregated.expectedTypeAt,
                )
            }
        }
    }

    private class AggregatedConstraintResults(
        val tailCallLocations: Set<Loc>,
        val methodCallResolutions: Map<Pair<Loc, String>, ConstraintResult.MethodCallInfo>,
        val callReturnTypes: Map<Loc, MezzeType>,
        val matchCaseBindings: Map<Loc, Map<String, MezzeType>>,
        val expectedTypeAt: Map<Loc, MezzeType>,
    )

    private fun aggregateConstraintResults(
        constraintResults: Map<String, ConstraintResult>,
        substitution: Substitution,
    ): AggregatedConstraintResults {
        val tailCallLocations = constraintResults.values.flatMap { it.tailCalls }.filterNotNull().toSet()
        val methodCallResolutions = constraintResults.values
            .fold(emptyMap<Pair<Loc, String>, ConstraintResult.MethodCallInfo>()) { acc, r -> acc + r.methodCallResolutions }
        val callReturnTypes = constraintResults.values
            .fold(emptyMap<Loc, MezzeType>()) { acc, r -> acc + r.callReturnTypes }
        val matchCaseBindings = constraintResults.values
            .fold(emptyMap<Loc, Map<String, MezzeType>>()) { acc, r ->
                acc + r.matchCaseBindings.mapValues { (_, bindings) ->
                    bindings.mapValues { (_, ty) -> substitution.apply(ty) }
                }
            }
        val expectedTypeAt = constraintResults.values
            .fold(emptyMap<Loc, MezzeType>()) { acc, r -> acc + r.expectedTypeAt }
            .mapValues { (_, type) -> substitution.apply(type) }
        return AggregatedConstraintResults(
            tailCallLocations = tailCallLocations,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            matchCaseBindings = matchCaseBindings,
            expectedTypeAt = expectedTypeAt,
        )
    }

    private fun buildFullRegistry(
        loadedModules: Map<ModulePath, TypedModule>,
        buildResult: ContextBuilder.BuildResult,
        importContext: ModuleImportContext?,
    ): DeclarationRegistry {
        val transitiveEffects = mutableMapOf<String, ParsedEffectDecl>()
        for ((_, mod) in loadedModules) {
            mod.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        val allEffectDeclarations = transitiveEffects +
            buildResult.registry.effectDeclarations +
            (importContext?.importedEffects ?: emptyMap())

        // Types reachable BY NAME stay import-scoped (local + imported), so a
        // user-written type reference still requires its import and name
        // collisions across modules don't leak. But types from every loaded /
        // cached module are also gathered for the registry's HASH map only
        // (#91): an ability-op's declared param type (e.g. `config: Config`
        // from another module) flows into an impl body as a hash-tagged
        // TypeApplication, and expandTypeApplication resolves it by hash there
        // — so the impl doesn't need `Config` imported by name just to check a
        // field access on an un-annotated param.
        // Restricted to modules actually loaded for THIS typecheck (not the
        // shared getAllCachedModules() set), so unrelated modules can't leak in
        // and shadow a same-named type/ability across independent typechecks.
        val transitiveTypesByHash = mutableMapOf<String, ParsedTypeDecl>()
        for ((_, mod) in loadedModules) {
            mod.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach {
                transitiveTypesByHash[it.name] = it
            }
        }
        val allTypeDeclarations = buildResult.registry.typeDeclarations +
            (importContext?.importedTypes ?: emptyMap())

        val importedAbilities = importContext?.importedAbilities ?: emptyMap()
        val transitiveAbilities = mutableMapOf<String, ParsedAbilityDecl>()
        for ((_, mod) in loadedModules) {
            mod.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        val allAbilityDeclarations = transitiveAbilities + buildResult.registry.abilityDeclarations + importedAbilities

        return DeclarationRegistry.from(
            allTypeDeclarations, allAbilityDeclarations, allEffectDeclarations,
            hashOnlyTypeDecls = transitiveTypesByHash.values,
        )
    }

    private fun collectConstraintWarnings(
        constraintResults: Map<String, ConstraintResult>,
    ): List<TypeCheckWarning> {
        val unusedVarWarnings = constraintResults.values.flatMap { it.unusedVariables }.map { unused ->
            TypeCheckWarning(
                message = "Unused variable '${unused.name}'. Prefix with '_' to suppress this warning.",
                loc = unused.loc,
                code = com.mezze.language.parsedast.diagnostics.WarningCode.W0101_UNUSED_VARIABLE
            )
        }
        val patternWarnings = constraintResults.values.flatMap { it.patternWarnings }.map { warningMsg ->
            TypeCheckWarning(message = warningMsg, loc = null)
        }
        return unusedVarWarnings + patternWarnings
    }

    private fun collectGlobalConstraints(
        constraintResults: Map<String, ConstraintResult>,
    ): List<Constraint> {
        return constraintResults.values.flatMap { it.constraints }
            .filter { constraint ->
                constraint !is Constraint.HasConstructor && constraint !is Constraint.HasField
            } + constraintResults.values.flatMap { it.abilityConstraints } +
              constraintResults.values.flatMap { it.associatedTypeConstraints }
    }

    private fun collectValidationResult(
        result: ValidationResult,
        errors: MutableList<TypeCheckError>,
        warnings: MutableList<TypeCheckWarning>,
    ) {
        when (result) {
            is ValidationResult.Failure -> {
                errors.addAll(result.errors.map { error ->
                    TypeCheckError(
                        phase = "Validation", message = error.message(), loc = error.location(),
                        code = error.code, hints = error.hints, fixHints = error.fixHints,
                    )
                })
            }
            is ValidationResult.Success -> {
                result.warnings.forEach { msg ->
                    warnings.add(TypeCheckWarning(message = msg, loc = null))
                }
            }
        }
    }

    private fun hasImportsParsed(module: ParsedModule): Boolean {
        return module.decls.any { decl ->
            decl is ParsedUseDecl || decl is ParsedPubUseDecl
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

    private var cachedTypeAliases: Map<String, TypeAliasInfo> = emptyMap()

    fun getTypeAliases(): Map<String, TypeAliasInfo> {
        return cachedTypeAliases
    }

        @JvmOverloads
    fun typeCheck(module: ParsedModule, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): OutputTypeCheckResult {
        TypeVar.resetCounter()
        val result = checkParsedModule(module, currentPath, currentModulePath = modulePath, enablePrelude = enablePrelude)

        return when (result) {
            is TypeCheckResult.Success -> {
                val resolvedImports = buildResolvedImports(result.importContext)
                val structuredWarnings = result.warnings.map { warning ->
                    OutputTypeCheckWarning(warning.message, warning.loc, warning.code)
                }
                OutputTypeCheckResult(
                    resolvedImports = resolvedImports,
                    warnings = structuredWarnings,
                    importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                    importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                    importedImpls = result.importContext?.importedImpls ?: emptyMap(),
                    allEffects = result.importContext?.let { ic ->
                        ic.importedEffects + ic.effectsByHash.values.associateBy { it.name }
                    } ?: emptyMap(),
                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                    typedModule = result.typedModule,
                    performerRecords = result.loweredPerformers,
                    loadedModules = result.loadedModules.mapValues { (_, m) -> m.typedModule }.filterValues { it != null }.mapValues { (_, v) -> v!! },
                    loadedModuleSources = result.loadedModules
                        .mapNotNull { (path, m) ->
                            val fp = m.filePath ?: return@mapNotNull null
                            val sh = m.sourceHash ?: return@mapNotNull null
                            path to LoadedModuleSource(fp, sh)
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
        val module: TypedAstModule,
        val loadedModules: Map<ModulePath, TypedModule>
    )

    fun checkParsedModuleToTyped(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        enablePrelude: Boolean = false
    ): TypedAstModule {
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
        var cachedPreludeLoadedModules: Map<ModulePath, TypedModule>? = null

        private fun createInitialContext(): TypingContext {
            val crashType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
                MezzeType.VarType(TypeVar.fresh())
            )
            val freeVars = collectFreeTypeVars(crashType)
            val scheme = TypeScheme(
                quantifiedVars = freeVars.toList(),
                constraints = emptyList(),
                type = crashType
            )
            return TypingContext.empty.extendPoly(BindingKey.parse("crash"), scheme)
        }

        fun create(): TypeChecker = TypeChecker()
        fun withContext(context: TypingContext): TypeChecker = TypeChecker(context)

        @JvmStatic
        fun clearCache() {
            ModuleResolver.clearCache()
            TypeCheckCache.clearAll()
        }

        @JvmStatic
        @Deprecated("Use ModuleResolver.getFromCache(modulePath)?.parsedModule instead")
        fun getTypedModule(modulePath: String): ParsedModule? {
            return ModuleResolver.getFromCache(modulePath)?.parsedModule
        }
    }
}
