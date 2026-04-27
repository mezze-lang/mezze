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
     * Walk every module recorded in [store]. For each one, hash its source
     * file on disk and look up a cached typed module at that hash. If the
     * source on disk matches what was typed before, the [hydrate] callback
     * is invoked with the deserialized blob bytes and the module path.
     *
     * Bad blobs (failed deserialization) are pruned to prevent infinite retry.
     *
     * @param store the project store to walk
     * @param hydrate callback that takes (modulePath, typedBlob); should
     *                deserialize and put into the in-memory cache. Returning
     *                false signals a deserialization error (treated as miss
     *                + prune).
     * @return statistics for diagnostic logging
     */
    fun hydrateTypedModules(
        store: MezzeStore,
        hydrate: (modulePath: String, typedBlob: ByteArray) -> Boolean
    ): Stats {
        val start = System.nanoTime()
        var hits = 0
        var misses = 0

        for (mod in store.getAllModules()) {
            val filePath = mod.filePath ?: continue
            val file = File(filePath).takeIf { it.exists() } ?: continue
            val onDiskHash = sha256(file.readText())
            if (onDiskHash != mod.sourceHash) {
                misses++
                continue
            }
            val blob = store.getTypedModule(mod.path, onDiskHash) ?: run {
                misses++
                null
            } ?: continue

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

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
