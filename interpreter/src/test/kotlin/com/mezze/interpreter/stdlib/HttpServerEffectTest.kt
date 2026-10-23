package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * W10: the `HttpServer` effect, its handle, and the mock performer.
 *
 * The point of these is that a handler is a PLAIN FUNCTION. Nothing here binds
 * a socket, and the handler is exercised by passing it a `Request` — which is
 * why `serve` takes a handler instead of a route table.
 */
class HttpServerEffectTest {

    private val head = """
        use std::effects::http::{ Request, Response, Get, Method }
        use std::effects::http_server::{ HttpServer, MockHttpServer, ServerHandle, get_request, incoming }
        use std::collections::map
        use std::collections::bytes
    """.trimIndent()

    @Test
    fun `a handler is a plain function, callable with no server`() {
        val r = MezzeTestHelper.run("""
            $head
            let handler: { req: Request } -> Response = { req } -> {
              status = 200, headers = map::empty {}, body = bytes::from_str { s = "hi" }, url = req.url,
              chunks = None,
            }
            let resp = handler { req = get_request { url = "/x" } }
            resp.status
        """.trimIndent())
        assertEquals(200L, r, "a handler needs no server to run")
    }

    @Test
    fun `serve returns a handle under the mock performer`() {
        val r = MezzeTestHelper.run("""
            $head
            let handler: { req: Request } -> Response = { req } -> {
              status = 204, headers = map::empty {}, body = bytes::from_str { s = "" }, url = req.url,
              chunks = None,
            }
            let go: {} -> Int <*> = {} -> perform MockHttpServer in do
              let h = HttpServer.serve { handler = handler }
              h.port
            go {}
        """.trimIndent())
        assertEquals(0L, r, "the mock binds nothing, so its port is 0")
    }

    @Test
    fun `the handler sees the request's url`() {
        val r = MezzeTestHelper.run("""
            $head
            let handler: { req: Request } -> Response = { req } -> {
              status = 200, headers = map::empty {}, body = bytes::from_str { s = req.url }, url = req.url,
              chunks = None,
            }
            let resp = handler { req = incoming { method = Get, url = "/users/7" } }
            match bytes::to_str { b = resp.body } is
              Ok { val } -> val
              Err { error = _ } -> "err"
        """.trimIndent())
        assertEquals("/users/7", r)
    }
}
