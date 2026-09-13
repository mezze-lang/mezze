package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.*
import java.io.File

/**
 * Handle `mezze sync` command to download and install all dependencies
 *
 * Usage:
 *   mez sync              # Fetch/resolve all dependencies
 *   mez sync --verbose    # Also print a line per resolved package (-v)
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
        /** Where `github:` deps are `git clone`d to. Defaults to `<MezzeHome>/cache/git`. Tests use a temp dir. */
        githubCacheOverride: File? = null,
        /** Replaces `https://github.com/` when composing clone URLs. Tests point this at a local bare repo. */
        githubBaseUrlOverride: String? = null,
    ): Int {
        // Walk up to the nearest Mezze.toml so `mez sync` works from any
        // subdirectory and .deps/ + Mezze.lock land at the project root (ProjectRoot).
        val root = ProjectRoot.discover(currentDir) ?: currentDir
        val mezzeToml = File(root, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("${Glyphs.ERROR} Error: No Mezze.toml in ${currentDir.absolutePath} or any parent directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("${Glyphs.TIP} Run 'mezze init' to create a new package first.")
            return 1
        }

        val isClean = args.contains("--clean")     // re-download deps (bypass content-store cache)
        val isForce = args.contains("--force")     // wipe the compiled cache → next build recompiles from source
        val isLocked = args.contains("--locked")   // verify against Mezze.lock instead of writing it
        val isVerbose = args.contains("--verbose") || args.contains("-v")  // per-package lines, not just the bar

        // --force: clear the project's compiled cache (.mez/store) so the next
        // check/build/dev recompiles everything from source — the escape hatch for
        // "recompile past the cache", independent of the content-hash reuse gates.
        if (isForce) {
            com.mezze.language.codebase.SQLiteMezzeStore.wipeProjectFiles(root)
            println("${Glyphs.HAMMER} --force: cleared the compiled cache; the next build recompiles from source.")
        }

        // Read Mezze.toml
        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("${Glyphs.ERROR} Error: Failed to parse Mezze.toml")
            println()
            println("The manifest file has invalid TOML syntax.")
            println("Error: ${e.message}")
            println()
            println("${Glyphs.TIP} Check the syntax of your Mezze.toml file.")
            return 1
        }

        // Semantic validation of `[polyglot.*]` and `[jars]` — surface every
        // failure in one pass with a source-location + did-you-mean hint. The
        // cross-check (packages declared but no provider on deps) reads the
        // literal `mezze-<lang>` naming convention out of the [dependencies]
        // table, so a well-authored user manifest that maps `mezze-python →
        // github:…` satisfies the check without any manual provider hint.
        val providerLangs = inferProviderLanguages(manifest)
        val validation = PolyglotValidator.validate(manifest, rawText = tomlContent, providerLanguagesInDeps = providerLangs)
        if (!validation.ok) {
            println("${Glyphs.ERROR} Error: Mezze.toml has ${validation.errors.size} problem(s):")
            for (e in validation.errors) println("  - ${e.render()}")
            println()
            println("${Glyphs.TIP} Fix these and rerun 'mez sync'.")
            return 1
        }

        val hasDeps = manifest.dependencies.isNotEmpty() ||
            manifest.devDependencies.isNotEmpty() ||
            manifest.pathDependencies.isNotEmpty()
        if (!hasDeps) {
            println("${Glyphs.OK} No dependencies to sync.")
            println()
            println("${Glyphs.TIP} Add a local dep under [path-dependencies] (name = \"../path\"),")
            println("   or a registry dep under [dependencies] (name = \"<url>#sha256:<hash>\").")
            return 0
        }

        println("${Glyphs.PACKAGE} Syncing dependencies...")
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
            println("${Glyphs.BROOM} Cleaning .deps/mezze/...")
            packagesDir.deleteRecursively()
        }
        packagesDir.mkdirs()
        cacheDir.mkdirs()

        val downloader = PackageDownloader(cacheDir)
        val archive = ArchiveHandler()
        // Per-`(owner,repo,ref)` clone cache. Shared across all projects (like
        // the content store) since a github ref resolves to the same bytes for
        // everyone. Tests inject a temp dir to keep runs hermetic.
        val gitCacheDir = githubCacheOverride ?: File(cacheDir, "git")
        gitCacheDir.mkdirs()
        val githubFetcher = GithubDepFetcher(gitCacheDir, githubBaseUrlOverride)
        val nodes = mutableListOf<LockedNode>()
        val failures = mutableListOf<String>()   // deps that could not be resolved/verified
        var lockOutOfDate = false

        try {
            val count = resolveDeps(root, manifest, storeRoot, isClean, isVerbose, downloader, archive, githubFetcher, nodes, failures)

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
                        println("${Glyphs.ERROR} --locked: no Mezze.lock to verify. Run 'mez sync' first.")
                        lockOutOfDate = true
                    }
                    existing.packages != freshLock.packages -> {
                        println("${Glyphs.ERROR} --locked: Mezze.lock is out of date with the resolved graph.")
                        println("   Run 'mez sync' (without --locked) to update it.")
                        lockOutOfDate = true
                    }
                    else -> {
                        println()
                        println("${Glyphs.LOCK} Mezze.lock verified — graph matches (${graph.size} package(s)).")
                    }
                }
            } else {
                try {
                    writeLockFile(lockFile, freshLock)
                    println()
                    println("${Glyphs.LOCK} Locked ${graph.size} package(s) in Mezze.lock")
                } catch (e: Exception) {
                    println("${Glyphs.WARN}  Warning: Failed to update Mezze.lock — ${e.message}")
                }
            }

            println()
            if (failures.isEmpty()) {
                println("${Glyphs.PARTY} Sync complete: $count package(s).")
            } else {
                println("${Glyphs.WARN}  Synced $count package(s); ${failures.size} failed: ${failures.distinct().joinToString(", ")}")
            }
            println("${Glyphs.FOLDER} Dependencies at: ${packagesDir.absolutePath}")

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
        data class Github(val owner: String, val repo: String, val ref: String) : DepSpec()
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
        verbose: Boolean,
        downloader: PackageDownloader,
        archive: ArchiveHandler,
        githubFetcher: GithubDepFetcher,
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
        // Library deps to compile-once after resolution, as (depDir, libRelPath,
        // moduleName). Collected on pool threads; the compile itself runs
        // sequentially below because DepPrecompiler swaps a global sysprop.
        val precompileTargets = java.util.Collections.synchronizedList(
            mutableListOf<Triple<File, String, String>>()
        )
        // Live progress across the parallel download waves + the sequential
        // precompile below: a sticky progress bar on a TTY. --verbose adds a line
        // per resolved package; failures always print.
        val reporter = SyncReporter(verbose = verbose)

        // For a github dep we don't know the resolved commit sha until AFTER the
        // clone — the store dir is keyed on `sha256(commit)` — so `process` needs
        // to hand that back to the caller for the lock. Keyed by declarer + name.
        val githubResolved = java.util.concurrent.ConcurrentHashMap<String, GithubResolved>()

        // Resolve ONE dep; return its own direct deps (the next wave). Runs on a pool thread.
        fun process(declaringDir: File, name: String, spec: DepSpec): List<Triple<File, String, DepSpec>> {
            val target = when (spec) {
                is DepSpec.Path -> resolvePathTarget(declaringDir, name, spec.rawPath, printLock)
                is DepSpec.Registry -> resolveRegistryTarget(name, spec, storeRoot, isClean, downloader, archive, printLock, reporter)
                is DepSpec.Github -> {
                    val res = resolveGithubTarget(name, spec, storeRoot, isClean, githubFetcher, printLock, reporter)
                    if (res != null) githubResolved["${declaringDir.canonicalPath}::$name"] = res
                    res?.storeDir
                }
            }
            if (target == null) {
                safeFailures.add(name)
                reporter.failed("  ${Glyphs.ERROR} $name: could not resolve", name)
                return emptyList()
            }

            val depToml = readToml(File(target, "Mezze.toml"))
            if (depToml == null) {
                reporter.failed("  ${Glyphs.ERROR} $name: no readable Mezze.toml in $target", name)
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
            reporter.resolved("  ${Glyphs.OK} $name-$version -> ${target.path}$where", name)

            val edges = (depToml.pathDependencies.keys + depToml.dependencies.keys).sorted()
            safeNodes.add(
                when (spec) {
                    is DepSpec.Path -> LockedNode(name, version, source = "path", path = target.path, dependencies = edges)
                    is DepSpec.Registry -> LockedNode(name, version, source = "registry", url = spec.url, checksum = "sha256:${spec.sha256}", dependencies = edges)
                    is DepSpec.Github -> {
                        // The commit sha the ref resolved to at sync time is
                        // what pins reproducibility — `main` may shift, this
                        // sha won't. Recorded verbatim from `git rev-parse HEAD`.
                        val res = githubResolved["${declaringDir.canonicalPath}::$name"]
                        LockedNode(
                            name, version, source = "github",
                            owner = spec.owner, repo = spec.repo, ref = spec.ref,
                            commit = res?.commit,
                            checksum = res?.let { "sha256:${it.storeSha}" },
                            dependencies = edges,
                        )
                    }
                }
            )
            // A library dep gets compiled once (below) into a shared store, so
            // consumers load its IR instead of recompiling it. Apps have no
            // [lib] entry → nothing to share.
            depToml.lib?.let { precompileTargets.add(Triple(target, it.path, name)) }
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
                fresh.forEach { reporter.discovered() }
                val futures = fresh.map { (dir, name, spec) ->
                    pool.submit(java.util.concurrent.Callable { process(dir, name, spec) })
                }
                frontier = futures.flatMap { it.get() }
            }
        } finally {
            pool.shutdown()
        }

        // Compile-once: each library dep is compiled from source into a shared,
        // content-addressed store the first time it's seen, so every consuming
        // project loads its IR instead of recompiling it. Sequential (each
        // compile temporarily swaps the global project-root sysprop); order-free
        // (every dep compiles independently from its own source). Idempotent and
        // best-effort — a miss just means the consumer compiles from source.
        //
        // On by default: the consumer read-side is wired (DepStoreLoader feeds
        // PrecompiledDeps → the typechecker serves this IR instead of re-lowering
        // deps, and computeReachableClosure carries it into the closure). Disable
        // with MEZZE_PRECOMPILE_DEPS=0 to force every consumer to compile deps
        // from source.
        val distinctTargets = precompileTargets.distinctBy { it.first.canonicalPath }
        if (distinctTargets.isNotEmpty() && System.getenv("MEZZE_PRECOMPILE_DEPS") != "0") {
            val toCompile = distinctTargets.filterNot { DepPrecompiler.compiledStoreFile(it.first).isFile }
            reporter.beginCompile(toCompile.size)
            var compiled = 0
            for ((depDir, libRel, moduleName) in distinctTargets) {
                if (DepPrecompiler.compiledStoreFile(depDir).isFile) continue
                reporter.compileStart(moduleName)
                DepPrecompiler.precompile(depDir, libRel, moduleName)
                if (DepPrecompiler.compiledStoreFile(depDir).isFile) compiled++
                reporter.compileDone()
            }
            reporter.finish()
            if (compiled > 0) println("  ${Glyphs.GEAR}  precompiled $compiled dependenc${if (compiled == 1) "y" else "ies"}")
        } else {
            reporter.finish()
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
                if (fullUrl.startsWith("github:")) {
                    // `github:<owner>/<repo>@<ref>` — checked out live via git,
                    // pinned by resolved commit sha in the lock. Sits in
                    // `[dependencies]` alongside URL#sha256 deps because it's
                    // remote content that can go stale; `[path-dependencies]`
                    // is for local mutable trees only.
                    val (owner, repo, ref) = parseGithubDep(fullUrl)
                    out.add(Triple(dir, n, DepSpec.Github(owner, repo, ref)))
                } else {
                    val (url, sha) = parseDependencyUrl(fullUrl)
                    out.add(Triple(dir, n, DepSpec.Registry(url, sha)))
                }
            } catch (e: IllegalArgumentException) {
                synchronized(printLock) { println("  ${Glyphs.ERROR} $n: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid dependency URL"}") }
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
            synchronized(printLock) { println("  ${Glyphs.ERROR} $name: '$rawPath' (from ${declaringDir.name}) is not a Mezze package (resolved: $target)") }
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
        reporter: SyncReporter,
    ): File? {
        val storeDir = File(storeRoot, spec.sha256)
        // Serialize per content hash: two waves needing the same sha download+extract
        // once; the loser sees the cache hit. Different shas run fully in parallel.
        synchronized(shaLocks.computeIfAbsent(spec.sha256) { Any() }) {
            if (File(storeDir, "Mezze.toml").exists() && !isClean) return storeDir   // content-addressed cache hit

            val archiveFile = try {
                downloader.downloadPackage(
                    name, spec.url, spec.sha256,
                    onStart = { total -> reporter.downloadStart(name, total) },
                    onProgress = { received -> reporter.downloadProgress(name, received) },
                )
            } catch (e: Exception) {
                synchronized(printLock) { println((e.message ?: "${Glyphs.ERROR} $name: download failed").prependIndent("  ")) }
                return null
            }
            return try {
                extractIntoStore(archive, archiveFile, storeDir, name)
                storeDir
            } catch (e: ArchiveException) {
                synchronized(printLock) { println((e.message ?: "${Glyphs.ERROR} $name: extract failed").prependIndent("  ")) }
                null
            }
        }
    }

    /** Result of a successful github fetch — the store dir the symlink points at plus what to write into the lock. */
    private data class GithubResolved(val storeDir: File, val commit: String, val storeSha: String)

    /**
     * Fetch a `github:` dep the same way registry deps are handled: reuse the
     * content-addressed `~/.mezze/store/<sha>/` if it already exists, otherwise
     * `git clone` into the shared cache, resolve HEAD, and copy the checkout
     * (minus `.git/`) into the store. Two waves needing the same repo/ref/commit
     * serialize on the resolved sha via [shaLocks] — no duplicate clones.
     *
     * Failures (git not on PATH, ref doesn't exist, unreadable clone) surface
     * as `null` here; the caller records the failure and continues so one bad
     * dep doesn't block the rest.
     */
    private fun resolveGithubTarget(
        name: String,
        spec: DepSpec.Github,
        storeRoot: File,
        isClean: Boolean,
        githubFetcher: GithubDepFetcher,
        printLock: Any,
        reporter: SyncReporter,
    ): GithubResolved? {
        // We must clone before we know the sha, but we do want to dedupe
        // parallel fetches of the same (owner,repo,ref). Serialize on the
        // cache-clone-dir key first — waves for the same repo/ref share the
        // clone. Then, once we know the commit sha, we're already in the
        // consistent state that `<sha>/` needs.
        val cloneKey = "${spec.owner}/${spec.repo}@${spec.ref}"
        synchronized(shaLocks.computeIfAbsent(cloneKey) { Any() }) {
            val fetched: GithubDepFetcher.Fetched = try {
                githubFetcher.fetch(spec.owner, spec.repo, spec.ref)
            } catch (e: GithubFetchException) {
                synchronized(printLock) { println((e.message ?: "${Glyphs.ERROR} $name: git fetch failed").prependIndent("  ")) }
                return null
            }
            val storeDir = File(storeRoot, fetched.storeSha)
            // Content-addressed cache hit — different declarers landing on
            // the same commit share this exact store entry.
            if (File(storeDir, "Mezze.toml").exists() && !isClean) {
                return GithubResolved(storeDir, fetched.commit, fetched.storeSha)
            }
            return try {
                githubFetcher.materializeStore(fetched.cloneDir, storeDir)
                if (!File(storeDir, "Mezze.toml").exists()) {
                    synchronized(printLock) {
                        println("  ${Glyphs.ERROR} $name: repository has no Mezze.toml at its root (${spec.owner}/${spec.repo}@${spec.ref})")
                    }
                    return null
                }
                GithubResolved(storeDir, fetched.commit, fetched.storeSha)
            } catch (e: Exception) {
                synchronized(printLock) {
                    println("  ${Glyphs.ERROR} $name: failed to materialize github dep — ${e.message}")
                }
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

    /**
     * Provider-language set for the cross-check in `PolyglotValidator`: every
     * `[dependencies]` / `[dev-dependencies]` / `[path-dependencies]` entry
     * named `mezze-<lang>` counts as a provider for `<lang>`. This lets a user
     * project like:
     *
     *   [polyglot.python]
     *   packages = ["numpy"]
     *
     *   [dependencies]
     *   mezze-python = "github:mezze-lang/mezze-python@1.0.0"
     *
     * satisfy the cross-check without any extra wiring. If the project author
     * uses a non-standard provider name, the validator surfaces the mismatch
     * so they see the convention up-front.
     */
    private fun inferProviderLanguages(manifest: com.mezze.language.cli.`package`.MezzeToml): Set<String> {
        val names = manifest.dependencies.keys +
            manifest.devDependencies.keys +
            manifest.pathDependencies.keys
        // Accept both `mezze-<lang>` (github repo-style, e.g. `mezze-python`) and
        // `mezze_<lang>` (Mezze module-name-style, e.g. `mezze_python`) as the
        // provider-package convention. Package keys become Mezze module segments
        // and thus can't contain hyphens; users writing `use mezze_python::...`
        // must use the underscore form as their `[dependencies]` key.
        return names.mapNotNull { n ->
            val stripped = n.removePrefix("mezze-").let { if (it == n) it.removePrefix("mezze_") else it }
            stripped.takeIf { it != n && it.isNotEmpty() }
        }.toSet()
    }

}
