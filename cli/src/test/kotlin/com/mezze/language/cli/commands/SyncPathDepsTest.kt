package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `transitive deps set up from one top-level sync`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val core = lib(root, "core")
        val utils = lib(root, "utils", pathDeps = mapOf("core" to "../core"))
        val app = lib(root, "app", pathDeps = mapOf("utils" to "../utils"))

        sync(app)

        assertNotNull(link(app, "utils"), "app should link utils")
        val coreLink = link(utils, "core")
        assertNotNull(coreLink, "utils should link core transitively (into utils/.deps)")
        assertEquals(core.canonicalFile, coreLink!!.canonicalFile)
    }

    @Test
    fun `diamond dedups — shared dep linked once per declarer`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val core = lib(root, "core")
        val a = lib(root, "a", pathDeps = mapOf("core" to "../core"))
        val b = lib(root, "b", pathDeps = mapOf("core" to "../core"))
        val app = lib(root, "app", pathDeps = mapOf("a" to "../a", "b" to "../b"))

        sync(app)

        assertNotNull(link(a, "core"), "a should link core")
        assertNotNull(link(b, "core"), "b should link core")
        // exactly one core-* entry under each declarer (no duplicate versions left)
        assertEquals(1, File(a, ".deps/mezze").listFiles()?.count { it.name.startsWith("core-") })
    }

    @Test
    fun `dependency cycle terminates and links both directions`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val a = lib(root, "a", pathDeps = mapOf("b" to "../b"))
        val b = lib(root, "b", pathDeps = mapOf("a" to "../a"))
        val app = lib(root, "app", pathDeps = mapOf("a" to "../a"))

        // Must not hang — the seen-set breaks the a <-> b cycle.
        assertDoesNotThrow { sync(app) }

        assertNotNull(link(app, "a"), "app -> a")
        assertNotNull(link(a, "b"), "a -> b")
        assertNotNull(link(b, "a"), "b -> a")
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
