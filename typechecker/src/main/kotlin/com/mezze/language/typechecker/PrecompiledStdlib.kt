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

    private const val STDLIB_MODULES_RESOURCE = "mezze-stdlib-modules.cbor"

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Compiled prelude interface — immutable, safe to reuse across compilations */
    val compiledPrelude: CompiledModuleInterface

    /** Raw prelude import context (for merging with user imports) */
    val preludeImportContext: ModuleImportContext

    /** v1 TypedModules — used during init to build stdlibTypedModules, cleared after. */
    var loadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule>
        private set

    /** Stdlib TypedModules — full modules during init, cleared after metadata extraction. */
    var stdlibTypedModules: Map<Hash, com.mezze.language.typedast.TypedModule>
        private set

    /** Ability impls from ALL stdlib modules — extracted during init, kept permanently.
     *  Used by TypeChecker to build allAbilityImpls per test. */
    var stdlibAbilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl> = emptyList()
        private set

    /** Decl hashes from ALL stdlib v2 modules — extracted during init, kept permanently.
     *  Used by TypeChecker to build preludeHashes set. */
    var stdlibDeclHashes: Set<String> = emptySet()
        private set

    /** Module hashes for prelude skip detection — extracted from stdlibTypedModules.keys */
    var stdlibModuleHashes: Set<Hash> = emptySet()
        private set

    /** Pre-lowered stdlib functions: hash → FunctionIR. Always available. */
    var preloweredFunctions: Map<String, com.mezze.language.truffleir.FunctionIR>
        private set

    /** Stdlib dependency graph: hash → set of hashes referenced in its body.
     *  Built eagerly at init from FunctionIR bodies.
     *  Used by PipelineRunner.collectReferencedHashes for BFS. */
    var stdlibDependencyGraph: Map<String, Set<String>> = emptyMap()
        private set

    /** Walk all stdlib FunctionIR bodies to build hash→referenced-hashes graph. */
    private fun buildDependencyGraph() {
        val graph = mutableMapOf<String, Set<String>>()
        for ((hash, fn) in preloweredFunctions) {
            val refs = mutableSetOf<String>()
            collectHashRefs(fn.body, refs)
            if (refs.isNotEmpty()) {
                graph[hash] = refs
            }
        }
        stdlibDependencyGraph = graph
    }

    /** Collect all hash references from a NodeIR tree. */
    private fun collectHashRefs(node: com.mezze.language.truffleir.NodeIR, refs: MutableSet<String>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> { refs.add(node.targetHash.hex); collectHashRefs(node.arg, refs) }
            is com.mezze.language.truffleir.MakeLambdaIR -> refs.add(node.functionHash.hex)
            is com.mezze.language.truffleir.HandleIR -> {
                collectHashRefs(node.body, refs)
                for (h in node.handlers) { refs.add(h.handlerHash.hex); refs.add(h.effectHash.hex) }
            }
            is com.mezze.language.truffleir.PerformOpIR -> { refs.add(node.effectHash.hex); collectHashRefs(node.arg, refs) }
            is com.mezze.language.truffleir.BlockIR -> {
                for (s in node.stmts) when (s) {
                    is com.mezze.language.truffleir.StmtIR.Let -> collectHashRefs(s.value, refs)
                    is com.mezze.language.truffleir.StmtIR.Expr -> collectHashRefs(s.node, refs)
                }
                collectHashRefs(node.result, refs)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectHashRefs(node.arg, refs)
            is com.mezze.language.truffleir.CallNativeIR -> collectHashRefs(node.arg, refs)
            is com.mezze.language.truffleir.CallIndirectIR -> { collectHashRefs(node.function, refs); collectHashRefs(node.arg, refs) }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectHashRefs(it.value, refs) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectHashRefs(node.record, refs)
            is com.mezze.language.truffleir.RecordUpdateIR -> { collectHashRefs(node.record, refs); node.updates.forEach { collectHashRefs(it.value, refs) } }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectHashRefs(it, refs) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectHashRefs(node.scrutinee, refs)
                for (c in node.cases) { collectHashRefs(c.body, refs); c.guard?.let { collectHashRefs(it, refs) } }
            }
            is com.mezze.language.truffleir.IfIR -> { collectHashRefs(node.cond, refs); collectHashRefs(node.thenBr, refs); collectHashRefs(node.elseBr, refs) }
            is com.mezze.language.truffleir.WriteLocalIR -> collectHashRefs(node.value, refs)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> { collectHashRefs(node.left, refs); collectHashRefs(node.right, refs) }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (p in node.parts) if (p is com.mezze.language.truffleir.StringPartIR.Expr) collectHashRefs(p.node, refs)
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (e in node.elements) when (e) {
                    is com.mezze.language.truffleir.SeqElementIR.Regular -> collectHashRefs(e.node, refs)
                    is com.mezze.language.truffleir.SeqElementIR.Spread -> collectHashRefs(e.node, refs)
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> { collectHashRefs(node.seq, refs); collectHashRefs(node.index, refs) }
            is com.mezze.language.truffleir.DictMethodCallIR -> collectHashRefs(node.arg, refs)
            is com.mezze.language.truffleir.ReadContextIR -> refs.add(node.name)
            is com.mezze.language.truffleir.ReadContextFnIR -> refs.add(node.name)
            else -> {} // leaf nodes
        }
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
            stdlibTypedModules = cached
        } else {
            val v2 = mutableMapOf<Hash, com.mezze.language.typedast.TypedModule>()
            for ((_, v1Module) in loadedModules) {
                val typedMod = v1Module.typedModule
                if (typedMod != null) {
                    v2[typedMod.hash] = typedMod
                }
            }
            stdlibTypedModules = v2
        }

        // Pre-lower all stdlib modules: mono → resolve → lower once at startup.
        // Build full module map including transitive deps from ModuleTypeChecker cache.
        val allModulesForPrelower = HashMap<Hash, com.mezze.language.typedast.TypedModule>(stdlibTypedModules)
        for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
            val stdlibModule = cached.typedModule
            if (stdlibModule != null && stdlibModule.hash !in allModulesForPrelower) {
                allModulesForPrelower[stdlibModule.hash] = stdlibModule
            }
        }

        val preFunctions = mutableMapOf<String, com.mezze.language.truffleir.FunctionIR>()
        val prePerformers = mutableListOf<Array<String>>()

        // Compile performer records eagerly (small, needed for all tests).
        // FunctionIRs compiled LAZILY per-module when first referenced — saves ~500MB.
        val allAbilityImpls = allModulesForPrelower.values.flatMap { it.abilityImpls }
        for ((_, typedMod) in allModulesForPrelower) {
            try {
                val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
                    abilityImpls = allAbilityImpls
                )
                val result = compiler.compile(typedMod)
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
        buildDependencyGraph()

        // Extract lightweight metadata.
        stdlibAbilityImpls = stdlibTypedModules.values.flatMap { it.abilityImpls }
        val hashes = mutableSetOf<String>()
        for ((_, stdlibModule) in stdlibTypedModules) {
            for (d in stdlibModule.decls) {
                if (d is com.mezze.language.typedast.TypedLetDecl) hashes.add(d.hash.hex)
            }
        }
        stdlibDeclHashes = hashes
        stdlibModuleHashes = stdlibTypedModules.keys.toSet()

        // Free v1 data + strip v2 modules. Keep preloweredFunctions for lazy CallTarget compilation.
        loadedModules = emptyMap()
        stdlibTypedModules = emptyMap()  // No longer needed — FunctionIRs in preloweredFunctions
    }

    /**
     * Try to load pre-compiled v2 modules from a classpath resource.
     * Returns null if the resource doesn't exist or can't be deserialized.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadV2ModulesFromResource(): Map<Hash, com.mezze.language.typedast.TypedModule>? {
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(STDLIB_MODULES_RESOURCE) ?: return null
            val bytes = stream.readAllBytes()
            val moduleList: List<com.mezze.language.typedast.TypedModule> = cbor.decodeFromByteArray(bytes)
            moduleList.associateBy { it.hash }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Serialize current stdlibTypedModules to CBOR bytes.
     * Called by build tools to produce the classpath resource.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun serializeV2Modules(): ByteArray {
        val moduleList = stdlibTypedModules.values.toList()
        return cbor.encodeToByteArray(moduleList)
    }
}
