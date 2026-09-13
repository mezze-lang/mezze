package com.mezze.language.cli.`package`

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises `ResolvedJarsClasspath.collect` — the aggregator that walks
 * the resolved dep graph, reads each package's `[jars]` table, and hands
 * back the local jar files for the wire child's classpath.
 */
class ResolvedJarsClasspathTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Write [content] to a store jar `<storeRoot>/jars/<sha>/<name>` and return the sha. */
    private fun writeJar(storeRoot: File, jarName: String, content: ByteArray = "jar-bytes".toByteArray()): Pair<String, File> {
        val sha = hex(MessageDigest.getInstance("SHA-256").digest(content))
        val dir = File(storeRoot, "jars/$sha").apply { mkdirs() }
        val jar = File(dir, jarName).apply { writeBytes(content) }
        return sha to jar
    }

    /** Minimal Mezze.toml with an optional `[jars]` block. */
    private fun writeToml(dir: File, name: String, version: String, jars: Map<String, String> = emptyMap()) {
        val body = buildString {
            appendLine("[package]")
            appendLine("name = \"$name\"")
            appendLine("version = \"$version\"")
            if (jars.isNotEmpty()) {
                appendLine()
                appendLine("[jars]")
                for ((k, v) in jars) appendLine("$k = \"$v\"")
            }
        }
        File(dir, "Mezze.toml").writeText(body)
    }

    @Test
    fun `walks root and resolved-dep jars`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }

        // A jar declared by the root user project.
        val (userSha, userJar) = writeJar(storeRoot, "user-lib.jar", "user".toByteArray())
        // A jar declared by a path-dep.
        val (depSha, depJar) = writeJar(storeRoot, "dep-lib.jar", "dep".toByteArray())

        val depDir = File(root, "utils").apply { mkdirs() }
        writeToml(depDir, "utils", "0.1.0", jars = mapOf(
            "dep_lib" to "https://example.com/dep-lib.jar#sha256:$depSha"
        ))

        val projectRoot = File(root, "app").apply { mkdirs() }
        writeToml(projectRoot, "app", "0.1.0", jars = mapOf(
            "user_lib" to "https://example.com/user-lib.jar#sha256:$userSha"
        ))

        // Hand-write a lockfile: root + one path-dep with a jars entry.
        writeLockFile(File(projectRoot, "Mezze.lock"), MezzeLock(
            version = 2,
            root = "app",
            packages = listOf(
                LockedNode("app", "0.1.0", source = "root", dependencies = listOf("utils")),
                LockedNode("utils", "0.1.0", source = "path", path = depDir.canonicalPath, dependencies = emptyList()),
            ),
        ))

        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertTrue(report.warnings.isEmpty(), "expected no warnings, got: ${report.warnings}")
        val paths = report.jars.map { it.jarPath.canonicalPath }.toSet()
        assertEquals(setOf(userJar.canonicalPath, depJar.canonicalPath), paths)

        val cp = report.asClasspath()
        assertTrue(cp.contains(userJar.absolutePath))
        assertTrue(cp.contains(depJar.absolutePath))
        assertTrue(cp.contains(File.pathSeparator))
    }

    @Test
    fun `missing store jar produces a warning, does not throw`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }
        val projectRoot = File(root, "app").apply { mkdirs() }
        // A well-formed sha256, but no jar under storeRoot/jars/<sha>/.
        val fakeSha = "a".repeat(64)
        writeToml(projectRoot, "app", "0.1.0", jars = mapOf(
            "ghost" to "https://example.com/ghost.jar#sha256:$fakeSha"
        ))
        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertTrue(report.jars.isEmpty())
        assertEquals(1, report.warnings.size)
        assertTrue(report.warnings[0].contains("not in store"))
    }

    @Test
    fun `sha256 mismatch is reported and jar skipped`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }
        val projectRoot = File(root, "app").apply { mkdirs() }

        // Real jar with sha256(A), but the toml declares sha256(B).
        val realBytes = "real".toByteArray()
        val realSha = hex(MessageDigest.getInstance("SHA-256").digest(realBytes))
        val wrongSha = "b".repeat(64)
        val dir = File(storeRoot, "jars/$wrongSha").apply { mkdirs() }
        File(dir, "impostor.jar").writeBytes(realBytes)  // stored under wrongSha
        writeToml(projectRoot, "app", "0.1.0", jars = mapOf(
            "impostor" to "https://example.com/impostor.jar#sha256:$wrongSha"
        ))

        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertTrue(report.jars.isEmpty(), "jar should be skipped on sha256 mismatch")
        assertEquals(1, report.warnings.size)
        assertTrue(report.warnings[0].contains("sha256 mismatch"))
        // sanity — the mismatch surfaces the ACTUAL hash for easy debugging.
        assertTrue(report.warnings[0].contains(realSha))
    }

    @Test
    fun `same jar declared twice dedups on sha256`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }
        val (sha, _) = writeJar(storeRoot, "shared.jar", "shared".toByteArray())

        val depDir = File(root, "utils").apply { mkdirs() }
        writeToml(depDir, "utils", "0.1.0", jars = mapOf(
            "shared" to "https://example.com/shared.jar#sha256:$sha"
        ))
        val projectRoot = File(root, "app").apply { mkdirs() }
        writeToml(projectRoot, "app", "0.1.0", jars = mapOf(
            "shared" to "https://example.com/shared.jar#sha256:$sha"
        ))
        writeLockFile(File(projectRoot, "Mezze.lock"), MezzeLock(
            version = 2,
            root = "app",
            packages = listOf(
                LockedNode("app", "0.1.0", source = "root", dependencies = listOf("utils")),
                LockedNode("utils", "0.1.0", source = "path", path = depDir.canonicalPath),
            ),
        ))
        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertEquals(1, report.jars.size, "dedup: same sha256 should appear once")
    }

    @Test
    fun `no jars — empty report`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }
        val projectRoot = File(root, "app").apply { mkdirs() }
        writeToml(projectRoot, "app", "0.1.0")
        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertTrue(report.jars.isEmpty())
        assertTrue(report.warnings.isEmpty())
        assertEquals("", report.asClasspath())
    }

    @Test
    fun `bad sha256 in toml is a warning, not a throw`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val storeRoot = File(root, "store").apply { mkdirs() }
        val projectRoot = File(root, "app").apply { mkdirs() }
        // Missing the #sha256 suffix entirely.
        writeToml(projectRoot, "app", "0.1.0", jars = mapOf(
            "shady" to "https://example.com/shady.jar"
        ))
        val report = ResolvedJarsClasspath.collect(projectRoot, storeRoot = storeRoot)
        assertTrue(report.jars.isEmpty())
        assertFalse(report.warnings.isEmpty(), "expected a warning for malformed URL")
        assertTrue(report.warnings[0].contains("bad #sha256"))
    }
}
