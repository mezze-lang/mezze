package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * DX helpers added to std::effects::http: the `mock_*` rule builders, the
 * `response_*` builders, and the `Result Response HttpError` chain terminals
 * (`text_or` / `json_or` / `on_ok` / `on_err`). These collapse the ceremony
 * of hand-building a MockRule + Response and matching on the `.send {}` result.
 */
class HttpDxHelpersTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `mock_get plus text_or collapses the fetch to one expression`() {
        val result = eval("""
            use std::effects::http::{ Http, MockHttpClient, mock_get, request }

            let fetch = { url } -> request { url }.send {}.text_or { def = "offline" }

            perform MockHttpClient { rules = [ mock_get { url = "*", body = "pong" } ] } in
              fetch { url = "https://api.mezze-lang.org/ping" }
        """.trimIndent())
        assertEquals("pong", result)
    }

    @Test
    fun `text_or returns the default on an Err (no matching rule)`() {
        val result = eval("""
            use std::effects::http::{ Http, MockHttpClient, request }

            perform MockHttpClient { rules = [] } in
              request { url = "https://nope" }.send {}.text_or { def = "offline" }
        """.trimIndent())
        assertEquals("offline", result)
    }

    @Test
    fun `mock_status builds an arbitrary-status rule and text_or reads its body`() {
        val result = eval("""
            use std::effects::http::{ Http, MockHttpClient, mock_status, request, Get }

            perform MockHttpClient { rules = [ mock_status { url = "*", methods = [Get], code = 404, body = "missing" } ] } in
              request { url = "https://x" }.send {}.text_or { def = "?" }
        """.trimIndent())
        assertEquals("missing", result)
    }

    @Test
    fun `response_ok_text builds a full Response usable directly`() {
        val result = eval("""
            use std::effects::http::{ response_ok_text, body_str }

            let resp = response_ok_text { body = "hi" }
            match body_str { resp = resp } is
              Ok { val } -> val
              Err { error = _ } -> "err"
        """.trimIndent())
        assertEquals("hi", result)
    }

    @Test
    fun `renamed from_str works end to end`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "abcd" }
            bytes::size { b = b }
        """.trimIndent())
        assertEquals(4L, result)
    }
}
