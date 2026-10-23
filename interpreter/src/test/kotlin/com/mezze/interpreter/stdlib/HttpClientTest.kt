package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for HTTP client implementations:
 * - PooledHttpClient (scoped, default config)
 * - PooledHttpClientWith (scoped, custom config)
 * - SimpleHttpClient (non-scoped, default config)
 * - SimpleHttpClientWith (non-scoped, custom config)
 *
 * Per-verb helpers (get/post/put/etc.) were removed in Stage C; the chain
 * surface (HttpChainTest) covers their replacements. This file keeps the
 * client/config tests that don't overlap with HttpChainTest.
 */
class HttpClientTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * A server whose handler takes 400ms, so a client timeout shorter than that
     * is observable and a client timeout longer than it is not.
     *
     * This is how a performer-level config override is SEEN. `send {}` always
     * passes `default_http_config` — 30s — so if the performer's own config
     * reached the request, the call fails at the override's timeout; if it did
     * not, the call succeeds after 400ms. Nothing short of a real slow endpoint
     * distinguishes the two, which is why these two tests want a socket.
     */
    private val slowServer = """
        use std::effects::http::{ HttpClient, PooledHttpClientWith, SimpleHttpClientWith, Request, Response, default_http_config, request, format_error }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::effects::time::{ Time, SystemTime }
        use std::datetime::{ duration_millis }
        use std::collections::map
        use std::collections::bytes
        use std::int

        let slow: { req: Request } -> Response <Time> = { req } -> do
          Time.sleep { d = duration_millis { n = 400 } }
          { status = 200, headers = map::empty {}, body = bytes::from_str { s = "slow" }, url = req.url,
 chunks = None,
}

        let any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 2 }
        let impatient = { ...default_http_config, timeout_ms = 60 }
    """.trimIndent()

    // =========================================================================
    // PooledHttpClient (scoped, default config)
    // =========================================================================

    @Test
    fun `PooledHttpClient is a valid implementation`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_str { s = "ok" },
                url = "",
                chunks = None,
              }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = request { url = "http://test.com/api" }.send {}
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, result)
    }

    // =========================================================================
    // PooledHttpClientWith (scoped, custom config)
    // =========================================================================

    @Test
    fun `PooledHttpClientWith config beats the one the call site passed`() {
        // The field is `overrides` rather than `config` because the operation's
        // own parameter is called `config` and shadowed a performer field of
        // that name — so the override used to be dead, and this test would see
        // "ok after 400ms" instead of a timeout.
        val result = eval("""
            $slowServer
            let go: {} -> Str <*> = {} ->
              perform SystemTime in
                perform HelidonHttpServerWith { config = any_port } in
                  perform PooledHttpClientWith { overrides = impatient } in do
                    let h = HttpServer.serve { handler = slow }
                    let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/slow" }.send {} is
                      Ok { val } -> "the override did not reach the request"
                      Err { error = e } -> format_error { err = e }
                    h.stop {}
                    out
            go {}
        """.trimIndent())
        assertEquals("Request timed out after 60ms", (result as String).substringBefore(":"),
            "the performer's own config must win over the `default_http_config` that `send {}` passes")
    }

    /**
     * A server that redirects `/redir` to `/dest`, so `follow_redirects` is
     * observable: off, the client returns the 302 itself; on, it returns what
     * `/dest` said.
     *
     * `follow_redirects`, `max_redirects`, `connect_timeout_ms`, `verify_ssl`,
     * `keep_alive` and `auto_decompress` are CLIENT-level in Helidon and were
     * all dropped on the floor — the builder hardcoded its own values and never
     * looked at the config. A config that is accepted and then ignored is worse
     * than one that is not offered.
     */
    private val redirectServer = """
        use std::effects::http::{ HttpClient, PooledHttpClientWith, Request, Response, default_http_config, request, format_error, Get, Method }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::effects::http_router::{ route, or_else, otherwise, path_of }
        use std::collections::map
        use std::collections::bytes
        use std::int

        let any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 2 }

        let text_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"

        ## The Location is absolute, built from the request's own Host header —
        ## the only way the handler can know the port the OS picked.
        let host_of: { req: Request } -> Str = { req } -> match map::get { m = req.headers, k = "host" } is
          Some { val } -> val
          None -> "127.0.0.1"

        let app: { req: Request } -> Response =
          otherwise {
            routes = or_else {
              a = route { method = Get, path = "/redir", handler = { m } -> {
                status = 302,
                headers = map::insert {
                  m = map::empty {},
                  k = "location",
                  v = "http://$(host_of { req = m.req })/dest",
                },
                body = bytes::from_str { s = "go to /dest" },
                url = m.req.url,
                chunks = None,
              } },
              b = route { method = Get, path = "/dest", handler = { m } -> {
                status = 200,
                headers = map::empty {},
                body = bytes::from_str { s = "arrived" },
                url = m.req.url,
                chunks = None,
              } },
            },
            fallback = { req } -> {
              status = 404, headers = map::empty {},
              body = bytes::from_str { s = "nope" }, url = req.url,
              chunks = None,
            },
          }
    """.trimIndent()

    @Test
    fun `follow_redirects off returns the redirect itself`() {
        val result = eval("""
            $redirectServer
            let go: {} -> Int <*> = {} ->
              perform HelidonHttpServerWith { config = any_port } in
                perform PooledHttpClientWith { overrides = { ...default_http_config, follow_redirects = False } } in do
                  let h = HttpServer.serve { handler = app }
                  let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/redir" }.send {} is
                    Ok { val } -> val.status
                    Err { error = _ } -> -1
                  h.stop {}
                  out
            go {}
        """.trimIndent())
        assertEquals(302L, result, "with follow_redirects off the 302 is the answer, not a hop")
    }

    @Test
    fun `follow_redirects on arrives at the destination`() {
        val result = eval("""
            $redirectServer
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = any_port } in
                perform PooledHttpClientWith { overrides = default_http_config } in do
                  let h = HttpServer.serve { handler = app }
                  let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/redir" }.send {} is
                    Ok { val } -> text_of { resp = val }
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
        """.trimIndent())
        assertEquals("arrived", result, "the default config follows redirects, and the config now reaches the client")
    }

    @Test
    fun `an HttpConfig spread turns verify_ssl off`() {
        // A record-spread test, not a performer test: it says what
        // `{ ...default_http_config, verify_ssl = False }` evaluates to, which
        // is worth pinning because every custom config is written that way.
        val result = eval("""
            use std::effects::http::{HttpClient, default_http_config}

            let insecure_config = { ...default_http_config, verify_ssl = False }
            if insecure_config.verify_ssl then 1 else 0
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `an HttpConfig spread turns follow_redirects off`() {
        val result = eval("""
            use std::effects::http::{HttpClient, default_http_config}

            let no_redirect_config = { ...default_http_config, follow_redirects = False, max_redirects = 0 }
            if no_redirect_config.follow_redirects then 1 else 0
        """)
        assertEquals(0L, result)
    }

    // =========================================================================
    // SimpleHttpClient (non-scoped, default config) — exercises HttpClient.request
    // directly with a raw Request + HttpConfig pair, which is what the chain's
    // .send {} delegates to. Keep this one as the low-level smoke test.
    // =========================================================================

    @Test
    fun `SimpleHttpClient is a valid implementation`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, UrlPattern, Response, Method, Request, HttpConfig}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 201,
                headers = map::empty {},
                body = bytes::from_str { s = "created" },
                url = "",
                chunks = None,
              }

            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "/create" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let req: Request = {
                    method = Post,
                    url = "http://test.com/create",
                    headers = map::empty {},
                    body = Some { val = bytes::from_str { s = "{}" } },
                    timeout_ms = None
                }
                let config: HttpConfig = {
                    timeout_ms = 30000,
                    connect_timeout_ms = 10000,
                    follow_redirects = True,
                    max_redirects = 10,
                    verify_ssl = True,
                    auto_decompress = True,
                    keep_alive = True
                }
                let resp = HttpClient.request { req = req, config = config }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(201L, result)
    }

    // =========================================================================
    // SimpleHttpClientWith (non-scoped, custom config)
    // =========================================================================

    @Test
    fun `SimpleHttpClientWith config beats the one the call site passed`() {
        val result = eval("""
            $slowServer
            let go: {} -> Str <*> = {} ->
              perform SystemTime in
                perform HelidonHttpServerWith { config = any_port } in
                  perform SimpleHttpClientWith { overrides = impatient } in do
                    let h = HttpServer.serve { handler = slow }
                    let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/slow" }.send {} is
                      Ok { val } -> "the override did not reach the request"
                      Err { error = e } -> format_error { err = e }
                    h.stop {}
                    out
            go {}
        """.trimIndent())
        assertEquals("Request timed out after 60ms", (result as String).substringBefore(":"),
            "a connection-per-request client must honour its own config too")
    }

    // =========================================================================
    // Config spread syntax
    // =========================================================================

    @Test
    fun `default_http_config has correct defaults`() {
        val result = eval("""
            use std::effects::http::{default_http_config}

            default_http_config.timeout_ms
        """)
        assertEquals(30000L, result)
    }

    @Test
    fun `config spread preserves other fields`() {
        val result = eval("""
            use std::effects::http::{default_http_config}

            let custom = { ...default_http_config, timeout_ms = 5000 }
            if custom.verify_ssl then custom.timeout_ms else -1
        """)
        assertEquals(5000L, result)
    }

    @Test
    fun `config can override multiple fields`() {
        val result = eval("""
            use std::effects::http::{default_http_config}

            let custom = {
                ...default_http_config,
                timeout_ms = 1000,
                verify_ssl = False,
                follow_redirects = False
            }
            let s1 = if custom.timeout_ms == 1000 then 1 else 0
            let s2 = if custom.verify_ssl == False then 1 else 0
            let s3 = if custom.follow_redirects == False then 1 else 0
            let s4 = if custom.keep_alive == True then 1 else 0
            s1 + s2 + s3 + s4
        """)
        assertEquals(4L, result)
    }

    // =========================================================================
    // MockHttpClient (for completeness)
    // =========================================================================

    @Test
    fun `MockHttpClient with empty rules returns error`() {
        val result = eval("""
            use std::effects::http::{HttpClient, MockHttpClient, MockRule, request}

            let rules: [MockRule] = []

            perform MockHttpClient { rules = rules } in do
                let resp = request { url = "http://test.com/api" }.send {}
                match resp is
                    Ok { val } -> 1
                    Err { error } -> -1
        """)
        assertEquals(-1L, result)
    }
}
