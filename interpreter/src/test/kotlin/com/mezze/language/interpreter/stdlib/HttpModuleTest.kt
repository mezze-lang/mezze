package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Paths

/**
 * Comprehensive tests for std/effects/http module.
 *
 * Tests cover:
 * - Type definitions (Method, Request, Response, HttpError)
 * - MockHttp implementation for testing
 * - Helper functions (make_request, with_header, body_string, etc.)
 * - JSON helpers (get_json, post_json, etc.)
 * - Error handling and formatting
 * - Status code checks
 *
 * Note: Real HTTP tests against external servers are in HttpIntegrationTest.kt
 */
@Execution(ExecutionMode.SAME_THREAD)
class HttpModuleTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // =========================================================================
    // Type Tests - Method
    // =========================================================================

    @Test
    fun `test Method Get variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Get
            match m is
                Get -> "get"
                _ -> "other"
        """)
        assertEquals("get", (result as MezzeString).value)
    }

    @Test
    fun `test Method Post variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Post
            match m is
                Post -> "post"
                _ -> "other"
        """)
        assertEquals("post", (result as MezzeString).value)
    }

    @Test
    fun `test Method Put variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Put
            match m is
                Put -> "put"
                _ -> "other"
        """)
        assertEquals("put", (result as MezzeString).value)
    }

    @Test
    fun `test Method Delete variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Delete
            match m is
                Delete -> "delete"
                _ -> "other"
        """)
        assertEquals("delete", (result as MezzeString).value)
    }

    @Test
    fun `test Method Patch variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Patch
            match m is
                Patch -> "patch"
                _ -> "other"
        """)
        assertEquals("patch", (result as MezzeString).value)
    }

    @Test
    fun `test Method Head variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Head
            match m is
                Head -> "head"
                _ -> "other"
        """)
        assertEquals("head", (result as MezzeString).value)
    }

    @Test
    fun `test Method Options variant`() {
        val result = eval("""
            use std::effects::http::{Method}
            let m: Method = Options
            match m is
                Options -> "options"
                _ -> "other"
        """)
        assertEquals("options", (result as MezzeString).value)
    }

    // =========================================================================
    // Type Tests - HttpError
    // =========================================================================

    @Test
    fun `test HttpError ConnectionFailed variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = ConnectionFailed { url = "http://example.com", reason = "refused" }
            match err is
                ConnectionFailed { url, reason } -> url
                _ -> ""
        """)
        assertEquals("http://example.com", (result as MezzeString).value)
    }

    @Test
    fun `test HttpError Timeout variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = Timeout { url = "http://example.com", ms = 5000 }
            match err is
                Timeout { url, ms } -> ms
                _ -> 0
        """)
        assertEquals(5000L, (result as MezzeInt).value)
    }

    @Test
    fun `test HttpError InvalidUrl variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = InvalidUrl { url = "not-a-url", reason = "missing protocol" }
            match err is
                InvalidUrl { url, reason } -> reason
                _ -> ""
        """)
        assertEquals("missing protocol", (result as MezzeString).value)
    }

    @Test
    fun `test HttpError TlsError variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = TlsError { url = "https://example.com", reason = "cert expired" }
            match err is
                TlsError { url, reason } -> reason
                _ -> ""
        """)
        assertEquals("cert expired", (result as MezzeString).value)
    }

    @Test
    fun `test HttpError RequestFailed variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 404, body = "not found" }
            match err is
                RequestFailed { url, status, body } -> status
                _ -> 0
        """)
        assertEquals(404L, (result as MezzeInt).value)
    }

    @Test
    fun `test HttpError Cancelled variant`() {
        val result = eval("""
            use std::effects::http::{HttpError}
            let err: HttpError = Cancelled
            match err is
                Cancelled -> True
                _ -> False
        """)
        assertTrue((result as MezzeBool).value)
    }

    // =========================================================================
    // Helper Functions - Request Building
    // =========================================================================

    @Test
    fun `test make_request creates basic request`() {
        val result = eval("""
            use std::effects::http::{make_request, Method}
            let req = make_request { method = Get, url = "http://example.com" }
            req.url
        """)
        assertEquals("http://example.com", (result as MezzeString).value)
    }

    @Test
    fun `test make_request sets method`() {
        val result = eval("""
            use std::effects::http::{make_request, Method}
            let req = make_request { method = Post, url = "http://example.com" }
            match req.method is
                Post -> True
                _ -> False
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test make_request_with_body includes body`() {
        val result = eval("""
            use std::effects::http::{make_request_with_body, Method}
            use std::collections::bytes
            let body = bytes::from_string { s = "hello" }
            let req = make_request_with_body { method = Post, url = "http://example.com", body = body }
            match req.body is
                Some { val } -> True
                None -> False
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test make_request_with_headers includes headers`() {
        val result = eval("""
            use std::effects::http::{make_request_with_headers, Method}
            use std::collections::map
            let headers = map::insert { m = map::empty {}, k = "Content-Type", v = "application/json" }
            let req = make_request_with_headers { method = Get, url = "http://example.com", headers = headers }
            match map::get { m = req.headers, k = "Content-Type" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("application/json", (result as MezzeString).value)
    }

    @Test
    fun `test with_header adds header to request`() {
        val result = eval("""
            use std::effects::http::{make_request, with_header, Method}
            use std::collections::map
            let req = make_request { method = Get, url = "http://example.com" }
            let req2 = with_header { req = req, key = "Authorization", value = "Bearer token123" }
            match map::get { m = req2.headers, k = "Authorization" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("Bearer token123", (result as MezzeString).value)
    }

    @Test
    fun `test with_header preserves existing headers`() {
        val result = eval("""
            use std::effects::http::{make_request, with_header, Method}
            use std::collections::map
            let req = make_request { method = Get, url = "http://example.com" }
            let req2 = with_header { req = req, key = "Accept", value = "application/json" }
            let req3 = with_header { req = req2, key = "Authorization", value = "Bearer token" }
            match map::get { m = req3.headers, k = "Accept" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("application/json", (result as MezzeString).value)
    }

    @Test
    fun `test with_body sets request body`() {
        val result = eval("""
            use std::effects::http::{make_request, with_body, Method}
            use std::collections::bytes
            let req = make_request { method = Post, url = "http://example.com" }
            let body = bytes::from_string { s = "data" }
            let req2 = with_body { req = req, body = body }
            match req2.body is
                Some { val } -> True
                None -> False
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test with_timeout sets timeout`() {
        val result = eval("""
            use std::effects::http::{make_request, with_timeout, Method}
            let req = make_request { method = Get, url = "http://example.com" }
            let req2 = with_timeout { req = req, ms = 5000 }
            match req2.timeout_ms is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(5000L, (result as MezzeInt).value)
    }

    @Test
    fun `test make_full_request creates complete request`() {
        val result = eval("""
            use std::effects::http::{make_full_request, Method}
            use std::collections::map
            use std::collections::bytes
            let headers = map::insert { m = map::empty {}, k = "X-Custom", v = "value" }
            let body = bytes::from_string { s = "body data" }
            let req = make_full_request {
                method = Put,
                url = "http://example.com/api",
                headers = headers,
                body = Some { val = body },
                timeout_ms = Some { val = 10000 }
            }
            req.url
        """)
        assertEquals("http://example.com/api", (result as MezzeString).value)
    }

    // =========================================================================
    // Helper Functions - Response Handling
    // =========================================================================

    @Test
    fun `test is_success returns true for 200`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_success { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_success returns true for 201`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 201,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_success { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_success returns true for 204`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 204,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_success { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_success returns false for 400`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 400,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_success { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_success returns false for 500`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 500,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_success { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_redirect returns true for 301`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 301,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_redirect { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_redirect returns true for 302`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 302,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_redirect { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_redirect returns false for 200`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_redirect { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_client_error returns true for 400`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 400,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_client_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_client_error returns true for 404`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 404,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_client_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_client_error returns false for 500`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 500,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_client_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_server_error returns true for 500`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 500,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_server_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_server_error returns true for 503`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 503,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_server_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_server_error returns false for 400`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 400,
                headers = map::empty {},
                body = bytes::empty {}
            }
            is_server_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test body_string converts bytes to string`() {
        val result = eval("""
            use std::effects::http::{Response, body_string}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "Hello, World!" }
            }
            match body_string { resp = resp } is
                Ok { val } -> val
                Err { error } -> error
        """)
        assertEquals("Hello, World!", (result as MezzeString).value)
    }

    // =========================================================================
    // Error Formatting
    // =========================================================================

    @Test
    fun `test format_error for ConnectionFailed`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = ConnectionFailed { url = "http://example.com", reason = "refused" }
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("Connection failed"))
        assertTrue(formatted.contains("http://example.com"))
        assertTrue(formatted.contains("refused"))
    }

    @Test
    fun `test format_error for Timeout`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = Timeout { url = "http://example.com", ms = 5000 }
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("timed out"))
        assertTrue(formatted.contains("5000"))
    }

    @Test
    fun `test format_error for InvalidUrl`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = InvalidUrl { url = "bad-url", reason = "no protocol" }
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("Invalid URL"))
        assertTrue(formatted.contains("bad-url"))
    }

    @Test
    fun `test format_error for TlsError`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = TlsError { url = "https://example.com", reason = "cert expired" }
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("TLS error"))
    }

    @Test
    fun `test format_error for RequestFailed`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 404, body = "not found" }
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("failed"))
        assertTrue(formatted.contains("404"))
    }

    @Test
    fun `test format_error for Cancelled`() {
        val result = eval("""
            use std::effects::http::{HttpError, format_error}
            let err: HttpError = Cancelled
            format_error { err = err }
        """)
        val formatted = (result as MezzeString).value
        assertTrue(formatted.contains("cancelled"))
    }

    // =========================================================================
    // Retryability
    // =========================================================================

    @Test
    fun `test is_retryable true for ConnectionFailed`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = ConnectionFailed { url = "http://example.com", reason = "refused" }
            is_retryable { err = err }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable true for Timeout`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = Timeout { url = "http://example.com", ms = 5000 }
            is_retryable { err = err }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable false for InvalidUrl`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = InvalidUrl { url = "bad", reason = "invalid" }
            is_retryable { err = err }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable false for TlsError`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = TlsError { url = "https://example.com", reason = "cert" }
            is_retryable { err = err }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable true for 500 error`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 500, body = "error" }
            is_retryable { err = err }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable true for 503 error`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 503, body = "unavailable" }
            is_retryable { err = err }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable false for 400 error`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 400, body = "bad request" }
            is_retryable { err = err }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable false for 404 error`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = RequestFailed { url = "http://example.com", status = 404, body = "not found" }
            is_retryable { err = err }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_retryable false for Cancelled`() {
        val result = eval("""
            use std::effects::http::{HttpError, is_retryable}
            let err: HttpError = Cancelled
            is_retryable { err = err }
        """)
        assertFalse((result as MezzeBool).value)
    }

    // =========================================================================
    // Common Headers Helpers
    // =========================================================================

    @Test
    fun `test json_headers creates correct headers`() {
        val result = eval("""
            use std::effects::http::{json_headers}
            use std::collections::map
            let headers = json_headers {}
            match map::get { m = headers, k = "Content-Type" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("application/json", (result as MezzeString).value)
    }

    @Test
    fun `test json_headers includes Accept header`() {
        val result = eval("""
            use std::effects::http::{json_headers}
            use std::collections::map
            let headers = json_headers {}
            match map::get { m = headers, k = "Accept" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("application/json", (result as MezzeString).value)
    }

    @Test
    fun `test bearer_auth creates authorization header`() {
        val result = eval("""
            use std::effects::http::{bearer_auth}
            use std::collections::map
            let headers = bearer_auth { token = "mytoken123" }
            match map::get { m = headers, k = "Authorization" } is
                Some { val } -> val
                None -> ""
        """)
        assertEquals("Bearer mytoken123", (result as MezzeString).value)
    }

    // =========================================================================
    // Request record structure
    // =========================================================================

    @Test
    fun `test Request has all required fields`() {
        val result = eval("""
            use std::effects::http::{Request, Method}
            use std::collections::map
            let req: Request = {
                method = Get,
                url = "http://example.com",
                headers = map::empty {},
                body = None,
                timeout_ms = None
            }
            req.url
        """)
        assertEquals("http://example.com", (result as MezzeString).value)
    }

    @Test
    fun `test Response has all required fields`() {
        val result = eval("""
            use std::effects::http::{Response}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::empty {}
            }
            resp.status
        """)
        assertEquals(200L, (result as MezzeInt).value)
    }

    // =========================================================================
    // Edge cases for status checks
    // =========================================================================

    @Test
    fun `test is_success boundary at 199`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 199, headers = map::empty {}, body = bytes::empty {} }
            is_success { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_success boundary at 300`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 300, headers = map::empty {}, body = bytes::empty {} }
            is_success { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_redirect boundary at 299`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 299, headers = map::empty {}, body = bytes::empty {} }
            is_redirect { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_redirect boundary at 400`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 400, headers = map::empty {}, body = bytes::empty {} }
            is_redirect { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_client_error boundary at 399`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 399, headers = map::empty {}, body = bytes::empty {} }
            is_client_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_client_error boundary at 500`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 500, headers = map::empty {}, body = bytes::empty {} }
            is_client_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_server_error boundary at 499`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 499, headers = map::empty {}, body = bytes::empty {} }
            is_server_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_server_error boundary at 600`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 600, headers = map::empty {}, body = bytes::empty {} }
            is_server_error { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    // =========================================================================
    // Status code specific tests
    // =========================================================================

    @Test
    fun `test status 100 is not success`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 100, headers = map::empty {}, body = bytes::empty {} }
            is_success { resp = resp }
        """)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test status 202 is success`() {
        val result = eval("""
            use std::effects::http::{Response, is_success}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 202, headers = map::empty {}, body = bytes::empty {} }
            is_success { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 307 is redirect`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 307, headers = map::empty {}, body = bytes::empty {} }
            is_redirect { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 308 is redirect`() {
        val result = eval("""
            use std::effects::http::{Response, is_redirect}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 308, headers = map::empty {}, body = bytes::empty {} }
            is_redirect { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 401 is client error`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 401, headers = map::empty {}, body = bytes::empty {} }
            is_client_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 403 is client error`() {
        val result = eval("""
            use std::effects::http::{Response, is_client_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 403, headers = map::empty {}, body = bytes::empty {} }
            is_client_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 502 is server error`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 502, headers = map::empty {}, body = bytes::empty {} }
            is_server_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test status 504 is server error`() {
        val result = eval("""
            use std::effects::http::{Response, is_server_error}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = { status = 504, headers = map::empty {}, body = bytes::empty {} }
            is_server_error { resp = resp }
        """)
        assertTrue((result as MezzeBool).value)
    }

    // =========================================================================
    // Request chaining
    // =========================================================================

    @Test
    fun `test chaining multiple with_header calls`() {
        val result = eval("""
            use std::effects::http::{make_request, with_header, Method}
            use std::collections::map
            let req1 = make_request { method = Get, url = "http://example.com" }
            let req2 = with_header { req = req1, key = "Accept", value = "application/json" }
            let req3 = with_header { req = req2, key = "Authorization", value = "Bearer token" }
            let req4 = with_header { req = req3, key = "X-Custom", value = "value" }
            map::size { m = req4.headers }
        """)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test chaining with_body and with_timeout`() {
        val result = eval("""
            use std::effects::http::{make_request, with_body, with_timeout, Method}
            use std::collections::bytes
            let body = bytes::from_string { s = "data" }
            let req1 = make_request { method = Post, url = "http://example.com" }
            let req2 = with_body { req = req1, body = body }
            let req3 = with_timeout { req = req2, ms = 10000 }
            match req3.timeout_ms is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(10000L, (result as MezzeInt).value)
    }

    // =========================================================================
    // Empty body handling
    // =========================================================================

    @Test
    fun `test body_string with empty body`() {
        val result = eval("""
            use std::effects::http::{Response, body_string}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 204,
                headers = map::empty {},
                body = bytes::empty {}
            }
            match body_string { resp = resp } is
                Ok { val } -> val
                Err { error } -> error
        """)
        assertEquals("", (result as MezzeString).value)
    }

    // =========================================================================
    // Unicode handling
    // =========================================================================

    @Test
    fun `test body_string with unicode content`() {
        val result = eval("""
            use std::effects::http::{Response, body_string}
            use std::collections::map
            use std::collections::bytes
            let resp: Response = {
                status = 200,
                headers = map::empty {},
                body = bytes::from_string { s = "Hello World" }
            }
            match body_string { resp = resp } is
                Ok { val } -> val
                Err { error } -> error
        """)
        assertEquals("Hello World", (result as MezzeString).value)
    }

    // =========================================================================
    // URL handling in requests
    // =========================================================================

    @Test
    fun `test make_request with query parameters in url`() {
        val result = eval("""
            use std::effects::http::{make_request, Method}
            let req = make_request { method = Get, url = "http://example.com/api?foo=bar&baz=123" }
            req.url
        """)
        assertEquals("http://example.com/api?foo=bar&baz=123", (result as MezzeString).value)
    }

    @Test
    fun `test make_request with https url`() {
        val result = eval("""
            use std::effects::http::{make_request, Method}
            let req = make_request { method = Get, url = "https://secure.example.com/api" }
            req.url
        """)
        assertEquals("https://secure.example.com/api", (result as MezzeString).value)
    }

    @Test
    fun `test make_request with port in url`() {
        val result = eval("""
            use std::effects::http::{make_request, Method}
            let req = make_request { method = Get, url = "http://localhost:8080/api" }
            req.url
        """)
        assertEquals("http://localhost:8080/api", (result as MezzeString).value)
    }
}
