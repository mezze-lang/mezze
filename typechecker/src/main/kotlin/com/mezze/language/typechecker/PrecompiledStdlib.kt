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
    private const val STDLIB_SQLITE_RESOURCE = "stdlib.sqlite"

    /**
     * Optional MezzeStore set by CLI/LSP BEFORE first access to PrecompiledStdlib.
     * When set, the init block loads stdlib data from the store instead of
     * using direct JDBC. This is the clean path — no java.sql dependency needed.
     *
     * Set via: PrecompiledStdlib.setStore(store) before any typechecker access.
     */
    @Volatile
    private var store: com.mezze.language.store.MezzeStore? = null

    /**
     * Set the MezzeStore to use for stdlib loading.
     * MUST be called before first access to PrecompiledStdlib fields.
     */
    @JvmStatic
    fun setStore(store: com.mezze.language.store.MezzeStore) {
        this.store = store
    }

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
    var loadedModules: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>
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
    var stdlibDeclHashes: Set<Hash> = emptySet()
        private set

    /** Module hashes for prelude skip detection — extracted from stdlibTypedModules.keys */
    var stdlibModuleHashes: Set<Hash> = emptySet()
        private set

    /** Pre-lowered stdlib functions: hash → FunctionIR. Always available. */
    var preloweredFunctions: Map<Hash, com.mezze.language.truffleir.FunctionIR>
        private set

    /** Stdlib dependency graph: hash → set of hashes referenced in its body.
     *  Built eagerly at init from FunctionIR bodies.
     *  Used by PipelineRunner.collectReferencedHashes for BFS. */
    var stdlibDependencyGraph: Map<Hash, Set<Hash>> = emptyMap()
        private set

    /** Walk all stdlib FunctionIR bodies to build hash→referenced-hashes graph. */
    private fun buildDependencyGraph() {
        val graph = mutableMapOf<Hash, Set<Hash>>()
        for ((hash, fn) in preloweredFunctions) {
            val refs = mutableSetOf<Hash>()
            collectHashRefs(fn.body, refs)
            if (refs.isNotEmpty()) {
                graph[hash] = refs
            }
        }
        stdlibDependencyGraph = graph
    }

    /** Collect all hash references from a NodeIR tree. */
    private fun collectHashRefs(node: com.mezze.language.truffleir.NodeIR, refs: MutableSet<Hash>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> { refs.add(node.targetHash); node.args.forEach { collectHashRefs(it, refs) } }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> { refs.add(node.targetHash); node.partialFields.forEach { collectHashRefs(it.value, refs) } }
            is com.mezze.language.truffleir.MakeLambdaIR -> refs.add(node.functionHash)
            is com.mezze.language.truffleir.HandleIR -> {
                collectHashRefs(node.body, refs)
                for (h in node.handlers) { refs.add(h.handlerHash); refs.add(h.effectHash) }
            }
            is com.mezze.language.truffleir.PerformOpIR -> { refs.add(node.effectHash); collectHashRefs(node.arg, refs) }
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
            is com.mezze.language.truffleir.CallIndirectScalarIR -> { collectHashRefs(node.function, refs); node.args.forEach { collectHashRefs(it, refs) } }
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
            is com.mezze.language.truffleir.SpecializedCallIR -> { node.args.forEach { collectHashRefs(it, refs) } }
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
            // ReadGlobalByHash{,Fn}IR carry a Hash directly — feed it into the
            // dep graph. ReadScopeIR is a runtime-injected local (e.g. "scope"
            // from HandleNode); not a global, so no hash ref.
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> refs.add(node.hash)
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> refs.add(node.hash)
            is com.mezze.language.truffleir.ReadScopeIR -> {}
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
        val initStart = System.nanoTime()
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Try fast path: load everything from pre-compiled stdlib.sqlite resource.
        // Generated by Gradle compileStdlib task. Contains prelude context, FunctionIRs,
        // performer records, dependency graph, ability impls, decl/module hashes.
        // Zero parsing, zero typechecking — pure deserialization.
        //
        // Build-time bypass: StdlibPrecompiler sets
        // -Dmezze.stdlib.force-recompile=1 so we take the slow path and
        // keep stdlibTypedModules / loadedModules around for writing
        // proper v7 rows into the seed file (slice C).
        val forceRecompile = System.getProperty("mezze.stdlib.force-recompile") == "1"
        val fullCache = if (forceRecompile) null
            else tryLoadFullFromStore() ?: tryLoadFullFromSqlite()

        if (fullCache != null) {
            // Fast path: all data loaded from sqlite
            preludeImportContext = fullCache.importContext
            compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)
            preloweredFunctions = fullCache.functions
            preloweredPerformerRecords = fullCache.performers
            stdlibDependencyGraph = fullCache.dependencyGraph
            stdlibAbilityImpls = fullCache.abilityImpls
            stdlibDeclHashes = fullCache.declHashes
            stdlibModuleHashes = fullCache.moduleHashes
            stdlibTypedModules = emptyMap()
            loadedModules = emptyMap()

            // Still need moduleLoader and importResolver for user code imports
            moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
            val tc = TypeChecker()
            val mtc = ModuleTypeChecker.withContextAndResolver(tc.parentContext, moduleLoader.getResolver())
            importResolver = ImportResolver(moduleLoader, mtc)

            if (System.getenv("MEZZE_DEBUG") != null) {
                val ms = (System.nanoTime() - initStart) / 1_000_000
                System.err.println("[PrecompiledStdlib] Loaded from stdlib.sqlite: ${fullCache.functions.size} functions, ${fullCache.abilityImpls.size} ability impls (${ms}ms)")
            }
        } else {
            // Slow path: parse + typecheck + lower from source
            val tc = TypeChecker()
            moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
            val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                tc.parentContext,
                moduleLoader.getResolver()
            )
            importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

            // Load prelude (populates ModuleTypeChecker.globalCache + ModuleResolver.moduleCache)
            preludeImportContext = tc.loadPreludeModulesPublic(importResolver)
            loadedModules = tc.getCachedPreludeLoadedModules() ?: emptyMap()
            compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)

            // Build v2 modules
            val cached = tryLoadV2ModulesFromResource()
            if (cached != null) {
                stdlibTypedModules = cached
            } else {
                val v2 = mutableMapOf<Hash, com.mezze.language.typedast.TypedModule>()
                for ((_, v1Module) in loadedModules) {
                    val typedMod = v1Module.typedModule
                    if (typedMod != null) v2[typedMod.hash] = typedMod
                }
                stdlibTypedModules = v2
            }

            // Try loading FunctionIRs from store first, then fall back to direct JDBC
            val preloaded = tryLoadPreloweredFromStore() ?: tryLoadPreloweredFromSqlite()
            if (preloaded != null) {
                preloweredFunctions = preloaded.functions
                preloweredPerformerRecords = preloaded.performers
                stdlibDependencyGraph = preloaded.dependencyGraph
            } else {
                // Full compile from TypedModules
                val allModulesForPrelower = HashMap<Hash, com.mezze.language.typedast.TypedModule>(stdlibTypedModules)
                for ((_, cachedMod) in ModuleTypeChecker.getAllCachedModules()) {
                    val stdlibModule = cachedMod.typedModule
                    if (stdlibModule != null && stdlibModule.hash !in allModulesForPrelower) {
                        allModulesForPrelower[stdlibModule.hash] = stdlibModule
                    }
                }

                val preFunctions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
                val prePerformers = mutableListOf<Array<String>>()
                val allAbilityImpls = allModulesForPrelower.values.flatMap { it.abilityImpls }
                for ((modHash, typedMod) in allModulesForPrelower) {
                    try {
                        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(abilityImpls = allAbilityImpls)
                        val result = compiler.compile(typedMod)
                        for (pr in result.performerRecords) {
                            val arr = mutableListOf(pr.performerHash, pr.effectHash)
                            for (i in pr.operationHashes.indices) {
                                arr.add(pr.operationHashes[i])
                                arr.add(pr.operationNames.getOrElse(i) { "op_$i" })
                            }
                            prePerformers.add(arr.toTypedArray())
                        }
                        for (fn in result.functions) { preFunctions.putIfAbsent(fn.hash, fn) }
                    } catch (e: Exception) {
                        // Stdlib lowering failures are not "best-effort": they mean a
                        // stdlib function won't be available at runtime, manifesting
                        // as bizarre missing-binding errors in user code. Emit a loud
                        // diagnostic. Don't throw — the prelude init must still
                        // produce *some* state for downstream consumers (#308).
                        System.err.println(
                            "[PrecompiledStdlib] WARNING: failed to lower stdlib module " +
                            "${modHash.hex.take(8)} (${typedMod.path}): " +
                            "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
                preloweredFunctions = preFunctions
                preloweredPerformerRecords = prePerformers
                buildDependencyGraph()
            }

            // Extract metadata from TypedModules
            stdlibAbilityImpls = stdlibTypedModules.values.flatMap { it.abilityImpls }
            val declHashes = mutableSetOf<Hash>()
            for ((_, stdlibModule) in stdlibTypedModules) {
                for (d in stdlibModule.decls) {
                    if (d is com.mezze.language.typedast.TypedLetDecl) declHashes.add(d.hash)
                }
            }
            stdlibDeclHashes = declHashes
            stdlibModuleHashes = stdlibTypedModules.keys.toSet()

            // Keep loadedModules (v1) and stdlibTypedModules (v2) around so
            // build-time tooling (StdlibPrecompiler / slice C seeding) can
            // walk decls and emit proper v7 rows (filePath + sourceHash live
            // on the v1 wrapper). Runtime memory cost is bounded by stdlib
            // size (~8 MB); cheaper than re-parsing + re-typechecking.

            if (System.getenv("MEZZE_DEBUG") != null) {
                val ms = (System.nanoTime() - initStart) / 1_000_000
                System.err.println("[PrecompiledStdlib] Compiled from source: ${preloweredFunctions.size} functions (${ms}ms)")
            }
        }
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
        } catch (e: Exception) {
            // Resource is present but couldn't be deserialized. Almost always
            // means a serialized-shape mismatch between the build that
            // produced stdlib.cbor and the build now running — surface it
            // under MEZZE_DEBUG to expedite diagnosis. Falling through to
            // slow path is the right runtime behavior; the warning is
            // forensic. (#308)
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] $STDLIB_MODULES_RESOURCE present but failed to deserialize: " +
                    "${e.javaClass.simpleName}: ${e.message}. Falling back to slow path."
                )
            }
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

    // ====================================================================
    // STORE-BASED LOADING — sole path. Both externally-provided stores
    // (CLI/LSP via setStore) and the JAR-resource fallback flow through
    // these methods.
    // ====================================================================

    /**
     * Try loading everything from the MezzeStore. Returns null if no store
     * is set or if any required metadata is missing.
     */
    /**
     * Read the persisted dependency graph from `deps:<hash>` metadata keys.
     * Falls back to walking FunctionIR bodies if the store has no `deps:*`
     * entries — preserves correctness against older stdlib.sqlite shapes
     * where the graph wasn't serialized.
     */
    private fun loadDepGraphFromStore(
        s: com.mezze.language.store.MezzeStore,
        functions: Map<Hash, com.mezze.language.truffleir.FunctionIR>
    ): Map<Hash, Set<Hash>> {
        val graph = mutableMapOf<Hash, Set<Hash>>()
        val depEntries = s.getMetadataByPrefix("deps:")
        if (depEntries.isNotEmpty()) {
            for ((key, value) in depEntries) {
                if (value.isEmpty()) continue
                val hash = Hash(key.removePrefix("deps:"))
                graph[hash] = value.split(";").map { Hash(it) }.toSet()
            }
            return graph
        }
        for ((hash, fn) in functions) {
            val refs = mutableSetOf<Hash>()
            collectHashRefs(fn.body, refs)
            if (refs.isNotEmpty()) graph[hash] = refs
        }
        return graph
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadFullFromStore(): FullCacheData? {
        val s = store ?: return null
        return try {
            // Read metadata
            val version = s.getMetadata("stdlib_version") ?: return null
            if (version != "2") return null

            val preludeB64 = s.getMetadata("prelude_import_context") ?: return null
            val preludeBytes = java.util.Base64.getDecoder().decode(preludeB64)
            val importContext: ModuleImportContext = cbor.decodeFromByteArray(preludeBytes)

            val implB64 = s.getMetadata("stdlib_ability_impls")
            val abilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl> = if (implB64 != null) {
                cbor.decodeFromByteArray(java.util.Base64.getDecoder().decode(implB64))
            } else emptyList()

            val declHashStr = s.getMetadata("stdlib_decl_hashes") ?: ""
            val declHashes: Set<Hash> = if (declHashStr.isNotEmpty()) {
                declHashStr.split(";").map { Hash(it) }.toSet()
            } else emptySet()

            val modHashStr = s.getMetadata("stdlib_module_hashes") ?: ""
            val moduleHashes = if (modHashStr.isNotEmpty()) modHashStr.split(";").map { Hash(it) }.toSet() else emptySet()

            // Load FunctionIRs from blobs table via store. We want EVERY
            // blob the store has, not just declHashes — internal lambdas,
            // performer factories, and synthetic helpers all live as blobs
            // without a corresponding decl. Loading only declHashes leaves
            // gaps that surface at runtime as "expected MezzeFunction, got
            // null" when a CallDirectScalarNode tries to dispatch.
            val functions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
            val decodeFailures = mutableListOf<Pair<Hash, String>>()
            // getAllBlobHashes is cheap for sqlite-backed stores (single
            // SELECT). Falls back to declHashes ∪ moduleHashes for stores
            // that don't implement it. Loading every blob (not just decl
            // hashes) is required — internal lambdas, performer factories,
            // and synthetic helpers all live as blobs without a
            // corresponding decl. Loading only declHashes leaves gaps
            // that surface at runtime as "expected MezzeFunction got null"
            // when CallDirectScalarNode tries to dispatch.
            val allHashes = s.getAllBlobHashes().ifEmpty { (declHashes + moduleHashes).toList() }
            for (h in allHashes) {
                try {
                    val irBytes = s.getIR(h) ?: continue
                    functions[h] = cbor.decodeFromByteArray(irBytes)
                } catch (e: Exception) {
                    decodeFailures.add(h to "${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (decodeFailures.isNotEmpty() && System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] ${decodeFailures.size} FunctionIR blob(s) failed to decode " +
                    "from stdlib store. Examples: " +
                    decodeFailures.take(3).joinToString(", ") { (h, msg) -> "${h.hex.take(8)} ($msg)" }
                )
            }

            // Load performers
            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

            // Load persisted dependency graph (deps:<hash> → semicolon-delimited refs).
            val depGraph = loadDepGraphFromStore(s, functions)

            if (functions.isNotEmpty()) {
                FullCacheData(importContext, functions, performers, depGraph, abilityImpls, declHashes, moduleHashes)
            } else null
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[PrecompiledStdlib] Failed to load from store: ${e.message}")
            }
            null
        }
    }

    /**
     * Try loading pre-lowered FunctionIRs from the store.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadPreloweredFromStore(): PreloweredData? {
        val s = store ?: return null
        return try {
            val functions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
            var modDecodeFailures = 0
            var irDecodeFailures = 0
            var lastError: String? = null

            // Load all stdlib modules and get their decl hashes for IR lookup
            for (mod in s.getAllModules()) {
                if (!com.mezze.language.ast.ModulePathConventions.isStdlib(mod.path)) continue
                val typedBlob = s.getTypedModule(mod.path, mod.sourceHash) ?: continue
                try {
                    val typedModule: com.mezze.language.typedast.TypedModule = cbor.decodeFromByteArray(typedBlob)
                    for (decl in typedModule.decls) {
                        if (decl is com.mezze.language.typedast.TypedLetDecl) {
                            val irBytes = s.getIR(decl.hash) ?: continue
                            try {
                                functions[decl.hash] = cbor.decodeFromByteArray(irBytes)
                            } catch (e: Exception) {
                                irDecodeFailures++
                                lastError = "IR ${decl.hash.hex.take(8)}: ${e.javaClass.simpleName}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    modDecodeFailures++
                    lastError = "module ${mod.path}: ${e.javaClass.simpleName}"
                }
            }

            if ((modDecodeFailures > 0 || irDecodeFailures > 0) &&
                System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] tryLoadPreloweredFromStore: " +
                    "$modDecodeFailures module(s) and $irDecodeFailures IR(s) failed to decode. " +
                    "Last: $lastError"
                )
            }

            // Load performers
            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

            // Load persisted dependency graph (with body-walk fallback for older stores).
            val depGraph = loadDepGraphFromStore(s, functions)

            if (functions.isNotEmpty()) PreloweredData(functions, performers, depGraph) else null
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] tryLoadPreloweredFromStore failed: " +
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            null
        }
    }

    // ====================================================================
    // RESOURCE-BASED LOADING — opens stdlib.sqlite from JAR and routes through
    // MezzeStore. Used when no MezzeStore is set externally (e.g. tests).
    // ====================================================================

    /**
     * Open the bundled `stdlib.sqlite` resource as a read-only [MezzeStore].
     *
     * Goes through [com.mezze.language.store.MezzeStoreOpener.current] —
     * the entry point (CLI / LSP / test harness) registers an opener
     * during bootstrap (typically `SQLiteMezzeStore::readOnly`). Without
     * a registered opener, this returns null and the slow compile-from-
     * source path takes over.
     *
     * This used to bridge the `:typechecker` → `:store-sqlite` dep cycle
     * with reflection (`Class.forName(...).getMethod("readOnly", ...)`).
     * The reflective lookup silently failed under native-image (no
     * reflect-config registration), causing native binaries to recompile
     * stdlib from source on every startup and producing a different hash
     * set than JVM runs (#168). The SPI approach is native-image friendly
     * and lets each surface choose its own backing store.
     */
    private fun openStdlibResourceAsStore(): com.mezze.language.store.MezzeStore? {
        val opener = com.mezze.language.store.MezzeStoreOpener.current() ?: return null
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(STDLIB_SQLITE_RESOURCE) ?: return null
            val tempFile = java.io.File.createTempFile("mezze-stdlib", ".sqlite")
            tempFile.deleteOnExit()
            tempFile.writeBytes(stream.readAllBytes())
            opener.openReadOnly(tempFile)
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] Failed to open stdlib.sqlite resource as store: " +
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            null
        }
    }

    /**
     * Resource-backed counterpart of [tryLoadFullFromStore]. Opens the bundled
     * stdlib.sqlite as a transient SQLiteMezzeStore, sets it as the active
     * store for the duration of the load, then delegates. No raw JDBC.
     */
    private fun tryLoadFullFromSqlite(): FullCacheData? {
        val s = openStdlibResourceAsStore() ?: return null
        return try {
            val previous = store
            store = s
            try {
                tryLoadFullFromStore()
            } finally {
                store = previous
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /**
     * Resource-backed counterpart of [tryLoadPreloweredFromStore].
     */
    private fun tryLoadPreloweredFromSqlite(): PreloweredData? {
        val s = openStdlibResourceAsStore() ?: return null
        return try {
            val previous = store
            store = s
            try {
                tryLoadPreloweredFromStore()
            } finally {
                store = previous
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private data class FullCacheData(
        val importContext: ModuleImportContext,
        val functions: Map<Hash, com.mezze.language.truffleir.FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>,
        val abilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl>,
        val declHashes: Set<Hash>,
        val moduleHashes: Set<Hash>
    )

    private data class PreloweredData(
        val functions: Map<Hash, com.mezze.language.truffleir.FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>
    )
}
