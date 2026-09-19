package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.util.WellKnownTypeApp
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
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.module.*
import com.mezze.language.typechecker.phase.*
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
    internal val parentContext: TypingContext = createInitialContext(),
    internal val strictTopLevel: Boolean = false,
) {
    /**
     * Java/WASM-friendly constructor: default context, explicit program-mode flag.
     * WASM (MezzeWeb.java) can't use Kotlin named args / defaults, so it calls
     * `new TypeChecker(true)` to run the same strict PROGRAM rules the CLI does —
     * e.g. rejecting a bare top-level expression (V0710).
     */
    constructor(strictTopLevel: Boolean) : this(createInitialContext(), strictTopLevel)

    internal fun loadPreludeModulesPublic(importResolver: ImportResolver): ModuleImportContext {
        return loadPreludeModulesInternal(importResolver, ModuleImportContext.empty())
    }

    internal fun getCachedPreludeLoadedModules(): Map<ModulePath, TypedModuleResult>? {
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
            val loadedModules: Map<ModulePath, TypedModuleResult> = emptyMap(),
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
            // #159: carry the import context on the failure path too, so the LSP
            // can populate imported abilities/effects/impls for completion and
            // hover even when the module as a whole didn't typecheck (mid-edit).
            // Without it, `x.` completion loses ability methods on first open of
            // a file that has an error (no prior successful result to fall back on).
            val importContext: ModuleImportContext? = null,
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
    ): TypeCheckResult = try {
        checkParsedModuleInner(module, currentPath, currentModulePath, enablePrelude)
    } catch (e: StackOverflowError) {
        // Ultimate anti-crash backstop: a pathological/malformed program can drive
        // the checker's recursive descent (type-annotation conversion, unification,
        // pattern validation, ability derivation, pretty-printing) into a stack
        // overflow. Convert it to a clean located T0214 rather than crashing the
        // compiler/LSP/playground. The parser already caps syntactic nesting, so
        // this only fires on shapes that slip past that (inference-built or
        // recursive/mutually-referential types).
        TypeCheckResult.Failure(listOf(TypeCheckError(
            phase = "typecheck",
            message = "This program is too complex or too deeply/recursively nested for the " +
                "typechecker to process safely.",
            code = com.mezze.language.parsedast.diagnostics.ErrorCode.T0214_TYPECHECK_LIMIT_EXCEEDED,
            hints = listOf("simplify the type, split it into named aliases, or check for an unintended recursive definition")
        )))
    }

    private fun checkParsedModuleInner(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        currentModulePath: String? = null,
        enablePrelude: Boolean = false
    ): TypeCheckResult {
        @Suppress("NAME_SHADOWING")
        val module = desugarPipesInParsedModule(module)

        clearUnificationCache()
        if (!TypeCheckCache.hasPersistence()) {
            TypeCheckCache.initializeInMemory()
        }

        var importContext: ModuleImportContext? = null
        var loadedModules: Map<ModulePath, TypedModuleResult> = emptyMap()
        val shouldInjectPrelude = !ModulePathConventions.isStdlib(currentModulePath)
        val hasUserImports = hasImportsParsed(module)
        val precompiled = try { PrecompiledStdlib } catch (_: Exception) { null }

        val enhancedContext = if (!hasUserImports && shouldInjectPrelude && precompiled != null) {
            importContext = precompiled.preludeImportContext
            if (cachedPreludeLoadedModules == null) {
                cachedPreludeLoadedModules = precompiled.loadedModules
            }
            precompiled.compiledPrelude.mergeInto(parentContext)
        } else if (hasUserImports || shouldInjectPrelude) {
            // User imports (`use <dep>::…`) MUST resolve against the module's own
            // directory — its `.deps/mezze`, `src/`, `lib/` — so we always build a
            // resolver rooted at `currentPath`. It still resolves `use std::…`
            // (stdlib is loaded from embedded resources, not from any project root)
            // and still shares the warm precompiled `parentContext`, so there's no
            // stdlib/perf loss.
            //
            // The old code reused a single GLOBAL resolver (`precompiled.importResolver`,
            // rooted at `mezze.project.root` — which is the STDLIB/compiler-install
            // root, not the user's project) whenever `currentPath.startsWith` that
            // sysprop. For a user project nested INSIDE the mezze repo tree that test
            // wrongly fired, so `.deps` was searched in the repo root instead of the
            // project → spurious "module not found". Rooting at `currentPath`
            // unconditionally is correct for the top project AND for a dependency
            // module (…/.deps/mezze/<pkg>/…), which must resolve its own `.deps`.
            val moduleLoader = ModuleLoader.fromPath(currentPath.toString())
            val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                parentContext,
                moduleLoader.getResolver()
            )
            val importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

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
                    // Cross-module `from_json`/`to_json` wrapper hole: a wrapper such
                    // as `decw : … with FromJson 'a Str -> …` compiles in its own
                    // module, but a module that only imports the WRAPPER (not the
                    // codec module) can't discharge the wrapper's `FromJson`/`Decode`
                    // constraint — the impls never travel with the import, so the
                    // ability check reports "does not implement FromJson". Make the
                    // codec blanket IMPLS visible for constraint solving here (never
                    // the ability decls — see loadCodecImplContext), but ONLY when
                    // this module does NOT
                    // already have codec in scope (an explicit `use std::codec::*`
                    // already brings them, and re-injecting a second copy perturbs
                    // the codec-derivation path). Scoped to a user-module typecheck —
                    // never the precompiled prelude/stdlib — so derivation ordering
                    // for codec-importing code is untouched.
                    if (shouldInjectPrelude && !hasCodecInScope(importContext!!)) {
                        loadCodecImplContext(importResolver)?.let {
                            importContext = importContext!!.merge(it)
                        }
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
            // Fold in by-hash effects (effectsByHash): an effect that was
            // not imported by name but IS needed for machinery — e.g. the
            // effect of an imported performer, registered so its
            // PerformerTypeInfo builds and `perform X { … }` config is checked.
            importedEffects = (importContext?.importedEffects ?: emptyMap()) +
                (importContext?.effectsByHash?.values?.associateBy { it.name } ?: emptyMap()),
            currentModulePath = currentModulePath
        )
        val context = buildResult.context
        val parsedModule = buildResult.resolvedModule ?: module

        // Cross-module generic-impl coherence: orphan rule (V0406) covers
        // "foreign ability + foreign type" but not "foreign generic +
        // local newtype". Per project_generic_impls_permanent, once a
        // generic impl exists in the dep tree, no concrete specialization
        // is allowed anywhere.
        importContext?.importedAbilityImpls?.let { imported ->
            val localImpls = parsedModule.decls.filterIsInstance<ParsedImplAbilityDecl>()
            com.mezze.language.typechecker.validation.DuplicateDeclarationValidator
                .validateGenericAgainstImportedImpls(localImpls, imported)
        }

        cachedTypeAliases = buildTypeAliasesFromParsedModule(parsedModule, contextBuilder, importContext)

        val abilityNames = buildResult.registry.abilityNames +
            (importContext?.importedAbilities?.keys ?: emptySet())
        val effectNames = buildResult.registry.effectNames +
            (importContext?.importedEffects?.keys ?: emptySet())

        DebugLogger.debugSection(Category.TypeChecker, "Abilities and Effects") {
            listOf("abilityNames = $abilityNames", "effectNames = $effectNames")
        }

        val registry = buildFullRegistry(loadedModules, buildResult, importContext)

        // Install the registry-backed hash resolver for the whole module check so
        // WellKnownTypeApp.of resolves a USER/LOCAL declared type's content hash in
        // ONE place — no call site needs to thread it. (Stdlib hashes come from the
        // well-known table; the registry supplies the rest; the hash is a pure fn of
        // the decl and always computable.) See WellKnownTypeApp.withHashResolver.
        WellKnownTypeApp.withHashResolver({ name ->
            // Resolve as broadly as ImplValidator.stampTypeHashes does (#312): a
            // cross-module ability-op type like `Config` isn't a DIRECT decl in
            // this module (lookupType misses it), but typeHashFor consults local +
            // imported + transitive types. Using it here means such a type is born
            // Declared(hash) at construction instead of Anonymous-with-a-name that
            // stampTypeHashes has to repair by reading `.constructor` — the deferred
            // case that blocked constructor from deriving purely from identity.
            registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) }
                ?: registry.typeHashFor(name)
        }) {

        // Shared codec-impl registry threaded through both the per-binding solvers
        // (spawned inside ConstraintGenerator) and the module-level solver below.
        // Codec impls the constraint solver auto-derives (e.g. `Encode JsonC Bool`
        // for a bare `True.encode{…}`) are a side effect of ability checking; they
        // must survive across the constraint generator's transient per-binding
        // solvers so `TypedModuleEmitter` can synthesize them into `abilityImpls`.
        // See AbilityChecker.generatedCodecImpls for the full rationale.
        val sharedGeneratedCodecImpls =
            mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typechecker.solving.AbilityChecker.GeneratedCodecImpl>()

        val constraintGenerator = ConstraintGenerator.create(
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            performerAddedEffects = buildResult.performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            // `isStdlibModule` here doubles as the "may reference `__native_*`
            // intrinsics" gate. Widened to include `mezze_<lang>` external
            // polyglot-provider packages — same trust boundary, both flavours
            // ship their `__native_*` calls through the same review process.
            isStdlibModule = ModulePathConventions.isNativeCapable(currentModulePath),
            sharedGeneratedCodecImpls = sharedGeneratedCodecImpls,
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

        // asymmetry fix: fold ability preconditions inferred from impl-op bodies
        // into the frozen context's AbilityImplInfo.operationConstraints, so the
        // final use-site solve rejects a generic impl applied to a type that
        // doesn't satisfy what its body needs (e.g. `impl Ord 'a for Wrap 'a`
        // whose body does `it.v < r.v` requires `Ord 'a`). An impl op is a
        // function; its body's residual is a precondition, like a fn's `with`.
        var solveContext = context
        for ((implHash, extra) in programConstraintResult.inferredImplConstraints) {
            solveContext = solveContext.withUpdatedImplConstraints(implHash, extra)
        }

        val solver = ConstraintSolver(solveContext, registry, sharedGeneratedCodecImpls = sharedGeneratedCodecImpls)
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
                    registry = registry,
                )
                val partialExpectedTypeAt = constraintResults.values
                    .fold(emptyMap<Loc, MezzeType>()) { acc, r ->
                        acc + r.expectedTypeAt
                    }
                    .mapValues { (_, type) -> perBindingSubstitution.apply(type) }
                return TypeCheckResult.Failure(
                    errors = deduped, typedModule = partialTyped,
                    expectedTypeAt = partialExpectedTypeAt,
                    importContext = importContext,
                )
            }
            is ConstraintSolver.SolveResult.Success -> {
                // S0.4/#373 — SATURATE the composed post-solve substitution so it is
                // IDEMPOTENT (apply(apply(t)) == apply(t)). The solver's step-wise
                // `compose` is a non-saturating union: it keeps each NEW binding
                // resolved against prior work but never re-resolves an OLD RHS when a
                // later binding pins a var that RHS still mentions, leaving entries
                // like `'d -> Fn(_ -> 'e)` beside `'e -> Str`. `apply` already yields
                // the fixpoint (it recurses), so saturating the stored RHSs changes no
                // resolved type — it only collapses the fixpoint into one stored step,
                // which is what makes the idempotence invariant below hold. This runs
                // once at the boundary; downstream `substitution.apply(...)` is
                // unchanged in result, just guaranteed idempotent.
                // A.1: fold in the impl-op body solves ImplValidator used to discard.
                // Each was a full, independent solve whose TypeVars carry globally
                // unique ids, so composing them cannot rebind another declaration's
                // variables — it only ADDS the bindings for variables nothing else
                // mentions. `compose` keeps the receiver's mappings on any overlap,
                // so the module solve stays authoritative wherever the two meet.
                val implOpSubstitution = if (ElaborationFlags.carryImplOpSolves) {
                    programConstraintResult.implOpSolutions
                        .fold(Substitution.empty) { acc, sol -> acc.compose(sol.substitution) }
                } else Substitution.empty
                val substitution = solveResult.substitution
                    .compose(perBindingSubstitution)
                    .compose(implOpSubstitution)
                    .saturate()
                InvariantChecker.checkSubstitution(substitution, "post-solve")
                // S0.4/#373 — POST-SOLVE SUBSTITUTION IDEMPOTENCE, now that
                // saturate() above establishes it. `apply(apply(t)) == apply(t)`: no
                // stored RHS still mentions a var the same substitution rewrites.
                //
                // ROOT CAUSE (traced): the non-idempotence is NOT the two-solve seam.
                // Every offending mapping lives entirely in the GLOBAL solve
                // (solveResult.substitution) — none straddles the per-binding/global
                // boundary — so it is the solver's own step-wise non-saturating
                // `compose` (result.compose(substitution) at every ConstraintSolver
                // step), which keeps NEW bindings resolved against prior work but
                // never re-resolves an OLD RHS when a later binding pins a var that
                // RHS still contains. The concrete repro was `'d -> Fn({n:Float} ->
                // 'e)` beside `'e -> Str` (a plain numeric record, NOT associated-type
                // specific — the assoc-type family just surfaced it first via the
                // prelude typecheck cached in a static initializer, hence the S0.1
                // ExceptionInInitializerError cascade).
                //
                // saturate() is semantically transparent: `apply` already yields the
                // fixpoint in one recursive pass, so re-storing each RHS as its own
                // fixpoint changes no resolved type — the full tc+interp suites stay
                // green with this invariant ON. Promoted from the disabled NOTE to a
                // real gate: a regression that reopens the non-idempotence (a
                // downstream result depending on how many times apply() runs) trips
                // here under -Dmezze.invariants=true instead of surfacing as an
                // order-dependent inference bug. This also discharges the S5.2/#365
                // two-solve composition clause: each side is itself made idempotent by
                // saturate() and their union is saturated too, so the two solves
                // compose without any further pass.
                InvariantChecker.checkSubstitutionIdempotence(substitution, "post-solve")
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
                // Pass ability name → method names so an ability imported only
                // to make its methods callable (`x.from_json {}`) is not
                // mis-reported as unused. Both imported and locally-declared
                // abilities, since either can be imported/used this way.
                val abilityOps: Map<String, Set<String>> =
                    ((importContext?.importedAbilities ?: emptyMap()).values +
                        parsedModule.decls.filterIsInstance<ParsedAbilityDecl>())
                        .associate { decl -> decl.name to decl.operations.map { it.name }.toSet() }
                warnings.addAll(UnusedImportLinter.lint(parsedModule, abilityOps).map {
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

                // A.1: the impl-op solves join the same aggregation as every other
                // declaration's, under synthetic keys so they cannot displace one.
                // Their side tables are keyed by their bodies' own source locations,
                // which no other node shares (LocKeyCollisionMeasurementTest), so the
                // fold below merges rather than overwrites.
                val resultsForAggregation = if (ElaborationFlags.carryImplOpSolves) {
                    constraintResults + programConstraintResult.implOpSolutions
                        .mapIndexed { index, sol ->
                            // matchCaseBindings is EXCLUDED, and the reason is
                            // measured rather than assumed. It is the one table
                            // whose merge breaks the codec decode bodies, which
                            // depend on their match bindings arriving as fresh
                            // variables: bisecting the merge, everything else
                            // together passes, and this table alone fails
                            // JsonDecodeExhaustiveTest and UserDecodeAbilityTest
                            // with a CallIndirect on a record. Supplying the
                            // binding's real type — even only where the structural
                            // read had nothing — routes the decoder wiring
                            // differently. That dependency on an absence is the
                            // hack A.3 removes by generating codec impls into the
                            // resolved module like derived ones; this line comes
                            // out with it. (The isolated solve also returns some
                            // types in the OTHER representation —
                            // `TypeApplication("Nat")` against the declared nominal
                            // `Nat` — which is disease 2, and B.1's job.)
                            "__impl_op_$index" to sol.result.copy(matchCaseBindings = emptyMap())
                        }
                } else constraintResults
                val aggregated = aggregateConstraintResults(resultsForAggregation, substitution)

                // S2.2/S2.3 (#357/#358): the SINGLE post-solve dispatch resolution
                // pass. For each recorded MethodResolveBucket it resolves the SOLVED
                // receiver's identity and applies ONE hash-keyed rule to pick the
                // winning op, producing a Resolution keyed by call-site Loc. S2.3:
                // emit READS these — for an inherent call site with a Resolution,
                // TypedAstBuilder takes `resolution.opHash` and performs NO impl
                // selection of its own (dispatch decided once). Sites the resolver
                // DEFERS (VarType-open receivers, ability/builtin collisions it
                // leaves to the ability path) have no Resolution and keep emit's
                // legacy per-site fallback — those are genuine deferrals, not a
                // second selection heuristic. Under -Dmezze.invariants=true a
                // Parity/Ambiguity divergence rethrows (the suite is the oracle);
                // in a normal gate it is swallowed to emptyMap so behaviour stays
                // byte-identical (emit falls back to legacy everywhere).
                val dispatchResolutions: Map<Loc, com.mezze.language.typechecker.constraints.Resolution> =
                    try {
                        com.mezze.language.typechecker.solving.DispatchResolver.resolveDispatch(
                            buckets = constraintGenerator.expressionGenerator.resolutionBuckets,
                            substitution = substitution,
                            methodCallResolutions = aggregated.methodCallResolutions,
                            typingContext = context,
                        )
                    } catch (e: com.mezze.language.typechecker.solving.DispatchResolver.DispatchParityException) {
                        if (System.getProperty("mezze.invariants", "false") == "true") throw e
                        emptyMap()
                    } catch (e: com.mezze.language.typechecker.solving.DispatchResolver.DispatchAmbiguityException) {
                        if (System.getProperty("mezze.invariants", "false") == "true") throw e
                        emptyMap()
                    }

                // S0.2 (#351): NO-UNRESOLVED-DISPATCH-VAR invariant. The DEFERRED
                // inherent-method resolutions (solveResult.unresolvedInherentMethods)
                // are the dispatch positions carrying the REAL solved receiver; the
                // emitted decl schemes' free vars are exactly the legitimately-
                // generalized params. A receiver that solves (via subst + the
                // per-call-site callReturnTypes, like emit) to a bare VarType which
                // is NOT one of those quantified params is an orphan receiver leak
                // (the "defaults to Bytes" mis-dispatch class) — throw under
                // mezze.invariants=true. Generalized-param receivers (dispatch on a
                // polymorphic `'a` inside `id`/`pair`/`add`) are a genuine deferral
                // and are NOT flagged; ability/builtin ops with a computed var
                // receiver are legitimately deferred elsewhere and never reach this
                // inherent-only list.
                val generalizedVarIds: Set<Int> = globallyResolvedConstraintResults.values
                    .flatMap { com.mezze.language.ast.solving.collectFreeTypeVars(it.type) }
                    .map { it.id }
                    .toSet()
                InvariantChecker.checkNoUnresolvedDispatchVar(
                    unresolvedInherentMethods = solveResult.unresolvedInherentMethods,
                    substitution = substitution,
                    callReturnTypes = aggregated.callReturnTypes,
                    generalizedVarIds = generalizedVarIds,
                )

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

                val validator = TypeValidator(context, registry, buildResult.implDeclarations, strictTopLevel)
                val constraintValidation = validator.validateConstraints(allConstraints, substitution)
                collectValidationResult(constraintValidation, collectedErrors, warnings)

                val isStdlibModule = ModulePathConventions.isStdlib(currentModulePath)
                // S5.2 (#365): freeze the solver→emit hand-off into ONE immutable
                // value here, at the boundary where the global solve finished and
                // the side-tables were aggregated. Emit consumes this read-only —
                // it can no longer reach behind it into mutable solver state.
                val solvedModule = com.mezze.language.typechecker.phase.SolvedModule(
                    substitution = substitution,
                    dispatchResolutions = dispatchResolutions,
                    tailCallLocations = aggregated.tailCallLocations,
                    mustBeOpenRowVars = mustBeOpenRowVars,
                    methodCallResolutions = aggregated.methodCallResolutions,
                    callReturnTypes = aggregated.callReturnTypes,
                    matchCaseBindings = aggregated.matchCaseBindings,
                    matchScrutineeTypes = aggregated.matchScrutineeTypes,
                    doBindingSolvedTypes = aggregated.doBindingSolvedTypes,
                    lambdaParamTypes = aggregated.lambdaParamTypes,
                    performFullyDischarged = aggregated.performFullyDischarged,
                )
                val emitResult = emitTypedModuleAndLower(
                    parsedModule = parsedModule, module = module,
                    solved = solvedModule,
                    constraintResults = globallyResolvedConstraintResults,
                    context = context, buildResult = buildResult,
                    importContext = importContext, registry = registry,
                    solver = solver, loadedModules = loadedModules,
                    isStdlibModule = isStdlibModule,
                    currentModulePath = currentModulePath,
                )

                // S4.2 (#362): effect-conservation invariant — the emitted
                // function-type effect row must be ⊇ the effects the body
                // actually performs (not-discharged). Runs on the just-emitted
                // TypedModule; gated by mezze.invariants=true, so a normal gate
                // is unaffected. A trip = a laundering hole (or an over-strict
                // invariant), never silent.
                emitResult.typedModule?.let { InvariantChecker.checkEffectConservation(it) }

                val v2ValidationResult = validator.validate(parsedModule, globallyResolvedConstraintResults, substitution, emitResult.typedModule)
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
                        importContext = importContext,
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
        } // end withHashResolver
    }

    private class AggregatedConstraintResults(
        val tailCallLocations: Set<Loc>,
        val methodCallResolutions: Map<Pair<Loc, String>, ConstraintResult.MethodCallInfo>,
        val callReturnTypes: Map<Loc, MezzeType>,
        val matchCaseBindings: Map<Loc, Map<String, MezzeType>>,
        val matchScrutineeTypes: Map<Loc, MezzeType>,
        val expectedTypeAt: Map<Loc, MezzeType>,
        val doBindingSolvedTypes: Map<Loc, MezzeType>,
        val performFullyDischarged: Map<Loc, Boolean>,
        val lambdaParamTypes: Map<Loc, Map<String, MezzeType>>,
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
        val matchScrutineeTypes = constraintResults.values
            .fold(emptyMap<Loc, MezzeType>()) { acc, r -> acc + r.matchScrutineeTypes }
            .mapValues { (_, type) -> substitution.apply(type) }
        val expectedTypeAt = constraintResults.values
            .fold(emptyMap<Loc, MezzeType>()) { acc, r -> acc + r.expectedTypeAt }
            .mapValues { (_, type) -> substitution.apply(type) }
        // #396: solved do-block let RHS value types, keyed by loc. bindLet already
        // substitution-applied these; re-applying the final module substitution is
        // idempotent and pins any var the local incremental solve left open.
        val doBindingSolvedTypes = constraintResults.values
            .fold(emptyMap<Loc, MezzeType>()) { acc, r -> acc + r.doBindingSolvedTypes }
            .mapValues { (_, type) -> substitution.apply(type) }
        // Effect-model migration (S2): per-perform fully-discharged flags (Boolean,
        // no substitution needed).
        val performFullyDischarged = constraintResults.values
            .fold(emptyMap<Loc, Boolean>()) { acc, r -> acc + r.performFullyDischarged }
        // Per-lambda solved param types. Substitution-applied here for the same
        // reason doBindingSolvedTypes is: the local incremental solve may have left
        // a variable open that the module solve pinned.
        val lambdaParamTypes = constraintResults.values
            .fold(emptyMap<Loc, Map<String, MezzeType>>()) { acc, r -> acc + r.lambdaParamTypes }
            .mapValues { (_, fields) -> fields.mapValues { (_, t) -> substitution.apply(t) } }
        return AggregatedConstraintResults(
            tailCallLocations = tailCallLocations,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            matchCaseBindings = matchCaseBindings,
            matchScrutineeTypes = matchScrutineeTypes,
            expectedTypeAt = expectedTypeAt,
            doBindingSolvedTypes = doBindingSolvedTypes,
            performFullyDischarged = performFullyDischarged,
            lambdaParamTypes = lambdaParamTypes,
        )
    }

    private fun buildFullRegistry(
        loadedModules: Map<ModulePath, TypedModuleResult>,
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
        // Built-in ability decls (NumAdd, Ord, Eq, …) go in at LOWEST precedence so
        // `2 + 3` resolves `.add` through the class-param-aware ability path even
        // with no `use`. Map-`+` is right-biased: any real decl of the same name on
        // the right wins, and DeclarationRegistry.from dedups the hash index (a
        // built-in and an imported NumAdd share the same contentHash). See #231.
        val allAbilityDeclarations = BuiltInAbilities.getRawAbilityDeclarations() +
            transitiveAbilities + buildResult.registry.abilityDeclarations + importedAbilities

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
        // BUG 3: a handler installed for an effect the body never performs. Dedup
        // by (performer, effect, loc) — the same perform can be reached through
        // more than one accumulated result.
        val unusedHandlerWarnings = constraintResults.values.flatMap { it.unusedHandlers }.distinct().map { dead ->
            TypeCheckWarning(
                message = "Handler '${dead.performerName}' handles '${dead.effectName}', but nothing in " +
                    "the `perform` body performs a '${dead.effectName}' operation — the handler never fires. " +
                    "Remove '${dead.performerName}' from the `perform` list.",
                loc = dead.loc,
                code = com.mezze.language.parsedast.diagnostics.WarningCode.W0401_UNUSED_HANDLER
            )
        }
        return unusedVarWarnings + patternWarnings + unusedHandlerWarnings
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

    /** Codec ability names whose blanket impls a from_json/to_json wrapper needs.
     *  If ANY is already visible, the module imports codec explicitly and we must
     *  NOT re-inject (a duplicate copy perturbs codec derivation). */
    private val codecAbilityNames = setOf(
        "FromJson", "ToJson", "ToJsonUtf8", "Encode", "Decode",
        "EncodeScalar", "DecodeScalar", "EncodeRecord", "DecodeRecord",
    )

    private fun hasCodecInScope(ctx: ModuleImportContext): Boolean =
        ctx.importedAbilities.keys.any { it in codecAbilityNames }

    private var cachedCodecImplContext: ModuleImportContext? = null

    /**
     * Resolve the codec modules and keep ONLY their ability impls + ability decls
     * (plus inherent-impl op metadata) — never their bindings or types, so the
     * opt-in `std::codec::*` NAME surface is unchanged. Cached per TypeChecker.
     * Returns null if the codec modules can't be resolved.
     */
    private fun loadCodecImplContext(importResolver: ImportResolver): ModuleImportContext? {
        cachedCodecImplContext?.let { return it }
        val src = "use std::codec::core\nuse std::codec::json\n"
        val synthetic = com.mezze.language.parser.MezzeParser().parseParsedModule(src, file = "<codec-impls>")
        val resolved = when (val r = importResolver.resolveImports(synthetic)) {
            is ImportResolutionResult.Success -> {
                val c = r.importContext
                // NOTE: deliberately drop `importedAbilities` — registering the codec
                // ability DECLS (`Format`, `Encode`, `Decode`, …) would shadow a
                // user ability of the same name (a user `ability Format for 'a` +
                // `42.format {}` must NOT resolve to the codec `Format`). We only
                // need the codec IMPLS visible for constraint solving; the ability
                // hashes come from `abilitiesByHash` / the registry. This keeps the
                // injection a pure impl-visibility fallback that cannot collide with
                // user names.
                ModuleImportContext(
                    importedBindings = emptyMap(),
                    importedTypes = emptyMap(),
                    importedAbilities = emptyMap(),
                    importedEffects = emptyMap(),
                    importedImpls = emptyMap(),
                    importedAbilityImpls = c.importedAbilityImpls,
                    abilitiesByHash = c.abilitiesByHash,
                    inherentImplOpReturnTypes = c.inherentImplOpReturnTypes,
                    inherentImplOpParamTypes = c.inherentImplOpParamTypes,
                    inherentImplOpEffects = c.inherentImplOpEffects,
                )
            }
            is ImportResolutionResult.Failure -> null
        }
        cachedCodecImplContext = resolved
        return resolved
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
        val loadedModules: Map<ModulePath, TypedModuleResult>
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
        @JvmStatic
        var cachedPreludeLoadedModules: Map<ModulePath, TypedModuleResult>? = null

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
        fun withContext(context: TypingContext, strictTopLevel: Boolean): TypeChecker =
            TypeChecker(context, strictTopLevel)

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
