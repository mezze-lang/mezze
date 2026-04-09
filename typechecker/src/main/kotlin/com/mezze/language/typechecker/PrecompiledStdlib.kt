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

        // Compile each prelude module using TypedASTCompiler (single-pass, no old pipeline)
        val allAbilityImpls = allV2ForPrelower.values.flatMap { it.abilityImpls }
        for ((_, v2Module) in allV2ForPrelower) {
            try {
                val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
                    abilityImpls = allAbilityImpls
                )
                val result = compiler.compile(v2Module)
                for (fn in result.functions) {
                    preFunctions.putIfAbsent(fn.hash.hex, fn)
                }
                for (pr in result.performerRecords) {
                    val arr = mutableListOf(pr.performerHash, pr.effectHash)
                    for (i in pr.operationHashes.indices) {
                        arr.add(pr.operationHashes[i])
                        arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                    }
                    prePerformers.add(arr.toTypedArray())
                }
            } catch (_: Exception) {
                // Module couldn't be compiled — skip
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
