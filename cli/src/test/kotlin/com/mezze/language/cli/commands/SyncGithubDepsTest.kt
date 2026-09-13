package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.readLockFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `mez sync` `github:<owner>/<repo>@<ref>` deps: `git clone --depth 1 --branch
 * <ref>` a local bare mirror (subbed for `github.com` via
 * `githubBaseUrlOverride` — no network in tests) → resolve HEAD to a commit sha
 * → materialize the content-addressed store entry keyed on `sha256(commit)` →
 * symlink `.deps/mezze/<name>-<ref>/` into it → record source=`github` + owner
 * + repo + ref + commit in `Mezze.lock`.
 */
class SyncGithubDepsTest {

    /** Run a git command, throwing on non-zero exit — sharpens test failures over silent skew. */
    private fun git(cwd: File, vararg cmd: String) {
        val pb = ProcessBuilder(*cmd).directory(cwd).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw AssertionError("git timed out: ${cmd.joinToString(" ")}")
        }
        if (proc.exitValue() != 0) {
            throw AssertionError("git failed (${proc.exitValue()}): ${cmd.joinToString(" ")}\n$out")
        }
    }

    /**
     * Create a bare-repo "GitHub mirror" at `<baseDir>/<owner>/<repo>.git` and
     * seed it with a single commit that contains a valid `Mezze.toml` for
     * [pkgName], tagged as [tag]. Returns baseDir (the value to feed to
     * `githubBaseUrlOverride` — its trailing '/' is added by the fetcher).
     */
    private fun seedRepo(
        tmp: File,
        owner: String,
        repo: String,
        pkgName: String,
        tag: String = "1.0.0",
    ): File {
        val baseDir = File(tmp, "fake-github").apply { mkdirs() }
        val bare = File(baseDir, "$owner/$repo.git").apply { parentFile.mkdirs() }
        git(baseDir, "git", "init", "--bare", "-b", "main", bare.absolutePath)

        val work = File(tmp, "work-$owner-$repo").apply { mkdirs() }
        git(tmp, "git", "clone", bare.absolutePath, work.absolutePath)
        // Isolated identity — the caller's `~/.gitconfig` may not have one set,
        // and CI never will; without these, `git commit` refuses to run.
        git(work, "git", "config", "user.email", "test@mezze.local")
        git(work, "git", "config", "user.name", "test")

        File(work, "src").mkdirs()
        File(work, "src/lib.mz").writeText("pub let ${pkgName}_val = {} -> \"$pkgName\"\n")
        File(work, "Mezze.toml").writeText(
            """
            [package]
            name = "$pkgName"
            version = "1.0.0"

            [lib]
            path = "src/lib.mz"
            """.trimIndent(),
        )
        git(work, "git", "add", ".")
        git(work, "git", "commit", "-m", "init")
        git(work, "git", "tag", tag)
        git(work, "git", "push", "origin", "main")
        git(work, "git", "push", "origin", "--tags")
        return baseDir
    }

    /** The `<name>-*` link the sync created under `app/.deps/mezze/`, if any. */
    private fun depLink(app: File, name: String): File? =
        File(app, ".deps/mezze").listFiles()?.firstOrNull { it.name.startsWith("$name-") }

    /** Minimal app manifest declaring one github dep. */
    private fun writeApp(app: File, name: String, spec: String) {
        app.mkdirs(); File(app, "src").mkdirs()
        File(app, "src/lib.mz").writeText("pub let app_val = {} -> \"app\"\n")
        File(app, "Mezze.toml").writeText(
            """
            [package]
            name = "app"
            version = "0.1.0"

            [lib]
            path = "src/lib.mz"

            [dependencies]
            $name = "$spec"
            """.trimIndent(),
        )
    }

    @Test
    fun `github dep is cloned, stored under sha256(commit), symlinked, and locked with commit`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val baseDir = seedRepo(root, owner = "mezze-lang", repo = "mezze-python", pkgName = "mezze_python", tag = "1.0.0")
        val store = File(root, "store")
        val cache = File(root, "gitcache")
        val app = File(root, "app").also { writeApp(it, "mezze_python", "github:mezze-lang/mezze-python@1.0.0") }

        val exit = SyncCommand().execute(
            emptyList(),
            currentDir = app,
            storeRootOverride = store,
            githubCacheOverride = cache,
            // Fetcher appends `<owner>/<repo>.git` — trailing '/' added automatically.
            githubBaseUrlOverride = baseDir.absolutePath,
        )
        assertEquals(0, exit, "clean github sync should exit 0")

        // The symlink names the DECLARED REF (not the resolved version) so the
        // user sees exactly what they asked for on disk.
        val link = depLink(app, "mezze_python")
        assertNotNull(link, "app/.deps/mezze/mezze_python-* should be linked")
        assertTrue(Files.isSymbolicLink(link!!.toPath()), "dep entry should be a symlink into the store")

        // The store dir is `sha256(commit)` — a stable content-address. It must
        // both exist and hold the checked-out Mezze.toml.
        val storeEntries = store.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals(1, storeEntries.size, "one store entry per unique github commit")
        val storeEntry = storeEntries.first()
        assertTrue(File(storeEntry, "Mezze.toml").isFile, "store dir holds the checkout")
        assertTrue(!File(storeEntry, ".git").exists(), ".git must be stripped from the store entry")
        assertEquals(storeEntry.canonicalFile, link.canonicalFile, "symlink -> store entry")

        val lock = readLockFile(File(app, "Mezze.lock"))
        assertNotNull(lock, "Mezze.lock is written")
        val node = lock!!.packages.first { it.name == "mezze_python" }
        assertEquals("github", node.source)
        assertEquals("mezze-lang", node.owner)
        assertEquals("mezze-python", node.repo)
        assertEquals("1.0.0", node.ref)
        assertNotNull(node.commit, "resolved commit sha must be recorded")
        assertTrue(node.commit!!.matches(Regex("[0-9a-f]{40}")), "commit is a 40-char sha")
        // The lock's checksum equals the store dir name — the reproducibility gate.
        assertEquals("sha256:${storeEntry.name}", node.checksum)
    }

    @Test
    fun `malformed github spec (missing @ref) is a hard failure that increments the failure count`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val cache = File(root, "gitcache")
        // No @ref -> parseGithubDep throws -> directDepsOf records a failure
        // and the dep never becomes part of the resolve frontier. No git call.
        val app = File(root, "app").also { writeApp(it, "mezze_python", "github:mezze-lang/mezze-python") }

        val exit = SyncCommand().execute(
            emptyList(),
            currentDir = app,
            storeRootOverride = store,
            githubCacheOverride = cache,
            githubBaseUrlOverride = "file:///dev/null/",
        )

        assertNotEquals(0, exit, "malformed spec must exit non-zero — CI must see the failure")
        assertNull(depLink(app, "mezze_python"), "no symlink for a spec that never resolved")
        // The lock does still write, but the failed dep must NOT appear as a
        // github-source node — nothing was resolved.
        val lock = readLockFile(File(app, "Mezze.lock"))
        assertNotNull(lock)
        assertTrue(lock!!.packages.none { it.name == "mezze_python" && it.source == "github" })
    }
}
