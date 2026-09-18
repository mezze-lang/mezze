package com.mezze.language.typechecker.phase

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.PrecompiledDeps
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
    solved: SolvedModule,
    constraintResults: Map<String, ConstraintResult>,
    context: TypingContext,
    buildResult: ContextBuilder.BuildResult,
    importContext: ModuleImportContext?,
    registry: DeclarationRegistry,
    solver: ConstraintSolver,
    loadedModules: Map<ModulePath, TypedModuleResult>,
    isStdlibModule: Boolean,
    currentModulePath: String?,
): EmitResult {
    val substitution = solved.substitution
    var emittedTypedModule: TypedModule? = null
    var loweredFns: List<FunctionIR>? = null
    var loweredPerformers: List<Array<String>> = emptyList()

    try {
        val builder = TypedAstBuilder(
            solved = solved,
            constraintResults = constraintResults,
            abilityImpls = context.getAllAbilityImpls(),
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            importContext = importContext,
            identifierContentHashes = context.getContentHashes(),
            implDeclarations = buildResult.implDeclarations,
        )
        val typed = builder.build(parsedModule)

        val codecImpls = solver.getGeneratedCodecImpls()
        val codecGen = CodecGenerator(registry)
        val extraImpls = mutableListOf<TypedAbilityImpl>()

        // New codec ability shape (docs/codec/redesign-2026-09.md):
        //   Encode 'config for 'a          — targetTypes = [config, data]
        //   Decode 'config 'a for 'in      — targetTypes = [config, data, in]
        // The primitive-seed pass the old codec used is gone: with the new
        // Tier-3 abilities every derivation is triggered at a concrete call
        // site, and the constraint solver registers all three positional
        // types for us to consume here.
        for (ci in codecImpls) {
            try {
                val abilityHash = registry.resolveAbilityHash(ci.abilityName)
                val parsedImpl = when {
                    WellKnownHashConstants.isEncodeFlavoredCodec(abilityHash) -> {
                        if (ci.targetTypes.size != 2) continue
                        codecGen.generateEncodeImpl(ci.targetTypes[0], ci.targetTypes[1])
                    }
                    WellKnownHashConstants.isDecodeFlavoredCodec(abilityHash) -> {
                        if (ci.targetTypes.size != 3) continue
                        codecGen.generateDecodeImpl(ci.targetTypes[0], ci.targetTypes[1], ci.targetTypes[2])
                    }
                    // Derived Eq for an anonymous record shape (AbilityChecker.registerDerivedShapeImpl).
                    WellKnownHashConstants.isEq(abilityHash) -> {
                        val shape = ci.targetTypes.singleOrNull() as? com.mezze.language.ast.MezzeType.RecordType ?: continue
                        com.mezze.language.typechecker.derive.DerivedImplGenerator.eqImplForShape(shape)
                    }
                    else -> continue
                }
                builder.buildCodecImpl(parsedImpl, extraImpls)
            } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                // A diagnostic is never "this codec simply does not apply" — it is a
                // message written for the person compiling, at a location they can
                // act on. Swallowing one replaces it with silence and a failure much
                // further downstream. See the same split on the two handlers below.
                throw e
            } catch (e: Exception) {
                // A codec impl that fails to generate does not fail here — the
                // program keeps going and dies later at the use site with
                // "Field not found: decode", a long way from the cause. Keep the
                // swallow (a generation failure is not always fatal: the codec
                // ability may simply not apply to this target) but stop hiding it,
                // matching the outer handler below.
                if (System.getenv("MEZZE_DEBUG") != null) {
                    System.err.println(
                        "[CODEC] ${ci.abilityName} for ${ci.targetTypes}: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
        }

        emittedTypedModule = if (extraImpls.isNotEmpty()) {
            typed.copy(abilityImpls = typed.abilityImpls + extraImpls)
        } else {
            typed
        }
    } catch (e: com.mezze.language.typechecker.TypeCheckError) {
        // A TypeCheckError is a DIAGNOSTIC — a message written for the person
        // compiling, at a location they can act on. Swallowing one can only ever
        // replace it with silence: the module comes back null, and the runtime
        // reports "TruffleIR not produced by typechecker" with nothing to act on.
        // Everything else keeps the behaviour below, which is about a build step
        // that may legitimately not apply.
        throw e
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
        // Per-call-site decoder wiring: `from_json` keeps a bare-var target through
        // solving, so codegen can't tell two different-shape decodes apart. Recover
        // each call's concrete target structurally (from usage) and map it to the
        // matching derived decoder's op hash.
        // Entry-point ops (from_json etc.) are DERIVED from impls/lets carrying
        // decode-flavored ability-constraint hashes — those live in imports.
        val importedModulesForDecode = loadedModules.values.mapNotNull { it.typedModule }
        val decodeResult = com.mezze.language.typechecker.emit.DecodeTargetResolver
            .process(emittedTypedModule!!, importedModulesForDecode)
        emittedTypedModule = decodeResult.module
        // Cross-module from_json wrapper: also wire `from_json` calls that live in
        // IMPORTED module bodies (a generic wrapper compiled here as an additional
        // module) to THIS module's derived decoder. Keyed by the derived decode
        // impls on the emitted module; a no-op when nothing was derived.
        val derivedDecodeImpls = emittedTypedModule!!.abilityImpls
            .filter(com.mezze.language.typechecker.emit.DecodeTargetResolver::isCodecDecodeImpl)
        val importedForDecode = loadedModules.values.mapNotNull { it.typedModule }
        val importedDecodeOps = com.mezze.language.typechecker.emit.DecodeTargetResolver
            .processImportedCalls(importedForDecode, derivedDecodeImpls)
        val mergedDecodeOpBySite = if (importedDecodeOps.isEmpty()) decodeResult.decodeOpBySite
            else decodeResult.decodeOpBySite + importedDecodeOps
        val compileResult = compileModule(emittedTypedModule!!, loadedModules, substitution, mergedDecodeOpBySite)
        loweredFns = compileResult.functions
        loweredPerformers = compileResult.performerRecords
    } catch (e: com.mezze.language.typechecker.TypeCheckError) {
        // As above: a diagnostic propagates. Lowering that fails for any other
        // reason still leaves the typed module standing, which is what the swallow
        // below is for.
        throw e
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
    decodeOpBySite: Map<com.mezze.language.parsedast.Loc, com.mezze.language.typedast.Hash> = emptyMap(),
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

    // Every visible ability's operation names, keyed by the ability's CONTENT HASH
    // — the ability decides what its ops are called, and two abilities sharing an
    // op name are still different abilities. Declarations, not impls: an ability
    // nobody has implemented in this step still has its operations.
    val abilityOpNames = buildMap {
        val decls = wrapped.abilityDecls.asSequence() +
            PrecompiledStdlib.stdlibTypedModules.values.asSequence().flatMap { it.abilityDecls } +
            loadedModules.values.asSequence().mapNotNull { it.typedModule }.flatMap { it.abilityDecls } +
            ModuleTypeChecker.getAllCachedModules().values.asSequence()
                .mapNotNull { it.typedModule }.flatMap { it.abilityDecls }
        for (decl in decls) put(decl.hash, decl.operations.mapTo(HashSet()) { it.name })
    }

    val declIndex = mutableMapOf<Hash, TypedLetDecl>()
    for (d in wrapped.decls) {
        if (d is TypedLetDecl) declIndex[d.hash] = d
    }

    val preludeHashes = mutableSetOf<Hash>()
    preludeHashes.addAll(PrecompiledStdlib.preloweredFunctions.keys)
    preludeHashes.addAll(PrecompiledStdlib.stdlibDeclHashes)
    // #46 read-side: a dependency already compiled into its shared store is a
    // leaf here, exactly like stdlib — its hash in the leaf-set means the loop
    // below (and TypedASTCompiler) serves its IR instead of re-lowering it.
    // Empty unless the CLI populated PrecompiledDeps from the dep stores.
    preludeHashes.addAll(PrecompiledDeps.declHashes)

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
    // BUG B / #333: collect declared record-type field shapes from the UN-wrapped
    // modules (wrapTopLevel strips TypedTypeDecls from `wrapped`) so mono can
    // structurally recover an identity-less record receiver's nominal impl.
    val recordShapesForMono = HashMap<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>>()
    for (m in (listOf(typedModule) + importedModules + stdlibModulesForMono)) {
        for (d in m.decls) {
            if (d is com.mezze.language.typedast.TypedTypeDecl) {
                val def = d.definition
                if (def is com.mezze.language.typedast.TypedRecordDef) {
                    recordShapesForMono.putIfAbsent(d.hash, def.fields)
                }
            }
        }
    }
    // Declared GENERIC shapes by name — `Box 'a` → (['a], {v: 'a}) — so mono can
    // open an impl target like `Box 'a` against a receiver that arrived expanded
    // (`RecordType{v: P}`) and bind `'a = P` through the instance head.
    val declShapesForMono = HashMap<String, Pair<List<String>, com.mezze.language.ast.MezzeType>>()
    for (m in (listOf(typedModule) + importedModules + stdlibModulesForMono)) {
        for (d in m.decls) {
            if (d is com.mezze.language.typedast.TypedTypeDecl) {
                val params = d.typeParams.map { it.name }
                val identity = com.mezze.language.ast.TypeIdentity.declared(d.name, d.hash)
                when (val def = d.definition) {
                    is com.mezze.language.typedast.TypedRecordDef ->
                        declShapesForMono.putIfAbsent(d.name, params to com.mezze.language.ast.MezzeType.RecordType(def.fields, identity = identity))
                    is com.mezze.language.typedast.TypedVariantDef ->
                        declShapesForMono.putIfAbsent(d.name, params to com.mezze.language.ast.MezzeType.VariantType(def.constructors, identity = identity))
                    is com.mezze.language.typedast.TypedNewtypeDef, is com.mezze.language.typedast.TypedAliasDef -> Unit
                }
            }
        }
    }
    val monoResult = MonoDriver.run(
        entry = wrapped,
        imported = importedModules,
        resolutionOnly = stdlibModulesForMono,
        recordShapes = recordShapesForMono,
        declShapes = declShapesForMono,
    )

    val stdlibFns = (try { PrecompiledStdlib.preloweredFunctions } catch (_: Throwable) { emptyMap<Hash, FunctionIR>() }) +
        PrecompiledDeps.preloweredFunctions   // #46: serve cached dep IR, don't re-lower
    val stdlibDictMethods = try { PrecompiledStdlib.preloweredDictMethods } catch (_: Throwable) { emptyMap() }
    val compiler = TypedASTCompiler(
        abilityImpls = allAbilityImpls,
        stdlibHashes = preludeHashes,
        monoCallSiteRewrites = monoResult.bySiteLoc,
        monoStripBySiteLoc = monoResult.stripBySiteLoc,
        preloweredFunctions = stdlibFns,
        preloweredDictMethods = stdlibDictMethods,
        substitution = substitution,
        decodeOpBySite = decodeOpBySite,
        abilityOpNames = abilityOpNames,
    )
    compiler.setDeclIndex(declIndex)

    // #394: declared type NAME → resolved underlying MezzeType, so the
    // PrintType builder can render a nominal `TypeApplication(name)` receiver
    // (newtypes/records/variants/aliases). Collected from the un-wrapped
    // modules (wrapTopLevel strips TypedTypeDecls from `wrapped`).
    val printTypeDefs = HashMap<String, com.mezze.language.ast.MezzeType>()
    val typeDeclParams = HashMap<String, List<String>>()
    for (m in (listOf(typedModule) + importedModules + stdlibModulesForMono)) {
        for (d in m.decls) {
            if (d is com.mezze.language.typedast.TypedTypeDecl) {
                typeDeclParams.putIfAbsent(d.name, d.typeParams.map { it.name })
                val resolved: com.mezze.language.ast.MezzeType? = when (val def = d.definition) {
                    is com.mezze.language.typedast.TypedNewtypeDef ->
                        com.mezze.language.ast.MezzeType.NewtypeType(
                            d.name, def.constructor, def.wrappedType,
                            // #400: nominal Declared identity (module-distinct), mirroring
                            // the record/variant siblings below — never Anonymous.
                            identity = com.mezze.language.ast.TypeIdentity.declared(d.name, d.hash)
                        )
                    is com.mezze.language.typedast.TypedRecordDef ->
                        com.mezze.language.ast.MezzeType.RecordType(
                            def.fields,
                            rowVar = if (def.isOpen) com.mezze.language.ast.RowVar.fresh() else null,
                            identity = com.mezze.language.ast.TypeIdentity.declared(d.name, d.hash)
                        )
                    is com.mezze.language.typedast.TypedVariantDef ->
                        com.mezze.language.ast.MezzeType.VariantType(
                            def.constructors,
                            rowVar = if (def.isOpen) com.mezze.language.ast.RowVar.fresh() else null,
                            identity = com.mezze.language.ast.TypeIdentity.declared(d.name, d.hash)
                        )
                    is com.mezze.language.typedast.TypedAliasDef -> def.aliasedType
                }
                if (resolved != null) printTypeDefs.putIfAbsent(d.name, resolved)
            }
        }
    }
    compiler.setPrintTypeDefs(printTypeDefs, typeDeclParams)

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
