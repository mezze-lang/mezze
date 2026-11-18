package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The chainable mock-rule API.
 *
 * A hand-built `MockRule` is a nested literal with a five-field `Response`
 * inside it — six lines to say "a GET returns 200 ok", and `chunks` made it
 * seven fields. The chain says it in one line, in the order you think of it:
 * which requests, then what comes back.
 *
 * Three of these tests cover things that were IMPOSSIBLE before, not merely
 * verbose: a mocked transport failure (`MockRule.response` has always been a
 * `Result Response HttpError`, but nothing constructed the `Err` arm, so every
 * error path in every caller was untestable without hand-writing the literal),
 * a response header, and a non-GET JSON reply.
 *
 * The flat builders are now one-liners over the chain, so the last tests here
 * pin that they did not drift — `mock_post` still answers 201, `mock_json`
 * still sets Content-Type.
 *
 * Header assertions go through `.header {}` rather than reading the raw map:
 * a Response's header map is LOWERCASE-KEYED (both natives lowercase what
 * comes off the wire, and `get_header` lowercases only the needle), so a raw
 * `map::get { k = "Content-Type" }` asserted against a convention the
 * production reader does not use.
 */
class HttpMockChainTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val head = """
        use std::effects::http::{ Method, Response, request, with_method, format_error, body_str, text_or, HttpClient, MockHttpClient, MockRule, MockRules, mock_http_rules, mocks, mock_otherwise, mock_get, mock_post, mock_json, mock_status }
        use std::codec::json
        use std::collections::{ bytes, map, seq }
        use std::int

        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"
    """.trimIndent()

    private fun run(src: String) = MezzeTestHelper.run("$head\n$src")

    // ==================== the basics ====================

    @Test
    fun `a bare route answers an empty 200 for any method`() {
        assertEquals(
            200L,
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> val.status
                    Err { error = _ } -> 0 - 1
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `get plus text answers that body`() {
        assertEquals(
            "pong",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.get { url = "**" }.text { body = "pong" } } in
                  request { url = "http://x/ping" }.send {}.text_or { def = "nope" }
                """.trimIndent(),
            ),
        )
    }

    /** Method selection RESTRICTS: a GET rule does not answer a POST. */
    @Test
    fun `a get rule does not match a post`() {
        assertEquals(
            "unmatched",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.get { url = "**" }.text { body = "pong" } } in
                  match request { url = "http://x/ping" }.with_method { val = Post }.send {} is
                    Ok { val } -> text_of { resp = val }
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    /**
     * `.methods` REPLACES the route's method rather than adding to it: a GET
     * route narrowed to `[Post]` no longer answers a GET. Sending a GET and
     * expecting NO match is the only thing that tells replace from accumulate —
     * a POST would match under both.
     */
    @Test
    fun `method selection replaces rather than accumulating`() {
        assertEquals(
            "unmatched",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}
                  .get { url = "**" }.methods { methods = [Post] }.text { body = "posted" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> text_of { resp = val }
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `for_methods matches any of a set`() {
        assertEquals(
            "either",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.methods { methods = [Get, Put] }.text { body = "either" } } in
                  match request { url = "http://x/y" }.with_method { val = Put }.send {} is
                    Ok { val } -> text_of { resp = val }
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    // ==================== order independence ====================

    /**
     * The shapers preserve each other, so the chain reads in any order.
     * `.status` keeps the body and `.text` keeps the status — the alternative
     * (each resets the rest) would make `.text {}.status {}` silently drop the
     * body.
     */
    @Test
    fun `status then text keeps both`() {
        assertEquals(
            "201|created",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.status { code = 201 }.text { body = "created" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `text then status keeps both`() {
        assertEquals(
            "202|accepted",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.text { body = "accepted" }.status { code = 202 } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    // ==================== what was impossible before ====================

    /** A response HEADER. There was no way to mock one at all. */
    @Test
    fun `header sets a response header`() {
        assertEquals(
            "text/csv",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.header { k = "Content-Type", v = "text/csv" }.text { body = "a,b" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> match val.header { name = "content-type" } is
                      Some { val = v } -> v
                      None -> "absent"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    /**
     * A mocked TIMEOUT. `MockRule.response` could always carry an `Err`, but
     * nothing built one — so the timeout path of every caller was untestable
     * without writing the literal by hand.
     */
    @Test
    fun `times_out answers a Timeout error`() {
        assertEquals(
            "timeout-30",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.times_out { ms = 30 } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = HttpTimeout { url = _, ms } } -> "timeout-$(int::to_str { n = ms })"
                    Err { error = _ } -> "other-error"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unreachable answers a HttpConnectionFailed error`() {
        assertEquals(
            "reset by peer",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.unreachable { reason = "reset by peer" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = HttpConnectionFailed { url = _, reason } } -> reason
                    Err { error = _ } -> "other-error"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fails answers an arbitrary error`() {
        assertEquals(
            "500",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.fails {
                    error = HttpRequestFailed { url = "http://x/y", status = 500, body = "boom" },
                  } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = HttpRequestFailed { url = _, status, body = _ } } -> int::to_str { n = status }
                    Err { error = _ } -> "other-error"
                """.trimIndent(),
            ),
        )
    }

    /** A non-GET JSON reply — `mock_json` was GET-only. */
    @Test
    fun `post plus json answers JSON with its content type`() {
        assertEquals(
            "application/json",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.post { url = "**" }.json { body = json::JString { val = "hi" } } } in
                  match request { url = "http://x/y" }.with_method { val = Post }.send {} is
                    Ok { val } -> match val.header { name = "content-type" } is
                      Some { val = v } -> v
                      None -> "absent"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `raw sets a byte body`() {
        assertEquals(
            "xy",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.any { url = "**" }.raw { body = bytes::from_str { s = "xy" } } } in
                  request { url = "http://x/y" }.send {}.text_or { def = "nope" }
                """.trimIndent(),
            ),
        )
    }

    // ==================== the flat builders did not drift ====================

    @Test
    fun `mock_get still answers 200 with its body`() {
        assertEquals(
            "200|pong",
            run(
                """
                perform MockHttpClient { rules = mock_get { url = "**", body = "pong" } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    /** mock_post's 201 is the one non-obvious semantic to preserve. */
    @Test
    fun `mock_post still answers 201`() {
        assertEquals(
            "201|made",
            run(
                """
                perform MockHttpClient { rules = mock_post { url = "**", body = "made" } } in
                  match request { url = "http://x/y" }.with_method { val = Post }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `mock_json still sets its content type`() {
        assertEquals(
            "application/json",
            run(
                """
                perform MockHttpClient { rules = mock_json { url = "**", body = json::JString { val = "hi" } } } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val } -> match val.header { name = "content-type" } is
                      Some { val = v } -> v
                      None -> "absent"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `mock_status still honours its code and methods`() {
        assertEquals(
            "404|gone",
            run(
                """
                perform MockHttpClient { rules = mock_status { url = "**", methods = [Delete], code = 404, body = "gone" } } in
                  match request { url = "http://x/y" }.with_method { val = Delete }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    // ==================== mock_http_rules: many routes, built like a router ====================

    /** Three routes on one mock; each request finds its own. */
    @Test
    fun `routes answer by method and url`() {
        assertEquals(
            "users|201|fallback",
            run(
                """
                let rules = mock_http_rules {}
                  .get  { url = "/users" }.text { body = "users" }
                  .post { url = "/users" }.status { code = 201 }
                  .otherwise { status = 200, body = "fallback" }
                perform MockHttpClient { rules = rules } in do
                  let a = request { url = "http://x/users" }.send {}.text_or { def = "?" }
                  let b = match request { url = "http://x/users" }.with_method { val = Post }.send {} is
                    Ok { val } -> int::to_str { n = val.status }
                    Err { error = _ } -> "unmatched"
                  let c = request { url = "http://x/other" }.send {}.text_or { def = "?" }
                  "$(a)|$(b)|$(c)"
                """.trimIndent(),
            ),
        )
    }

    /** Routes are tried in the order added: a catch-all added FIRST shadows what follows. */
    @Test
    fun `the first added route wins`() {
        assertEquals(
            "first",
            run(
                """
                let rules = mock_http_rules {}
                  .any { url = "**" }.text { body = "first" }
                  .get { url = "/a" }.text { body = "second" }
                  .otherwise { status = 404, body = "none" }
                perform MockHttpClient { rules = rules } in
                  request { url = "http://x/a" }.send {}.text_or { def = "?" }
                """.trimIndent(),
            ),
        )
    }

    /** A shaper refines the route added LAST, never an earlier one. */
    @Test
    fun `shapers apply to the last route only`() {
        assertEquals(
            "200:a|204:",
            run(
                """
                let rules = mock_http_rules {}
                  .get { url = "/a" }.text { body = "a" }
                  .get { url = "/b" }.status { code = 204 }
                  .otherwise { status = 404, body = "none" }
                perform MockHttpClient { rules = rules } in do
                  let show = { url } -> match request { url = url }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status }):$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                  "$(show { url = "http://x/a" })|$(show { url = "http://x/b" })"
                """.trimIndent(),
            ),
        )
    }

    /** `.otherwise` answers whatever no route matched, with the status it was given. */
    @Test
    fun `otherwise answers unmatched requests with its status`() {
        assertEquals(
            "404:nope",
            run(
                """
                let rules = mock_http_rules {}.get { url = "/a" }.otherwise { status = 404, body = "nope" }
                perform MockHttpClient { rules = rules } in
                  match request { url = "http://x/zzz" }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status }):$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    /** `.methods` narrows the last route to a set. */
    @Test
    fun `methods narrows the last route`() {
        assertEquals(
            "put|unmatched",
            run(
                """
                let rules = mock_http_rules {}
                  .any { url = "**" }.methods { methods = [Get, Put] }.text { body = "put" }
                  .otherwise { status = 404, body = "unmatched" }
                perform MockHttpClient { rules = rules } in do
                  let a = request { url = "http://x/y" }.with_method { val = Put }.send {}.text_or { def = "unmatched" }
                  let b = request { url = "http://x/y" }.with_method { val = Post }.send {}.text_or { def = "unmatched" }
                  "$(a)|$(b)"
                """.trimIndent(),
            ),
        )
    }

    /** A shaper before any selector shapes a catch-all route. */
    @Test
    fun `a shaper before any selector shapes a catch-all`() {
        assertEquals(
            "x",
            run(
                """
                perform MockHttpClient { rules = mock_http_rules {}.text { body = "x" }.otherwise { status = 404, body = "none" } } in
                  request { url = "http://anywhere/at/all" }.send {}.text_or { def = "?" }
                """.trimIndent(),
            ),
        )
    }

    /** The list form's catch-all: last in the list, it answers whatever nothing before it matched. */
    @Test
    fun `mock_otherwise ends a list as the catch-all`() {
        assertEquals(
            "users|404:nope",
            run(
                """
                let rules = mocks { of = [mock_get { url = "/users", body = "users" }, mock_otherwise { status = 404, body = "nope" }] }
                perform MockHttpClient { rules = rules } in do
                  let a = request { url = "http://x/users" }.send {}.text_or { def = "?" }
                  let b = match request { url = "http://x/zzz" }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status }):$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                  "$(a)|$(b)"
                """.trimIndent(),
            ),
        )
    }

    /** `` ends the chain without a catch-all: an unmatched request is the `Err` a list gives it. */
    @Test
    fun `rules ends a chain without a catch-all`() {
        assertEquals(
            "a|unmatched",
            run(
                """
                let rules = mock_http_rules {}.get { url = "/a" }.text { body = "a" }
                perform MockHttpClient { rules = rules } in do
                  let a = request { url = "http://x/a" }.send {}.text_or { def = "?" }
                  let b = match request { url = "http://x/zzz" }.send {} is
                    Ok { val = _ } -> "answered"
                    Err { error = _ } -> "unmatched"
                  "$(a)|$(b)"
                """.trimIndent(),
            ),
        )
    }
}
