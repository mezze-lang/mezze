package com.funk.language.cli.`package`

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Data classes representing Funk.lock file structure
 *
 * Example Funk.lock:
 * ```json
 * {
 *   "version": 1,
 *   "packages": [
 *     {
 *       "name": "json",
 *       "version": "1.2.3",
 *       "url": "https://github.com/funk-lang/json/releases/download/v1.2.3/json.tar.gz",
 *       "checksum": "sha256:abc123..."
 *     }
 *   ]
 * }
 * ```
 */
@Serializable
data class FunkLock(
    val version: Int = 1,
    val packages: List<LockedPackage> = emptyList()
)

@Serializable
data class LockedPackage(
    val name: String,
    val version: String,
    val url: String,
    val checksum: String
)

/**
 * Read Funk.lock file from disk
 */
fun readLockFile(lockFile: File): FunkLock? {
    if (!lockFile.exists()) {
        return null
    }

    return try {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        json.decodeFromString<FunkLock>(lockFile.readText())
    } catch (e: Exception) {
        throw IllegalStateException(
            "❌ Failed to parse Funk.lock\n\n" +
            "The lock file is corrupted or has invalid JSON syntax.\n" +
            "Error: ${e.message}\n\n" +
            "💡 Try deleting Funk.lock and running 'funk sync' to regenerate it.",
            e
        )
    }
}

/**
 * Write Funk.lock file to disk
 */
fun writeLockFile(lockFile: File, lock: FunkLock) {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        // Indent with 2 spaces for readability
    }

    try {
        lockFile.writeText(json.encodeToString(FunkLock.serializer(), lock))
    } catch (e: Exception) {
        throw IllegalStateException(
            "❌ Failed to write Funk.lock\n\n" +
            "Could not save lock file to: ${lockFile.absolutePath}\n" +
            "Error: ${e.message}\n\n" +
            "💡 Check file permissions and disk space.",
            e
        )
    }
}

/**
 * Merge new packages into existing lock file
 */
fun mergeLockFile(existing: FunkLock?, newPackages: List<LockedPackage>): FunkLock {
    if (existing == null) {
        return FunkLock(version = 1, packages = newPackages)
    }

    // Create a map of existing packages by name
    val existingMap = existing.packages.associateBy { it.name }.toMutableMap()

    // Update or add new packages
    for (pkg in newPackages) {
        existingMap[pkg.name] = pkg
    }

    return FunkLock(
        version = 1,
        packages = existingMap.values.sortedBy { it.name }
    )
}

/**
 * Remove packages from lock file
 */
fun removeLockFilePackages(existing: FunkLock, packageNames: Set<String>): FunkLock {
    return FunkLock(
        version = 1,
        packages = existing.packages.filter { it.name !in packageNames }
    )
}
