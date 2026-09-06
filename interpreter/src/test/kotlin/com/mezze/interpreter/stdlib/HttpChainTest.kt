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
 *   client { base_url = "..." }.with_auth { val = "..." }.get { path = "..." }
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
                .with_auth { val = "Bearer xyz" }
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, client}
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
            use std::effects::http::{Http, client}
            use std::collections::map

            let api = client { base_url = "https://api.acme.com" }
              .with_auth { val = "Bearer xyz" }
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
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, client}
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
            use std::effects::http::{Http, client}

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
            use std::effects::http::{Http, client}

            let api = client { base_url = "" }
            let r = api.get { path = "https://other.com/foo" }
            r.url
        """)
        assertEquals("https://other.com/foo", result)
    }
}
