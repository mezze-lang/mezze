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
            is com.mezze.language.truffleir.CallDirectIR -> { refs.add(node.targetHash); collectHashRefs(node.arg, refs) }
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
                for ((_, typedMod) in allModulesForPrelower) {
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
                    } catch (_: Exception) {}
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

    // ====================================================================
    // STORE-BASED LOADING (clean path — no java.sql dependency)
    // ====================================================================

    /**
     * Try loading everything from the MezzeStore (set by CLI/LSP).
     * The store already has stdlib.sqlite ATTACHed, so we can read
     * via the standard MezzeStore interface. Returns null if no store
     * was set or if loading fails.
     */
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

            // Load FunctionIRs from blobs table via store
            val functions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
            // We need all blob hashes to iterate — use the module's known decl hashes
            // plus any reachable via edges. Since we have declHashes, load their IR.
            for (declHash in declHashes) {
                try {
                    val irBytes = s.getIR(declHash) ?: continue
                    functions[declHash] = cbor.decodeFromByteArray(irBytes)
                } catch (_: Exception) {}
            }

            // Also load any blobs not in declHashes (performer functions, etc.)
            // by iterating module hashes and their edges
            for (mHash in moduleHashes) {
                try {
                    val irBytes = s.getIR(mHash) ?: continue
                    functions[mHash] = cbor.decodeFromByteArray(irBytes)
                } catch (_: Exception) {}
            }

            // Load performers
            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

            // Load dependency graph
            val depGraph = mutableMapOf<Hash, Set<Hash>>()
            // Dependency graph is stored as metadata keys "deps:<hash>"
            // We can't enumerate metadata keys through MezzeStore interface,
            // so rebuild from FunctionIR bodies instead
            for ((hash, fn) in functions) {
                val refs = mutableSetOf<Hash>()
                collectHashRefs(fn.body, refs)
                if (refs.isNotEmpty()) depGraph[hash] = refs
            }

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
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }

            // Load performers
            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

            // Build dependency graph from function bodies
            val depGraph = mutableMapOf<Hash, Set<Hash>>()
            for ((hash, fn) in functions) {
                val refs = mutableSetOf<Hash>()
                collectHashRefs(fn.body, refs)
                if (refs.isNotEmpty()) depGraph[hash] = refs
            }

            if (functions.isNotEmpty()) PreloweredData(functions, performers, depGraph) else null
        } catch (_: Exception) { null }
    }

    // ====================================================================
    // JDBC-BASED LOADING (legacy — used when no MezzeStore is set)
    // ====================================================================

    private data class FullCacheData(
        val importContext: ModuleImportContext,
        val functions: Map<Hash, com.mezze.language.truffleir.FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>,
        val abilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl>,
        val declHashes: Set<Hash>,
        val moduleHashes: Set<Hash>
    )

    /**
     * Try loading everything from the bundled `stdlib.sqlite` JAR
     * resource (has prelude context). The legacy filesystem path
     * `~/.mezze/cache/stdlib.sqlite` is no longer consulted — the
     * JAR is the single source of truth.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadFullFromSqlite(): FullCacheData? {
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(STDLIB_SQLITE_RESOURCE) ?: return null
            val tempFile = java.io.File.createTempFile("mezze-stdlib-full", ".sqlite")
            tempFile.deleteOnExit()
            tempFile.writeBytes(stream.readAllBytes())
            val sqlitePath = tempFile.absolutePath

            try {
                val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$sqlitePath")

                // Check version — v2 has prelude context
                val version = conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'stdlib_version'").use { rs ->
                        if (rs.next()) rs.getString("value") else null
                    }
                }
                if (version != "2") {
                    conn.close()
                    return null
                }

                // Load prelude import context
                val preludeB64 = conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'prelude_import_context'").use { rs ->
                        if (rs.next()) rs.getString("value") else null
                    }
                } ?: run { conn.close(); return null }

                val preludeBytes = java.util.Base64.getDecoder().decode(preludeB64)
                val importContext: ModuleImportContext = cbor.decodeFromByteArray(preludeBytes)

                // Load ability impls
                val implB64 = conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'stdlib_ability_impls'").use { rs ->
                        if (rs.next()) rs.getString("value") else null
                    }
                }
                val abilityImpls: List<com.mezze.language.typedast.TypedAbilityImpl> = if (implB64 != null) {
                    cbor.decodeFromByteArray(java.util.Base64.getDecoder().decode(implB64))
                } else emptyList()

                // Load decl hashes
                val declHashStr = conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'stdlib_decl_hashes'").use { rs ->
                        if (rs.next()) rs.getString("value") else ""
                    }
                }
                val declHashes: Set<Hash> = if (declHashStr.isNotEmpty()) {
                    declHashStr.split(";").map { Hash(it) }.toSet()
                } else emptySet()

                // Load module hashes
                val modHashStr = conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'stdlib_module_hashes'").use { rs ->
                        if (rs.next()) rs.getString("value") else ""
                    }
                }
                val moduleHashes = if (modHashStr.isNotEmpty()) modHashStr.split(";").map { Hash(it) }.toSet() else emptySet()

                // Load FunctionIRs
                val functions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT hash, ir FROM blobs WHERE ir IS NOT NULL").use { rs ->
                        while (rs.next()) {
                            val hash = Hash(rs.getString("hash"))
                            val irBytes = rs.getBytes("ir")
                            try {
                                functions[hash] = cbor.decodeFromByteArray(irBytes)
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Load performers
                val performers = mutableListOf<Array<String>>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'performer_records'").use { rs ->
                        if (rs.next()) {
                            val data = rs.getString("value")
                            if (data.isNotEmpty()) {
                                for (record in data.split(";")) {
                                    performers.add(record.split(",").toTypedArray())
                                }
                            }
                        }
                    }
                }

                // Load dependency graph
                val depGraph = mutableMapOf<Hash, Set<Hash>>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT key, value FROM metadata WHERE key LIKE 'deps:%'").use { rs ->
                        while (rs.next()) {
                            val hash = Hash(rs.getString("key").removePrefix("deps:"))
                            val deps = rs.getString("value")
                            if (deps.isNotEmpty()) depGraph[hash] = deps.split(";").map { Hash(it) }.toSet()
                        }
                    }
                }

                conn.close()

                if (functions.isNotEmpty()) {
                    FullCacheData(importContext, functions, performers, depGraph, abilityImpls, declHashes, moduleHashes)
                } else null
            } catch (_: Exception) { null }
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[PrecompiledStdlib] Failed to load full cache from stdlib.sqlite: ${e.message}")
            }
            null
        }
    }

    private data class PreloweredData(
        val functions: Map<Hash, com.mezze.language.truffleir.FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>
    )

    /**
     * Try loading pre-compiled FunctionIRs from embedded stdlib.sqlite resource.
     * Generated by StdlibPrecompiler at Gradle build time.
     * Uses raw JDBC to avoid circular dependency on :codebase module.
     */
    private fun tryLoadPreloweredFromSqlite(): PreloweredData? {
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(STDLIB_SQLITE_RESOURCE) ?: return null

            val tempFile = java.io.File.createTempFile("mezze-stdlib", ".sqlite")
            try {
                tempFile.writeBytes(stream.readAllBytes())
                val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${tempFile.absolutePath}")

                val functions = mutableMapOf<Hash, com.mezze.language.truffleir.FunctionIR>()
                val performers = mutableListOf<Array<String>>()
                val depGraph = mutableMapOf<Hash, Set<Hash>>()

                // Load all FunctionIRs from blobs table
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT hash, ir FROM blobs WHERE ir IS NOT NULL").use { rs ->
                        while (rs.next()) {
                            val hash = Hash(rs.getString("hash"))
                            val irBytes = rs.getBytes("ir")
                            try {
                                @OptIn(ExperimentalSerializationApi::class)
                                val fn = kotlinx.serialization.cbor.Cbor.decodeFromByteArray<com.mezze.language.truffleir.FunctionIR>(irBytes)
                                functions[hash] = fn
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Load performer records
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'performer_records'").use { rs ->
                        if (rs.next()) {
                            val data = rs.getString("value")
                            if (data != null && data.isNotEmpty()) {
                                for (record in data.split(";")) {
                                    performers.add(record.split(",").toTypedArray())
                                }
                            }
                        }
                    }
                }

                // Load dependency graph
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT key, value FROM metadata WHERE key LIKE 'deps:%'").use { rs ->
                        while (rs.next()) {
                            val hash = Hash(rs.getString("key").removePrefix("deps:"))
                            val deps = rs.getString("value")
                            if (deps.isNotEmpty()) {
                                depGraph[hash] = deps.split(";").map { Hash(it) }.toSet()
                            }
                        }
                    }
                }

                conn.close()

                if (functions.isNotEmpty()) {
                    PreloweredData(functions, performers, depGraph)
                } else null
            } finally {
                tempFile.delete()
            }
        } catch (_: Exception) {
            null
        }
    }
}
