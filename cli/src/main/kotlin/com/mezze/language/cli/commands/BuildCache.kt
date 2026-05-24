package com.mezze.language.cli.commands

import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import java.io.File
import java.security.MessageDigest

/**
 * Cache lookup for `mez build`: if `.mz/cache.sqlite` has the app's IRs
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
     */
    fun tryLoad(store: SQLiteMezzeStore, appName: String): Hit? {
        // App registered?
        if (store.getApp(appName) == null) return null

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
