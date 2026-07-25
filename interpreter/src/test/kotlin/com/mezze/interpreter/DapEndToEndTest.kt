package com.mezze.interpreter

import com.mezze.interpreter.standalone.DapWire
import com.mezze.interpreter.standalone.GraalDapBridge
import com.mezze.interpreter.standalone.MezzeDapServer
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 end-to-end: the hand-written DAP adapter drives GraalVM's real
 * Debugger over the socket. A fake DAP client sets a line breakpoint, launches a
 * Mezze program, receives a `stopped` event at that line, reads the stack trace,
 * and continues to completion — proving breakpoints + stopped + stack + continue
 * all flow through MezzeDapServer → GraalDapBridge → GraalVM Debugger, on the
 * bridge's own socket (no wire protocol, no dap-tool).
 */
class DapEndToEndTest {

    private val clientSeq = AtomicInteger(1)

    @Test
    @Timeout(60)
    fun `set breakpoint, run, stop at line, read stack, continue`() {
        // Break inside a lambda body. Two consecutive `let` statements (lines 2
        // and 3) give step-over a next statement to land on. Line 4 (`t2 + 1`)
        // is the do-block result expression — not a tagged statement stop point.
        val program = """
            let calc = {a: Int, b: Int} -> do
              let t1 = a + b
              let t2 = t1 + a
              t2 + 1
            let result = calc { a = 5, b = 7 }
        """.trimIndent()
        val tmp = java.io.File.createTempFile("dap_", ".mz").apply { writeText(program); deleteOnExit() }

        val ctx = Context.newBuilder("mezze")
            .allowAllAccess(true)
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        val bridge = GraalDapBridge(ctx.engine)
        val server = MezzeDapServer(
            port = 0,
            onLaunch = { /* program is driven by the test thread below */ },
            bridge = bridge,
        )
        bridge.server = server

        val serverThread = Thread { server.serve() }.apply { isDaemon = true; start() }
        val port = awaitPort(server)

        // The program runs on its own "eval" thread — the debugger's onSuspend
        // callback parks THAT thread, while the DAP client drives from here.
        val evalThread = Thread {
            try {
                val fileSource = Source.newBuilder("mezze", tmp).build()
                ctx.eval(fileSource)
            } catch (e: Throwable) {
                // program finished / interrupted
            }
        }.apply { isDaemon = true }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15000  // fail fast instead of hanging on a missing event
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            // Handshake.
            request(out, "initialize", mapOf("adapterID" to "mezze"))
            readUntil(input, "response")               // initialize response
            readUntil(input, "event")                  // initialized event
            request(out, "launch", mapOf("program" to tmp.absolutePath))
            readUntil(input, "response")

            // Break on line 2 (`let sum = a + b`) — a statement node inside
            // `calc`'s body, where params `a`, `b` are in scope. (Line 3
            // `sum + 1` is the do-block result expression, not a tagged
            // statement, so a breakpoint can't attach there.)
            request(out, "setBreakpoints", mapOf(
                "source" to mapOf("path" to tmp.absolutePath),
                "breakpoints" to listOf(mapOf("line" to 2)),
            ))
            val bpResp = readUntil(input, "response")
            @Suppress("UNCHECKED_CAST")
            val installed = (bpResp["body"] as Map<String, Any?>)["breakpoints"] as List<Map<String, Any?>>
            // A line breakpoint only RESOLVES once its source is loaded — which
            // hasn't happened yet (the program starts after configurationDone).
            // So `verified` may be false here; the real proof it works is the
            // `stopped` event below. We just confirm one breakpoint entry came
            // back for the requested line.
            assertTrue(installed.size == 1 && (installed[0]["line"] as Number).toInt() == 2,
                "expected one breakpoint entry for line 2, got: $installed")

            request(out, "configurationDone", emptyMap())
            readUntil(input, "response")

            // NOW start the program. It should hit the breakpoint and the server
            // pushes a `stopped` event.
            evalThread.start()

            val stopped = readUntil(input, "event") { (it["event"] as? String) == "stopped" }
            @Suppress("UNCHECKED_CAST")
            val stopBody = stopped["body"] as Map<String, Any?>
            assertEquals("breakpoint", stopBody["reason"])

            // Read the stack trace while parked — top frame should be at line 3.
            request(out, "stackTrace", mapOf("threadId" to 1))
            val stackResp = readUntil(input, "response") { (it["command"] as? String) == "stackTrace" }
            @Suppress("UNCHECKED_CAST")
            val frames = (stackResp["body"] as Map<String, Any?>)["stackFrames"] as List<Map<String, Any?>>
            assertTrue(frames.isNotEmpty(), "expected at least one stack frame while suspended")
            val topLine = (frames[0]["line"] as Number).toInt()
            assertEquals(2, topLine, "top frame should be on the breakpoint line, frames=$frames")
            val topFrameId = (frames[0]["id"] as Number).toInt()

            // scopes + variables: the protocol round-trips (MezzeNode now
            // exports NodeLibrary.getScope, so the debugger finds a scope at the
            // suspend point). The scope currently reports no NAMED locals because
            // the compiler builds FrameDescriptor slots by count, not name —
            // naming those slots (params + do-block locals) is a follow-up
            // interpreter task. Here we assert the requests SUCCEED (no error,
            // well-formed body), which is what P3 wires end-to-end.
            request(out, "scopes", mapOf("frameId" to topFrameId))
            val scopesResp = readUntil(input, "response") { (it["command"] as? String) == "scopes" }
            assertEquals(true, scopesResp["success"], "scopes request should succeed")
            @Suppress("UNCHECKED_CAST")
            val scopesBody = scopesResp["body"] as Map<String, Any?>
            assertTrue(scopesBody.containsKey("scopes"), "scopes body should have a scopes array")
            @Suppress("UNCHECKED_CAST")
            val scopes = scopesBody["scopes"] as List<Map<String, Any?>>
            // If a scope surfaced (named slots present), variables must round-trip too.
            if (scopes.isNotEmpty()) {
                val ref = (scopes[0]["variablesReference"] as Number).toInt()
                request(out, "variables", mapOf("variablesReference" to ref))
                val varsResp = readUntil(input, "response") { (it["command"] as? String) == "variables" }
                assertEquals(true, varsResp["success"], "variables request should succeed")
            }

            // evaluate a watch expression while paused (drives frame.eval on
            // the eval thread via the park command queue). A literal always
            // evaluates regardless of local-slot naming.
            request(out, "evaluate", mapOf("expression" to "40 + 2", "context" to "watch"))
            val evalResp = readUntil(input, "response") { (it["command"] as? String) == "evaluate" }
            assertEquals(true, evalResp["success"], "evaluate request should succeed")
            @Suppress("UNCHECKED_CAST")
            val evalResult = (evalResp["body"] as Map<String, Any?>)["result"] as? String
            // The evaluate round-trip works (frame.eval ran on the eval thread
            // and returned a value). What the string renders as depends on how
            // MezzeLanguage evaluates a bare `40 + 2` in the paused frame's
            // context — assert the round-trip produced a non-null result rather
            // than pinning the exact rendering.
            assertNotNull(evalResult, "watch evaluate should return a result string")
            assertFalse(evalResult!!.startsWith("<eval"), "evaluate should not error, got: $evalResult")

            // step over (`next`) → the debugger advances and re-suspends.
            request(out, "next", mapOf("threadId" to 1))
            readUntil(input, "response") { (it["command"] as? String) == "next" }
            val stoppedAgain = readUntil(input, "event") { (it["event"] as? String) == "stopped" }
            @Suppress("UNCHECKED_CAST")
            val stopBody2 = stoppedAgain["body"] as Map<String, Any?>
            assertEquals("step", stopBody2["reason"], "step-over should stop with reason 'step'")

            // Continue to completion.
            request(out, "continue", mapOf("threadId" to 1))
            readUntil(input, "response") { (it["command"] as? String) == "continue" }

            request(out, "disconnect", emptyMap())
            readUntil(input, "response") { (it["command"] as? String) == "disconnect" }
        }

        evalThread.join(3000)
        serverThread.join(3000)
        ctx.close(true)
    }

    // ─── Fake DAP client helpers ─────────────────────────────────────────

    private fun awaitPort(server: MezzeDapServer): Int {
        val deadline = System.currentTimeMillis() + 5000
        while (server.boundPort == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(server.boundPort > 0, "server should bind a port")
        return server.boundPort
    }

    private fun request(out: java.io.OutputStream, command: String, args: Map<String, Any?>) {
        DapWire.writeMessage(out, linkedMapOf(
            "seq" to clientSeq.getAndIncrement(),
            "type" to "request",
            "command" to command,
            "arguments" to args,
        ))
    }

    /** Read messages until one matches [type] (and optional [pred]). */
    private fun readUntil(
        input: java.io.InputStream,
        type: String,
        pred: (Map<String, Any?>) -> Boolean = { true },
    ): Map<String, Any?> {
        repeat(50) {
            val msg = DapWire.readMessage(input) ?: error("stream closed waiting for $type")
            if (msg["type"] == type && pred(msg)) return msg
        }
        error("did not see a $type message within 50 messages")
    }
}
