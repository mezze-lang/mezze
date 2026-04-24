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

    /** @deprecated v1 TypedModules — cleared after init to free ~500MB. Use v2Modules instead. */
    @Deprecated("Use v2Modules instead — v1 modules cleared after init")
    var loadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>
        private set

    /** Pre-converted v2 TypedModules — full modules during init, stripped after.
     *  After init, only abilityImpls and decl hashes are retained (via stdlibAbilityImpls + stdlibDeclHashes). */
    var v2Modules: Map<Hash, com.mezze.language.typedast.TypedModule>
        private set

    /** Ability impls from ALL stdlib v2 modules — extracted during init, kept permanently.
     *  Used by TypeChecker to build allAbilityImpls per test. */
    var stdlibAbilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl> = emptyList()
        private set

    /** Decl hashes from ALL stdlib v2 modules — extracted during init, kept permanently.
     *  Used by TypeChecker to build preludeHashes set. */
    var stdlibDeclHashes: Set<String> = emptySet()
        private set

    /** Module hashes for prelude skip detection — extracted from v2Modules.keys */
    var stdlibModuleHashes: Set<Hash> = emptySet()
        private set

    /** Pre-lowered stdlib functions: hash → FunctionIR.
     *  Cleared after CallTargets are compiled to free ~100MB of IR data. */
    var preloweredFunctions: Map<String, com.mezze.language.truffleir.FunctionIR>
        private set

    /** Just the hash keys — kept after FunctionIRs are cleared, for TypeChecker prelude detection. */
    var preloweredFunctionHashes: Set<String> = emptySet()
        private set

    /** Clear FunctionIR data after compilation — frees significant memory.
     *  Keeps only the hash set for TypeChecker prelude detection. */
    fun clearPreloweredFunctions() {
        preloweredFunctionHashes = preloweredFunctions.keys.toSet()
        preloweredFunctions = emptyMap()
    }

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

        // Compile performer records eagerly (small, needed for all tests).
        // FunctionIRs compiled LAZILY per-module when first referenced — saves ~500MB.
        val allAbilityImpls = allV2ForPrelower.values.flatMap { it.abilityImpls }
        for ((_, v2Module) in allV2ForPrelower) {
            try {
                val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
                    abilityImpls = allAbilityImpls
                )
                val result = compiler.compile(v2Module)
                // Only keep performer records eagerly; FunctionIRs compiled on-demand
                for (pr in result.performerRecords) {
                    val arr = mutableListOf(pr.performerHash, pr.effectHash)
                    for (i in pr.operationHashes.indices) {
                        arr.add(pr.operationHashes[i])
                        arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                    }
                    prePerformers.add(arr.toTypedArray())
                }
                // Build hash→FunctionIR map for lazy compilation
                for (fn in result.functions) {
                    preFunctions.putIfAbsent(fn.hash.hex, fn)
                }
            } catch (_: Exception) {
                // Compilation errors during stdlib precompilation are non-fatal;
                // the module will be compiled on demand if needed.
            }
        }

        preloweredFunctions = preFunctions
        preloweredPerformerRecords = prePerformers

        // Extract lightweight metadata before clearing heavy data.
        stdlibAbilityImpls = v2Modules.values.flatMap { it.abilityImpls }
        val hashes = mutableSetOf<String>()
        for ((_, v2m) in v2Modules) {
            for (d in v2m.decls) {
                if (d is com.mezze.language.typedast.TypedLetDecl) hashes.add(d.hash.hex)
            }
        }
        stdlibDeclHashes = hashes
        stdlibModuleHashes = v2Modules.keys.toSet()

        // Free v1 data + strip v2 modules. Keep preloweredFunctions for lazy CallTarget compilation.
        loadedModules = emptyMap()
        v2Modules = emptyMap()  // No longer needed — FunctionIRs in preloweredFunctions
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
