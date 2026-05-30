package com.mezze.interpreter.standalone

import com.mezze.interpreter.runtime.MezzeExitException
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.wire.Bye
import com.mezze.wire.Config
import com.mezze.wire.ConfigAck
import com.mezze.wire.Evict
import com.mezze.wire.ExitDetail
import com.mezze.wire.Exited
import com.mezze.wire.Hello
import com.mezze.wire.Interrupt
import com.mezze.wire.Load
import com.mezze.wire.Loaded
import com.mezze.wire.MessageType
import com.mezze.wire.PasswordReply
import com.mezze.wire.PasswordRequest
import com.mezze.wire.Signal
import com.mezze.wire.Winch
import com.mezze.wire.Ping
import com.mezze.wire.Pong
import com.mezze.wire.ProtocolError
import com.mezze.wire.Quit
import com.mezze.wire.RuntimeInfo
import com.mezze.wire.StackFrame
import com.mezze.wire.Started
import com.mezze.wire.StatusReply
import com.mezze.wire.StatusReq
import com.mezze.wire.StderrData
import com.mezze.wire.StdinData
import com.mezze.wire.StdinEof
import com.mezze.wire.StdinRequest
import com.mezze.wire.StdoutData
import com.mezze.wire.Welcome
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireProtocolException
import com.mezze.interpreter.nodes.builtin.effects.StdinNatives
import com.mezze.interpreter.standalone.wire.WireStdinBuffer
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JVM-side server for the Mezze wire protocol v1. See
 * `docs/wire-protocol/00-design.md` for the design.
 *
 * Lifecycle:
 *   - Read [Hello] from stdin → reply [Welcome] with negotiated
 *     capabilities + runtime info.
 *   - Loop on incoming frames: dispatch by [MessageType], maintain
 *     state machine (HANDSHAKING / IDLE / ACTIVE / INTERRUPTING /
 *     SHUTTING_DOWN), execute when [Load] arrives.
 *   - On [Quit]: drain current run if any, send [Exited] then [Bye],
 *     return (caller exits 0).
 *
 * User code's `Environment.exit { code = N }` throws
 * [MezzeExitException], which Polyglot wraps as `PolyglotException`
 * with `isExit == true`. We catch and send `EXITED reason=exit_called
 * code=N`, then stay alive for the next [Load].
 *
 * User program stdout/stderr is captured by replacing
 * [System.setOut] / [System.setErr] with streams that wrap writes in
 * [StdoutData] / [StderrData] frames. The original real-stdout fd 1
 * is used exclusively for control frames.
 */
object RuntimeServer {

    /** Capabilities this server advertises in [Welcome]. */
    private val ADVERTISED_CAPABILITIES = listOf(
        WireProtocol.Capability.CORE,
        WireProtocol.Capability.EXIT_CODE,
        WireProtocol.Capability.INTERRUPT,
        WireProtocol.Capability.STRUCTURED_ERRORS,
        WireProtocol.Capability.STDIN,
        WireProtocol.Capability.STDERR_FRAMES,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Save the real stdout BEFORE redirecting — control frames go here.
        // Anything written to System.out after we redirect gets wrapped in
        // STDOUT_DATA and routed back to the parent.
        val controlOut: OutputStream = BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out))
        val controlIn = BufferedInputStream(System.`in`)

        // Run all I/O on this lock — single writer to controlOut, no
        // interleaving between (e.g.) a STDOUT_DATA frame from user code
        // and an EXITED frame from the dispatch loop.
        val ioLock = ReentrantLock()
        fun send(frame: WireFrame) = ioLock.withLock {
            WireFrameCodec.writeFlush(controlOut, frame)
        }

        // Save original stderr — used for runtime diagnostics that
        // must NOT go through the wire (handshake-time warnings, dev-
        // flag announce, fatal protocol errors). The parent inherits
        // fd 2 from us, so these surface in the user's terminal
        // verbatim with no protocol involvement.
        val originalStderr = System.err
        val runIdCarrier = AtomicLong(0)
        // We'll redirect System.out / System.err to wire-frame streams,
        // but ONLY after dev-flag init has had a chance to write its
        // "[mezze] profile → /path" announce lines via originalStderr.
        // Mezze's println native writes to System.out directly (not via
        // Truffle Context.out), so redirecting System.out is required to
        // capture user output into the wire.
        val wireOut = PrintStream(WireFrameOutputStream(MessageType.STDOUT_DATA, runIdCarrier, ::send), true)
        val wireErr = PrintStream(WireFrameOutputStream(MessageType.STDERR_DATA, runIdCarrier, ::send), true)

        val jitEnabled = System.getenv("MEZZE_JIT")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

        // Dev instrumentation flags arrive as command-line args from the
        // parent's WireRunner. Mirror EmbeddedExec's wiring so behavior
        // matches between `mez dev`/`mez exec` and a standalone-jar/native
        // user-app binary. Outputs land under <project>/.mz/profile/ (the
        // wire path; EmbeddedExec uses <cwd>/mezze-*.txt for user-app
        // binaries since they may run from anywhere).
        val devFlags = DevFlags.parse(args, originalStderr)

        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", jitEnabled.toString())
        if (devFlags.traceJit) engineBuilder.option("engine.TraceCompilation", "true")
        if (devFlags.profile != null) {
            engineBuilder.option("cpusampler", "true")
                .option("cpusampler.OutputFile", devFlags.profile.absolutePath)
            originalStderr.println("[mezze] profile → ${devFlags.profile.absolutePath}")
        }
        if (devFlags.coverage != null) {
            engineBuilder.option("coverage", "true")
                .option("coverage.OutputFile", devFlags.coverage.absolutePath)
            originalStderr.println("[mezze] coverage → ${devFlags.coverage.absolutePath}")
        }
        if (devFlags.allocations != null) {
            engineBuilder.option("mezze-allocations", "true")
                .out(PrintStream(devFlags.allocations.outputStream(), true))
            originalStderr.println("[mezze] allocations → ${devFlags.allocations.absolutePath}")
        }
        if (devFlags.insightFile != null) {
            engineBuilder.option("insight", devFlags.insightFile.absolutePath)
            originalStderr.println("[mezze] insight probe → ${devFlags.insightFile.absolutePath}")
        }
        if (devFlags.inspect) {
            engineBuilder.option("inspect", "4242")
            if (devFlags.inspectBrk) engineBuilder.option("inspect.Suspend", "true")
            originalStderr.println("[mezze] devtools listening on chrome://inspect — port 4242")
        }
        val engine = engineBuilder.build()

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            // Truffle's Context.out is the channel for tool-emitted text
            // (cpusampler reports etc.) and any language-internal writes.
            // Send through the wire so timing matches user output. err
            // intentionally not set — defaults to System.err which is
            // inherited fd 2 (parent's terminal); avoids wire frames for
            // dev-tool startup banners.
            .out(wireOut)
            .build()

        val state = ServerState(context, engine, runIdCarrier, jitEnabled)
        // Diagnostics destination: silent by default, opt-in via MEZZE_DEBUG.
        // Without this, every `mez exec` prints "[RuntimeServer] handshake
        // ok ..." and "QUIT (done)" lines to the user's terminal.
        val diag: PrintStream = if (System.getenv("MEZZE_DEBUG") != null) originalStderr
                                else PrintStream(java.io.OutputStream.nullOutputStream())

        // Wire stdin: buffer that the dispatch loop feeds from STDIN_DATA
        // frames, exposed to StdinNatives as an InputStream. The
        // first-read callback sends STDIN_REQUEST so the parent knows to
        // start forwarding user-stdin bytes.
        val stdinBuffer = WireStdinBuffer()
        StdinNatives.installWireStreams(stdinBuffer.asInputStream()) {
            try {
                send(WireFrame.of(MessageType.STDIN_REQUEST, StdinRequest(runId = runIdCarrier.get())))
            } catch (_: Exception) {
                // best-effort; if the wire is broken the read will simply EOF
            }
        }
        state.stdinBuffer = stdinBuffer

        // Wire password reader: parent owns the real terminal, so password
        // reads must round-trip via PASSWORD_REQUEST / PASSWORD_REPLY. We
        // block the eval thread on the reply queue; the dispatch loop
        // unblocks it when the parent's PASSWORD_REPLY arrives.
        StdinNatives.installWirePasswordReader {
            try {
                // Drop any stale reply from a prior cancelled request before
                // sending fresh PASSWORD_REQUEST — defensive against bugs in
                // the parent that double-replied.
                state.passwordReplies.clear()
                send(WireFrame.of(MessageType.PASSWORD_REQUEST,
                    PasswordRequest(runId = runIdCarrier.get())))
                // Block until the dispatch loop hands us the reply.
                state.passwordReplies.take().value
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                // Wire broken or framing error: treat as EOF for the user.
                null
            }
        }

        try {
            handshake(controlIn, ::send, diag)
            // Redirect System.out / System.err to the wire so all user
            // output arrives at the parent as STDOUT_DATA / STDERR_DATA
            // frames. Done late — after engine/context init and after
            // handshake — so tool init warnings (cpusampler, inspect)
            // and the dev-flag announce can write to the *real* terminal
            // via `originalStderr` without becoming out-of-band wire frames.
            //
            // The parent re-emits STDOUT_DATA → its own stdout and
            // STDERR_DATA → its own stderr, preserving the user's
            // expectation that `Log.print` writes to stdout and
            // panics/exceptions land on stderr.
            System.setOut(wireOut)
            System.setErr(wireErr)
            dispatchLoop(controlIn, ::send, state, diag)
        } catch (e: WireProtocolException) {
            // Framing-level failure — best-effort report then exit. Always
            // log this to the real stderr; wire failures are bugs, not
            // routine.
            tryWriteProtocolError(::send, e.code, e.message ?: "wire failure")
            originalStderr.println("[RuntimeServer] wire failure: ${e.code} ${e.message}")
        } catch (e: Exception) {
            originalStderr.println("[RuntimeServer] fatal: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(originalStderr)
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { send(WireFrame.of(MessageType.BYE, Bye())) } catch (_: Exception) {}
        }
    }

    // ─── Handshake ───────────────────────────────────────────────────

    private fun handshake(
        input: java.io.InputStream,
        send: (WireFrame) -> Unit,
        diag: PrintStream,
    ) {
        val first = WireFrameCodec.read(input)
            ?: throw WireProtocolException("PROTO_DECODE", "EOF before HELLO")
        if (first.type != MessageType.HELLO) {
            throw WireProtocolException("PROTO_STATE_INVALID", "expected HELLO first, got ${first.type}")
        }
        val hello = first.decode<Hello>()
        // Capture parent's TTY/terminal snapshot so the child's
        // `is_tty` etc. natives can serve it without OS access. The
        // child's fd 0 is the wire, not a real TTY, so without this
        // info `is_tty` would always return false.
        StdinNatives.setWireIsTty(hello.isattyStdin, hello.isattyStdout, hello.isattyStderr)
        // Initial terminal dimensions — refreshed live by WINCH dispatch
        // when the parent's SIGWINCH handler ships an update.
        StdinNatives.setWireTermSize(hello.termRows, hello.termCols)
        // Version negotiation. Major mismatch is fatal; minor mismatch
        // uses the lower version's vocabulary.
        if (hello.versionMajor != WireProtocol.PROTOCOL_VERSION_MAJOR) {
            send(WireFrame.of(MessageType.REJECT, com.mezze.wire.Reject(
                reason = "version_unsupported",
                detail = "client wants v${hello.versionMajor}.${hello.versionMinor}, " +
                         "server is v${WireProtocol.PROTOCOL_VERSION_MAJOR}.${WireProtocol.PROTOCOL_VERSION_MINOR}",
            )))
            throw WireProtocolException("PROTO_VERSION", "incompatible major version")
        }
        val agreedMinor = minOf(hello.versionMinor, WireProtocol.PROTOCOL_VERSION_MINOR)
        val agreedCaps = hello.capabilities.intersect(ADVERTISED_CAPABILITIES.toSet()).toList()
        send(WireFrame.of(MessageType.WELCOME, Welcome(
            versionMajor = WireProtocol.PROTOCOL_VERSION_MAJOR,
            versionMinor = WireProtocol.PROTOCOL_VERSION_MINOR,
            capabilities = ADVERTISED_CAPABILITIES,
            agreedVersionMajor = WireProtocol.PROTOCOL_VERSION_MAJOR,
            agreedVersionMinor = agreedMinor,
            agreedCapabilities = agreedCaps,
            runtimeInfo = collectRuntimeInfo(),
        )))
        diag.println("[RuntimeServer] handshake ok with ${hello.client} v${hello.versionMajor}.${hello.versionMinor}; agreed caps: $agreedCaps")
    }

    // ─── Dispatch loop ───────────────────────────────────────────────

    private fun dispatchLoop(
        input: java.io.InputStream,
        send: (WireFrame) -> Unit,
        state: ServerState,
        diag: PrintStream,
    ) {
        // Dedicated worker for LOAD-triggered evals. The dispatch loop
        // MUST stay free to read frames concurrently with eval — otherwise
        // STDIN_DATA / INTERRUPT / EVICT frames would buffer on the wire
        // until eval returned, which means stdin reads would deadlock.
        // Single-thread executor enforces "one active run at a time" (v1).
        val evalWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mez-runtime-eval").apply { isDaemon = true }
        }
        try {
            while (true) {
                val frame = try {
                    WireFrameCodec.read(input) ?: return  // EOF — parent closed, leave quietly
                } catch (e: WireProtocolException) {
                    tryWriteProtocolError(send, e.code, e.message ?: "decode error")
                    continue
                }
                when (frame.type) {
                    MessageType.QUIT -> {
                        val q = frame.decode<Quit>()
                        diag.println("[RuntimeServer] QUIT (${q.reason})")
                        return
                    }
                    MessageType.EVICT -> handleEvict(frame.decode())
                    MessageType.LOAD -> {
                        val load = frame.decode<Load>()
                        // Hand off to worker so we can keep reading frames
                        // (including STDIN_DATA, INTERRUPT) concurrently.
                        evalWorker.submit { handleLoad(load, state, send) }
                    }
                    MessageType.PING -> send(WireFrame.of(MessageType.PONG, Pong()))
                    MessageType.STATUS -> send(WireFrame.of(MessageType.STATUS_REPLY, state.snapshotStatus()))
                    MessageType.INTERRUPT -> handleInterrupt(frame.decode(), state, diag)
                    MessageType.SIGNAL -> handleSignal(frame.decode(), state, diag)
                    MessageType.CONFIG -> handleConfig(frame.decode(), send)
                    MessageType.STDIN_DATA -> {
                        val data = frame.decode<StdinData>()
                        state.stdinBuffer?.push(data.bytes)
                    }
                    MessageType.STDIN_EOF -> {
                        state.stdinBuffer?.pushEof()
                    }
                    MessageType.PASSWORD_REPLY -> {
                        // Hand off to the eval thread that's blocked in
                        // wirePasswordReader.get(). offer is non-blocking
                        // (capacity 1, slot is empty by contract since the
                        // requester drained before sending PASSWORD_REQUEST).
                        state.passwordReplies.offer(frame.decode<PasswordReply>())
                    }
                    MessageType.WINCH -> {
                        // Parent's terminal resized — refresh the static
                        // snapshot that Stdin.term_size reads. Cheap: two
                        // volatile assignments, no thread coordination.
                        val w = frame.decode<Winch>()
                        StdinNatives.setWireTermSize(w.rows, w.cols)
                    }
                    MessageType.HELLO -> {
                        tryWriteProtocolError(send, "PROTO_STATE_INVALID", "duplicate HELLO after handshake")
                    }
                    else -> {
                        // Frame type the v1 server doesn't recognize.
                        // Defensive against forward-compat clients sending
                        // a higher minor-version frame we don't have a
                        // handler for. Reject cleanly so the client knows
                        // not to expect a reply.
                        tryWriteProtocolError(send, "PROTO_STATE_INVALID", "unsupported in v1 server: ${frame.type}")
                    }
                }
            }
        } finally {
            // Best-effort shutdown of the eval worker. If a run is in
            // progress when we exit (e.g. parent closed mid-run), it's
            // already going to die with the process anyway.
            evalWorker.shutdownNow()
        }
    }

    // ─── Command handlers ────────────────────────────────────────────

    private fun handleEvict(e: Evict) {
        val hashes = e.hashes.mapNotNull { hex -> runCatching { Hash(hex) }.getOrNull() }
        if (hashes.isNotEmpty()) MezzeLanguage.setHashesToInvalidate(hashes)
    }

    private fun handleLoad(load: Load, state: ServerState, send: (WireFrame) -> Unit) {
        val fnsFile = File(load.fnsCborPath)
        val metaFile = File(load.metaCborPath)
        if (!fnsFile.exists()) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(message = "fns cbor not found: ${fnsFile.absolutePath}"))
            return
        }
        if (!metaFile.exists()) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(message = "meta cbor not found: ${metaFile.absolutePath}"))
            return
        }

        val functions: List<FunctionIR>
        val meta: StoreSerialization.DeserializedEmbeddedAppMeta
        try {
            functions = StoreSerialization.deserializeFunctionIRList(fnsFile.readBytes())
            meta = StoreSerialization.deserializeEmbeddedAppMeta(metaFile.readBytes())
        } catch (e: Exception) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(
                message = "decode failed: ${e.javaClass.simpleName}: ${e.message}"
            ))
            return
        }
        if (functions.isEmpty()) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(message = "empty function closure"))
            return
        }

        val runId = state.nextRunId()
        send(WireFrame.of(MessageType.LOADED, Loaded(runId = runId, fnCount = functions.size)))

        if (load.mode != "run") {
            // `register` mode: compile only, don't execute. Parent will
            // EVAL later (REPL flow, v1.x). For v1 we still send STARTED+
            // EXITED with a no-op shape so the parent's state machine
            // matches.
            send(WireFrame.of(MessageType.STARTED, Started(runId = runId)))
            sendExited(send, state, code = 0, reason = "normal", detail = null)
            return
        }

        val entryName = meta.entryName
        try {
            if (entryName != null) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, meta.performerRecords, state.projectRoot(), entryName)
            } else {
                MezzeLanguage.setFunctionsForDirectExecution(functions, meta.performerRecords, state.projectRoot())
            }
        } catch (e: Exception) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(
                message = "register failed: ${e.javaClass.simpleName}: ${e.message}"
            ))
            return
        }

        send(WireFrame.of(MessageType.STARTED, Started(runId = runId)))
        state.runStarted(runId)

        // Reset stdin: drop any buffered bytes / EOF from a previous run,
        // and re-arm the demand-request flag so the first stdin read in
        // this run triggers a fresh STDIN_REQUEST to the parent.
        state.stdinBuffer?.reset()
        StdinNatives.resetRequestFlag()

        // Install per-run process surface from the LOAD payload. Null fields
        // mean parent didn't ship them; child falls back to JVM-inherited
        // values. Refreshed per LOAD so dev-mode reload picks up cwd/env
        // changes between saves.
        com.mezze.interpreter.nodes.builtin.effects.EnvironmentNatives.setWireArgv(
            load.argv?.toTypedArray()
        )
        if (load.cwd != null) {
            System.setProperty("user.dir", load.cwd)
        }
        // env: per-key System.setProperty (Java doesn't expose a mutable
        // env API). Only consulted by env-style natives; doesn't affect
        // sub-processes the user might spawn.
        load.env?.forEach { (k, v) -> System.setProperty(k, v) }

        val source = Source.newBuilder(
            MezzeLanguage.ID,
            "# RuntimeServer $runId",
            "__runtime_server_${runId}__.mz"
        ).buildLiteral()

        try {
            state.context.eval(source)
            sendExited(send, state, code = 0, reason = "normal", detail = null)
        } catch (e: PolyglotException) {
            if (e.isExit) {
                sendExited(send, state, code = e.exitStatus, reason = "exit_called", detail = null)
            } else if (e.isCancelled || e.isInterrupted) {
                // Distinguish SIGTERM (143) from SIGINT/Ctrl-C (130) so
                // supervisors that read POSIX exit codes (systemd, k8s)
                // see "terminated by signal 15" not "ctrl-c". Default
                // path (terminatingSignal == null) is SIGINT.
                //
                // isInterrupted: Context.interrupt() path (used by both
                // handleInterrupt for SIGINT and handleSignal for SIGTERM).
                // isCancelled: Context.close(cancelIfRunning=true) path
                // (not currently used here but accept it defensively).
                val (code, reason) = when (val sig = state.terminatingSignal) {
                    "SIGTERM" -> 143 to "terminated"
                    null -> 130 to "interrupted"
                    else -> (128 + signumOf(sig)) to "terminated"
                }
                sendExited(send, state, code = code, reason = reason, detail = null)
            } else {
                sendExited(send, state, code = 1, reason = "error", detail = exitDetailFor(e))
            }
        } catch (e: Exception) {
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(
                message = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            ))
        } finally {
            state.runEnded()
        }
    }

    /**
     * Parent forwarded a terminating signal (SIGTERM today; could grow to
     * SIGHUP/SIGUSR* without protocol changes — Signal carries a name).
     * Treated as a graceful-shutdown variant of INTERRUPT: cancel the
     * current eval, flag the state so the cancel-catch path knows to
     * surface POSIX-conventional exit code 128 + signum (143 for TERM)
     * instead of 130 (SIGINT).
     *
     * Per-run state.terminatingSignal acts as the single piece of out-of-band
     * data; the eval thread's PolyglotException catch reads it to pick the
     * right exit code.
     */
    private fun handleSignal(sig: Signal, state: ServerState, diag: PrintStream) {
        val active = state.activeRunId
        if (active == null) return
        diag.println("[RuntimeServer] SIGNAL ${sig.name} run $active")
        state.terminatingSignal = sig.name
        state.markInterrupting()
        try {
            state.context.interrupt(java.time.Duration.ofSeconds(5))
        } catch (e: Exception) {
            diag.println("[RuntimeServer] interrupt-from-signal failed: ${e.message}")
        }
    }

    private fun handleInterrupt(i: Interrupt, state: ServerState, diag: PrintStream) {
        val active = state.activeRunId
        if (active == null || (i.runId != null && i.runId != active)) return  // nothing to interrupt
        diag.println("[RuntimeServer] INTERRUPT run $active")
        state.markInterrupting()
        try {
            // Truffle cancels in-flight execution; the eval call in
            // handleLoad will throw a cancelled PolyglotException, which
            // we surface as EXITED reason=interrupted.
            state.context.interrupt(java.time.Duration.ofSeconds(5))
        } catch (e: Exception) {
            diag.println("[RuntimeServer] interrupt failed: ${e.message}")
        }
    }

    private fun handleConfig(c: Config, send: (WireFrame) -> Unit) {
        // v1 server doesn't support any CONFIG keys yet — accept the
        // frame to keep the state machine clean, reject every key.
        val rejected = c.keys.mapValues { "capability not advertised by this server (v1)" }
        send(WireFrame.of(MessageType.CONFIG_ACK, ConfigAck(accepted = emptyList(), rejected = rejected)))
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * POSIX signal number for the names we may receive over the wire.
     * Used to compute exit code = 128 + signum. Falls back to 0 (so the
     * full code becomes 128) for unknown names — safer than crashing the
     * runtime on an unexpected SIGNAL frame.
     */
    private fun signumOf(name: String): Int = when (name) {
        "SIGHUP" -> 1
        "SIGINT" -> 2
        "SIGTERM" -> 15
        "SIGUSR1" -> 30  // values vary by OS; we use macOS/Darwin
        "SIGUSR2" -> 31
        else -> 0
    }

    private fun sendExited(
        send: (WireFrame) -> Unit,
        state: ServerState,
        code: Int,
        reason: String,
        detail: ExitDetail?,
    ) {
        // Flush any pending user output before announcing EXITED so the
        // last line of stdout/stderr can't race with the exit-status arrival
        // at the parent. PrintStream.flush is idempotent and cheap.
        try { System.out.flush() } catch (_: Exception) {}
        try { System.err.flush() } catch (_: Exception) {}
        val runId = state.activeRunId ?: 0L
        send(WireFrame.of(MessageType.EXITED, Exited(runId = runId, code = code, reason = reason, detail = detail)))
    }

    private fun tryWriteProtocolError(send: (WireFrame) -> Unit, code: String, message: String) {
        try {
            send(WireFrame.of(MessageType.ERROR, ProtocolError(code = code, message = message)))
        } catch (_: Exception) {
            // Last-resort — the wire itself is broken; nothing useful we can do.
        }
    }

    private fun exitDetailFor(e: PolyglotException): ExitDetail {
        val frames = mutableListOf<StackFrame>()
        try {
            for (sf in e.polyglotStackTrace) {
                val src = sf.sourceLocation
                frames.add(StackFrame(
                    function = sf.rootName ?: "<unknown>",
                    file = src?.source?.name,
                    line = src?.startLine,
                    col = src?.startColumn,
                ))
                if (frames.size >= 50) break  // cap
            }
        } catch (_: Exception) {
            // best-effort; stack trace introspection can fail post-cancel
        }
        val src = e.sourceLocation
        return ExitDetail(
            message = e.message ?: "polyglot error",
            stack = frames,
            sourceFile = src?.source?.name,
            sourceLine = src?.startLine,
            sourceCol = src?.startColumn,
        )
    }

    private fun collectRuntimeInfo(): RuntimeInfo {
        val rt = Runtime.version().toString()
        val vendor = System.getProperty("java.vm.vendor") ?: "unknown"
        return RuntimeInfo(
            runtimeVersion = "0.1.0",
            runtimeGitSha = "",
            jvmVendor = vendor,
            jvmVersion = rt,
            truffleVersion = "24.2.2",
            languagesAvailable = listOf(MezzeLanguage.ID),
            jitEnabled = (System.getenv("MEZZE_JIT") == "1"),
        )
    }
}

// ─── Mutable server state ────────────────────────────────────────────

private class ServerState(
    val context: Context,
    val engine: Engine,
    private val runIdCarrier: AtomicLong,
    private val jitEnabled: Boolean,
) {
    @Volatile var activeRunId: Long? = null
        private set
    @Volatile private var interrupting: Boolean = false
    /**
     * Set by handleSignal when the parent forwarded a SIGTERM (or other
     * terminating signal). Causes the cancel-catch branch in handleLoad
     * to surface exit code 143 (POSIX 128 + 15) instead of 130 (SIGINT).
     * Reset per-run alongside `interrupting`.
     */
    @Volatile var terminatingSignal: String? = null
    /** Wire-fed stdin buffer; reset between runs. */
    var stdinBuffer: WireStdinBuffer? = null
    /**
     * Single-slot rendezvous for the password round-trip. Holds the parent's
     * reply (PasswordReply: string or null = EOF/cancelled) for one
     * outstanding PASSWORD_REQUEST. Capacity 1 so the dispatch loop's offer
     * never blocks even if no reader is waiting (a stale reply would be
     * dropped by clear() at the next request anyway).
     */
    val passwordReplies: java.util.concurrent.ArrayBlockingQueue<PasswordReply> =
        java.util.concurrent.ArrayBlockingQueue(1)

    fun nextRunId(): Long = runIdCarrier.incrementAndGet()

    fun runStarted(runId: Long) {
        activeRunId = runId
        interrupting = false
        terminatingSignal = null
    }

    fun runEnded() {
        activeRunId = null
        interrupting = false
        terminatingSignal = null
    }

    fun markInterrupting() {
        interrupting = true
    }

    fun projectRoot(): java.nio.file.Path =
        System.getProperty("mezze.project.root")?.let { Paths.get(it) } ?: Paths.get(".")

    fun snapshotStatus(): StatusReply {
        val state = when {
            interrupting -> "interrupting"
            activeRunId != null -> "running"
            else -> "idle"
        }
        return StatusReply(
            state = state,
            activeRunId = activeRunId,
            handleCount = 0,
            memoryBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
        )
    }
}

// ─── User-output → wire-frame adapter ────────────────────────────────

/**
 * OutputStream that wraps every write in a [StdoutData] / [StderrData]
 * frame and sends it through [send]. The current [runIdCarrier] value
 * is attached to each frame so the parent can attribute output to the
 * active run.
 *
 * Buffering: small writes are batched per call to write/flush. The
 * caller (a [PrintStream]) typically flushes per println, so frames
 * arrive at the line boundary granularity user code writes at.
 */
private class WireFrameOutputStream(
    private val type: MessageType,
    private val runIdCarrier: AtomicLong,
    private val send: (WireFrame) -> Unit,
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val bytes = b.copyOfRange(off, off + len)
        val runId = runIdCarrier.get()
        val frame = when (type) {
            MessageType.STDOUT_DATA -> WireFrame.of(type, StdoutData(runId = runId, bytes = bytes))
            MessageType.STDERR_DATA -> WireFrame.of(type, StderrData(runId = runId, bytes = bytes))
            else -> error("WireFrameOutputStream only supports STDOUT_DATA / STDERR_DATA, got $type")
        }
        send(frame)
    }

    override fun flush() {
        // send() already flushes the underlying controlOut.
    }

    override fun close() {
        // Don't actually close the underlying stream — RuntimeServer
        // controls its lifecycle. Flushing is a no-op (see above).
    }
}

// ─── Dev instrumentation flags ───────────────────────────────────────

/**
 * Parsed dev-instrumentation flags. Mirrors what EmbeddedExec accepts
 * so behavior matches between `mez dev` / `mez exec` (wire path) and
 * a standalone-jar / native user-app binary.
 *
 * Outputs land under <project>/.mz/profile/ — derived from the
 * `mezze.project.root` system property the parent sets when spawning.
 * Falls back to cwd if for some reason that property isn't set.
 */
private data class DevFlags(
    val inspect: Boolean,
    val inspectBrk: Boolean,
    val traceJit: Boolean,
    val profile: File?,
    val coverage: File?,
    val allocations: File?,
    val insightFile: File?,
) {
    companion object {
        fun parse(args: Array<String>, diag: PrintStream): DevFlags {
            val inspect = args.contains("--inspect") || args.contains("--inspect-brk")
            val inspectBrk = args.contains("--inspect-brk")
            val traceJit = args.contains("--trace-jit")
            val profile = if (args.contains("--profile")) profileOut("mezze-profile.txt") else null
            val coverage = if (args.contains("--coverage")) profileOut("mezze-coverage.txt") else null
            val allocations = if (args.contains("--allocations")) profileOut("mezze-allocations.txt") else null
            val insight = args.firstNotNullOfOrNull { a ->
                if (a.startsWith("--insight=")) a.substringAfter("=") else null
            }?.let { File(it) }
            if (insight != null && !insight.exists()) {
                diag.println("Error: --insight=${insight.path}: file not found")
                kotlin.system.exitProcess(1)
            }
            return DevFlags(inspect, inspectBrk, traceJit, profile, coverage, allocations, insight)
        }

        private fun profileOut(name: String): File {
            val root = System.getProperty("mezze.project.root")?.let { File(it) } ?: File(".")
            val dir = File(root, ".mz/profile")
            dir.mkdirs()
            return File(dir, name)
        }
    }
}
