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
     * Minimal first cut: re-uses the file-watch / keystroke handling
     * from DevRunner only conceptually — here we keep it simple and
     * just watch the entry-file's directory for `.mz` events.
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
            System.err.println("[mez dev] $appName · ${appFile.name}")
            val first = compile(currentDir, appFile, entryName, appName, timing, t0)
            if (first != null) {
                session.runOnce(first)
                prevHashes = first.hashes
            }
            System.err.println("[mez dev] watching ${appFile.parentFile.absolutePath}")

            val watcher = java.nio.file.FileSystems.getDefault().newWatchService()
            appFile.parentFile.toPath().register(
                watcher,
                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
            )
            while (true) {
                val key = try { watcher.take() } catch (_: InterruptedException) { return 0 }
                var sawMz = false
                for (ev in key.pollEvents()) {
                    val name = ev.context()?.toString() ?: continue
                    if (name.endsWith(".mz")) sawMz = true
                }
                key.reset()
                if (!sawMz) continue
                Thread.sleep(50)
                while (watcher.poll() != null) { /* drain */ }

                val tReload = System.nanoTime()
                val next = compile(currentDir, appFile, entryName, appName, timing, tReload) ?: continue
                val evict = next.hashes - prevHashes
                System.err.println("[mez dev] reload — ${evict.size} new hashes")
                session.runOnce(next, evict = if (evict.isEmpty()) null else evict)
                prevHashes = next.hashes
            }
        } finally {
            session.close()
        }
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

    private val signalHandler = SigintForwarder(toChild)

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
 * child. The JVM child cancels the active run cleanly; parent's
 * dispatch loop then sees EXITED with reason="interrupted".
 */
private class SigintForwarder(private val toChild: OutputStream) {
    private var previous: sun.misc.SignalHandler? = null
    private val handler = sun.misc.SignalHandler {
        try {
            val frame = WireFrame.of(MessageType.INTERRUPT, Interrupt())
            synchronized(toChild) {
                WireFrameCodec.writeFlush(toChild, frame)
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
    }
}

