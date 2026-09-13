package com.mezze.language.cli.`package`

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
import kotlin.test.fail

/**
 * Hermetic tests for `MavenResolver` — a fake maven repo on the local
 * filesystem, served over `file://`. Aether treats a `file://` remote
 * identically to `https://` so this exercises the real resolver code
 * path with zero network I/O.
 *
 * Fixtures write a minimal maven layout:
 *
 *     <repo>/<group>/<artifact>/<version>/<artifact>-<version>.jar
 *     <repo>/<group>/<artifact>/<version>/<artifact>-<version>.pom
 *     <repo>/<group>/<artifact>/maven-metadata.xml
 *
 * Every jar/pom carries its `.sha1` alongside — Aether's default
 * checksum policy is `fail`, so missing/mismatched checksums surface
 * as resolution failures (exactly what we want in production).
 */
class MavenResolverTest {

    // ─── single coord resolution ─────────────────────────────────────

    @Test
    fun `single coord resolves into the cache dir`(@TempDir tmp: Path) {
        val repoDir = File(tmp.toFile(), "repo").also { it.mkdirs() }
        val cacheDir = File(tmp.toFile(), "cache").also { it.mkdirs() }

        publishArtifact(repoDir, "com.example", "hello", "1.0.0", deps = emptyList())

        val remotes = listOf(MavenResolver.MavenRemote(id = "local", url = repoDir.toURI().toString()))
        val resolved = MavenResolver.resolve(
            coords = listOf("com.example:hello:1.0.0"),
            cacheDir = cacheDir.toPath(),
            repos = remotes,
        )

        assertEquals(1, resolved.size, "one artifact requested → one resolved")
        val ar = resolved.single()
        assertEquals("com.example:hello:1.0.0", ar.coord)
        // Lands under the maven layout Aether uses for its local repo.
        val expected = File(cacheDir, "com/example/hello/1.0.0/hello-1.0.0.jar")
        assertEquals(expected.absolutePath, ar.file.toFile().absolutePath)
        assertTrue(ar.file.toFile().isFile, "resolved jar must be on disk")
        assertEquals(64, ar.sha256.length, "sha256 hex is 64 chars")
    }

    // ─── transitive resolution ───────────────────────────────────────

    @Test
    fun `transitive dep is resolved alongside its parent`(@TempDir tmp: Path) {
        val repoDir = File(tmp.toFile(), "repo").also { it.mkdirs() }
        val cacheDir = File(tmp.toFile(), "cache").also { it.mkdirs() }

        publishArtifact(repoDir, "com.example", "leaf", "1.0.0", deps = emptyList())
        publishArtifact(
            repoDir, "com.example", "parent", "1.0.0",
            deps = listOf(Triple("com.example", "leaf", "1.0.0")),
        )

        val remotes = listOf(MavenResolver.MavenRemote(id = "local", url = repoDir.toURI().toString()))
        val resolved = MavenResolver.resolve(
            coords = listOf("com.example:parent:1.0.0"),
            cacheDir = cacheDir.toPath(),
            repos = remotes,
        )

        // Both parent and leaf come back — deterministic order (sorted by coord).
        val coords = resolved.map { it.coord }
        assertEquals(listOf("com.example:leaf:1.0.0", "com.example:parent:1.0.0"), coords)
    }

    // ─── errors ──────────────────────────────────────────────────────

    @Test
    fun `bad coord throws with a clear message`(@TempDir tmp: Path) {
        val cacheDir = File(tmp.toFile(), "cache").also { it.mkdirs() }
        val repoDir = File(tmp.toFile(), "repo").also { it.mkdirs() }
        val remotes = listOf(MavenResolver.MavenRemote(id = "local", url = repoDir.toURI().toString()))

        try {
            MavenResolver.resolve(
                coords = listOf("not-a-coord"),
                cacheDir = cacheDir.toPath(),
                repos = remotes,
            )
            fail("bad coord must throw")
        } catch (e: MavenResolver.MavenResolverException) {
            assertTrue(
                e.message?.contains("not-a-coord") == true,
                "error must name the offending coord: ${e.message}",
            )
        }
    }

    @Test
    fun `unknown coord fails with the coord in the error`(@TempDir tmp: Path) {
        val cacheDir = File(tmp.toFile(), "cache").also { it.mkdirs() }
        val repoDir = File(tmp.toFile(), "repo").also { it.mkdirs() }
        val remotes = listOf(MavenResolver.MavenRemote(id = "local", url = repoDir.toURI().toString()))

        try {
            MavenResolver.resolve(
                coords = listOf("com.example:absent:9.9.9"),
                cacheDir = cacheDir.toPath(),
                repos = remotes,
            )
            fail("unknown coord must throw")
        } catch (e: MavenResolver.MavenResolverException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("com.example:absent:9.9.9"),
                "error must reference the missing coord: ${e.message}",
            )
        }
    }

    // ─── fixture: publish a minimal artifact into a file:// maven repo ─

    /**
     * Write `<group>/<artifact>/<version>/<artifact>-<version>.{jar,pom}`
     * into [repoDir] along with each file's `.sha1`. [deps] is a list of
     * `(group, artifact, version)` triples that become `<dependencies>`
     * inside the pom so transitive resolution has something to walk.
     */
    private fun publishArtifact(
        repoDir: File,
        group: String,
        artifact: String,
        version: String,
        deps: List<Triple<String, String, String>>,
    ) {
        val dir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version").also { it.mkdirs() }

        // Tiny jar (empty manifest + one dummy resource so it's a valid archive).
        val jarFile = File(dir, "$artifact-$version.jar")
        JarOutputStream(jarFile.outputStream(), Manifest()).use { out ->
            val e = JarEntry("mezze/${artifact}-marker.txt")
            out.putNextEntry(e)
            out.write("$group:$artifact:$version".toByteArray())
            out.closeEntry()
        }

        val pomFile = File(dir, "$artifact-$version.pom")
        pomFile.writeText(buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">")
            appendLine("  <modelVersion>4.0.0</modelVersion>")
            appendLine("  <groupId>$group</groupId>")
            appendLine("  <artifactId>$artifact</artifactId>")
            appendLine("  <version>$version</version>")
            appendLine("  <packaging>jar</packaging>")
            if (deps.isNotEmpty()) {
                appendLine("  <dependencies>")
                for ((g, a, v) in deps) {
                    appendLine("    <dependency>")
                    appendLine("      <groupId>$g</groupId>")
                    appendLine("      <artifactId>$a</artifactId>")
                    appendLine("      <version>$v</version>")
                    appendLine("    </dependency>")
                }
                appendLine("  </dependencies>")
            }
            appendLine("</project>")
        })

        // Aether verifies these on every fetch.
        writeSha1(jarFile)
        writeSha1(pomFile)
    }

    private fun writeSha1(f: File) {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val hex = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        File(f.absolutePath + ".sha1").writeText(hex)
    }
}
