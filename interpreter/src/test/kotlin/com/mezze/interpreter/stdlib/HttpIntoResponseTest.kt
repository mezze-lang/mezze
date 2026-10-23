package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `IntoResponse` and the per-method route verbs, plus the Response builders.
 *
 * A handler's job is to answer, and most answers are a string or some JSON.
 * `on_get`/`on_post`/… accept anything implementing `IntoResponse`, so a
 * handler no longer has to spell out a five-field Response record. `route`
 * itself is unchanged and still takes a Response-returning handler.
 *
 * One shape does NOT work and is pinned below: a handler returning a BARE
 * record literal. See [a bare response literal picks the wrong impl].
 */
class HttpIntoResponseTest {

    private val head = """
        use std::effects::http::{ Request, Response, Get, Post, Put, Delete, created, no_content, not_found, bad_request, server_error, redirect, redirect_with, response_ok_text, response_json, response_stream }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ on_get, on_post, on_put, on_delete, Matched, Routes, otherwise, or_else }
        use std::codec::json::{ JString }
        use std::collections::map
        use std::collections::bytes
        use std::collections::seq
        use std::collections::iter
        use std::str
        use std::int

        let fallback: { req: Request } -> Response = { req } -> not_found { body = "nope" }
        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"
        let header_of: { resp: Response, name: Str } -> Str = { resp, name } ->
          match resp.header { name = name } is
            Some { val } -> val
            None -> "MISSING"
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    // ---- IntoResponse ----------------------------------------------------

    @Test
    fun `a handler may answer with a plain string`() {
        val r = run(
            """
            let app = on_get { path = "/ping", handler = { m } -> "pong" }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/ping" } } }
            """.trimIndent()
        )
        assertEquals("pong", r)
    }

    @Test
    fun `a string answer is a 200`() {
        val r = run(
            """
            let app = on_get { path = "/ping", handler = { m } -> "pong" }
              .otherwise { fallback = fallback }
            app { req = get_request { url = "/ping" } }.status
            """.trimIndent()
        )
        assertEquals(200L, r)
    }

    @Test
    fun `a handler may answer with Json, and the body is encoded`() {
        val r = run(
            """
            let app = on_get { path = "/j", handler = { m } -> JString { val = "jay" } }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/j" } } }
            """.trimIndent()
        )
        assertEquals("\"jay\"", r, "a JString encodes with its quotes")
    }

    @Test
    fun `a Json answer carries the json content type`() {
        val r = run(
            """
            let app = on_get { path = "/j", handler = { m } -> JString { val = "jay" } }
              .otherwise { fallback = fallback }
            header_of { resp = app { req = get_request { url = "/j" } }, name = "content-type" }
            """.trimIndent()
        )
        assertEquals("application/json", r)
    }

    /** The identity impl: a handler that builds its own Response still works. */
    @Test
    fun `a handler may answer with a Response it built itself`() {
        val r = run(
            """
            let app = on_post { path = "/users", handler = { m } -> created { body = "made" } }
              .otherwise { fallback = fallback }
            app { req = { method = Post, url = "/users", headers = map::empty {}, body = None,
                          timeout_ms = None } }.status
            """.trimIndent()
        )
        assertEquals(201L, r)
    }

    @Test
    fun `a path parameter reaches a string-answering handler`() {
        val r = run(
            """
            let app = on_get {
              path = "/users/:id",
              handler = { m } -> m.param_or { name = "id", default = "?" },
            }.otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/users/42" } } }
            """.trimIndent()
        )
        assertEquals("42", r)
    }

    @Test
    fun `the verbs dispatch on method, not only on path`() {
        val r = run(
            """
            let app = on_get { path = "/thing", handler = { m } -> "got" }
              .or_else { b = on_put { path = "/thing", handler = { m } -> "put" } }
              .or_else { b = on_delete { path = "/thing", handler = { m } -> "gone" } }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = { method = Delete, url = "/thing",
              headers = map::empty {}, body = None, timeout_ms = None } } }
            """.trimIndent()
        )
        assertEquals("gone", r)
    }

    @Test
    fun `a method with no route falls through to the fallback`() {
        val r = run(
            """
            let app = on_get { path = "/thing", handler = { m } -> "got" }
              .otherwise { fallback = fallback }
            app { req = { method = Post, url = "/thing", headers = map::empty {}, body = None,
                          timeout_ms = None } }.status
            """.trimIndent()
        )
        assertEquals(404L, r)
    }

    /**
     * A handler returning a BARE record literal answers WRONGLY — it crashes
     * in a native having selected `impl IntoResponse for Str` and handed it the
     * record.
     *
     * This is the known `with C 'a` dictionary-plumbing family, not something
     * `IntoResponse` introduced: the constraint's type variable is solved to an
     * anonymous record (the literal leaves `chunks` undetermined, so alias
     * adoption cannot label it), and impl selection then guesses instead of
     * either resolving or failing loudly. The same shape with an annotation
     * works, and is the control below.
     *
     * Pinned so a fix is NOTICED: this test fails the day dictionary selection
     * stops guessing, whether it starts answering correctly or starts erroring.
     * The builders exist precisely so a handler never has to write this literal.
     */
    @Test
    fun `a bare response literal picks the wrong impl`() {
        val outcome = runCatching {
            run(
                """
                let app = on_get {
                  path = "/lit",
                  handler = { m } -> {
                    status = 203, headers = map::empty {},
                    body = bytes::from_str { s = "bare" }, url = "", chunks = None,
                  },
                }.otherwise { fallback = fallback }
                app { req = get_request { url = "/lit" } }.status
                """.trimIndent()
            )
        }
        val error = outcome.exceptionOrNull()
        assertTrue(
            error != null,
            "a bare literal handler is expected to still fail; got ${outcome.getOrNull()}. " +
                "If it now answers 203, dictionary selection was fixed — delete this test " +
                "and keep the annotated control.",
        )
    }

    /** The control, and the documented workaround: annotate the literal. */
    @Test
    fun `an annotated response literal answers correctly`() {
        val r = run(
            """
            let app = on_get {
              path = "/lit",
              handler = { m } -> do
                let lit: Response = {
                  status = 203, headers = map::empty {},
                  body = bytes::from_str { s = "bare" }, url = "", chunks = None,
                }
                lit,
            }.otherwise { fallback = fallback }
            app { req = get_request { url = "/lit" } }.status
            """.trimIndent()
        )
        assertEquals(203L, r)
    }

    // ---- Response builders ----------------------------------------------

    @Test
    fun `the status builders answer their own codes`() {
        val r = run(
            """
            let codes = [
              created { body = "a" }.status,
              no_content {}.status,
              bad_request { body = "b" }.status,
              not_found { body = "c" }.status,
              server_error { body = "d" }.status,
              redirect { to = "/x" }.status,
            ]
            seq::join { xs = seq::map { xs = codes, f = { x } -> int::to_str { n = x } }, sep = "," }
            """.trimIndent()
        )
        assertEquals("201,204,400,404,500,302", r)
    }

    @Test
    fun `a redirect carries a readable Location`() {
        val r = run("""header_of { resp = redirect { to = "/login" }, name = "location" }""")
        assertEquals("/login", r)
    }

    @Test
    fun `204 carries no body`() {
        val r = run("""bytes::size { b = no_content {}.body }""")
        assertEquals(0L, r)
    }

    /** Each setter preserves the others, so a chain reads in any order. */
    @Test
    fun `status then text keeps the status`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }
              .with_status { code = 201 }
              .with_text { val = "hi" }
            int::to_str { n = resp.status }
            """.trimIndent()
        )
        assertEquals("201", r)
    }

    @Test
    fun `text then status keeps the body`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }
              .with_text { val = "hi" }
              .with_status { code = 201 }
            body_of { resp = resp }
            """.trimIndent()
        )
        assertEquals("hi", r)
    }

    @Test
    fun `a chained header is readable`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }.with_header { key = "X-Trace", value = "abc" }
            header_of { resp = resp, name = "x-trace" }
            """.trimIndent()
        )
        assertEquals("abc", r, "the setter lowercases the key, which is what get_header assumes")
    }

    @Test
    fun `merged headers are readable whatever case they were given in`() {
        val r = run(
            """
            let extra = map::insert { m = map::empty {}, k = "X-Rate-Limit", v = "60" }
            let resp = response_ok_text { body = "x" }.with_headers { headers = extra }
            header_of { resp = resp, name = "x-rate-limit" }
            """.trimIndent()
        )
        assertEquals("60", r)
    }

    @Test
    fun `with_json sets the body and the content type`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }.with_json { val = JString { val = "j" } }
            str::concat {
              l = body_of { resp = resp },
              r = header_of { resp = resp, name = "content-type" },
            }
            """.trimIndent()
        )
        assertEquals("\"j\"application/json", r)
    }

    /**
     * THE pre-existing bug this surfaced: `response_json` set `Content-Type`
     * capitalised while `get_header` lowercases only the NEEDLE, so the header
     * it set could not be read by anything — including its own
     * `.content_type {}`, which answered None.
     */
    @Test
    fun `response_json's own content type is readable`() {
        val r = run(
            """
            match response_json { body = JString { val = "j" } }.content_type {} is
              Some { val } -> val
              None -> "MISSING"
            """.trimIndent()
        )
        assertEquals("application/json", r)
    }

    @Test
    fun `setting a body clears a streamed one`() {
        val r = run(
            """
            let streamed = response_stream {
              status = 200,
              headers = map::empty {},
              chunks = iter::from_seq { xs = [bytes::from_str { s = "stream" }] },
            }
            match streamed.with_text { val = "plain" }.chunks is
              Some { val = _ } -> "still-streaming"
              None -> body_of { resp = streamed.with_text { val = "plain" } }
            """.trimIndent()
        )
        assertEquals("plain", r, "chunks would otherwise win and the new body never ship")
    }
}
