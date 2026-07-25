package com.mezze.interpreter.standalone

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A hand-written Debug Adapter Protocol (DAP) server for Mezze.
 *
 * It drives GraalVM's `Debugger` engine for all actual debugging (breakpoints,
 * stepping, stack frames, scopes, variables, watch-eval) and speaks DAP over
 * its OWN TCP socket — replacing ONLY GraalVM's `dap-tool`, which is unusable
 * in mez's wire-protocol embedding (the tool hooks the Context output stream,
 * which IS the wire channel → PROTO_TOO_LARGE; plus a connect-time NPE).
 *
 * Architecture (see docs/lsp/dap-adapter-design.md):
 *   - Lives in RuntimeServer (the JVM execute-side child), where the Context +
 *     Debugger already are. The CLI stays interpreter-free.
 *   - Opens a SEPARATE socket; never touches the wire stdio. That's the whole
 *     difference from the broken dap-tool.
 *
 * This file is Phase 1: socket + Content-Length framing + the
 * initialize/launch/configurationDone/disconnect handshake. The debugger-driving
 * requests (setBreakpoints, stackTrace, scopes, variables, stepping, evaluate)
 * are wired in later phases via [DapDebuggerBridge].
 */
class MezzeDapServer(
    private val port: Int,
    /** Called once the client has completed launch+configurationDone and the
     *  program should start running. Phase 1 default is a no-op; RuntimeServer
     *  supplies the real "begin execution" trigger. */
    private val onLaunch: () -> Unit = {},
    /** Bridge to the GraalVM Debugger. Null in Phase 1 / handshake-only tests. */
    private val bridge: DapDebuggerBridge? = null,
    private val log: (String) -> Unit = {},
) {
    private val seq = AtomicInteger(1)
    @Volatile private var out: OutputStream? = null
    @Volatile private var running = true

    /** The bound port (useful when [port] was 0 = ephemeral, in tests). */
    @Volatile var boundPort: Int = port
        private set

    private var serverSocket: ServerSocket? = null

    /**
     * Bind the port and accept ONE client (a debug session is 1:1 with a run).
     * Blocks in the message loop until the client disconnects or [stop] is
     * called. Returns when the session ends.
     */
    fun serve() {
        val ss = ServerSocket(port)
        serverSocket = ss
        boundPort = ss.localPort
        log("[dap] listening on port $boundPort")
        ss.use {
            val socket = ss.accept()
            log("[dap] client connected")
            handleClient(socket)
        }
    }

    /** Stop accepting / close the socket (for tests + disconnect teardown). */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            out = socket.getOutputStream()
            while (running) {
                val msg = DapWire.readMessage(input) ?: break
                try {
                    dispatch(msg)
                } catch (e: Exception) {
                    log("[dap] dispatch error: ${e.message}")
                }
            }
            log("[dap] client loop ended")
        }
    }

    // ─── Request dispatch ────────────────────────────────────────────────

    private fun dispatch(msg: Map<String, Any?>) {
        val type = msg["type"] as? String
        if (type != "request") return
        val command = msg["command"] as? String ?: return
        val reqSeq = (msg["seq"] as? Number)?.toInt() ?: 0
        val args = msg["arguments"] as? Map<String, Any?> ?: emptyMap()

        when (command) {
            "initialize" -> {
                respond(command, reqSeq, capabilities())
                // Per DAP: after replying to initialize, send `initialized` so
                // the client sends its setBreakpoints / configurationDone.
                sendEvent("initialized", null)
            }
            "launch", "attach" -> {
                respond(command, reqSeq, null)
            }
            "configurationDone" -> {
                respond(command, reqSeq, null)
                // The program starts now (breakpoints are installed).
                onLaunch()
            }
            "setBreakpoints" -> {
                respond(command, reqSeq, handleSetBreakpoints(args))
            }
            "threads" -> {
                // One synthetic thread; Mezze fibers are a follow-up.
                respond(command, reqSeq, mapOf(
                    "threads" to listOf(mapOf("id" to 1, "name" to "main"))
                ))
            }
            "stackTrace" -> respond(command, reqSeq, bridge?.stackTrace() ?: emptyStack())
            "scopes" -> respond(command, reqSeq, bridge?.scopes(args) ?: mapOf("scopes" to emptyList<Any>()))
            "variables" -> respond(command, reqSeq, bridge?.variables(args) ?: mapOf("variables" to emptyList<Any>()))
            "evaluate" -> respond(command, reqSeq, bridge?.evaluate(args) ?: mapOf("result" to "", "variablesReference" to 0))
            "continue" -> {
                respond(command, reqSeq, mapOf("allThreadsContinued" to true))
                bridge?.resume(DapDebuggerBridge.StepKind.CONTINUE)
                sendEvent("continued", mapOf("threadId" to 1, "allThreadsContinued" to true))
            }
            "next" -> { respond(command, reqSeq, null); bridge?.resume(DapDebuggerBridge.StepKind.OVER) }
            "stepIn" -> { respond(command, reqSeq, null); bridge?.resume(DapDebuggerBridge.StepKind.INTO) }
            "stepOut" -> { respond(command, reqSeq, null); bridge?.resume(DapDebuggerBridge.StepKind.OUT) }
            "disconnect", "terminate" -> {
                respond(command, reqSeq, null)
                bridge?.disconnect()
                stop()
            }
            else -> {
                // Unknown request: reply unsuccessfully so the client isn't left
                // waiting, but keep the session alive.
                respondError(command, reqSeq, "unsupported request: $command")
            }
        }
    }

    private fun capabilities(): Map<String, Any?> = mapOf(
        "supportsConfigurationDoneRequest" to true,
        "supportsEvaluateForHovers" to true,
        "supportsTerminateRequest" to true,
        "supportsConditionalBreakpoints" to true,
        "supportsLogPoints" to true,
        "supportsSingleThreadExecutionRequests" to false,
    )

    private fun handleSetBreakpoints(args: Map<String, Any?>): Map<String, Any?> {
        val b = bridge ?: return mapOf("breakpoints" to emptyList<Any>())
        return b.setBreakpoints(args)
    }

    private fun emptyStack(): Map<String, Any?> =
        mapOf("stackFrames" to emptyList<Any>(), "totalFrames" to 0)

    // ─── Outgoing messages ───────────────────────────────────────────────

    private fun respond(command: String, requestSeq: Int, body: Map<String, Any?>?) {
        val msg = linkedMapOf<String, Any?>(
            "seq" to seq.getAndIncrement(),
            "type" to "response",
            "request_seq" to requestSeq,
            "success" to true,
            "command" to command,
        )
        if (body != null) msg["body"] = body
        write(msg)
    }

    private fun respondError(command: String, requestSeq: Int, message: String) {
        write(linkedMapOf(
            "seq" to seq.getAndIncrement(),
            "type" to "response",
            "request_seq" to requestSeq,
            "success" to false,
            "command" to command,
            "message" to message,
        ))
    }

    /** Fire a DAP event (`stopped`, `output`, `terminated`, …). Thread-safe. */
    fun sendEvent(event: String, body: Map<String, Any?>?) {
        val msg = linkedMapOf<String, Any?>(
            "seq" to seq.getAndIncrement(),
            "type" to "event",
            "event" to event,
        )
        if (body != null) msg["body"] = body
        write(msg)
    }

    @Synchronized
    private fun write(msg: Map<String, Any?>) {
        val o = out ?: return
        try {
            DapWire.writeMessage(o, msg)
        } catch (e: Exception) {
            log("[dap] write failed: ${e.message}")
        }
    }
}
