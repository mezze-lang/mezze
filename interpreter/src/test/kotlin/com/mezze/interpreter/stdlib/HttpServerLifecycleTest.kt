package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A server must not outlive the program that opened it.
 *
 * `serve` hands the guest a `stop`, and a guest may never call it — a crash, an
 * interrupted `mez dev`, a test that forgot. Without the context-dispose
 * backstop that leaves a bound port and a pool of workers parked on a queue, and
 * nothing in the suite would notice until something ran out of threads.
 */
class HttpServerLifecycleTest {

    private val head = """
        use std::effects::http::{ Request, Response }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig, get_request }
        use std::collections::map
        use std::collections::bytes

        let handler: { req: Request } -> Response = { req } -> {
          status = 200, headers = map::empty {}, body = bytes::from_str { s = "ok" }, url = req.url,
        }
        let any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 2 }
    """.trimIndent()

    @Test
    fun `a server nobody stops does not leak its workers past the context`() {
        // Each run starts a server and walks away without stopping it. The
        // worker threads are named by Truffle and parked on the queue; if the
        // context's dispose hook did not retire them, this would pile up two per
        // iteration and the count would climb monotonically.
        val before = Thread.getAllStackTraces().size
        repeat(12) {
            MezzeTestHelper.run(
                """
                $head
                let go: {} -> Int <*> = {} ->
                  perform HelidonHttpServerWith { config = any_port } in do
                    let h = HttpServer.serve { handler = handler }
                    h.port
                go {}
                """.trimIndent(),
            )
        }
        // Give the interrupts a moment to land; thread teardown is not instant.
        Thread.sleep(500)
        val after = Thread.getAllStackTraces().size
        assertTrue(
            after - before < 24,
            "12 unstopped servers at 2 workers each should not leave 24 threads behind; " +
                "threads went $before -> $after",
        )
    }
}
