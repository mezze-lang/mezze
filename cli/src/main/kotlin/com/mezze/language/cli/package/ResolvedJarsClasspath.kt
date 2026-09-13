package com.mezze.language.cli.`package`

import com.akuleshov7.ktoml.Toml
import java.io.File
import java.security.MessageDigest

/**
 * Commit 5 — aggregate `[jars]` entries from every synced package into a
 * single JVM classpath the wire child sees at `-cp` time.
 *
 * The store layout is content-addressed: each jar lives at
 * `~/.mezze/store/jars/<sha256>/<basename>.jar`. This module walks the
 * project's `Mezze.lock`, reads each resolved package's `Mezze.toml`,
 * parses the `[jars]` table, and produces the list of local jar files.
 *
 * Not required by this commit to actually fetch missing jars — the
 * caller is expected to have run `mez sync` first, so every jar named
 * on the resolved graph is already extracted into the store. Missing
 * jars are LOGGED to stderr and skipped so a partially-primed store
 * still boots; the sha256 mismatch case is a hard warning.
 */
object ResolvedJarsClasspath {

    /** Single jar entry the wire child will add to its classpath. */
    data class JarEntry(
        /** `name` as it appeared in `[jars]` — the map key. */
        val name: String,
        /** URL#sha256 as declared in the source toml. */
        val declaredUrl: String,
        /** sha256 hex (64 chars). */
        val sha256: String,
        /** Absolute path to the extracted jar in the store. */
        val jarPath: File,
    )

    /** Result of one lookup pass. */
    data class Report(
        val jars: List<JarEntry>,
        /** Warnings that DID NOT stop us — missing store entries, unreadable tomls, etc. */
        val warnings: List<String>,
    ) {
        /** Classpath-ready `File.pathSeparator`-joined string, or empty. */
        fun asClasspath(): String =
            if (jars.isEmpty()) "" else jars.joinToString(File.pathSeparator) { it.jarPath.absolutePath }
    }

    /**
     * Walk [projectRoot]'s Mezze.lock, collect every jar entry from every
     * resolved package, and verify each one against the local store.
     * Also reads the root project's own `[jars]` from its Mezze.toml —
     * user projects contribute their own JVM deps here too.
     */
    fun collect(projectRoot: File, storeRoot: File? = null): Report {
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<JarEntry>()
        val store = storeRoot ?: defaultStoreRoot()

        // Root project's own jars — they aren't in Mezze.lock (root isn't
        // "resolved from" anywhere), so we always add them separately.
        collectFromToml(File(projectRoot, "Mezze.toml"), store, entries, warnings)

        // Every resolved dep's own jars.
        val lock = try {
            readLockFile(File(projectRoot, "Mezze.lock"))
        } catch (e: Exception) {
            warnings += "Mezze.lock: unreadable — ${e.message ?: e.javaClass.simpleName}"
            null
        }
        if (lock != null) {
            for (node in lock.packages) {
                if (node.source == "root") continue
                val tomlFile = tomlForNode(projectRoot, node) ?: continue
                collectFromToml(tomlFile, store, entries, warnings)
            }
        }

        // Dedup on sha256 — the same jar can be declared by both a user
        // project and one of its deps; we want it on the classpath once.
        val distinct = entries.distinctBy { it.sha256 }
        return Report(distinct, warnings)
    }

    /** Where extracted jars live (`~/.mezze/store/jars/<sha>/…`). */
    fun defaultStoreRoot(): File =
        System.getenv("MEZZE_STORE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".mezze/store")

    // ─── internals ─────────────────────────────

    /**
     * Locate the `Mezze.toml` for a resolved graph node.
     *
     *   - `path` deps: it's under `node.path`.
     *   - `registry` deps: it's under the content-addressed store
     *     (`<store>/<sha>/`); the sha is on `node.checksum`.
     *   - `github` deps: same store, keyed on `commit`-derived sha.
     *
     * Returns null when we can't locate it (broken lock, unfinished
     * sync); the caller downgrades to a warning.
     */
    private fun tomlForNode(projectRoot: File, node: LockedNode): File? {
        return when (node.source) {
            "path" -> {
                val dir = node.path?.let { File(it) } ?: return null
                File(dir, "Mezze.toml").takeIf { it.isFile }
            }
            "registry" -> {
                val checksum = node.checksum?.removePrefix("sha256:") ?: return null
                File(defaultStoreRoot(), "$checksum/Mezze.toml").takeIf { it.isFile }
            }
            "github" -> {
                // Commit 3 records the resolved commit sha; the store dir
                // is keyed on the same value. When the LockedNode isn't
                // github-shaped yet (older lock), just symlink-fallback.
                val commit = node.commit
                if (commit != null) {
                    File(defaultStoreRoot(), "$commit/Mezze.toml").takeIf { it.isFile }
                } else {
                    // Fallback: use the symlink `.deps/mezze/<name>-<ref>/`.
                    node.ref?.let { r ->
                        File(projectRoot, ".deps/mezze/${node.name}-$r/Mezze.toml").takeIf { it.isFile }
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Parse [tomlFile] and extract every `[jars]` entry into [entries].
     * Missing store artifacts + bad sha256 shape are recorded in
     * [warnings]; the caller may still boot with a partial classpath.
     */
    private fun collectFromToml(
        tomlFile: File,
        storeRoot: File,
        entries: MutableList<JarEntry>,
        warnings: MutableList<String>,
    ) {
        if (!tomlFile.isFile) return
        val toml = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlFile.readText())
        } catch (e: Exception) {
            warnings += "${tomlFile.absolutePath}: unreadable — ${e.message ?: e.javaClass.simpleName}"
            return
        }
        for ((name, spec) in toml.jars) {
            val sha = spec.substringAfter("#sha256:", missingDelimiterValue = "")
            if (sha.length != 64 || !sha.all { it.isHexChar() }) {
                warnings += "${tomlFile.absolutePath}: [jars].$name: bad #sha256 in '$spec'"
                continue
            }
            val jarDir = File(storeRoot, "jars/$sha")
            val jar = firstJarIn(jarDir)
            if (jar == null) {
                warnings += "${tomlFile.absolutePath}: [jars].$name: not in store " +
                    "at ${jarDir.absolutePath} — run 'mez sync' to fetch"
                continue
            }
            // Verify. Reading + hashing a jar takes ~a few ms; still under
            // the 100ms budget for a normal-sized project. Skippable by
            // env var if a very large project makes this hurt.
            if (System.getenv("MEZZE_SKIP_JAR_VERIFY") != "1") {
                val actual = sha256Hex(jar)
                if (!actual.equals(sha, ignoreCase = true)) {
                    warnings += "${tomlFile.absolutePath}: [jars].$name: sha256 mismatch — " +
                        "expected $sha, got $actual — skipping (retry 'mez sync --clean')"
                    continue
                }
            }
            entries += JarEntry(name = name, declaredUrl = spec, sha256 = sha, jarPath = jar)
        }
    }

    private fun firstJarIn(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.firstOrNull()

    private fun Char.isHexChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
