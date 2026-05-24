package com.mezze.interpreter.standalone

import com.mezze.interpreter.runtime.MezzeExitException
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.wire.AttachDenied
import com.mezze.wire.AttachGranted
import com.mezze.wire.AttachRequest
import com.mezze.wire.Bye
import com.mezze.wire.Config
import com.mezze.wire.ConfigAck
import com.mezze.wire.Eval
import com.mezze.wire.EvalResult
import com.mezze.wire.Evict
import com.mezze.wire.ExitDetail
import com.mezze.wire.Exited
import com.mezze.wire.Hello
import com.mezze.wire.Interrupt
import com.mezze.wire.Load
import com.mezze.wire.Loaded
import com.mezze.wire.MessageType
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
import com.mezze.wire.StdoutData
import com.mezze.wire.Welcome
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireProtocolException
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
        "core",
        "exit_code",
        "interrupt",
        "structured_errors",
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

        // Redirect user-visible System.out / System.err to wire frames.
        // The original stderr stays as fd 2 — runtime diagnostics and any
        // pre-redirect log output bypass the protocol.
        val originalStderr = System.err
        val runIdCarrier = AtomicLong(0)
        System.setOut(PrintStream(WireFrameOutputStream(MessageType.STDOUT_DATA, runIdCarrier, ::send), true))
        System.setErr(PrintStream(WireFrameOutputStream(MessageType.STDERR_DATA, runIdCarrier, ::send), true))

        val jitEnabled = System.getenv("MEZZE_JIT")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", jitEnabled.toString())
            .build()

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            // Route user output through our captured System.out/err so it
            // ends up in STDOUT_DATA / STDERR_DATA frames.
            .out(System.out)
            .err(System.err)
            .build()

        val state = ServerState(context, engine, runIdCarrier, jitEnabled)

        try {
            handshake(controlIn, ::send, originalStderr)
            dispatchLoop(controlIn, ::send, state, originalStderr)
        } catch (e: WireProtocolException) {
            // Framing-level failure — best-effort report then exit.
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
                MessageType.LOAD -> handleLoad(frame.decode(), state, send)
                MessageType.PING -> send(WireFrame.of(MessageType.PONG, Pong()))
                MessageType.STATUS -> send(WireFrame.of(MessageType.STATUS_REPLY, state.snapshotStatus()))
                MessageType.INTERRUPT -> handleInterrupt(frame.decode(), state, diag)
                MessageType.CONFIG -> handleConfig(frame.decode(), send)
                MessageType.HELLO -> {
                    tryWriteProtocolError(send, "PROTO_STATE_INVALID", "duplicate HELLO after handshake")
                }
                else -> {
                    // Unknown-in-this-version or capability-gated commands
                    // (EVAL, REF_GET, ATTACH_REQUEST, …). Reject cleanly.
                    tryWriteProtocolError(send, "PROTO_STATE_INVALID", "unsupported in v1 server: ${frame.type}")
                }
            }
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
            } else if (e.isCancelled) {
                sendExited(send, state, code = 130, reason = "interrupted", detail = null)
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

    private fun sendExited(
        send: (WireFrame) -> Unit,
        state: ServerState,
        code: Int,
        reason: String,
        detail: ExitDetail?,
    ) {
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

    fun nextRunId(): Long = runIdCarrier.incrementAndGet()

    fun runStarted(runId: Long) {
        activeRunId = runId
        interrupting = false
    }

    fun runEnded() {
        activeRunId = null
        interrupting = false
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
