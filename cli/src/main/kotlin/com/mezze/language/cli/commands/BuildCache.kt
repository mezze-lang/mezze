package com.mezze.language.cli.commands

import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import java.io.File
import java.security.MessageDigest

/**
 * Cache lookup for `mez build`: if `.mez/store/src.sqlite` has the app's IRs
 * AND every project file content-matches the cache, return the cached
 * (functions, performerRecords); else null (caller recompiles).
 *
 * The store is the single source of truth — populated by `mez dev` on
 * every save (and re-populated by `mez build` on miss). This lets the
 * common workflow (edit → save → `mez build`) skip the 1.3 s typecheck
 * cost and pack the jar from cache.
 *
 * Conceptually mirrors `mez dev`'s cache-warm path (handled inside
 * WireRunner.compile by the typechecker's parser cache). Worth
 * extracting a shared `BuildCacheLookup` once both paths stabilise.
 */
object BuildCache {

    /**
     * Environment flags that change the LOWERED IR, and therefore the identity of
     * what the cache holds.
     *
     * THE BUG THIS EXISTS FOR, found the hard way: a build with
     * `MEZZE_MONO_STRIP=1` wrote its IR into `.mez`, and every later build —
     * WITHOUT the flag — got a cache hit and ran the stripped IR. The symptom was
     * `bench/cases/defunc` kernel 6 answering `sum=<function:>` instead of
     * 400000040000000, long after the flag was gone from the command line, and it
     * cost a detour chasing a regression in `seq::fold` that did not exist. The
     * cache's own doc lists what it invalidates on — sources, missing blobs,
     * stdlib drift — and the COMPILER'S OWN CONFIGURATION was not on that list.
     *
     * Trace-only flags are deliberately absent: `MEZZE_DEBUG`,
     * `MEZZE_TRACE_FUSION` and `MEZZE_TRACE_WRAPPER` print and change nothing, so
     * keying on them would throw the cache away for a diagnostic run.
     *
     * A flag added to the lowering path and not added here reintroduces the bug
     * silently, which is why the list names the files to grep: these are the
     * `System.getenv` reads under `typechecker/.../mono/`,
     * `typechecker/.../compiler/` and `ast/.../truffleir/`.
     */
    private val LOWERING_FLAGS = listOf(
        "MEZZE_CHAIN_CANONICALIZE",
        "MEZZE_DEAD_LET_ELIMINATION",
        "MEZZE_DISABLE_INLINE",
        "MEZZE_DISABLE_SCALAR",
        "MEZZE_INLINE_WRAPPERS",
        "MEZZE_MONO_CLOSURES",
        "MEZZE_MONO_SEED_STDLIB",
        "MEZZE_MONO_STRIP",
    )

    /**
     * A digest of the lowering configuration. Unset flags are included as empty,
     * so the DEFAULT configuration has a stable key of its own rather than being
     * "no key" — otherwise the common case could never be told from a cache
     * written before this check existed.
     */
    internal fun loweringConfigKey(env: (String) -> String? = System::getenv): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (name in LOWERING_FLAGS) {
            md.update(name.toByteArray())
            md.update('='.code.toByte())
            md.update((env(name) ?: "").toByteArray())
            md.update('\n'.code.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The flags the key covers, for the test that holds the list honest. */
    internal fun loweringFlagNames(): List<String> = LOWERING_FLAGS

    private fun keyFile(projectDir: File) = File(File(projectDir, ".mez"), "lowering.key")

    /**
     * Record the configuration the cache was written under. Called after a fresh
     * compile, so the next build can tell whether its own configuration matches.
     */
    fun recordLoweringConfig(projectDir: File) {
        try {
            val f = keyFile(projectDir)
            f.parentFile?.mkdirs()
            f.writeText(loweringConfigKey())
        } catch (_: Exception) {
            // Unwritable `.mez` means the next build misses and recompiles, which
            // is the safe direction — never a silently reused foreign cache.
        }
    }

    data class Hit(
        val functions: List<FunctionIR>,
        val performerRecords: List<Array<String>>,
    )

    /**
     * Try to satisfy a build from cache.sqlite. Returns null if any of:
     *   - app row missing (never built / different app name)
     *   - any project source file changed on disk since last cache
     *   - any IR blob missing or unreadable
     *   - any referenced hash not resolvable (stdlib drift; mez upgrade)
     *   - the LOWERING CONFIGURATION differs from the one it was written under
     *     (see [LOWERING_FLAGS]), or was never recorded
     */
    fun tryLoad(store: SQLiteMezzeStore, appName: String, projectDir: File): Hit? {
        // App registered?
        if (store.getApp(appName) == null) return null

        // Compiled under the same lowering configuration? A missing key means the
        // cache predates this check, and recompiling once is cheaper than trusting
        // IR whose provenance is unknown.
        val recorded = try {
            keyFile(projectDir).takeIf { it.isFile }?.readText()?.trim()
        } catch (_: Exception) { null }
        if (recorded != loweringConfigKey()) return null

        // Sources unchanged?
        if (anyProjectFileStale(store)) return null

        // IRs all present + decodable?
        val hashes = store.getAppFunctionHashes(appName)
        if (hashes.isEmpty()) return null
        val functions = ArrayList<FunctionIR>(hashes.size)
        for (h in hashes) {
            val blob = store.getIR(h) ?: return null
            try {
                functions.add(StoreSerialization.deserializeFunctionIR(blob))
            } catch (_: Exception) {
                return null
            }
        }

        // Cross-reference: every hash referenced by the IRs (transitively)
        // must resolve to something in this binary's stdlib OR this project's
        // store. Catches mez-upgrade staleness where stdlib helper hashes
        // moved out from under us.
        val stdlib = com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions
        val executor = com.mezze.language.codebase.MezzeExecutor(listOf(store))
        val unresolved = executor.collectUnresolvedHashes(functions) { hash ->
            stdlib.containsKey(hash) || store.blobExists(hash)
        }
        if (unresolved.isNotEmpty()) return null

        return Hit(
            functions = functions,
            performerRecords = store.getAppPerformerRecords(appName),
        )
    }

    /**
     * Any project (non-stdlib) source file changed on disk since it was
     * cached. Stdlib drift is handled separately at startup via
     * `StoreHydration.invalidateOnVersionShift`.
     */
    private fun anyProjectFileStale(store: SQLiteMezzeStore): Boolean {
        for (mod in store.getAllModules()) {
            if (mod.path.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)) continue
            val filePath = mod.filePath ?: continue
            val file = File(filePath)
            if (!file.exists()) return true
            if (sha256(file.readText()) != mod.sourceHash) return true
        }
        return false
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
