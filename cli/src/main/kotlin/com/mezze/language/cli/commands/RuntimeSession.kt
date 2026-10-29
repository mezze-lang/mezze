package com.mezze.language.cli.commands

import com.mezze.wire.Eval
import com.mezze.wire.EvalResult
import com.mezze.wire.Hello
import com.mezze.wire.Log
import com.mezze.wire.MessageType
import com.mezze.wire.ProtocolError
import com.mezze.wire.Quit
import com.mezze.wire.Reject
import com.mezze.wire.StderrData
import com.mezze.wire.StdoutData
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireListener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * A client attached to an ALREADY-RUNNING runtime that is listening on a
 * loopback socket (`RuntimeServer --listen`, published as a session file).
 *
 * Unlike [WireSession], this does NOT spawn or own a JVM — it connects to the
 * warm runtime `mez dev` already runs in the project's devenv shell (published
 * as `.mezze/session.json`). Compilation stays on THIS side (native `mez`, no JDK needed); only
 * EXECUTION happens in the runtime. The EVAL closure is self-contained ("all
 * modules are already in the FunctionIR list"), so nothing has to be loaded
 * into that runtime's world first and a sample cannot perturb a running
 * program's state.
 *
 * Request/response only: it sends EVAL and reads the expression's output
 * frames plus EVAL_RESULT. It deliberately does not drive an interactive
 * program — no stdin forwarding, no terminal signals — that is [WireSession]'s
 * job for a run it owns.
 */
internal class RuntimeSession private constructor(
    private val socket: Socket,
    private val out: OutputStream,
    private val input: BufferedInputStream,
) : AutoCloseable {

    /**
     * Send one compiled expression (CBOR produced by `EvalClosure.compile` on
     * this side) and return its value, or null if the runtime answered with no
     * value (crash / protocol error / closed connection — reported to stderr).
     * Output the expression itself produces is written to [stdout] as it
     * arrives, so ordering with the returned value is preserved.
     */
    fun evalOnce(
        fnsFile: File,
        metaFile: File,
        requestId: Long,
        maxDepth: Int,
        maxItems: Int,
        timeoutMs: Long,
        stdout: OutputStream = System.out,
    ): EvalResult? {
        WireFrameCodec.writeFlush(
            out,
            WireFrame.of(
                MessageType.EVAL,
                Eval(
                    requestId = requestId,
                    fnsCborPath = fnsFile.absolutePath,
                    metaCborPath = metaFile.absolutePath,
                    maxDepth = maxDepth,
                    maxItems = maxItems,
                    timeoutMs = timeoutMs,
                ),
            ),
        )
        while (true) {
            val frame = try {
                WireFrameCodec.read(input) ?: return null
            } catch (_: Exception) {
                return null
            }
            when (frame.type) {
                MessageType.STDOUT_DATA -> { stdout.write(frame.decode<StdoutData>().bytes); stdout.flush() }
                MessageType.STDERR_DATA -> { System.err.write(frame.decode<StderrData>().bytes); System.err.flush() }
                MessageType.EVAL_RESULT -> return frame.decode()
                MessageType.LOG -> {
                    val l = frame.decode<Log>()
                    System.err.println("[runtime ${l.level}] ${l.message}")
                }
                MessageType.ERROR -> {
                    val pe = frame.decode<ProtocolError>()
                    System.err.println("Protocol error: ${pe.code} ${pe.message}")
                    return null
                }
                MessageType.EXITED, MessageType.BYE -> return null
                // LOADED / STARTED / and anything else are advisory here — ignore.
                else -> {}
            }
        }
    }

    override fun close() {
        try { WireFrameCodec.writeFlush(out, WireFrame.of(MessageType.QUIT, Quit(reason = "done"))) } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        /**
         * Connect to a listening runtime and complete the token handshake.
         * Null (with a message on stderr) if the socket cannot be reached or the
         * runtime rejects the token.
         */
        fun connect(session: WireListener.Session, client: String = "mez-sample"): RuntimeSession? {
            val socket = try {
                Socket(InetAddress.getLoopbackAddress(), session.port)
            } catch (e: Exception) {
                System.err.println(
                    "${Glyphs.CROSS} could not reach the runtime session on " +
                        "127.0.0.1:${session.port}: ${e.message}",
                )
                return null
            }
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            // Auth FIRST: the HELLO carries the session token the runtime demands
            // of any socket client (see WireListener / the socket-token rules).
            WireFrameCodec.writeFlush(
                out,
                WireFrame.of(MessageType.HELLO, Hello(client = client, token = session.token)),
            )
            val reply = try { WireFrameCodec.read(input) } catch (_: Exception) { null }
            when (reply?.type) {
                MessageType.WELCOME -> return RuntimeSession(socket, out, input)
                MessageType.REJECT -> {
                    val r = reply.decode<Reject>()
                    System.err.println("${Glyphs.CROSS} the runtime refused the connection: ${r.reason}")
                }
                null -> System.err.println("${Glyphs.CROSS} the runtime closed the connection before WELCOME")
                else -> System.err.println("${Glyphs.CROSS} unexpected handshake reply: ${reply.type}")
            }
            try { socket.close() } catch (_: Exception) {}
            return null
        }
    }
}
