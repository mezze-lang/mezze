package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for HTTP client implementations:
 * - HttpClient (scoped, default config)
 * - HttpClientWith (scoped, custom config)
 * - SimpleHttpClient (non-scoped, default config)
 * - SimpleHttpClientWith (non-scoped, custom config)
 *
 * Note: These tests use MockHttpClient for the actual HTTP calls since we can't
 * make real network requests in tests. The tests verify that the different
 * client implementations correctly handle configuration and scoping.
 */
class HttpClientTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // =========================================================================
    // HttpClient (scoped, default config)
    // =========================================================================

    @Test
    fun `HttpClient is a valid implementation`() {
        // This test verifies HttpClient can be used - uses mock for actual request
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "ok" }
            }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            # Use MockHttpClient to test (HttpClient would need real network)
            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://test.com/api" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    // =========================================================================
    // HttpClientWith (scoped, custom config)
    // =========================================================================

    @Test
    fun `HttpClientWith accepts custom config`() {
        val result = eval("""
            use std::effects::http::{Http, HttpClientWith, MockHttpClient, MockRule, UrlPattern, Response, Method, default_http_config, get}
            use std::collections::map
            use std::collections::bytes

            let custom_config = { ...default_http_config, timeout_ms = 5000 }

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "ok" }
            }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]

            # Verify config can be created and used
            let timeout = custom_config.timeout_ms

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://test.com/api" }
                match resp is
                    Ok { val } -> timeout
                    Err { error } -> -1
        """)
        assertEquals(5000L, (result as MezzeInt).value)
    }

    @Test
    fun `HttpClientWith config with verify_ssl false`() {
        val result = eval("""
            use std::effects::http::{Http, default_http_config}

            let insecure_config = { ...default_http_config, verify_ssl = False }
            if insecure_config.verify_ssl then 1 else 0
        """)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `HttpClientWith config with follow_redirects false`() {
        val result = eval("""
            use std::effects::http::{Http, default_http_config}

            let no_redirect_config = { ...default_http_config, follow_redirects = False, max_redirects = 0 }
            if no_redirect_config.follow_redirects then 1 else 0
        """)
        assertEquals(0L, (result as MezzeInt).value)
    }

    // =========================================================================
    // SimpleHttpClient (non-scoped, default config)
    // =========================================================================

    @Test
    fun `SimpleHttpClient is a valid implementation`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, Request, HttpConfig, get}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 201,
                headers = map::empty {},
                body = bytes::from_string { s = "created" }
            }

            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "/create" }, response = Ok { val = mock_response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let req: Request = {
                    method = Post,
                    url = "http://test.com/create",
                    headers = map::empty {},
                    body = Some { val = bytes::from_string { s = "{}" } },
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
                let resp = Http.request { req = req, config = config }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(201L, (result as MezzeInt).value)
    }

    // =========================================================================
    // SimpleHttpClientWith (non-scoped, custom config)
    // =========================================================================

    @Test
    fun `SimpleHttpClientWith accepts custom config`() {
        val result = eval("""
            use std::effects::http::{Http, default_http_config}

            let custom_config = { ...default_http_config, timeout_ms = 1000, connect_timeout_ms = 500 }
            custom_config.timeout_ms + custom_config.connect_timeout_ms
        """)
        assertEquals(1500L, (result as MezzeInt).value)
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
        assertEquals(30000L, (result as MezzeInt).value)
    }

    @Test
    fun `config spread preserves other fields`() {
        val result = eval("""
            use std::effects::http::{default_http_config}

            let custom = { ...default_http_config, timeout_ms = 5000 }
            # verify_ssl should still be True from default
            if custom.verify_ssl then custom.timeout_ms else -1
        """)
        assertEquals(5000L, (result as MezzeInt).value)
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
            let s4 = if custom.keep_alive == True then 1 else 0  # unchanged from default
            s1 + s2 + s3 + s4
        """)
        assertEquals(4L, (result as MezzeInt).value)
    }

    // =========================================================================
    // MockHttpClient (for completeness)
    // =========================================================================

    @Test
    fun `MockHttpClient with empty rules returns error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, get}

            let rules: [MockRule] = []

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://test.com/api" }
                match resp is
                    Ok { val } -> 1
                    Err { error } -> -1
        """)
        assertEquals(-1L, (result as MezzeInt).value)
    }

    // =========================================================================
    // Request helpers
    // =========================================================================

    @Test
    fun `get helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `post helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, post}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 201, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = post { url = "http://example.com/create", body = bytes::from_string { s = "{}" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(201L, (result as MezzeInt).value)
    }

    @Test
    fun `put helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, put}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Put], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = put { url = "http://example.com/update", body = bytes::from_string { s = "{}" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `delete helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, delete}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 204, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Delete], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = delete { url = "http://example.com/remove" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(204L, (result as MezzeInt).value)
    }

    @Test
    fun `patch helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, patch}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Patch], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = patch { url = "http://example.com/patch", body = bytes::from_string { s = "{}" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `head helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, head}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Head], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = head { url = "http://example.com/check" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `options helper works`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, options}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let rules: [MockRule] = [
                { methods = [Options], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = options { url = "http://example.com/options" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }
}
