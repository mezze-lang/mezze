package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `mez sync` `[maven]`-resolve behaviour: every entry named on the
 * resolved dep graph is fetched via Apache Aether from Maven Central
 * plus any `[[maven-repo]]` remotes, into `~/.mezze/cache/maven/`,
 * checksum-verified by Aether, and recorded in `Mezze.lock`.
 *
 * Tests hit a `file://` maven repo they publish into a `TempDir`, so
 * they never touch the network. `MEZZE_HOME` is overridden per-test
 * to isolate the maven cache — that's what SyncCommand reads for
 * `cache/maven/`.
 */
class SyncMavenTest {

    // ─── fixture helpers ─────────────────────────────────────────────

    /** Minimal Mezze package with an optional `[maven]` + `[[maven-repo]]`. */
    private fun writePkg(
        dir: File,
        name: String,
        maven: Map<String, String> = emptyMap(),
        repos: List<Pair<String, String>> = emptyList(),
    ) {
        dir.mkdirs(); File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${name}_ok = {} -> \"$name\"\n")
        File(dir, "Mezze.toml").writeText(buildString {
            appendLine("[package]"); appendLine("name = \"$name\""); appendLine("version = \"1.0.0\"")
            appendLine(); appendLine("[lib]"); appendLine("path = \"src/lib.mz\"")
            if (maven.isNotEmpty()) {
                appendLine(); appendLine("[maven]")
                for ((k, v) in maven) appendLine("\"$k\" = \"$v\"")
            }
            for ((id, url) in repos) {
                appendLine(); appendLine("[[maven-repo]]")
                appendLine("id = \"$id\"")
                appendLine("url = \"$url\"")
            }
        })
    }

    /** Publish a tiny maven artifact into a `file://` repo fixture. */
    private fun publishArtifact(
        repoDir: File,
        group: String,
        artifact: String,
        version: String,
    ) {
        val dir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version").also { it.mkdirs() }
        val jarFile = File(dir, "$artifact-$version.jar")
        JarOutputStream(jarFile.outputStream(), Manifest()).use { out ->
            val e = JarEntry("mezze/${artifact}-marker.txt")
            out.putNextEntry(e); out.write("$group:$artifact:$version".toByteArray()); out.closeEntry()
        }
        val pomFile = File(dir, "$artifact-$version.pom")
        pomFile.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>$group</groupId>\n" +
                "  <artifactId>$artifact</artifactId>\n" +
                "  <version>$version</version>\n" +
                "  <packaging>jar</packaging>\n" +
                "</project>\n",
        )
        // Aether's default checksum policy is `fail` — a missing .sha1 aborts.
        writeSha1(jarFile); writeSha1(pomFile)
    }

    private fun writeSha1(f: File) {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val hex = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        File(f.absolutePath + ".sha1").writeText(hex)
    }

    private fun runSync(app: File, mavenCache: File, storeRoot: File, args: List<String> = emptyList()): Int =
        SyncCommand().execute(
            args,
            currentDir = app,
            storeRootOverride = storeRoot,
            mavenCacheOverride = mavenCache,
        )

    // ─── tests ───────────────────────────────────────────────────────

    /**
     * A package whose ONLY dependency is a `[maven]` coord still syncs.
     *
     * It did not. `hasDeps` counted `[dependencies]`, `[dev-dependencies]` and
     * `[path-dependencies]` and nothing else, so a maven-only package printed
     * "No dependencies to sync" and returned 0 — success, with nothing fetched.
     * Every other test in this file hid it by adding a path-dep purely to get
     * past the short-circuit, which is what kept it alive.
     */
    @Test
    fun `a maven-only package is not short-circuited as having no dependencies`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val mavenCache = File(root, "maven-cache").apply { mkdirs() }
        val store = File(root, "store")
        val repoDir = File(root, "repo").apply { mkdirs() }

        publishArtifact(repoDir, "com.example", "solo", "2.0.0")

        val app = File(root, "app")
        writePkg(
            app, "app",
            maven = mapOf("com.example:solo" to "2.0.0"),
            repos = listOf("local" to repoDir.toURI().toString()),
        )
        // Deliberately NO path-dep, NO registry dep — the maven entry is the only one.

        assertEquals(0, runSync(app, mavenCache, store))

        val jar = File(mavenCache, "com/example/solo/2.0.0/solo-2.0.0.jar")
        assertTrue(
            jar.isFile,
            "a maven-only package must still resolve its coords — sync reported success " +
                "without fetching anything at ${jar.absolutePath}",
        )
    }

    @Test
    fun `root maven entry lands in the cache and the lock`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val mavenCache = File(root, "maven-cache").apply { mkdirs() }
        val store = File(root, "store")
        val repoDir = File(root, "repo").apply { mkdirs() }

        publishArtifact(repoDir, "com.example", "hello", "1.0.0")

        val app = File(root, "app")
        writePkg(
            app, "app",
            maven = mapOf("com.example:hello" to "1.0.0"),
            repos = listOf("local" to repoDir.toURI().toString()),
        )
        // A path-dep alongside the maven one. This used to be REQUIRED — sync's
        // "no deps" short-circuit did not count `[maven]`, so without it nothing
        // ran. That is fixed (see the maven-only test below); the path-dep stays
        // here because this test is about the two coexisting.
        val leaf = File(root, "leaf").also { writePkg(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        assertEquals(0, runSync(app, mavenCache, store))

        val jar = File(mavenCache, "com/example/hello/1.0.0/hello-1.0.0.jar")
        assertTrue(jar.isFile, "Aether must place the jar at the maven-layout path: ${jar.absolutePath}")

        val lock = com.mezze.language.cli.`package`.readLockFile(File(app, "Mezze.lock"))
        assertNotNull(lock, "sync must write a lock")
        assertEquals(1, lock.maven.size, "one maven artifact in the lock")
        val row = lock.maven.single()
        assertEquals("com.example:hello:1.0.0", row.coord)
        assertEquals("com/example/hello/1.0.0/hello-1.0.0.jar", row.resolvedFilePath)
        assertEquals("app", row.declaredBy)
        assertEquals(64, row.sha256.length)
    }

    @Test
    fun `--locked catches a dropped maven coord`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val mavenCache = File(root, "maven-cache").apply { mkdirs() }
        val store = File(root, "store")
        val repoDir = File(root, "repo").apply { mkdirs() }

        publishArtifact(repoDir, "com.example", "hello", "1.0.0")

        val app = File(root, "app")
        writePkg(
            app, "app",
            maven = mapOf("com.example:hello" to "1.0.0"),
            repos = listOf("local" to repoDir.toURI().toString()),
        )
        val leaf = File(root, "leaf").also { writePkg(it, "leaf") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\nleaf = \"${leaf.absolutePath}\"\n")

        assertEquals(0, runSync(app, mavenCache, store))
        assertEquals(0, runSync(app, mavenCache, store, args = listOf("--locked")))

        // Drop the [maven] section → --locked flags drift.
        File(app, "Mezze.toml").writeText(
            """
            [package]
            name = "app"
            version = "1.0.0"
            [lib]
            path = "src/lib.mz"
            [path-dependencies]
            leaf = "${leaf.absolutePath}"
            """.trimIndent(),
        )
        assertEquals(1, runSync(app, mavenCache, store, args = listOf("--locked")),
            "--locked must catch a dropped [maven] entry")
    }

    @Test
    fun `dep-declared maven coord is resolved`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val mavenCache = File(root, "maven-cache").apply { mkdirs() }
        val store = File(root, "store")
        val repoDir = File(root, "repo").apply { mkdirs() }

        publishArtifact(repoDir, "com.example", "shared", "2.1.0")

        val dep = File(root, "dep").also {
            writePkg(
                it, "dep",
                maven = mapOf("com.example:shared" to "2.1.0"),
                repos = listOf("local" to repoDir.toURI().toString()),
            )
        }
        val app = File(root, "app").also { writePkg(it, "app") }
        File(app, "Mezze.toml").appendText("\n[path-dependencies]\ndep = \"${dep.absolutePath}\"\n")

        assertEquals(0, runSync(app, mavenCache, store))

        val jar = File(mavenCache, "com/example/shared/2.1.0/shared-2.1.0.jar")
        assertTrue(jar.isFile, "dep-declared maven jar must land in the cache: ${jar.absolutePath}")
        val lock = com.mezze.language.cli.`package`.readLockFile(File(app, "Mezze.lock"))!!
        assertEquals("dep", lock.maven.single().declaredBy)
    }
}
