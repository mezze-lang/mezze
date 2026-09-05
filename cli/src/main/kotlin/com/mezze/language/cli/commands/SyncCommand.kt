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

        // Registry deps (URL#sha256) + local path deps.
        val allDependencies = manifest.dependencies + manifest.devDependencies
        val pathDeps = manifest.pathDependencies

        if (allDependencies.isEmpty() && pathDeps.isEmpty()) {
            println("✅ No dependencies to sync.")
            println()
            println("💡 Add a local dep under [path-dependencies] (name = \"../path\"),")
            println("   or a registry dep under [dependencies] (name = \"<url>#sha256:<hash>\").")
            return
        }

        println("📦 Syncing ${allDependencies.size + pathDeps.size} package(s)...")
        println()

        // Everything is materialized into `.deps/mezze/<name>-<version>/` — exactly
        // where ModuleResolver.resolveModuleFile searches, so `use <name>::...`
        // resolves with no further wiring.
        val packagesDir = File(currentDir, ".deps/mezze")
        val cacheDir = File(System.getProperty("user.home"), ".mez/cache")

        if (isClean && packagesDir.exists()) {
            println("🧹 Cleaning .deps/mezze/...")
            packagesDir.deleteRecursively()
        }

        packagesDir.mkdirs()
        cacheDir.mkdirs()

        // --- local path dependencies: symlink so edits to the target are live ---
        for ((depName, rawPath) in pathDeps) {
            println("🔗 Linking $depName (path = \"$rawPath\")...")
            try {
                linkPathDependency(currentDir, packagesDir, depName, rawPath)
            } catch (e: Exception) {
                println("❌ Error linking $depName:")
                println("   ${e.message}")
            }
            println()
        }

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
            println("🎉 Sync complete: ${pathDeps.size} local + ${lockedPackages.size} registry package(s).")
            println()
            println("📂 Dependencies at: ${packagesDir.absolutePath}")

        } finally {
            downloader.close()
        }
    }

    /**
     * Materialize a local path dependency into `.deps/mezze/<name>-<version>/`
     * as a **symlink** to the target package — no copy, so edits to the target
     * reflect immediately, and the module resolver finds it under the dir it
     * already searches.
     */
    private fun linkPathDependency(
        currentDir: File,
        packagesDir: File,
        depName: String,
        rawPath: String,
    ) {
        val target = File(rawPath).let { if (it.isAbsolute) it else File(currentDir, rawPath) }.canonicalFile
        if (!target.isDirectory) {
            throw IllegalArgumentException("path '$rawPath' is not a directory (resolved: $target)")
        }
        val depManifest = File(target, "Mezze.toml")
        if (!depManifest.exists()) {
            throw IllegalArgumentException("no Mezze.toml in '$target' — not a Mezze package")
        }
        val version = readPackageVersion(depManifest) ?: "0.0.0"

        // Drop any stale <name>-* entries (a version bump shouldn't leave dupes).
        // Delete symlinks as links (never recurse into the target and wipe its source!).
        packagesDir.listFiles { f -> f.name.startsWith("$depName-") }?.forEach { stale ->
            val p = stale.toPath()
            if (java.nio.file.Files.isSymbolicLink(p)) java.nio.file.Files.delete(p)
            else stale.deleteRecursively()
        }

        java.nio.file.Files.createSymbolicLink(File(packagesDir, "$depName-$version").toPath(), target.toPath())
        println("  ✅ $depName-$version -> ${target.path}")
    }

    /** Best-effort read of `[package].version` from a dependency's Mezze.toml. */
    private fun readPackageVersion(manifest: File): String? =
        try {
            Toml.decodeFromString(MezzeToml.serializer(), manifest.readText()).`package`.version
        } catch (e: Exception) {
            null
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
