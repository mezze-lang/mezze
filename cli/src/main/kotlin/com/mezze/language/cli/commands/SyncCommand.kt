package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.*
import java.io.File

/**
 * Handle `mezze sync` command to download and install all dependencies
 *
 * Usage:
 *   mezze sync              # Download all dependencies
 *   mezze sync --clean      # Delete and re-download everything
 */
class SyncCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml found in current directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'mezze init' to create a new package first.")
            return
        }

        val isClean = args.contains("--clean")

        // Read Mezze.toml
        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("❌ Error: Failed to parse Mezze.toml")
            println()
            println("The manifest file has invalid TOML syntax.")
            println("Error: ${e.message}")
            println()
            println("💡 Check the syntax of your Mezze.toml file.")
            return
        }

        // Combine dependencies and dev-dependencies
        val allDependencies = manifest.dependencies + manifest.devDependencies

        if (allDependencies.isEmpty()) {
            println("✅ No dependencies to sync.")
            println()
            println("💡 Add dependencies with: mezze add <url>")
            return
        }

        println("📦 Syncing ${allDependencies.size} package(s)...")
        println()

        // Create directories
        val packagesDir = File(currentDir, "mezze_packages")
        val cacheDir = File(System.getProperty("user.home"), ".mz/cache")

        if (isClean && packagesDir.exists()) {
            println("🧹 Cleaning mezze_packages/...")
            packagesDir.deleteRecursively()
        }

        packagesDir.mkdirs()
        cacheDir.mkdirs()

        // Download and extract packages
        val downloader = PackageDownloader(cacheDir)
        val archiveHandler = ArchiveHandler()
        val lockedPackages = mutableListOf<LockedPackage>()

        try {
            for ((packageName, fullUrl) in allDependencies) {
                println("📦 Processing $packageName...")

                // Parse URL
                val (url, checksum) = try {
                    parseDependencyUrl(fullUrl)
                } catch (e: IllegalArgumentException) {
                    println("❌ Error in $packageName:")
                    println(e.message)
                    println()
                    continue
                }

                // Extract version from URL
                val version = extractVersionFromUrl(url)

                // Download
                val archiveFile = try {
                    downloader.downloadPackage(packageName, url, checksum)
                } catch (e: PackageDownloadException) {
                    println(e.message)
                    println()
                    continue
                } catch (e: Exception) {
                    println("❌ Error downloading $packageName:")
                    println(e.message)
                    println()
                    continue
                }

                // Extract
                val packageDir = File(packagesDir, "$packageName-$version")

                // Skip extraction if already extracted (unless --clean)
                if (packageDir.exists() && !isClean) {
                    println("  ✅ Already extracted $packageName")
                } else {
                    if (packageDir.exists()) {
                        packageDir.deleteRecursively()
                    }

                    packageDir.mkdirs()

                    try {
                        archiveHandler.extractTarGz(archiveFile, packageDir)
                    } catch (e: ArchiveException) {
                        println(e.message)
                        println()
                        continue
                    }

                    // Find package root (might be nested in a directory)
                    val packageRoot = archiveHandler.findPackageRoot(packageDir)

                    // Validate structure
                    try {
                        archiveHandler.validatePackageStructure(packageRoot, packageName)
                    } catch (e: ArchiveException) {
                        println(e.message)
                        println()
                        // Don't skip - package might still be usable
                    }
                }

                // Add to lock file
                lockedPackages.add(
                    LockedPackage(
                        name = packageName,
                        version = version,
                        url = url,
                        checksum = "sha256:$checksum"
                    )
                )

                println()
            }

            // Write lock file
            val lockFile = File(currentDir, "Mezze.lock")
            val existingLock = readLockFile(lockFile)
            val newLock = mergeLockFile(existingLock, lockedPackages)

            try {
                writeLockFile(lockFile, newLock)
                println("✅ Updated Mezze.lock")
            } catch (e: Exception) {
                println("⚠️  Warning: Failed to update Mezze.lock")
                println("   ${e.message}")
            }

            println()
            println("🎉 Successfully synced ${lockedPackages.size} package(s)!")
            println()
            println("📂 Packages installed to: ${packagesDir.absolutePath}")

        } finally {
            downloader.close()
        }
    }

    /**
     * Extract version from URL
     *
     * Examples:
     *   .../releases/download/v1.2.3/... -> "1.2.3"
     *   .../json-1.2.3.tar.gz -> "1.2.3"
     */
    private fun extractVersionFromUrl(url: String): String {
        // Try to extract from /vX.Y.Z/ pattern
        val versionRegex = Regex("/v?(\\d+\\.\\d+\\.\\d+(?:-[\\w.]+)?)/")
        val match = versionRegex.find(url)

        if (match != null) {
            return match.groupValues[1]
        }

        // Try to extract from filename
        val filename = url.substringAfterLast("/").removeSuffix(".tar.gz").removeSuffix(".tgz")
        val filenameVersionRegex = Regex("-(\\d+\\.\\d+\\.\\d+(?:-[\\w.]+)?)$")
        val filenameMatch = filenameVersionRegex.find(filename)

        if (filenameMatch != null) {
            return filenameMatch.groupValues[1]
        }

        // Fallback: use "unknown"
        return "unknown"
    }
}
