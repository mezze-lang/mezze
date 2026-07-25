package com.mezze.interpreter

import com.mezze.interpreter.standalone.DapWire
import com.mezze.interpreter.standalone.MezzeDapServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 acceptance for the hand-written DAP adapter: a fake DAP client can
 * connect over the socket, run the initialize → launch → configurationDone
 * handshake, receive capabilities + the `initialized` event, and trigger the
 * server's onLaunch callback. No GraalVM Debugger involved yet — this validates
 * the socket, Content-Length framing, and the request/response protocol.
 */
class MezzeDapServerTest {

    private val clientSeq = AtomicInteger(1)

    @Test
    @Timeout(20)
    fun `initialize launch configurationDone handshake completes`() {
        val launched = CountDownLatch(1)
        // Port 0 → ephemeral; read the bound port after serve() binds.
        val server = MezzeDapServer(port = 0, onLaunch = { launched.countDown() })

        val serverThread = Thread { server.serve() }.apply { isDaemon = true; start() }
        // Wait for the port to be bound.
        var port = 0
        val deadline = System.currentTimeMillis() + 5000
        while (port == 0 && System.currentTimeMillis() < deadline) {
            port = server.boundPort
            if (port == 0) Thread.sleep(10)
        }
        assertTrue(port > 0, "server should bind an ephemeral port")

        Socket("127.0.0.1", port).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            // initialize → expect a response with capabilities, then an
            // `initialized` event.
            request(out, "initialize", mapOf("adapterID" to "mezze"))
            val initResp = DapWire.readMessage(input)!!
            assertEquals("response", initResp["type"])
            assertEquals("initialize", initResp["command"])
            assertEquals(true, initResp["success"])
            @Suppress("UNCHECKED_CAST")
            val caps = initResp["body"] as Map<String, Any?>
            assertEquals(true, caps["supportsConfigurationDoneRequest"],
                "server should advertise configurationDone support")

            val initializedEvent = DapWire.readMessage(input)!!
            assertEquals("event", initializedEvent["type"])
            assertEquals("initialized", initializedEvent["event"])

            // launch → success response.
            request(out, "launch", mapOf("program" to "/tmp/x.mz"))
            val launchResp = DapWire.readMessage(input)!!
            assertEquals("launch", launchResp["command"])
            assertEquals(true, launchResp["success"])

            // configurationDone → success + fires onLaunch.
            request(out, "configurationDone", emptyMap())
            val cfgResp = DapWire.readMessage(input)!!
            assertEquals("configurationDone", cfgResp["command"])
            assertEquals(true, cfgResp["success"])

            assertTrue(launched.await(5, TimeUnit.SECONDS),
                "configurationDone should trigger the server's onLaunch")

            // threads → the one synthetic main thread.
            request(out, "threads", emptyMap())
            val threadsResp = DapWire.readMessage(input)!!
            @Suppress("UNCHECKED_CAST")
            val threads = (threadsResp["body"] as Map<String, Any?>)["threads"] as List<Map<String, Any?>>
            assertEquals(1, threads.size)
            assertEquals("main", threads[0]["name"])

            // disconnect → success, then the server closes.
            request(out, "disconnect", emptyMap())
            val discResp = DapWire.readMessage(input)!!
            assertEquals("disconnect", discResp["command"])
            assertEquals(true, discResp["success"])
        }

        serverThread.join(3000)
    }

    @Test
    @Timeout(10)
    fun `unknown request gets an unsuccessful response, session stays alive`() {
        val server = MezzeDapServer(port = 0)
        Thread { server.serve() }.apply { isDaemon = true; start() }
        var port = 0
        val deadline = System.currentTimeMillis() + 5000
        while (port == 0 && System.currentTimeMillis() < deadline) { port = server.boundPort; if (port == 0) Thread.sleep(10) }

        Socket("127.0.0.1", port).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            request(out, "somethingWeird", emptyMap())
            val resp = DapWire.readMessage(input)!!
            assertEquals("response", resp["type"])
            assertEquals(false, resp["success"], "unknown request should fail gracefully")
            assertNotNull(resp["message"])

            // Session still alive: a following initialize still works.
            request(out, "initialize", emptyMap())
            val initResp = DapWire.readMessage(input)!!
            assertEquals(true, initResp["success"])
            request(out, "disconnect", emptyMap())
            DapWire.readMessage(input)
        }
    }

    // ─── Fake client helper ──────────────────────────────────────────────

    private fun request(out: java.io.OutputStream, command: String, args: Map<String, Any?>) {
        DapWire.writeMessage(out, linkedMapOf(
            "seq" to clientSeq.getAndIncrement(),
            "type" to "request",
            "command" to command,
            "arguments" to args,
        ))
    }
}
