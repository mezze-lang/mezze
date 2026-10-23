package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The server story, composed: routes fold into a handler, `serve` takes it, and
 * the test supplies its own async.
 *
 * Every assertion here runs with NO socket. That is the design working rather
 * than a limitation — routing and handlers are pure, so the request path is
 * exercised by passing a `Request` in.
 */
class HttpServerIntegrationTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, Post }
        use std::effects::http_server::{ HttpServer, MockHttpServer, incoming, get_request }
        use std::effects::http_router::{ route, or_else, otherwise, Matched }
        use std::collections::map
        use std::collections::bytes
        use std::int

        let reply: { code: Int, text: Str, url: Str } -> Response = { code, text, url } -> {
          status = code, headers = map::empty {}, body = bytes::from_str { s = text }, url = url,
          chunks = None,
        }
        let not_found: { req: Request } -> Response = { req } -> reply { code = 404, text = "nope", url = req.url }
        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"

        let app = otherwise {
          routes = or_else {
            a = route { method = Get, path = "/users/:id", handler = { m } ->
                  reply { code = 200, text = match map::get { m = m.params, k = "id" } is
                    Some { val } -> val
                    None -> "?", url = m.req.url } },
            b = route { method = Post, path = "/users", handler = { m } ->
                  reply { code = 201, text = "created", url = m.req.url } },
          },
          fallback = not_found,
        }
    """.trimIndent()

    @Test
    fun `a route table folds into a handler that serve accepts`() {
        val r = MezzeTestHelper.run("""
            $head
            let go: {} -> Int <*> = {} -> perform MockHttpServer in do
              let h = HttpServer.serve { handler = app }
              h.port
            go {}
        """.trimIndent())
        assertEquals(0L, r, "the folded route table IS the handler `serve` takes")
    }

    @Test
    fun `the same app answers three different requests`() {
        val r = MezzeTestHelper.run("""
            $head
            let a = body_of { resp = app { req = get_request { url = "/users/9" } } }
            let b = body_of { resp = app { req = incoming { method = Post, url = "/users" } } }
            let c = int::to_str { n = app { req = get_request { url = "/nothing" } }.status }
            "${'$'}(a)-${'$'}(b)-${'$'}(c)"
        """.trimIndent())
        assertEquals("9-created-404", r)
    }

    @Test
    fun `serve is callable under a DIFFERENT async performer supplied by the test`() {
        // MockHttpServer installs no async performer, so a handler's <Async>
        // stays residual and the TEST discharges it. This is the swap: the same
        // `serve` call under LoomAsync here, under Helidon's own executor in
        // production.
        val r = MezzeTestHelper.run("""
            $head
            use std::effects::async::{ Async, LoomAsync }
            let go: {} -> Int <*> = {} -> perform LoomAsync in perform MockHttpServer in do
              let h = HttpServer.serve { handler = app }
              h.port
            go {}
        """.trimIndent())
        assertEquals(0L, r, "the test chooses the async performer; the mock imposes none")
    }
}
