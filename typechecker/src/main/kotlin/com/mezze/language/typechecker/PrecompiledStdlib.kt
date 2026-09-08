package com.mezze.language.typechecker

import com.mezze.language.ast.ModulePath
import com.mezze.language.ast.ModulePathConventions
import com.mezze.language.stdlib.StdlibRuntimeData
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.MezzeStoreOpener
import com.mezze.language.truffleir.*
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import com.mezze.language.typechecker.module.*
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule as TypedAstModule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.nio.file.Paths

object PrecompiledStdlib {

    private const val STDLIB_MODULES_RESOURCE = "mezze-stdlib-modules.cbor"
    private const val STDLIB_SQLITE_RESOURCE = "stdlib.sqlite"

        @Volatile
    private var store: MezzeStore? = null

    @JvmStatic
    fun setStore(store: MezzeStore) {
        this.store = store
    }

        @JvmStatic
    fun getStore(): MezzeStore? = store

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val compiledPrelude: CompiledModuleInterface
    val preludeImportContext: ModuleImportContext

    var loadedModules: Map<ModulePath, TypedModuleResult>
        private set

    var stdlibTypedModules: Map<Hash, TypedAstModule>
        private set

    var stdlibAbilityImpls: List<TypedAbilityImpl> = emptyList()
        private set

    var stdlibDeclHashes: Set<Hash> = emptySet()
        private set

    var stdlibModuleHashes: Set<Hash> = emptySet()
        private set

    var preloweredFunctions: Map<Hash, FunctionIR>
        private set

    /** Dict method requirements discovered during stdlib pre-compilation.
     *  Keys are function hashes, values are sets of method names (e.g. "lt", "le").
     *  Passed to the user-code TypedASTCompiler so call sites can inject dicts
     *  for pre-compiled stdlib functions. */
    var preloweredDictMethods: Map<Hash, Set<String>> = emptyMap()
        private set

    var stdlibDependencyGraph: Map<Hash, Set<Hash>> = emptyMap()
        private set

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

    private fun collectHashRefs(node: NodeIR, refs: MutableSet<Hash>) {
        when (node) {
            is CallDirectIR -> { refs.add(node.targetHash); node.args.forEach { collectHashRefs(it, refs) } }
            is PartialApplyDirectIR -> { refs.add(node.targetHash); node.partialFields.forEach { collectHashRefs(it.value, refs) } }
            is MakeLambdaIR -> refs.add(node.functionHash)
            is HandleIR -> {
                collectHashRefs(node.body, refs)
                for (h in node.handlers) { refs.add(h.handlerHash); refs.add(h.effectHash) }
            }
            is PerformOpIR -> { refs.add(node.effectHash); collectHashRefs(node.arg, refs) }
            is BlockIR -> {
                for (s in node.stmts) when (s) {
                    is StmtIR.Let -> collectHashRefs(s.value, refs)
                    is StmtIR.Expr -> collectHashRefs(s.node, refs)
                }
                collectHashRefs(node.result, refs)
            }
            is CallLocalIR -> collectHashRefs(node.arg, refs)
            is CallNativeIR -> {
                collectHashRefs(node.arg, refs)
                node.args.forEach { collectHashRefs(it, refs) }
            }
            is CallIndirectIR -> { collectHashRefs(node.function, refs); collectHashRefs(node.arg, refs) }
            is CallIndirectScalarIR -> { collectHashRefs(node.function, refs); node.args.forEach { collectHashRefs(it, refs) } }
            is MakeRecordIR -> node.fields.forEach { collectHashRefs(it.value, refs) }
            is ReadFieldIR -> collectHashRefs(node.record, refs)
            is RecordUpdateIR -> { collectHashRefs(node.record, refs); node.updates.forEach { collectHashRefs(it.value, refs) } }
            is MakeVariantIR -> node.payload?.let { collectHashRefs(it, refs) }
            is MatchIR -> {
                collectHashRefs(node.scrutinee, refs)
                for (c in node.cases) { collectHashRefs(c.body, refs); c.guard?.let { collectHashRefs(it, refs) } }
            }
            is IfIR -> { collectHashRefs(node.cond, refs); collectHashRefs(node.thenBr, refs); collectHashRefs(node.elseBr, refs) }
            is WriteLocalIR -> collectHashRefs(node.value, refs)
            is InlineBinaryOpIR -> { collectHashRefs(node.left, refs); collectHashRefs(node.right, refs) }
            is SpecializedCallIR -> { node.args.forEach { collectHashRefs(it, refs) } }
            is InterpolatedStringIR -> {
                for (p in node.parts) if (p is StringPartIR.Expr) collectHashRefs(p.node, refs)
            }
            is MakeSeqIR -> {
                for (e in node.elements) when (e) {
                    is SeqElementIR.Regular -> collectHashRefs(e.node, refs)
                    is SeqElementIR.Spread -> collectHashRefs(e.node, refs)
                }
            }
            is SeqGetIR -> { collectHashRefs(node.seq, refs); collectHashRefs(node.index, refs) }
            is DictMethodCallIR -> collectHashRefs(node.arg, refs)
            // ReadGlobalByHash{,Fn}IR carry a Hash directly — feed it into the
            // dep graph. EffectBindingIR is a runtime-injected effect-handler
            // binding (`scope`/`resume`/`abort`); not a global, so no hash ref.
            is ReadGlobalByHashIR -> refs.add(node.hash)
            is ReadGlobalFnByHashIR -> refs.add(node.hash)
            is EffectBindingIR -> {}
            else -> {} // leaf nodes
        }
    }

    val preloweredPerformerRecords: List<Array<String>>
    val moduleLoader: ModuleLoader
    val importResolver: ImportResolver

    init {
        val initStart = System.nanoTime()
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // StdlibPrecompiler sets -Dmezze.stdlib.force-recompile=1 to
        // keep stdlibTypedModules/loadedModules for writing seed file rows.
        val forceRecompile = System.getProperty("mezze.stdlib.force-recompile") == "1"
        val fullCache = if (forceRecompile) null
            else tryLoadFromSnapshot() ?: tryLoadFullFromStore() ?: tryLoadFullFromSqlite()

        if (fullCache != null) {
            preludeImportContext = fullCache.importContext
            compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)
            preloweredFunctions = fullCache.functions
            preloweredPerformerRecords = fullCache.performers
            stdlibDependencyGraph = fullCache.dependencyGraph
            stdlibAbilityImpls = fullCache.abilityImpls
            stdlibDeclHashes = fullCache.declHashes
            stdlibModuleHashes = fullCache.moduleHashes
            // #415: honor the snapshot's typedModules so mono can see
            // stdlib decls in the fast-load path. Old snapshots default
            // to an empty map, preserving the pre-#415 behavior.
            stdlibTypedModules = fullCache.typedModules
            loadedModules = emptyMap()
            // Re-derive dict methods — the snapshot format doesn't store them.
            preloweredDictMethods = prescanDictMethodsFromModules(stdlibTypedModules)

            moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
            val tc = TypeChecker()
            val mtc = ModuleTypeChecker.withContextAndResolver(tc.parentContext, moduleLoader.getResolver())
            importResolver = ImportResolver(moduleLoader, mtc)

            if (System.getenv("MEZZE_DEBUG") != null) {
                val ms = (System.nanoTime() - initStart) / 1_000_000
                System.err.println("[PrecompiledStdlib] Loaded from stdlib.sqlite: ${fullCache.functions.size} functions, ${fullCache.abilityImpls.size} ability impls (${ms}ms)")
            }
        } else {
            val tc = TypeChecker()
            moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
            val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
                tc.parentContext,
                moduleLoader.getResolver()
            )
            importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

            preludeImportContext = tc.loadPreludeModulesPublic(importResolver)
            loadedModules = tc.getCachedPreludeLoadedModules() ?: emptyMap()
            compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)

            val cached = tryLoadV2ModulesFromResource()
            if (cached != null) {
                stdlibTypedModules = cached
            } else {
                val byHash = mutableMapOf<Hash, TypedAstModule>()
                for ((_, wrapper) in loadedModules) {
                    val typedMod = wrapper.typedModule
                    if (typedMod != null) byHash[typedMod.hash] = typedMod
                }
                stdlibTypedModules = byHash
            }

            val preloaded = tryLoadPreloweredFromStore() ?: tryLoadPreloweredFromSqlite()
            if (preloaded != null) {
                preloweredFunctions = preloaded.functions
                preloweredPerformerRecords = preloaded.performers
                stdlibDependencyGraph = preloaded.dependencyGraph
                // Re-derive dict methods from typed modules when loading from cache.
                // The cache format doesn't store dict methods, so we prescan here.
                preloweredDictMethods = prescanDictMethodsFromModules(stdlibTypedModules)
            } else {
                val allModulesForPrelower = HashMap<Hash, TypedAstModule>(stdlibTypedModules)
                for ((_, cachedMod) in ModuleTypeChecker.getAllCachedModules()) {
                    val stdlibModule = cachedMod.typedModule
                    if (stdlibModule != null && stdlibModule.hash !in allModulesForPrelower) {
                        allModulesForPrelower[stdlibModule.hash] = stdlibModule
                    }
                }

                val preFunctions = mutableMapOf<Hash, FunctionIR>()
                val prePerformers = mutableListOf<Array<String>>()
                val preDictMethods = mutableMapOf<Hash, Set<String>>()
                val allAbilityImpls = allModulesForPrelower.values.flatMap { it.abilityImpls }
                for ((modHash, typedMod) in allModulesForPrelower) {
                    try {
                        val compiler = TypedASTCompiler(abilityImpls = allAbilityImpls)
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
                        // Accumulate dict method requirements for user-code compilation
                        for ((h, methods) in compiler.getDictMethodsForHash()) {
                            preDictMethods[h] = methods
                        }
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
                preloweredDictMethods = preDictMethods
                buildDependencyGraph()
            }

            stdlibAbilityImpls = stdlibTypedModules.values.flatMap { it.abilityImpls }
            val declHashes = mutableSetOf<Hash>()
            for ((_, stdlibModule) in stdlibTypedModules) {
                for (d in stdlibModule.decls) {
                    if (d is TypedLetDecl) declHashes.add(d.hash)
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

        // Publish the runtime-only view so :interpreter can read it
        // without depending on this object. Covers all populating
        // branches (snapshot fast-path, store/sqlite preloaded, full
        // compile). StdlibRuntimeData may already have loaded the same
        // data from its own snapshot resource — install() overwrites
        // either way, last-writer-wins; the data is the same.
        StdlibRuntimeData.install(
            preloweredFunctions = preloweredFunctions,
            preloweredPerformerRecords = preloweredPerformerRecords,
            dependencyGraph = stdlibDependencyGraph,
        )
    }

    /**
     * Lightweight prescan of stdlib typed modules to discover which functions
     * need dictionary params (e.g. `__dict_lt`). Runs the TypedASTCompiler's
     * prescan for each module, collecting the hash → methods map. Does NOT
     * compile function bodies — only the prescan step runs.
     */
    private fun prescanDictMethodsFromModules(modules: Map<Hash, TypedAstModule>): Map<Hash, Set<String>> {
        if (modules.isEmpty()) return emptyMap()
        val allAbilityImpls = modules.values.flatMap { it.abilityImpls }
        val result = mutableMapOf<Hash, Set<String>>()
        for ((_, typedMod) in modules) {
            try {
                val compiler = TypedASTCompiler(abilityImpls = allAbilityImpls)
                compiler.prescanOnly(typedMod)
                for ((h, methods) in compiler.getDictMethodsForHash()) {
                    result[h] = methods
                }
            } catch (_: Exception) { /* best-effort — non-fatal */ }
        }
        return result
    }

        @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadV2ModulesFromResource(): Map<Hash, TypedAstModule>? {
        return try {
            val stream = PrecompiledStdlib::class.java.classLoader
                .getResourceAsStream(STDLIB_MODULES_RESOURCE) ?: return null
            val bytes = stream.readAllBytes()
            val moduleList: List<TypedAstModule> = cbor.decodeFromByteArray(bytes)
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

            private fun loadDepGraphFromStore(
        s: MezzeStore,
        functions: Map<Hash, FunctionIR>
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

        private fun tryLoadFromSnapshot(): FullCacheData? {
        if (!StdlibSnapshotData.present) return null
        val ic = StdlibSnapshotData.importContext ?: return null
        if (System.getenv("MEZZE_DEBUG") != null) {
            System.err.println(
                "[PrecompiledStdlib] Loaded from StdlibSnapshotData (image heap): " +
                "${StdlibSnapshotData.functions.size} functions, " +
                "${StdlibSnapshotData.abilityImpls.size} impls"
            )
        }
        return FullCacheData(
            importContext = ic,
            functions = StdlibSnapshotData.functions,
            performers = StdlibSnapshotData.performers,
            dependencyGraph = StdlibSnapshotData.dependencyGraph,
            abilityImpls = StdlibSnapshotData.abilityImpls,
            declHashes = StdlibSnapshotData.declHashes,
            moduleHashes = StdlibSnapshotData.moduleHashes,
            typedModules = StdlibSnapshotData.typedModules,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadFullFromStore(): FullCacheData? {
        val s = store ?: return null
        return try {
            val version = s.getMetadata("stdlib_version") ?: return null
            if (version != "2") return null

            val preludeB64 = s.getMetadata("prelude_import_context") ?: return null
            val preludeBytes = java.util.Base64.getDecoder().decode(preludeB64)
            val importContext: ModuleImportContext = cbor.decodeFromByteArray(preludeBytes)

            val implB64 = s.getMetadata("stdlib_ability_impls")
            val abilityImpls: List<TypedAbilityImpl> = if (implB64 != null) {
                cbor.decodeFromByteArray(java.util.Base64.getDecoder().decode(implB64))
            } else emptyList()

            val declHashStr = s.getMetadata("stdlib_decl_hashes") ?: ""
            val declHashes: Set<Hash> = if (declHashStr.isNotEmpty()) {
                declHashStr.split(";").map { Hash(it) }.toSet()
            } else emptySet()

            val modHashStr = s.getMetadata("stdlib_module_hashes") ?: ""
            val moduleHashes = if (modHashStr.isNotEmpty()) modHashStr.split(";").map { Hash(it) }.toSet() else emptySet()

            // Load ALL blobs, not just declHashes — internal lambdas/performers/
            // synthetics have no decl but crash at runtime if missing.
            val functions = mutableMapOf<Hash, FunctionIR>()
            val decodeFailures = mutableListOf<Pair<Hash, String>>()
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

            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

            val depGraph = loadDepGraphFromStore(s, functions)

            // #440 Day 7 (Mono Gap A): read stdlib TypedModules from the
            // store so mono can see stdlib decls. Without this, the sqlite
            // fast path silently seeded mono with an empty map, blocking
            // HOF specialization across stdlib calls (fold, map, filter).
            // Walk the same shape as tryLoadPreloweredFromStore.
            val typedModules = mutableMapOf<Hash, TypedAstModule>()
            var typedModDecodeFailures = 0
            var typedModLastError: String? = null
            for (mod in s.getAllModules()) {
                if (!ModulePathConventions.isStdlib(mod.path)) continue
                val typedBlob = s.getTypedModule(mod.path, mod.sourceHash) ?: continue
                try {
                    val typedModule: TypedAstModule = cbor.decodeFromByteArray(typedBlob)
                    typedModules[typedModule.hash] = typedModule
                } catch (e: Exception) {
                    typedModDecodeFailures++
                    typedModLastError = "${mod.path}: ${e.javaClass.simpleName}"
                }
            }
            if (typedModDecodeFailures > 0 && System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[PrecompiledStdlib] tryLoadFullFromStore: $typedModDecodeFailures typed module(s) " +
                    "failed to decode. Last: $typedModLastError"
                )
            }

            if (functions.isNotEmpty()) {
                FullCacheData(importContext, functions, performers, depGraph, abilityImpls, declHashes, moduleHashes, typedModules)
            } else null
        } catch (e: Exception) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[PrecompiledStdlib] Failed to load from store: ${e.message}")
            }
            null
        }
    }

        @OptIn(ExperimentalSerializationApi::class)
    private fun tryLoadPreloweredFromStore(): PreloweredData? {
        val s = store ?: return null
        return try {
            val functions = mutableMapOf<Hash, FunctionIR>()
            var modDecodeFailures = 0
            var irDecodeFailures = 0
            var lastError: String? = null

            for (mod in s.getAllModules()) {
                if (!ModulePathConventions.isStdlib(mod.path)) continue
                val typedBlob = s.getTypedModule(mod.path, mod.sourceHash) ?: continue
                try {
                    val typedModule: TypedAstModule = cbor.decodeFromByteArray(typedBlob)
                    for (decl in typedModule.decls) {
                        if (decl is TypedLetDecl) {
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

            val performerStr = s.getMetadata("performer_records") ?: ""
            val performers = if (performerStr.isNotEmpty()) {
                performerStr.split(";").map { it.split(",").toTypedArray() }
            } else emptyList()

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

    // Uses MezzeStoreOpener SPI (#168: reflection broke native-image)
    private fun openStdlibResourceAsStore(): MezzeStore? {
        val opener = MezzeStoreOpener.current() ?: return null
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
        val functions: Map<Hash, FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>,
        val abilityImpls: List<TypedAbilityImpl>,
        val declHashes: Set<Hash>,
        val moduleHashes: Set<Hash>,
        // #415: TypedModules for mono. Empty on old snapshots (loader-side
        // default) — mono then behaves as before (skips stdlib specialization).
        val typedModules: Map<Hash, TypedAstModule> = emptyMap()
    )

    private data class PreloweredData(
        val functions: Map<Hash, FunctionIR>,
        val performers: List<Array<String>>,
        val dependencyGraph: Map<Hash, Set<Hash>>
    )
}
