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
                url = ""
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
    fun `PooledHttpClientWith accepts custom config`() {
        val result = eval("""
            use std::effects::http::{HttpClient, PooledHttpClientWith, MockHttpClient, MockRule, UrlPattern, Response, Method, default_http_config, request}
            use std::collections::map
            use std::collections::bytes

            let custom_config = { ...default_http_config, timeout_ms = 5000 }

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_str { s = "ok" },
                url = ""
            }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            let timeout = custom_config.timeout_ms

            perform MockHttpClient { rules = rules } in do
                let resp = request { url = "http://test.com/api" }.send {}
                match resp is
                    Ok { val } -> timeout
                    Err { error } -> -1
        """)
        assertEquals(5000L, result)
    }

    @Test
    fun `PooledHttpClientWith config with verify_ssl false`() {
        val result = eval("""
            use std::effects::http::{HttpClient, default_http_config}

            let insecure_config = { ...default_http_config, verify_ssl = False }
            if insecure_config.verify_ssl then 1 else 0
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `PooledHttpClientWith config with follow_redirects false`() {
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
                url = ""
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
    fun `SimpleHttpClientWith accepts custom config`() {
        val result = eval("""
            use std::effects::http::{HttpClient, default_http_config}

            let custom_config = { ...default_http_config, timeout_ms = 1000, connect_timeout_ms = 500 }
            custom_config.timeout_ms + custom_config.connect_timeout_ms
        """)
        assertEquals(1500L, result)
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
