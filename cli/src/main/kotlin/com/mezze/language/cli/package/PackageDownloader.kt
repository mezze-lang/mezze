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
 * Fetches a package tarball and verifies its SHA-256 against the pin from
 * `Mezze.toml` (`<url>#sha256:<hash>`). Supports `http`/`https` (JDK
 * `HttpClient` — no third-party HTTP client, keeps the `mez` binary slim) and
 * `file://` (local tarballs, offline use, and tests).
 *
 * **Verification is mandatory:** bytes whose SHA-256 doesn't match the pinned
 * hash are rejected, so a compromised, swapped, or man-in-the-middled upstream
 * can never be used. This is the core supply-chain guarantee.
 */
class PackageDownloader(private val cacheDir: File) {

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    }

    /**
     * Fetch [url], verify it hashes to [expectedChecksum], return the verified
     * `.tar.gz` on disk.
     *
     * [onStart] fires once with the total byte size (or -1 if the server sends
     * no Content-Length); [onProgress] fires as bytes arrive with the running
     * total. Both default to no-ops, so callers that don't care pay nothing.
     */
    fun downloadPackage(
        packageName: String,
        url: String,
        expectedChecksum: String,
        onStart: (total: Long) -> Unit = {},
        onProgress: (received: Long) -> Unit = {},
    ): File {
        val bytes = try {
            fetchBytes(url, onStart, onProgress)
        } catch (e: PackageDownloadException) {
            throw e
        } catch (e: Exception) {
            throw PackageDownloadException("❌ Failed to fetch $packageName from $url\n   ${e.message}", e)
        }

        val actual = sha256Hex(bytes)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            throw PackageDownloadException(
                "❌ Checksum mismatch for $packageName — refusing to use it.\n" +
                    "   expected sha256: $expectedChecksum\n" +
                    "   got      sha256: $actual\n" +
                    "   The fetched artifact does not match the hash pinned in Mezze.toml."
            )
        }

        cacheDir.mkdirs()
        return File(cacheDir, "$actual.tar.gz").apply { writeBytes(bytes) }
    }

    private fun fetchBytes(
        url: String,
        onStart: (Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): ByteArray {
        val uri = URI(url)
        return when (uri.scheme?.lowercase()) {
            "file" -> File(uri).let { readLocal(it, onStart, onProgress) }
            null -> readLocal(File(url), onStart, onProgress)
            "http", "https" -> {
                // Stream the body so we can report byte-progress and hash on the
                // fly instead of blocking on the whole payload at once.
                val req = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofMinutes(5)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                if (resp.statusCode() !in 200..299) {
                    resp.body().close()
                    throw PackageDownloadException("HTTP ${resp.statusCode()} from $url")
                }
                val total = resp.headers().firstValueAsLong("content-length").orElse(-1L)
                onStart(total)
                val out = ByteArrayOutputStream(if (total in 1..(64 * 1024 * 1024)) total.toInt() else 64 * 1024)
                val buf = ByteArray(64 * 1024)
                var received = 0L
                resp.body().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        onProgress(received)
                    }
                }
                out.toByteArray()
            }
            else -> throw PackageDownloadException(
                "unsupported URL scheme '${uri.scheme}' (use http, https, or file)"
            )
        }
    }

    private fun readLocal(file: File, onStart: (Long) -> Unit, onProgress: (Long) -> Unit): ByteArray {
        if (!file.isFile) throw PackageDownloadException("no such file: $file")
        val bytes = file.readBytes()
        onStart(bytes.size.toLong())
        onProgress(bytes.size.toLong())
        return bytes
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun close() { /* JDK HttpClient needs no explicit close */ }
}

class PackageDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
