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
        /** Where Aether writes the maven cache. Defaults to `<MezzeHome>/cache/maven`. Tests use a temp dir. */
        mavenCacheOverride: File? = null,
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

            // ── [jars] fetch phase ──────────────────────────
            //
            // Every resolved package (and the root) contributes to a
            // single deduped list of jars. Fetched in parallel (per-sha
            // lock; diamonds fetch once) and placed at
            // `<store>/jars/<sha256>/<basename>.jar`. A failure here is
            // a hard failure — the wire child needs those jars on its
            // classpath, and a partial install would silently break.
            val lockedJars = fetchJarsForGraph(root, manifest, graph, storeRoot, isClean, failures)

            // ── [maven] resolve phase (Aether) ─────────────
            //
            // Every resolved package (and the root) contributes to a
            // deduped list of maven coords + repos. Aether fetches
            // each into `~/.mezze/cache/maven/` (isolated from the
            // user's `~/.m2/`); the returned jars go on the wire
            // child's classpath alongside `[jars]` entries and lock
            // as `LockedMaven` rows.
            //
            // Version conflicts across the graph follow Aether's
            // nearest-wins policy; the merge logs a warning when the
            // same coord's version differs across packages so the
            // drift is visible in `mez sync` output.
            val lockedMaven = resolveMavenForGraph(root, manifest, graph, storeRoot, mavenCacheOverride, failures)

            // ── [polyglot.<lang>].packages install phase ────
            //
            // For every language the user declared `packages` under
            // (`[polyglot.python]`, `[polyglot.js]`, …), find its
            // provider in the resolved graph and run its declared
            // install template with `{prefix}` / `{dest}` / `{packages}`
            // substituted. Idempotent: skipped when the marker file at
            // `.deps/<runtime_dir>/.mez-installed.json` already records
            // the same provider commit + package list.
            //
            // Only meaningful when the resolve phase itself succeeded —
            // a provider dep that failed to materialize can't have its
            // install template read.
            if (failures.isEmpty()) {
                runProviderInstalls(root, manifest, graph, storeRoot, isLocked, failures)
            }

            val freshLock = MezzeLock(
                version = 2,
                root = manifest.`package`.name,
                ir = com.mezze.language.store.StoreSerialization.IR_VERSION,
                packages = graph,
                jars = lockedJars,
                maven = lockedMaven,
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
                    existing.jars != freshLock.jars -> {
                        println("${Glyphs.ERROR} --locked: Mezze.lock is out of date with the resolved [jars] set.")
                        println("   Run 'mez sync' (without --locked) to update it.")
                        lockOutOfDate = true
                    }
                    existing.maven != freshLock.maven -> {
                        println("${Glyphs.ERROR} --locked: Mezze.lock is out of date with the resolved [maven] set.")
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
            // FLAT layout: link EVERY dep (direct + transitive) into the TOP
            // project's single .deps/mezze, never the declarer's. Content is
            // addressed by <module>-<version>, so different versions of the same
            // package coexist as distinct entries — no need to nest .deps inside
            // dep dirs (which polluted sibling path-dep source trees). The
            // resolver looks in the top project's .deps for all deps.
            File(rootDir, ".deps/mezze").also { it.mkdirs() }.let {
                materializeSymlink(it, name, target, version)
            }
            count.incrementAndGet()
            reporter.resolved("  ${Glyphs.OK} $name-$version -> ${target.path}", name)

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
        // Name the dir by the MODULE spelling (`-`→`_`) so `use <module>::…`
        // resolves it — the resolver searches `.deps/mezze/<module>-*`, and a
        // module path segment can't contain a hyphen. A dep declared
        // `mezze-python` is imported as `mezze_python`, so its dir is
        // `mezze_python-<version>`. See packageNameToModule.
        val moduleName = com.mezze.language.cli.`package`.packageNameToModule(name)
        packagesDir.listFiles { f -> f.name.startsWith("$moduleName-") }?.forEach { stale ->
            val p = stale.toPath()
            if (java.nio.file.Files.isSymbolicLink(p)) java.nio.file.Files.delete(p) else stale.deleteRecursively()
        }
        java.nio.file.Files.createSymbolicLink(File(packagesDir, "$moduleName-$version").toPath(), target.toPath())
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
     * Walk the resolved graph (root + every node) for `[jars]` entries,
     * fetch each unique sha via [JarFetcher], and return the deduped
     * list of locked jars for the Mezze.lock snapshot. Fetch failures
     * push into [failures] so the top-level sync exits non-zero and CI
     * catches the drift.
     *
     * A jar declared under different names in different packages
     * collapses to one lock entry keyed on sha256; the winning `name`
     * / `declaredBy` come from whichever tomlkey the walk hit first.
     */
    private fun fetchJarsForGraph(
        projectRoot: File,
        rootToml: MezzeToml,
        graph: List<LockedNode>,
        storeRoot: File,
        isClean: Boolean,
        failures: MutableList<String>,
    ): List<LockedJar> {
        // (sha256 → first-seen task) collected in stable graph order so
        // the same input graph always produces the same lock output —
        // clean diffs.
        val tasks = LinkedHashMap<String, JarFetcher.Task>()

        fun addFromToml(toml: MezzeToml, declaredBy: String) {
            for ((name, spec) in toml.jars) {
                val sha = spec.substringAfter("#sha256:", missingDelimiterValue = "").lowercase()
                if (sha.length != 64 || !sha.matches(Regex("[0-9a-f]{64}"))) {
                    // The validator already caught malformed specs; this
                    // is defence in depth (a corrupt dep manifest that
                    // slipped through validation shouldn't crash sync).
                    failures.add("[jars].$name")
                    println("  ${Glyphs.ERROR} [jars].$name: bad #sha256 in '$spec'")
                    continue
                }
                val url = spec.substringBefore("#sha256:")
                if (tasks.putIfAbsent(sha, JarFetcher.Task(name, spec, url, sha, declaredBy)) != null) {
                    // Duplicate sha under a different name — diamond
                    // dedup handles it; keep the first winner's label
                    // (stable across syncs).
                }
            }
        }

        // Root project first, then every non-root node in graph order.
        addFromToml(rootToml, rootToml.`package`.name)
        for (node in graph) {
            if (node.source == "root") continue
            val depToml = tomlForNode(projectRoot, node, storeRoot) ?: continue
            addFromToml(depToml, node.name)
        }

        if (tasks.isEmpty()) return emptyList()

        val fetcher = JarFetcher(storeRoot)
        val outcomes = fetcher.fetchAll(tasks.values.toList(), isClean = isClean)

        // Report + fail-collect. Group by sha so a single failed sha
        // (declared under several names) surfaces as one line, not N.
        val bySha = outcomes.groupBy { when (it) { is JarFetcher.Outcome.Ok -> it.task.sha256; is JarFetcher.Outcome.Failed -> it.task.sha256 } }
        var newlyFetched = 0
        for ((_, group) in bySha) {
            val first = group.first()
            when (first) {
                is JarFetcher.Outcome.Ok -> if (!first.cached) newlyFetched++
                is JarFetcher.Outcome.Failed -> {
                    val t = first.task
                    println("  ${Glyphs.ERROR} [jars].${t.name}: ${first.reason}")
                    failures.add("[jars].${t.name}")
                }
            }
        }
        if (newlyFetched > 0) {
            println("  ${Glyphs.DOWNLOAD} fetched $newlyFetched jar${if (newlyFetched == 1) "" else "s"} into $storeRoot/jars/")
        }

        // Only the successful outcomes make it into the lock — a jar
        // that failed to fetch is not "pinned" (the drift check would
        // otherwise mask the failure).
        return outcomes.mapNotNull { o -> (o as? JarFetcher.Outcome.Ok)?.task }
            .distinctBy { it.sha256 }
            .sortedBy { it.sha256 }
            .map { t -> LockedJar(name = t.name, url = t.spec, sha256 = t.sha256, declaredBy = t.declaredBy) }
    }

    /**
     * Walk the resolved graph (root + every dep) for `[maven]` +
     * `[[maven-repo]]` entries, resolve them all through Aether into
     * `~/.mezze/cache/maven/`, and return the deduped list of locked
     * maven artifacts for the Mezze.lock snapshot.
     *
     * Merge rules:
     *   - Coords: same `group:artifact` under different versions ⇒
     *     Aether picks nearest-wins on the union; a warning line is
     *     printed so the user sees the collision.
     *   - Repos: default `central` is always first; user + package
     *     entries append (Central-shadowing is a footgun, so we
     *     forbid an `id = "central"` collision). Deduped on `id` in
     *     graph order — first-declarer wins.
     *
     * A resolve failure surfaces via [failures] so `mez sync` exits
     * non-zero and CI catches the drift. Partial success is
     * intentionally not supported — a missing jar breaks the wire
     * child later, and we want the error at sync time.
     */
    private fun resolveMavenForGraph(
        projectRoot: File,
        rootToml: MezzeToml,
        graph: List<LockedNode>,
        storeRoot: File,
        mavenCacheOverride: File?,
        failures: MutableList<String>,
    ): List<LockedMaven> {
        // `group:artifact` -> (version, declaredBy). LinkedHashMap for
        // stable insertion order so a re-sync produces the same lock.
        data class CoordEntry(val version: String, val declaredBy: String)
        val coords = LinkedHashMap<String, CoordEntry>()
        val repos = LinkedHashMap<String, MavenResolver.MavenRemote>() // id -> remote

        fun addFromToml(toml: MezzeToml, declaredBy: String) {
            for ((key, version) in toml.mavenNormalized) {
                val prev = coords[key]
                if (prev == null) {
                    coords[key] = CoordEntry(version, declaredBy)
                } else if (prev.version != version) {
                    // Version collision — let Aether's nearest-wins
                    // decide, but log it so the drift is visible.
                    println("  ${Glyphs.WARN} [maven].$key: declared with both ${prev.version} (${prev.declaredBy}) and $version ($declaredBy) — Aether will pick one")
                }
            }
            for (r in toml.mavenRepos) {
                if (r.id == "central") {
                    println("  ${Glyphs.WARN} [[maven-repo]] id='central' from $declaredBy shadows Maven Central — skipping")
                    continue
                }
                if (!repos.containsKey(r.id)) {
                    repos[r.id] = MavenResolver.MavenRemote(id = r.id, url = r.url)
                }
            }
        }

        addFromToml(rootToml, rootToml.`package`.name)
        for (node in graph) {
            if (node.source == "root") continue
            val depToml = tomlForNode(projectRoot, node, storeRoot) ?: continue
            addFromToml(depToml, node.name)
        }

        if (coords.isEmpty()) return emptyList()

        // Cache is under MezzeHome ("~/.mezze/cache/maven/"), NOT
        // MEZZE_STORE_DIR — the store is for content-addressed dep
        // archives keyed on sha256; maven artifacts live in a
        // conventional maven-layout dir shared across projects. Tests
        // pass `mavenCacheOverride` to keep runs hermetic.
        val cacheDir = (mavenCacheOverride ?: File(com.mezze.language.cli.MezzeHome.home, "cache/maven")).apply { mkdirs() }

        val coordList = coords.entries.map { (ga, entry) -> "$ga:${entry.version}" }
        val declaredByCoord = coords.entries.associate { (ga, entry) -> "$ga:${entry.version}" to entry.declaredBy }

        println("  ${Glyphs.DOWNLOAD} resolving ${coordList.size} maven coord(s) via Aether → ${cacheDir.absolutePath}")
        val resolved = try {
            MavenResolver.resolve(coordList, cacheDir.toPath(), repos.values.toList())
        } catch (e: MavenResolver.MavenResolverException) {
            println("  ${Glyphs.ERROR} ${e.message}")
            failures.add("[maven]")
            return emptyList()
        }

        // Compute the `resolved_file_path` relative to the cache dir
        // (portable across machines: maven writes the same layout
        // everywhere). `transitive_from` names the direct-declared
        // coord that pulled each artifact in — self-reference when the
        // coord was declared directly.
        val directCoords = coordList.toSet()
        val cacheAbsolute = cacheDir.absolutePath
        return resolved.map { r ->
            val abs = r.file.toFile().absolutePath
            val rel = if (abs.startsWith(cacheAbsolute + File.separator)) {
                abs.substring(cacheAbsolute.length + 1)
            } else {
                abs
            }
            val direct = if (r.coord in directCoords) r.coord else directCoords.firstOrNull() ?: r.coord
            LockedMaven(
                coord = r.coord,
                resolvedFilePath = rel,
                sha256 = r.sha256,
                transitiveFrom = direct,
                declaredBy = declaredByCoord[r.coord] ?: declaredByCoord[direct] ?: rootToml.`package`.name,
            )
        }.distinctBy { it.coord }.sortedBy { it.coord }
    }

    /**
     * Locate the on-disk `Mezze.toml` for a resolved graph node. Path
     * deps land at `node.path`; content-addressed deps (registry /
     * github) live under `<storeRoot>/<sha>/`. Github deps use the
     * commit sha the ref resolved to; that value's also what
     * [ResolvedJarsClasspath] uses, so the two views stay in sync.
     */
    private fun tomlForNode(projectRoot: File, node: LockedNode, storeRoot: File): MezzeToml? {
        val tomlFile: File? = when (node.source) {
            "path" -> node.path?.let { File(File(it), "Mezze.toml") }?.takeIf { it.isFile }
            "registry" -> {
                val checksum = node.checksum?.removePrefix("sha256:") ?: return null
                File(storeRoot, "$checksum/Mezze.toml").takeIf { it.isFile }
            }
            "github" -> {
                // ResolvedJarsClasspath keys github store entries on the
                // resolved commit sha, so re-encoding that sha via
                // `sha256(commit)` here would find a stale path. The
                // GithubDepFetcher writes the store dir under
                // `sha256(commit)` — the `checksum` field on the
                // LockedNode already carries it.
                val checksum = node.checksum?.removePrefix("sha256:")
                if (checksum != null) File(storeRoot, "$checksum/Mezze.toml").takeIf { it.isFile }
                else node.ref?.let { r ->
                    val m = com.mezze.language.cli.`package`.packageNameToModule(node.name)
                    File(projectRoot, ".deps/mezze/$m-$r/Mezze.toml").takeIf { it.isFile }
                }
            }
            else -> null
        }
        return tomlFile?.let { readToml(it) }
    }

    /**
     * Execute every `[polyglot.provider].install` template that
     * matches a `[polyglot.<lang>].packages` list in the user's
     * manifest. One provider per language; a graph that carries two
     * providers claiming the same language is a hard error surfaced
     * before either runs, so the user never sees partial state.
     *
     *   - `{prefix}` = the provider package's on-disk location
     *     (`~/.mezze/store/<sha>/` for content-addressed deps, the
     *     absolute path for a path-dep). This is the same directory
     *     `.deps/mezze/<name>-<version>` symlinks to.
     *   - `{dest}`   = `<project_root>/<runtime_dir>`, the absolute
     *     path package files land under. Created if missing.
     *   - `{packages}` = the user's `packages` list, space-joined.
     *
     * A cache marker at `<dest>/.mez-installed.json` records the
     * provider's identity (path / commit / checksum) + the resolved
     * package list; a rerun with the same inputs is skipped. Any
     * change to those inputs recomputes.
     *
     * On non-zero exit code the sync fails loudly with the last 20
     * lines of stderr and the command that ran — the whole point of
     * the template is that install failures shouldn't be silent.
     */
    private fun runProviderInstalls(
        projectRoot: File,
        rootToml: MezzeToml,
        graph: List<LockedNode>,
        storeRoot: File,
        isLocked: Boolean,
        failures: MutableList<String>,
    ) {
        // Discover every provider on the graph, keyed by language. A
        // fresh miss is fine (no packages of that language declared);
        // two providers for the same language is a hard error either
        // way, so we surface it up-front.
        val providers = mutableMapOf<String, ProviderRef>()
        val duplicates = mutableSetOf<String>()
        for (node in graph) {
            if (node.source == "root") continue
            val depToml = tomlForNode(projectRoot, node, storeRoot) ?: continue
            val p = depToml.polyglot?.provider ?: continue
            val lang = p.language ?: continue
            val install = p.install ?: continue
            val env = p.runtimeEnv ?: continue
            val dir = p.runtimeDir ?: continue
            val prefix = providerPrefix(projectRoot, node, storeRoot) ?: continue
            val existing = providers[lang]
            if (existing != null) {
                duplicates += lang
                println("  ${Glyphs.ERROR} [polyglot.$lang]: multiple providers claim language '$lang' — ${existing.declaringNodeName} and ${node.name}")
                continue
            }
            providers[lang] = ProviderRef(
                declaringNodeName = node.name,
                declaringNodeCommit = node.commit,
                declaringNodeChecksum = node.checksum,
                declaringNodePath = node.path,
                prefix = prefix,
                install = install,
                runtimeEnv = env,
                runtimeDir = dir,
            )
        }
        if (duplicates.isNotEmpty()) {
            failures.addAll(duplicates.map { "[polyglot.$it]" })
            return
        }

        // Only languages with a user-declared, non-empty `packages` list get invoked.
        val declared = collectDeclaredPackages(rootToml)
        if (declared.isEmpty()) return

        for ((lang, packages) in declared) {
            val prov = providers[lang]
            if (prov == null) {
                // Cross-check in the validator already surfaces this, but
                // guard again in case a manifest slipped through with a
                // custom-named provider dep that resolves at runtime.
                println("  ${Glyphs.WARN}  [polyglot.$lang].packages declared but no provider on graph — skipping install")
                continue
            }

            val destDir = File(projectRoot, prov.runtimeDir).absoluteFile
            val identity = providerIdentity(prov)
            val marker = File(destDir, ".mez-installed.json")

            if (marker.isFile) {
                val prior = try { readInstalledMarker(marker) } catch (_: Exception) { null }
                if (prior != null && prior.provider == identity && prior.packages == packages && prior.language == lang) {
                    println("  ${Glyphs.OK} [polyglot.$lang] install cached (${packages.size} package${if (packages.size == 1) "" else "s"}) — ${destDir.absolutePath}")
                    continue
                }
            }

            if (isLocked) {
                // --locked promises no writes to the project tree; a
                // stale/missing install is drift.
                println("${Glyphs.ERROR} --locked: [polyglot.$lang] would run install template, but --locked forbids side effects.")
                failures.add("[polyglot.$lang]")
                continue
            }

            destDir.mkdirs()
            // Each package specifier is single-quoted before being
            // spliced in — otherwise `numpy>=1.24.0` looks like output
            // redirection to `sh -c`, and pip only sees "numpy".
            // {prefix}/{dest} are already absolute paths; quote them
            // too so a project living under a path with a space stays
            // valid.
            val substituted = prov.install
                .replace("{prefix}", shellQuote(prov.prefix.absolutePath))
                .replace("{dest}", shellQuote(destDir.absolutePath))
                .replace("{packages}", packages.joinToString(" ") { shellQuote(it) })
            println("  ${Glyphs.GEAR} [polyglot.$lang] $substituted")

            val execResult = try {
                executeShell(substituted, workDir = projectRoot)
            } catch (e: Exception) {
                println("${Glyphs.ERROR} mez sync: provider install failed for [polyglot.$lang]: ${e.message}")
                failures.add("[polyglot.$lang]")
                continue
            }
            if (execResult.exitCode != 0) {
                val tail = execResult.stderrTail(20)
                println("${Glyphs.ERROR} mez sync: provider install failed for [polyglot.$lang] (exit ${execResult.exitCode}): $tail")
                failures.add("[polyglot.$lang]")
                continue
            }

            writeInstalledMarker(
                marker,
                InstalledMarker(language = lang, provider = identity, packages = packages),
            )
            println("  ${Glyphs.OK} [polyglot.$lang] installed ${packages.size} package${if (packages.size == 1) "" else "s"} → ${destDir.absolutePath}")
        }
    }

    /** Providers keyed on manifest language — enough to run one install. */
    private data class ProviderRef(
        val declaringNodeName: String,
        val declaringNodeCommit: String?,
        val declaringNodeChecksum: String?,
        val declaringNodePath: String?,
        val prefix: File,
        val install: String,
        val runtimeEnv: String,
        val runtimeDir: String,
    )

    /** Stable identity for the cache marker — commit / checksum / path in preference order. */
    private fun providerIdentity(p: ProviderRef): String =
        p.declaringNodeCommit?.let { "${p.declaringNodeName}@$it" }
            ?: p.declaringNodeChecksum?.let { "${p.declaringNodeName}#$it" }
            ?: p.declaringNodePath?.let { "${p.declaringNodeName}:$it" }
            ?: p.declaringNodeName

    /** Locate a provider's on-disk directory the same way jar-collection does. */
    private fun providerPrefix(projectRoot: File, node: LockedNode, storeRoot: File): File? {
        return when (node.source) {
            "path" -> node.path?.let { File(it).canonicalFile }?.takeIf { it.isDirectory }
            "registry" -> {
                val checksum = node.checksum?.removePrefix("sha256:") ?: return null
                File(storeRoot, checksum).takeIf { it.isDirectory }
            }
            "github" -> {
                val checksum = node.checksum?.removePrefix("sha256:")
                if (checksum != null) File(storeRoot, checksum).takeIf { it.isDirectory }
                else node.ref?.let { r ->
                    val m = com.mezze.language.cli.`package`.packageNameToModule(node.name)
                    File(projectRoot, ".deps/mezze/$m-$r").takeIf { it.isDirectory }
                }
            }
            else -> null
        }
    }

    /** User's `[polyglot.<lang>].packages` map — only non-empty entries. */
    private fun collectDeclaredPackages(toml: MezzeToml): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        toml.polyglot?.python?.packages?.takeIf { it.isNotEmpty() }?.let { out["python"] = it }
        toml.polyglot?.js?.packages?.takeIf { it.isNotEmpty() }?.let { out["js"] = it }
        toml.polyglot?.wasm?.packages?.takeIf { it.isNotEmpty() }?.let { out["wasm"] = it }
        toml.polyglot?.jvm?.packages?.takeIf { it.isNotEmpty() }?.let { out["jvm"] = it }
        return out
    }

    /** One process invocation — stdout streams to the parent, stderr is buffered so we can tail it on failure. */
    private data class ExecResult(val exitCode: Int, val stderr: String) {
        fun stderrTail(n: Int): String {
            val lines = stderr.lines()
            val slice = if (lines.size <= n) lines else lines.takeLast(n)
            return slice.joinToString("\n").ifBlank { "(no stderr)" }
        }
    }

    /**
     * Wrap a string as a single-quoted Bourne shell argument, escaping
     * embedded single quotes with the `'\''` trick. `sh -c` is our
     * install-template driver, and pip specifiers like `numpy>=1.24.0`
     * carry redirection metacharacters that must survive re-parsing.
     */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    private fun executeShell(command: String, workDir: File): ExecResult {
        val pb = ProcessBuilder("sh", "-c", command)
            .directory(workDir)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectErrorStream(false)
        val proc = pb.start()
        // Capture stderr for the failure-tail; let stdout flow through
        // so users see progress from long-running installs.
        val errBuf = StringBuilder()
        val errThread = Thread {
            proc.errorStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    System.err.println(line)
                    synchronized(errBuf) { errBuf.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        val code = proc.waitFor()
        errThread.join(1_000)
        return ExecResult(code, synchronized(errBuf) { errBuf.toString() })
    }

    /** Marker file schema at `<dest>/.mez-installed.json`. */
    @kotlinx.serialization.Serializable
    private data class InstalledMarker(
        val language: String,
        val provider: String,
        val packages: List<String>,
    )

    private fun readInstalledMarker(f: File): InstalledMarker =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(InstalledMarker.serializer(), f.readText())

    private fun writeInstalledMarker(f: File, m: InstalledMarker) {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        f.parentFile.mkdirs()
        f.writeText(json.encodeToString(InstalledMarker.serializer(), m) + "\n")
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
