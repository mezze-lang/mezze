package com.mezze.language.typechecker.phase

import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.TypedModule
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.Substitution

/**
 * Result of emitting a typed module.
 */
internal data class EmitResult(
    val typedModule: com.mezze.language.typedast.TypedModule?,
    val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>?,
    val loweredPerformers: List<Array<String>>,
)

/**
 * Phase 4: Build TypedAST, including codec impls, anonymous constructor
 * validation, monomorphization, and lowering to TruffleIR.
 */
internal fun emitTypedModuleAndLower(
    parsedModule: ParsedModule,
    module: ParsedModule, // original module (pre-resolution) for type decl scanning
    substitution: Substitution,
    constraintResults: Map<String, ConstraintResult>,
    context: TypingContext,
    buildResult: ContextBuilder.BuildResult,
    importContext: ModuleImportContext?,
    registry: DeclarationRegistry,
    solver: ConstraintSolver,
    loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule>,
    tailCallLocations: Set<com.mezze.language.parsedast.Loc>,
    mustBeOpenRowVars: Set<com.mezze.language.ast.RowVar>,
    methodCallResolutions: Map<Pair<com.mezze.language.parsedast.Loc, String>, ConstraintResult.MethodCallInfo>,
    callReturnTypes: Map<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>,
    matchCaseBindings: Map<com.mezze.language.parsedast.Loc, Map<String, com.mezze.language.ast.MezzeType>>,
    bindLetLocs: Set<com.mezze.language.parsedast.Loc>,
    isStdlibModule: Boolean,
    currentModulePath: String?,
): EmitResult {
    var emittedTypedModule: com.mezze.language.typedast.TypedModule? = null
    var loweredFns: List<com.mezze.language.truffleir.FunctionIR>? = null
    var loweredPerformers: List<Array<String>> = emptyList()

    // Build TypedAST
    try {
        val builder = com.mezze.language.typechecker.emit.TypedAstBuilder(
            substitution = substitution,
            constraintResults = constraintResults,
            abilityImpls = context.getAllAbilityImpls(),
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            importContext = importContext,
            identifierContentHashes = context.getContentHashes(),
            implDeclarations = buildResult.implDeclarations,
            tailCallLocations = tailCallLocations,
            mustBeOpenRowVars = mustBeOpenRowVars,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            matchCaseBindings = matchCaseBindings,
            bindLetLocs = bindLetLocs,
        )
        val typed = builder.build(parsedModule)

        // Generate auto-derived codec impls (EncodeRepr/DecodeRepr) from constraint solver.
        val codecImpls = solver.getGeneratedCodecImpls()
        val codecGen = com.mezze.language.typechecker.codec.CodecGenerator()
        val extraImpls = mutableListOf<com.mezze.language.typedast.TypedAbilityImpl>()

        for (parsedImpl in codecGen.seedPrimitiveImpls()) {
            try {
                builder.buildCodecImpl(parsedImpl, extraImpls)
            } catch (_: Exception) {
                // Best-effort: if a primitive impl fails to build, on-demand
                // path remains as fallback.
            }
        }

        for (ci in codecImpls) {
            try {
                val formatType: com.mezze.language.ast.MezzeType
                val dataType: com.mezze.language.ast.MezzeType
                if (ci.targetTypes.size == 2) {
                    formatType = ci.targetTypes[0]
                    dataType = ci.targetTypes[1]
                } else if (ci.targetTypes.size == 1) {
                    dataType = ci.targetTypes[0]
                    formatType = com.mezze.language.ast.MezzeType.RecordType(emptyMap())
                } else {
                    continue
                }
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
                // Codec generation errors are non-fatal.
            }
        }

        emittedTypedModule = if (extraImpls.isNotEmpty()) {
            typed.copy(abilityImpls = typed.abilityImpls + extraImpls)
        } else {
            typed
        }
    } catch (e: Exception) {
        if (System.getenv("MEZZE_DEBUG") != null) {
            System.err.println("[BUILDER] ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    // #311: anonymous variant constructors must have a single shape per module.
    if (!isStdlibModule && emittedTypedModule != null) {
        validateAnonymousConstructorShapes(module, importContext, emittedTypedModule!!)
    }

    // Mono -> Resolve -> Lower (entry module only)
    if (!isStdlibModule && emittedTypedModule != null) try {
        val compileResult = compileModule(emittedTypedModule!!, loadedModules)
        loweredFns = compileResult.functions
        loweredPerformers = compileResult.performerRecords
    } catch (e: Exception) {
        if (System.getenv("MEZZE_COMPILER_DBG") != null) {
            System.err.println("[COMPILER-DBG] ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    return EmitResult(emittedTypedModule, loweredFns, loweredPerformers)
}

/**
 * Validate anonymous variant constructors have a single shape per module.
 */
private fun validateAnonymousConstructorShapes(
    module: ParsedModule,
    importContext: ModuleImportContext?,
    typedModule: com.mezze.language.typedast.TypedModule,
) {
    val declaredCtors = mutableSetOf<String>()
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
        .validate(typedModule, declaredCtors)
}

/**
 * Result of compiling a typed module.
 */
private data class CompileResult(
    val functions: List<com.mezze.language.truffleir.FunctionIR>,
    val performerRecords: List<Array<String>>,
)

/**
 * Run mono + compile on a typed module.
 */
private fun compileModule(
    typedModule: com.mezze.language.typedast.TypedModule,
    loadedModules: Map<com.mezze.language.ast.ModulePath, TypedModule>,
): CompileResult {
    val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule)

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
        if (stdlibModule.hash in preludeModuleHashes) continue
        if (stdlibModule.hash in seenHashes) continue
        seenHashes.add(stdlibModule.hash)
        importedModules.add(stdlibModule)
        importedPerformers.addAll(stdlibModule.performerDecls)
    }
    for ((_, cached) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
        val stdlibModule = cached.typedModule ?: continue
        if (stdlibModule.hash in preludeModuleHashes) continue
        if (stdlibModule.hash in seenHashes) continue
        seenHashes.add(stdlibModule.hash)
        importedModules.add(stdlibModule)
        importedPerformers.addAll(stdlibModule.performerDecls)
    }

    // Mono
    val stdlibModulesForMono = PrecompiledStdlib.stdlibTypedModules.values.toList()
    val monoResult = com.mezze.language.typechecker.mono.MonoDriver.run(
        entry = wrapped,
        imported = importedModules,
        resolutionOnly = stdlibModulesForMono
    )

    // Compile with TypedASTCompiler
    val stdlibFns = try { PrecompiledStdlib.preloweredFunctions } catch (_: Throwable) { emptyMap() }
    val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
        abilityImpls = allAbilityImpls,
        stdlibHashes = preludeHashes,
        monoCallSiteRewrites = monoResult.bySiteLoc,
        preloweredFunctions = stdlibFns
    )
    compiler.setDeclIndex(declIndex)

    val result = compiler.compile(
        module = wrapped,
        additionalModules = importedModules,
        additionalPerformers = importedPerformers,
        monoSpecializations = monoResult.specializations.values.toList()
    )

    val performerRecords = result.performerRecords.map { pr ->
        val arr = mutableListOf(pr.performerHash, pr.effectHash)
        for (i in pr.operationHashes.indices) {
            arr.add(pr.operationHashes[i])
            arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
        }
        arr.toTypedArray()
    }

    return CompileResult(result.functions, performerRecords)
}
