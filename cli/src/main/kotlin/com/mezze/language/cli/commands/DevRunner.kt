package com.mezze.language.cli.commands

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * JVM entry point for `mez dev` execution.
 *
 * Default: watch mode — compile, store in cache.sqlite, execute, watch, repeat.
 * Same compile/store/execute path as the native binary. JVM stays alive.
 * Truffle Engine reused (JIT stays warm). ~100ms hot reload.
 */
object DevRunner {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val runCounter = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastDelta: com.mezze.language.codebase.PopulateDelta? = null
    private const val SEPARATOR = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    private const val ANSI_YELLOW = "[33m"
    private const val ANSI_CYAN = "[36m"
    private const val ANSI_BOLD = "[1m"
    private const val ANSI_CLEAR = "[2J[H"  // clear screen + cursor home

    /** TTY-aware: don't emit ANSI escapes if stderr isn't a terminal (e.g. piped to a file). */
    private val isTty: Boolean by lazy { System.console() != null }
    private val noClear: Boolean by lazy { System.getenv("MEZZE_NO_CLEAR") != null || !isTty }
    private const val ANSI_RED = "\u001b[31m"
    private const val ANSI_GREEN = "\u001b[32m"
    private const val ANSI_GRAY = "\u001b[90m"
    private const val ANSI_RESET = "\u001b[0m"

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)

        val projectRoot = File(parsed["project-root"] ?: ".")
        val entryFile = parsed["entry-file"] ?: "src/app.mz"
        val entryName = parsed["entry-name"] ?: "main"
        val modulePath = parsed["module-path"] ?: "app"
        val appName = parsed["app-name"] ?: modulePath
        val inspect = parsed.containsKey("inspect")
        val inspectBrk = parsed.containsKey("inspect-brk")
        val noWatch = parsed.containsKey("no-watch")

        // Profiling / diagnostics flags. Each maps to a polyglot Engine
        // option below. After P0 (#173), all of these produce useful output;
        // statement-level granularity awaits #187 (per-node @GenerateWrapper).
        val profile = parsed.containsKey("profile")
        val coverage = parsed.containsKey("coverage")
        val traceJit = parsed.containsKey("trace-jit")
        val insightFile = parsed["insight"]

        System.setProperty("mezze.project.root", projectRoot.absolutePath)
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Open project store — stays open for the lifetime of the process
        val store = SQLiteMezzeStore.forProject(projectRoot)

        // Load stdlib into store if not already
        ensureStdlibLoaded(store)

        // Hydrate any cached typed-modules whose source on disk still matches.
        // These are skipped during the next typecheck (cache hit in
        // ModuleTypeChecker.globalCache); cold start drops by hundreds of ms.
        hydrateTypedModulesFromStore(store, projectRoot)

        // Shared Truffle Engine — JIT stays warm across runs.
        // Instrument options (cpusampler, coverage, insight, JIT trace) are
        // registered on the Engine so they survive watch reloads — each run
        // appends to the same profile/coverage output.
        val engineBuilder = Engine.newBuilder()
            // Required for any Truffle instrumentation tool — these options
            // are all "experimental" and silently no-op without this.
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")

        if (profile) {
            // CPU sampler. Function-granularity by default (low overhead);
            // use --cpusampler.* options on java -jar for finer settings.
            val out = File(projectRoot, ".mz/profile.txt")
            out.parentFile?.mkdirs()
            engineBuilder
                .option("cpusampler", "true")
                .option("cpusampler.OutputFile", out.absolutePath)
            System.err.println("${ANSI_GRAY}profile → ${out.absolutePath}${ANSI_RESET}")
        }
        if (coverage) {
            val out = File(projectRoot, ".mz/coverage.txt")
            out.parentFile?.mkdirs()
            engineBuilder
                .option("coverage", "true")
                .option("coverage.OutputFile", out.absolutePath)
            System.err.println("${ANSI_GRAY}coverage → ${out.absolutePath}${ANSI_RESET}")
        }
        if (traceJit) {
            // GraalVM 24.x dropped `engine.TraceCompilation` from the polyglot
            // option layer — these flags now live under the Graal compiler
            // namespace and only work as JVM system properties set on the
            // command line, not via Engine.option(). Show the user how:
            System.err.println("${ANSI_GRAY}--trace-jit not yet wired in this Truffle build.${ANSI_RESET}")
            System.err.println("${ANSI_GRAY}For JIT diagnostics, run with these JVM flags:${ANSI_RESET}")
            System.err.println("${ANSI_GRAY}  java -Dgraal.TraceTruffleCompilation=true \\${ANSI_RESET}")
            System.err.println("${ANSI_GRAY}       -Dgraal.TraceTruffleTransferToInterpreter=true \\${ANSI_RESET}")
            System.err.println("${ANSI_GRAY}       -jar mezze.jar dev --nw${ANSI_RESET}")
        }
        if (insightFile != null) {
            val f = File(insightFile)
            if (!f.exists()) {
                System.err.println("Error: --insight=$insightFile: file not found")
                System.exit(1)
            }
            engineBuilder.option("insight", f.absolutePath)
        }

        val engine = engineBuilder.build()

        if (noWatch) {
            runOnce(store, engine, projectRoot, entryFile, entryName, modulePath, appName, inspect, inspectBrk)
        } else {
            // Watch mode — JVM stays alive, recompiles on file changes.
            runWatchMode(store, engine, projectRoot, entryFile, entryName, modulePath, appName, inspect, inspectBrk)
        }
    }

    /**
     * Compile + execute once, then exit. Used by `mez dev --no-watch` (or
     * `--nw`) for CI smoke tests, scripting, and "I just want to run this
     * and not have my terminal taken over."
     */
    private fun runOnce(
        store: SQLiteMezzeStore, engine: Engine,
        projectRoot: File, entryFile: String, entryName: String, modulePath: String,
        appName: String, inspect: Boolean, inspectBrk: Boolean
    ) {
        val sourceFile = File(projectRoot, entryFile)
        if (!sourceFile.exists()) {
            System.err.println("Error: Source file not found: ${sourceFile.absolutePath}")
            System.exit(1)
        }
        compileStoreExecute(
            store, engine, sourceFile, projectRoot, entryName, modulePath,
            appName, inspect, inspectBrk, ReloadMode.COLD_START
        )
    }

    private fun runWatchMode(
        store: SQLiteMezzeStore, engine: Engine,
        projectRoot: File, entryFile: String, entryName: String, modulePath: String,
        appName: String, inspect: Boolean, inspectBrk: Boolean
    ) {
        val sourceFile = File(projectRoot, entryFile)
        if (!sourceFile.exists()) {
            System.err.println("Error: Source file not found: ${sourceFile.absolutePath}")
            System.exit(1)
        }

        // First run — always execute (use cache if present, else compile).
        printHeader(appName, entryFile)
        compileStoreExecute(store, engine, sourceFile, projectRoot, entryName, modulePath, appName, inspect, inspectBrk, ReloadMode.COLD_START)
        printWatching()

        // Watch only the dirs containing files this app depends on.
        // After every compile, we recompute the set from ModuleResolver and adjust.
        val watcher = FileSystems.getDefault().newWatchService()
        val watchedDirs = HashMap<Path, java.nio.file.WatchKey>()
        syncWatchedDirs(watcher, watchedDirs, sourceFile, projectRoot)

        // One queue feeds both file events and keyboard events into the main loop.
        val events = java.util.concurrent.LinkedBlockingQueue<DevEvent>()
        startFileWatcherThread(watcher, events)
        val keyboardActive = startKeyboardThread(events)

        while (true) {
            when (val event = events.take()) {
                is DevEvent.FilesChanged -> {
                    Thread.sleep(50) // debounce — collect any near-simultaneous events
                    val coalesced = mutableSetOf<String>().apply { addAll(event.files) }
                    while (true) {
                        val next = events.poll() ?: break
                        if (next is DevEvent.FilesChanged) coalesced.addAll(next.files) else { events.add(next); break }
                    }
                    // Evict module-resolver entries for the files that changed.
                    // Without this, the typechecker would re-use the previously-typed
                    // version of an imported module even though its source has new bytes.
                    invalidateChangedModules(coalesced, projectRoot)
                    if (!noClear) System.err.print(ANSI_CLEAR)
                    printReloadBanner(appName, coalesced)
                    compileStoreExecute(store, engine, sourceFile, projectRoot, entryName, modulePath, appName, inspect, inspectBrk, ReloadMode.WATCH_RELOAD)
                    syncWatchedDirs(watcher, watchedDirs, sourceFile, projectRoot)
                    printWatching(keyboardActive)
                }
                DevEvent.Rerun -> {
                    if (!noClear) System.err.print(ANSI_CLEAR)
                    printForceRunBanner(appName)
                    compileStoreExecute(store, engine, sourceFile, projectRoot, entryName, modulePath, appName, inspect, inspectBrk, ReloadMode.FORCE_RUN)
                    syncWatchedDirs(watcher, watchedDirs, sourceFile, projectRoot)
                    printWatching(keyboardActive)
                }
                DevEvent.Clear -> {
                    if (!noClear) System.err.print(ANSI_CLEAR)
                    printHeader(appName, entryFile)
                    printWatching(keyboardActive)
                }
                DevEvent.Help -> {
                    System.err.println()
                    System.err.println("${ANSI_BOLD}keys${ANSI_RESET}")
                    System.err.println("  ${ANSI_BOLD}r${ANSI_RESET}  re-run (force, even with no changes)")
                    System.err.println("  ${ANSI_BOLD}c${ANSI_RESET}  clear screen")
                    System.err.println("  ${ANSI_BOLD}h${ANSI_RESET}  show this help")
                    System.err.println("  ${ANSI_BOLD}q${ANSI_RESET}  quit")
                    System.err.println()
                }
                DevEvent.Quit -> {
                    System.err.println()
                    System.err.println("${ANSI_GRAY}bye${ANSI_RESET}")
                    return
                }
            }
        }
    }

    private sealed class DevEvent {
        data class FilesChanged(val files: Set<String>) : DevEvent()
        object Rerun : DevEvent()
        object Clear : DevEvent()
        object Help : DevEvent()
        object Quit : DevEvent()
    }

    private fun startFileWatcherThread(
        watcher: java.nio.file.WatchService,
        events: java.util.concurrent.BlockingQueue<DevEvent>
    ) {
        Thread {
            while (true) {
                val key = try { watcher.take() } catch (_: InterruptedException) { return@Thread }
                val files = mutableSetOf<String>()
                for (event in key.pollEvents()) {
                    val filename = event.context()?.toString() ?: continue
                    if (filename.endsWith(".mz")) files.add(filename)
                }
                key.reset()
                if (files.isNotEmpty()) events.add(DevEvent.FilesChanged(files))
            }
        }.apply { isDaemon = true; name = "mez-watch-fs" }.start()
    }

    /**
     * Start a thread that reads single keystrokes from stdin in raw mode (no
     * Enter required). Returns true if raw mode was successfully enabled.
     *
     * stty-based; macOS/Linux only. Windows support tracked in #159.
     * If we can't enable raw mode (non-interactive stdin, no stty, etc.),
     * keyboard events are silently disabled — file-watcher events still work.
     */
    private fun startKeyboardThread(events: java.util.concurrent.BlockingQueue<DevEvent>): Boolean {
        if (System.console() == null) return false
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("windows")) return false  // tracked in #159

        // Save current stty settings, switch to raw mode.
        val saved = try {
            ProcessBuilder("stty", "-g")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { return false }
        if (saved.isEmpty()) return false

        try {
            ProcessBuilder("stty", "-icanon", "-echo", "min", "1")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(true)
                .start().waitFor()
        } catch (_: Exception) { return false }

        Runtime.getRuntime().addShutdownHook(Thread {
            try { ProcessBuilder("stty", saved).redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor() } catch (_: Exception) {}
        })

        Thread {
            try {
                while (true) {
                    val ch = System.`in`.read()
                    if (ch < 0) return@Thread
                    when (ch.toChar().lowercaseChar()) {
                        'r' -> events.add(DevEvent.Rerun)
                        'c' -> events.add(DevEvent.Clear)
                        'h', '?' -> events.add(DevEvent.Help)
                        'q' -> { events.add(DevEvent.Quit); return@Thread }
                        '' -> { events.add(DevEvent.Quit); return@Thread } // Ctrl+C as char (rare; signal usually)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "mez-watch-keys" }.start()
        return true
    }

    /**
     * Map changed filenames (basename only — that's all WatchService gives us)
     * to the module paths whose `Module.filePath` ends with that name, then
     * evict those module paths from both resolver caches.
     *
     * Coarse but correct: a "utils.mz" event might match multiple modules in
     * different dirs. Evicting all of them is safe — they'll be re-resolved on
     * next typecheck, and ModuleResolver dedups by content hash.
     */
    private fun invalidateChangedModules(changedFiles: Set<String>, projectRoot: File) {
        val rootPath = projectRoot.toPath().toAbsolutePath().normalize()
        val toEvict = mutableSetOf<String>()
        for ((modulePath, module) in com.mezze.language.typechecker.ModuleResolver.snapshot()) {
            val filePath = module.filePath.takeIf { it.isNotBlank() } ?: continue
            val abs = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize()
            // Only evict modules under the project root (don't touch stdlib).
            if (!abs.startsWith(rootPath)) continue
            val basename = abs.fileName.toString()
            if (basename in changedFiles) toEvict.add(modulePath)
        }
        if (toEvict.isNotEmpty()) {
            com.mezze.language.typechecker.ModuleResolver.invalidateModulePaths(toEvict)
            com.mezze.language.typechecker.module.ModuleTypeChecker.invalidateModulePaths(toEvict)
        }
    }

    /**
     * Compute the set of directories we want to watch — the parent dirs of every
     * project-local file actually loaded by the typechecker. Stdlib is excluded
     * (it lives under the install dir and never changes during a dev session).
     *
     * Mutates [watchedDirs]: registers any new dirs, cancels keys for dirs no
     * longer needed.
     */
    private fun syncWatchedDirs(
        watcher: java.nio.file.WatchService,
        watchedDirs: MutableMap<Path, java.nio.file.WatchKey>,
        sourceFile: File,
        projectRoot: File
    ) {
        val rootPath = projectRoot.toPath().toAbsolutePath().normalize()

        // Always include the entry file's parent directory.
        val wanted = HashSet<Path>()
        wanted.add(sourceFile.parentFile.toPath().toAbsolutePath().normalize())

        // Add parent dirs of every loaded module that lives under the project root.
        for ((_, module) in com.mezze.language.typechecker.ModuleResolver.snapshot()) {
            val filePath = module.filePath.takeIf { it.isNotBlank() } ?: continue
            val abs = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize()
            if (abs.startsWith(rootPath)) {
                wanted.add(abs.parent ?: continue)
            }
        }

        // Register new dirs.
        for (dir in wanted) {
            if (dir !in watchedDirs && java.nio.file.Files.isDirectory(dir)) {
                watchedDirs[dir] = dir.register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                )
            }
        }
        // Cancel ones we no longer want.
        val iter = watchedDirs.entries.iterator()
        while (iter.hasNext()) {
            val (dir, key) = iter.next()
            if (dir !in wanted) {
                key.cancel()
                iter.remove()
            }
        }
    }

    /**
     * Reload outcome: how the watch-mode reload should behave after compile.
     */
    private enum class ReloadMode {
        /** First run on JVM startup — always execute. Use cache if present, else compile. */
        COLD_START,
        /** File changed — recompile, decide on hash delta whether to execute. */
        WATCH_RELOAD,
        /** User asked for `r` — compile + execute regardless of delta. */
        FORCE_RUN,
    }

    /**
     * The unified compile/store/execute path. Watch-mode invariants:
     * - Always parse + typecheck on WATCH_RELOAD (we can't know if anything
     *   changed semantically without typechecking).
     * - The per-definition hash delta decides whether we execute. No semantic
     *   change → skip exec, print "no changes".
     * - On COLD_START we read from cache if it's populated, otherwise compile.
     */
    private fun compileStoreExecute(
        store: SQLiteMezzeStore, engine: Engine,
        sourceFile: File, projectRoot: File, entryName: String, modulePath: String,
        appName: String, inspect: Boolean, inspectBrk: Boolean,
        mode: ReloadMode
    ) {
        val startTime = System.nanoTime()
        val source = sourceFile.readText()
        val sourceHash = sha256(source)
        val filePath = sourceFile.absolutePath
        val timing = System.getenv("MEZZE_TIMING") != null
        fun ms() = (System.nanoTime() - startTime) / 1_000_000

        // COLD_START fast path — if cache is populated AND every module's
        // file on disk still matches its stored sourceHash, use the cached
        // functions and skip the compile.
        //
        // Checking only the entry file isn't enough: a transitive import
        // (e.g. `watchtest::utils`) may have changed while `app.mz` didn't.
        // Walking every row in `modules` is cheap (sub-ms for tens of files).
        if (mode == ReloadMode.COLD_START) {
            val cached = loadFunctions(store, appName)
            if (cached.isNotEmpty() && !anyProjectFileStale(store)) {
                val performers = loadPerformerRecords(store, appName)
                executeAndReport(cached, performers, projectRoot, entryName, inspect, inspectBrk, engine, startTime)
                return
            }
        }

        // Compile — parse + typecheck + lower.
        val parser = com.mezze.language.parser.MezzeParser()
        val ast = try {
            parser.parseParsedModule(source, sourceFile.name)
        } catch (e: com.mezze.language.parser.ParseException) {
            printError("Parse Error", e.message ?: "Unknown parse error")
            return
        }
        if (timing) System.err.println("[${ms()}ms] parsed")

        // Surgically invalidate the entry module's typed cache so the new
        // source is re-typechecked. Stdlib + unchanged dep modules stay cached;
        // if a dep file changed, we evict it on the next sync via the watcher
        // event matching its module path. (Cheap: only evicts on actual change.)
        com.mezze.language.typechecker.ModuleResolver.invalidateModulePaths(listOf(modulePath))
        com.mezze.language.typechecker.module.ModuleTypeChecker.invalidateModulePaths(listOf(modulePath))

        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val result = try {
            typeChecker.typeCheck(ast, projectRoot.toPath())
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            val formatted = try {
                com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source)
            } catch (_: Exception) { e.message ?: "Unknown type error" }
            printError("", formatted)
            return
        }
        if (timing) System.err.println("[${ms()}ms] typechecked + lowered (${result.loweredFunctions.size} fns)")

        if (result.loweredFunctions.isEmpty()) {
            printError("Error", "No functions produced by compiler")
            return
        }

        // Validate the entry function has shape `{} -> {} <*>` BEFORE we try
        // to invoke it. Better error here than a runtime crash later.
        val entryDecl = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == entryName }
        if (entryDecl == null) {
            printError("Error", "App must define a '$entryName' function with type `{} -> {} <*>`")
            return
        }
        val entryCheck = EntryPointCheck.check(entryDecl)
        EntryPointCheck.errorMessage(entryCheck, entryName)?.let { msg ->
            printError("Error", msg)
            return
        }

        // Write to cache.sqlite — delta tells us what actually changed.
        val delta = StorePopulator.populate(store, result, modulePath, filePath, sourceHash)
        store.transaction {
            store.putApp(appName, entryName, modulePath, filePath)
            store.setAppFunctionHashes(appName, result.loweredFunctions.map { it.hash })
            store.setAppPerformerRecords(appName, result.performerRecords)
        }
        if (timing) System.err.println("[${ms()}ms] stored (${delta.newHashes.size} new, ${delta.unchangedHashes.size} skip)")

        // Decide whether to execute.
        // - WATCH_RELOAD with empty delta = file changed but no semantic change
        //   (whitespace, comment, edit to unimported file). Skip execution.
        // - COLD_START / FORCE_RUN always execute.
        if (mode == ReloadMode.WATCH_RELOAD && delta.newHashes.isEmpty()) {
            val elapsed = ms()
            System.err.println()
            System.err.println("${ANSI_GRAY}no changes · ${ANSI_GREEN}${elapsed}ms${ANSI_RESET}${ANSI_GRAY} (press ${ANSI_BOLD}r${ANSI_RESET}${ANSI_GRAY} to re-run)${ANSI_RESET}")
            lastDelta = delta
            return
        }

        // Hand the changed-hash set to MezzeLanguage so its CallTarget cache
        // evicts only changed hashes (#152). Empty set = no eviction.
        MezzeLanguage.setHashesToInvalidate(delta.newHashes.map { it.hex })
        lastDelta = delta

        executeAndReport(result.loweredFunctions, result.performerRecords,
            projectRoot, entryName, inspect, inspectBrk, engine, startTime)
    }

    /**
     * Pre-load typed modules from the store so the typechecker skips
     * re-typecheck for files whose source on disk hasn't changed.
     *
     * Delegates the walk to [com.mezze.language.codebase.StoreHydration];
     * passes a lambda that deserializes the blob and pushes it into
     * ModuleTypeChecker.globalCache.
     */
    private fun hydrateTypedModulesFromStore(store: SQLiteMezzeStore, projectRoot: File) {
        val stats = com.mezze.language.codebase.StoreHydration.hydrateTypedModules(store) { modulePath, blob ->
            try {
                val v2Module = CodebaseSerialization.deserializeTypedModule(blob)
                com.mezze.language.typechecker.module.ModuleTypeChecker
                    .hydrateFromV2Module(modulePath, v2Module)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (System.getenv("MEZZE_TIMING") != null) {
            System.err.println("[${stats.elapsedMs}ms] hydrated typed modules: ${stats.hits} hit, ${stats.misses} miss")
        }
    }

    private fun ensureStdlibLoaded(store: SQLiteMezzeStore) {
        if (store.getMetadata("stdlib_loaded") != null) return
        try {
            val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
            store.transaction {
                for ((hash, fnIR) in precompiled.preloweredFunctions) {
                    val irBytes = CodebaseSerialization.serializeFunctionIR(fnIR)
                    store.putBlob(Hash(hash),
                        com.mezze.language.codebase.BlobKind.FUNCTION,
                        com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                }
                store.setMetadata("stdlib_loaded", "true")
            }
        } catch (e: Exception) {
            // Non-fatal: stdlib will fall back to PrecompiledStdlib at runtime.
            // But surface the cause so cache.sqlite issues don't hide.
            System.err.println("${ANSI_RED}warning:${ANSI_RESET} failed to seed stdlib into cache.sqlite: ${e.message}")
        }
    }

    private fun executeAndReport(
        functions: List<FunctionIR>, performers: List<Array<String>>,
        projectRoot: File, entryName: String, inspect: Boolean, inspectBrk: Boolean,
        engine: Engine, startTime: Long
    ) {
        try {
            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            printError("Runtime Error", e.message ?: "Unknown error")
            return
        } catch (e: Exception) {
            printError("Error", e.message ?: "Unknown error")
            return
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        val color = if (elapsed < 200) ANSI_GREEN else ANSI_YELLOW
        System.err.println()
        System.err.println("${ANSI_GRAY}ready in ${color}${elapsed}ms${ANSI_RESET}")
    }

    private fun executeWithTruffle(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        projectRoot: java.nio.file.Path,
        entryName: String,
        inspect: Boolean,
        inspectBrk: Boolean,
        engine: Engine
    ) {
        val builder = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            // Required for any of Truffle's instrumentation tools — inspect,
            // cpusampler, coverage, memtracer, insight, engine.Trace*. Without
            // this, every option flag silently no-ops.
            .allowExperimentalOptions(true)

        if (inspect || inspectBrk) {
            builder.option("inspect", "true")
            if (inspectBrk) builder.option("inspect.Suspend", "true")
        }

        val context = builder.build()
        try {
            MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryName)
            val runId = runCounter.incrementAndGet()
            val source = Source.newBuilder(MezzeLanguage.ID, "# DevRunner $runId", "__dev_runner_${runId}__.mz").buildLiteral()
            context.eval(source)
        } finally {
            context.close(true)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun loadFunctions(store: SQLiteMezzeStore, appName: String): List<FunctionIR> {
        return store.getAppFunctionHashes(appName).mapNotNull { hash ->
            val irBytes = store.getIR(hash) ?: return@mapNotNull null
            CodebaseSerialization.deserializeFunctionIR(irBytes)
        }
    }

    private fun loadPerformerRecords(store: SQLiteMezzeStore, appName: String): List<Array<String>> =
        store.getAppPerformerRecords(appName)

    /**
     * True if any module recorded in the store has a source file on disk
     * whose SHA-256 differs from the stored sourceHash. Returns true also
     * if a recorded source file is missing (treat as stale → recompile).
     */
    private fun anyProjectFileStale(store: SQLiteMezzeStore): Boolean {
        for (mod in store.getAllModules()) {
            val filePath = mod.filePath ?: continue
            val file = File(filePath)
            if (!file.exists()) return true
            if (sha256(file.readText()) != mod.sourceHash) return true
        }
        return false
    }

    private fun printSeparator() { System.err.println(ANSI_GRAY + SEPARATOR + ANSI_RESET) }
    private fun printError(label: String, msg: String) { System.err.println(); if (label.isNotEmpty()) System.err.println("${ANSI_RED}$label${ANSI_RESET}"); System.err.println(msg) }
    private fun printWatching(keyboardActive: Boolean = false) {
        System.err.println()
        if (keyboardActive) {
            System.err.println("${ANSI_GRAY}watching · press ${ANSI_BOLD}r${ANSI_RESET}${ANSI_GRAY} to re-run, ${ANSI_BOLD}h${ANSI_RESET}${ANSI_GRAY} for help, ${ANSI_BOLD}q${ANSI_RESET}${ANSI_GRAY} to quit${ANSI_RESET}")
        } else {
            System.err.println("${ANSI_GRAY}watching for changes — press Ctrl+C to stop${ANSI_RESET}")
        }
    }

    private fun printHeader(appName: String, entryFile: String) {
        if (!noClear) System.err.print(ANSI_CLEAR)
        System.err.println("${ANSI_BOLD}${ANSI_CYAN}mez dev${ANSI_RESET}${ANSI_GRAY} · $appName${ANSI_RESET}")
        System.err.println("${ANSI_GRAY}$entryFile${ANSI_RESET}")
        System.err.println()
    }

    private fun printReloadBanner(appName: String, changedFiles: Set<String>) {
        val timestamp = LocalTime.now().format(TIME_FMT)
        val files = changedFiles.joinToString(", ")
        System.err.println("${ANSI_BOLD}${ANSI_CYAN}mez dev${ANSI_RESET}${ANSI_GRAY} · $appName · ${ANSI_RESET}${ANSI_YELLOW}reload${ANSI_RESET}${ANSI_GRAY} · [$timestamp] $files${ANSI_RESET}")
        System.err.println()
    }

    private fun printForceRunBanner(appName: String) {
        val timestamp = LocalTime.now().format(TIME_FMT)
        System.err.println("${ANSI_BOLD}${ANSI_CYAN}mez dev${ANSI_RESET}${ANSI_GRAY} · $appName · ${ANSI_RESET}${ANSI_CYAN}re-run${ANSI_RESET}${ANSI_GRAY} · [$timestamp]${ANSI_RESET}")
        System.err.println()
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (arg in args) {
            when {
                arg.startsWith("--") && "=" in arg -> {
                    val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                    result[key] = value
                }
                arg.startsWith("--") -> result[arg.removePrefix("--")] = ""
            }
        }
        return result
    }
}
