package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * W11: the router is PURE.
 *
 * Every test here calls the folded handler with a synthetic `Request`. No
 * server, no mock performer, no socket — which is the property that makes
 * routing worth keeping out of the `HttpServer` effect.
 */
class HttpRouterTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, Post }
        use std::effects::http_server::{ incoming, get_request }
        use std::effects::http_router::{ Matched, Routes, route, or_else, otherwise, match_path, segments }
        use std::collections::map
        use std::collections::bytes

        let reply: { code: Int, text: Str, url: Str } -> Response = { code, text, url } -> {
          status = code, headers = map::empty {}, body = bytes::from_str { s = text }, url = url,
        }
        let not_found: { req: Request } -> Response = { req } -> reply { code = 404, text = "nope", url = req.url }
        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"
    """.trimIndent()

    @Test
    fun `a literal path routes to its handler`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/health", handler = { m } -> reply { code = 200, text = "ok", url = m.req.url } },
              fallback = not_found,
            }
            body_of { resp = app { req = get_request { url = "/health" } } }
        """.trimIndent())
        assertEquals("ok", r)
    }

    @Test
    fun `an unmatched path falls through to the fallback`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/health", handler = { m } -> reply { code = 200, text = "ok", url = m.req.url } },
              fallback = not_found,
            }
            app { req = get_request { url = "/missing" } }.status
        """.trimIndent())
        assertEquals(404L, r)
    }

    @Test
    fun `a path parameter is bound and reaches the handler`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/users/:id", handler = { m } ->
                reply { code = 200, text = match map::get { m = m.params, k = "id" } is
                  Some { val } -> val
                  None -> "none", url = m.req.url } },
              fallback = not_found,
            }
            body_of { resp = app { req = get_request { url = "/users/42" } } }
        """.trimIndent())
        assertEquals("42", r, "`:id` must bind the matching segment")
    }

    @Test
    fun `the METHOD is part of the match`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Post, path = "/submit", handler = { m } -> reply { code = 201, text = "made", url = m.req.url } },
              fallback = not_found,
            }
            app { req = incoming { method = Get, url = "/submit" } }.status
        """.trimIndent())
        assertEquals(404L, r, "a GET must not match a POST route on the same path")
    }

    @Test
    fun `or_else chains routes and the first match wins`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = or_else {
                a = route { method = Get, path = "/a", handler = { m } -> reply { code = 200, text = "first", url = m.req.url } },
                b = route { method = Get, path = "/b", handler = { m } -> reply { code = 200, text = "second", url = m.req.url } },
              },
              fallback = not_found,
            }
            body_of { resp = app { req = get_request { url = "/b" } } }
        """.trimIndent())
        assertEquals("second", r)
    }

    @Test
    fun `a trailing slash does not change the match`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/health", handler = { m } -> reply { code = 200, text = "ok", url = m.req.url } },
              fallback = not_found,
            }
            app { req = get_request { url = "/health/" } }.status
        """.trimIndent())
        assertEquals(200L, r, "`/health/` and `/health` are the same route")
    }

    @Test
    fun `a longer path does not match a shorter pattern`() {
        val r = MezzeTestHelper.run("""
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/users/:id", handler = { m } -> reply { code = 200, text = "hit", url = m.req.url } },
              fallback = not_found,
            }
            app { req = get_request { url = "/users/42/posts" } }.status
        """.trimIndent())
        assertEquals(404L, r, "segment counts must agree")
    }
}
