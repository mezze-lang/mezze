package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Comprehensive tests for HTTP MockHttpClient implementation.
 *
 * Tests mock rules with:
 * - Method matching (specific methods, any method)
 * - URL pattern matching (exact, glob patterns)
 * - Response handling (success, errors)
 * - Rule ordering (first match wins)
 */
class HttpMockTest {

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
    // BASIC MOCK SETUP
    // =========================================================================

    @Test
    fun `mock returns configured response for exact match`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, HttpError, Method, get}
            use std::collections::map
            use std::collections::bytes

            let mock_response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "hello" }
            }

            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "/test" }, response = Ok { val = mock_response } }
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
    fun `mock returns error response`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, get}

            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "/error" }, response = Err { error = ConnectionFailed { url = "/error", reason = "mock error" } } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/error" }
                match resp is
                    Ok { val } -> "success"
                    Err { error } -> match error is
                        ConnectionFailed { url, reason } -> reason
                        _ -> "other error"
        """)
        assertEquals("mock error", (result as MezzeString).value)
    }

    // =========================================================================
    // METHOD MATCHING
    // =========================================================================

    @Test
    fun `mock matches specific GET method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, post}
            use std::collections::map
            use std::collections::bytes

            let get_response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "get" } }
            let post_response: Response = { status = 201, headers = map::empty {}, body = bytes::from_string { s = "post" } }

            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "/resource" }, response = Ok { val = get_response } },
                { methods = [Post], url = Glob { pattern = "/resource" }, response = Ok { val = post_response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/resource" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock matches specific POST method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, post}
            use std::collections::map
            use std::collections::bytes

            let get_response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "get" } }
            let post_response: Response = { status = 201, headers = map::empty {}, body = bytes::from_string { s = "post" } }

            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "/resource" }, response = Ok { val = get_response } },
                { methods = [Post], url = Glob { pattern = "/resource" }, response = Ok { val = post_response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = post { url = "http://example.com/resource", body = bytes::from_string { s = "" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(201L, (result as MezzeInt).value)
    }

    @Test
    fun `mock matches multiple methods in same rule`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, head}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Get, Head], url = Glob { pattern = "/resource" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp1 = get { url = "http://example.com/resource" }
                let resp2 = head { url = "http://example.com/resource" }
                let s1 = match resp1 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s2 = match resp2 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                s1 + s2
        """)
        assertEquals(400L, (result as MezzeInt).value)  // 200 + 200
    }

    @Test
    fun `mock with empty methods array matches any method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, post, delete}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/any-method" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let r1 = get { url = "http://example.com/any-method" }
                let r2 = post { url = "http://example.com/any-method", body = bytes::from_string { s = "" } }
                let r3 = delete { url = "http://example.com/any-method" }
                let c1 = match r1 is
                    Ok { val } -> 1
                    Err { error } -> 0
                let c2 = match r2 is
                    Ok { val } -> 1
                    Err { error } -> 0
                let c3 = match r3 is
                    Ok { val } -> 1
                    Err { error } -> 0
                c1 + c2 + c3
        """)
        assertEquals(3L, (result as MezzeInt).value)
    }

    // =========================================================================
    // URL PATTERN MATCHING - EXACT
    // =========================================================================

    @Test
    fun `mock matches exact URL path`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/exact/path" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/exact/path" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock does not match different exact path`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/exact/path" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/different/path" }
                match resp is
                    Ok { val } -> "matched"
                    Err { error } -> "no match"
        """)
        assertEquals("no match", (result as MezzeString).value)
    }

    // =========================================================================
    // URL PATTERN MATCHING - STAR (*)
    // =========================================================================

    @Test
    fun `mock matches star pattern for single segment`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/users/*" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/users/123" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock star pattern does not match nested paths`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/users/*" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/users/123/posts" }
                match resp is
                    Ok { val } -> "matched"
                    Err { error } -> "no match"
        """)
        assertEquals("no match", (result as MezzeString).value)
    }

    @Test
    fun `mock star pattern in middle of path`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/api/*/health" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/api/v1/health" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    // =========================================================================
    // URL PATTERN MATCHING - DOUBLE STAR (**)
    // =========================================================================

    @Test
    fun `mock matches doublestar for nested paths`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/api/**/health" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/api/v1/internal/health" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock doublestar matches zero segments`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/api/**/health" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/api/health" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock doublestar catch-all`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/any/path/at/all" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    // =========================================================================
    // RULE ORDERING (FIRST MATCH WINS)
    // =========================================================================

    @Test
    fun `mock uses first matching rule`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let first: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let second: Response = { status = 201, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/users/*" }, response = Ok { val = first } },
                { methods = [], url = Glob { pattern = "/users/123" }, response = Ok { val = second } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/users/123" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        // First rule (star pattern) matches first
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock specific rule before catch-all`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let specific: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let catchall: Response = { status = 404, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "/users/*" }, response = Ok { val = specific } },
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = catchall } }
            ]

            perform MockHttpClient { rules = rules } in do
                let r1 = get { url = "http://example.com/users/123" }
                let r2 = get { url = "http://example.com/other" }
                let s1 = match r1 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s2 = match r2 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                s1 * 1000 + s2
        """)
        // First request matches specific (200), second matches catch-all (404)
        assertEquals(200404L, (result as MezzeInt).value)
    }

    // =========================================================================
    // RESPONSE BODY
    // =========================================================================

    @Test
    fun `mock response body is preserved`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, body_string}
            use std::collections::map
            use std::collections::bytes

            let response: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "hello world" }
            }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> match body_string { resp = val } is
                        Ok { val = s } -> s
                        Err { error } -> "decode error"
                    Err { error } -> "request error"
        """)
        assertEquals("hello world", (result as MezzeString).value)
    }

    // =========================================================================
    // RESPONSE HEADERS
    // =========================================================================

    @Test
    fun `mock response headers are preserved`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, get_header}
            use std::collections::map
            use std::collections::bytes

            let headers = map::insert { m = map::empty {}, k = "content-type", v = "application/json" }
            let response: Response = {
                status = 200,
                headers = headers,
                body = bytes::from_string { s = "" }
            }

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> match get_header { resp = val, name = "content-type" } is
                        Some { val = h } -> h
                        None -> "not found"
                    Err { error } -> "request error"
        """)
        assertEquals("application/json", (result as MezzeString).value)
    }

    // =========================================================================
    // ERROR RESPONSES
    // =========================================================================

    @Test
    fun `mock Timeout error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, get}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = Timeout { url = "/slow", ms = 5000 } } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/slow" }
                match resp is
                    Ok { val } -> -1
                    Err { error } -> match error is
                        Timeout { url, ms } -> ms
                        _ -> -1
        """)
        assertEquals(5000L, (result as MezzeInt).value)
    }

    @Test
    fun `mock InvalidUrl error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, get}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = InvalidUrl { url = "bad", reason = "malformed" } } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> "success"
                    Err { error } -> match error is
                        InvalidUrl { url, reason } -> reason
                        _ -> "other"
        """)
        assertEquals("malformed", (result as MezzeString).value)
    }

    @Test
    fun `mock TlsError error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, get}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = TlsError { url = "/secure", reason = "cert expired" } } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "https://example.com/secure" }
                match resp is
                    Ok { val } -> "success"
                    Err { error } -> match error is
                        TlsError { url, reason } -> reason
                        _ -> "other"
        """)
        assertEquals("cert expired", (result as MezzeString).value)
    }

    @Test
    fun `mock Cancelled error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, HttpError, Method, get}

            let rules: [MockRule] = [
                { methods = [], url = Glob { pattern = "**" }, response = Err { error = Cancelled } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> "success"
                    Err { error } -> match error is
                        Cancelled -> "cancelled"
                        _ -> "other"
        """)
        assertEquals("cancelled", (result as MezzeString).value)
    }

    // =========================================================================
    // NO MATCHING RULE
    // =========================================================================

    @Test
    fun `mock returns error when no rule matches`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Post], url = Glob { pattern = "/users" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/users" }
                match resp is
                    Ok { val } -> "matched"
                    Err { error } -> "no match"
        """)
        // GET doesn't match POST-only rule
        assertEquals("no match", (result as MezzeString).value)
    }

    @Test
    fun `mock with empty rules returns error`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Method, get}

            let rules: [MockRule] = []

            perform MockHttpClient { rules = rules } in do
                let resp = get { url = "http://example.com/test" }
                match resp is
                    Ok { val } -> "matched"
                    Err { error } -> "no match"
        """)
        assertEquals("no match", (result as MezzeString).value)
    }

    // =========================================================================
    // ALL HTTP METHODS
    // =========================================================================

    @Test
    fun `mock works with PUT method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, put}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Put], url = Glob { pattern = "/users/*" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = put { url = "http://example.com/users/123", body = bytes::from_string { s = "" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock works with DELETE method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, delete}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 204, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Delete], url = Glob { pattern = "/users/*" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = delete { url = "http://example.com/users/123" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(204L, (result as MezzeInt).value)
    }

    @Test
    fun `mock works with PATCH method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, patch}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Patch], url = Glob { pattern = "/users/*" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = patch { url = "http://example.com/users/123", body = bytes::from_string { s = "" } }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock works with HEAD method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, head}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Head], url = Glob { pattern = "/users" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = head { url = "http://example.com/users" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    @Test
    fun `mock works with OPTIONS method`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, options}
            use std::collections::map
            use std::collections::bytes

            let response: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "" } }

            let rules: [MockRule] = [
                { methods = [Options], url = Glob { pattern = "/users" }, response = Ok { val = response } }
            ]

            perform MockHttpClient { rules = rules } in do
                let resp = options { url = "http://example.com/users" }
                match resp is
                    Ok { val } -> val.status
                    Err { error } -> -1
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    // =========================================================================
    // COMPLEX SCENARIOS
    // =========================================================================

    @Test
    fun `mock realistic REST API setup`() {
        val result = eval("""
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, get, post, delete}
            use std::collections::map
            use std::collections::bytes

            let list_users: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "[users]" } }
            let get_user: Response = { status = 200, headers = map::empty {}, body = bytes::from_string { s = "{user}" } }
            let create_user: Response = { status = 201, headers = map::empty {}, body = bytes::from_string { s = "{new}" } }
            let delete_user: Response = { status = 204, headers = map::empty {}, body = bytes::from_string { s = "" } }
            let not_found: Response = { status = 404, headers = map::empty {}, body = bytes::from_string { s = "not found" } }

            let rules: [MockRule] = [
                { methods = [Get], url = Glob { pattern = "/users" }, response = Ok { val = list_users } },
                { methods = [Get], url = Glob { pattern = "/users/*" }, response = Ok { val = get_user } },
                { methods = [Post], url = Glob { pattern = "/users" }, response = Ok { val = create_user } },
                { methods = [Delete], url = Glob { pattern = "/users/*" }, response = Ok { val = delete_user } },
                { methods = [], url = Glob { pattern = "**" }, response = Ok { val = not_found } }
            ]

            perform MockHttpClient { rules = rules } in do
                let r1 = get { url = "http://api.example.com/users" }
                let r2 = get { url = "http://api.example.com/users/123" }
                let r3 = post { url = "http://api.example.com/users", body = bytes::from_string { s = "" } }
                let r4 = delete { url = "http://api.example.com/users/456" }
                let r5 = get { url = "http://api.example.com/posts" }

                let s1 = match r1 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s2 = match r2 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s3 = match r3 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s4 = match r4 is
                    Ok { val } -> val.status
                    Err { error } -> -1
                let s5 = match r5 is
                    Ok { val } -> val.status
                    Err { error } -> -1

                # Encode the status codes with multipliers
                # 200 * 10000000000 + 200 * 100000000 + 201 * 1000000 + 204 * 10000 + 404
                # = 2000000000000 + 20000000000 + 201000000 + 2040000 + 404 = 2020203040404
                s1 * 10000000000 + s2 * 100000000 + s3 * 1000000 + s4 * 10000 + s5
        """)
        assertEquals(2020203040404L, (result as MezzeInt).value)
    }
}
