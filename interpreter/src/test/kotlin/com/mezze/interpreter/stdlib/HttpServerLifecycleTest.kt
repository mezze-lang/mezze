package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A server must not outlive the program that opened it.
 *
 * `serve` hands the guest a `stop`, and a guest may never call it — a crash, an
 * interrupted `mez dev`, a test that forgot. Without the context-dispose
 * backstop that leaves a listener holding a port for as long as the process
 * lives, and nothing in the suite would notice.
 *
 * The handler used to run on a pool of Truffle-owned workers, and this file
 * asserted the pool was retired. It now runs on Helidon's own connection
 * threads, so there is no pool and that assertion would pass whatever the code
 * did. What still has to hold — and is what mattered all along — is that the
 * SOCKET is released, so that is what is checked here.
 */
class HttpServerLifecycleTest {

    private val head = """
        use std::effects::http::{ Request, Response }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig, get_request }
        use std::collections::map
        use std::collections::bytes

        let handler: { req: Request } -> Response = { req } -> {
          status = 200, headers = map::empty {}, body = bytes::from_str { s = "ok" }, url = req.url,
          chunks = None,
        }
        let any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 2, stream_bodies = False }
    """.trimIndent()

    private fun serveAndWalkAway(): Int =
        (
            MezzeTestHelper.run(
                """
                $head
                let go: {} -> Int <*> = {} ->
                  perform HelidonHttpServerWith { config = any_port } in do
                    let h = HttpServer.serve { handler = handler }
                    h.port
                go {}
                """.trimIndent(),
            ) as Long
            ).toInt()

    @Test
    fun `a server nobody stops releases its port when the context goes`() {
        val port = serveAndWalkAway()
        assertTrue(port > 0, "the OS should have chosen a port, got $port")

        // Closing a listener is not instant, so retry briefly rather than
        // racing it. If dispose never closed it, no amount of waiting helps.
        var reclaimed = false
        for (attempt in 1..40) {
            try {
                java.net.ServerSocket(port).close()
                reclaimed = true
                break
            } catch (_: java.io.IOException) {
                Thread.sleep(50)
            }
        }
        assertTrue(
            reclaimed,
            "port $port was still bound 2s after the context that opened it was disposed",
        )
    }

    @Test
    fun `repeated unstopped servers do not pile up threads`() {
        // Helidon starts threads of its own per server. Twelve servers nobody
        // stops should not leave twelve servers' worth of them behind.
        val before = Thread.getAllStackTraces().size
        repeat(12) { serveAndWalkAway() }
        Thread.sleep(500)
        val after = Thread.getAllStackTraces().size
        assertTrue(
            after - before < 24,
            "12 unstopped servers should not leave 24 threads behind; threads went $before -> $after",
        )
    }
}
