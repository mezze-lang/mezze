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

    /** Returns a process exit code: 0 on success, non-zero if any dependency
     *  failed to resolve / verify, or `--locked` found the lock out of date. */
    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        storeRootOverride: File? = null,
    ): Int {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml found in current directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'mezze init' to create a new package first.")
            return 1
        }

        val isClean = args.contains("--clean")
        val isLocked = args.contains("--locked")   // verify against Mezze.lock instead of writing it

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
            return 1
        }

        val hasDeps = manifest.dependencies.isNotEmpty() ||
            manifest.devDependencies.isNotEmpty() ||
            manifest.pathDependencies.isNotEmpty()
        if (!hasDeps) {
            println("✅ No dependencies to sync.")
            println()
            println("💡 Add a local dep under [path-dependencies] (name = \"../path\"),")
            println("   or a registry dep under [dependencies] (name = \"<url>#sha256:<hash>\").")
            return 0
        }

        println("📦 Syncing dependencies...")
        println()

        // Deps are materialized into each package's `.deps/mezze/<name>-<version>/` —
        // where ModuleResolver.resolveModuleFile searches — so `use <name>::...`
        // resolves with no further wiring.
        val packagesDir = File(currentDir, ".deps/mezze")
        val cacheDir = File(System.getProperty("user.home"), ".mez/cache")

        // Global content store: extracted registry packages keyed by sha256, shared
        // across ALL projects (fetch once, ever). Override with MEZZE_STORE_DIR (tests).
        val storeRoot = storeRootOverride
            ?: System.getenv("MEZZE_STORE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".mezze/store")

        if (isClean && packagesDir.exists()) {
            println("🧹 Cleaning .deps/mezze/...")
            packagesDir.deleteRecursively()
        }
        packagesDir.mkdirs()
        cacheDir.mkdirs()

        val downloader = PackageDownloader(cacheDir)
        val archive = ArchiveHandler()
        val nodes = mutableListOf<LockedNode>()
        val failures = mutableListOf<String>()   // deps that could not be resolved/verified
        var lockOutOfDate = false

        try {
            val count = resolveDeps(currentDir, manifest, storeRoot, isClean, downloader, archive, nodes, failures)

            // Complete graph lock: the root package + every resolved node, deduped + sorted.
            val rootEdges = (manifest.pathDependencies.keys + manifest.dependencies.keys + manifest.devDependencies.keys).sorted()
            val rootNode = LockedNode(manifest.`package`.name, manifest.`package`.version, source = "root", dependencies = rootEdges)
            val graph = (listOf(rootNode) + nodes).distinctBy { it.name }.sortedBy { it.name }
            val freshLock = MezzeLock(version = 2, root = manifest.`package`.name, packages = graph)

            val lockFile = File(currentDir, "Mezze.lock")
            if (isLocked) {
                val existing = readLockFile(lockFile)
                when {
                    existing == null -> {
                        println("❌ --locked: no Mezze.lock to verify. Run 'mez sync' first.")
                        lockOutOfDate = true
                    }
                    existing.packages != freshLock.packages -> {
                        println("❌ --locked: Mezze.lock is out of date with the resolved graph.")
                        println("   Run 'mez sync' (without --locked) to update it.")
                        lockOutOfDate = true
                    }
                    else -> {
                        println()
                        println("🔒 Mezze.lock verified — graph matches (${graph.size} package(s)).")
                    }
                }
            } else {
                try {
                    writeLockFile(lockFile, freshLock)
                    println()
                    println("🔒 Locked ${graph.size} package(s) in Mezze.lock")
                } catch (e: Exception) {
                    println("⚠️  Warning: Failed to update Mezze.lock — ${e.message}")
                }
            }

            println()
            if (failures.isEmpty()) {
                println("🎉 Sync complete: $count package(s).")
            } else {
                println("⚠️  Synced $count package(s); ${failures.size} failed: ${failures.distinct().joinToString(", ")}")
            }
            println("📂 Dependencies at: ${packagesDir.absolutePath}")

            // A declared dep that can't be resolved/verified (bad checksum, missing
            // path, unreadable manifest) — or a stale lock under --locked — is a hard
            // failure: the project won't compile, and CI must see a non-zero exit.
            return if (failures.isEmpty() && !lockOutOfDate) 0 else 1
        } finally {
            downloader.close()
        }
    }

    private sealed class DepSpec {
        data class Path(val rawPath: String) : DepSpec()
        data class Registry(val url: String, val sha256: String) : DepSpec()
    }

    /**
     * Walk the whole dependency graph — local `path` deps AND remote `url#sha256`
     * deps, transitively — linking each dep into its DECLARER's `.deps/mezze/`.
     * Module resolution roots at each package's own dir, so a dep (a local dir or a
     * shared content-store entry) resolves *its* imports against *its* `.deps` — a
     * registry dep's sub-deps land in that store entry's `.deps` (shared, since a
     * package's deps are a pure function of the package). One `mez sync` sets up the
     * entire graph. Diamonds dedup and cycles terminate via a seen-set keyed on
     * declarer+name. Returns the number of packages materialized.
     */
    private fun resolveDeps(
        rootDir: File,
        rootToml: MezzeToml,
        storeRoot: File,
        isClean: Boolean,
        downloader: PackageDownloader,
        archive: ArchiveHandler,
        nodes: MutableList<LockedNode>,
        failures: MutableList<String>,
    ): Int {
        val seen = HashSet<String>()                              // "<declarerCanonical>::<name>"
        val queue = ArrayDeque<Triple<File, String, DepSpec>>()   // (declaringDir, name, spec)
        enqueue(queue, rootDir, rootToml, includeDev = true, failures)

        var count = 0
        while (queue.isNotEmpty()) {
            val (declaringDir, name, spec) = queue.removeFirst()
            if (!seen.add("${declaringDir.canonicalPath}::$name")) continue

            val target = when (spec) {
                is DepSpec.Path -> resolvePathTarget(declaringDir, name, spec.rawPath)
                is DepSpec.Registry -> resolveRegistryTarget(name, spec, storeRoot, isClean, downloader, archive)
            }
            if (target == null) { failures.add(name); continue }   // resolver already printed why

            val depToml = readToml(File(target, "Mezze.toml"))
            if (depToml == null) {
                println("  ❌ $name: no readable Mezze.toml in $target")
                failures.add(name)
                continue
            }
            val version = depToml.`package`.version

            // Link into the DECLARING package's own .deps/mezze so its `use` resolves.
            val depsDir = File(declaringDir, ".deps/mezze").also { it.mkdirs() }
            materializeSymlink(depsDir, name, target, version)
            count++
            val where = if (declaringDir == rootDir) "" else "  (into ${declaringDir.name}/.deps)"
            println("  ✅ $name-$version -> ${target.path}$where")

            // Record this node in the graph lock, with its own direct-dep edges.
            val edges = (depToml.pathDependencies.keys + depToml.dependencies.keys).sorted()
            nodes.add(
                when (spec) {
                    is DepSpec.Path -> LockedNode(name, version, source = "path", path = target.path, dependencies = edges)
                    is DepSpec.Registry -> LockedNode(name, version, source = "registry", url = spec.url, checksum = "sha256:${spec.sha256}", dependencies = edges)
                }
            )

            // Recurse: this dep's own deps resolve against ITS dir. Dev-deps are the
            // top project's only — a dependency's dev-deps are not the consumer's.
            enqueue(queue, target, depToml, includeDev = false, failures)
        }
        return count
    }

    private fun enqueue(
        queue: ArrayDeque<Triple<File, String, DepSpec>>,
        dir: File,
        toml: MezzeToml,
        includeDev: Boolean,
        failures: MutableList<String>,
    ) {
        toml.pathDependencies.forEach { (n, p) -> queue.add(Triple(dir, n, DepSpec.Path(p))) }
        val registry = if (includeDev) toml.dependencies + toml.devDependencies else toml.dependencies
        registry.forEach { (n, fullUrl) ->
            try {
                val (url, sha) = parseDependencyUrl(fullUrl)
                queue.add(Triple(dir, n, DepSpec.Registry(url, sha)))
            } catch (e: IllegalArgumentException) {
                println("  ❌ $n: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid dependency URL"}")
                failures.add(n)
            }
        }
    }

    private fun resolvePathTarget(declaringDir: File, name: String, rawPath: String): File? {
        val target = File(rawPath).let { if (it.isAbsolute) it else File(declaringDir, rawPath) }.canonicalFile
        if (!target.isDirectory || !File(target, "Mezze.toml").exists()) {
            println("  ❌ $name: '$rawPath' (from ${declaringDir.name}) is not a Mezze package (resolved: $target)")
            return null
        }
        return target
    }

    private fun resolveRegistryTarget(
        name: String,
        spec: DepSpec.Registry,
        storeRoot: File,
        isClean: Boolean,
        downloader: PackageDownloader,
        archive: ArchiveHandler,
    ): File? {
        val storeDir = File(storeRoot, spec.sha256)
        if (File(storeDir, "Mezze.toml").exists() && !isClean) return storeDir   // content-addressed cache hit

        val archiveFile = try {
            downloader.downloadPackage(name, spec.url, spec.sha256)
        } catch (e: Exception) {
            println((e.message ?: "❌ $name: download failed").prependIndent("  "))
            return null
        }
        return try {
            extractIntoStore(archive, archiveFile, storeDir, name)
            storeDir
        } catch (e: ArchiveException) {
            println((e.message ?: "❌ $name: extract failed").prependIndent("  "))
            null
        }
    }

    private fun readToml(manifest: File): MezzeToml? =
        try {
            Toml.decodeFromString(MezzeToml.serializer(), manifest.readText())
        } catch (e: Exception) {
            null
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

}
