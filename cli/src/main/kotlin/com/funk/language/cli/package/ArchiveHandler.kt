package com.funk.language.cli.`package`

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handle tar.gz archive extraction for Funk packages
 */
class ArchiveHandler {

    /**
     * Extract a .tar.gz archive to a destination directory
     *
     * @param archiveFile The .tar.gz file to extract
     * @param destDir The destination directory (will be created if it doesn't exist)
     * @param showProgress Whether to show extraction progress
     */
    fun extractTarGz(archiveFile: File, destDir: File, showProgress: Boolean = true) {
        if (!archiveFile.exists()) {
            throw ArchiveException(
                "❌ Archive file not found: ${archiveFile.absolutePath}\n\n" +
                "💡 This is likely a bug in the package manager.\n" +
                "   Please report this issue."
            )
        }

        if (!archiveFile.name.endsWith(".tar.gz") && !archiveFile.name.endsWith(".tgz")) {
            throw ArchiveException(
                "❌ Invalid archive format: ${archiveFile.name}\n\n" +
                "Expected: .tar.gz or .tgz file\n\n" +
                "💡 Funk packages must be distributed as .tar.gz archives."
            )
        }

        // Create destination directory if it doesn't exist
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        if (showProgress) {
            print("  📦 Extracting ${archiveFile.name}...")
        }

        try {
            FileInputStream(archiveFile).use { fis ->
                GzipCompressorInputStream(fis).use { gzis ->
                    TarArchiveInputStream(gzis).use { tais ->
                        var entry = tais.nextEntry
                        var filesExtracted = 0

                        while (entry != null) {
                            val outputFile = File(destDir, entry.name)

                            // Security: Prevent path traversal attacks
                            if (!outputFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                                throw ArchiveException(
                                    "❌ Security Error: Archive contains path traversal attempt\n\n" +
                                    "Entry: ${entry.name}\n" +
                                    "This archive is trying to extract files outside the package directory.\n\n" +
                                    "⚠️  SECURITY WARNING ⚠️\n" +
                                    "This could be a malicious package. Do not use it."
                                )
                            }

                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                // Ensure parent directory exists
                                outputFile.parentFile?.mkdirs()

                                // Extract file
                                FileOutputStream(outputFile).use { fos ->
                                    tais.copyTo(fos)
                                }

                                // Preserve execute permissions
                                if (entry.mode and 0x40 != 0) {
                                    outputFile.setExecutable(true)
                                }

                                filesExtracted++
                            }

                            entry = tais.nextEntry
                        }

                        if (showProgress) {
                            println(" ✅ ($filesExtracted files)")
                        }

                        if (filesExtracted == 0) {
                            throw ArchiveException(
                                "❌ Empty archive: ${archiveFile.name}\n\n" +
                                "The archive contains no files.\n\n" +
                                "💡 This package may be corrupted or incorrectly packaged."
                            )
                        }
                    }
                }
            }
        } catch (e: ArchiveException) {
            // Re-throw our custom exceptions
            throw e
        } catch (e: Exception) {
            throw ArchiveException(
                "❌ Failed to extract ${archiveFile.name}\n\n" +
                "Error: ${e.message}\n\n" +
                "💡 Possible causes:\n" +
                "   - The archive is corrupted\n" +
                "   - Insufficient disk space\n" +
                "   - File permissions issues\n\n" +
                "Try deleting the cached package and downloading again.",
                e
            )
        }
    }

    /**
     * Find the root directory of an extracted package
     *
     * Many packages have a single root directory (e.g., "json-1.2.3/")
     * This function finds that directory or returns the extraction directory if there are multiple roots
     */
    fun findPackageRoot(extractedDir: File): File {
        val entries = extractedDir.listFiles() ?: return extractedDir

        // If there's exactly one directory and nothing else, that's the package root
        if (entries.size == 1 && entries[0].isDirectory) {
            return entries[0]
        }

        // Otherwise, the extraction directory is the package root
        return extractedDir
    }

    /**
     * Validate that an extracted package contains a valid Funk.toml
     */
    fun validatePackageStructure(packageRoot: File, packageName: String) {
        val funkToml = File(packageRoot, "Funk.toml")

        if (!funkToml.exists()) {
            throw ArchiveException(
                "❌ Invalid package structure: Missing Funk.toml\n\n" +
                "Package: $packageName\n" +
                "Location: ${packageRoot.absolutePath}\n\n" +
                "💡 Every Funk package must have a Funk.toml manifest file at the root."
            )
        }

        // Optionally check for src/ directory
        val srcDir = File(packageRoot, "src")
        if (!srcDir.exists() || !srcDir.isDirectory) {
            println("  ⚠️  Warning: Package $packageName has no 'src/' directory")
            println("     This is unusual but not required.")
        }
    }
}

/**
 * Exception thrown when archive operations fail
 */
class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)
