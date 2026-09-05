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

    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        storeRootOverride: File? = null,
    ) {
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

        // Global content store: extracted packages keyed by sha256, shared across ALL
        // projects (fetch once, ever). Each project's .deps/mezze just symlinks into it.
        // Override with MEZZE_STORE_DIR (used by tests).
        val storeRoot = storeRootOverride
            ?: System.getenv("MEZZE_STORE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".mezze/store")

        if (isClean && packagesDir.exists()) {
            println("🧹 Cleaning .deps/mezze/...")
            packagesDir.deleteRecursively()
        }

        packagesDir.mkdirs()
        cacheDir.mkdirs()

        // --- local path dependencies: resolved TRANSITIVELY and hoisted flat into
        // this project's .deps/mezze/ (module resolution is rooted here), each a
        // symlink so edits to the target reflect immediately. ---
        val localCount = if (pathDeps.isNotEmpty()) {
            println("🔗 Resolving local path dependencies (transitive)...")
            val n = syncPathDeps(currentDir, pathDeps)
            println()
            n
        } else 0

        // Download and extract packages
        val downloader = PackageDownloader(cacheDir)
        val archiveHandler = ArchiveHandler()
        val lockedPackages = mutableListOf<LockedPackage>()

        try {
            for ((packageName, fullUrl) in allDependencies) {
                println("📦 $packageName (registry)...")

                val (url, checksum) = try {
                    parseDependencyUrl(fullUrl)
                } catch (e: IllegalArgumentException) {
                    println((e.message ?: "invalid dependency URL").prependIndent("  "))
                    println()
                    continue
                }

                // Content-addressed store entry — shared across all projects.
                val storeDir = File(storeRoot, checksum)
                if (File(storeDir, "Mezze.toml").exists() && !isClean) {
                    println("  ♻️  cached (${checksum.take(12)}…)")
                } else {
                    val archiveFile = try {
                        downloader.downloadPackage(packageName, url, checksum)
                    } catch (e: Exception) {
                        println((e.message ?: "download failed").prependIndent("  "))
                        println()
                        continue
                    }
                    try {
                        extractIntoStore(archiveHandler, archiveFile, storeDir, packageName)
                    } catch (e: ArchiveException) {
                        println((e.message ?: "extract failed").prependIndent("  "))
                        println()
                        continue
                    }
                }

                // Prefer the package's own declared version for the .deps entry name.
                val version = readPackageVersion(File(storeDir, "Mezze.toml")) ?: extractVersionFromUrl(url)

                // Symlink this project's .deps/mezze/<name>-<version> -> the store entry.
                materializeSymlink(packagesDir, packageName, storeDir, version)
                println("  ✅ $packageName-$version -> ${storeDir.path}")

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
            println("🎉 Sync complete: $localCount local + ${lockedPackages.size} registry package(s).")
            println()
            println("📂 Dependencies at: ${packagesDir.absolutePath}")

        } finally {
            downloader.close()
        }
    }

    /**
     * Resolve local path dependencies **transitively**, hoisting every package in
     * the graph flat into the top project's `.deps/mezze/` — module resolution is
     * rooted at the top project, so a transitive dep (`app` -> `utils` -> `core`)
     * must live there too for `utils`'s `use core::...` to resolve. Each entry is a
     * symlink, so edits to any dep reflect immediately.
     *
     * Diamonds dedup by name (first wins — the top project's own declaration is
     * enqueued first, so it takes precedence); differing paths for the same name
     * warn; cycles terminate via the `done` set. Returns packages materialized.
     */
    private fun syncPathDeps(projectDir: File, topDeps: Map<String, String>): Int {
        // Module resolution roots at EACH package's own dir, so a package's deps
        // must live in that package's `.deps/mezze/` — not hoisted to the top. We
        // therefore walk the graph and link each dep into its DECLARER's `.deps`,
        // recursing into the dep's real dir. One `mez sync` sets up the whole
        // local graph (it also writes a gitignored `.deps/` into each local dep).
        val seen = HashSet<String>()                            // "<declarerCanonical>::<name>" — dedups diamonds, breaks cycles
        val queue = ArrayDeque<Triple<String, String, File>>()  // (name, rawPath, declaring dir)
        var materialized = 0
        topDeps.forEach { (n, p) -> queue.add(Triple(n, p, projectDir)) }

        while (queue.isNotEmpty()) {
            val (name, rawPath, declaringDir) = queue.removeFirst()
            if (!seen.add("${declaringDir.canonicalPath}::$name")) continue

            val target = File(rawPath)
                .let { if (it.isAbsolute) it else File(declaringDir, rawPath) }
                .canonicalFile

            val manifestFile = File(target, "Mezze.toml")
            if (!target.isDirectory || !manifestFile.exists()) {
                println("  ❌ $name: '$rawPath' (from ${declaringDir.name}) is not a Mezze package (resolved: $target)")
                continue
            }
            val depToml = try {
                Toml.decodeFromString(MezzeToml.serializer(), manifestFile.readText())
            } catch (e: Exception) {
                println("  ❌ $name: failed to parse ${target.name}/Mezze.toml — ${e.message}")
                continue
            }
            val version = depToml.`package`.version

            // Link into the DECLARING package's own .deps/mezze so that package's
            // `use <name>::...` resolves.
            val depsDir = File(declaringDir, ".deps/mezze").also { it.mkdirs() }
            materializeSymlink(depsDir, name, target, version)
            materialized++
            val where = if (declaringDir == projectDir) "" else "  (into ${declaringDir.name}/.deps)"
            println("  ✅ $name-$version -> ${target.path}$where")

            // Recurse: this dep's own path deps live in ITS dir's .deps.
            depToml.pathDependencies.forEach { (tn, tp) -> queue.add(Triple(tn, tp, target)) }
        }
        return materialized
    }

    /**
     * Symlink `<packagesDir>/<name>-<version>` -> target, replacing any stale
     * `<name>-*` entry first. Symlinks are removed as links (never recurse into the
     * target — that would delete the dep's own source).
     */
    private fun materializeSymlink(packagesDir: File, name: String, target: File, version: String) {
        packagesDir.listFiles { f -> f.name.startsWith("$name-") }?.forEach { stale ->
            val p = stale.toPath()
            if (java.nio.file.Files.isSymbolicLink(p)) java.nio.file.Files.delete(p) else stale.deleteRecursively()
        }
        java.nio.file.Files.createSymbolicLink(File(packagesDir, "$name-$version").toPath(), target.toPath())
    }

    /**
     * Extract a verified tarball into the content store at [storeDir], atomically:
     * unpack to a temp dir, find the package root (handles a single nested top-level
     * dir like GitHub archives), validate it has a Mezze.toml, then move it into
     * place. On any failure the temp dir is cleaned and [storeDir] is left untouched.
     */
    private fun extractIntoStore(archive: ArchiveHandler, archiveFile: File, storeDir: File, packageName: String) {
        val tmp = File(storeDir.parentFile, ".tmp-${storeDir.name}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            archive.extractTarGz(archiveFile, tmp, showProgress = false)
            val root = archive.findPackageRoot(tmp)
            archive.validatePackageStructure(root, packageName)   // throws if no Mezze.toml
            if (storeDir.exists()) storeDir.deleteRecursively()
            storeDir.parentFile.mkdirs()
            java.nio.file.Files.move(root.toPath(), storeDir.toPath())
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Best-effort read of `[package].version` from a package's Mezze.toml. */
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
