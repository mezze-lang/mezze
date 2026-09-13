package com.mezze.language.cli.`package`

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Fetch every `[jars]` entry named on the resolved dep graph into
 * `~/.mezze/store/jars/<sha256>/<basename>.jar`. Content-addressed like
 * regular packages: the same jar declared twice (a diamond) is fetched
 * exactly once and shared across every consumer of the store.
 *
 * URL schemes supported: `http`, `https`, `file` — same set the
 * [PackageDownloader] handles. A `file://` URL is what lets the local
 * jar fixture (or a pre-published tree) prime the store without a
 * network round-trip, and what most tests use.
 *
 * Every fetched jar's sha256 is verified against the pin from
 * `<url>#sha256:<hex>` before being placed in the store — the same
 * supply-chain guarantee the package downloader gives.
 */
class JarFetcher(private val storeRoot: File) {

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    }

    /** One `[jars]` entry that needs fetching / verifying / placing. */
    data class Task(
        /** `name` as it appeared in `[jars]` — the map key. */
        val name: String,
        /** Full spec verbatim from the toml (`<url>#sha256:<hex>`). */
        val spec: String,
        /** URL half of [spec]. */
        val url: String,
        /** sha256 hex (64 chars). */
        val sha256: String,
        /** Package that declared this entry — used for error attribution. */
        val declaredBy: String,
    )

    /** Result of one fetch attempt. */
    sealed class Outcome {
        data class Ok(val task: Task, val placedAt: File, val cached: Boolean) : Outcome()
        data class Failed(val task: Task, val reason: String) : Outcome()
    }

    /**
     * Fetch (or verify already-fetched) every task. Diamond dedup is on
     * [Task.sha256] — a jar declared under different names in different
     * packages is still one on-disk artifact. Runs waves in parallel;
     * two threads landing on the same sha serialize on a per-sha lock
     * so the on-disk write is atomic.
     */
    fun fetchAll(tasks: List<Task>, isClean: Boolean = false): List<Outcome> {
        if (tasks.isEmpty()) return emptyList()
        val bySha = tasks.groupBy { it.sha256 }
        val results = java.util.Collections.synchronizedList(mutableListOf<Outcome>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(8, maxOf(2, Runtime.getRuntime().availableProcessors())),
        )
        try {
            val futures = bySha.map { (_, group) ->
                pool.submit {
                    // One representative task per sha does the work; the
                    // others report the same on-disk outcome.
                    val rep = group.first()
                    val outcome = fetchOne(rep, isClean)
                    results += outcome
                    // Peers landing on the same sha share the on-disk
                    // result (they're the same bytes; no separate fetch).
                    for (other in group.drop(1)) {
                        results += when (outcome) {
                            is Outcome.Ok -> Outcome.Ok(other, outcome.placedAt, cached = true)
                            is Outcome.Failed -> Outcome.Failed(other, outcome.reason)
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return results.toList()
    }

    // ─── per-jar ──────────────────────────────────

    private val shaLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun fetchOne(task: Task, isClean: Boolean): Outcome {
        synchronized(shaLocks.computeIfAbsent(task.sha256) { Any() }) {
            val jarDir = File(storeRoot, "jars/${task.sha256}")
            val existing = firstJarIn(jarDir)
            // Content-addressed cache hit: the store already has this
            // sha (any file with a .jar extension is fine — the sha is
            // the identity).
            if (existing != null && !isClean) {
                return Outcome.Ok(task, existing, cached = true)
            }
            val bytes = try {
                fetchBytes(task.url)
            } catch (e: JarFetchException) {
                return Outcome.Failed(task, e.message ?: "fetch failed")
            } catch (e: Exception) {
                return Outcome.Failed(task, "fetch failed: ${task.url}: ${e.message ?: e.javaClass.simpleName}")
            }
            val actual = sha256Hex(bytes)
            if (!actual.equals(task.sha256, ignoreCase = true)) {
                return Outcome.Failed(
                    task,
                    "sha256 mismatch — expected ${task.sha256}, got $actual",
                )
            }
            // Atomic replace: write to a temp file next to the target,
            // then move onto the final path. A crashed fetch leaves the
            // store untouched instead of a half-written jar.
            jarDir.mkdirs()
            val basename = deriveJarBasename(task)
            val target = File(jarDir, basename)
            val tmp = File(jarDir, ".tmp-$basename")
            try {
                tmp.writeBytes(bytes)
                if (target.exists()) target.delete()
                java.nio.file.Files.move(tmp.toPath(), target.toPath())
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            return Outcome.Ok(task, target, cached = false)
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val uri = try { URI(url) } catch (e: Exception) {
            throw JarFetchException("fetch failed: $url: bad URL — ${e.message}")
        }
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val f = try { File(uri) } catch (e: Exception) {
                    throw JarFetchException("fetch failed: $url: ${e.message}")
                }
                if (!f.isFile) throw JarFetchException("fetch failed: $url: no such file")
                f.readBytes()
            }
            "http", "https" -> {
                val req = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofMinutes(5)).build()
                val resp = try {
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: Exception) {
                    throw JarFetchException("fetch failed: $url: ${e.message ?: e.javaClass.simpleName}")
                }
                if (resp.statusCode() !in 200..299) {
                    resp.body().close()
                    throw JarFetchException("fetch failed: $url: HTTP ${resp.statusCode()}")
                }
                val out = ByteArrayOutputStream(64 * 1024)
                val buf = ByteArray(64 * 1024)
                resp.body().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
            else -> throw JarFetchException(
                "fetch failed: $url: unsupported URL scheme '${uri.scheme}' (use http, https, or file)",
            )
        }
    }

    /**
     * Pick a stable on-disk name for the jar. Prefer the URL's basename
     * when it ends in `.jar`, otherwise fall back to `<name>.jar` (the
     * `[jars]` map key). `.jar` extension is not enforced — anything
     * fetchable is placed as-is when the URL provides a valid basename,
     * except we normalise the extension because [firstJarIn] scans by
     * suffix.
     */
    private fun deriveJarBasename(task: Task): String {
        val urlPath = try { URI(task.url).path } catch (_: Exception) { null }
        val fromUrl = urlPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val candidate = fromUrl ?: "${task.name}.jar"
        // ResolvedJarsClasspath.firstJarIn only picks up `*.jar`; we
        // add the extension when the URL didn't carry one so the file
        // is discoverable by the classpath aggregator.
        return if (candidate.endsWith(".jar")) candidate else "$candidate.jar"
    }

    private fun firstJarIn(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.firstOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class JarFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)
