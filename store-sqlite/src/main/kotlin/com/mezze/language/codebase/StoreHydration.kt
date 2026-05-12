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
     * mezze version (different stdlib module hashes), wipe ALL
     * typecheck-derived state so the next compile produces fresh IR
     * against the current binary's hashes.
     *
     * Why everything: a typechecker/lowerer change shifts the content
     * hashes of user decls. After such a shift, EVERY table that keys
     * by hash holds stale references — not just typed_modules but also
     * blobs (per-decl IR), app_functions (the manifest the runtime
     * reads), names, decls, usages, imports, edges, performer/mono
     * rows. Wiping only typed_modules left blobs and app_functions
     * referencing hashes nothing else recognises, and the runtime
     * crashed with `expected MezzeFunction got null`.
     *
     * What's preserved: `modules` (path/file/mtime — pure user-source
     * observations), `parsed_modules` (parser output, upstream of
     * typecheck), and `metadata` (where the stamp itself lives).
     *
     * Two trigger cases — both should wipe:
     *   1. recorded != null && recorded != current → real upgrade.
     *   2. recorded == null but typecheck output already exists →
     *      project was populated by an older binary that pre-dates
     *      this version stamp. We can't tell if its hashes are
     *      compatible with ours, so assume not.
     *
     * Returns true if a wipe happened (caller may want to log).
     */
    fun invalidateOnStdlibVersionShift(
        store: MezzeStore,
        currentStdlibHashes: String,
    ): Boolean {
        val recorded = store.getMetadata("project_stdlib_module_hashes")
        if (recorded == currentStdlibHashes) return false

        // Detect "no stamp yet but cache already has typecheck output" —
        // legacy state from a pre-stamp binary; treat the same as a shift.
        val hasLegacyOutput = recorded == null && store.getAllModules().any {
            !it.path.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)
        }

        if (recorded != null || hasLegacyOutput) {
            wipeAllTypecheckDerivedState(store)
            store.setMetadata("project_stdlib_module_hashes", currentStdlibHashes)
            return true
        }
        // Truly fresh project — first-time stamp, nothing to wipe.
        store.setMetadata("project_stdlib_module_hashes", currentStdlibHashes)
        return false
    }

    /**
     * Truncate every table in [store] that depends on typechecker/lowerer
     * output. Leaves modules + parsed_modules + metadata + apps registry
     * (which is just name→entry mapping, restored on next build/run).
     */
    private fun wipeAllTypecheckDerivedState(store: MezzeStore) {
        // We need raw SQL since some of these tables aren't exposed by
        // the MezzeStore interface as "clear all" operations. Cast where
        // we have to.
        if (store is com.mezze.language.codebase.SQLiteMezzeStore) {
            // Wipe only the project DB ("main." prefix). Attached stdlib
            // and deps DBs are read-only and re-seeded separately; we'd
            // get a "table is locked" error trying to DELETE from them
            // anyway. Tables not in the project schema are silently
            // skipped via try/catch.
            store.executeRaw { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { s ->
                        for (table in listOf(
                            "typed_modules", "blobs", "app_functions",
                            "app_performers", "names", "decls", "usages",
                            "imports", "edges", "mono_specializations",
                        )) {
                            try { s.executeUpdate("DELETE FROM main.$table") } catch (_: Exception) {}
                        }
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        } else {
            // In-memory / other stores: best-effort per-module clear.
            // No tests rely on this path being a full wipe.
            for (mod in store.getAllModules()) {
                if (mod.path.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)) continue
                store.clearTypedModulesForPath(mod.path)
            }
        }
    }
}
