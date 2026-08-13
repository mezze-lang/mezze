package com.mezze.language.typechecker.phase

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.emit.EntryFunctionBuilder
import com.mezze.language.typechecker.emit.TypedAstBuilder
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typechecker.module.TypedModuleResult
import com.mezze.language.typechecker.mono.MonoDriver
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.validation.AnonymousConstructorShapeValidator
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypedModule

internal data class EmitResult(
    val typedModule: TypedModule?,
    val loweredFunctions: List<FunctionIR>?,
    val loweredPerformers: List<Array<String>>,
)

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
    loadedModules: Map<ModulePath, TypedModuleResult>,
    tailCallLocations: Set<Loc>,
    mustBeOpenRowVars: Set<RowVar>,
    methodCallResolutions: Map<Pair<Loc, String>, ConstraintResult.MethodCallInfo>,
    callReturnTypes: Map<Loc, MezzeType>,
    matchCaseBindings: Map<Loc, Map<String, MezzeType>>,
    isStdlibModule: Boolean,
    currentModulePath: String?,
): EmitResult {
    var emittedTypedModule: TypedModule? = null
    var loweredFns: List<FunctionIR>? = null
    var loweredPerformers: List<Array<String>> = emptyList()

    try {
        val builder = TypedAstBuilder(
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
        )
        val typed = builder.build(parsedModule)

        val codecImpls = solver.getGeneratedCodecImpls()
        val codecGen = CodecGenerator()
        val extraImpls = mutableListOf<TypedAbilityImpl>()

        for (parsedImpl in codecGen.seedPrimitiveImpls()) {
            try {
                builder.buildCodecImpl(parsedImpl, extraImpls)
            } catch (_: Exception) {
            }
        }

        for (ci in codecImpls) {
            try {
                val formatType: MezzeType
                val dataType: MezzeType
                if (ci.targetTypes.size == 2) {
                    formatType = ci.targetTypes[0]
                    dataType = ci.targetTypes[1]
                } else if (ci.targetTypes.size == 1) {
                    dataType = ci.targetTypes[0]
                    formatType = MezzeType.RecordType(emptyMap())
                } else {
                    continue
                }
                val abilityHash = WellKnownHashConstants.resolveHash(ci.abilityName)
                val parsedImpl = when {
                    WellKnownHashConstants.isEncodeFlavoredCodec(abilityHash) ->
                        codecGen.generateEncodeImpl(formatType, dataType)
                    WellKnownHashConstants.isDecodeFlavoredCodec(abilityHash) ->
                        codecGen.generateDecodeImpl(formatType, dataType)
                    else -> continue
                }
                builder.buildCodecImpl(parsedImpl, extraImpls)
            } catch (_: Exception) {
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

    if (!isStdlibModule && emittedTypedModule != null) try {
        val compileResult = compileModule(emittedTypedModule!!, loadedModules, substitution)
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

private fun validateAnonymousConstructorShapes(
    module: ParsedModule,
    importContext: ModuleImportContext?,
    typedModule: TypedModule,
) {
    val declaredCtors = mutableSetOf<String>()
    declaredCtors.addAll(BuiltinTypes.coreVariantConstructors)
    fun collectFromTypeDecl(decl: ParsedTypeDecl) {
        when (val def = decl.definition) {
            is ParsedVariantDef ->
                def.constructors.forEach { declaredCtors.add(it.name) }
            is ParsedNewtypeDef ->
                declaredCtors.add(def.constructor)
            else -> {}
        }
    }
    for (decl in module.decls) {
        if (decl is ParsedTypeDecl) collectFromTypeDecl(decl)
    }
    importContext?.importedTypes?.values?.forEach { collectFromTypeDecl(it) }
    for ((_, stdMod) in PrecompiledStdlib.stdlibTypedModules) {
        for (d in stdMod.decls) {
            if (d is TypedTypeDecl) {
                when (val def = d.definition) {
                    is TypedVariantDef ->
                        def.constructors.keys.forEach { declaredCtors.add(it) }
                    is TypedNewtypeDef ->
                        declaredCtors.add(def.constructor)
                    else -> {}
                }
            }
        }
    }
    AnonymousConstructorShapeValidator
        .validate(typedModule, declaredCtors)
}

private data class CompileResult(
    val functions: List<FunctionIR>,
    val performerRecords: List<Array<String>>,
)

private fun compileModule(
    typedModule: TypedModule,
    loadedModules: Map<ModulePath, TypedModuleResult>,
    substitution: Substitution,
): CompileResult {
    val wrapped = EntryFunctionBuilder.wrapTopLevel(typedModule)

    val allAbilityImpls = mutableListOf<TypedAbilityImpl>()
    allAbilityImpls.addAll(wrapped.abilityImpls)
    allAbilityImpls.addAll(PrecompiledStdlib.stdlibAbilityImpls)
    for ((_, v1Module) in loadedModules) {
        val stdlibModule = v1Module.typedModule ?: continue
        allAbilityImpls.addAll(stdlibModule.abilityImpls)
    }
    for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
        val stdlibModule = cached.typedModule ?: continue
        allAbilityImpls.addAll(stdlibModule.abilityImpls)
    }

    val declIndex = mutableMapOf<Hash, TypedLetDecl>()
    for (d in wrapped.decls) {
        if (d is TypedLetDecl) declIndex[d.hash] = d
    }

    val preludeHashes = mutableSetOf<Hash>()
    preludeHashes.addAll(PrecompiledStdlib.preloweredFunctions.keys)
    preludeHashes.addAll(PrecompiledStdlib.stdlibDeclHashes)

    val preludeModuleHashes = PrecompiledStdlib.stdlibModuleHashes
    val importedModules = mutableListOf<TypedModule>()
    val importedPerformers = mutableListOf<TypedPerformerDecl>()
    val seenHashes = mutableSetOf<Hash>()
    for ((_, v1Module) in loadedModules) {
        val stdlibModule = v1Module.typedModule ?: continue
        if (stdlibModule.hash in preludeModuleHashes) continue
        if (stdlibModule.hash in seenHashes) continue
        seenHashes.add(stdlibModule.hash)
        importedModules.add(stdlibModule)
        importedPerformers.addAll(stdlibModule.performerDecls)
    }
    for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
        val stdlibModule = cached.typedModule ?: continue
        if (stdlibModule.hash in preludeModuleHashes) continue
        if (stdlibModule.hash in seenHashes) continue
        seenHashes.add(stdlibModule.hash)
        importedModules.add(stdlibModule)
        importedPerformers.addAll(stdlibModule.performerDecls)
    }

    val stdlibModulesForMono = PrecompiledStdlib.stdlibTypedModules.values.toList()
    val monoResult = MonoDriver.run(
        entry = wrapped,
        imported = importedModules,
        resolutionOnly = stdlibModulesForMono
    )

    val stdlibFns = try { PrecompiledStdlib.preloweredFunctions } catch (_: Throwable) { emptyMap() }
    val compiler = TypedASTCompiler(
        abilityImpls = allAbilityImpls,
        stdlibHashes = preludeHashes,
        monoCallSiteRewrites = monoResult.bySiteLoc,
        monoStripBySiteLoc = monoResult.stripBySiteLoc,
        preloweredFunctions = stdlibFns,
        substitution = substitution
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
