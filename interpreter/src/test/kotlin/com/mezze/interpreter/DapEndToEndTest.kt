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
import java.util.concurrent.TimeUnit
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

            // scopes + variables: the frame's named slots (params + do-block
            // locals) surface as a "Locals" scope, and the param `a` reads as 5
            // at the line-2 breakpoint.
            request(out, "scopes", mapOf("frameId" to topFrameId))
            val scopesResp = readUntil(input, "response") { (it["command"] as? String) == "scopes" }
            @Suppress("UNCHECKED_CAST")
            val scopes = (scopesResp["body"] as Map<String, Any?>)["scopes"] as List<Map<String, Any?>>
            assertTrue(scopes.isNotEmpty(), "expected a Locals scope while suspended, got: $scopes")
            val ref = (scopes[0]["variablesReference"] as Number).toInt()
            request(out, "variables", mapOf("variablesReference" to ref))
            val varsResp = readUntil(input, "response") { (it["command"] as? String) == "variables" }
            @Suppress("UNCHECKED_CAST")
            val vars = (varsResp["body"] as Map<String, Any?>)["variables"] as List<Map<String, Any?>>
            val a = vars.firstOrNull { it["name"] == "a" }
            assertTrue(a != null, "expected local `a` in scope, got: ${vars.map { it["name"] }}")
            assertEquals("5", a!!["value"], "param a should be 5 at line 2, got: ${a["value"]}")

            // evaluate a watch expression referencing a LOCAL while paused — the
            // inline-eval override resolves `a` against the frame's named slots,
            // so watch now reads locals (not just globals/literals).
            request(out, "evaluate", mapOf("expression" to "a", "context" to "watch"))
            val evalResp = readUntil(input, "response") { (it["command"] as? String) == "evaluate" }
            assertEquals(true, evalResp["success"], "evaluate request should succeed")
            @Suppress("UNCHECKED_CAST")
            val evalResult = (evalResp["body"] as Map<String, Any?>)["result"] as? String
            assertEquals("5", evalResult, "watch `a` should read the local as 5, got: $evalResult")

            // arithmetic watch over locals: `a + b` where a=5, b=7 → 12 (both
            // params are bound at the line-2 breakpoint).
            request(out, "evaluate", mapOf("expression" to "a + b", "context" to "watch"))
            val evalResp2 = readUntil(input, "response") { (it["command"] as? String) == "evaluate" }
            @Suppress("UNCHECKED_CAST")
            val evalResult2 = (evalResp2["body"] as Map<String, Any?>)["result"] as? String
            assertEquals("12", evalResult2, "watch `a + b` should be 12, got: $evalResult2")

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

            // The program finishes → RuntimeServer's finally hook calls
            // onProgramExit; simulate it here (the test drives eval directly).
            // The client should get `exited` (with code) then `terminated`.
            evalThread.join(3000)
            bridge.onProgramExit(0)
            val exited = readUntil(input, "event") { (it["event"] as? String) == "exited" }
            @Suppress("UNCHECKED_CAST")
            assertEquals(0, ((exited["body"] as Map<String, Any?>)["exitCode"] as Number).toInt(),
                "exited event should carry exit code 0")
            readUntil(input, "event") { (it["event"] as? String) == "terminated" }

            request(out, "disconnect", emptyMap())
            readUntil(input, "response") { (it["command"] as? String) == "disconnect" }
        }

        serverThread.join(3000)
        ctx.close(true)
    }

    @Test
    @Timeout(30)
    fun `a conditional breakpoint over a local suppresses the stop when false`() {
        // Break on line 2 only when `a > 100`. `a` is 5, and the condition now
        // resolves the LOCAL (via the inline-eval override), so it's false — the
        // program runs to completion with no `stopped` event.
        val program = """
            let calc = {a: Int, b: Int} -> do
              let t1 = a + b
              t1 + 1
            let result = calc { a = 5, b = 7 }
        """.trimIndent()
        val tmp = java.io.File.createTempFile("dapc_", ".mz").apply { writeText(program); deleteOnExit() }
        val ctx = Context.newBuilder("mezze").allowAllAccess(true)
            .option("engine.WarnInterpreterOnly", "false").build()
        val bridge = GraalDapBridge(ctx.engine)
        val server = MezzeDapServer(port = 0, bridge = bridge)
        bridge.server = server
        Thread { server.serve() }.apply { isDaemon = true; start() }
        val port = awaitPort(server)

        val done = java.util.concurrent.CountDownLatch(1)
        val evalThread = Thread {
            try { ctx.eval(Source.newBuilder("mezze", tmp).build()) } catch (_: Throwable) {} finally { done.countDown() }
        }.apply { isDaemon = true }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            request(out, "initialize", mapOf("adapterID" to "mezze"))
            readUntil(input, "response"); readUntil(input, "event")
            request(out, "launch", mapOf("program" to tmp.absolutePath)); readUntil(input, "response")

            request(out, "setBreakpoints", mapOf(
                "source" to mapOf("path" to tmp.absolutePath),
                "breakpoints" to listOf(mapOf("line" to 2, "condition" to "a > 100")),
            ))
            val resp = readUntil(input, "response") { (it["command"] as? String) == "setBreakpoints" }
            assertEquals(true, resp["success"], "setBreakpoints with a local condition should succeed")
            request(out, "configurationDone", emptyMap()); readUntil(input, "response")
            evalThread.start()

            // Condition `a > 100` is false (a=5) → program finishes, no stop.
            assertTrue(done.await(10, TimeUnit.SECONDS),
                "program should run to completion (local condition a>100 is false)")
            request(out, "disconnect", emptyMap()); readUntil(input, "response")
        }
        ctx.close(true)
    }

    @Test
    @Timeout(60)
    fun `an uncaught error stops on an exception breakpoint`() {
        // `1 / 0` throws a guest error at runtime. With an "uncaught" exception
        // breakpoint set, the debugger stops at the raise site (reason
        // "exception") before the error propagates out.
        val program = "let x = 1 / 0"
        val tmp = java.io.File.createTempFile("dape_", ".mz").apply { writeText(program); deleteOnExit() }
        val ctx = Context.newBuilder("mezze").allowAllAccess(true)
            .option("engine.WarnInterpreterOnly", "false").build()
        val bridge = GraalDapBridge(ctx.engine)
        val server = MezzeDapServer(port = 0, bridge = bridge)
        bridge.server = server
        Thread { server.serve() }.apply { isDaemon = true; start() }
        val port = awaitPort(server)

        val evalThread = Thread {
            try { ctx.eval(Source.newBuilder("mezze", tmp).build()) } catch (_: Throwable) {}
        }.apply { isDaemon = true }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            request(out, "initialize", mapOf("adapterID" to "mezze"))
            readUntil(input, "response"); readUntil(input, "event")
            request(out, "launch", mapOf("program" to tmp.absolutePath)); readUntil(input, "response")
            request(out, "setExceptionBreakpoints", mapOf("filters" to listOf("uncaught")))
            readUntil(input, "response") { (it["command"] as? String) == "setExceptionBreakpoints" }
            request(out, "configurationDone", emptyMap()); readUntil(input, "response")
            evalThread.start()

            val stopped = readUntil(input, "event") { (it["event"] as? String) == "stopped" }
            @Suppress("UNCHECKED_CAST")
            val body = stopped["body"] as Map<String, Any?>
            assertEquals("exception", body["reason"], "should stop with reason 'exception', got: $body")

            request(out, "continue", mapOf("threadId" to 1))
            readUntil(input, "response") { (it["command"] as? String) == "continue" }
            request(out, "disconnect", emptyMap()); readUntil(input, "response")
        }
        ctx.close(true)
    }

    @Test
    @Timeout(30)
    fun `stopping on two distinct JVM threads yields two DAP threads`() {
        // Async.spawn runs each task on its own virtual java.lang.Thread, which
        // GraalVM sees as a real thread — distinct suspending threads become
        // distinct DAP threads. We drive the bridge thread-registry directly from
        // two real threads (the async-source path needs the full RuntimeServer
        // entry, which raw ctx.eval can't invoke) to prove the mapping.
        val ctx = Context.newBuilder("mezze").allowAllAccess(true)
            .option("engine.WarnInterpreterOnly", "false").build()
        val bridge = GraalDapBridge(ctx.engine)
        val m = GraalDapBridge::class.java.getDeclaredMethod("dapThreadId", Thread::class.java)
        m.isAccessible = true
        val ids = java.util.concurrent.ConcurrentHashMap<String, Int>()
        listOf("t-alpha", "t-beta").map { name ->
            Thread({ ids[name] = m.invoke(bridge, Thread.currentThread()) as Int }, name).apply { start() }
        }.forEach { it.join(2000) }
        assertEquals(2, ids.size, "expected an id for each thread")
        assertTrue(ids.values.toSet().size == 2, "the two threads should get distinct DAP ids: $ids")
        val threads = bridge.threads()
        assertTrue(threads.size >= 2, "threads() should list both, got: $threads")
        assertTrue(threads.any { (it["id"] as Number).toInt() == 1 }, "id 1 (main) present: $threads")
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
