package com.mezze.language.cli.`package`

import java.io.File

/**
 * Temporarily disabled — Ktor HTTP client was removed from :cli to slim
 * the `mez` binary. `mez sync` will report this error until the
 * downloader is rewired against Helidon's WebClient (used by user-app
 * HTTP effect since the Vert.x → Helidon swap).
 *
 * Restoring the previous behaviour:
 *   - re-add `ktor-client-core` + `ktor-client-cio` to :cli's deps, or
 *   - port the original Ktor flow to `io.helidon.webclient.WebClient`
 *     (which is already on the classpath via :interpreter / user-app
 *     runtime jar).
 *
 * The original implementation lives in git history before this commit.
 */
class PackageDownloader(@Suppress("UNUSED_PARAMETER") private val cacheDir: File) {
    fun downloadPackage(
        @Suppress("UNUSED_PARAMETER") packageName: String,
        @Suppress("UNUSED_PARAMETER") url: String,
        @Suppress("UNUSED_PARAMETER") expectedChecksum: String,
    ): File {
        throw PackageDownloadException(
            "❌ `mez sync` is temporarily disabled while the HTTP client is being " +
            "consolidated onto Helidon. The Ktor-based downloader was removed to " +
            "shrink the `mez` binary; the Helidon rewire is tracked separately."
        )
    }

    fun close() { /* no-op */ }
}

class PackageDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
