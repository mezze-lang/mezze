package com.mezze.language.cli

import com.mezze.language.store.StoreSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.store.BlobKind
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.Visibility
import com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.Hash
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.serializer
import java.io.File

/**
 * Build-time tool: pre-compiles all stdlib modules to FunctionIR and stores
 * them in a SQLite file that ships with the JAR.
 *
 * Run by Gradle during cli build:
 *   java -cp <classpath> com.mezze.language.typechecker.StdlibPrecompilerKt <output-path> <project-root>
 *
 * The output stdlib.sqlite contains:
 * - All stdlib FunctionIRs in the blobs table
 * - Performer records in metadata
 * - Function name mappings in metadata
 * - Dependency graph edges
 */
fun main(args: Array<String>) {
    // FIRST, before any typechecker class can initialise: this process BUILDS the
    // stdlib snapshot, so it must not read one. Set below too (where the reason for
    // the slow path is explained), but that is already too late if anything above
    // touches StdlibSnapshotData, whose `object` init reads the classpath once.
    System.setProperty("mezze.stdlib.force-recompile", "1")

    // Populate the NativeOp DSL registry before triggering PrecompiledStdlib
    // init. Without this, native calls in stdlib lower as record-ABI; once
    // the precompiled snapshot is baked into the native binary, every
    // stdlib call site is locked on the slow path. Use reflection because
    // the :cli module deliberately keeps :interpreter off its compile
    // classpath (#220) — touching the class by name forces its static-init
    // to install the bootstrap into NativeSignatures.
    try {
        Class.forName("com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap")
            .getDeclaredField("INSTANCE").get(null)
            .let { boot -> boot.javaClass.getMethod("ensureLoaded").invoke(boot) }
    } catch (e: Throwable) {
        // Interpreter classes not on classpath — only acceptable from
        // typechecker-only flows. StdlibPrecompiler always has them in
        // its runtime classpath, so a failure here is genuinely broken.
        System.err.println("WARNING: NativeBootstrap not reachable: ${e.message}")
    }

    val outputPath = args[0]
    val projectRoot = args.getOrElse(1) { "." }
    // Optional 3rd arg: where to write the SQLite-free CBOR snapshot.
    // Defaults to `stdlib-snapshot.cbor` next to the sqlite output for
    // backwards-compat with older invocations.
    val snapshotPath = args.getOrElse(2) {
        File(outputPath).resolveSibling("stdlib-snapshot.cbor").absolutePath
    }

    System.setProperty("mezze.project.root", projectRoot)
    // Force slow path so PrecompiledStdlib retains stdlibTypedModules /
    // loadedModules for the v7-row scatter below. Without this, an
    // existing ~/.mezze/cache/stdlib.sqlite would let the fast path win
    // and clear those maps to emptyMap() before we get to walk them.
    // Also set at the top of main, which is what actually takes effect.
    System.setProperty("mezze.stdlib.force-recompile", "1")

    val startTime = System.nanoTime()

    // Trigger full PrecompiledStdlib init (parse + typecheck + lower)
    val precompiled = PrecompiledStdlib
    val rawFunctions = precompiled.preloweredFunctions
    val performers = precompiled.preloweredPerformerRecords

    // β-reduce `Mut.mutate { act = λ }` in every stdlib wrapper.
    // Mut is hermetic — the type system already proves no Mut value
    // escapes its handler region — so the effect-dispatch dance is
    // statically unobservable when LocalMut is on top of the chain.
    // After this pass, e.g. `incr1` directly calls `__native_mut_int_incr`
    // with no OpCallNode, no fiber, no marker. Same function hash; only
    // the body changes. See `MutEraser` for the rewrite rules.
    val mutErasedFunctions = com.mezze.language.truffleir.MutEraser.erase(rawFunctions)

    // Inline trivial one-line wrapper functions (`core::pow` →
    // `__native_float_pow`, `float::abs` → `__native_float_abs`, etc.)
    // at every call site. Pairs with the scalar-ABI work: after
    // wrappers are inlined, hot loops call straight into the native
    // dispatch with no intermediate CallTarget per iteration.
    // Measured on `/tmp/primitive-bench` (JIT-on jar, N=10M):
    //   float_sqrt:   ~1000ms → ~650ms (~1.5× faster)
    //   float_inline:  ~200ms → ~145ms (~1.4× faster)
    // Disable with MEZZE_INLINE_WRAPPERS=0 for debugging. See
    // TrivialWrapperInliner for eligibility rules.
    val inlinedFunctions = if (System.getenv("MEZZE_INLINE_WRAPPERS") == "0") {
        mutErasedFunctions
    } else {
        com.mezze.language.truffleir.TrivialWrapperInliner.inline(mutErasedFunctions)
    }
    // #446 — canonicalize iter chains into FusedChainIR so the BDSL
    // fusion peel matches one shape. Off by default until Phase 5 flips
    // MEZZE_CHAIN_CANONICALIZE=1. In the stdlib pipeline the whole map
    // is visible, so no snapshot arg is needed.
    val canonicalizedFunctions = com.mezze.language.truffleir.ChainCanonicalizer
        .canonicalize(inlinedFunctions)
    // Dead-let elimination is opt-in (MEZZE_DEAD_LET_ELIMINATION=1). The
    // conservative purity check in DeadLetEliminator misclassified a
    // number of value-producing patterns during first testing (HTTP
    // handler tests, scope closures) — needs more design work on what
    // "pure" means at this IR level (see notes in DeadLetEliminator).
    // Keeping the pass gated so we can iterate without breaking mainline.
    val functions = if (System.getenv("MEZZE_DEAD_LET_ELIMINATION") == "1") {
        com.mezze.language.truffleir.DeadLetEliminator.eliminate(canonicalizedFunctions)
    } else {
        canonicalizedFunctions
    }

    // Rebuild the dependency graph from the post-inline function bodies.
    // `PrecompiledStdlib.stdlibDependencyGraph` was computed from the raw
    // pre-inline IR — after inlining removes the last direct caller of a
    // wrapper, the pre-inline graph still lists that wrapper as
    // reachable from the caller and every user binary keeps carrying it.
    // Rebuilding here means downstream reachability BFS (mez build,
    // mez build --native) correctly drops those orphaned wrappers.
    val dependencyGraph = com.mezze.language.stdlib.FunctionIRReachability
        .buildDependencyGraph(functions.values)

    val initTime = (System.nanoTime() - startTime) / 1_000_000
    println("Stdlib compiled: ${functions.size} functions, ${performers.size} performers, " +
        "${dependencyGraph.size} nodes in dep graph (${initTime}ms)")

    // Eagerly typecheck every stdlib `.mz` file so its decls — especially
    // `effect` and `performer` declarations — show up in stdlib.sqlite's
    // `decls` table. Without this, the V0505 "did you mean to import this
    // effect?" code action can't surface stdlib effects (Log, FileSystem,
    // etc.) because they were never typechecked during prelude init.
    // The cost is small (~5-10s); the alternative is lazy typecheck at
    // import time, which means stdlib effects are invisible to the
    // import-hints query until someone happens to use them.
    //
    // We go through `ModuleLoader.loadModuleWithDependencies` and feed each
    // result into `ModuleTypeChecker.typeCheckModule()` — the same call the
    // typechecker uses when user code imports a module. That populates
    // ModuleTypeChecker.globalCache, which the StorePopulator loop below
    // reads from to write the `decls` rows.
    val stdlibEagerStart = System.nanoTime()
    val resolver = ModuleResolver.create()
    val stdlibRoot = File(projectRoot, "stdlib/std")
    if (stdlibRoot.isDirectory) {
        val loader = com.mezze.language.typechecker.module.ModuleLoader(resolver)
        val typeChecker = com.mezze.language.typechecker.module.ModuleTypeChecker(
            moduleResolver = resolver
        )
        var eagerCount = 0
        stdlibRoot.walkTopDown().filter { it.isFile && it.extension == "mz" }.forEach { mzFile ->
            // Convert filesystem path "stdlib/std/effects/log.mz" → module path
            // "std::effects::log".
            val rel = mzFile.toRelativeString(stdlibRoot.parentFile)
            val modPath = rel.removeSuffix(".mz").replace(File.separatorChar, ':').replace(":", "::")
                .replace("::::", "::")
            try {
                val loadResult = loader.loadModuleWithDependencies(modPath)
                if (loadResult is com.mezze.language.typechecker.module.ModuleLoadResult.Success) {
                    typeChecker.typeCheckModule(loadResult.module)
                    eagerCount++
                } else if (loadResult is com.mezze.language.typechecker.module.ModuleLoadResult.Failure) {
                    // A parse or module-load failure inside stdlib is never
                    // "best-effort" — the file WILL be re-parsed at user-code
                    // typecheck time (transitively through imports) and will
                    // fail there anyway, or worse: it produces a malformed
                    // AST that silently corrupts ability resolution for user
                    // code (see #512). Fail loud at compile time. Individual
                    // "work-in-progress" files should be excluded via the
                    // manifest, not by silently swallowing errors here.
                    val msg = loadResult.errors.joinToString("; ") { it.message ?: it.javaClass.simpleName }
                    System.err.println("[StdlibPrecompiler] $modPath failed to load: $msg")
                    throw RuntimeException("Stdlib module '$modPath' failed to load: $msg")
                }
            } catch (e: Throwable) {
                // Surface (don't swallow). A parse error at this stage means
                // downstream compilation will use a broken AST — see #512 where
                // a stdlib `!` slipped past this silent catch and cascaded.
                System.err.println("[StdlibPrecompiler] $modPath eager-typecheck failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
        println("Eagerly typechecked $eagerCount stdlib modules " +
            "in ${(System.nanoTime() - stdlibEagerStart) / 1_000_000}ms")
    }

    // Write to SQLite
    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()
    if (outputFile.exists()) outputFile.delete()

    val store = SQLiteMezzeStore.forFile(outputFile)
    try {
        // Slice C: write proper v7 rows per stdlib module so the project
        // cache seed pulls in modules / names / decls / typed_modules /
        // parsed_modules / imports / usages all keyed by category='stdlib'.
        // Loops StorePopulator over each loaded stdlib module — same scatter
        // path user code goes through, but with category=STDLIB and reading
        // from PrecompiledStdlib's already-loaded TypedModules instead of
        // re-typechecking.
        val stdlibParsed = ModuleResolver.getAllParsedModules()
            .filterKeys { it.startsWith(STDLIB_NAMESPACE_PREFIX) }
        // Iterate every cached typechecker module so we capture both the
        // 18 prelude-loaded ones and the ~5 effects/abilities pulled in
        // transitively. PrecompiledStdlib.loadedModules only has the
        // first set; ModuleTypeChecker.getAllCachedModules sees all.
        // Each entry's key IS the canonical module path (e.g. "std::int").
        val allCached = com.mezze.language.typechecker.module.ModuleTypeChecker
            .getAllCachedModules()
            .filterKeys { it.startsWith(STDLIB_NAMESPACE_PREFIX) }
        for ((modPath, v1) in allCached) {
            val typedMod = v1.typedModule ?: continue
            val source = readModuleSource(v1.filePath)
            val sourceHash = source?.let { sha256Hex(it) } ?: ""
            val parsed = stdlibParsed[modPath] ?: v1.parsedModule

            StorePopulator.populate(
                store,
                TypeCheckResult(typedModule = typedMod),
                modulePath = modPath,
                filePath = v1.filePath,
                sourceHash = sourceHash,
                entrySource = source,
                parsedModule = parsed
            )
        }

        store.transaction {
            // Merge IR bytes into the typed-only blobs StorePopulator just
            // wrote. Each function lookup updates ir on the existing row,
            // matching the user-code merge path in scatterFunctionIRs.
            for ((hash, fn) in functions) {
                val existing = store.getBlob(hash)
                val irBytes = StoreSerialization.serializeFunctionIR(fn)
                store.putBlob(
                    hash,
                    existing?.kind ?: BlobKind.FUNCTION,
                    existing?.visibility ?: Visibility.PUBLIC,
                    existing?.typed,
                    irBytes
                )
            }

            // Store performer records
            if (performers.isNotEmpty()) {
                store.setMetadata("performer_records",
                    performers.joinToString(";") { it.joinToString(",") })
            }

            // Store function name mappings. SQLite metadata keys/values
            // are strings — write `hash.hex` explicitly at this serialization
            // boundary; Hash.toString() truncates to 8 chars for readability.
            for ((hash, fn) in functions) {
                if (fn.name != "lambda" && !fn.name.startsWith("__")) {
                    store.setMetadata("fn_name:${hash.hex}", fn.name)
                }
            }

            // Store the dependency graph as EDGES — the table that exists for it.
            // It used to go into `metadata` as `deps:<hash>` = `;`-joined hashes: a
            // delimited string in a key-value table, duplicating what `edges` is
            // for, and unqueryable. Meanwhile `edges` held only IMPLEMENTS rows, so
            // "who calls this" answered "nothing" for all 1,882 stdlib functions.
            //
            // Computed from post-inline IR above, not
            // `precompiled.stdlibDependencyGraph` (which is pre-inline).
            for ((hash, deps) in dependencyGraph) {
                for (dep in deps) store.addEdge(hash, dep, EdgeKind.CALLS)
            }

            // Store prelude context (types, abilities, effects, bindings)
            // This eliminates the need to parse + typecheck stdlib at runtime.
            @OptIn(ExperimentalSerializationApi::class)
            run {
                val cborFmt = Cbor { ignoreUnknownKeys = true }

                val preludeBytes = cborFmt.encodeToByteArray(
                    serializer<com.mezze.language.typechecker.module.ModuleImportContext>(),
                    precompiled.preludeImportContext
                )
                store.setMetadata("prelude_import_context",
                    java.util.Base64.getEncoder().encodeToString(preludeBytes))

                val implBytes = cborFmt.encodeToByteArray(
                    serializer<List<com.mezze.language.typedast.TypedAbilityImpl>>(),
                    precompiled.stdlibAbilityImpls
                )
                store.setMetadata("stdlib_ability_impls",
                    java.util.Base64.getEncoder().encodeToString(implBytes))
            }

            // Store decl and module hashes
            store.setMetadata("stdlib_decl_hashes",
                precompiled.stdlibDeclHashes.joinToString(";") { it.hex })
            store.setMetadata("stdlib_module_hashes",
                precompiled.stdlibModuleHashes.joinToString(";") { it.hex })

            // Store metadata counts for verification
            store.setMetadata("stdlib_version", "2")
            store.setMetadata("function_count", functions.size.toString())
            store.setMetadata("performer_count", performers.size.toString())
        }

        val fileSize = outputFile.length() / 1024
        println("Wrote ${outputFile.name} (${fileSize}KB) with prelude context")
    } finally {
        store.close()
    }

    // Also write an SQLite-free CBOR snapshot of the same data — used by
    // the native binary (and any other host that doesn't have sqlite-jdbc
    // available). One file, one CBOR decode at boot, no JDBC anywhere.
    val snapshotFile = File(snapshotPath)
    snapshotFile.parentFile?.mkdirs()
    // Every stdlib module checked while precompiling, as importers read it, so
    // `use std::…` finds it checked instead of checking it in every process —
    // except a module whose typed form is over [MAX_SHIPPED_MODULE_BYTES].
    // `std::syntax` is 188 MB and `std::codebase` 39 MB: their types are carried
    // fully expanded. Those are still checked per process until types are
    // stored compactly; the build says which.
    val sizer = kotlinx.serialization.cbor.Cbor { }
    val (checked, tooLarge) = com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()
        .filterKeys { it.startsWith(STDLIB_NAMESPACE_PREFIX) }.values
        .filter { it.typedModule != null }
        .partition { r ->
            com.mezze.language.ast.TypeSharing.write {
                sizer.encodeToByteArray(com.mezze.language.typedast.TypedModule.serializer(), r.typedModule!!)
            }.size <= MAX_SHIPPED_MODULE_BYTES
        }
    if (tooLarge.isNotEmpty()) {
        println("Not preloaded (typed form over ${MAX_SHIPPED_MODULE_BYTES / 1_000_000} MB): ${tooLarge.map { it.modulePath }.sorted()}")
    }
    val shippedTyped = precompiled.stdlibTypedModules + checked.associate { it.typedModule!!.hash to it.typedModule!! }
    val snapshot = com.mezze.language.typechecker.StdlibSnapshot(
        importContext = precompiled.preludeImportContext,
        functions = functions,
        performers = performers.map { it.toList() },
        dependencyGraph = dependencyGraph,  // post-inline; see comment above
        abilityImpls = precompiled.stdlibAbilityImpls,
        declHashes = precompiled.stdlibDeclHashes,
        moduleHashes = precompiled.stdlibModuleHashes,
        // #415: ship TypedModules so mono can specialize any callsite
        // that flows through a polymorphic stdlib decl.
        typedModules = shippedTyped,
        identities = com.mezze.language.typechecker.StdlibIdentities.table(),
        modules = checked.mapNotNull { com.mezze.language.typechecker.module.CachedModule.of(it) },
        dictMethods = precompiled.preloweredDictMethods,
        stamped = com.mezze.language.typechecker.ModuleResolver.getAllStampedSources()
            .filter { it.modulePath.startsWith(STDLIB_NAMESPACE_PREFIX) },
    )
    snapshotFile.writeBytes(com.mezze.language.typechecker.StdlibSnapshot.encode(snapshot))
    // Sibling fingerprint of THIS build's serialization schema, checked at load
    // before decode so a schema-skewed snapshot is rejected cleanly (see
    // StdlibSnapshotData / StdlibSnapshot.schemaFingerprint).
    snapshotFile.resolveSibling("stdlib-snapshot.fp")
        .writeText(com.mezze.language.typechecker.StdlibSnapshot.schemaFingerprint)
    println("Wrote ${snapshotFile.name} (${snapshotFile.length() / 1024}KB) + fingerprint — SQLite-free snapshot")
}

/**
 * Read a stdlib module's source. The filePath PrecompiledStdlib carries
 * is a classpath-relative `stdlib/<...>.mz` (extracted via
 * `ModuleResolver.readStdlibFile`). When PrecompiledStdlib loads from
 * disk during build, it's an absolute filesystem path — try both.
 */
private fun readModuleSource(filePath: String?): String? {
    if (filePath == null) return null
    val asFile = File(filePath)
    if (asFile.exists()) return asFile.readText()
    return ModuleResolver.readStdlibFile(filePath)
}

private fun sha256Hex(s: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The largest typed module the stdlib snapshot preloads; see where it is used. */
private const val MAX_SHIPPED_MODULE_BYTES = 8_000_000
