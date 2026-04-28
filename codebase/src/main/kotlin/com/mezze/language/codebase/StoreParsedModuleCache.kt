package com.mezze.language.codebase

import com.mezze.language.parser.ParsedModuleCache
import com.mezze.language.parsedast.ParsedModule
import java.security.MessageDigest

/**
 * MezzeStore-backed ParsedModuleCache. The CLI / LSP install one of these
 * via `ParsedModuleCacheHolder.set(...)` so module resolution can skip the
 * parser when the project store already has a row for the file's bytes.
 *
 * The cache is keyed by `(modulePath, sourceHash)` in the store, but the
 * lookup signature takes `(filePath, sourceHash)` because parser callers
 * don't all know the module path. We resolve `filePath → modulePath`
 * through `modules.file_path` (an indexed column).
 *
 * Misses (no row, mismatched hash, or any deserialization error) return
 * null so callers fall through to a clean parse — caching is opportunistic.
 */
class StoreParsedModuleCache(
    private val store: MezzeStore
) : ParsedModuleCache {

    override fun get(filePath: String, sourceHash: String): ParsedModule? {
        return try {
            val modulePath = resolveModulePath(filePath) ?: return null
            val blob = store.getParsedModule(modulePath, sourceHash) ?: return null
            CodebaseSerialization.deserializeParsedModule(blob)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Look up the module path for a given file path. The store keeps this
     * mapping in `modules.file_path`. Called once per parse so we cache the
     * result to avoid hitting SQLite on every recursive import.
     */
    private fun resolveModulePath(filePath: String): String? {
        pathCache[filePath]?.let { return it }
        val match = store.getAllModules().firstOrNull { it.filePath == filePath } ?: return null
        pathCache[filePath] = match.path
        return match.path
    }

    private val pathCache = java.util.concurrent.ConcurrentHashMap<String, String>()
}

/**
 * SHA-256 hex of source text. Matches the keying StorePopulator uses so
 * cache reads find rows that StorePopulator just wrote.
 */
fun sha256Hex(source: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
}
