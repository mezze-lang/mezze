package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.*
import com.mezze.language.typedast.Hash
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.nio.file.Paths

/**
 * Singleton that precomputes all stdlib/prelude data once at first access.
 *
 * Tries to load pre-compiled v2 TypedModules from a classpath resource first.
 * Falls back to parsing and typechecking from source if not available.
 */
object PrecompiledStdlib {

    private const val V2_MODULES_RESOURCE = "mezze-stdlib-v2modules.cbor"

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Compiled prelude interface — immutable, safe to reuse across compilations */
    val compiledPrelude: CompiledModuleInterface

    /** Raw prelude import context (for merging with user imports) */
    val preludeImportContext: ModuleImportContext

    /** Loaded v1 TypedModules for all prelude modules */
    val loadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>

    /** Pre-converted v2 TypedModules for monomorphizer */
    val v2Modules: Map<Hash, com.mezze.language.typedast.TypedModule>

    /** Pre-lowered stdlib functions: hash → FunctionIR.
     *  Each stdlib module is mono→resolve→lower'd once at startup.
     *  Entry modules reference these by hash — no re-monomorphization needed. */
    val preloweredFunctions: Map<String, com.mezze.language.truffleir.FunctionIR>

    /** Pre-lowered performer records from all stdlib modules */
    val preloweredPerformerRecords: List<Array<String>>

    /** Cached ModuleLoader — avoids per-test filesystem traversal (findProjectRoot) */
    val moduleLoader: ModuleLoader

    /** Cached ImportResolver — reuses moduleLoader and moduleTypeChecker */
    val importResolver: ImportResolver

    init {
        val tc = TypeChecker()
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
        val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
            tc.parentContext,
            moduleLoader.getResolver()
        )
        importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

        // Load prelude (also populates ModuleTypeChecker.globalCache + ModuleResolver.moduleCache)
        preludeImportContext = tc.loadPreludeModulesPublic(importResolver)
        loadedModules = tc.getCachedPreludeLoadedModules() ?: emptyMap()

        // Build the compiled interface from the import context
        compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)

        // Build v2 modules from TypedAstBuilder output (stored during type-checking)
        val cached = tryLoadV2ModulesFromResource()
        if (cached != null) {
            v2Modules = cached
        } else {
            val v2 = mutableMapOf<Hash, com.mezze.language.typedast.TypedModule>()
            for ((_, v1Module) in loadedModules) {
                val v2Module = v1Module.v2TypedModule
                if (v2Module != null) {
                    v2[v2Module.hash] = v2Module
                }
            }
            v2Modules = v2
        }

        // Pre-lower all stdlib modules: mono → resolve → lower once at startup.
        // Build full module map including transitive deps from ModuleTypeChecker cache.
        val allV2ForPrelower = HashMap<Hash, com.mezze.language.typedast.TypedModule>(v2Modules)
        for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
            val v2m = cached.v2TypedModule
            if (v2m != null && v2m.hash !in allV2ForPrelower) {
                allV2ForPrelower[v2m.hash] = v2m
            }
        }

        val preFunctions = mutableMapOf<String, com.mezze.language.truffleir.FunctionIR>()
        val prePerformers = mutableListOf<Array<String>>()

        // Phase 1: Monomorphize each module (traces from entry point)
        for ((_, v2Module) in allV2ForPrelower) {
            try {
                val mono = com.mezze.language.typechecker.mono.Monomorphizer()
                    .monomorphize(v2Module, allV2ForPrelower)
                val resolved = com.mezze.language.typechecker.mono.Resolver().resolve(mono)
                val lowered = com.mezze.language.typechecker.mono.Lowerer().lower(resolved)
                for (fn in lowered.functions) {
                    preFunctions.putIfAbsent(fn.hash.hex, fn)
                }
                for (pr in lowered.performerRecords) {
                    val arr = mutableListOf(pr.performerHash, pr.effectHash)
                    for (i in pr.operationHashes.indices) {
                        arr.add(pr.operationHashes[i])
                        arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                    }
                    prePerformers.add(arr.toTypedArray())
                }
            } catch (_: Exception) {
                // Module couldn't be lowered — skip
            }
        }

        // Phase 2: Eagerly compile ALL ability operations from all modules.
        // These are referenced by tryResolveMethodCall when user code calls methods
        // like result.map, option.unwrap_or, list.length, etc.
        // The monomorphizer traces from entry points, but ability operations are
        // only reached when user code calls them — so we compile them ALL here.
        val allAbilityImpls = allV2ForPrelower.values.flatMap { it.abilityImpls }
        val allGlobalDecls = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedLetDecl>()
        for (m in allV2ForPrelower.values) {
            for (d in m.decls) {
                if (d is com.mezze.language.typedast.TypedLetDecl) allGlobalDecls[d.hash] = d
            }
        }
        for (impl in allAbilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = com.mezze.language.typechecker.mono.Monomorphizer().computeOpHashPublic(impl.hash, opName)
                if (opHash.hex in preFunctions) continue
                try {
                    val monoizer = com.mezze.language.typechecker.mono.Monomorphizer()
                    // Unwrap lambda: convert the body, not the lambda itself
                    val (opParams, monoBody) = if (opBody is com.mezze.language.typedast.TypedLambda) {
                        val params = opBody.params.map { p -> com.mezze.language.monoast.MonoParam(p.name, p.type, p.loc) }
                        params to monoizer.convertExprPublic(opBody.body, allGlobalDecls)
                    } else {
                        emptyList<com.mezze.language.monoast.MonoParam>() to monoizer.convertExprPublic(opBody, allGlobalDecls)
                    }
                    val monoFn = com.mezze.language.monoast.MonoFunction(
                        hash = opHash,
                        name = opName,
                        originalName = opName,
                        originalHash = opHash,
                        params = opParams,
                        captures = emptyList(),
                        body = monoBody,
                        type = opBody.type,
                        isPublic = false,
                        loc = opBody.loc
                    )
                    val resolver = com.mezze.language.typechecker.mono.Resolver()
                    val monoModule = com.mezze.language.monoast.MonoModule(
                        path = "<ability-op>", hash = opHash,
                        functions = listOf(monoFn),
                        effectDecls = emptyList(), performerDecls = emptyList()
                    )
                    val resolved = resolver.resolve(monoModule)
                    val lowered = com.mezze.language.typechecker.mono.Lowerer().lower(resolved)
                    for (fn in lowered.functions) {
                        preFunctions.putIfAbsent(fn.hash.hex, fn)
                    }
                } catch (_: Exception) {
                    // Operation couldn't be compiled — skip
                }
            }
        }

        preloweredFunctions = preFunctions
        preloweredPerformerRecords = prePerformers

    }

    /**
     * Try to load pre-compiled v2 modules from a classpath resource.
     * Returns null if the resource doesn't exist or can't be deserialized.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadV2ModulesFromResource(): Map<Hash, com.mezze.language.typedast.TypedModule>? {
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(V2_MODULES_RESOURCE) ?: return null
            val bytes = stream.readAllBytes()
            val moduleList: List<com.mezze.language.typedast.TypedModule> = cbor.decodeFromByteArray(bytes)
            moduleList.associateBy { it.hash }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Serialize current v2Modules to CBOR bytes.
     * Called by build tools to produce the classpath resource.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun serializeV2Modules(): ByteArray {
        val moduleList = v2Modules.values.toList()
        return cbor.encodeToByteArray(moduleList)
    }
}
