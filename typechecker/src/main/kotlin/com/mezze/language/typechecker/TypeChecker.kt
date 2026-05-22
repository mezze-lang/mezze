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
import com.mezze.language.parsedast.diagnostics.ErrorCode
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
 * - Input: ParsedModule with ParsedUseDecl
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
 * - Output: TypedModule -> FunctionIR (via TypedASTCompiler)
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
            val typedModule: com.mezze.language.typedast.TypedModule? = null,       // V2 TypedAST (primary output)
            val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>? = null,  // TruffleIR functions (after mono+resolve+lower)
            val loweredPerformers: List<Array<String>> = emptyList(),  // Performer record info: [hash, effectHash, op1Hash, op1Name, ...]
            /** Extracted type context: binding name -> resolved MezzeType. Built from constraint results + substitution. */
            val typeContext: Map<String, MezzeType> = emptyMap(),
            /** The resolved ParsedModule (after ContextBuilder resolves performer->ability impls). */
            val resolvedParsedModule: com.mezze.language.parsedast.ParsedModule? = null,
            /**
             * Per-source-position "expected type" hint after substitution.
             * Populated by the constraint generator at every emission site
             * with a Loc (annotations, fields, function args). LSP ranker
             * uses this for type-aware completion ranking — independent of
             * whether errors fired.
             */
            val expectedTypeAt: Map<com.mezze.language.parsedast.Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()

        /**
         * Type checking failed.
         *
         * `typedModule` carries the partial TypedAST when a recoverable error
         * doesn't prevent us from emitting one (constraint-solve errors in
         * one decl, validation failures, etc.). The LSP uses this for hover,
         * inlay hints, and go-to-def of *other* decls in the same file —
         * without it, a single error wipes out type info for the whole file.
         */
        data class Failure(
            val errors: List<TypeCheckError>,
            val typedModule: com.mezze.language.typedast.TypedModule? = null,
            /** See `Success.expectedTypeAt` — populated on partial-typed Failure too. */
            val expectedTypeAt: Map<com.mezze.language.parsedast.Loc, MezzeType> = emptyMap(),
        ) : TypeCheckResult()
    }

    /**
     * Type checking error.
     *
     * Wraps errors from different phases. Carries the same rich info as the
     * thrown [com.mezze.language.typechecker.TypeCheckError] exception so
     * callers using the result-style API (CLI, LSP) get full diagnostic
     * fidelity — not just message+loc. See #114 / recovery work.
     */
    data class TypeCheckError(
        val phase: String,
        val message: String,
        val loc: com.mezze.language.parsedast.Loc? = null,
        val errorType: ErrorType = ErrorType.GENERAL,
        val metadata: Map<String, Any> = emptyMap(),
        /**
         * Stable error code — see [com.mezze.language.parsedast.diagnostics.ErrorCode].
         * Defaults to `UNCATEGORIZED` during the Phase B migration; sites
         * that have been migrated pass a real code so the formatter can
         * surface it as `error[T0210]: …`.
         */
        val code: com.mezze.language.parsedast.diagnostics.ErrorCode =
            com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED,
        /**
         * Zero or more "what to do next" hints. Rendered as `= hint: …`
         * lines under the source snippet.
         */
        val hints: List<String> = emptyList(),
        /**
         * Structured fix suggestions consumed by the LSP code-action
         * handler. Empty list = no automated fix offered. See
         * [com.mezze.language.parsedast.diagnostics.FixHint].
         */
        val fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> = emptyList(),
    )

    /**
     * Type checking warning.
     *
     * Warnings are non-fatal issues detected during type checking.
     */
    data class TypeCheckWarning(
        val message: String,
        val loc: com.mezze.language.parsedast.Loc? = null
    )


    /**
     * Check a program through all 7 phases.
     *
     * This is the main entry point!
     *
     * @param program The program to type-check
     * @param currentPath Optional path for module resolution (defaults to current directory)
     */
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
        var module = desugarPipesInParsedModule(module)

        // Desugar do-block <- statements (do-bind feature). See DoBindDesugar.kt.
        // Each `pat <- expr` becomes `let pat = expr.try {}`, the do-block's
        // result is wrapped in `Ok {val = ...}`, and the entire block is
        // wrapped in a synthetic `perform ExceptionToResult in do { ... }`.
        // From that point on, the typechecker handles it exactly like a
        // user-written `perform ExceptionToResult` — same effect discharge,
        // same `<*>` marker. The only "magic" left is name resolution: the
        // surgical injection below adds Exception + ExceptionToResult to the
        // import context whenever the module uses `<-`, so the synthetic
        // perform's handler name resolves.
        val bindDesugarResult = com.mezze.language.typechecker.desugar.DoBindDesugar.desugar(module)
        module = bindDesugarResult.module
        val bindLetLocs = bindDesugarResult.bindLetLocs
        val moduleUsesBind = bindLetLocs.isNotEmpty()

        // Clear caches for fresh type checking
        com.mezze.language.typechecker.solving.clearUnificationCache()

        // Initialize TypeCheckCache in memory mode if not already initialized with SQLite
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        // PHASE 0: Module Resolution (if imports are present)
        var importContext: ModuleImportContext? = null
        var loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule> = emptyMap()
        val shouldInjectPrelude = !com.mezze.language.ast.ModulePathConventions.isStdlib(currentModulePath)
        val hasUserImports = hasImportsParsed(module)

        // Try to use PrecompiledStdlib for fast prelude injection
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { null }

        val enhancedContext = if (!hasUserImports && shouldInjectPrelude && precompiled != null && !moduleUsesBind) {
            // FAST PATH: no user imports, precompiled prelude available, no `<-`.
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

                    if (shouldInjectPrelude) {
                        val preludeContext = loadPreludeModules(importResolver, importContext!!)
                        importContext = importContext!!.merge(preludeContext)
                    }
                    if (moduleUsesBind) {
                        importContext = injectExceptionToResultForBind(importContext!!, importResolver)
                    }
                    mergeImportContext(parentContext, importContext!!)
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

        // Include effects from ALL loaded modules (not just directly imported ones).
        // User code may reference effects indirectly via performers (e.g., perform MutPerformer
        // references the Mut effect, even if the user only imported MutPerformer).
        val transitiveEffects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        for ((_, module) in loadedModules) {
            module.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedEffectDecl>()?.forEach {
                transitiveEffects[it.name] = it
            }
        }
        val allEffectDeclarations = transitiveEffects +
            buildResult.effectDeclarations +
            (importContext?.importedEffects ?: emptyMap())

        val allTypeDeclarations = buildResult.typeDeclarations +
            (importContext?.importedTypes ?: emptyMap())

        val importedAbilities = importContext?.importedAbilities ?: emptyMap()
        val transitiveAbilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        for ((_, module) in loadedModules) {
            module.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
            cached.parsedModule?.decls?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()?.forEach {
                transitiveAbilities[it.name] = it
            }
        }
        val allAbilityDeclarations = transitiveAbilities + buildResult.abilityDeclarations + importedAbilities

        val constraintGenerator = ConstraintGenerator.create(
            typeDeclarations = allTypeDeclarations,
            performerToEffect = buildResult.performerToEffect,
            performerAddedEffects = buildResult.performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            abilityDeclarations = allAbilityDeclarations,
            effectDeclarations = allEffectDeclarations,
        )
        val programConstraintResult = constraintGenerator.generateProgram(parsedModule, context)
        val constraintResults = programConstraintResult.constraintResults
        val perBindingMustBeOpenRowVars = programConstraintResult.perBindingMustBeOpenRowVars
        val perBindingSolveErrors = programConstraintResult.perBindingSolveErrors
        val perBindingSubstitution = programConstraintResult.perBindingSubstitution
        val collectedTypeCheckErrors = programConstraintResult.collectedTypeCheckErrors

        // Errors accumulated through this checker run. Any error mode that
        // doesn't make subsequent phases unsafe goes here and we keep going,
        // so we can still emit a TypedAST for the surviving decls. The LSP
        // (and other tools) use that partial TypedAST for hover, inlay,
        // and go-to-def — without it, one broken decl would wipe out type
        // info for the whole file. Final disposition: if non-empty at the
        // end, return Failure(collectedErrors, partialTypedModule).
        val collectedErrors = mutableListOf<TypeCheckError>()

        // Check for collected TypeCheckErrors (from recovery — preserves metadata)
        if (collectedTypeCheckErrors.isNotEmpty()) {
            // Always go through Failure, never throw — Failure.errors carries
            // errorType + metadata since #313, so result-API callers (CLI,
            // LSP) get full diagnostic fidelity. Throwing on the size==1 case
            // (the old behavior) defeated multi-error reporting whenever there
            // also happened to be exactly one collected exception.
            collectedErrors.addAll(collectedTypeCheckErrors.map { e ->
                TypeCheckError(
                    phase = "Type Checking",
                    message = e.message ?: "Type checking failed",
                    loc = e.loc,
                    errorType = e.errorType,
                    metadata = e.metadata,
                    code = e.code,
                    hints = e.hints,
                    fixHints = e.fixHints,
                )
            })
            // Don't return early — keep flowing so we can emit a partial
            // TypedAST for editor support. Later phases tolerate per-decl
            // failures (the constraint solver's per-binding pass already
            // recorded these errors for the affected decl only).
        }

        // Check for per-binding solve errors
        if (perBindingSolveErrors.isNotEmpty()) {
            collectedErrors.addAll(perBindingSolveErrors.map { error ->
                classifyConstraintError(error)
            })
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
            // `context` is the pre-program TypingContext — it doesn't yet
            // contain the peer top-level lets that the constraint generator
            // added on its way through. Without folding those names in,
            // "Did you mean…?" for a typo'd reference to a peer binding
            // would silently return nothing (the candidate pool was the
            // prelude only). Include the program's binding names so the
            // suggestion + structured FixHint.Rename for V0702 can offer
            // peer bindings as candidates.
            val peerBindingNames = constraintResults.keys.filterNot { it.startsWith("_") }
            val errors = undefinedVars.map { undefinedVar ->
                // Track PrivateItemResult across message building so the AddPub
                // FixHint at the bottom of the lambda can reference it.
                var privateItemForFix: PrivateItemResult? = null
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
                    privateItemForFix = checkIfPrivateQualifiedItem(undefinedVar, loadedModules, importContext)

                    if (privateItemForFix != null) {
                        privateItemForFix!!.message
                    } else {
                        val candidateNames = allVariables.keys.map { key -> key.toKeyString() } + peerBindingNames
                        val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                        // Prelude-stripped name hint: the prelude deliberately drops
                        // free-function bindings (option::fold vs seq::fold name
                        // collisions) and only keeps inherent impls. If the user
                        // wrote `fold {…}` bare, it's nearly always either a method
                        // call (`xs.fold {…}`) or a qualified import they forgot.
                        // Surface the inherent-impl carriers from the precompiled
                        // prelude so the message points at the fix.
                        val preludeMethodCarriers = findPreludeMethodCarriers(undefinedVar.name)
                        buildString {
                            append("Undefined variable or function: ${undefinedVar.name}")
                            if (similarNames.isNotEmpty()) {
                                append(". Did you mean: ${similarNames.joinToString()}")
                            }
                            if (preludeMethodCarriers.isNotEmpty()) {
                                val carriers = preludeMethodCarriers.take(3).joinToString(", ")
                                append(". '${undefinedVar.name}' is available as a method on $carriers")
                                append(" — try `<receiver>.${undefinedVar.name} {…}` or import the function explicitly")
                            }
                        }
                    }
                }
                // Emit structured FixHints so the LSP can offer one-click QuickFix:
                //  - AddPub when the user named a real-but-private decl in another module
                //  - Rename when the unqualified name is a typo of an in-scope binding
                val undefFixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> = buildList {
                    if (undefinedVar.loc != null && privateItemForFix != null) {
                        add(com.mezze.language.parsedast.diagnostics.FixHint.AddPub(
                            undefinedVar.loc!!,
                            privateItemForFix!!.declModulePath,
                            privateItemForFix!!.declName,
                        ))
                    }
                    if (undefinedVar.loc != null && privateItemForFix == null &&
                        !isBuiltInAbility && !isUserDefinedAbility && !isEffect &&
                        matchingAbilityOp == null && matchingEffectOp == null) {
                        val candidateNames = allVariables.keys.map { key -> key.toKeyString() } + peerBindingNames
                        val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                        if (similarNames.isNotEmpty()) {
                            add(com.mezze.language.parsedast.diagnostics.FixHint.Rename(
                                undefinedVar.loc!!, similarNames.take(3)
                            ))
                        }
                    }
                }
                TypeCheckError(
                    phase = "Validation",
                    message = message,
                    loc = undefinedVar.loc,
                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                    fixHints = undefFixHints,
                )
            }
            collectedErrors.addAll(errors)
            // Don't return — undefined-var errors are local to one binding's
            // constraint result; subsequent phases substitute a free TypeVar
            // and continue.
        }

        // Check for duplicate bindings BEFORE solving constraints
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

        // Collect unused variable warnings
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
        val solver = ConstraintSolver(context, allTypeDeclarations, allAbilityDeclarations)
        val solveResult = solver.solve(allConstraints)

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                // Global solve failed. Per-binding solving in
                // ConstraintGenerator already recorded a per-decl error
                // for the same root cause; the global pass typically
                // re-discovers it via ability/has-field constraints.
                //
                // Editor support: even though the solve failed, we still
                // try to emit a *partial* TypedModule using only the
                // per-binding substitution. Decls whose own constraints
                // solved cleanly get their real types; decls whose body
                // failed get `VarType("unknown")` placeholders. The LSP
                // (#149) uses this so hover/completion on UNRELATED
                // surviving decls keep working when ONE decl is broken.
                collectedErrors.addAll(solveResult.errors.map { error ->
                    classifyConstraintError(error)
                })
                val seen = mutableSetOf<Pair<com.mezze.language.parsedast.Loc?, String>>()
                val deduped = collectedErrors.filter { e -> seen.add(e.loc to e.message) }
                val partialTyped = buildPartialTypedModule(
                    parsedModule = parsedModule,
                    substitution = perBindingSubstitution,
                    constraintResults = constraintResults,
                    context = context,
                    buildResult = buildResult,
                    importContext = importContext,
                    allTypeDeclarations = allTypeDeclarations,
                    allAbilityDeclarations = allAbilityDeclarations,
                    allEffectDeclarations = allEffectDeclarations,
                    bindLetLocs = bindLetLocs,
                )
                // expectedTypeAt resolved through whatever substitution we
                // have. Even on global-solve failure, per-binding solves
                // already applied to the union so this gives the LSP usable
                // types on surviving bindings.
                val partialExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                        acc + r.expectedTypeAt
                    }
                    .mapValues { (_, type) -> perBindingSubstitution.apply(type) }
                return TypeCheckResult.Failure(
                    errors = deduped,
                    typedModule = partialTyped,
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
                    .fold(emptyMap<Pair<com.mezze.language.parsedast.Loc, String>, com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo>()) { acc, r ->
                        acc + r.methodCallResolutions
                    }

                val allCallReturnTypes = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                        acc + r.callReturnTypes
                    }

                // expectedTypeAt union — applied through the final
                // substitution so the LSP ranker sees concrete types,
                // not raw TypeVars from the constraint generator.
                val allExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                        acc + r.expectedTypeAt
                    }
                    .mapValues { (_, type) -> substitution.apply(type) }

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
                        // Validation errors are non-fatal for editor support
                        // — keep flowing so the LSP gets a TypedAST. Final
                        // disposition is decided by the bottom of this
                        // method via `collectedErrors`.
                        collectedErrors.addAll(constraintValidation.errors.map { error ->
                            TypeCheckError(
                                phase = "Validation",
                                message = error.message(),
                                loc = error.location(),
                                code = error.code,
                                hints = error.hints,
                                fixHints = error.fixHints,
                            )
                        })
                    }
                    is ValidationResult.Success -> {
                        constraintValidation.warnings.forEach { msg ->
                            warnings.add(TypeCheckWarning(message = msg, loc = null))
                        }
                    }
                }

                // V2 Pipeline: TypedAST -> Mono -> Resolve -> Lower -> TruffleIR
                val isStdlibModule = com.mezze.language.ast.ModulePathConventions.isStdlib(currentModulePath)
                var emittedTypedModule: com.mezze.language.typedast.TypedModule? = null
                var loweredFns: List<com.mezze.language.truffleir.FunctionIR>? = null
                var loweredPerformers: List<Array<String>> = emptyList()

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
                        methodCallResolutions = allMethodCallResolutions,
                        callReturnTypes = allCallReturnTypes,
                        bindLetLocs = bindLetLocs,
                    )
                    val typed = builder.build(parsedModule)

                    // Generate auto-derived codec impls (EncodeRepr/DecodeRepr) from constraint solver.
                    // The solver tracks which types need codec impls; CodecGenerator builds parsed ASTs;
                    // the builder converts them to TypedAbilityImpls that the compiler can emit as FunctionIRs.
                    val codecImpls = solver.getGeneratedCodecImpls()
                    if (codecImpls.isNotEmpty()) {
                        val codecGen = com.mezze.language.typechecker.codec.CodecGenerator()
                        val extraImpls = mutableListOf<com.mezze.language.typedast.TypedAbilityImpl>()
                        for (ci in codecImpls) {
                            try {
                                val formatType: com.mezze.language.ast.MezzeType
                                val dataType: com.mezze.language.ast.MezzeType
                                if (ci.targetTypes.size == 2) {
                                    // Two-parameter form: [formatType, dataType]
                                    formatType = ci.targetTypes[0]
                                    dataType = ci.targetTypes[1]
                                } else if (ci.targetTypes.size == 1) {
                                    // Single-parameter form (EncodeRepr/DecodeRepr method call):
                                    // Only dataType is known. Use a dummy format type —
                                    // the generated code references Format/DecodeFormat dynamically.
                                    dataType = ci.targetTypes[0]
                                    formatType = com.mezze.language.ast.MezzeType.RecordType(emptyMap())
                                } else {
                                    continue
                                }
                                // Pick encode-shaped vs decode-shaped impl body via
                                // hash, not name — rename-safe.
                                val abilityHash = WellKnownHashes.resolveHash(ci.abilityName)
                                val parsedImpl = when {
                                    WellKnownHashes.isEncodeFlavoredCodec(abilityHash) ->
                                        codecGen.generateEncodeImpl(formatType, dataType)
                                    WellKnownHashes.isDecodeFlavoredCodec(abilityHash) ->
                                        codecGen.generateDecodeImpl(formatType, dataType)
                                    else -> continue
                                }
                                builder.buildCodecImpl(parsedImpl, extraImpls)
                            } catch (_: Exception) {
                                // Codec generation errors are non-fatal; the impl will be missing at runtime.
                            }
                        }
                        if (extraImpls.isNotEmpty()) {
                            val updatedImpls = typed.abilityImpls + extraImpls
                            emittedTypedModule = typed.copy(abilityImpls = updatedImpls)
                        } else {
                            emittedTypedModule = typed
                        }
                    } else {
                        emittedTypedModule = typed
                    }
                } catch (e: Exception) {
                    if (System.getenv("MEZZE_DEBUG") != null) {
                        System.err.println("[BUILDER] ${e.javaClass.simpleName}: ${e.message}")
                        e.printStackTrace(System.err)
                    }
                    // TypedAstBuilder errors are non-fatal for type checking; the typed module
                    // will be null and the pipeline will skip lowering.
                }

                // #311: anonymous variant constructors must have a single shape per module.
                // Stdlib modules use polymorphic constructors (Some, None, Ok, Err) that we
                // intentionally let through — they're declared via `type` decls there.
                if (!isStdlibModule && emittedTypedModule != null) {
                    val declaredCtors = mutableSetOf<String>()
                    // Built-in variant constructors (Option, Result, Bool, List) —
                    // these types are defined internally, not via .mz type decls, so
                    // they wouldn't otherwise show up in any of our scans.
                    declaredCtors.addAll(com.mezze.language.ast.BuiltinTypes.coreVariantConstructors)
                    fun collectFromTypeDecl(decl: com.mezze.language.parsedast.ParsedTypeDecl) {
                        when (val def = decl.definition) {
                            is com.mezze.language.parsedast.ParsedVariantDef ->
                                def.constructors.forEach { declaredCtors.add(it.name) }
                            is com.mezze.language.parsedast.ParsedNewtypeDef ->
                                declaredCtors.add(def.constructor)
                            else -> {}
                        }
                    }
                    for (decl in module.decls) {
                        if (decl is com.mezze.language.parsedast.ParsedTypeDecl) collectFromTypeDecl(decl)
                    }
                    importContext?.importedTypes?.values?.forEach { collectFromTypeDecl(it) }
                    // Prelude type declarations (Option, Result, …) live in stdlibTypedModules.
                    for ((_, stdMod) in PrecompiledStdlib.stdlibTypedModules) {
                        for (d in stdMod.decls) {
                            if (d is com.mezze.language.typedast.TypedTypeDecl) {
                                when (val def = d.definition) {
                                    is com.mezze.language.typedast.TypedVariantDef ->
                                        def.constructors.keys.forEach { declaredCtors.add(it) }
                                    is com.mezze.language.typedast.TypedNewtypeDef ->
                                        declaredCtors.add(def.constructor)
                                    else -> {}
                                }
                            }
                        }
                    }
                    com.mezze.language.typechecker.validation.AnonymousConstructorShapeValidator
                        .validate(emittedTypedModule!!, declaredCtors)
                }

                // Phase 2: Mono -> Resolve -> Lower (entry module only)
                if (!isStdlibModule && emittedTypedModule != null) try {
                    val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(emittedTypedModule!!)

                    // Collect all ability impls from all visible modules
                    val allAbilityImpls = mutableListOf<com.mezze.language.typedast.TypedAbilityImpl>()
                    allAbilityImpls.addAll(wrapped.abilityImpls)
                    allAbilityImpls.addAll(PrecompiledStdlib.stdlibAbilityImpls)
                    for ((_, v1Module) in loadedModules) {
                        val stdlibModule = v1Module.typedModule ?: continue
                        allAbilityImpls.addAll(stdlibModule.abilityImpls)
                    }
                    for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
                        val stdlibModule = cached.typedModule ?: continue
                        allAbilityImpls.addAll(stdlibModule.abilityImpls)
                    }

                    // Build decl index for ByHash resolution
                    val declIndex = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedLetDecl>()
                    for (d in wrapped.decls) {
                        if (d is com.mezze.language.typedast.TypedLetDecl) declIndex[d.hash] = d
                    }

                    // Collect ALL prelude decl hashes to skip during compilation
                    val preludeHashes = mutableSetOf<com.mezze.language.typedast.Hash>()
                    preludeHashes.addAll(PrecompiledStdlib.preloweredFunctions.keys)
                    preludeHashes.addAll(PrecompiledStdlib.stdlibDeclHashes)

                    // Collect imported modules and performers (non-prelude only)
                    val preludeModuleHashes = PrecompiledStdlib.stdlibModuleHashes
                    val importedModules = mutableListOf<com.mezze.language.typedast.TypedModule>()
                    val importedPerformers = mutableListOf<com.mezze.language.typedast.TypedPerformerDecl>()
                    val seenHashes = mutableSetOf<com.mezze.language.typedast.Hash>()
                    for ((_, v1Module) in loadedModules) {
                        val stdlibModule = v1Module.typedModule ?: continue
                        if (stdlibModule.hash in preludeModuleHashes) continue  // skip prelude
                        if (stdlibModule.hash in seenHashes) continue
                        seenHashes.add(stdlibModule.hash)
                        importedModules.add(stdlibModule)
                        importedPerformers.addAll(stdlibModule.performerDecls)
                    }
                    for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
                        val stdlibModule = cached.typedModule ?: continue
                        if (stdlibModule.hash in preludeModuleHashes) continue  // skip prelude
                        if (stdlibModule.hash in seenHashes) continue
                        seenHashes.add(stdlibModule.hash)
                        importedModules.add(stdlibModule)
                        importedPerformers.addAll(stdlibModule.performerDecls)
                    }

                    // Mono Phase 1 hybrid: opt-in via -Dmezze.mono=1 or
                    // MEZZE_MONO=1. Off by default — the dict-passing path
                    // is the canonical compilation strategy. When on, the
                    // monomorphizer runs over concrete decls' bodies and
                    // produces specializations + per-call-site rewrites.
                    //
                    // The driver also gets `PrecompiledStdlib.stdlibTypedModules`
                    // as resolution-only context: prelude generics live
                    // there (lowered as FunctionIR blobs, but the
                    // TypedLetDecl source is preserved). Calls into the
                    // prelude are exactly where mono pays off most.
                    val monoResult = if (com.mezze.language.typechecker.mono.MonoDriver.enabled) {
                        val stdlibModulesForMono = PrecompiledStdlib.stdlibTypedModules.values.toList()
                        com.mezze.language.typechecker.mono.MonoDriver.run(
                            entry = wrapped,
                            imported = importedModules,
                            resolutionOnly = stdlibModulesForMono
                        )
                    } else null

                    // Compile with TypedASTCompiler — single pass, no Monomorphizer/Resolver/Lowerer
                    val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
                        abilityImpls = allAbilityImpls,
                        stdlibHashes = preludeHashes,
                        monoCallSiteRewrites = monoResult?.bySiteLoc ?: emptyMap()
                    )
                    compiler.setDeclIndex(declIndex)

                    val result = compiler.compile(
                        module = wrapped,
                        additionalModules = importedModules,
                        additionalPerformers = importedPerformers,
                        monoSpecializations = monoResult?.specializations?.values?.toList() ?: emptyList()
                    )

                    loweredFns = result.functions
                    loweredPerformers = result.performerRecords.map { pr ->
                        val arr = mutableListOf(pr.performerHash, pr.effectHash)
                        for (i in pr.operationHashes.indices) {
                            arr.add(pr.operationHashes[i])
                            arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                        }
                        arr.toTypedArray()
                    }
                } catch (e: Exception) {
                    // TypedASTCompiler errors are non-fatal for type checking.
                    if (System.getenv("MEZZE_COMPILER_DBG") != null) {
                        System.err.println("[COMPILER-DBG] ${e.javaClass.simpleName}: ${e.message}")
                        e.printStackTrace(System.err)
                    }
                }

                // V2 validation skipped — TypedASTCompiler doesn't produce MonoAST

                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution)
                if (v2ValidationResult is ValidationResult.Failure) {
                    collectedErrors.addAll(v2ValidationResult.errors.map { error ->
                        TypeCheckError(
                            phase = "Validation",
                            message = error.message(),
                            loc = error.location(),
                            code = error.code,
                            hints = error.hints,
                            fixHints = error.fixHints,
                        )
                    })
                }

                // Build typeContext from constraint results + substitution
                val typeContext = globallyResolvedConstraintResults.mapValues { (_, cr) ->
                    substitution.apply(cr.type)
                }

                val validationWarnings = if (v2ValidationResult is ValidationResult.Success) {
                    v2ValidationResult.warnings.map { TypeCheckWarning(message = it, loc = null) }
                } else emptyList()

                // Final disposition. If we accumulated any non-fatal errors
                // along the way (constraint solve, validation, undefined
                // vars), return Failure but include the partial typedModule
                // so editor tooling can still hover/inlay/cmd-click into the
                // surviving decls. Otherwise return Success.
                if (collectedErrors.isNotEmpty()) {
                    // Dedupe — per-binding solve, global solve, and global
                    // validation can each surface the same error.
                    val seen = mutableSetOf<Pair<com.mezze.language.parsedast.Loc?, String>>()
                    val deduped = collectedErrors.filter { e ->
                        seen.add(e.loc to e.message)
                    }
                    return TypeCheckResult.Failure(
                        errors = deduped,
                        typedModule = emittedTypedModule,
                        expectedTypeAt = allExpectedTypeAt,
                    )
                }
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
                    resolvedParsedModule = parsedModule,
                    expectedTypeAt = allExpectedTypeAt,
                )
            }
        }
    }

    /**
     * Emit a best-effort partial TypedModule when global constraint solving
     * failed but per-binding solving had already resolved most decls.
     *
     * Decls whose own constraints solved cleanly (recorded in
     * `perBindingSubstitution` / `constraintResults`) get their real types.
     * Decls whose body failed end up with `VarType("unknown")` placeholders
     * inside the resulting TypedAST — `TypedAstBuilder.build` handles this
     * gracefully and emits a TypedLetDecl regardless. The LSP uses the
     * partial module for hover/completion on unrelated surviving decls
     * (see #149).
     *
     * Wrapped in a single try/catch — if the partial pass itself blows up
     * (e.g. an unrelated invariant the builder enforces), we just return
     * null and the caller falls back to the LSP's last-successful cache.
     */
    /**
     * Convert a raw solver [ConstraintError] into a [TypeCheckError] with
     * stable error code + hints when we can classify it. This is the
     * Phase A2 entry point for the new diagnostic shape — Phase B will
     * grow the match arms until every error has a real code.
     *
     * Sites NOT yet classified fall through with `code = UNCATEGORIZED`,
     * so output stays identical to before until each kind is migrated.
     */
    private fun classifyConstraintError(
        error: com.mezze.language.typechecker.solving.ConstraintSolver.ConstraintError
    ): TypeCheckError {
        // Dispatch on the constraint's structured ErrorContext, NOT message
        // prefixes. ErrorContext is the discriminator the solver itself uses
        // to pick its message wording in `generateBetterErrorMessage`; reusing
        // it here keeps the code-classification in lockstep — rewording a
        // message doesn't accidentally break code/hint assignment.
        val constraint = error.constraint
        val ctx = (constraint as? com.mezze.language.typechecker.constraints.Constraint.Equality)
            ?.errorContext

        val annotationHint = listOf(
            "either change the annotation to match the body, or change the body to produce the annotated type"
        )

        val (code, hints) = when (ctx) {
            is com.mezze.language.typechecker.constraints.ErrorContext.TypeAnnotation ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH to annotationHint
            is com.mezze.language.typechecker.constraints.ErrorContext.ReturnType ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH to annotationHint
            is com.mezze.language.typechecker.constraints.ErrorContext.FunctionArgument ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to emptyList()
            is com.mezze.language.typechecker.constraints.ErrorContext.BinaryOperation ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to listOf(
                    "the operator expects both operands to have the same type"
                )
            is com.mezze.language.typechecker.constraints.ErrorContext.FieldAccess ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0205_FIELD_NOT_FOUND to emptyList()
            is com.mezze.language.typechecker.constraints.ErrorContext.PatternMatch ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to listOf(
                    "the pattern's type must match the scrutinee's type"
                )
            is com.mezze.language.typechecker.constraints.ErrorContext.SeqElement ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to listOf(
                    "all seq elements must have the same type"
                )
            is com.mezze.language.typechecker.constraints.ErrorContext.StringInterpolation ->
                com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to listOf(
                    "interpolated expressions must produce a `Str` (use `.to_str {}` or `.to_dbg_str {}`)"
                )
            is com.mezze.language.typechecker.constraints.ErrorContext.Simple,
            null -> {
                // No structured context. Fall back to message-based detection
                // for the few cases produced outside generateBetterErrorMessage
                // (e.g. occurs-check from Unification, row-field equality from
                // operator desugar). Each branch here is a TODO: trace the
                // emission site, attach an ErrorContext, then drop the branch.
                val msg = error.message
                when {
                    msg.startsWith("Infinite type: ") ->
                        com.mezze.language.parsedast.diagnostics.ErrorCode.T0207_INFINITE_TYPE to listOf(
                            "this usually means a recursive let-binding is missing a type annotation"
                        )
                    // Row-field equality (operator desugar): "Field '<name>'
                    // type mismatch: expected X but got Y". Not classified
                    // structurally because the constraint here is on a fresh
                    // RecordType field, not a user-visible field access.
                    msg.startsWith("Field '") && msg.contains("type mismatch") ->
                        com.mezze.language.parsedast.diagnostics.ErrorCode.T0201_TYPE_MISMATCH to emptyList<String>()
                    else -> com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED to emptyList()
                }
            }
        }

        // When the failure is a pure effect-row mismatch on a TypeAnnotation /
        // ReturnType constraint, mine the constraint's actual/expected effect
        // rows to emit structured `AddEffectToAnnotation` fixes. The LSP can
        // then offer "Add `<Log>` to return type" one-click.
        val solverFixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> =
            if (ctx is com.mezze.language.typechecker.constraints.ErrorContext.TypeAnnotation ||
                ctx is com.mezze.language.typechecker.constraints.ErrorContext.ReturnType) {
                effectRowExtraEffects(constraint).let { extras ->
                    if (extras.isNotEmpty() && error.constraint.loc != null) {
                        extras.map { effect ->
                            com.mezze.language.parsedast.diagnostics.FixHint
                                .AddEffectToAnnotation(error.constraint.loc!!, effect)
                        }
                    } else emptyList()
                }
            } else emptyList()

        return TypeCheckError(
            phase = "Constraint Solving",
            message = error.message,
            loc = error.constraint.loc,
            code = code,
            hints = hints,
            fixHints = solverFixHints,
        )
    }

    /**
     * Returns the effects that exist in the constraint's actual (left) side
     * but not in the expected (right) side, when both are function types that
     * only differ in their effect rows. Empty when the constraint is some
     * other shape — caller falls back to other fix kinds.
     */
    private fun effectRowExtraEffects(
        constraint: com.mezze.language.typechecker.constraints.Constraint
    ): List<String> {
        val eq = constraint as? com.mezze.language.typechecker.constraints.Constraint.Equality
            ?: return emptyList()
        val actual = eq.left as? MezzeType.FunctionType ?: return emptyList()
        val expected = eq.right as? MezzeType.FunctionType ?: return emptyList()
        if (actual.paramType != expected.paramType) return emptyList()
        if (actual.returnType != expected.returnType) return emptyList()
        val actualEffects = actual.effects?.effects ?: emptySet()
        val expectedEffects = expected.effects?.effects ?: emptySet()
        return (actualEffects - expectedEffects).sorted()
    }

    private fun buildPartialTypedModule(
        parsedModule: ParsedModule,
        substitution: com.mezze.language.typechecker.solving.Substitution,
        constraintResults: Map<String, com.mezze.language.typechecker.constraints.ConstraintResult>,
        context: com.mezze.language.typechecker.context.TypingContext,
        buildResult: com.mezze.language.typechecker.context.ContextBuilder.BuildResult,
        importContext: ModuleImportContext?,
        allTypeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
        allAbilityDeclarations: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>,
        allEffectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        bindLetLocs: Set<com.mezze.language.parsedast.Loc> = emptySet(),
    ): com.mezze.language.typedast.TypedModule? {
        return try {
            val resolvedResults = constraintResults.mapValues { (_, r) ->
                r.copy(type = substitution.apply(r.type))
            }
            val methodCallResolutions = constraintResults.values
                .fold(emptyMap<Pair<com.mezze.language.parsedast.Loc, String>, com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo>()) { acc, r ->
                    acc + r.methodCallResolutions
                }
            val callReturnTypes = constraintResults.values
                .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                    acc + r.callReturnTypes
                }
            val tailCallLocations = constraintResults.values
                .flatMap { it.tailCalls }
                .filterNotNull()
                .toSet()
            val builder = com.mezze.language.typechecker.emit.TypedAstBuilder(
                substitution = substitution,
                constraintResults = resolvedResults,
                abilityImpls = context.getAllAbilityImpls(),
                typeDeclarations = allTypeDeclarations,
                abilityDeclarations = allAbilityDeclarations,
                effectDeclarations = allEffectDeclarations,
                performerToEffect = buildResult.performerToEffect,
                importContext = importContext,
                identifierContentHashes = context.getContentHashes(),
                implDeclarations = buildResult.implDeclarations,
                tailCallLocations = tailCallLocations,
                mustBeOpenRowVars = emptySet(),
                methodCallResolutions = methodCallResolutions,
                callReturnTypes = callReturnTypes,
                bindLetLocs = bindLetLocs,
            )
            builder.build(parsedModule)
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[partial-builder] ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace(System.err)
            }
            null
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
     * Inject Exception + ExceptionToResult into the import context so the
     * synthetic `perform ExceptionToResult in ...` emitted by DoBindDesugar
     * resolves at constraint-generation time. Only called when the module
     * actually contains `<-`. The names ARE visible elsewhere in the
     * module too (a small acceptable "leak"); if the user writes
     * `perform ExceptionToResult` themselves they'll get the regular
     * performer behavior, no different from importing it.
     */
    private fun injectExceptionToResultForBind(
        baseCtx: ModuleImportContext,
        importResolver: ImportResolver,
    ): ModuleImportContext {
        val exceptionCtx = cachedExceptionForBindCtx ?: run {
            val effectName = com.mezze.language.typechecker.desugar.DoBindDesugar.EXCEPTION_EFFECT_NAME
            val performerName = com.mezze.language.typechecker.desugar.DoBindDesugar.EXCEPTION_TO_RESULT_NAME
            val syntheticSrc = "use std::effects::exception::{ $effectName, $performerName }"
            val syntheticModule = MezzeParser().parseParsedModule(syntheticSrc, file = "<dobind-synthetic>")
            when (val r = importResolver.resolveImports(syntheticModule)) {
                is ImportResolutionResult.Success -> r.importContext.also {
                    cachedExceptionForBindCtx = it
                }
                is ImportResolutionResult.Failure -> return baseCtx
            }
        }
        return baseCtx.copy(
            importedEffects = baseCtx.importedEffects + exceptionCtx.importedEffects,
            importedImpls = baseCtx.importedImpls + exceptionCtx.importedImpls,
        )
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

        // Load prelude module list from _prelude.mz (compiler-internal, not user-importable).
        // The resource MUST be present — the build pipeline ships it inside the
        // runtime jar and the gradle compileStdlib task uses it as the source
        // of truth. A missing resource is a build bug, not a runtime fallback,
        // so fail loudly instead of using a stale hardcoded list (issue #222).
        val preludeSource = ModuleResolver.readStdlibFile("stdlib/std/_prelude.mz")
            ?: ModuleResolver.readStdlibFile("std/_prelude.mz")
            ?: error(
                "Compiler internal error: stdlib/std/_prelude.mz not found on the classpath. " +
                "This file is required to bootstrap the prelude. Rebuild the runtime jar via " +
                "`./gradlew :cli:installRuntime` (or `installNative`)."
            )
        val syntheticModule = MezzeParser().parseParsedModule(preludeSource, file = "<prelude>")

        val result = importResolver.resolveImports(syntheticModule)
        val preludeContext = when (result) {
            is ImportResolutionResult.Success -> {
                // Import inherent impls for method syntax + named ability impls for
                // context-based constraint solving (replacing hardcoded checks).
                // No bindings — prevents name collisions (option::fold vs seq::fold).
                //
                // Abilities ARE carried so that `with X 'a` constraints in user
                // code can refer to prelude abilities (Sized, Show, etc.) and
                // method-call resolution sees their operation types (#304).
                //
                // Effects and effect impls are stripped. The Exception effect /
                // ExceptionToResult performer needed by the do-bind desugar are
                // injected per-module by `injectExceptionToResultForBind` ONLY
                // when the module contains a `<-`. See do-bind-proposal.md.
                val ctx = result.importContext
                // Cache loaded modules for inclusion in Monomorphizer's allModules
                cachedPreludeLoadedModules = result.loadedModules
                ModuleImportContext(
                    importedBindings = emptyMap(),
                    importedTypes = emptyMap(),
                    importedAbilities = ctx.importedAbilities,
                    importedEffects = emptyMap(),
                    importedImpls = emptyMap(),
                    importedAbilityImpls = ctx.importedAbilityImpls,
                    // Carry the inferred return types so anonymous-impl method calls
                    // (`xs.length {}`, `i.abs {}`, `s.to_upper {}`) infer concrete
                    // return types instead of `'a`. Issue #213.
                    inherentImplOpReturnTypes = ctx.inherentImplOpReturnTypes,
                    // Carry the inferred PARAM types so callers can't pass a
                    // non-function where the impl op expects one (#230).
                    inherentImplOpParamTypes = ctx.inherentImplOpParamTypes
                )
            }
            is ImportResolutionResult.Failure -> ModuleImportContext.empty()
        }

        cachedPreludeContext = preludeContext
        return preludeContext
    }

    // Prelude cache is in the companion object below

    /**
     * For an unresolved name, return the type-constructor names of any
     * prelude inherent impls whose operations include that name.
     *
     * Powers the "use method syntax" hint on undefined-variable errors:
     * when the user writes `fold {…}` bare, the prelude strips the free
     * function bindings but keeps the inherent impls — so `xs.fold {…}`
     * resolves while bare `fold` doesn't. Surfacing the carrier types
     * (Seq, Option, Result, …) tells the user how to call it.
     *
     * Returns at most a handful of carrier names, deduplicated.
     */
    private fun findPreludeMethodCarriers(name: String): List<String> {
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { return emptyList() }
        // During stdlib precompile, this can fire while PrecompiledStdlib itself
        // is mid-<clinit> (typecheck of one stdlib module surfaces an undefined
        // name; that module's error path lands here before preludeImportContext
        // is assigned). Treat the recursive case as "no carriers".
        val ctx = try { precompiled.preludeImportContext } catch (_: Throwable) { return emptyList() }
        @Suppress("SENSELESS_COMPARISON") if (ctx == null) return emptyList()
        val carriers = mutableListOf<String>()
        for ((_, impl) in ctx.importedAbilityImpls) {
            if (!impl.isInherent) continue
            if (impl.operations.none { it.name == name }) continue
            val target = impl.targetTypes.firstOrNull() ?: continue
            val targetName = when (target) {
                is com.mezze.language.parsedast.ParsedNamedType -> target.name
                is com.mezze.language.parsedast.ParsedTypeApp -> target.constructor
                else -> null
            } ?: continue
            if (targetName !in carriers) carriers.add(targetName)
        }
        return carriers
    }

    /** Result of a private-qualified-item check: message + the (modulePath, declName) needed for an AddPub fix. */
    private data class PrivateItemResult(val message: String, val declModulePath: String, val declName: String)

    /**
     * Check if a qualified name (like "module::function") refers to an item
     * that exists in the module but isn't public.
     *
     * @return A [PrivateItemResult] if the item exists but isn't public, null otherwise.
     */
    private fun checkIfPrivateQualifiedItem(
        undefinedVar: ConstraintResult.UndefinedVariable,
        loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule>,
        importContext: ModuleImportContext?
    ): PrivateItemResult? {
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
                return PrivateItemResult(
                    "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it.",
                    moduleByPath.modulePath,
                    itemName,
                )
            }
        }

        // Also check by matching against imported bindings to find the source module
        // Use structured path comparison instead of string startsWith
        val matchingBinding = importContext?.importedBindings?.entries?.find { (bindingName, _) ->
            val bindingPath = ModulePath.parse(bindingName.substringBeforeLast("::"))
            bindingPath == qualifierPath || bindingPath.endsWith(qualifierPath)
        }

        if (matchingBinding != null) {
            val modulePath = com.mezze.language.ast.ModulePath.parse(matchingBinding.value.modulePath)
            val module = loadedModules[modulePath]
            if (module != null) {
                val existsButNotExported = checkIfItemExistsInModule(module, itemName)
                if (existsButNotExported) {
                    return PrivateItemResult(
                        "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it.",
                        module.modulePath,
                        itemName,
                    )
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
                val opReturnTypes = importContext.inherentImplOpReturnTypes[implKey].orEmpty()
                val opParamTypes = importContext.inherentImplOpParamTypes[implKey].orEmpty()
                context = contextBuilder.registerImportedInherentImpl(
                    context, implDecl, implKey,
                    opReturnTypes = opReturnTypes,
                    opParamTypes = opParamTypes
                )
            } else {
                // Named ability impls only need AbilityImplInfo. The shared
                // factory (#307) ensures imported impls produce the same
                // shape as locally-registered ones — fixes a long-standing
                // divergence where targetTypes used `it.toString()` (source
                // form) here but `getTypeName` (synthetic-key form) in
                // ContextBuilder, AND where `operationConstraints` was
                // dropped on the import path.
                val abilityDeclHashForImpl = implDecl.abilityName?.let { name ->
                    importContext.importedAbilities[name]?.contentHash
                }
                val implInfo = com.mezze.language.typechecker.util.AbilityImplFactory.build(
                    implDecl, abilityDeclHashForImpl
                )
                context = context.extendWithImpl(implKey, implInfo)
            }
        }

        return context
    }


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
     * Type check a ParsedModule (v2 entry point).
     *
     * @param enablePrelude If true, auto-imports inherent impls from all stdlib modules.
     *                      Used by CLI/project execution, not by unit tests.
     * @throws TypeCheckError if type checking fails
     */
    @JvmOverloads
    fun typeCheck(module: com.mezze.language.parsedast.ParsedModule, currentPath: Path, enablePrelude: Boolean = false, modulePath: String? = null): com.mezze.language.typechecker.TypeCheckResult {
        // Reset type variable counter for consistent naming (old behavior)
        com.mezze.language.ast.TypeVar.resetCounter()

        // Delegate directly to checkParsedModule (the real v2 implementation)
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
                // Preserve errorType + metadata so callers using the throwing
                // entry point (legacy tests) see the same rich info that
                // result-style callers get via Failure.errors. See #313.
                throw TypeCheckError(
                    error.message,
                    error.loc,
                    error.errorType,
                    error.metadata,
                    code = error.code,
                    hints = error.hints,
                )
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
        // Call the 4-param checkParsedModule directly (the real v2 implementation)
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
                // Preserve errorType + metadata (see #313 — throwing entry
                // points keep parity with the Failure.errors list).
                throw TypeCheckError(
                    error.message,
                    error.loc,
                    error.errorType,
                    error.metadata,
                    code = error.code,
                    hints = error.hints,
                )
            }
        }
    }

    /**
     * Build resolved imports from import context for IDE support.
     */
    private fun buildResolvedImports(importContext: ModuleImportContext?): List<com.mezze.language.typechecker.ResolvedImport> {
        if (importContext == null) return emptyList()

        val imports = mutableListOf<com.mezze.language.typechecker.ResolvedImport>()

        // Add imported bindings (functions/values)
        for ((name, binding) in importContext.importedBindings) {
            imports.add(com.mezze.language.typechecker.ResolvedImport(
                localName = name,
                originalName = binding.originalName,
                modulePath = binding.modulePath,
                type = binding.type,
                kind = com.mezze.language.typechecker.ImportKind.FUNCTION
            ))
        }

        // Add imported types - use TypeApplication for type representation
        for ((name, typeDecl) in importContext.importedTypes) {
            val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar(it)) }
            imports.add(com.mezze.language.typechecker.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",  // TypeDeclaration doesn't track module path
                type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, typeParams),
                kind = com.mezze.language.typechecker.ImportKind.TYPE
            ))
        }

        // Add imported abilities - use simple type application for representation
        for ((name, _) in importContext.importedAbilities) {
            imports.add(com.mezze.language.typechecker.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList()),
                kind = com.mezze.language.typechecker.ImportKind.ABILITY
            ))
        }

        // Add imported effects - use simple type application for representation
        for ((name, _) in importContext.importedEffects) {
            imports.add(com.mezze.language.typechecker.ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList()),
                kind = com.mezze.language.typechecker.ImportKind.EFFECT
            ))
        }

        return imports
    }

    companion object {
        /** Cached prelude context — computed once per JVM process since stdlib doesn't change. */
        @Volatile
        private var cachedPreludeContext: ModuleImportContext? = null

        /**
         * Cached import context produced by resolving
         * `use std::effects::exception::{ Exception, ExceptionToResult }`.
         * Used by [injectExceptionToResultForBind] to surgically resolve
         * the synthetic `perform ExceptionToResult` inserted by do-bind.
         */
        @Volatile
        internal var cachedExceptionForBindCtx: ModuleImportContext? = null


        /** Cached prelude loaded modules — shared across all TypeChecker instances */
        @Volatile
        @JvmStatic
        var cachedPreludeLoadedModules: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>? = null

        /** Cached v1→v2 TypedModule conversions, keyed by content hash.
         *  Hash-based identity means the same module content at different paths shares one cache entry. */
        private val v1ToV2ModuleCache = java.util.concurrent.ConcurrentHashMap<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>()

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
         * Get a cached typed module's ParsedModule if available (compatibility API).
         * Returns the ParsedModule if the module has been loaded, null otherwise.
         */
        @JvmStatic
        @Deprecated("Use ModuleResolver.getFromCache(modulePath)?.parsedModule instead")
        fun getTypedModule(modulePath: String): com.mezze.language.parsedast.ParsedModule? {
            return ModuleResolver.getFromCache(modulePath)?.parsedModule
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
         * to avoid parsing them at runtime. Modules are loaded into the
         * ModuleResolver's cache.
         *
         * @param modules Map of module paths to ParsedModules
         */
        @JvmStatic
        fun preloadModules(modules: Map<String, com.mezze.language.parsedast.ParsedModule>) {
            val resolver = ModuleResolver.create()

            for ((modulePath, parsedModule) in modules) {
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

    // PIPE EXPRESSION DESUGARING

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
                    is com.mezze.language.parsedast.ParsedStatement.Bind -> hasPipes(stmt.expr)
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
            is com.mezze.language.parsedast.ParsedBinaryOp -> hasPipes(expr.left) || hasPipes(expr.right)
            is com.mezze.language.parsedast.ParsedUnaryOp -> hasPipes(expr.operand)
            else -> false
        }
        val hasAnyPipes = module.decls.any { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> hasPipes(decl.value)
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> decl.operations.any { hasPipes(it.implementation) }
                is com.mezze.language.parsedast.ParsedPerformerDecl -> decl.operations.any { hasPipes(it.implementation) }
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> hasPipes(decl.expression)
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
                        is com.mezze.language.parsedast.ParsedStatement.Bind -> stmt.copy(expr = desugar(stmt.expr))
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
            is com.mezze.language.parsedast.ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
                when (part) {
                    is com.mezze.language.parsedast.ParsedStringPart.Interpolation -> part.copy(expr = desugar(part.expr))
                    else -> part
                }
            })
            is com.mezze.language.parsedast.ParsedBinaryOp -> expr.copy(left = desugar(expr.left), right = desugar(expr.right))
            is com.mezze.language.parsedast.ParsedUnaryOp -> expr.copy(operand = desugar(expr.operand))
            else -> expr
        }

        return module.copy(decls = module.decls.map { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> decl.copy(value = desugar(decl.value))
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> decl.copy(operations = decl.operations.map { op -> op.copy(implementation = desugar(op.implementation)) })
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> decl.copy(expression = desugar(decl.expression))
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
        is com.mezze.language.parsedast.ParsedMatch -> expr.copy(
            scrutinee = replaceParsedPlaceholder(expr.scrutinee, replacement),
            cases = expr.cases.map { it.copy(body = replaceParsedPlaceholder(it.body, replacement)) }
        )
        is com.mezze.language.parsedast.ParsedDo -> expr.copy(
            statements = expr.statements.map { stmt ->
                when (stmt) {
                    is com.mezze.language.parsedast.ParsedStatement.Let -> stmt.copy(value = replaceParsedPlaceholder(stmt.value, replacement))
                    is com.mezze.language.parsedast.ParsedStatement.Expr -> stmt.copy(expr = replaceParsedPlaceholder(stmt.expr, replacement))
                    is com.mezze.language.parsedast.ParsedStatement.Bind -> stmt.copy(expr = replaceParsedPlaceholder(stmt.expr, replacement))
                }
            },
            result = replaceParsedPlaceholder(expr.result, replacement)
        )
        is com.mezze.language.parsedast.ParsedSeqLit -> expr.copy(elements = expr.elements.map { elem ->
            when (elem) {
                is com.mezze.language.parsedast.ParsedSeqElement.Regular -> elem.copy(expr = replaceParsedPlaceholder(elem.expr, replacement))
                is com.mezze.language.parsedast.ParsedSeqElement.Spread -> elem.copy(expr = replaceParsedPlaceholder(elem.expr, replacement))
            }
        })
        is com.mezze.language.parsedast.ParsedVariantConstruct -> expr.copy(value = expr.value?.let { replaceParsedPlaceholder(it, replacement) })
        is com.mezze.language.parsedast.ParsedPerform -> expr.copy(body = replaceParsedPlaceholder(expr.body, replacement))
        is com.mezze.language.parsedast.ParsedEffectCall -> expr.copy(arguments = replaceParsedPlaceholder(expr.arguments, replacement))
        is com.mezze.language.parsedast.ParsedStringLit -> expr.copy(parts = expr.parts.map { part ->
            when (part) {
                is com.mezze.language.parsedast.ParsedStringPart.Interpolation -> part.copy(expr = replaceParsedPlaceholder(part.expr, replacement))
                else -> part
            }
        })
        else -> expr
    }

    // V1 pipe desugarer deleted — v2 ParsedModule desugars pipes in desugarPipesInParsedModule()
    // do-bind desugar (`<-`) lives in DoBindDesugar.kt

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
