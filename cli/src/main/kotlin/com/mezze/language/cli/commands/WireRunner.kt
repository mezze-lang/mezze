package com.mezze.language.cli.commands

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
import java.io.OutputStream

/**
 * Parent-side client of the Mezze wire protocol v1. See
 * `docs/wire-protocol/00-design.md` for the design.
 *
 * Flow:
 *   1. native mez parses + typechecks + lowers the user app.
 *   2. Serializes the closure + meta to CBOR files under `.mz/run/`.
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
        "core",
        "exit_code",
        "interrupt",
        "structured_errors",
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
     */
    fun execOnce(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
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

        val session = WireSession.start(currentDir, timing, t0) ?: return 1
        return try {
            val outcome = session.runOnce(compile)
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
     *   - Keyboard shortcuts (when stdin is a TTY): `r` to force re-run,
     *     `c` to clear screen, `q` to quit. stty-based raw mode; if it
     *     can't be enabled (CI / non-interactive), keys are silently
     *     disabled — file watcher still works.
     *
     *   - Coalesces fs events (macOS fires multiple per save) and drains
     *     duplicates after compile to avoid spurious extra reloads.
     */
    fun watch(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
    ): Int {
        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()

        if (!appFile.exists()) {
            System.err.println("Error: App file not found: ${appFile.absolutePath}")
            return 1
        }
        initStore(currentDir)

        val session = WireSession.start(currentDir, timing, t0) ?: return 1
        var prevHashes: Set<Hash> = emptySet()

        try {
            // First run. `lastCompile` tracks the most recent successful
            // compile so the `r` (force re-run) handler can re-execute it
            // without spending another typecheck cycle.
            System.err.println("[mez dev] $appName · ${appFile.name}")
            var lastCompile: CompileResult? = compile(currentDir, appFile, entryName, appName, timing, t0)
            if (lastCompile != null) {
                session.runOnce(lastCompile)
                prevHashes = lastCompile.hashes
            }

            // Set up file watcher + keyboard reader.
            val watcher = java.nio.file.FileSystems.getDefault().newWatchService()
            val watchedDirs = HashMap<java.nio.file.Path, java.nio.file.WatchKey>()
            syncWatchedDirs(watcher, watchedDirs, appFile, currentDir)

            val events = java.util.concurrent.LinkedBlockingQueue<DevEvent>()
            startFileWatcherThread(watcher, events)
            val keyboardActive = startKeyboardThread(events)
            printWatchingBanner(keyboardActive)

            while (true) {
                when (val event = events.take()) {
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
                        val next = compile(currentDir, appFile, entryName, appName, timing, tReload)
                        if (next != null) {
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
                            if (outcome.reason == "exit_called") {
                                System.err.println("[mez dev] program exited with code ${outcome.exitCode}")
                            }
                        }
                        syncWatchedDirs(watcher, watchedDirs, appFile, currentDir)
                        // Drain any duplicate FS events from the same save.
                        while (true) {
                            val n = events.poll() ?: break
                            if (n !is DevEvent.FilesChanged) { events.add(n); break }
                        }
                        printWatchingBanner(keyboardActive)
                    }
                    DevEvent.Rerun -> {
                        // `r` — re-execute the last compiled closure. No
                        // EVICT (nothing changed), no recompile. Useful for
                        // programs whose output depends on time / external
                        // state.
                        val current = lastCompile
                            ?: compile(currentDir, appFile, entryName, appName, timing, System.nanoTime())
                        if (current != null) {
                            session.runOnce(current, evict = null)
                            lastCompile = current
                            prevHashes = current.hashes
                        }
                        printWatchingBanner(keyboardActive)
                    }
                    DevEvent.Clear -> {
                        System.err.print("[2J[H")
                        printWatchingBanner(keyboardActive)
                    }
                    DevEvent.Quit -> {
                        System.err.println()
                        System.err.println("bye")
                        return 0
                    }
                }
            }
        } finally {
            session.close()
        }
    }

    // ─── Watch helpers: file watcher, keyboard, dep tracking ──────────

    private sealed class DevEvent {
        data class FilesChanged(val files: Set<String>) : DevEvent()
        object Rerun : DevEvent()
        object Clear : DevEvent()
        object Quit : DevEvent()
    }

    private fun printWatchingBanner(keyboardActive: Boolean) {
        val keys = if (keyboardActive) " · press [r] re-run · [c] clear · [q] quit" else " · Ctrl-C to stop"
        System.err.println("[mez dev] watching$keys")
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

    /**
     * Raw-mode stdin reader. Returns true if raw mode was enabled (and
     * keyboard events will arrive); false on non-interactive stdin or
     * Windows (no stty). Cleanup restores the saved stty state on JVM
     * shutdown.
     */
    private fun startKeyboardThread(events: java.util.concurrent.BlockingQueue<DevEvent>): Boolean {
        if (System.console() == null) return false
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("windows")) return false

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
            try {
                ProcessBuilder("stty", saved)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start().waitFor()
            } catch (_: Exception) {}
        })

        Thread {
            try {
                while (true) {
                    val ch = System.`in`.read()
                    if (ch < 0) return@Thread
                    when (ch.toChar().lowercaseChar()) {
                        'r' -> events.add(DevEvent.Rerun)
                        'c' -> events.add(DevEvent.Clear)
                        'q' -> { events.add(DevEvent.Quit); return@Thread }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "mez-watch-keys" }.start()
        return true
    }

    /** Set up the project store + typechecker singletons. */
    private fun initStore(currentDir: File) {
        System.setProperty("mezze.project.root", currentDir.absolutePath)
        val store = SQLiteMezzeStore.openProject(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)
    }

    /**
     * Parse + typecheck + lower the user app. Writes CBOR files under
     * `.mz/run/` (overwriting per run). Returns null on failure.
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

        val typeChecker = TypeChecker()
        val rawResult = try {
            typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            System.err.println(com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source))
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

        val closure: List<FunctionIR> = NativeBuildPath.computeReachableClosure(result.loweredFunctions)
        val (fnBytes, metaBytes) = NativeBuildPath.packEmbeddedAppCbor(
            closure = closure,
            appName = appName,
            entryName = entryName,
            performerRecords = result.performerRecords,
        )
        val runDir = File(currentDir, ".mz/run").apply { mkdirs() }
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

    init {
        signalHandler.install()
    }

    /** Result of one LOAD cycle. */
    data class RunOutcome(val exitCode: Int, val reason: String)

    /**
     * Send (optional) EVICT + LOAD, stream output, return when EXITED
     * arrives. The session stays open for the next run.
     */
    fun runOnce(compile: WireRunner.CompileResult, evict: Set<Hash>? = null): RunOutcome {
        if (evict != null && evict.isNotEmpty()) {
            send(WireFrame.of(MessageType.EVICT, Evict(hashes = evict.map { it.hex })))
        }
        send(WireFrame.of(MessageType.LOAD, Load(
            fnsCborPath = compile.fnsFile.absolutePath,
            metaCborPath = compile.metaFile.absolutePath,
            mode = "run",
        )))
        return drainUntilExited()
    }

    private fun drainUntilExited(): RunOutcome {
        while (true) {
            val frame = try {
                WireFrameCodec.read(fromChild)
            } catch (e: WireProtocolException) {
                System.err.println("Error: wire decode failed: ${e.code} ${e.message}")
                return RunOutcome(exitCode = 1, reason = "wire_error")
            } ?: return run {
                System.err.println("Error: RuntimeServer exited unexpectedly")
                RunOutcome(exitCode = 1, reason = "crashed")
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
                else -> {
                    System.err.println("[wire] unexpected frame: ${frame.type}")
                }
            }
        }
    }

    fun close() {
        try { signalHandler.uninstall() } catch (_: Exception) {}
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
        fun start(currentDir: File, timing: Boolean, t0: Long): WireSession? {
            fun ms() = (System.nanoTime() - t0) / 1_000_000

            val java = JvmLauncher.findJava() ?: run {
                System.err.println("Error: Java 21+ required.")
                return null
            }
            val classpath = JvmLauncher.buildClasspath()
            val command = mutableListOf(java.absolutePath)
            command.addAll(jvmArgs())
            command.add("-Dmezze.project.root=${currentDir.absolutePath}")
            command.add("-cp"); command.add(classpath)
            command.add("com.mezze.interpreter.standalone.RuntimeServer")

            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val hook = Thread { if (process.isAlive) process.destroy() }
            Runtime.getRuntime().addShutdownHook(hook)

            val toChild = BufferedOutputStream(process.outputStream)
            val fromChild = BufferedInputStream(process.inputStream)

            val session = WireSession(process, toChild, fromChild, hook, timing)
            if (timing) System.err.println("[${ms()}ms] RuntimeServer spawned")

            // Perform handshake before returning.
            session.handshake() ?: run {
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
            "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-Dpolyglot.engine.WarnInterpreterOnly=false",
        )
    }

    /** Send HELLO, await WELCOME. Returns the agreed capability set, or null on failure. */
    private fun handshake(): List<String>? {
        send(WireFrame.of(MessageType.HELLO, Hello(
            versionMajor = WireProtocol.PROTOCOL_VERSION_MAJOR,
            versionMinor = WireProtocol.PROTOCOL_VERSION_MINOR,
            capabilities = WireRunner.ADVERTISED_CAPABILITIES,
            client = "mez-dev",
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
) {
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
            return@SignalHandler
        }
        try {
            val frame = WireFrame.of(MessageType.INTERRUPT, Interrupt())
            synchronized(toChild) {
                WireFrameCodec.writeFlush(toChild, frame)
            }
        } catch (_: Exception) {
            // Wire write failed — child is probably already dead; bail.
            return@SignalHandler
        }
        pendingEscalation = timer.schedule(
            { kill("graceful interrupt timed out after ${timeoutMs}ms") },
            timeoutMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
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

