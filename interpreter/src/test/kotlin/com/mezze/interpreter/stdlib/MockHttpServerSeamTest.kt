package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The test seam `MockHttpServer` leaves open, used the way it is meant to be.
 *
 * That performer binds no socket and does not call the handler, because a
 * handler is a plain function and needs no server to exercise. What a test does
 * instead is build a request and apply it — and that used to read inside-out:
 *
 *     body_of { resp = app { req = incoming { method = Post, url = "/users" } } }
 *
 * innermost-first, with the record spelled out whenever a method other than GET
 * was wanted. A builder per method and a `to` terminal put it in one direction:
 *
 *     post_request { url = "/users" }.with_text { val = "ada" }.to { app = app }
 *
 * Every step is an ordinary `Request` in between, so `Request`'s existing chain
 * (`with_header`, `with_json`, `with_query`, `with_timeout`, …) carries on
 * unchanged — nothing new had to be invented for the mock, which is the point:
 * the mock path and the client path speak the same vocabulary.
 */
class MockHttpServerSeamTest {

    private val head = """
        use std::effects::http::{ Request, Response, Get, Post, response_ok_text, not_found }
        use std::effects::http_server::{ get_request, post_request, put_request, delete_request, patch_request }
        use std::effects::http_router::{ group, otherwise }
        use std::collections::bytes
        use std::str
        use std::int

        let fallback: { req: Request } -> Response = { req } -> not_found { body = "nope" }

        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"

        ## One app answering every verb, so each test only changes the request.
        let app = group { prefix = "" }
          .get { path = "/u", handler = { m } -> "got" }
          .post { path = "/u", handler = { m } -> str::concat {
            l = "made:", r = match m.req.body is
              Some { val } -> body_of { resp = { status = 200, headers = m.req.headers,
                                                 body = val, url = "", chunks = None } }
              None -> "-",
          } }
          .put { path = "/u", handler = { m } -> "replaced" }
          .delete { path = "/u", handler = { m } -> "gone" }
          .patch { path = "/u", handler = { m } -> "tweaked" }
          .otherwise { fallback = fallback }
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    /** The whole path in one direction, which is what the seam is for. */
    @Test
    fun `a request chains into a handler and out again`() {
        val r = run("""post_request { url = "/u" }.with_text { val = "ada" }.to { app = app }.status""")
        assertEquals(200L, r)
    }

    /** And the body the chain attached is the body the handler read. */
    @Test
    fun `the body set by the chain reaches the handler`() {
        val r = run(
            """body_of { resp = post_request { url = "/u" }.with_text { val = "ada" }.to { app = app } }"""
        )
        assertEquals("made:ada", r)
    }

    /** One builder per method, so the Request record is never written out. */
    @Test
    fun `each method builder routes to its own verb`() {
        val r = run(
            """
            str::concat {
              l = str::concat {
                l = body_of { resp = get_request { url = "/u" }.to { app = app } },
                r = body_of { resp = put_request { url = "/u" }.to { app = app } },
              },
              r = str::concat {
                l = body_of { resp = delete_request { url = "/u" }.to { app = app } },
                r = body_of { resp = patch_request { url = "/u" }.to { app = app } },
              },
            }
            """.trimIndent()
        )
        assertEquals("gotreplacedgonetweaked", r)
    }

    /** A header the chain added is a header the handler sees. */
    @Test
    fun `a header set by the chain reaches the handler`() {
        val r = run(
            """
            let echo = group { prefix = "" }
              .get { path = "/h", handler = { m } -> match m.req.header { name = "x-trace" } is
                Some { val } -> val
                None -> "absent" }
              .otherwise { fallback = fallback }
            body_of {
              resp = get_request { url = "/h" }
                .with_header { k = "x-trace", v = "abc123" }
                .to { app = echo }
            }
            """.trimIndent()
        )
        assertEquals("abc123", r)
    }

    /**
     * A header written in any casing is the header the handler finds.
     *
     * The server lowercases every inbound header name before a handler sees it
     * (`HttpServerNatives` line 355), so in production a handler reads
     * `x-trace`. `with_header` did not lowercase, so a test writing `X-Trace`
     * put a differently-keyed entry in the same map and the same handler found
     * nothing — a test that passed or failed on the casing of a string, not on
     * the behaviour it was written for.
     *
     * Both halves are checked here: written mixed-case, read mixed-case the
     * other way, and read as the lowercase name the wire would have given.
     */
    @Test
    fun `a header is found whatever casing it was written in`() {
        val r = run(
            """
            let echo = group { prefix = "" }
              .get { path = "/h", handler = { m } -> str::concat {
                l = match m.req.header { name = "X-TRACE" } is
                  Some { val } -> val
                  None -> "absent",
                r = match m.req.header { name = "x-trace" } is
                  Some { val } -> val
                  None -> "absent",
              } }
              .otherwise { fallback = fallback }
            body_of {
              resp = get_request { url = "/h" }
                .with_header { k = "X-Trace", v = "abc" }
                .to { app = echo }
            }
            """.trimIndent()
        )
        assertEquals("abcabc", r)
    }

    /** A route that does not match still falls through to the fallback. */
    @Test
    fun `an unmatched request reaches the fallback through the chain`() {
        val r = run("""delete_request { url = "/nope" }.to { app = app }.status""")
        assertEquals(404L, r)
    }
}
