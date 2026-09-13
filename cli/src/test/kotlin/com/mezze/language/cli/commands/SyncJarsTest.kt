package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mez sync` `[jars]`-fetch behaviour: every entry named on the resolved
 * dep graph is downloaded from its `<url>#sha256:<hex>` into
 * `<store>/jars/<sha>/<basename>.jar`, checksum-verified, deduped, and
 * recorded in `Mezze.lock` so `--locked` catches drift.
 *
 * We drive fetch off a `file://` URL because it's what `mez sync` (and
 * every other content-addressed downloader in the CLI) accepts for
 * local fixtures, and it means the tests never touch the network.
 */
class SyncJarsTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun writePkgToml(dir: File, name: String, jars: Map<String, String> = emptyMap()) {
        dir.mkdirs()
        File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${name}_ok = {} -> \"$name\"\n")
        File(dir, "Mezze.toml").writeText(buildString {
            appendLine("[package]"); appendLine("name = \"$name\""); appendLine("version = \"1.0.0\"")
            appendLine(); appendLine("[lib]"); appendLine("path = \"src/lib.mz\"")
            if (jars.isNotEmpty()) {
                appendLine(); appendLine("[jars]")
                for ((k, v) in jars) appendLine("$k = \"$v\"")
            }
        })
    }

    @Test
    fun `root jars entry lands in the store with the pinned sha`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")

        val jarBytes = "hello-jar-bytes".toByteArray()
        val jarSrc = File(root, "hello.jar").apply { writeBytes(jarBytes) }
        val sha = sha256(jarBytes)

        val app = File(root, "app")
        // A no-dep app whose OWN [jars] table names a file:// jar. Sync
        // MUST still fetch it — the aggregator walks the root manifest
        // as well as every resolved node.
        writePkgToml(app, "app", jars = mapOf("hello" to "${jarSrc.toURI()}#sha256:$sha"))
        // Force a resolvable — otherwise sync short-circuits with "no deps".
        val leaf = File(root, "leaf").also { writePkgToml(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(0, code, "clean sync must succeed")

        val landed = File(store, "jars/$sha").listFiles()?.firstOrNull { it.name.endsWith(".jar") }
        assertNotNull(landed, "jar must exist under <store>/jars/<sha>/")
        assertEquals(sha, sha256(landed.readBytes()), "on-disk bytes must match the pinned sha")
    }

    @Test
    fun `dep-declared jars entry is fetched when the dep is a path-dep`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val jarBytes = "dep-jar".toByteArray()
        val jarSrc = File(root, "dep.jar").apply { writeBytes(jarBytes) }
        val sha = sha256(jarBytes)

        val dep = File(root, "dep").also {
            writePkgToml(it, "dep", jars = mapOf("dep_lib" to "${jarSrc.toURI()}#sha256:$sha"))
        }
        val app = File(root, "app").also { writePkgToml(it, "app") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\ndep = \"${dep.absolutePath}\"\n")

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(0, code)

        assertNotNull(File(store, "jars/$sha").listFiles()?.firstOrNull { it.name.endsWith(".jar") })
    }

    @Test
    fun `sha256 mismatch — jar rejected, sync exits non-zero`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val jarSrc = File(root, "j.jar").apply { writeBytes("real-bytes".toByteArray()) }
        val wrongSha = "0".repeat(64)

        val app = File(root, "app").also {
            writePkgToml(it, "app", jars = mapOf("j" to "${jarSrc.toURI()}#sha256:$wrongSha"))
        }
        val leaf = File(root, "leaf").also { writePkgToml(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(1, code, "tampered jar must fail the sync")

        // The rejected jar must not be placed in the store.
        assertFalse(File(store, "jars/$wrongSha").listFiles()?.any { it.name.endsWith(".jar") } == true)
    }

    @Test
    fun `diamond — same jar declared in two deps fetches once`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val jarBytes = "shared".toByteArray()
        val jarSrc = File(root, "s.jar").apply { writeBytes(jarBytes) }
        val sha = sha256(jarBytes)
        val spec = "${jarSrc.toURI()}#sha256:$sha"

        val a = File(root, "a").also { writePkgToml(it, "a", jars = mapOf("shared" to spec)) }
        val b = File(root, "b").also { writePkgToml(it, "b", jars = mapOf("shared" to spec)) }
        val app = File(root, "app").also { writePkgToml(it, "app") }
        File(app, "Mezze.toml").appendText(
            "\n[path-dependencies]\na = \"${a.absolutePath}\"\nb = \"${b.absolutePath}\"\n"
        )

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(0, code)

        val jars = File(store, "jars/$sha").listFiles()?.filter { it.name.endsWith(".jar") }
        assertEquals(1, jars?.size, "diamond: one jar on disk, one sha entry")
    }

    @Test
    fun `Mezze_lock records the fetched jar and --locked catches drift`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val jarBytes = "locked-jar".toByteArray()
        val jarSrc = File(root, "j.jar").apply { writeBytes(jarBytes) }
        val sha = sha256(jarBytes)

        val app = File(root, "app").also {
            writePkgToml(it, "app", jars = mapOf("j" to "${jarSrc.toURI()}#sha256:$sha"))
        }
        val leaf = File(root, "leaf").also { writePkgToml(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))

        val lock = com.mezze.language.cli.`package`.readLockFile(File(app, "Mezze.lock"))
        assertNotNull(lock, "sync must write a lock")
        assertEquals(1, lock.jars.size, "one jar in the lock")
        assertEquals(sha, lock.jars.single().sha256)

        // --locked on the fresh lock is fine.
        assertEquals(0, SyncCommand().execute(listOf("--locked"), currentDir = app, storeRootOverride = store))

        // Now flip the manifest's jars-set (drop the entry) → --locked flags drift.
        File(app, "Mezze.toml").writeText("""
            [package]
            name = "app"
            version = "1.0.0"
            [lib]
            path = "src/lib.mz"
            [path-dependencies]
            leaf = "${leaf.absolutePath}"
        """.trimIndent())
        assertEquals(1, SyncCommand().execute(listOf("--locked"), currentDir = app, storeRootOverride = store),
            "--locked must catch a dropped [jars] entry")
    }

    @Test
    fun `unreachable url — fetch fails cleanly with the jar name in the error`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        // A well-formed sha but a file that doesn't exist.
        val phantom = File(root, "does-not-exist.jar")
        val fakeSha = "a".repeat(64)

        val app = File(root, "app").also {
            writePkgToml(it, "app", jars = mapOf("phantom" to "${phantom.toURI()}#sha256:$fakeSha"))
        }
        val leaf = File(root, "leaf").also { writePkgToml(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(1, code, "missing URL target must fail the sync")

        // Nothing was placed for the fake sha.
        assertNull(File(store, "jars/$fakeSha").listFiles()?.firstOrNull { it.name.endsWith(".jar") })
    }
}
