package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `mez sync` local `[path-dependencies]` behavior — resolution roots at each
 * package's own dir, so a dep is symlinked into its DECLARER's `.deps/mezze/`,
 * transitively, from a single top-level sync. Covers single, transitive, diamond
 * dedup, cycle termination, and the missing-dep error path.
 */
class SyncPathDepsTest {

    /** Scaffold a minimal library package `<root>/<name>` with the given path-deps. */
    private fun lib(
        root: File,
        name: String,
        pathDeps: Map<String, String> = emptyMap(),
        version: String = "0.1.0",
    ): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${name}_val = {} -> \"$name\"\n")
        val toml = buildString {
            appendLine("[package]")
            appendLine("name = \"$name\"")
            appendLine("version = \"$version\"")
            appendLine()
            appendLine("[lib]")
            appendLine("path = \"src/lib.mz\"")
            if (pathDeps.isNotEmpty()) {
                appendLine()
                appendLine("[path-dependencies]")
                pathDeps.forEach { (k, v) -> appendLine("$k = \"$v\"") }
            }
        }
        File(dir, "Mezze.toml").writeText(toml)
        return dir
    }

    /** The `<name>-*` entry the sync created under `dir/.deps/mezze/`, if any. */
    private fun link(dir: File, name: String): File? =
        File(dir, ".deps/mezze").listFiles()?.firstOrNull { it.name.startsWith("$name-") }

    private fun sync(dir: File) = SyncCommand().execute(emptyList(), currentDir = dir)

    @Test
    fun `single path dep is symlinked into the declarer's deps`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val utils = lib(root, "utils")
        val app = lib(root, "app", pathDeps = mapOf("utils" to "../utils"))

        sync(app)

        val l = link(app, "utils")
        assertNotNull(l, "app/.deps/mezze/utils-* should exist")
        assertTrue(Files.isSymbolicLink(l!!.toPath()), "dep entry should be a symlink (live edits)")
        assertEquals(utils.canonicalFile, l.canonicalFile, "should point at the utils package")
    }

    @Test
    fun `transitive deps hoisted FLAT into the top project — no sibling pollution`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val core = lib(root, "core")
        val utils = lib(root, "utils", pathDeps = mapOf("core" to "../core"))
        val app = lib(root, "app", pathDeps = mapOf("utils" to "../utils"))

        sync(app)

        // Both utils AND core land in the TOP project's single .deps (flat).
        assertNotNull(link(app, "utils"), "app/.deps should hold utils")
        val coreLink = link(app, "core")
        assertNotNull(coreLink, "app/.deps should hold core transitively (flat, hoisted)")
        assertEquals(core.canonicalFile, coreLink!!.canonicalFile)
        // The dep source dirs must NOT be polluted with nested .deps.
        assertFalse(File(utils, ".deps").exists(), "utils/ must not get a nested .deps")
        assertFalse(File(core, ".deps").exists(), "core/ must not get a nested .deps")
    }

    @Test
    fun `diamond dedups — shared dep linked once in the flat top-level deps`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val core = lib(root, "core")
        val a = lib(root, "a", pathDeps = mapOf("core" to "../core"))
        val b = lib(root, "b", pathDeps = mapOf("core" to "../core"))
        val app = lib(root, "app", pathDeps = mapOf("a" to "../a", "b" to "../b"))

        sync(app)

        // a, b, and the shared core all in app/.deps; core exactly once.
        assertNotNull(link(app, "a"), "app should link a")
        assertNotNull(link(app, "b"), "app should link b")
        assertNotNull(link(app, "core"), "app should link the shared core")
        assertEquals(1, File(app, ".deps/mezze").listFiles()?.count { it.name.startsWith("core-") },
            "shared core appears exactly once in the flat .deps")
        assertFalse(File(a, ".deps").exists(), "a/ not polluted")
        assertFalse(File(b, ".deps").exists(), "b/ not polluted")
    }

    @Test
    fun `dependency cycle terminates and links all in the flat top-level deps`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val a = lib(root, "a", pathDeps = mapOf("b" to "../b"))
        val b = lib(root, "b", pathDeps = mapOf("a" to "../a"))
        val app = lib(root, "app", pathDeps = mapOf("a" to "../a"))

        // Must not hang — the seen-set breaks the a <-> b cycle.
        assertDoesNotThrow { sync(app) }

        // Both a and b hoisted flat into app/.deps.
        assertNotNull(link(app, "a"), "app -> a in flat .deps")
        assertNotNull(link(app, "b"), "b (via a) hoisted into flat .deps")
    }

    @Test
    fun `missing path dep is reported, not fatal`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val app = lib(root, "app", pathDeps = mapOf("nope" to "../does-not-exist"))

        assertDoesNotThrow { sync(app) }
        assertNull(link(app, "nope"), "a non-existent dep must not be linked")
    }

    @Test
    fun `sync writes the complete dependency graph to Mezze_lock`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        lib(root, "core")
        lib(root, "utils", pathDeps = mapOf("core" to "../core"))
        val app = lib(root, "app", pathDeps = mapOf("utils" to "../utils"))

        sync(app)

        val lock = com.mezze.language.cli.`package`.readLockFile(File(app, "Mezze.lock"))
        assertNotNull(lock, "Mezze.lock should be written")
        assertEquals("app", lock!!.root)
        assertEquals(setOf("app", "utils", "core"), lock.packages.map { it.name }.toSet(), "whole transitive graph")

        val appNode = lock.packages.first { it.name == "app" }
        assertEquals("root", appNode.source)
        assertEquals(listOf("utils"), appNode.dependencies, "root's edges")

        val utilsNode = lock.packages.first { it.name == "utils" }
        assertEquals("path", utilsNode.source)
        assertEquals(listOf("core"), utilsNode.dependencies, "utils -> core edge recorded")
    }
}
