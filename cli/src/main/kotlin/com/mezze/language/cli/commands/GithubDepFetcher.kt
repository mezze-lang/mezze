package com.mezze.language.cli.commands

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches a `github:<owner>/<repo>@<ref>` dependency by shelling out to the
 * system `git` binary (assumed on PATH). Two-stage:
 *
 *  1. `git clone --depth 1 --branch <ref>` into a per-`(owner,repo,ref)` cache
 *     under [cacheDir] (typically `~/.mezze/cache/git/`), so a re-run doesn't
 *     re-download and multiple projects share the same clone.
 *  2. `git rev-parse HEAD` in that clone to record the resolved commit sha.
 *
 * The commit sha is what makes the checkout reproducible in `Mezze.lock`:
 * `github:foo/bar@main` on one day and the next may resolve to different
 * commits, but the lock records exactly the commit that was used.
 *
 * The store hash is `sha256(commitSha)` — a stable 64-char content-address
 * that keys `~/.mezze/store/<sha>/` alongside registry (URL#sha256) deps. Two
 * refs that resolve to the same commit share the same store entry.
 *
 * `githubBaseUrl` normally is `https://github.com/`. Tests override it via the
 * `MEZZE_TEST_GITHUB_BASE_URL` env var (or an explicit constructor argument)
 * so `git clone` targets a local bare repo instead of the real GitHub — no
 * network in unit tests.
 */
class GithubDepFetcher(
    private val cacheDir: File,
    githubBaseUrl: String? = null,
) {
    /** Base URL prefix. Must end with '/' so `${base}<owner>/<repo>.git` composes cleanly. */
    private val baseUrl: String = run {
        val raw = githubBaseUrl
            ?: System.getenv("MEZZE_TEST_GITHUB_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: "https://github.com/"
        if (raw.endsWith("/")) raw else "$raw/"
    }

    data class Fetched(
        /** The prepared clone dir on disk (with `.git/` still present). */
        val cloneDir: File,
        /** 40-char commit sha `HEAD` resolved to. */
        val commit: String,
        /** 64-char sha256 that keys the shared store entry. */
        val storeSha: String,
    )

    /**
     * Clone (or reuse the cache) and resolve HEAD. Returns [Fetched] on
     * success, throws [GithubFetchException] otherwise — the caller wraps.
     */
    fun fetch(owner: String, repo: String, ref: String): Fetched {
        val cloneDir = File(cacheDir, "$owner-$repo-${sanitize(ref)}")
        // Best-effort cache: only reuse if it looks like a healthy clone.
        // A partial/aborted clone should be re-tried, not silently used.
        val cached = File(cloneDir, ".git").isDirectory && File(cloneDir, ".git/HEAD").isFile
        if (!cached) {
            cloneDir.deleteRecursively()
            cloneDir.parentFile?.mkdirs()
            val url = "$baseUrl$owner/$repo.git"
            runGit(
                cloneDir.parentFile ?: cacheDir,
                listOf("git", "clone", "--depth", "1", "--branch", ref, url, cloneDir.name),
                failureContext = "clone $url@$ref",
            )
        }
        val commit = runGitCapture(
            cloneDir,
            listOf("git", "rev-parse", "HEAD"),
            failureContext = "resolve HEAD in $cloneDir",
        ).trim()
        if (!commit.matches(Regex("[0-9a-f]{40}"))) {
            throw GithubFetchException("git rev-parse HEAD returned unexpected value: '$commit'")
        }
        val storeSha = sha256Hex(commit.toByteArray(Charsets.UTF_8))
        return Fetched(cloneDir, commit, storeSha)
    }

    /**
     * Copy [cloneDir]'s contents (minus `.git/`) into [storeDir] atomically:
     * copy into a sibling temp dir, then move into place. On failure the temp
     * dir is cleaned and [storeDir] is left untouched. Idempotent.
     */
    fun materializeStore(cloneDir: File, storeDir: File) {
        val tmp = File(storeDir.parentFile, ".tmp-${storeDir.name}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            cloneDir.listFiles()?.forEach { entry ->
                if (entry.name == ".git") return@forEach
                copyRecursive(entry, File(tmp, entry.name))
            }
            if (storeDir.exists()) storeDir.deleteRecursively()
            storeDir.parentFile.mkdirs()
            java.nio.file.Files.move(tmp.toPath(), storeDir.toPath())
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            // Preserve executable bit — some deps ship shell entry-points.
            if (src.canExecute()) dst.setExecutable(true)
        }
    }

    private fun runGit(cwd: File, cmd: List<String>, failureContext: String) {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw GithubFetchException("git not on PATH? Failed to $failureContext: ${e.message}", e)
        }
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(5, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            throw GithubFetchException("git timed out while trying to $failureContext")
        }
        val code = proc.exitValue()
        if (code != 0) {
            throw GithubFetchException(
                "git exited $code while trying to $failureContext\n${output.trim().prependIndent("      ")}"
            )
        }
    }

    private fun runGitCapture(cwd: File, cmd: List<String>, failureContext: String): String {
        val pb = ProcessBuilder(cmd).directory(cwd)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw GithubFetchException("git not on PATH? Failed to $failureContext: ${e.message}", e)
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        if (!proc.waitFor(1, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            throw GithubFetchException("git timed out while trying to $failureContext")
        }
        val code = proc.exitValue()
        if (code != 0) {
            throw GithubFetchException(
                "git exited $code while trying to $failureContext\n${err.trim().prependIndent("      ")}"
            )
        }
        return out
    }

    /** Turn a ref into a filename-safe cache-dir segment (branches like `feature/x`). */
    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class GithubFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)
