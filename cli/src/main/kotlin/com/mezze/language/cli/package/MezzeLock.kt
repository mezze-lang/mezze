package com.mezze.language.cli.`package`

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
    /**
     * The compiled-IR compat version the graph was resolved under
     * (`StoreSerialization.IR_VERSION`). Records which IR format a consumer would
     * need to load any shipped compiled dep IR as-is vs. recompile from source
     * (see versioning-governance.md). Defaulted so older locks still parse.
     */
    val ir: Int = 0,
    val packages: List<LockedNode> = emptyList(),
    /**
     * Every `[jars]` entry named on the resolved graph, deduped on
     * `sha256` and stable-sorted. `mez sync` fetches each into
     * `~/.mezze/store/jars/<sha256>/`; `--locked` verification treats a
     * mismatch here (a jar added / removed / re-hashed) as drift, the
     * same way it treats package graph drift.
     */
    val jars: List<LockedJar> = emptyList(),
    /**
     * Every `[maven]` coord (root's + every dep's) plus their
     * transitives, as resolved by Apache Aether at sync time.
     * Deduped on `coord` and stable-sorted. `mez sync --locked`
     * treats a mismatch here (a coord added / removed / re-hashed)
     * as drift.
     *
     * `resolved_file_path` is the maven-layout relative path
     * (`<group>/<artifact>/<version>/<artifact>-<version>.jar`)
     * under `~/.mezze/cache/maven/` — repeatable across machines
     * because Aether writes the same layout everywhere.
     */
    val maven: List<LockedMaven> = emptyList(),
)

/**
 * One `[jars]` table entry as pinned in the lock. The `name` is the
 * key the entry had in its source manifest (possibly under multiple
 * packages — the first-declaring one wins for the label); `declaredBy`
 * is the package that first claimed this sha, kept as an informational
 * back-pointer to the origin.
 */
/**
 * One `[maven]` coord (root's + every dep's + transitives) as pinned
 * in the lock. `coord` is Aether-normalized (`<group>:<artifact>:<version>`);
 * `resolved_file_path` is relative to the maven cache dir so the lock
 * stays portable. `transitive_from` names the coord that pulled this
 * one in — an artifact declared directly has itself, keeping the
 * origin chain readable.
 */
@Serializable
data class LockedMaven(
    val coord: String,
    /** Relative path within `~/.mezze/cache/maven/` (maven layout). */
    @SerialName("resolved_file_path")
    val resolvedFilePath: String,
    /** sha256 hex of the jar's bytes (64 chars). */
    val sha256: String,
    /** Coord that pulled this artifact in — self-reference for direct declarations. */
    @SerialName("transitive_from")
    val transitiveFrom: String,
    /** Package that first declared this coord in the resolved graph. */
    val declaredBy: String,
)

@Serializable
data class LockedJar(
    val name: String,
    /** Full spec verbatim (`<url>#sha256:<hex>`). */
    val url: String,
    /** sha256 hex (64 chars) — the store key. */
    val sha256: String,
    /** Package name that first declared this jar in the resolved graph. */
    val declaredBy: String,
)

@Serializable
data class LockedNode(
    val name: String,
    val version: String,
    /** "root" | "path" | "registry" | "github" */
    val source: String = "registry",
    /** resolved path — path deps only (informational; local + mutable, no integrity) */
    val path: String? = null,
    /** tarball URL — registry deps only */
    val url: String? = null,
    /** "sha256:…" integrity — registry deps only */
    val checksum: String? = null,
    /** github deps only — owner segment of `github:<owner>/<repo>@<ref>` */
    val owner: String? = null,
    /** github deps only — repo segment */
    val repo: String? = null,
    /** github deps only — the ref (tag/branch/sha) the user wrote */
    val ref: String? = null,
    /** github deps only — the 40-char commit sha the ref resolved to at sync time */
    val commit: String? = null,
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
