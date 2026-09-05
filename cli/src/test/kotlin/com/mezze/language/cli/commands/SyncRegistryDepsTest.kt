package com.mezze.language.cli.commands

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * `mez sync` registry (URL#sha256) deps: fetch (here via `file://`) → verify the
 * pinned SHA-256 → extract into the content store `<store>/<sha256>/` (deduped,
 * shared) → symlink `.deps/mezze/<name>-<version>/` into it. Covers the flat and
 * nested-tarball layouts and the mandatory checksum check.
 */
class SyncRegistryDepsTest {

    private fun pkg(dir: File, name: String, deps: Map<String, String> = emptyMap()) {
        dir.mkdirs(); File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${name}_val = {} -> \"$name\"\n")
        File(dir, "Mezze.toml").writeText(buildString {
            appendLine("[package]"); appendLine("name = \"$name\""); appendLine("version = \"1.0.0\"")
            appendLine(); appendLine("[lib]"); appendLine("path = \"src/lib.mz\"")
            if (deps.isNotEmpty()) {
                appendLine(); appendLine("[dependencies]")
                deps.forEach { (k, v) -> appendLine("$k = \"$v\"") }
            }
        })
    }

    /** tar.gz [srcDir]'s files into [out], optionally under a top-level [prefix] dir. */
    private fun tarGz(srcDir: File, out: File, prefix: String = "") {
        GzipCompressorOutputStream(FileOutputStream(out)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = prefix + srcDir.toPath().relativize(f.toPath()).toString()
                    tar.putArchiveEntry(TarArchiveEntry(f, rel))
                    f.inputStream().use { it.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun depLink(app: File, name: String): File? =
        File(app, ".deps/mezze").listFiles()?.firstOrNull { it.name.startsWith("$name-") }

    @Test
    fun `registry dep is fetched into the content store and symlinked`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val srcPkg = File(root, "greet-src").also { pkg(it, "greet") }
        val tarball = File(root, "greet.tar.gz").also { tarGz(srcPkg, it) }
        val hash = sha256(tarball)
        val store = File(root, "store")
        val app = File(root, "app").also { pkg(it, "app", mapOf("greet" to "${tarball.toURI()}#sha256:$hash")) }

        SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)

        assertTrue(File(store, "$hash/Mezze.toml").exists(), "store/<hash>/Mezze.toml should exist")
        val link = depLink(app, "greet")
        assertNotNull(link, "app/.deps/mezze/greet-* should be linked")
        assertTrue(Files.isSymbolicLink(link!!.toPath()), "dep entry should symlink the store")
        assertEquals(File(store, hash).canonicalFile, link.canonicalFile, "symlink -> store entry")
    }

    @Test
    fun `nested tarball is flattened so Mezze_toml sits at the store root`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val srcPkg = File(root, "greet-src").also { pkg(it, "greet") }
        val tarball = File(root, "greet.tar.gz").also { tarGz(srcPkg, it, prefix = "greet-1.0.0/") }
        val hash = sha256(tarball)
        val store = File(root, "store")
        val app = File(root, "app").also { pkg(it, "app", mapOf("greet" to "${tarball.toURI()}#sha256:$hash")) }

        SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)

        assertTrue(File(store, "$hash/Mezze.toml").exists(), "nested top-level dir should be flattened away")
        assertTrue(File(store, "$hash/src/lib.mz").exists(), "src should be directly under the store entry")
        assertNotNull(depLink(app, "greet"))
    }

    @Test
    fun `checksum mismatch is rejected — nothing stored or linked`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val srcPkg = File(root, "greet-src").also { pkg(it, "greet") }
        val tarball = File(root, "greet.tar.gz").also { tarGz(srcPkg, it) }
        val store = File(root, "store")
        val wrong = "0".repeat(64)
        val app = File(root, "app").also { pkg(it, "app", mapOf("greet" to "${tarball.toURI()}#sha256:$wrong")) }

        assertDoesNotThrow { SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store) }

        assertNull(depLink(app, "greet"), "a hash-mismatched dep must not be linked")
        assertFalse(File(store, wrong).exists(), "store must not contain the rejected hash")
    }

    @Test
    fun `transitive remote dep lands in the store entry's own deps`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")

        // leaf (no deps) -> tarball + hash
        val leafSrc = File(root, "leaf-src").also { pkg(it, "leaf") }
        val leafTar = File(root, "leaf.tar.gz").also { tarGz(leafSrc, it) }
        val leafHash = sha256(leafTar)

        // mid depends on leaf (registry) -> tarball + hash
        val midSrc = File(root, "mid-src").also { pkg(it, "mid", mapOf("leaf" to "${leafTar.toURI()}#sha256:$leafHash")) }
        val midTar = File(root, "mid.tar.gz").also { tarGz(midSrc, it) }
        val midHash = sha256(midTar)

        // app depends on mid (registry)
        val app = File(root, "app").also { pkg(it, "app", mapOf("mid" to "${midTar.toURI()}#sha256:$midHash")) }

        SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)

        // app -> mid
        assertNotNull(depLink(app, "mid"), "app should link mid")
        assertTrue(File(store, "$midHash/Mezze.toml").exists())
        // mid (in the store) -> leaf, linked into mid's OWN store-entry .deps (shared)
        val midLeaf = File(store, "$midHash/.deps/mezze").listFiles()?.firstOrNull { it.name.startsWith("leaf-") }
        assertNotNull(midLeaf, "mid's store entry should transitively link leaf")
        assertEquals(File(store, leafHash).canonicalFile, midLeaf!!.canonicalFile)
        assertTrue(File(store, "$leafHash/Mezze.toml").exists())
    }
}
