package com.funk.language.cli.`package`

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * Download and verify packages from URLs with checksum validation
 */
class PackageDownloader(
    private val cacheDir: File
) {
    private val client = HttpClient(CIO) {
        engine {
            // Configure timeouts
            requestTimeout = 300_000 // 5 minutes for large packages
        }
    }

    init {
        // Ensure cache directory exists
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    /**
     * Download a package from URL and verify its checksum
     *
     * Returns the path to the downloaded .tar.gz file
     */
    fun downloadPackage(
        packageName: String,
        url: String,
        expectedChecksum: String,
        showProgress: Boolean = true
    ): File = runBlocking {
        val cacheFile = File(cacheDir, "$packageName.tar.gz")

        // Check if already cached and valid
        if (cacheFile.exists()) {
            try {
                val actualChecksum = computeSHA256(cacheFile)
                if (actualChecksum == expectedChecksum) {
                    if (showProgress) {
                        println("  ✅ Using cached $packageName")
                    }
                    return@runBlocking cacheFile
                } else {
                    if (showProgress) {
                        println("  ⚠️  Cached file checksum mismatch, re-downloading...")
                    }
                    cacheFile.delete()
                }
            } catch (e: Exception) {
                // Cache file is corrupted, delete and re-download
                cacheFile.delete()
            }
        }

        // Download the package
        if (showProgress) {
            println("  📥 Downloading $packageName...")
        }

        try {
            client.prepareGet(url).execute { response ->
                if (response.status.value !in 200..299) {
                    throw PackageDownloadException(
                        "Failed to download package from $url\n" +
                        "HTTP Status: ${response.status.value} ${response.status.description}\n\n" +
                        "💡 Possible causes:\n" +
                        "   - The URL is incorrect or the release has been deleted\n" +
                        "   - The GitHub repository is private or doesn't exist\n" +
                        "   - Network connectivity issues\n\n" +
                        "Please verify the URL in your Funk.toml."
                    )
                }

                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                val channel: ByteReadChannel = response.bodyAsChannel()

                val tempFile = File.createTempFile("funk-download-", ".tar.gz", cacheDir)

                try {
                    tempFile.outputStream().use { output ->
                        var bytesDownloaded = 0L
                        val buffer = ByteArray(8192)

                        while (!channel.isClosedForRead) {
                            val packet = channel.readAvailable(buffer)
                            if (packet > 0) {
                                output.write(buffer, 0, packet)
                                bytesDownloaded += packet

                                if (showProgress && contentLength > 0) {
                                    val progress = (bytesDownloaded * 100 / contentLength).toInt()
                                    val mbDownloaded = bytesDownloaded / (1024 * 1024)
                                    val mbTotal = contentLength / (1024 * 1024)
                                    print("\r  📥 Downloading $packageName... $progress% ($mbDownloaded/$mbTotal MB)")
                                }
                            }
                        }

                        if (showProgress) {
                            println() // New line after progress
                        }
                    }

                    // Verify checksum
                    if (showProgress) {
                        print("  🔐 Verifying checksum...")
                    }

                    val actualChecksum = computeSHA256(tempFile)

                    if (actualChecksum != expectedChecksum) {
                        tempFile.delete()
                        throw PackageDownloadException(
                            "❌ Checksum verification failed for $packageName\n\n" +
                            "Expected: $expectedChecksum\n" +
                            "Actual:   $actualChecksum\n\n" +
                            "⚠️  SECURITY WARNING ⚠️\n" +
                            "The downloaded package does not match the expected checksum.\n" +
                            "This could indicate:\n" +
                            "   - A corrupted download (try again)\n" +
                            "   - A tampered package (security risk!)\n" +
                            "   - An incorrect checksum in Funk.toml\n\n" +
                            "💡 Do not use this package until you verify the checksum is correct."
                        )
                    }

                    if (showProgress) {
                        println(" ✅")
                    }

                    // Move to final location
                    tempFile.renameTo(cacheFile)
                    cacheFile

                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
        } catch (e: PackageDownloadException) {
            throw e
        } catch (e: Exception) {
            throw PackageDownloadException(
                "❌ Failed to download $packageName from $url\n\n" +
                "Error: ${e.message}\n\n" +
                "💡 Possible causes:\n" +
                "   - Network connectivity issues\n" +
                "   - DNS resolution failure\n" +
                "   - Firewall or proxy blocking the request\n" +
                "   - The server is temporarily unavailable\n\n" +
                "Please check your network connection and try again.",
                e
            )
        }
    }

    /**
     * Compute SHA-256 checksum of a file
     */
    private fun computeSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun close() {
        client.close()
    }
}

/**
 * Exception thrown when package download fails
 */
class PackageDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
