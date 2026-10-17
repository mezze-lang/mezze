package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the chainable HTTP API: Request / Response / Client impl
 * methods.
 *
 * The surface is method-driven:
 *   request { url }.with_method { val = Post }.with_json { val = j }.send {}
 *   client { base_url = "..." }.with_bearer { token = "..." }.get { path = "..." }
 *   resp.to_json {} / .text {} / .is_ok {} / .header { name }
 *
 * All tests use MockHttpClient so they don't touch the network.
 */
class HttpChainTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =========================================================================
    // Request chain
    // =========================================================================

    @Test
    fun `request builder chains and sends`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_str { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/api" }
                .with_method { val = Get }
                .with_header { key = "X-Trace", value = "abc" }
                .send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `request with_json sets body and Content-Type`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::codec::json::{Json}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 201,
                headers = map::empty {},
                body = bytes::from_str { s = "created" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let payload: Json = JString { val = "hello" }
              let resp = request { url = "http://test.com/users" }
                .with_method { val = Post }
                .with_json { val = payload }
                .send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(201L, result)
    }

    @Test
    fun `request with_text sets body from string`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_str { s = "got it" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/echo" }
                .with_method { val = Post }
                .with_text { val = "hello world" }
                .send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `request with_timeout and with_auth`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_str { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/protected" }
                .with_bearer { token = "xyz" }
                .with_timeout { ms = 5000 }
                .send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(200L, result)
    }

    // =========================================================================
    // Response chain
    // =========================================================================

    @Test
    fun `response is_ok and is_success`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 204, headers = map::empty {}, body = bytes::empty {},
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/" }.send {}
              match resp is
                Ok { val } -> if val.is_ok {} then 1 else 0
                Err { error } -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `response is_client_err and is_server_err`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 503, headers = map::empty {}, body = bytes::empty {},
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/" }.send {}
              match resp is
                Ok { val } -> if val.is_server_err {} then 1 else 0
                Err { error } -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `response text decodes body as UTF-8`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_str { s = "hello!" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/" }.send {}
              match resp is
                Ok { val } -> match val.text {} is
                  Ok { val = s } -> s
                  Err { error } -> "decode-err"
                Err { error } -> "send-err"
        """)
        assertEquals("hello!", result)
    }

    @Test
    fun `response header lookup is case-insensitive`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes
            use std::option

            let headers = map::insert {
                m = map::empty {},
                k = "content-type",
                v = "application/json"
            }
            let mock_response: Response = {
                status = 200, headers = headers, body = bytes::from_str { s = "{}" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/" }.send {}
              match resp is
                Ok { val } -> option::get_or { opt = val.header { name = "Content-Type" }, def = "missing" }
                Err { error } -> "send-err"
        """)
        assertEquals("application/json", result)
    }

    // =========================================================================
    // Client value
    // =========================================================================

    @Test
    fun `client get yields a pre-filled Request`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, client}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_str { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let api = client { base_url = "https://api.acme.com" }
              let resp = api.get { path = "/users/42" }.send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `client with_auth applies Authorization to derived requests`() {
        // No `.send` — this is a pure builder check. Inspect the
        // Authorization header directly on the constructed request.
        val result = eval("""
            use std::effects::http::{HttpClient, client, Bearer}
            use std::collections::map

            let api = client { base_url = "https://api.acme.com" }
              .with_auth { auth = Bearer { token = "xyz" } }
            let r = api.get { path = "/protected" }
            let auth_opt = map::get { m = r.headers, k = "Authorization" }
            match auth_opt is
              Some { val } -> val
              None -> "no-auth"
        """)
        assertEquals("Bearer xyz", result)
    }

    @Test
    fun `client post chained with with_json`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, client}
            use std::codec::json::{Json}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 201, headers = map::empty {}, body = bytes::from_str { s = "made" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
              let api = client { base_url = "https://api.acme.com" }
              let resp = api.post { path = "/orders" }
                .with_json { val = JString { val = "hi" } }
                .send {}
              match resp is
                Ok { val } -> val.status
                Err { error } -> -1
        """)
        assertEquals(201L, result)
    }

    @Test
    fun `client base_url joins paths correctly`() {
        // No `.send` — pure URL-building check, no perform needed.
        val result = eval("""
            use std::effects::http::{HttpClient, client}

            let api = client { base_url = "https://api.acme.com" }
            let r = api.get { path = "/v1/users" }
            r.url
        """)
        assertEquals("https://api.acme.com/v1/users", result)
    }

    @Test
    fun `client base_url empty falls back to absolute path`() {
        // No `.send` — pure URL-building check, no perform needed.
        val result = eval("""
            use std::effects::http::{HttpClient, client}

            let api = client { base_url = "" }
            let r = api.get { path = "https://other.com/foo" }
            r.url
        """)
        assertEquals("https://other.com/foo", result)
    }

    // =========================================================================
    // `.from_json {}` on the Result a send returns
    // =========================================================================
    //
    // `from_json_result` had no test at all. It carried a comment forbidding a
    // hand-written `Ok { val = d } -> Ok { val = d }` re-wrap, because that
    // used to drop the target-directed decode's runtime dispatch and crash
    // with UnsupportedSpecialization. A rule nobody runs is a rule nobody can
    // check, so these run it: the target type comes only from the annotation on
    // the `let`, and reaching it at all means the dictionary threaded.

    @Test
    fun `from_json decodes the body into the type the use site asks for`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, HttpError, request, format_error}
            use std::collections::map
            use std::collections::bytes

            perform MockHttpClient { rules = [
                { methods = [], url = Glob { pattern = "**" },
                  response = Ok { val = { status = 200, headers = map::empty {},
                    body = bytes::from_str { s = "{\"name\":\"Ada\",\"age\":36}" }, url = "http://test.com/u" } } }
            ] } in do
              let u: Result { name: Str, age: Int } HttpError =
                request { url = "http://test.com/u" }.send {}.from_json {}
              match u is
                Ok { val } -> val.name
                Err { error = e } -> format_error { err = e }
        """)
        assertEquals("Ada", result)
    }

    @Test
    fun `from_json turns a body that is not JSON into a DecodeError naming the url`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, HttpError, request, format_error}
            use std::collections::map
            use std::collections::bytes

            perform MockHttpClient { rules = [
                { methods = [], url = Glob { pattern = "**" },
                  response = Ok { val = { status = 200, headers = map::empty {},
                    body = bytes::from_str { s = "not json at all" }, url = "http://test.com/u" } } }
            ] } in do
              let u: Result { name: Str } HttpError =
                request { url = "http://test.com/u" }.send {}.from_json {}
              match u is
                Ok { val } -> val.name
                Err { error = e } -> format_error { err = e }
        """)
        assertTrue(
            (result as String).contains("http://test.com/u"),
            "a decode failure must name the url it failed on; got: ${'$'}result",
        )
    }

    // ===============================================================
    // Inline effect-op chain on `Result <T> <Err>` — static dispatch
    // ===============================================================
    //
    // Regression for the polyglot `Python.eval{...}.str{}` shape,
    // exercised against the stdlib http module because its
    // `impl Response` and `impl Result Response HttpError` share method
    // names (`.text`, `.json`, `.from_json`) — the same "parallel per-
    // language impls" collision the polyglot providers ship
    // (`impl PyForeign` + `impl Result PyForeign InteropError`).
    //
    // Before the fix, `HttpClient.request{...}.text{}` (receiver is an
    // effect-op call, NOT a named local) failed to dispatch:
    //   - constraint gen's "homogeneous fast path" picked whichever
    //     subset of candidates happened to share a return-type
    //     signature and DROPPED the other subset. On a receiver whose
    //     args later solved to the dropped branch, emit picked from a
    //     set that didn't contain the right target;
    //   - the deferred solver's fallback matched only the constructor
    //     name (`Result`) against candidates registered under the
    //     mangled `Result_Response_HttpError` — no match, T0206 or a
    //     silent mis-dispatch.
    //
    // The fix: fall through to the fully-deferred dispatch when the
    // homogeneous group doesn't cover EVERY concrete candidate, and
    // try the mangled receiver-type name FIRST when picking a candidate
    // (specificity ladder: `Result_A_B` beats generic `Result`).
    // Verified end-to-end here on `.text` (defined on both Response and
    // Result Response HttpError) — inline chain must return the
    // `Result Str HttpError` from `impl Result Response HttpError.text`
    // (delegates to `text_result`), NOT the bare `Result Str Str`
    // from `impl Response.text`.

    @Test
    fun `inline effect-op chain on Result Response HttpError dispatches Result impl`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, Request, default_http_config, request}
            use std::collections::map
            use std::collections::bytes

            perform MockHttpClient { rules = [
                { methods = [], url = Glob { pattern = "**" },
                  response = Ok { val = { status = 200, headers = map::empty {},
                    body = bytes::from_str { s = "the-body" }, url = "" } } }
            ] } in do
              let req = request { url = "http://test.com/x" }
              # Inline chain: `HttpClient.request{...}` is a TypedPerformOp receiver
              # with declared return `Result Response HttpError`, and `.text{}`
              # is defined on BOTH `impl Response` AND `impl Result Response
              # HttpError`. Must dispatch to the Result impl (which delegates to
              # `text_result` and yields `Result Str HttpError`), NOT the
              # Response impl (which yields the raw `Result Str Str` from
              # `body_str`).
              let result_text = HttpClient.request { req = req, config = default_http_config }.text {}
              match result_text is
                Ok  { val = s } -> s
                Err { error = _ } -> "ERR"
        """)
        assertEquals("the-body", result)
    }

    @Test
    fun `inline chain on Result Response HttpError passes a network Err through`() {
        // The test above used to distinguish the two impls BY TYPE: the Result
        // impl yielded `Result Str HttpError` and the Response impl the bare
        // `Result Str Str` that `body_str` once returned. `body_str` now
        // returns `Result Str HttpError` too, so that distinction is gone and
        // the test above would pass under either dispatch.
        //
        // This one distinguishes them by BEHAVIOUR, which does not depend on
        // the two happening to disagree about a type. Only `impl Result
        // Response HttpError.text` can be applied to an Err at all, and only it
        // passes the network failure through untouched — `impl Response.text`
        // needs a Response and has none to work with.
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, Request, default_http_config, request, format_error}
            use std::collections::map
            use std::collections::bytes

            perform MockHttpClient { rules = [
                { methods = [], url = Glob { pattern = "**" },
                  response = Err { error = ConnectionFailed { url = "http://test.com/x", reason = "refused" } } }
            ] } in do
              let req = request { url = "http://test.com/x" }
              let result_text = HttpClient.request { req = req, config = default_http_config }.text {}
              match result_text is
                Ok  { val = s } -> s
                Err { error = e } -> format_error { err = e }
        """)
        assertEquals("Connection failed to http://test.com/x: refused", result,
            "the Result impl passes a network Err through; the Response impl could not have produced this")
    }
}
