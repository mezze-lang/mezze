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
        use std::effects::http::{
          HttpClient, MockHttpClient, MockRule, Method, Get, Post, Put, Delete,
          Response, request, with_method, format_error, body_str, text_or,
          mock, mock_get, mock_post, mock_json, mock_status,
          Timeout, ConnectionFailed, RequestFailed
        }
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
    fun `a bare mock answers an empty 200 for any method`() {
        assertEquals(
            200L,
            run(
                """
                perform MockHttpClient { rules = [ mock { url = "**" } ] } in
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
                perform MockHttpClient { rules = [ mock { url = "**" }.get {}.text { body = "pong" } ] } in
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
                perform MockHttpClient { rules = [ mock { url = "**" }.get {}.text { body = "pong" } ] } in
                  match request { url = "http://x/ping" }.with_method { val = Post }.send {} is
                    Ok { val } -> text_of { resp = val }
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }

    /**
     * `.get {}.post {}` means POST — the last selector wins.
     *
     * Sends a GET and expects NO match, which is the only thing that
     * distinguishes replace from accumulate. Asserting that a POST works
     * passes under BOTH designs — `[Post]` and `[Get, Post]` both match a
     * POST — and the first version of this test did exactly that, so
     * accumulating methods failed nothing. Verified by breaking it.
     */
    @Test
    fun `method selection replaces rather than accumulating`() {
        assertEquals(
            "unmatched",
            run(
                """
                perform MockHttpClient { rules = [
                  mock { url = "**" }.get {}.post {}.text { body = "posted" },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.for_methods { methods = [Get, Put] }.text { body = "either" },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.status { code = 201 }.text { body = "created" },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.text { body = "accepted" }.status { code = 202 },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.header { k = "Content-Type", v = "text/csv" }.text { body = "a,b" },
                ] } in
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
                perform MockHttpClient { rules = [ mock { url = "**" }.times_out { ms = 30 } ] } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = Timeout { url = _, ms } } -> "timeout-$(int::to_str { n = ms })"
                    Err { error = _ } -> "other-error"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unreachable answers a ConnectionFailed error`() {
        assertEquals(
            "reset by peer",
            run(
                """
                perform MockHttpClient { rules = [
                  mock { url = "**" }.unreachable { reason = "reset by peer" },
                ] } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = ConnectionFailed { url = _, reason } } -> reason
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.fails {
                    error = RequestFailed { url = "http://x/y", status = 500, body = "boom" },
                  },
                ] } in
                  match request { url = "http://x/y" }.send {} is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = RequestFailed { url = _, status, body = _ } } -> int::to_str { n = status }
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.post {}.json { body = json::JString { val = "hi" } },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock { url = "**" }.raw { body = bytes::from_str { s = "xy" } },
                ] } in
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
                perform MockHttpClient { rules = [ mock_get { url = "**", body = "pong" } ] } in
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
                perform MockHttpClient { rules = [ mock_post { url = "**", body = "made" } ] } in
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
                perform MockHttpClient { rules = [
                  mock_json { url = "**", body = json::JString { val = "hi" } },
                ] } in
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
                perform MockHttpClient { rules = [
                  mock_status { url = "**", methods = [Delete], code = 404, body = "gone" },
                ] } in
                  match request { url = "http://x/y" }.with_method { val = Delete }.send {} is
                    Ok { val } -> "$(int::to_str { n = val.status })|$(text_of { resp = val })"
                    Err { error = _ } -> "unmatched"
                """.trimIndent(),
            ),
        )
    }
}
