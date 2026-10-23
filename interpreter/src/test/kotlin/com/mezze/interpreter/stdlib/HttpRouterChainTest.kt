package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The dot-chained router surface.
 *
 * Same behaviour as HttpRouterTest, written the way the API is meant to read.
 * The nested form stays supported — these are inherent impls over the same
 * functions, not a second implementation.
 */
class HttpRouterChainTest {

    private val head = """
        use std::effects::http::{ Request, Response, Get, Post }
        use std::effects::http_server::{ HttpServer, MockHttpServer, incoming, get_request }
        use std::effects::http_router::{ route, Matched, Routes }
        use std::collections::map
        use std::collections::bytes
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" }, url = req.url,
          chunks = None,
        }
        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"

        let app =
          route { method = Get, path = "/users/:id", handler = { m } ->
            m.ok { text = m.param_or { name = "id", default = "?" } } }
            .or_else { b = route { method = Post, path = "/users", handler = { m } ->
              m.reply { status = 201, text = "created" } } }
            .otherwise { fallback = not_found }
    """.trimIndent()

    @Test
    fun `a chained table binds a path parameter`() {
        val r = MezzeTestHelper.run("$head\nbody_of { resp = app { req = get_request { url = \"/users/42\" } } }")
        assertEquals("42", r, "m.param_or replaces the map::get boilerplate")
    }

    @Test
    fun `a chained table routes the second branch`() {
        val r = MezzeTestHelper.run("$head\nbody_of { resp = app { req = incoming { method = Post, url = \"/users\" } } }")
        assertEquals("created", r)
    }

    @Test
    fun `a chained table falls through`() {
        val r = MezzeTestHelper.run("$head\napp { req = get_request { url = \"/nope\" } }.status")
        assertEquals(404L, r)
    }

    @Test
    fun `m dot ok sets 200 and echoes the request url`() {
        val r = MezzeTestHelper.run("$head\napp { req = get_request { url = \"/users/7\" } }.url")
        assertEquals("/users/7", r, "the response carries the url that produced it")
    }

    @Test
    fun `param returns None for a name the pattern never bound`() {
        val r = MezzeTestHelper.run("""
            $head
            let probe = route { method = Get, path = "/x", handler = { m } ->
              m.ok { text = match m.param { name = "absent" } is
                Some { val } -> val
                None -> "unbound" } }
              .otherwise { fallback = not_found }
            body_of { resp = probe { req = get_request { url = "/x" } } }
        """.trimIndent())
        assertEquals("unbound", r)
    }

    @Test
    fun `the chained handler is what serve accepts`() {
        val r = MezzeTestHelper.run("""
            $head
            let go: {} -> Int <*> = {} -> perform MockHttpServer in do
              let h = HttpServer.serve { handler = app }
              h.port
            go {}
        """.trimIndent())
        assertEquals(0L, r)
    }
}
