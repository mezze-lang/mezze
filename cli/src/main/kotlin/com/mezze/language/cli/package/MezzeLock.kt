package com.mezze.language.cli.`package`

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `Mezze.lock` — the **complete resolved dependency graph**. One entry per package
 * with its source, integrity (sha256 for registry deps), and its direct dependency
 * edges, so the whole transitive tree is pinned and reviewable. Written fresh by
 * `mez sync` as a full snapshot; JSON, stable-sorted for clean diffs.
 *
 * ```json
 * { "version": 2, "root": "app",
 *   "packages": [
 *     { "name": "app",  "version": "0.1.0", "source": "root",
 *       "dependencies": ["utils"] },
 *     { "name": "utils","version": "0.1.0", "source": "path", "path": "/abs/utils",
 *       "dependencies": ["json"] },
 *     { "name": "json", "version": "1.2.3", "source": "registry",
 *       "url": "https://…/json.tar.gz", "checksum": "sha256:abc…", "dependencies": [] }
 *   ] }
 * ```
 */
@Serializable
data class MezzeLock(
    val version: Int = 2,
    val root: String = "",
    val packages: List<LockedNode> = emptyList(),
)

@Serializable
data class LockedNode(
    val name: String,
    val version: String,
    /** "root" | "path" | "registry" */
    val source: String = "registry",
    /** resolved path — path deps only (informational; local + mutable, no integrity) */
    val path: String? = null,
    /** tarball URL — registry deps only */
    val url: String? = null,
    /** "sha256:…" integrity — registry deps only */
    val checksum: String? = null,
    /** direct dependency names — the graph edges */
    val dependencies: List<String> = emptyList(),
)

fun readLockFile(lockFile: File): MezzeLock? {
    if (!lockFile.exists()) return null
    return try {
        Json { ignoreUnknownKeys = true }.decodeFromString<MezzeLock>(lockFile.readText())
    } catch (e: Exception) {
        throw IllegalStateException(
            "❌ Failed to parse Mezze.lock — corrupted or invalid JSON.\n" +
                "   ${e.message}\n" +
                "💡 Delete Mezze.lock and run 'mez sync' to regenerate it.",
            e,
        )
    }
}

fun writeLockFile(lockFile: File, lock: MezzeLock) {
    val json = Json { prettyPrint = true }
    try {
        lockFile.writeText(json.encodeToString(MezzeLock.serializer(), lock) + "\n")
    } catch (e: Exception) {
        throw IllegalStateException("❌ Failed to write Mezze.lock to ${lockFile.absolutePath}\n   ${e.message}", e)
    }
}
