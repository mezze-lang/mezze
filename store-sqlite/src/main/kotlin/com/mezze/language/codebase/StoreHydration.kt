package com.mezze.language.codebase

import java.io.File
import java.security.MessageDigest

/**
 * Shared hydration logic: pre-load typechecker state from the store so tools
 * (DevRunner, LSP, mez build, mez check) can skip re-typechecking files that
 * haven't changed since their last typecheck.
 *
 * The actual cache target is `ModuleTypeChecker.globalCache` in the
 * typechecker module — this object only knows how to walk the store and call
 * the hydration entry point, so it can live in the codebase module without a
 * cyclic dependency.
 *
 * Callers pass a [hydrate] lambda that knows how to deserialize and preload
 * a single module — this keeps codebase decoupled from typechecker.
 */
object StoreHydration {

    data class Stats(val hits: Int, val misses: Int, val elapsedMs: Long)

    /**
     * Walk a filtered set of modules in [store] and pre-load their typed
     * AST blobs into the typechecker's in-memory cache via [hydrate].
     *
     * Slice G change: callers pass an explicit [include] predicate so we
     * don't eagerly walk every module on JVM start. DevRunner passes the
     * entry's transitive import closure; LSP passes "no modules yet" and
     * lazy-loads on didOpen.
     *
     * For each included module:
     *  - User modules (category=USER, LIBRARY) verify that the source on
     *    disk still hashes to `modules.source_hash`. If it doesn't, mark
     *    a miss and skip — the on-demand path will re-typecheck.
     *  - Stdlib modules (category=STDLIB) skip the disk check; their
     *    source files are temp-extracted JAR resources that disappear
     *    across JVM runs, but the cached typed blob is still trustable
     *    because slice C re-seeds whenever stdlib_module_hashes shifts.
     *
     * Bad blobs (failed deserialization) are pruned to prevent infinite
     * retry.
     *
     * @param store the project store to walk
     * @param hydrate callback that takes (modulePath, typedBlob); should
     *                deserialize and put into the in-memory cache.
     *                Returning false signals a deserialization error
     *                (treated as miss + prune).
     * @param include predicate deciding whether to attempt to hydrate a
     *                given module. Default: every module.
     * @return statistics for diagnostic logging
     */
    fun hydrateTypedModules(
        store: MezzeStore,
        hydrate: (modulePath: String, typedBlob: ByteArray) -> Boolean,
        include: (ModuleRow) -> Boolean = { true }
    ): Stats {
        val start = System.nanoTime()
        var hits = 0
        var misses = 0

        for (mod in store.getAllModules()) {
            if (!include(mod)) continue

            val cachedHash = if (mod.path.startsWith("std::")) {
                // Stdlib modules: trust the cached source_hash. Their
                // file_path is a JVM-temp path from build time that's
                // gone at runtime; the seed copy guarantees the blob
                // matches modules.source_hash.
                mod.sourceHash
            } else {
                val filePath = mod.filePath ?: run { misses++; continue }
                val file = File(filePath).takeIf { it.exists() } ?: run { misses++; continue }
                // Fast path: if the cached mtime matches the file's current
                // mtime, the source can't have changed since we hashed it —
                // trust the cached hash without re-reading the file.
                // Falls through to the hash check if mtime is missing
                // (rows from before the column was added) or differs.
                val onDiskMtime = file.lastModified()
                if (mod.mtime != null && mod.mtime == onDiskMtime) {
                    mod.sourceHash
                } else {
                    val onDiskHash = sha256(file.readText())
                    if (onDiskHash != mod.sourceHash) {
                        misses++
                        continue
                    }
                    onDiskHash
                }
            }

            val blob = store.getTypedModule(mod.path, cachedHash) ?: run {
                misses++
                continue
            }

            val ok = try {
                hydrate(mod.path, blob)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                hits++
            } else {
                store.clearTypedModulesForPath(mod.path)
                misses++
            }
        }

        return Stats(hits, misses, (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * Compute the transitive import closure of [entryModulePath] using the
     * `imports` table. Result includes [entryModulePath] itself and every
     * module reachable through chains of imports. Stops at modules that
     * have no imports row in the store (typically stdlib leaves).
     *
     * BFS, deduped via a visited set. Cheap — `getImportsForModule` is one
     * indexed query per module, and project import graphs are usually
     * shallow. Caller passes the result as the [include] filter to
     * [hydrateTypedModules] so we only pre-load what the entry actually
     * needs.
     */
    fun computeImportClosure(store: MezzeStore, entryModulePath: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(entryModulePath)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!visited.add(cur)) continue
            for (imp in store.getImportsForModule(cur)) {
                if (imp.targetModule !in visited) queue.add(imp.targetModule)
            }
        }
        return visited
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * If the project's cache was last typechecked against a different
     * stdlib version (different content hashes for stdlib modules),
     * wipe stale typed_modules so the next typecheck rebuilds against
     * the binary's current stdlib.
     *
     * Caller passes [currentStdlibHashes] — the running binary's idea
     * of the stdlib's module hashes, semicolon-joined. We compare to
     * the project's last-stamped value (stored in cache.sqlite metadata
     * under key `project_stdlib_module_hashes`).
     *
     * Returns true if a wipe happened (caller may want to log).
     */
    fun invalidateOnStdlibVersionShift(
        store: MezzeStore,
        currentStdlibHashes: String,
    ): Boolean {
        val recorded = store.getMetadata("project_stdlib_module_hashes")
        if (recorded == currentStdlibHashes) return false
        if (recorded != null) {
            for (mod in store.getAllModules()) {
                // Stdlib lives in its own attached DB; cache.sqlite shouldn't
                // hold std:: typed_modules anyway, but skip them defensively.
                if (mod.path.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)) continue
                store.clearTypedModulesForPath(mod.path)
            }
            store.setMetadata("project_stdlib_module_hashes", currentStdlibHashes)
            return true
        }
        // First-time stamp — no recorded value yet, no wipe needed.
        store.setMetadata("project_stdlib_module_hashes", currentStdlibHashes)
        return false
    }
}
