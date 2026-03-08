package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Comprehensive tests for HTTP MockHttpClient implementation.
 * Migrated from V1 to V2 with unboxed values.
 */
class HttpMockTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

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
        assertEquals(200L, result)
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
        assertEquals("mock error", result)
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
        assertEquals(200L, result)
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
        assertEquals(201L, result)
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
        assertEquals(3L, result)
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
        assertEquals(200L, result)
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
        assertEquals("no match", result)
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
        assertEquals(200L, result)
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
        assertEquals(200L, result)
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
        assertEquals(200L, result)
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
        assertEquals("hello world", result)
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
        assertEquals("application/json", result)
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
        assertEquals(5000L, result)
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
        assertEquals("cancelled", result)
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
        assertEquals("no match", result)
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
        assertEquals("no match", result)
    }
}
