package com.mezze.interpreter.standalone

import com.mezze.interpreter.runtime.MezzeExitException
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.wire.Bye
import com.mezze.wire.Cancel
import com.mezze.wire.Config
import com.mezze.wire.ConfigAck
import com.mezze.wire.Eval
import com.mezze.wire.EvalError
import com.mezze.wire.EvalResult
import com.mezze.wire.Evict
import com.mezze.wire.ExitDetail
import com.mezze.wire.Expand
import com.mezze.wire.ExpandResult
import com.mezze.wire.Exited
import com.mezze.wire.Hello
import com.mezze.wire.Interrupt
import com.mezze.wire.Load
import com.mezze.wire.Loaded
import com.mezze.wire.MessageType
import com.mezze.wire.PasswordReply
import com.mezze.wire.PasswordRequest
import com.mezze.wire.RawModeSet
import com.mezze.wire.RawModeUnset
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
        // EVAL / EXPAND / CANCEL. Advertised only now that all three are served
        // — a capability is a promise, and advertising EVAL while EXPAND still
        // answered "unsupported" would make a conforming client fail on the
        // first elided value.
        WireProtocol.Capability.EVAL,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        // Suppress chatty Truffle/polyglot log output UNLESS the user asked
        // for trace-jit — compilation trace events route through the polyglot
        // logger and would be eaten by level=OFF. (#515)
        val envTrace = System.getenv("MEZZE_ENGINE_OPTS")?.contains("Trace") == true
        if (!args.contains("--trace-jit") && !envTrace) {
            System.setProperty("polyglot.log.level", "OFF")
        }

        // Save the real stdout BEFORE redirecting — control frames go here.
        // Anything written to System.out after we redirect gets wrapped in
        // STDOUT_DATA and routed back to the parent.
        // TRANSPORT. `--listen[=<session-file>]` serves one client over a loopback
        // socket instead of the inherited pipes, so a tool that did NOT spawn this
        // process — an editor, a REPL, an agent — can attach to an already warm
        // runtime rather than starting its own and paying the whole startup cost.
        //
        // Default stays stdio: a spawned child's pipes are the OS's business and
        // need no secret. A socket does, and `WireListener` carries one; the
        // handshake below refuses a client that cannot present it.
        val listenArg = args.firstOrNull { it == "--listen" || it.startsWith("--listen=") }
        val listener = if (listenArg == null) null else {
            val sessionPath = listenArg.substringAfter("--listen", "").removePrefix("=")
            com.mezze.wire.WireListener.bind(
                sessionFile = sessionPath.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
            )
        }
        // Printed on the ORIGINAL stderr, before any redirection: a client that was
        // given no session file still needs the port, and stdout is about to become
        // a frame stream.
        if (listener != null) {
            System.err.println("[mezze] listening on 127.0.0.1:${listener.port}")
            if (listenArg!!.startsWith("--listen=")) {
                System.err.println("[mezze] session file: ${listenArg.substringAfter("=")}")
            } else {
                System.err.println("[mezze] token: ${listener.token}")
            }
        }
        val accepted = listener?.accept()

        val controlOut: OutputStream = if (accepted != null) {
            BufferedOutputStream(accepted.getOutputStream())
        } else {
            BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out))
        }
        val controlIn = if (accepted != null) {
            BufferedInputStream(accepted.getInputStream())
        } else {
            BufferedInputStream(System.`in`)
        }
        // Null on stdio, where there is nothing to prove.
        val requiredToken = listener?.token

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
        // When --dap is active, we also tee user output to the DAP client so it
        // shows in VS Code's Debug Console. `dapOutputSink` is set once the DAP
        // bridge exists (below); a no-op until then and when not debugging.
        val dapOutputSink = java.util.concurrent.atomic.AtomicReference<((String, String) -> Unit)?>(null)
        val wireOut = PrintStream(DapTeeOutputStream(
            WireFrameOutputStream(MessageType.STDOUT_DATA, runIdCarrier, ::send), "stdout", dapOutputSink), true)
        val wireErr = PrintStream(DapTeeOutputStream(
            WireFrameOutputStream(MessageType.STDERR_DATA, runIdCarrier, ::send), "stderr", dapOutputSink), true)

        val jitEnabled = System.getenv("MEZZE_JIT")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

        // Detect whether the optimizing Truffle runtime is on the classpath.
        // DefaultTruffleRuntime is the pure-interpreter fallback that ships
        // with truffle-api alone — it doesn't know about engine.Compilation
        // or engine.TraceCompilation, so emitting those options crashes
        // Engine.build() with IllegalArgumentException. The optimizing
        // runtime (truffle-runtime jar) accepts them. Match EmbeddedExec
        // (#195): only emit JIT-control options when the runtime is JIT-
        // capable, so the same wire path works against either runtime on
        // Linux/macOS installs.
        val jitCapable = try {
            val rt = com.oracle.truffle.api.Truffle.getRuntime()
            !rt.javaClass.name.endsWith("DefaultTruffleRuntime")
        } catch (_: Throwable) { true }
        if (jitEnabled && !jitCapable) {
            originalStderr.println(
                "[mezze] MEZZE_JIT=1 ignored — this install has no optimizing " +
                "Truffle runtime (truffle-runtime jar missing from classpath). " +
                "Running interpreter-only."
            )
        }

        // Dev instrumentation flags arrive as command-line args from the
        // parent's WireRunner. Mirror EmbeddedExec's wiring so behavior
        // matches between `mez dev`/`mez exec` and a standalone-jar/native
        // user-app binary. Outputs land under <project>/.mez/profile/ (the
        // wire path; EmbeddedExec uses <cwd>/mezze-*.txt for user-app
        // binaries since they may run from anywhere).
        val devFlags = DevFlags.parse(args, originalStderr)

        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
        if (jitCapable) engineBuilder.option("engine.Compilation", jitEnabled.toString())
        if (devFlags.traceJit && jitCapable) {
            engineBuilder.option("engine.TraceCompilation", "true")
            engineBuilder.option("engine.TraceCompilationDetails", "true")
            // Route trace output to the original stderr (the parent inherits
            // fd 2 from us) instead of through the wire's System.err redirect.
            // Matches EmbeddedExec's .err(System.err) call. (#515)
            engineBuilder.err(originalStderr)
        }
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
        if (devFlags.dap && !jitCapable) {
            originalStderr.println(
                "[mezze] --dap ignored — no JIT-capable runtime. Debugging needs the " +
                "JVM runtime (mez dev/exec on a JDK), not the AOT native binary."
            )
        }
        // NOTE: we deliberately do NOT use GraalVM's bundled dap-tool
        // (`engineBuilder.option("dap", …)`) — it hooks the Context output
        // stream, which IS the wire channel here, corrupting the protocol
        // (PROTO_TOO_LARGE), and NPEs on client connect. Instead we run our own
        // MezzeDapServer (below, after the engine is built) which drives the
        // same GraalVM Debugger over a SEPARATE socket. See
        // docs/lsp/dap-adapter-design.md.
        // MEZZE_ENGINE_OPTS="key1=val1,key2=val2" — arbitrary engine options
        // (mirrors EmbeddedExec). Useful for tuning compilation thresholds,
        // inlining budgets, etc. without recompiling.
        if (jitCapable) {
            var hasTrace = false
            // Aggressive compilation thresholds — same rationale as
            // EmbeddedExec. Mezze's static types stabilize at invocation #1.
            // InliningRecursionDepth=4 lets PE inline 2 levels of recursive
            // calls, enabling escape analysis to scalar-replace Long boxing +
            // Object[] varargs at DirectCallNode.call(). Measured: ~22% on
            // recursive workloads (fib(40) native: 680ms → 530ms).
            if (jitEnabled) {
                engineBuilder.option("engine.FirstTierCompilationThreshold", "16")
                engineBuilder.option("engine.LastTierCompilationThreshold", "100")
                engineBuilder.option("engine.SingleTierCompilationThreshold", "32")
                // OSR: 10K iterations before compiling a running loop
                // (default 100K). FP one-shot loops warm up 10× faster.
                engineBuilder.option("engine.OSRCompilationThreshold", "10000")
                engineBuilder.option("compiler.InliningRecursionDepth", "4")
            }
            System.getenv("MEZZE_ENGINE_OPTS")?.takeIf { it.isNotBlank() }?.let { raw ->
                for (kv in raw.split(",")) {
                    val eq = kv.indexOf('=')
                    if (eq > 0) {
                        val k = kv.substring(0, eq).trim()
                        val v = kv.substring(eq + 1).trim()
                        if (k.contains("Trace")) hasTrace = true
                        // compilation thresholds are set above; MEZZE_ENGINE_OPTS overrides
                        try { engineBuilder.option(k, v) }
                        catch (e: Exception) {
                            originalStderr.println("[mezze] ignoring unknown engine option: $k=$v (${e.message})")
                        }
                    }
                }
            }
            // Route trace output to the original stderr (the parent inherits
            // fd 2), bypassing the wire redirect, so compilation traces are
            // visible in the user's terminal.
            if (hasTrace) engineBuilder.err(originalStderr)
        }
        val engine = engineBuilder.build()

        // Polyglot: enable a guest language iff the project's Mezze.toml
        // declares its `[polyglot.<lang>]` section AND the language's
        // Truffle provider is actually installed on this wire child's
        // classpath. Bare `mez` ships only the Mezze provider — the
        // Python/JS/Wasm engines arrive via user-installed provider
        // packages (`mezze-python`, `mezze-js`, `mezze-wasm`) whose
        // `[jars]` tables `mez sync` puts on this child's classpath.
        // Config lives in Mezze.toml resolved via the `mezze.project.root`
        // system property the parent set when spawning us.
        val projectRoot = System.getProperty("mezze.project.root")?.let { File(it) } ?: File(".")
        val polyglot = com.mezze.interpreter.polyglot.PolyglotManifest.load(projectRoot)

        val installedLanguages = engine.languages.keys
        val requested = buildList {
            if (polyglot.enablePython) add("python")
            if (polyglot.enableJs) add("js")
            if (polyglot.enableWasm) add("wasm")
        }
        val extraLanguages = requested.filter { it in installedLanguages }
        for (missing in requested - extraLanguages.toSet()) {
            originalStderr.println(
                "[mezze] [polyglot.$missing] declared but no provider on the " +
                "classpath — install the mezze-$missing package and rerun `mez sync`."
            )
        }
        val languageIds = (listOf(MezzeLanguage.ID) + extraLanguages).toTypedArray()
        val contextBuilder = Context.newBuilder(*languageIds)
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
        val enablePython = "python" in extraLanguages
        val enableJs = "js" in extraLanguages
        val enableWasm = "wasm" in extraLanguages
        if (enablePython) {
            contextBuilder
                .option("python.PosixModuleBackend", "java")
                .option("python.WarnExperimentalFeatures", "false")
            if (!polyglot.pythonPath.isNullOrBlank()) {
                contextBuilder.option("python.PythonPath", polyglot.pythonPath)
            }
            for ((k, v) in polyglot.pythonOptions) {
                contextBuilder.option("python.$k", v)
            }
        }
        if (enableJs) {
            contextBuilder.option("js.ecmascript-version", "2024")
            // CommonJS require: the user's explicit `node_modules` override,
            // else the JS provider's `runtime_dir` — and only when that
            // directory EXISTS. See PolyglotManifest.commonJsRoot.
            val jsCwd = com.mezze.interpreter.polyglot.PolyglotManifest
                .commonJsRoot(projectRoot, polyglot.jsNodeModules)
            if (jsCwd != null) {
                contextBuilder.option("js.commonjs-require", "true")
                contextBuilder.option("js.commonjs-require-cwd", jsCwd)
            }
            for ((k, v) in polyglot.jsOptions) {
                contextBuilder.option("js.$k", v)
            }
        }
        if (enableWasm) {
            for ((k, v) in polyglot.wasmOptions) {
                contextBuilder.option("wasm.$k", v)
            }
        }
        // Build the FIRST context, but keep the fully-configured builder as a
        // factory so each subsequent run (`mez dev` reload) gets a FRESH context.
        // A long-lived server that reused one context accumulated guest state
        // across reloads — imported Python modules, JS `require` globals, native
        // modules — a resource leak that eventually stack-overflowed on reload.
        // The Engine (JIT'd CallTargets) stays shared; only the Context is
        // per-run. See ServerState.resetContextForNewRun.
        val contextFactory: () -> Context = { contextBuilder.build() }
        val context = contextFactory()

        val state = ServerState(context, contextFactory, engine, runIdCarrier, jitEnabled)
        // Stash the declared guest languages so the eval worker can
        // auto-warm them on ITS thread before the first user eval.
        // (Warming here on `main` would pin GraalJS to the wrong
        // thread — user code runs on the mez-runtime-eval single-
        // thread executor, not on RuntimeServer.main.)
        state.polyglotLanguagesToWarm = extraLanguages

        // Hand-written DAP debugger. When --dap is set (JIT runtime only), start
        // our MezzeDapServer on a SEPARATE socket and a GraalDapBridge that
        // drives the GraalVM Debugger. A latch gates program execution until the
        // client has attached + finished configuration (so breakpoints are
        // installed first). See docs/lsp/dap-adapter-design.md.
        if (devFlags.dap && !jitCapable) {
            originalStderr.println(
                "[mezze] --dap ignored — no JIT-capable runtime. Debugging needs " +
                "the JVM runtime (mez dev/exec on a JDK), not the AOT native binary."
            )
        }
        if (devFlags.dap && jitCapable) {
            // #356 — BDSL bytecode has no debugger instrumentation; force off
            // so scopes/breakpoints/stepping keep working on the AST path.
            com.mezze.interpreter.runtime.MezzeLanguage.enableBdslDebugInstrumentation()
            val bridge = GraalDapBridge(engine) { if (System.getenv("MEZZE_DEBUG") != null) originalStderr.println(it) }
            bridge.stopOnEntry = devFlags.dapBrk
            val attached = java.util.concurrent.CountDownLatch(1)
            val dapServer = MezzeDapServer(
                port = devFlags.dapPort,
                onLaunch = { attached.countDown() },
                bridge = bridge,
                log = { if (System.getenv("MEZZE_DEBUG") != null) originalStderr.println(it) },
            )
            bridge.server = dapServer
            state.dapBridge = bridge
            state.dapAttachLatch = attached
            // Tee program output to the Debug Console.
            dapOutputSink.set { text, category -> bridge.onProgramOutput(text, category) }
            Thread({ try { dapServer.serve() } catch (_: Exception) {} }, "mezze-dap")
                .apply { isDaemon = true; start() }
            originalStderr.println(
                "[mezze] DAP server listening on port ${devFlags.dapPort} — attach a DAP client (VS Code)"
            )
        }

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

        // Wire raw-mode toggles. Parent owns the terminal; child asks via
        // RAW_MODE_SET / RAW_MODE_UNSET frames. Best-effort: if the wire
        // is dead, the request is dropped and the next read just sees
        // cooked-mode bytes.
        StdinNatives.installWireRawModeHooks(
            /* setter   */ {
                try { send(WireFrame.of(MessageType.RAW_MODE_SET, RawModeSet())) }
                catch (_: Exception) {}
            },
            /* unsetter */ {
                try { send(WireFrame.of(MessageType.RAW_MODE_UNSET, RawModeUnset())) }
                catch (_: Exception) {}
            },
        )

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
            handshake(controlIn, ::send, diag, requiredToken)
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
            // Close the CURRENT context (state.context may have been swapped by a
            // dev reload; the local `context` is only the startup one).
            try { state.context.close(true) } catch (_: Exception) {}
            try { send(WireFrame.of(MessageType.BYE, Bye())) } catch (_: Exception) {}
        }
    }

    // ─── Handshake ───────────────────────────────────────────────────

    private fun handshake(
        input: java.io.InputStream,
        send: (WireFrame) -> Unit,
        diag: PrintStream,
        /**
         * The secret a client must present, when the transport is a socket.
         *
         * Null on stdio: those pipes were inherited from the process that forked
         * this one, so the OS already decided who may write to them.
         */
        requiredToken: String? = null,
    ) {
        val first = WireFrameCodec.read(input)
            ?: throw WireProtocolException("PROTO_DECODE", "EOF before HELLO")
        if (first.type != MessageType.HELLO) {
            throw WireProtocolException("PROTO_STATE_INVALID", "expected HELLO first, got ${first.type}")
        }
        val hello = first.decode<Hello>()

        // AUTH FIRST — before the terminal snapshot, before version negotiation,
        // before anything this frame says is allowed to affect the runtime. An
        // unauthenticated client must not be able to reach any behaviour at all,
        // and this protocol can LOAD and RUN code.
        if (requiredToken != null &&
            !com.mezze.wire.WireListener.tokenMatches(requiredToken, hello.token)
        ) {
            send(WireFrame.of(MessageType.REJECT, com.mezze.wire.Reject(
                reason = "unauthorized",
                // Deliberately says nothing about the expected value, its length, or
                // whether a token was supplied at all.
                detail = "this runtime is listening on a socket and requires the session token",
            )))
            throw WireProtocolException("PROTO_UNAUTHORIZED", "client presented no valid session token")
        }
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
                        // Eagerly bump the runId carrier here in the
                        // dispatch loop (not in handleLoad on the eval
                        // worker), so any STDIN_DATA for this run that
                        // arrives in the dispatch loop before handleLoad
                        // gets a chance to setActiveRunId is correctly
                        // attributed via routeStdin's "expected next runId"
                        // check (#243 Race C).
                        val runId = state.nextRunId()
                        // Hand off to worker so we can keep reading frames
                        // (including STDIN_DATA, INTERRUPT) concurrently.
                        evalWorker.submit { handleLoad(load, runId, state, send) }
                    }
                    MessageType.EVAL -> {
                        // Onto the same single-thread worker LOAD uses, so the
                        // dispatch loop stays free to read CANCEL while an
                        // expression runs — a cancel that queued behind the eval
                        // it was meant to stop could never arrive in time.
                        val eval = frame.decode<Eval>()
                        evalWorker.submit { handleEval(eval, state, send) }
                    }
                    // Not submitted to the worker: an EXPAND walks a value that is
                    // already computed, so it needs no context and must not queue
                    // behind a running expression.
                    MessageType.EXPAND -> handleExpand(frame.decode(), send)
                    MessageType.CANCEL -> handleCancel(frame.decode(), state, diag)
                    MessageType.PING -> send(WireFrame.of(MessageType.PONG, Pong()))
                    MessageType.STATUS -> send(WireFrame.of(MessageType.STATUS_REPLY, state.snapshotStatus()))
                    MessageType.INTERRUPT -> handleInterrupt(frame.decode(), state, diag)
                    MessageType.SIGNAL -> handleSignal(frame.decode(), state, diag)
                    MessageType.CONFIG -> handleConfig(frame.decode(), send)
                    MessageType.STDIN_DATA -> {
                        val data = frame.decode<StdinData>()
                        routeStdin(state, data.runId, data.bytes, false, diag)
                    }
                    MessageType.STDIN_EOF -> {
                        val eof = frame.decode<StdinEof>()
                        routeStdin(state, eof.runId, ByteArray(0), true, diag)
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

    /**
     * Route a STDIN_DATA or STDIN_EOF frame into the active run's buffer,
     * dropping bytes intended for a stale (already-finished) run (#243).
     *
     * Acceptance rule: a frame with runId R is accepted when R == the
     * latest LOAD-assigned runId on this child (state.peekLatestRunId()).
     * The dispatch loop bumps the carrier as soon as LOAD arrives, so:
     *   - bytes that left the parent before the LOAD they're meant for
     *     also carry the new runId (parent bumps before sending LOAD)
     *   - bytes for a prior run that arrive after we've seen the new LOAD
     *     get dropped as stale
     *   - legacy frames with runId=0 (pre-tagging client) are always
     *     accepted, for back-compat
     */
    private fun routeStdin(
        state: ServerState,
        runId: Long,
        bytes: ByteArray,
        isEof: Boolean,
        diag: PrintStream,
    ) {
        val latest = state.peekLatestRunId()
        if (runId == 0L || runId == latest) {
            if (isEof) state.stdinBuffer?.pushEof()
            else state.stdinBuffer?.push(bytes)
        } else {
            if (System.getenv("MEZZE_DEBUG") != null) {
                diag.println("[RuntimeServer] dropped stale stdin (run $runId, latest $latest)")
            }
        }
    }

    private fun handleLoad(load: Load, runId: Long, state: ServerState, send: (WireFrame) -> Unit) {
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

        // runId was assigned in the dispatch loop (in advance of this
        // worker starting) so STDIN_DATA frames for the same run can be
        // correctly attributed. See dispatch's LOAD handler.
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

        // Entry-point identity = content hash (never a name). meta.entryHash is
        // the entry function's content-hash hex, packed at build/pack time.
        val entryHash = meta.entryHash
        try {
            if (entryHash.isNotEmpty()) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, meta.performerRecords, state.projectRoot(), entryHash)
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

        // Re-arm the demand-request flag so the first stdin read in this
        // run triggers a fresh STDIN_REQUEST to the parent. Note: we no
        // longer reset the buffer — runId-tagged STDIN_DATA frames are
        // already filtered by the dispatch loop's routeStdin, so any
        // bytes still in the buffer here belong to this run (they arrived
        // after the parent's currentRunId was bumped for this LOAD).
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

        // DAP: block until the debug client has attached + configured (so
        // breakpoints are installed before anything runs), then start the debug
        // session. `configurationDone` fires the latch via onLaunch.
        state.dapAttachLatch?.let { latch ->
            state.dapBridge?.let { bridge ->
                latch.await()
                bridge.start()
            }
        }

        // Auto-warm each declared guest language on THIS thread (the
        // eval worker) before user code runs. GraalJS is single-
        // threaded per Context — first thread in "owns" it, and later
        // access from a different thread trips the multi-threaded-
        // access guard ("not allowed for language(s) js"). NumPy's
        // native workers touching the Context first is the usual
        // trigger. A no-op eval per language stakes the claim on the
        // right thread. Once per Context — flip a flag so subsequent
        // LOADs don't repeat the work.
        // Per-run context isolation: on every run AFTER the first (a `mez dev`
        // reload), swap in a fresh Context so guest state (Python modules, JS
        // require globals, native modules) doesn't accumulate across reloads.
        // The first run uses the context built at startup. resetContextForNewRun
        // clears polyglotWarmed so the fresh context re-warms below.
        state.maybeResetContextForNewRun()

        if (!state.polyglotWarmed && state.polyglotLanguagesToWarm.isNotEmpty()) {
            warmupPolyglotLanguages(state.context, state.polyglotLanguagesToWarm)
            state.polyglotWarmed = true
        }

        var dapExitCode = 0
        try {
            state.context.eval(source)
            sendExited(send, state, code = 0, reason = "normal", detail = null)
        } catch (e: PolyglotException) {
            if (e.isExit) {
                dapExitCode = e.exitStatus
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
                dapExitCode = code
                sendExited(send, state, code = code, reason = reason, detail = null)
            } else {
                dapExitCode = 1
                sendExited(send, state, code = 1, reason = "error", detail = exitDetailFor(e))
            }
        } catch (e: Exception) {
            dapExitCode = 1
            sendExited(send, state, code = 1, reason = "error", detail = ExitDetail(
                message = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            ))
        } finally {
            // DAP: tell the client the debuggee finished so VS Code closes the
            // session (otherwise it hangs "running"). exited carries the code;
            // terminated ends the session.
            state.dapBridge?.onProgramExit(dapExitCode)
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

    // ─── Expression evaluation ───────────────────────────────────────
    //
    // EVAL is LOAD's sibling: the same compiled-IR closure, run the same way,
    // but the answer is the entry's RESULT as a value tree rather than console
    // bytes and an exit code.
    //
    // Two pieces already existed and are what make this small. `MezzeLanguage`
    // wraps every top-level body in a ResultCapturingNode, which stores the raw
    // guest result (forcing a MezzeSuspend on the way) — so the value is
    // reachable without unwrapping a polyglot Value, which cannot be done for a
    // guest object. And the static shape the renderer needs rides in the meta
    // sidecar as `resultPrintType`, the same channel `entryHash` uses.

    /** Where an [Expand] resolves from: the last result, kept with its shape. */
    private class PinnedResult(
        val requestId: Long,
        val value: Any?,
        val printType: com.mezze.language.truffleir.PrintType,
    )

    /**
     * One pinned result, so an [Expand] has something to descend.
     *
     * ONE, not a cache keyed by request: a pin holds a whole live value tree
     * alive, and an unbounded map of them is a leak that looks like a feature.
     * Re-deriving instead of pinning would be free for a pure expression and
     * impossible for an effectful one — the effect row says which, statically —
     * so choosing per-expression is the eventual answer, and needs the effect
     * row on the wire first. Until then: the most recent result expands, and an
     * older one answers EXPIRED rather than silently resolving against the wrong
     * value.
     */
    @Volatile
    private var pinned: PinnedResult? = null

    /** Requests cancelled before or during their run. */
    private val cancelledRequests = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** The request the eval worker is running, for [handleCancel]. */
    @Volatile
    private var activeEvalRequest: Long? = null

    private fun evalFailed(requestId: Long, kind: String, message: String): WireFrame =
        WireFrame.of(MessageType.EVAL_RESULT, EvalResult(
            requestId = requestId,
            error = EvalError(kind = kind, detail = ExitDetail(message = message)),
        ))

    /** Everything [handleEval] needs before it may touch the Context, or why not. */
    internal sealed interface EvalPrep {
        data class Ready(
            val functions: List<FunctionIR>,
            val meta: StoreSerialization.DeserializedEmbeddedAppMeta,
            val printType: com.mezze.language.truffleir.PrintType,
        ) : EvalPrep

        data class Refused(val kind: String, val message: String) : EvalPrep
    }

    /**
     * Read and check an [Eval] request without running anything.
     *
     * Split out because every refusal here is decided from the request and two
     * files alone — no Context, no guest thread, nothing to warm up — so keeping
     * it separate is what makes those paths testable. It also puts the whole
     * "which error is this" decision in one place instead of interleaved with the
     * evaluation.
     */
    internal fun prepareEval(eval: Eval): EvalPrep {
        val fnsFile = File(eval.fnsCborPath)
        val metaFile = File(eval.metaCborPath)
        if (!fnsFile.exists() || !metaFile.exists()) {
            val missing = if (!fnsFile.exists()) fnsFile else metaFile
            return EvalPrep.Refused(EvalError.Kind.NOT_FOUND, "not found: ${missing.absolutePath}")
        }

        val functions: List<FunctionIR>
        val meta: StoreSerialization.DeserializedEmbeddedAppMeta
        try {
            functions = StoreSerialization.deserializeFunctionIRList(fnsFile.readBytes())
            meta = StoreSerialization.deserializeEmbeddedAppMeta(metaFile.readBytes())
        } catch (e: Exception) {
            return EvalPrep.Refused(
                EvalError.Kind.DECODE_FAILED,
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            )
        }

        if (functions.isEmpty() || meta.entryHash.isEmpty()) {
            return EvalPrep.Refused(
                EvalError.Kind.NO_ENTRY,
                "the closure names no entry, so there is no expression to evaluate",
            )
        }

        // No shape means no rendering. Refused rather than guessed: rendering from
        // the runtime value alone silently loses exactly the distinctions the shape
        // exists to carry — a Nat printed as an Int, a newtype printed as its
        // payload — and the caller would have no way to tell it happened.
        val printType = meta.resultPrintType
            ?: return EvalPrep.Refused(
                EvalError.Kind.UNRENDERABLE,
                "the metadata carried no result shape, so the value cannot be rendered; " +
                    "an EVAL closure must set resultPrintType",
            )

        return EvalPrep.Ready(functions, meta, printType)
    }

    /**
     * The reply for a value that evaluated successfully.
     *
     * Separate from [handleEval] so the frame a caller actually receives can be
     * asserted without standing up a Context.
     */
    internal fun evalResultFrame(
        requestId: Long,
        result: Any?,
        printType: com.mezze.language.truffleir.PrintType,
        maxDepth: Int,
        maxItems: Int,
        elapsedMicros: Long,
    ): WireFrame = WireFrame.of(MessageType.EVAL_RESULT, EvalResult(
        requestId = requestId,
        value = com.mezze.interpreter.nodes.builtin.effects.InspectTree.build(
            result, printType, dbgStyle = true, maxDepth = maxDepth, maxItems = maxItems,
        ),
        elapsedMicros = elapsedMicros,
        // S-23: drained, not peeked, so the next evaluation starts clean. Empty
        // unless the client shipped instrumented IR — only `mez sample` does.
        branchHits = com.mezze.interpreter.nodes.builtin.effects.CoverageNatives.takeHits(),
    ))

    private fun handleEval(eval: Eval, state: ServerState, send: (WireFrame) -> Unit) {
        val started = System.nanoTime()

        // A cancel that arrived while this sat in the worker queue. Checked
        // before any work, so a client that cancels immediately pays nothing.
        if (cancelledRequests.remove(eval.requestId)) {
            send(evalFailed(eval.requestId, EvalError.Kind.CANCELLED, "cancelled before it started"))
            return
        }

        val prep = prepareEval(eval)
        if (prep is EvalPrep.Refused) {
            send(evalFailed(eval.requestId, prep.kind, prep.message))
            return
        }
        val (functions, meta, printType) = prep as EvalPrep.Ready

        try {
            MezzeLanguage.setFunctionsForDirectExecution(
                functions, meta.performerRecords, state.projectRoot(), meta.entryHash,
            )
        } catch (e: Exception) {
            send(evalFailed(
                eval.requestId, EvalError.Kind.DECODE_FAILED,
                "register failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            ))
            return
        }

        try {
            MezzeLanguage.setFunctionsForDirectExecution(
                functions, meta.performerRecords, state.projectRoot(), meta.entryHash,
            )
        } catch (e: Exception) {
            send(evalFailed(
                eval.requestId, EvalError.Kind.DECODE_FAILED,
                "register failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            ))
            return
        }

        val source = Source.newBuilder(
            MezzeLanguage.ID,
            "# RuntimeServer eval ${eval.requestId}",
            "__runtime_server_eval_${eval.requestId}__.mz",
        ).buildLiteral()

        // Cleared first: a stale result from an earlier run would otherwise be
        // indistinguishable from this expression returning that same value.
        MezzeLanguage.clearLastResult()
        activeEvalRequest = eval.requestId
        val timeoutWatchdog = armEvalTimeout(eval, state)
        try {
            state.context.eval(source)
            val result = MezzeLanguage.getLastExecutionResult()
            val frame = evalResultFrame(
                requestId = eval.requestId,
                result = result,
                printType = printType,
                maxDepth = eval.maxDepth,
                maxItems = eval.maxItems,
                elapsedMicros = (System.nanoTime() - started) / 1_000,
            )
            // Pinned only after the tree built: a value whose shape does not
            // describe it must not become the thing an EXPAND descends.
            pinned = PinnedResult(eval.requestId, result, printType)
            send(frame)
        } catch (e: PolyglotException) {
            // A cancel and a timeout both arrive here as an interrupted
            // PolyglotException, so which one it was is decided by what we
            // recorded, not by the exception.
            val kind = when {
                cancelledRequests.remove(eval.requestId) -> EvalError.Kind.CANCELLED
                (e.isCancelled || e.isInterrupted) && timeoutWatchdog.fired -> EvalError.Kind.TIMEOUT
                e.isCancelled || e.isInterrupted -> EvalError.Kind.CANCELLED
                else -> EvalError.Kind.FAILED
            }
            send(WireFrame.of(MessageType.EVAL_RESULT, EvalResult(
                requestId = eval.requestId,
                error = EvalError(kind, exitDetailFor(e)),
                elapsedMicros = (System.nanoTime() - started) / 1_000,
                // S-23: a crashed evaluation still REACHED the branches that led to
                // the crash. Dropping them here would hide the path to it, which is
                // the one thing a reader of a failing row most wants.
                branchHits = com.mezze.interpreter.nodes.builtin.effects.CoverageNatives.takeHits(),
            )))
        } catch (e: com.mezze.interpreter.nodes.builtin.effects.InspectTree.ShapeMismatchException) {
            // The shape does not describe the value — a compiler bug, not the
            // expression failing. Reported as its own kind so it is not mistaken
            // for one.
            send(evalFailed(eval.requestId, EvalError.Kind.UNRENDERABLE, e.message ?: "unrenderable"))
        } catch (e: Exception) {
            send(evalFailed(
                eval.requestId, EvalError.Kind.FAILED,
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            ))
        } finally {
            timeoutWatchdog.disarm()
            activeEvalRequest = null
            cancelledRequests.remove(eval.requestId)
        }
    }

    private class EvalTimeout(private val timer: java.util.Timer?) {
        @Volatile var fired: Boolean = false
        fun disarm() = timer?.cancel()
        fun markFired() { fired = true }
    }

    /**
     * Interrupt the context when `timeout_ms` elapses.
     *
     * Context-wide, because that is the only interrupt Truffle offers — which is
     * also why the worker runs one expression at a time. Serving several
     * concurrently needs a context per request, i.e. the world/context refactor.
     */
    private fun armEvalTimeout(eval: Eval, state: ServerState): EvalTimeout {
        if (eval.timeoutMs <= 0) return EvalTimeout(null)
        val timer = java.util.Timer("mez-eval-timeout-${eval.requestId}", true)
        val handle = EvalTimeout(timer)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                handle.markFired()
                try {
                    state.context.interrupt(java.time.Duration.ofSeconds(5))
                } catch (_: Exception) {
                    // The expression finished between the deadline and here.
                }
            }
        }, eval.timeoutMs)
        return handle
    }

    private fun handleExpand(expand: Expand, send: (WireFrame) -> Unit) {
        fun fail(kind: String, message: String) = send(
            WireFrame.of(MessageType.EXPAND_RESULT, ExpandResult(
                requestId = expand.requestId,
                path = expand.path,
                error = EvalError(kind, ExitDetail(message = message)),
            ))
        )

        val held = pinned
        if (held == null || held.requestId != expand.fromRequest) {
            fail(
                EvalError.Kind.EXPIRED,
                "request ${expand.fromRequest} is no longer held; only the most recent " +
                    "result can be expanded",
            )
            return
        }
        try {
            val tree = com.mezze.interpreter.nodes.builtin.effects.InspectTree.expand(
                held.value, held.printType, expand.path,
                dbgStyle = true, maxDepth = expand.maxDepth, maxItems = expand.maxItems,
            )
            send(WireFrame.of(MessageType.EXPAND_RESULT, ExpandResult(
                requestId = expand.requestId, path = expand.path, value = tree,
            )))
        } catch (e: com.mezze.interpreter.nodes.builtin.effects.InspectTree.NoSuchPathException) {
            fail(EvalError.Kind.NO_SUCH_PATH, e.message ?: "no such path")
        } catch (e: Exception) {
            fail(EvalError.Kind.UNRENDERABLE, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    /**
     * Drop one in-flight or queued request.
     *
     * Recorded rather than acted on directly when the request is not the running
     * one: interrupting the context would kill whatever IS running. A cancel for
     * an unknown or already-finished request is not an error — the reply may
     * already be on the wire — so it is recorded and consumed by whichever path
     * sees it first.
     */
    private fun handleCancel(cancel: Cancel, state: ServerState, diag: PrintStream) {
        cancelledRequests.add(cancel.requestId)
        if (activeEvalRequest == cancel.requestId) {
            diag.println("[RuntimeServer] CANCEL eval request ${cancel.requestId}")
            try {
                state.context.interrupt(java.time.Duration.ofSeconds(5))
            } catch (e: Exception) {
                diag.println("[RuntimeServer] cancel interrupt failed: ${e.message}")
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * Evaluate a minimal no-op snippet in each declared guest language on
     * the current thread. Called once at the top of the first [handleLoad]
     * (on the eval worker) so single-threaded guests like GraalJS pin the
     * right thread as their owner BEFORE user code — and specifically
     * before a language like NumPy spins up its own native workers that
     * would otherwise stake the claim on the wrong thread.
     *
     * Silently swallows any failure — the warmup snippet must never crash
     * Context boot. INFO-level trace only under [MEZZE_DEBUG] to keep
     * regular runs quiet. WASM is intentionally skipped: no source-string
     * eval surface, and it isn't affected by the same thread-ownership
     * rule.
     */
    private fun warmupPolyglotLanguages(context: Context, languages: List<String>) {
        val debug = System.getenv("MEZZE_DEBUG") != null
        for (lang in languages) {
            val snippet = when (lang) {
                "js" -> "1"
                "python" -> "1"
                else -> continue
            }
            try {
                context.eval(lang, snippet)
                if (debug) System.err.println("[mezze] polyglot: warmed $lang")
            } catch (_: Throwable) {
                // Best-effort — a bad snippet or provider hiccup here
                // should surface at the user's first real eval with a
                // clearer message from GraalVM.
            }
        }
    }

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
    context: Context,
    private val contextFactory: () -> Context,
    val engine: Engine,
    private val runIdCarrier: AtomicLong,
    private val jitEnabled: Boolean,
) {
    /**
     * The live polyglot Context. A `var` so a fresh one can replace it per run
     * — see [resetContextForNewRun]. The old context's guest state (imported
     * modules, JS globals, native modules) must not leak into the next run.
     */
    @Volatile var context: Context = context
        private set

    /** True until the first run consumes the startup context. */
    @Volatile private var startupContextUnused: Boolean = true

    /**
     * On the FIRST run, keep the context built at startup (already fresh). On
     * every subsequent run (a `mez dev` reload), discard the current Context and
     * build a fresh one (reusing the shared Engine), so guest state doesn't
     * accumulate across reloads — imported Python modules, JS `require` globals.
     * The Engine (JIT'd CallTargets) is preserved.
     *
     * EXCEPTION, and it is narrower than "a guest language is active". A guest
     * that loaded a native C extension (e.g. numpy's _multiarray_umath.so) cannot
     * have that module re-loaded into a second Context in the same process ON
     * macOS — GraalPy would need to patch the Mach-O ("Modifying Mach-O files is
     * not yet supported"), and isolating native modules per-context also fails
     * there. That is the whole of the reason, and it names one OS and one
     * language: GraalJS loads no native modules anywhere, and on Linux/Windows
     * GraalPy isolates them per-context. The carve-out used to fire for ANY guest
     * language on ANY OS, so a JS-only reload — or any polyglot reload on Linux —
     * accumulated guest state until "Resource exhausted: Stack overflow", for a
     * macOS-and-GraalPy reason that did not apply to it.
     *
     * Everything else gets a fresh Context each run (the Engine, and its JIT'd
     * CallTargets, is preserved). Best-effort close of the old context — a wedged
     * guest shouldn't block the next run.
     *
     * VERIFIED end to end on Linux with GraalJS, by A/B against the old
     * condition. A `mez dev` watch project whose app evaluates
     * `globalThis.mez_runs = (globalThis.mez_runs || 0) + 1` and prints it,
     * across an initial run plus three reloads:
     *
     *     old `if (polyglotLanguagesToWarm.isNotEmpty()) return`   1, 2, 3, 4
     *     this condition                                           1, 1, 1, 1
     *
     * So the guest state really did accumulate for a language that loads no
     * native modules, and it no longer does. The macOS+GraalPy behaviour is
     * BYTE-IDENTICAL to what it was, and is the one case not reachable here.
     */
    fun maybeResetContextForNewRun() {
        if (startupContextUnused) {
            startupContextUnused = false
            return
        }
        if (nativeModulesPinTheContext()) return
        val old = context
        context = contextFactory()
        polyglotWarmed = false  // the new context must re-warm its guest languages
        try { old.close(true) } catch (_: Exception) {}
    }

    /**
     * True only where a guest's NATIVE modules make a second Context unsafe:
     * GraalPy on macOS. See [maybeResetContextForNewRun] for why that is the
     * entire rule, and why it is not "any guest language".
     */
    private fun nativeModulesPinTheContext(): Boolean {
        if ("python" !in polyglotLanguagesToWarm) return false
        return System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
    }
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
    /** DAP debugger bridge + attach latch, set when --dap is active. */
    @Volatile var dapBridge: GraalDapBridge? = null
    @Volatile var dapAttachLatch: java.util.concurrent.CountDownLatch? = null
    /** Wire-fed stdin buffer; reset between runs. */
    var stdinBuffer: WireStdinBuffer? = null
    /**
     * Guest languages the manifest asked for that this engine actually
     * has installed — the eval worker warms each one on its own thread
     * before the first `context.eval` so GraalJS's single-thread lock
     * pins the RIGHT thread (see [warmupPolyglotLanguages]).
     */
    @Volatile var polyglotLanguagesToWarm: List<String> = emptyList()
    /** Set once the eval worker has run [warmupPolyglotLanguages]. */
    @Volatile var polyglotWarmed: Boolean = false
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

    /**
     * Latest runId the dispatch loop has assigned to a LOAD, whether or
     * not handleLoad has begun executing it yet. Used by routeStdin to
     * decide if incoming stdin frames are for "the current/upcoming run"
     * (accept) or "a stale prior run" (drop). See #243.
     */
    fun peekLatestRunId(): Long = runIdCarrier.get()

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

/**
 * Wraps a [WireFrameOutputStream]: passes every write to the wire (so the
 * parent terminal still gets it), and — when a DAP sink is set — ALSO forwards
 * the text to the DAP client as an `output` event (Debug Console). The sink is
 * null unless --dap is active, so there's no cost on the normal path.
 */
private class DapTeeOutputStream(
    private val delegate: OutputStream,
    private val category: String,
    private val sink: java.util.concurrent.atomic.AtomicReference<((String, String) -> Unit)?>,
) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        if (len > 0) {
            sink.get()?.let { forward ->
                try { forward(String(b, off, len, Charsets.UTF_8), category) } catch (_: Exception) {}
            }
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

// ─── Dev instrumentation flags ───────────────────────────────────────

/**
 * Parsed dev-instrumentation flags. Mirrors what EmbeddedExec accepts
 * so behavior matches between `mez dev` / `mez exec` (wire path) and
 * a standalone-jar / native user-app binary.
 *
 * Outputs land under <project>/.mez/profile/ — derived from the
 * `mezze.project.root` system property the parent sets when spawning.
 * Falls back to cwd if for some reason that property isn't set.
 */
private data class DevFlags(
    val inspect: Boolean,
    val inspectBrk: Boolean,
    val dap: Boolean,
    val dapBrk: Boolean,
    val dapPort: Int,
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
            // `--dap` / `--dap=<port>` / `--dap-brk`: GraalVM Debug Adapter
            // Protocol server so VS Code (or any DAP client) can attach for
            // breakpoints / stepping. `--dap-brk` suspends on entry so the client
            // can install breakpoints before anything runs.
            val dap = args.contains("--dap") || args.any { it.startsWith("--dap=") } || args.contains("--dap-brk")
            val dapBrk = args.contains("--dap-brk")
            val dapPort = args.firstNotNullOfOrNull { a ->
                if (a.startsWith("--dap=")) a.substringAfter("=").toIntOrNull() else null
            } ?: 4711
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
            return DevFlags(inspect, inspectBrk, dap, dapBrk, dapPort, traceJit, profile, coverage, allocations, insight)
        }

        private fun profileOut(name: String): File {
            val root = System.getProperty("mezze.project.root")?.let { File(it) } ?: File(".")
            val dir = com.mezze.language.store.ProjectLayout.profileDir(root)
            dir.mkdirs()
            return File(dir, name)
        }
    }
}
