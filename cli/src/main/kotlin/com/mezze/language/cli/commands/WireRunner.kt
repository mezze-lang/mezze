package com.mezze.language.cli.commands

import com.mezze.language.langservice.printing.ParseErrorFormatter
import com.mezze.wire.Bye
import com.mezze.wire.Evict
import com.mezze.wire.Exited
import com.mezze.wire.Hello
import com.mezze.wire.Interrupt
import com.mezze.wire.Load
import com.mezze.wire.Loaded
import com.mezze.wire.MessageType
import com.mezze.wire.ProtocolError
import com.mezze.wire.Quit
import com.mezze.wire.Reject
import com.mezze.wire.Started
import com.mezze.wire.StderrData
import com.mezze.wire.StdoutData
import com.mezze.wire.Welcome
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireProtocolException
import com.mezze.language.cli.JvmLauncher
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.io.OutputStream

/**
 * Parent-side client of the Mezze wire protocol v1. See
 * `docs/wire-protocol/00-design.md` for the design.
 *
 * Flow:
 *   1. native mez parses + typechecks + lowers the user app.
 *   2. Serializes the closure + meta to CBOR files under `.mez/run/`.
 *   3. Spawns a JVM child running [com.mezze.interpreter.standalone.RuntimeServer].
 *   4. HELLO/WELCOME handshake → caps negotiated.
 *   5. Sends LOAD (and EVICT for hot-reload); streams STDOUT_DATA /
 *      STDERR_DATA frames back to the user's terminal; waits for EXITED.
 *   6. On exit (one-shot) or Ctrl-C (watch): sends QUIT, waits for BYE.
 *
 * Exit code propagation: [Exited.code] becomes the parent's exit code
 * for one-shot runs, surfaced as a status line in watch mode.
 *
 * Ctrl-C handling: parent installs a SIGINT handler that sends
 * INTERRUPT to the child; the runtime cancels the active run cleanly,
 * sends EXITED with reason="interrupted".
 */
object WireRunner {

    /** Capabilities the parent claims. Negotiated with server's set. */
    internal val ADVERTISED_CAPABILITIES = listOf(
        WireProtocol.Capability.CORE,
        WireProtocol.Capability.EXIT_CODE,
        WireProtocol.Capability.INTERRUPT,
        WireProtocol.Capability.STRUCTURED_ERRORS,
        WireProtocol.Capability.STDIN,
        WireProtocol.Capability.STDERR_FRAMES,
    )

    /** Per-compile output we need to ship to RuntimeServer. */
    data class CompileResult(
        val fnsFile: File,
        val metaFile: File,
        val hashes: Set<Hash>,
    )

    /**
     * Compile + execute once via RuntimeServer. Returns process exit code.
     * Equivalent to the `--no-watch` path of `mez dev` / `mez exec`.
     *
     * [devFlags] are pass-through CLI tokens (e.g. "--inspect",
     * "--profile", "--insight=/abs/probe.js") that the child runtime
     * applies to its Truffle Context/Engine at startup. See
     * [RuntimeServer]'s DevFlags helper for the accepted set.
     */
    fun execOnce(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
        devFlags: List<String> = emptyList(),
        appArgs: List<String> = emptyList(),
    ): Int {
        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        if (!appFile.exists()) {
            System.err.println("Error: App file not found: ${appFile.absolutePath}")
            return 1
        }

        initStore(currentDir)
        val compile = compile(currentDir, appFile, entryName, appName, timing, t0) ?: return 4

        val session = WireSession.start(currentDir, timing, t0, devFlags) ?: return 1
        return try {
            val outcome = session.runOnce(compile, appArgs = appArgs)
            outcome.exitCode
        } finally {
            session.close()
        }
    }

    /**
     * Watch mode. Long-lived RuntimeServer; per-save: recompile, EVICT
     * changed hashes, LOAD new closure. The JVM Engine + Context stay
     * warm so Truffle's per-context caches survive (saves ~30–50 ms
     * per reload).
     *
     * Behavior:
     *
     *   - Tracks every `.mz` file the typechecker actually loaded
     *     (via ModuleResolver.snapshot) and watches their parent dirs.
     *     Re-syncs after every successful compile so newly-imported
     *     modules start being watched. Edits to deeply-imported files
     *     trigger reload, same as the entry file.
     *
     *   - Coalesces fs events (macOS fires multiple per save) and drains
     *     duplicates after compile to avoid spurious extra reloads.
     *
     *   - No keyboard shortcuts: stdin belongs entirely to the user
     *     program (see `std::effects::stdin`). Reload happens on file
     *     save; Ctrl-C stops the watch.
     */
    fun watch(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
        devFlags: List<String> = emptyList(),
    ): Int {
        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()

        if (!appFile.exists()) {
            System.err.println("Error: App file not found: ${appFile.absolutePath}")
            return 1
        }
        initStore(currentDir)

        var session = WireSession.start(currentDir, timing, t0, devFlags) ?: return 1
        var prevHashes: Set<Hash> = emptySet()

        // Ctrl-C in watch mode quits the whole session. We disable
        // native-image's own exit handlers so SigintForwarder owns SIGINT;
        // its quit hook trips this flag and interrupts THIS (watch) thread so
        // the blocking `events.take()` throws and the loop returns cleanly.
        val watchThread = Thread.currentThread()
        val quitRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        fun installQuitHook() = session.onQuit {
            quitRequested.set(true)
            watchThread.interrupt()
        }
        installQuitHook()

        /** Rebuild the session after wire corruption — keeps the watch
         *  loop alive across framing errors. Returns false if rebuild
         *  fails (subprocess won't start, etc); caller should bail. */
        fun restartSession(): Boolean {
            try { session.close() } catch (_: Exception) {}
            val fresh = WireSession.start(currentDir, timing, System.nanoTime(), devFlags)
                ?: return false
            session = fresh
            prevHashes = emptySet()  // child has no cached state anymore
            installQuitHook()        // new session → re-arm the quit hook
            return true
        }

        // TTY detection: colour only when stderr is an interactive terminal.
        val console = DevConsole(appName, color = System.console() != null)
        val watchedDirLabel = appFile.parentFile?.let {
            runCatching { it.toPath().let(currentDir.toPath()::relativize).toString() }.getOrNull()
        } ?: appFile.parent ?: "."

        try {
            val tFirst = System.nanoTime()
            var lastCompile: CompileResult? = compile(currentDir, appFile, entryName, appName, timing, t0)
            val compileSecs = (System.nanoTime() - tFirst) / 1e9
            for (line in console.banner(watchedDirLabel, compileSecs)) System.err.println(line)

            // The first run's separator precedes its output.
            System.err.println(console.separator("running · ^C to stop"))
            System.err.println(console.blank())
            if (lastCompile != null) {
                session.runOnce(lastCompile)
                prevHashes = lastCompile.hashes
            }

            // Set up file watcher. No keyboard shortcuts — stdin belongs
            // to the user program (see std::effects::stdin). Reload via
            // file save; quit via Ctrl-C.
            val watcher = java.nio.file.FileSystems.getDefault().newWatchService()
            val watchedDirs = HashMap<java.nio.file.Path, java.nio.file.WatchKey>()
            syncWatchedDirs(watcher, watchedDirs, appFile, currentDir)

            val events = java.util.concurrent.LinkedBlockingQueue<DevEvent>()
            startFileWatcherThread(watcher, events)

            // Carries how the PREVIOUS run ended so the NEXT reload separator
            // can fold it in (`… reloaded app.mz · prev exited 0 · 0.09s`).
            var priorOutcome: DevConsole.RunResult = DevConsole.RunResult.Reloading
            // True while the current source doesn't compile — so the next good
            // save shows a `recovered` separator instead of a plain `reloaded`.
            var inErrorState = false

            while (!quitRequested.get()) {
                val event = try {
                    events.take()
                } catch (_: InterruptedException) {
                    // Ctrl-C quit hook interrupted us — leave the loop, the
                    // finally-block closes the child.
                    break
                }
                when (event) {
                    is DevEvent.FilesChanged -> {
                        // Coalesce any events that arrived during the take().
                        val coalesced = mutableSetOf<String>().apply { addAll(event.files) }
                        while (true) {
                            val next = events.poll() ?: break
                            if (next is DevEvent.FilesChanged) coalesced.addAll(next.files)
                            else { events.add(next); break }
                        }
                        invalidateChangedModules(coalesced, currentDir)
                        val tReload = System.nanoTime()
                        val changed = coalesced.firstOrNull() ?: appFile.name
                        // Clear the visible screen on EVERY reload (success OR
                        // error) so each save shows only the fresh result;
                        // scrollback keeps the old output. No-op off a TTY.
                        System.err.print(console.clearScreen())
                        val next = compile(currentDir, appFile, entryName, appName, timing, tReload)
                        if (next == null) {
                            // Save doesn't compile. compile() already printed the
                            // pretty diagnostic; add a persistent "waiting for a
                            // fix" marker and keep the prior run alive underneath.
                            System.err.println(console.blank())
                            System.err.println(console.errorSeparator(changed))
                            inErrorState = true
                        } else {
                            val reloadSecs = (System.nanoTime() - tReload) / 1e9
                            // Separator PRECEDES this run's output. Coming out of
                            // an error → `recovered`; otherwise a normal reload
                            // that folds in how the previous run ended.
                            System.err.println(console.blank())
                            if (inErrorState) {
                                System.err.println(console.recovered(changed, reloadSecs))
                            } else {
                                System.err.println(console.separator(
                                    label = "reloaded $changed · ${fmtSecs(reloadSecs)}",
                                    priorStatus = console.priorStatus(priorOutcome),
                                ))
                            }
                            inErrorState = false
                            System.err.println(console.blank())

                            // EVICT: hashes that were in the previous closure
                            // but aren't in the new one. They identify
                            // functions whose source changed (so the hash
                            // changed) and we need the runtime to drop the
                            // stale CallTarget. New hashes don't need EVICT
                            // — they were never cached.
                            val evict = prevHashes - next.hashes
                            val outcome = session.runOnce(next, evict = if (evict.isEmpty()) null else evict)
                            prevHashes = next.hashes
                            lastCompile = next
                            priorOutcome = when (outcome.reason) {
                                "exit_called" -> DevConsole.RunResult.Exited(outcome.exitCode)
                                "wire_error", "crashed" -> DevConsole.RunResult.Crashed
                                else -> DevConsole.RunResult.Exited(outcome.exitCode)
                            }
                            if (outcome.reason == "wire_error" || outcome.reason == "crashed") {
                                // Session is unusable — wire corrupted or
                                // child died. Restart so the next file save
                                // gets a fresh runtime. Bail entirely if
                                // even the restart fails.
                                if (!restartSession()) {
                                    System.err.println(console.separator("couldn't restart runtime — exiting"))
                                    return 1
                                }
                            }
                        }
                        syncWatchedDirs(watcher, watchedDirs, appFile, currentDir)
                        // Drain any duplicate FS events from the same save.
                        while (true) {
                            val n = events.poll() ?: break
                            if (n !is DevEvent.FilesChanged) { events.add(n); break }
                        }
                    }
                }
            }
            // Left the loop → Ctrl-C quit. 130 is the conventional
            // "terminated by SIGINT" exit code.
            return 130
        } finally {
            session.close()
        }
    }

    private fun fmtSecs(s: Double): String =
        if (s < 1.0) "${(s * 1000).toInt()}ms" else "${"%.2f".format(s)}s"

    // ─── Watch helpers: file watcher, dep tracking ──────────

    private sealed class DevEvent {
        data class FilesChanged(val files: Set<String>) : DevEvent()
    }

    /**
     * Tell the typechecker that these source files changed, so the next
     * compile re-reads them from disk instead of returning the cached
     * typed-module. Only invalidates modules under the project root —
     * stdlib never changes during a dev session.
     */
    private fun invalidateChangedModules(changedFiles: Set<String>, projectRoot: File) {
        val rootPath = projectRoot.toPath().toAbsolutePath().normalize()
        val toEvict = mutableSetOf<String>()
        for ((modulePath, module) in com.mezze.language.typechecker.ModuleResolver.snapshot()) {
            val filePath = module.filePath.takeIf { it.isNotBlank() } ?: continue
            val abs = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize()
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
     * Watch every dir that contains a `.mz` file the typechecker
     * actually loaded for this project. After each compile we recompute
     * the set — newly-imported modules' dirs get added, no-longer-
     * imported ones get unregistered.
     */
    private fun syncWatchedDirs(
        watcher: java.nio.file.WatchService,
        watchedDirs: MutableMap<java.nio.file.Path, java.nio.file.WatchKey>,
        sourceFile: File,
        projectRoot: File,
    ) {
        val rootPath = projectRoot.toPath().toAbsolutePath().normalize()
        val wanted = HashSet<java.nio.file.Path>()
        // Always include the entry file's parent.
        wanted.add(sourceFile.parentFile.toPath().toAbsolutePath().normalize())
        for ((_, module) in com.mezze.language.typechecker.ModuleResolver.snapshot()) {
            val filePath = module.filePath.takeIf { it.isNotBlank() } ?: continue
            val abs = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize()
            if (abs.startsWith(rootPath)) {
                wanted.add(abs.parent ?: continue)
            }
        }
        for (dir in wanted) {
            if (dir !in watchedDirs && java.nio.file.Files.isDirectory(dir)) {
                watchedDirs[dir] = dir.register(
                    watcher,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                )
            }
        }
        val iter = watchedDirs.entries.iterator()
        while (iter.hasNext()) {
            val (dir, key) = iter.next()
            if (dir !in wanted) { key.cancel(); iter.remove() }
        }
    }

    private fun startFileWatcherThread(
        watcher: java.nio.file.WatchService,
        events: java.util.concurrent.BlockingQueue<DevEvent>,
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

    /** Set up the project store + typechecker singletons. */
    private fun initStore(currentDir: File) {
        System.setProperty("mezze.project.root", currentDir.absolutePath)
        // Opens + invalidates cache written by a different mez version, so
        // `mez exec` / `mez dev` never run stale IR after a toolchain switch
        // (see ProjectStore). Covers both — they route through here.
        val store = ProjectStore.open(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)
    }

    /**
     * Parse + typecheck + lower the user app. Writes CBOR files under
     * `.mez/run/` (overwriting per run). Returns null on failure.
     */
    private fun compile(
        currentDir: File,
        appFile: File,
        entryName: String,
        appName: String,
        timing: Boolean,
        t0: Long,
    ): CompileResult? {
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        val source = appFile.readText()
        val parser = MezzeParser()
        val parseResult = parser.parseWithRecovery(source, file = appFile.name)
        if (parseResult.errors.isNotEmpty()) {
            System.err.println(ParseErrorFormatter.formatAll(parseResult.errors))
            return null
        }
        val ast = parseResult.module
        if (timing) System.err.println("[${ms()}ms] parsed")

        // #46: load precompiled dependency IR so the typechecker serves it
        // instead of re-lowering deps. No-op when no dep has a compiled store.
        DepStoreLoader.load(currentDir)

        val typeChecker = TypeChecker(strictTopLevel = true)
        val rawResult = try {
            typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            System.err.println(com.mezze.language.langservice.printing.ErrorFormatter.format(e, source))
            return null
        }
        if (rawResult is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure) {
            System.err.println(TypeCheckRunner.formatErrors(rawResult, source))
            return null
        }
        val result = TypeCheckRunner.wrapSuccess(
            rawResult as com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success
        )
        if (timing) System.err.println("[${ms()}ms] typechecked + lowered (${result.loweredFunctions.size} fns)")

        val entry = result.typedModule?.decls
            ?.filterIsInstance<TypedLetDecl>()
            ?.find { it.name == entryName }
        if (entry == null) {
            System.err.println("Error: App must define a '$entryName' function with type `{} -> {} <*>`")
            return null
        }
        EntryPointCheck.errorMessage(EntryPointCheck.check(entry), entryName)?.let { msg ->
            System.err.println("Error: $msg")
            return null
        }

        // Entry identity = content hash (== entry FunctionIR.hash). Resolved
        // once here at the front door from the user-named entry; carried as a
        // hash through packing / runtime selection. entryName kept as display.
        val entryHash = entry.hash
        val rawClosure: List<FunctionIR> = NativeBuildPath.computeReachableClosure(result.loweredFunctions)
        // #446 — canonicalize iter/seq/list HOF chains into FusedChainIR.
        // StdlibPrecompiler runs this on stdlib functions at build time;
        // we need to run it again on user code (and any reachable stdlib
        // functions whose callers compose chains) so map+fold chains etc.
        // in user code get fused.
        val closureMap = rawClosure.associateBy { it.hash }
        val canonicalized = com.mezze.language.truffleir.ChainCanonicalizer.canonicalize(closureMap)
        val closure = rawClosure.map { canonicalized[it.hash] ?: it }
        val (fnBytes, metaBytes) = NativeBuildPath.packEmbeddedAppCbor(
            closure = closure,
            appName = appName,
            entryHash = entryHash.hex,
            entryName = entryName,
            performerRecords = result.performerRecords,
        )
        val runDir = com.mezze.language.store.ProjectLayout.runDir(currentDir).apply { mkdirs() }
        val fnsFile = File(runDir, "fns.cbor").apply { writeBytes(fnBytes) }
        val metaFile = File(runDir, "meta.cbor").apply { writeBytes(metaBytes) }
        if (timing) System.err.println("[${ms()}ms] packed (${fnBytes.size} + ${metaBytes.size} bytes)")
        return CompileResult(fnsFile, metaFile, closure.map { it.hash }.toSet())
    }
}

/**
 * One open wire-protocol session with a RuntimeServer subprocess. Tracks
 * Ctrl-C wiring and the I/O streams; per-run reporting goes through
 * [runOnce] which sends EVICT/LOAD and consumes frames until EXITED.
 */
private class WireSession private constructor(
    private val process: Process,
    private val toChild: OutputStream,
    private val fromChild: InputStream,
    private val shutdownHook: Thread,
    private val timing: Boolean,
) {

    private val signalHandler = SigintForwarder(toChild, process)
    private val winchForwarder = WinchForwarder(toChild)
    private val sigtermForwarder = SigtermForwarder(toChild, process)
    private val rawModeManager = RawModeManager()

    /**
     * Set to the child's exit code when the RuntimeServer process dies.
     * Initialized via process.onExit() in init{}; consulted by
     * drainUntilExited so we can report crash promptly instead of waiting
     * on a blocking read that won't terminate until the OS finishes
     * closing the pipe. -1 means "not dead yet".
     */
    @Volatile private var childExitCode: Int = -1

    /**
     * Captured tail of the child's real stderr (fd 2). Normal user stderr rides
     * the wire as STDERR_DATA frames; fd 2 only carries the pre-wire fatal path
     * — a RuntimeServer startup crash, or a Truffle/libgraal init failure — which
     * used to dump a raw JVM stack trace straight to the terminal via
     * redirectError(INHERIT). We PIPE it and drain it here instead, so on a
     * crash we can show a graceful message by default and the raw trace only
     * under --verbose. Bounded so a runaway child can't exhaust memory.
     */
    private val childStderr = StringBuilder()
    private val childStderrLock = Any()

    /** Attach a drain thread to the child's piped stderr. Called from start(). */
    private fun captureStderr(errStream: InputStream) {
        thread(isDaemon = true, name = "mez-child-stderr") {
            try {
                errStream.bufferedReader().forEachLine { line ->
                    synchronized(childStderrLock) {
                        if (childStderr.length < 64 * 1024) childStderr.append(line).append('\n')
                    }
                }
            } catch (_: Exception) { /* stream closed on child exit — done */ }
        }
    }

    /** The captured child stderr tail, trimmed; empty if the child said nothing on fd 2. */
    private fun childStderrText(): String = synchronized(childStderrLock) { childStderr.toString().trim() }

    /**
     * Print the reason a child crash occurred, honouring --verbose: the raw
     * captured stderr (usually a JVM/Truffle trace) only when verbose, otherwise
     * a one-line pointer. Keeps a hard child crash from ever dumping a trace by
     * default while still making it one flag away.
     */
    private fun reportChildStderr() {
        val captured = childStderrText()
        if (captured.isEmpty()) return
        if (com.mezze.language.cli.CliContext.verbose) {
            System.err.println(captured)
        } else {
            System.err.println("    (re-run with --verbose for the underlying error)")
        }
    }

    /**
     * Parent-side mirror of the runId the child will assign to the next
     * (or current) run. Used to tag STDIN_DATA frames so the child can
     * drop bytes intended for a prior run after a `mez dev` reload (#243
     * Race C: forwarder ships bytes between the parent's LOAD send and
     * the child's per-run reset; without runId tagging those bytes
     * silently land in the wrong run's buffer or get reset away).
     *
     * Incremented at the top of runOnce; the forwarder reads the current
     * value when shipping each chunk.
     */
    private val currentRunId = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Stdin forwarder: lazily spawned when the child first asks for stdin
     * via STDIN_REQUEST. Reads from parent's System.in, ships STDIN_DATA
     * frames to the child, sends STDIN_EOF on EOF. Stays alive for the
     * rest of the session; subsequent STDIN_REQUEST frames are no-ops
     * because the reader is already pulling.
     */
    @Volatile private var stdinForwarder: Thread? = null
    private val stdinForwarderLock = Any()

    init {
        signalHandler.install()
        winchForwarder.install()
        sigtermForwarder.install()
        // When the child exits (for any reason), record its exit code
        // and close fromChild so any blocked WireFrameCodec.read returns
        // promptly. Without this, a SIGKILL or OOM on the child can leave
        // the parent hanging until the kernel decides to close the pipe,
        // which on some systems takes seconds.
        process.onExit().thenAccept { p ->
            childExitCode = p.exitValue()
            try { fromChild.close() } catch (_: Exception) {}
        }
    }

    /** Result of one LOAD cycle. */
    data class RunOutcome(val exitCode: Int, val reason: String)

    /**
     * Install a watch-mode quit hook: the next Ctrl-C interrupts the running
     * program AND runs [hook] so the watch loop can unblock and return. Only
     * `mez dev` watch mode calls this; `--no-watch` leaves it unset so a
     * Ctrl-C there just ends the single run.
     */
    fun onQuit(hook: () -> Unit) = signalHandler.setQuitHook(hook)

    /**
     * Send (optional) EVICT + LOAD, stream output, return when EXITED
     * arrives. The session stays open for the next run.
     */
    fun runOnce(
        compile: WireRunner.CompileResult,
        evict: Set<Hash>? = null,
        appArgs: List<String> = emptyList(),
    ): RunOutcome {
        // Bump our parent-side runId mirror BEFORE sending LOAD, so any
        // STDIN_DATA the forwarder ships from now on is tagged for the
        // new run. Bytes the forwarder shipped before this point still
        // carry the prior runId and the child can correctly drop them
        // as stale.
        currentRunId.incrementAndGet()
        if (evict != null && evict.isNotEmpty()) {
            send(WireFrame.of(MessageType.EVICT, Evict(hashes = evict.map { it.hex })))
        }
        // Ship per-run process surface so the child's process_args / env
        // / cwd reflect what the user passed to mez, not the JVM launcher.
        val env: Map<String, String>? = try { System.getenv() } catch (_: Exception) { null }
        val cwd: String? = try { File("").absoluteFile.path } catch (_: Exception) { null }
        send(WireFrame.of(MessageType.LOAD, Load(
            fnsCborPath = compile.fnsFile.absolutePath,
            metaCborPath = compile.metaFile.absolutePath,
            mode = "run",
            argv = appArgs,
            cwd = cwd,
            env = env,
        )))
        return drainUntilExited()
    }

    private fun drainUntilExited(): RunOutcome {
        // Consecutive framing-error counter. PROTO_UNKNOWN_TYPE is fully
        // recoverable (length+payload were read correctly, we just don't
        // recognize the tag), and many PROTO_DECODE errors are also
        // recoverable as long as the next length-prefix is still aligned.
        // PROTO_TOO_LARGE desynchronizes the stream and can't be skipped.
        //
        // Bail when we hit too many in a row — the stream is probably
        // unrecoverable and continuing just produces noise.
        var consecutiveFraming = 0
        val maxConsecutive = 5
        while (true) {
            val frame = try {
                val f = WireFrameCodec.read(fromChild)
                consecutiveFraming = 0  // clean read → reset
                f
            } catch (e: java.io.IOException) {
                // Stream was closed underneath us — almost certainly
                // because onExit fired and closed fromChild to unblock
                // this read. Treat as clean EOF; the ?: return below
                // surfaces the child's exit code.
                null
            } catch (e: WireProtocolException) {
                // PROTO_TOO_LARGE means we know the wire is desynchronized
                // (we read a length but won't read the payload). No point
                // trying to skip; the next bytes are mid-payload junk.
                if (e.code == "PROTO_TOO_LARGE") {
                    System.err.println("Error: wire decode failed: ${e.code} ${e.message} — stream unrecoverable")
                    return RunOutcome(exitCode = 1, reason = "wire_error")
                }
                consecutiveFraming++
                System.err.println(
                    "[mez] wire frame skipped (${e.code} ${e.message}); " +
                    "continuing (errors in a row: $consecutiveFraming)"
                )
                if (consecutiveFraming >= maxConsecutive) {
                    System.err.println(
                        "Error: $maxConsecutive framing errors in a row, " +
                        "giving up on this run"
                    )
                    return RunOutcome(exitCode = 1, reason = "wire_error")
                }
                continue
            } ?: return run {
                // Either the child closed its stdout cleanly (rare —
                // should always send BYE first) or the onExit hook
                // already closed fromChild because the process died.
                // Race: we may see EOF before onExit fires, so wait up to
                // 500 ms for the exit code if it's not in yet.
                if (childExitCode < 0) {
                    try {
                        process.onExit().get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: Exception) { /* still alive or timeout */ }
                }
                val ec = childExitCode
                if (ec >= 0) {
                    val signal = signalFromExitCode(ec)
                    val msg = if (signal != null) {
                        "${Glyphs.CROSS} the runtime was killed by $signal"
                    } else {
                        "${Glyphs.CROSS} the runtime exited unexpectedly (exit $ec)"
                    }
                    System.err.println(msg)
                    reportChildStderr()
                    RunOutcome(exitCode = ec, reason = "crashed")
                } else {
                    System.err.println("${Glyphs.CROSS} the runtime exited unexpectedly")
                    reportChildStderr()
                    RunOutcome(exitCode = 1, reason = "crashed")
                }
            }
            when (frame.type) {
                MessageType.LOADED -> { /* ignore — purely advisory */ }
                MessageType.STARTED -> { /* ignore */ }
                MessageType.STDOUT_DATA -> {
                    val d = frame.decode<StdoutData>()
                    System.out.write(d.bytes)
                    System.out.flush()
                }
                MessageType.STDERR_DATA -> {
                    val d = frame.decode<StderrData>()
                    System.err.write(d.bytes)
                    System.err.flush()
                }
                MessageType.EXITED -> {
                    // Race-safe: cancel any pending kill BEFORE returning,
                    // so a slow Ctrl-C escalation timer doesn't fire after
                    // a graceful EXITED arrived. SigintForwarder's
                    // cancelEscalation is a no-op if no timer is armed.
                    signalHandler.cancelEscalation()
                    sigtermForwarder.cancelEscalation()
                    val e = frame.decode<Exited>()
                    val detail = e.detail
                    if (e.reason == "error" && detail != null && detail.message.isNotEmpty()) {
                        System.err.println(detail.message)
                    }
                    return RunOutcome(exitCode = e.code, reason = e.reason)
                }
                MessageType.ERROR -> {
                    val pe = frame.decode<ProtocolError>()
                    System.err.println("Protocol error: ${pe.code} ${pe.message}")
                    return RunOutcome(exitCode = 1, reason = "protocol_error")
                }
                MessageType.LOG -> {
                    // Surface child-runtime logs to stderr.
                    val log = frame.decode<com.mezze.wire.Log>()
                    System.err.println("[runtime ${log.level}] ${log.message}")
                }
                MessageType.STDIN_REQUEST -> {
                    if (System.getenv("MEZZE_DEBUG") != null) {
                        System.err.println("[wire] STDIN_REQUEST received, ensuring forwarder")
                    }
                    // Child asked for stdin. Start the parent-side reader
                    // if not already running. Idempotent.
                    ensureStdinForwarder()
                }
                MessageType.PASSWORD_REQUEST -> {
                    val req = frame.decode<com.mezze.wire.PasswordRequest>()
                    handlePasswordRequest(req)
                }
                MessageType.RAW_MODE_SET -> rawModeManager.enter()
                MessageType.RAW_MODE_UNSET -> rawModeManager.leave()
                else -> {
                    System.err.println("[wire] unexpected frame: ${frame.type}")
                }
            }
        }
    }

    /**
     * Child sent PASSWORD_REQUEST — read a password from the parent's real
     * terminal (no echo) and ship the bytes back via PASSWORD_REPLY. When
     * stdin is piped or redirected (System.console() == null) there's no
     * TTY to disable echo on, so we reply with null and the child surfaces
     * that as `None` to the user program.
     */
    /**
     * Translate a process exit code into a signal name when it matches
     * POSIX's "killed by signal N → exit 128+N" convention. Used when we
     * detect a crashed RuntimeServer so the surfaced error tells the user
     * "killed by SIGKILL" rather than the cryptic "exit 137".
     *
     * Unix shells report this convention; on macOS / Linux it's standard.
     * Returns null for codes that aren't in the 128+N range, or for codes
     * outside the common signals (1, 2, 6, 9, 11, 15) we'd actually see
     * from a RuntimeServer death.
     */
    private fun signalFromExitCode(code: Int): String? {
        if (code !in 129..143) return null
        return when (code - 128) {
            1 -> "SIGHUP"
            2 -> "SIGINT"
            6 -> "SIGABRT"  // e.g. JVM assertion failure
            9 -> "SIGKILL"  // OOM-killer or external kill -9
            11 -> "SIGSEGV"
            15 -> "SIGTERM"
            else -> null
        }
    }

    private fun handlePasswordRequest(req: com.mezze.wire.PasswordRequest) {
        val cons = System.console()
        val value: String? = if (cons == null) {
            null
        } else {
            try {
                val chars = cons.readPassword()
                chars?.let { String(it) }
            } catch (_: Exception) {
                null
            }
        }
        try {
            send(WireFrame.of(MessageType.PASSWORD_REPLY,
                com.mezze.wire.PasswordReply(runId = req.runId, value = value)))
        } catch (_: Exception) {
            // best-effort; if wire is broken, child will eventually fail too
        }
    }

    /**
     * Start (once per session) a thread that reads parent's stdin and
     * forwards bytes to the child as STDIN_DATA frames. Sends STDIN_EOF
     * when System.in returns -1. Subsequent calls are no-ops — the
     * forwarder is shared across runs.
     *
     * The child sends STDIN_REQUEST on the first stdin read of each run;
     * we only need the forwarder once because once running it pumps any
     * available bytes regardless of which run is active. (Run-id on the
     * STDIN_DATA frames is filled in opportunistically; the child's
     * buffer is run-agnostic — bytes that arrive between runs are
     * dropped at run-start via reset.)
     */
    private fun ensureStdinForwarder() {
        if (stdinForwarder != null) return
        synchronized(stdinForwarderLock) {
            if (stdinForwarder != null) return
            val debug = System.getenv("MEZZE_DEBUG") != null
            val thread = Thread({
                if (debug) System.err.println("[wire] stdin forwarder starting")
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = System.`in`.read(buf)
                        if (debug) System.err.println("[wire] stdin read returned $n")
                        if (n < 0) {
                            // Tag EOF with the runId active when we observed EOF
                            // — child uses this to decide whether to push EOF
                            // into the active run's buffer or drop it as stale.
                            val rid = currentRunId.get()
                            try { send(WireFrame.of(MessageType.STDIN_EOF, com.mezze.wire.StdinEof(runId = rid))) } catch (_: Exception) {}
                            if (debug) System.err.println("[wire] sent STDIN_EOF (run $rid)")
                            return@Thread
                        }
                        if (n == 0) continue
                        val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                        // Tag with the runId active at the moment we shipped
                        // these bytes. If a reload races in between, bytes
                        // from the previous run get correctly attributed and
                        // the child drops them (#243 Race C).
                        val rid = currentRunId.get()
                        try {
                            send(WireFrame.of(MessageType.STDIN_DATA, com.mezze.wire.StdinData(runId = rid, bytes = chunk)))
                            if (debug) System.err.println("[wire] sent STDIN_DATA n=$n run=$rid")
                        } catch (_: Exception) {
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    if (debug) System.err.println("[wire] stdin forwarder error: ${e.message}")
                    val rid = currentRunId.get()
                    try { send(WireFrame.of(MessageType.STDIN_EOF, com.mezze.wire.StdinEof(runId = rid))) } catch (_: Exception) {}
                }
            }, "mez-wire-stdin-fwd").apply {
                isDaemon = true
            }
            stdinForwarder = thread
            thread.start()
        }
    }

    fun close() {
        try { signalHandler.uninstall() } catch (_: Exception) {}
        try { winchForwarder.uninstall() } catch (_: Exception) {}
        try { sigtermForwarder.uninstall() } catch (_: Exception) {}
        try { rawModeManager.restore() } catch (_: Exception) {}
        try { send(WireFrame.of(MessageType.QUIT, Quit(reason = "done"))) } catch (_: Exception) {}
        // Drain until BYE or EOF (give it 2s to be polite).
        val deadline = System.currentTimeMillis() + 2000
        try {
            while (System.currentTimeMillis() < deadline) {
                val frame = WireFrameCodec.read(fromChild) ?: break
                if (frame.type == MessageType.BYE) break
            }
        } catch (_: Exception) {}
        try { toChild.close() } catch (_: Exception) {}
        try { process.waitFor() } catch (_: Exception) {}
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) {}
    }

    @Synchronized
    private fun send(frame: WireFrame) {
        WireFrameCodec.writeFlush(toChild, frame)
    }

    companion object {
        fun start(
            currentDir: File,
            timing: Boolean,
            t0: Long,
            devFlags: List<String> = emptyList(),
        ): WireSession? {
            fun ms() = (System.nanoTime() - t0) / 1_000_000

            val java = JvmLauncher.findJava() ?: run {
                System.err.println("Error: Java 21+ required.")
                return null
            }
            // Base classpath = mezze-runtime.jar. When dev flags are
            // present, also append ~/.mezze/lib/tools/*.jar so the
            // chromeinspector / profiler / coverage / dap / insight
            // tool jars are visible to Truffle's option parser.
            // installDevTools laid these down at install time.
            val baseClasspath = JvmLauncher.buildClasspath()
            val withTools = if (devFlags.isNotEmpty()) {
                val toolsDir = com.mezze.language.cli.MezzeHome.toolsDir
                val toolJars = toolsDir.listFiles { f -> f.name.endsWith(".jar") }
                    ?.joinToString(File.pathSeparator) { it.absolutePath }
                    ?: ""
                if (toolJars.isNotEmpty()) "$baseClasspath${File.pathSeparator}$toolJars" else baseClasspath
            } else baseClasspath
            // Mezze.toml [polyglot.jvm] classpath entries — user-supplied
            // Java classes callable via `java::type(...)`. Resolved once
            // here (parent side) so the child's -cp is fully built before
            // spawn.
            val polyglotJvmCp = com.mezze.language.cli.`package`.PolyglotResolver
                .resolveJvmClasspath(currentDir)
            // Commit 5 — walk Mezze.lock's resolved packages, read each
            // package's `[jars]` table, verify each jar's sha256 against
            // `~/.mezze/store/jars/<sha>/`, and append the verified jars
            // to the classpath. Missing store entries log a warning and
            // are skipped so a partially-synced project still boots; a
            // sha256 mismatch is a hard skip with a clean-sync hint.
            val resolvedJarsReport = com.mezze.language.cli.`package`.ResolvedJarsClasspath.collect(currentDir)
            for (w in resolvedJarsReport.warnings) System.err.println("[mez] $w")
            val resolvedJarsCp = resolvedJarsReport.asClasspath()
            val classpath = buildString {
                append(withTools)
                if (polyglotJvmCp.isNotEmpty()) {
                    append(File.pathSeparator)
                    append(polyglotJvmCp.joinToString(File.pathSeparator))
                }
                if (resolvedJarsCp.isNotEmpty()) {
                    append(File.pathSeparator)
                    append(resolvedJarsCp)
                }
            }

            val command = mutableListOf(java.absolutePath)
            command.addAll(jvmArgs())
            command.add("-Dmezze.project.root=${currentDir.absolutePath}")
            command.add("-cp"); command.add(classpath)
            command.add("com.mezze.interpreter.standalone.RuntimeServer")
            // Dev instrumentation flags get appended as RuntimeServer's
            // args[] — DevFlags.parse picks them up before building the
            // Truffle Engine. Order doesn't matter; they're flag-named.
            command.addAll(devFlags)

            // PIPE (not INHERIT) the child's stderr: the child's normal user
            // output rides the wire as STDERR_DATA frames, so fd 2 only ever
            // carries the pre-wire fatal path (a RuntimeServer startup crash or a
            // Truffle/libgraal init failure). Capturing it lets us show a graceful
            // message by default and the raw trace only under --verbose, instead
            // of dumping a JVM stack trace straight to the terminal.
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            val hook = Thread { if (process.isAlive) process.destroy() }
            Runtime.getRuntime().addShutdownHook(hook)

            val toChild = BufferedOutputStream(process.outputStream)
            val fromChild = BufferedInputStream(process.inputStream)

            val session = WireSession(process, toChild, fromChild, hook, timing)
            session.captureStderr(process.errorStream)
            if (timing) System.err.println("[${ms()}ms] RuntimeServer spawned")

            // Perform handshake before returning. A handshake failure is the
            // classic runtime-startup crash symptom (e.g. a Truffle/libgraal init
            // mismatch): the child JVM dies before WELCOME. Surface a graceful
            // message + the captured stderr (raw trace only under --verbose)
            // instead of silently returning null and leaving the user with
            // nothing to act on.
            session.handshake() ?: run {
                // Give the child's exit + stderr drain a beat to settle so the
                // captured message is complete before we report it.
                try { process.onExit().get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                System.err.println("${Glyphs.CROSS} the runtime failed to start")
                session.reportChildStderr()
                session.close()
                return null
            }
            if (timing) System.err.println("[${ms()}ms] handshake complete")
            return session
        }

        private fun jvmArgs(): List<String> = listOf(
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector",
            "--sun-misc-unsafe-memory-access=allow",
            "-Dpolyglot.engine.WarnInterpreterOnly=false",
        )
    }

    /** Send HELLO, await WELCOME. Returns the agreed capability set, or null on failure. */
    private fun handshake(): List<String>? {
        // Parent owns the real terminal. Capture isatty/term info now so
        // the child's `is_tty` / `term_size` natives can serve it without
        // needing direct OS access (their fd 0 is the wire, not a TTY).
        val cons = System.console()
        val stdinIsTty = cons != null
        val stdoutIsTty = cons != null  // best-effort proxy; System.console() needs both
        val stderrIsTty = cons != null
        // Initial terminal dimensions. Prefer LINES/COLUMNS env vars if
        // set (shells like bash/zsh export them on resize); else ask stty
        // directly. Live updates ride in on SIGWINCH via WinchForwarder
        // after handshake.
        var termRows = System.getenv("LINES")?.toIntOrNull() ?: 0
        var termCols = System.getenv("COLUMNS")?.toIntOrNull() ?: 0
        if (termRows == 0 || termCols == 0) {
            try {
                val proc = ProcessBuilder("stty", "size")
                    .redirectInput(java.io.File("/dev/tty"))
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                val parts = out.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    termRows = parts[0].toIntOrNull() ?: termRows
                    termCols = parts[1].toIntOrNull() ?: termCols
                }
            } catch (_: Exception) { /* stay 0 */ }
        }
        val term = System.getenv("TERM") ?: ""
        send(WireFrame.of(MessageType.HELLO, Hello(
            versionMajor = WireProtocol.PROTOCOL_VERSION_MAJOR,
            versionMinor = WireProtocol.PROTOCOL_VERSION_MINOR,
            capabilities = WireRunner.ADVERTISED_CAPABILITIES,
            client = "mez-dev",
            isattyStdin = stdinIsTty,
            isattyStdout = stdoutIsTty,
            isattyStderr = stderrIsTty,
            termRows = termRows,
            termCols = termCols,
            term = term,
        )))
        val reply = try {
            WireFrameCodec.read(fromChild)
        } catch (e: WireProtocolException) {
            System.err.println("Error: wire handshake failed: ${e.code} ${e.message}")
            return null
        } ?: run {
            System.err.println("Error: RuntimeServer exited before WELCOME")
            return null
        }
        return when (reply.type) {
            MessageType.WELCOME -> reply.decode<Welcome>().agreedCapabilities
            MessageType.REJECT -> {
                val r = reply.decode<Reject>()
                System.err.println("Error: runtime rejected handshake (${r.reason}): ${r.detail}")
                null
            }
            else -> {
                System.err.println("Error: expected WELCOME after HELLO, got ${reply.type}")
                null
            }
        }
    }
}

/**
 * Forward SIGINT (Ctrl-C) in the parent to an INTERRUPT frame in the
 * child, then escalate to process kill if the child doesn't respond.
 *
 * Flow per Ctrl-C:
 *   1. Send INTERRUPT frame. Child cancels via Truffle.interrupt
 *      and sends EXITED reason=interrupted.
 *   2. Start a deadline timer (default 5 s, override via
 *      MEZZE_INTERRUPT_TIMEOUT_MS env var). If EXITED arrives in
 *      time, [cancelEscalation] is called and the timer is cancelled.
 *   3. If the deadline fires (user code is in a tight loop with no
 *      safepoints, or polyglot ignored cancel), call
 *      process.destroyForcibly(). This closes the pipe; parent's
 *      drainUntilExited sees EOF and surfaces as reason=crashed.
 *
 * Second Ctrl-C inside the grace window also escalates immediately
 * — same "tap to stop, double-tap to kill" UX as common dev tools.
 */
private class SigintForwarder(
    private val toChild: OutputStream,
    private val process: Process,
    /**
     * Watch-mode quit hook. When set (only `mez dev` watch mode sets it), the
     * FIRST Ctrl-C means "quit the whole dev session": we forward INTERRUPT to
     * stop the running program AND invoke [onQuit] so the watch loop unblocks
     * from `events.take()` and returns (its `finally` closes the child).
     *
     * WHY THIS IS NEEDED: we disable native-image's own exit handlers
     * (`-H:-InstallExitHandlers`) so THIS forwarder controls Ctrl-C. That
     * fixed the "child left running" bug, but the parent's watch loop has no
     * other exit path — without this hook it would hang after the interrupt.
     * In `--no-watch` [onQuit] stays null: Ctrl-C interrupts the single run
     * and `runOnce` returns on its own.
     */
    @Volatile private var onQuit: (() -> Unit)? = null,
) {
    fun setQuitHook(hook: () -> Unit) { onQuit = hook }

    private var previous: sun.misc.SignalHandler? = null
    @Volatile private var pendingEscalation: java.util.concurrent.ScheduledFuture<*>? = null
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mez-interrupt-timer").apply { isDaemon = true }
    }

    private val timeoutMs: Long =
        System.getenv("MEZZE_INTERRUPT_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(100L) ?: 5_000L

    private val handler = sun.misc.SignalHandler {
        // Second Ctrl-C while we're already waiting → escalate now.
        val already = pendingEscalation
        if (already != null && !already.isDone) {
            already.cancel(false)
            kill("user pressed Ctrl-C twice")
            onQuit?.invoke()
            return@SignalHandler
        }
        try {
            val frame = WireFrame.of(MessageType.INTERRUPT, Interrupt())
            synchronized(toChild) {
                WireFrameCodec.writeFlush(toChild, frame)
            }
        } catch (_: Exception) {
            // Wire write failed — child is probably already dead; bail.
            onQuit?.invoke()
            return@SignalHandler
        }
        pendingEscalation = timer.schedule(
            { kill("graceful interrupt timed out after ${timeoutMs}ms") },
            timeoutMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        // Watch mode: a single Ctrl-C quits the session. Unblock the watch
        // loop now — the interrupt already told the child to stop.
        onQuit?.invoke()
    }

    /** Called by WireSession when EXITED arrives — cancel any pending kill. */
    fun cancelEscalation() {
        pendingEscalation?.cancel(false)
        pendingEscalation = null
    }

    private fun kill(reason: String) {
        try {
            if (process.isAlive) {
                System.err.println("[mez] $reason; killing runtime")
                process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    fun install() {
        try {
            previous = sun.misc.Signal.handle(sun.misc.Signal("INT"), handler)
        } catch (_: Throwable) {
            // sun.misc may not be available — graceful degradation
        }
    }

    fun uninstall() {
        try {
            previous?.let { sun.misc.Signal.handle(sun.misc.Signal("INT"), it) }
        } catch (_: Throwable) {}
        try { timer.shutdownNow() } catch (_: Exception) {}
    }
}

/**
 * SIGWINCH handler: when the user resizes the terminal the parent receives
 * SIGWINCH from the OS; we read the new rows/cols and ship a WINCH frame
 * to the child so `Stdin.term_size` reflects the live size. Without this,
 * the child's term-size snapshot is frozen at handshake values and any
 * user-program TUI render after a resize draws at the old dimensions.
 *
 * The "WINCH" signal name isn't part of the Java POSIX signal contract,
 * but sun.misc.Signal accepts it on Unix JVMs. Windows JVMs throw — we
 * swallow that and the feature degrades to "no live updates" (initial
 * handshake values stay sticky).
 *
 * We read TIOCGWINSZ-style values from `stty size` since JVM has no
 * portable getwinsize API. That spawns a process per SIGWINCH — small
 * cost for a UI-rate event (typically < 1 per second during a drag).
 */
private class WinchForwarder(private val toChild: OutputStream) {
    private var previous: sun.misc.SignalHandler? = null

    private val handler = sun.misc.SignalHandler {
        val (rows, cols) = readSize() ?: return@SignalHandler
        try {
            val frame = WireFrame.of(MessageType.WINCH, com.mezze.wire.Winch(rows = rows, cols = cols))
            synchronized(toChild) {
                WireFrameCodec.writeFlush(toChild, frame)
            }
        } catch (_: Exception) {
            // Child dead or wire torn — give up quietly; next handshake
            // ships fresh values anyway.
        }
    }

    private fun readSize(): Pair<Int, Int>? {
        return try {
            // `stty size` writes "<rows> <cols>" on stdout when stdin is
            // a TTY. We bind /dev/tty as stdin so it works even if the
            // JVM's own stdin is piped (which it is under `mez exec`).
            val proc = ProcessBuilder("stty", "size")
                .redirectInput(java.io.File("/dev/tty"))
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            val parts = out.split(Regex("\\s+"))
            if (parts.size < 2) return null
            val r = parts[0].toIntOrNull() ?: return null
            val c = parts[1].toIntOrNull() ?: return null
            r to c
        } catch (_: Exception) {
            null
        }
    }

    fun install() {
        try {
            previous = sun.misc.Signal.handle(sun.misc.Signal("WINCH"), handler)
        } catch (_: Throwable) {
            // Non-Unix JVM or SIGWINCH unavailable — initial handshake
            // values stick, no live updates. Acceptable degradation.
        }
    }

    fun uninstall() {
        try {
            previous?.let { sun.misc.Signal.handle(sun.misc.Signal("WINCH"), it) }
        } catch (_: Throwable) {}
    }
}

/**
 * SIGTERM handler: when systemd / Docker / Kubernetes / `kill <pid>`
 * sends SIGTERM to the mez parent process, we forward it as a SIGNAL
 * frame so the child can drain stdout/stderr and close its Polyglot
 * context cleanly before the OS escalates to SIGKILL (default 10 s on
 * most schedulers). Without this, the parent dies on default JVM
 * SIGTERM handling and orphans the child mid-eval — partial output,
 * undefined exit code, ugly logs.
 *
 * Mechanism mirrors SigintForwarder: ship the frame, arm a grace timer,
 * cancel the timer when EXITED arrives. The grace window matches
 * MEZZE_INTERRUPT_TIMEOUT_MS (default 5 s) — slightly shorter than the
 * typical 10 s supervisor SIGKILL deadline so we have headroom.
 *
 * No "double tap to kill" UX here — a second SIGTERM from a supervisor
 * is rare and usually means the supervisor lost patience; we don't
 * need to escalate ourselves. The grace timer handles that case.
 */
private class SigtermForwarder(
    private val toChild: OutputStream,
    private val process: Process,
) {
    private var previous: sun.misc.SignalHandler? = null
    @Volatile private var pendingEscalation: java.util.concurrent.ScheduledFuture<*>? = null
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mez-sigterm-timer").apply { isDaemon = true }
    }

    private val timeoutMs: Long =
        System.getenv("MEZZE_INTERRUPT_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(100L) ?: 5_000L

    private val handler = sun.misc.SignalHandler {
        try {
            val frame = WireFrame.of(MessageType.SIGNAL, com.mezze.wire.Signal(name = "SIGTERM"))
            synchronized(toChild) {
                WireFrameCodec.writeFlush(toChild, frame)
            }
        } catch (_: Exception) {
            // Wire dead — child probably already gone; let JVM run its
            // default SIGTERM behaviour (terminate).
            return@SignalHandler
        }
        pendingEscalation = timer.schedule(
            { kill("graceful SIGTERM timed out after ${timeoutMs}ms") },
            timeoutMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    /** Called by WireSession when EXITED arrives — cancel pending kill. */
    fun cancelEscalation() {
        pendingEscalation?.cancel(false)
        pendingEscalation = null
    }

    private fun kill(reason: String) {
        try {
            if (process.isAlive) {
                System.err.println("[mez] $reason; killing runtime")
                process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    fun install() {
        try {
            previous = sun.misc.Signal.handle(sun.misc.Signal("TERM"), handler)
        } catch (_: Throwable) {
            // sun.misc unavailable / Windows / sandbox — graceful
            // degradation: JVM default SIGTERM handling kicks in (die).
        }
    }

    fun uninstall() {
        try {
            previous?.let { sun.misc.Signal.handle(sun.misc.Signal("TERM"), it) }
        } catch (_: Throwable) {}
        try { timer.shutdownNow() } catch (_: Exception) {}
    }
}

/**
 * Manages the parent terminal's raw-mode state on behalf of child requests
 * (#240). The child sends RAW_MODE_SET to enter (disable canonical line
 * buffering + echo so single keypresses are visible to the user program),
 * RAW_MODE_UNSET to leave. The trickiest part is *restoring* termios:
 *
 *   - User-program crash mid-eval → close() restores via restore()
 *   - Ctrl-C (SIGINT) → SigintForwarder triggers graceful shutdown,
 *     close() restores
 *   - SIGTERM → SigtermForwarder same path, close() restores
 *   - mez parent itself crashes → JVM shutdown hook restores
 *   - Unexpected hard kill (SIGKILL) → can't restore, terminal is hosed.
 *     User runs `stty sane` to recover. Documented limitation.
 *
 * Implementation: capture termios state via `stty -g < /dev/tty` (returns
 * an opaque restore string), run `stty raw -echo < /dev/tty` to enter raw
 * mode. On exit, run `stty <saved> < /dev/tty`. Idempotent: enter()
 * doesn't re-snapshot if already in raw mode; leave()/restore() are no-ops
 * if not currently in raw mode.
 */
private class RawModeManager {
    @Volatile private var savedState: String? = null
    private val shutdownHook = Thread { restore() }
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureShutdownHook() {
        if (installed.compareAndSet(false, true)) {
            try { Runtime.getRuntime().addShutdownHook(shutdownHook) } catch (_: Throwable) {}
        }
    }

    @Synchronized fun enter() {
        if (savedState != null) return  // already in raw mode
        ensureShutdownHook()
        val state = sttyGet() ?: return  // not a TTY → silently no-op
        savedState = state
        runStty("raw", "-echo")
    }

    @Synchronized fun leave() {
        val state = savedState ?: return  // not in raw mode → no-op
        runStty(state)
        savedState = null
    }

    /**
     * Best-effort restore on session close / shutdown hook. Distinct from
     * leave() because we want both close() and the shutdown hook to be
     * safe to call without coordination — only one actually does work.
     */
    fun restore() {
        leave()
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) {}
    }

    /** Read termios state as an opaque restore string. Returns null on non-TTY. */
    private fun sttyGet(): String? {
        return try {
            val proc = ProcessBuilder("stty", "-g")
                .redirectInput(java.io.File("/dev/tty"))
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ec = proc.waitFor()
            if (ec != 0 || out.isEmpty()) null else out
        } catch (_: Exception) { null }
    }

    /** Apply stty flags against /dev/tty. Silent on failure. */
    private fun runStty(vararg flags: String) {
        try {
            val cmd = mutableListOf("stty")
            cmd.addAll(flags)
            ProcessBuilder(cmd)
                .redirectInput(java.io.File("/dev/tty"))
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (_: Exception) {}
    }
}

