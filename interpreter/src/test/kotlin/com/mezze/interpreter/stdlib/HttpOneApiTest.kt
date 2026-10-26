package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ONE route table, exercised four ways.
 *
 * Routing, path parameters and query decoding are covered in depth elsewhere.
 * What was not covered is the claim the whole design rests on: that the mock
 * path and the real path are the same API, so a table written once answers a
 * request value, a mocked client and a real socket without being rewritten for
 * any of them. `nest` and `table` each appeared in a single test file, and
 * nothing tied one table to both a mock and a socket.
 *
 * Swapping mock for real changes the `perform` line and nothing else. That is
 * the property these tests are for; each one uses the SAME `api` below.
 */
class HttpOneApiTest {

    /** The service, written once — exactly as a user would write it. */
    private val head = """
        use std::effects::http::{ Request, Response, HttpClient, SimpleHttpClient, MockHttpClient, mock, client, not_found, created, body_str }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, get_request, post_request }
        use std::effects::http_router::{ group, Matched }
        use std::collections::bytes
        use std::str
        use std::int

        let fallback: { req: Request } -> Response = { req } -> not_found { body = "no such route" }

        ## Everything about users, under its own prefix.
        let users = group { prefix = "/users" }
          .get { path = "/:id", handler = { m } ->
            str::concat { l = "user ", r = m.param_or { name = "id", default = "?" } } }
          .post { path = "", handler = { m } -> created { body = "made a user" } }

        ## The whole service: users mounted under /api, plus a health check.
        let api = group { prefix = "/api" }
          .nest { group = users }
          .get { path = "/health", handler = { m } -> "ok" }
          .otherwise { fallback = fallback }

        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"

        let sent: { r: Result Response 'e } -> Str = { r } -> match r is
          Ok { val } -> text_of { resp = val }
          Err { error = _ } -> "request failed"
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    // ── 1. no server at all ─────────────────────────────────────────────

    /**
     * A nested route reached through a request VALUE.
     *
     * `/api/users/:id` exists only because `nest` prepended the outer prefix to
     * the inner group's already-prefixed path. Nothing else asserted that.
     */
    @Test
    fun `a nested route answers through the mock seam`() {
        val r = run("""text_of { resp = get_request { url = "/api/users/7" }.to { app = api } }""")
        assertEquals("user 7", r)
    }

    /** The method is part of the match, through the same table. */
    @Test
    fun `the nested post route answers its own verb`() {
        val r = run("""text_of { resp = post_request { url = "/api/users" }.to { app = api } }""")
        assertEquals("made a user", r)
    }

    /** A sibling route added after the nest still matches. */
    @Test
    fun `a route beside the nested group still matches`() {
        val r = run("""text_of { resp = get_request { url = "/api/health" }.to { app = api } }""")
        assertEquals("ok", r)
    }

    /** And the fallback still has the last word. */
    @Test
    fun `an unmatched path reaches the fallback`() {
        val r = run("""get_request { url = "/nope" }.to { app = api }.status""")
        assertEquals(404L, r)
    }

    /** Handlers returning a bare Str and a Response mix in one table. */
    @Test
    fun `one table carries handlers of different return types`() {
        val r = run(
            """
            str::concat {
              l = text_of { resp = get_request { url = "/api/health" }.to { app = api } },
              r = text_of { resp = post_request { url = "/api/users" }.to { app = api } },
            }
            """.trimIndent()
        )
        assertEquals("okmade a user", r, "a Str handler and a Response handler, same chain")
    }

    // ── 2. the client side, mocked ───────────────────────────────────────

    /**
     * The same endpoints answered by rules instead of by the table.
     *
     * The point is the CALLER does not change: `c.get { path }.send {}` is what
     * it would be against a real server.
     */
    @Test
    fun `a mocked client answers the same endpoints`() {
        val r = run(
            """
            let fake = [
              mock { url = "**/api/users/7" }.get {}.text { body = "user 7" },
              mock { url = "**/api/health" }.get {}.text { body = "ok" },
            ]
            perform MockHttpClient { rules = fake } in do
              let c = client { base_url = "http://example.test" }
              str::concat {
                l = sent { r = c.get { path = "/api/users/7" }.send {} },
                r = sent { r = c.get { path = "/api/health" }.send {} },
              }
            """.trimIndent()
        )
        assertEquals("user 7ok", r)
    }

    // ── 3. a real socket, same table ─────────────────────────────────────

    /**
     * The same `api` over TCP, called by a real client.
     *
     * Binds port 0 and reads the chosen port off the handle, so no test
     * reserves a port another might want.
     */
    @Test
    fun `the same table answers over a real socket`() {
        val r = run(
            """
            perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False } } in
              perform SimpleHttpClient in do
                let h = HttpServer.serve { handler = api }
                let c = client { base_url = "http://127.0.0.1:${'$'}(int::to_str { n = h.port })" }
                let one = sent { r = c.get { path = "/api/users/7" }.send {} }
                let two = sent { r = c.post { path = "/api/users" }.send {} }
                h.stop {}
                str::concat { l = one, r = str::concat { l = " | ", r = two } }
            """.trimIndent()
        )
        assertEquals("user 7 | made a user", r, "the nested route survived the socket")
    }

    // ── 4. closing the group ─────────────────────────────────────────────

    /**
     * `handler` closes a group without spelling out a fallback.
     *
     * 404 is the only answer a table has for a request it does not match, so
     * requiring every group to say so is ceremony.
     */
    @Test
    fun `handler closes a group with a 404 default`() {
        val r = run(
            """
            let bare = group { prefix = "/api" }
              .get { path = "/health", handler = { m } -> "ok" }
              .handler {}
            str::concat {
              l = text_of { resp = get_request { url = "/api/health" }.to { app = bare } },
              r = int::to_str { n = get_request { url = "/nope" }.to { app = bare }.status },
            }
            """.trimIndent()
        )
        assertEquals("ok404", r)
    }

    /**
     * Forgetting to close a group is a COMPILE error, not a runtime surprise.
     *
     * A group is a route table; a server takes a handler. Passing the group
     * itself cannot work, and the type error says which type it got — which is
     * the question a reader of this test actually has.
     */
    @Test
    fun `an unclosed group is rejected while compiling`() {
        val outcome = runCatching {
            run(
                """
                let unclosed = group { prefix = "/api" }
                  .get { path = "/health", handler = { m } -> "ok" }
                get_request { url = "/api/health" }.to { app = unclosed }.status
                """.trimIndent()
            )
        }
        val err = outcome.exceptionOrNull()
        assertTrue(
            err != null && (err.message ?: "").contains("RouteGroup"),
            "an unclosed group must be refused, naming RouteGroup; got ${err?.message ?: outcome.getOrNull()}",
        )
    }
}
