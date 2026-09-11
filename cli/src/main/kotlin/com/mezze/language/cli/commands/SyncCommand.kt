package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.*
import java.io.File

/**
 * Handle `mezze sync` command to download and install all dependencies
 *
 * Usage:
 *   mez sync              # Fetch/resolve all dependencies
 *   mez sync --clean      # Re-download deps (bypass the content-store cache)
 *   mez sync --force      # Clear the compiled cache → next build recompiles from source
 *   mez sync --locked     # Verify Mezze.lock matches (CI); fail on drift
 */
class SyncCommand {

    /** Returns a process exit code: 0 on success, non-zero if any dependency
     *  failed to resolve / verify, or `--locked` found the lock out of date. */
    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        storeRootOverride: File? = null,
    ): Int {
        // Walk up to the nearest Mezze.toml so `mez sync` works from any
        // subdirectory and .deps/ + Mezze.lock land at the project root (ProjectRoot).
        val root = ProjectRoot.discover(currentDir) ?: currentDir
        val mezzeToml = File(root, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml in ${currentDir.absolutePath} or any parent directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'mezze init' to create a new package first.")
            return 1
        }

        val isClean = args.contains("--clean")     // re-download deps (bypass content-store cache)
        val isForce = args.contains("--force")     // wipe the compiled cache → next build recompiles from source
        val isLocked = args.contains("--locked")   // verify against Mezze.lock instead of writing it

        // --force: clear the project's compiled cache (.mez/store) so the next
        // check/build/dev recompiles everything from source — the escape hatch for
        // "recompile past the cache", independent of the content-hash reuse gates.
        if (isForce) {
            com.mezze.language.codebase.SQLiteMezzeStore.wipeProjectFiles(root)
            println("🔨 --force: cleared the compiled cache; the next build recompiles from source.")
        }

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
        val packagesDir = File(root, ".deps/mezze")
        // Download staging for fetched tarballs — under the global mezze home
        // (honors MEZZE_HOME), NOT a stray ~/.mez/cache. `cleanInstall` wipes
        // ~/.mezze/cache, so this is now the one it actually cleans.
        val cacheDir = File(com.mezze.language.cli.MezzeHome.home, "cache")

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
            val count = resolveDeps(root, manifest, storeRoot, isClean, downloader, archive, nodes, failures)

            // Complete graph lock: the root package + every resolved node, deduped + sorted.
            val rootEdges = (manifest.pathDependencies.keys + manifest.dependencies.keys + manifest.devDependencies.keys).sorted()
            val rootNode = LockedNode(manifest.`package`.name, manifest.`package`.version, source = "root", dependencies = rootEdges)
            val graph = (listOf(rootNode) + nodes).distinctBy { it.name }.sortedBy { it.name }
            val freshLock = MezzeLock(
                version = 2,
                root = manifest.`package`.name,
                ir = com.mezze.language.store.StoreSerialization.IR_VERSION,
                packages = graph,
            )

            val lockFile = File(root, "Mezze.lock")
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
        // Concurrency: fetching is network-bound, so the graph is walked in parallel
        // WAVES — every dep in the current frontier is resolved/fetched at once, then
        // their own deps form the next wave. seen (dedup) is a concurrent set;
        // resolveRegistryTarget serializes per-sha (below) so a diamond fetches once.
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()  // "<declarerCanonical>::<name>"
        val safeNodes = java.util.Collections.synchronizedList(nodes)
        val safeFailures = java.util.Collections.synchronizedList(failures)
        val printLock = Any()
        val count = java.util.concurrent.atomic.AtomicInteger(0)

        // Resolve ONE dep; return its own direct deps (the next wave). Runs on a pool thread.
        fun process(declaringDir: File, name: String, spec: DepSpec): List<Triple<File, String, DepSpec>> {
            val target = when (spec) {
                is DepSpec.Path -> resolvePathTarget(declaringDir, name, spec.rawPath, printLock)
                is DepSpec.Registry -> resolveRegistryTarget(name, spec, storeRoot, isClean, downloader, archive, printLock)
            }
            if (target == null) { safeFailures.add(name); return emptyList() }

            val depToml = readToml(File(target, "Mezze.toml"))
            if (depToml == null) {
                synchronized(printLock) { println("  ❌ $name: no readable Mezze.toml in $target") }
                safeFailures.add(name)
                return emptyList()
            }
            val version = depToml.`package`.version
            // Link into the DECLARING package's own .deps/mezze so its `use` resolves.
            File(declaringDir, ".deps/mezze").also { it.mkdirs() }.let {
                materializeSymlink(it, name, target, version)
            }
            count.incrementAndGet()
            val where = if (declaringDir == rootDir) "" else "  (into ${declaringDir.name}/.deps)"
            synchronized(printLock) { println("  ✅ $name-$version -> ${target.path}$where") }

            val edges = (depToml.pathDependencies.keys + depToml.dependencies.keys).sorted()
            safeNodes.add(
                when (spec) {
                    is DepSpec.Path -> LockedNode(name, version, source = "path", path = target.path, dependencies = edges)
                    is DepSpec.Registry -> LockedNode(name, version, source = "registry", url = spec.url, checksum = "sha256:${spec.sha256}", dependencies = edges)
                }
            )
            // This dep's own deps resolve against ITS dir. Dev-deps are the top
            // project's only — a dependency's dev-deps are not the consumer's.
            return directDepsOf(target, depToml, includeDev = false, safeFailures, printLock)
        }

        var frontier = directDepsOf(rootDir, rootToml, includeDev = true, safeFailures, printLock)
        if (frontier.isEmpty()) return 0

        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(16, maxOf(4, Runtime.getRuntime().availableProcessors() * 2)),
        )
        try {
            while (frontier.isNotEmpty()) {
                // Dedup this wave against everything seen (thread-safe first-writer-wins).
                val fresh = frontier.filter { seen.add("${it.first.canonicalPath}::${it.second}") }
                val futures = fresh.map { (dir, name, spec) ->
                    pool.submit(java.util.concurrent.Callable { process(dir, name, spec) })
                }
                frontier = futures.flatMap { it.get() }
            }
        } finally {
            pool.shutdown()
        }
        return count.get()
    }

    /** A package's direct deps as (declaringDir, name, spec) triples. Pure (no I/O). */
    private fun directDepsOf(
        dir: File,
        toml: MezzeToml,
        includeDev: Boolean,
        failures: MutableList<String>,
        printLock: Any,
    ): List<Triple<File, String, DepSpec>> {
        val out = mutableListOf<Triple<File, String, DepSpec>>()
        toml.pathDependencies.forEach { (n, p) -> out.add(Triple(dir, n, DepSpec.Path(p))) }
        val registry = if (includeDev) toml.dependencies + toml.devDependencies else toml.dependencies
        registry.forEach { (n, fullUrl) ->
            try {
                val (url, sha) = parseDependencyUrl(fullUrl)
                out.add(Triple(dir, n, DepSpec.Registry(url, sha)))
            } catch (e: IllegalArgumentException) {
                synchronized(printLock) { println("  ❌ $n: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid dependency URL"}") }
                failures.add(n)
            }
        }
        return out
    }

    /** Per-sha200 lock so concurrent waves fetching the SAME content (diamonds) fetch once. */
    private val shaLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun resolvePathTarget(declaringDir: File, name: String, rawPath: String, printLock: Any): File? {
        val target = File(rawPath).let { if (it.isAbsolute) it else File(declaringDir, rawPath) }.canonicalFile
        if (!target.isDirectory || !File(target, "Mezze.toml").exists()) {
            synchronized(printLock) { println("  ❌ $name: '$rawPath' (from ${declaringDir.name}) is not a Mezze package (resolved: $target)") }
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
        printLock: Any,
    ): File? {
        val storeDir = File(storeRoot, spec.sha256)
        // Serialize per content hash: two waves needing the same sha download+extract
        // once; the loser sees the cache hit. Different shas run fully in parallel.
        synchronized(shaLocks.computeIfAbsent(spec.sha256) { Any() }) {
            if (File(storeDir, "Mezze.toml").exists() && !isClean) return storeDir   // content-addressed cache hit

            val archiveFile = try {
                downloader.downloadPackage(name, spec.url, spec.sha256)
            } catch (e: Exception) {
                synchronized(printLock) { println((e.message ?: "❌ $name: download failed").prependIndent("  ")) }
                return null
            }
            return try {
                extractIntoStore(archive, archiveFile, storeDir, name)
                storeDir
            } catch (e: ArchiveException) {
                synchronized(printLock) { println((e.message ?: "❌ $name: extract failed").prependIndent("  ")) }
                null
            }
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
