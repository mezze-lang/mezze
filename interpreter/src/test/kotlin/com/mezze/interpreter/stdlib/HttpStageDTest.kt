package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Stage D helpers: `with_query`, `ensure_ok`, `retry`, plus the
 * `std::codec::url` codec they ride on.
 */
class HttpStageDTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =========================================================================
    // url codec — encoder/decoder round-trips
    // =========================================================================

    @Test
    fun `url_encode_component percent-encodes reserved characters`() {
        val result = eval("""
            use std::codec::url::{url_encode_component}
            url_encode_component { s = "hello world & friends" }
        """)
        // Spaces -> %20, & -> %26
        assertEquals("hello%20world%20%26%20friends", result)
    }

    @Test
    fun `url_decode_component round-trips`() {
        val result = eval("""
            use std::codec::url::{url_encode_component, url_decode_component}
            let s = "café & döner"
            url_decode_component { s = url_encode_component { s = s } } == s
        """)
        assertEquals(true, result)
    }

    // Note: `(42).encode_repr {...}` and `True.encode_repr {...}` style
    // tests for primitive EncodeRepr dispatch are deliberately omitted —
    // the codec auto-derivation today only covers records and variants,
    // not primitives. Once primitives are wired into EncodeRepr we can
    // expose a polymorphic `with_query` accepting `Map Str 'a with
    // EncodeRepr 'a`. For Stage D the API is `Map Str Str` and callers
    // convert primitives at the call-site.

    // =========================================================================
    // with_query — append + percent-encode
    // =========================================================================

    @Test
    fun `with_query appends single string param`() {
        val result = eval("""
            use std::effects::http::{request}
            use std::collections::map

            let params = map::insert { m = map::empty {}, k = "q", v = "hello" }
            let r = request { url = "https://api.acme.com/search" }
              .with_query { params = params }
            r.url
        """)
        assertEquals("https://api.acme.com/search?q=hello", result)
    }

    @Test
    fun `with_query int value via pre-encoded string`() {
        // Polymorphic-value `with_query` is deferred (see EncodeRepr note);
        // callers stringify primitives at the call-site.
        val result = eval("""
            use std::effects::http::{request}
            use std::collections::map
            use std::int

            let params = map::insert { m = map::empty {}, k = "page", v = int::to_str { n = 42 } }
            let r = request { url = "https://api.acme.com/items" }
              .with_query { params = params }
            r.url
        """)
        assertEquals("https://api.acme.com/items?page=42", result)
    }

    @Test
    fun `with_query percent-encodes special characters`() {
        val result = eval("""
            use std::effects::http::{request}
            use std::collections::map

            let params = map::insert { m = map::empty {}, k = "q", v = "a b&c" }
            let r = request { url = "https://api.acme.com/s" }
              .with_query { params = params }
            r.url
        """)
        assertEquals("https://api.acme.com/s?q=a%20b%26c", result)
    }

    @Test
    fun `with_query empty params is a no-op`() {
        val result = eval("""
            use std::effects::http::{request}
            use std::collections::map

            let r = request { url = "https://api.acme.com/x" }
              .with_query { params = map::empty {} }
            r.url
        """)
        assertEquals("https://api.acme.com/x", result)
    }

    @Test
    fun `with_query uses ampersand when URL already has a query string`() {
        val result = eval("""
            use std::effects::http::{request}
            use std::collections::map

            let params = map::insert { m = map::empty {}, k = "page", v = "2" }
            let r = request { url = "https://api.acme.com/x?lang=en" }
              .with_query { params = params }
            r.url
        """)
        assertEquals("https://api.acme.com/x?lang=en&page=2", result)
    }

    // =========================================================================
    // ensure_ok — promote non-2xx to Err
    // =========================================================================

    @Test
    fun `ensure_ok passes through 2xx responses`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request, ensure_ok}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_string { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/x" }.send {}
              match ensure_ok { resp = resp } is
                Ok { val } -> val.status
                Err { error = _ } -> -1
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `Response url field carries the request URL through the mock`() {
        // Even though the mock fixture set `url = ""`, the runtime
        // injects the request URL into the Ok-wrapped Response so
        // downstream code can identify the source.
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_string { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/users/42" }.send {}
              match resp is
                Ok { val } -> val.url
                Err { error = _ } -> "ERR"
        """)
        assertEquals("http://test.com/users/42", result)
    }

    @Test
    fun `ensure_ok promotes 404 to RequestFailed`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, HttpError, Method, request, ensure_ok}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 404, headers = map::empty {}, body = bytes::from_string { s = "not found" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/x" }.send {}
              match ensure_ok { resp = resp } is
                Ok { val } -> -1
                Err { error } -> match error is
                  RequestFailed { url, status, body } -> status
                  _ -> -2
        """)
        assertEquals(404L, result)
    }

    @Test
    fun `ensure_ok propagates the request URL into RequestFailed`() {
        // The mock layer injects the request URL into the Response so that
        // ensure_ok can build a RequestFailed { url, ... } that identifies
        // the source. Verifies #179-followup.
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, HttpError, Method, request, ensure_ok}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 500, headers = map::empty {}, body = bytes::from_string { s = "boom" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/api/v2/users/42" }.send {}
              match ensure_ok { resp = resp } is
                Ok { val = _ } -> "ok"
                Err { error } -> match error is
                  RequestFailed { url, status, body } -> url
                  _ -> "other"
        """)
        assertEquals("http://test.com/api/v2/users/42", result)
    }

    @Test
    fun `ensure_ok preserves existing Err`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, request, ensure_ok}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = Cancelled } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/x" }.send {}
              match ensure_ok { resp = resp } is
                Ok { val } -> "ok"
                Err { error } -> match error is
                  Cancelled -> "cancelled"
                  _ -> "other"
        """)
        assertEquals("cancelled", result)
    }

    @Test
    fun `Response ensure_ok method on Ok value`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 500, headers = map::empty {}, body = bytes::from_string { s = "boom" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform MockHttpClient { rules = rules } in do
              let resp = request { url = "http://test.com/x" }.send {}
              match resp is
                Ok { val } -> match val.ensure_ok {} is
                  Ok { val = _ } -> -1
                  Err { error } -> match error is
                    RequestFailed { url, status, body } -> status
                    _ -> -2
                Err { error = _ } -> -3
        """)
        assertEquals(500L, result)
    }

    // =========================================================================
    // retry — succeed on first try / retry on 5xx
    // =========================================================================

    @Test
    fun `retry returns immediately on first Ok`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, request, retry}
            use std::effects::async::{LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200, headers = map::empty {}, body = bytes::from_string { s = "ok" },
                url = ""
            }
            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = mock_response } }
            ]
            perform SystemTime in perform LoomAsync in
              perform MockHttpClient { rules = rules } in do
                let req = request { url = "http://test.com/x" }
                let resp = retry { req = req, max_attempts = 3, backoff_ms = 0 }
                match resp is
                  Ok { val } -> val.status
                  Err { error = _ } -> -1
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `retry stops on non-retryable Err`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, request, retry}
            use std::effects::async::{LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = InvalidUrl { url = "x", reason = "bad" } } }
            ]
            perform SystemTime in perform LoomAsync in
              perform MockHttpClient { rules = rules } in do
                let req = request { url = "http://test.com/x" }
                let resp = retry { req = req, max_attempts = 5, backoff_ms = 0 }
                match resp is
                  Ok { val } -> "ok"
                  Err { error } -> match error is
                    InvalidUrl { url, reason } -> "invalid"
                    _ -> "other"
        """)
        assertEquals("invalid", result)
    }

    @Test
    fun `retry exhausts attempts on persistent retryable Err`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, request, retry}
            use std::effects::async::{LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = Timeout { url = "x", ms = 100 } } }
            ]
            perform SystemTime in perform LoomAsync in
              perform MockHttpClient { rules = rules } in do
                let req = request { url = "http://test.com/x" }
                let resp = retry { req = req, max_attempts = 3, backoff_ms = 0 }
                match resp is
                  Ok { val } -> "ok"
                  Err { error } -> match error is
                    Timeout { url, ms } -> "timed-out"
                    _ -> "other"
        """)
        assertEquals("timed-out", result)
    }
}
