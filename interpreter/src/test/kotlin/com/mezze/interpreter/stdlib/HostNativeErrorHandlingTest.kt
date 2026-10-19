package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * LOCK-IN regression tests for host-boundary natives [S8.1b/#383].
 *
 * The audit found that every host I/O native (FS, HTTP, JSON, numeric
 * parse, DateTime) already funnels JVM-level failure into a Mezze
 * Result Err / error-variant / None instead of letting an exception
 * escape into the interpreter. These tests permanently protect that:
 * each one feeds deliberately BAD INPUT to a native (via its stdlib
 * wrapper) and asserts a well-formed Mezze error value comes back.
 *
 * The safety net is structural: `MezzeTestHelper.run` evaluates the
 * program and returns its value. The program itself matches on the
 * result and returns a plain marker String. If a native ever regresses
 * to THROWING instead of returning Err, the exception escapes `run`,
 * no value is produced, and the test fails loudly — which is exactly
 * the future-proofing we want.
 *
 * These tests add NO new behaviour; they only pin down existing-good
 * behaviour. A failure here means a real host-crash hole was introduced.
 */
class HostNativeErrorHandlingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        // MemoryFileSystem is per context now; a fresh program starts empty.
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== 1. FS: missing file → NotFound Err ====================

    @Test
    fun `FS read_bytes on a missing real path returns FsError NotFound, not a thrown exception`(
        @TempDir tmp: Path
    ) {
        // A path guaranteed not to exist on the real disk.
        val missing = tmp.resolve("definitely_missing_file").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem }
            perform LocalFileSystem in
              match FileSystem.read_bytes { path = "$missing" } is
                Ok { val } -> "unexpected-ok"
                Err { error } -> match error is
                  FsNotFound { path } -> "not-found"
                  _ -> "other-err"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    // ==================== 3. Numeric parse: non-numeric → None ====================

    @Test
    fun `nat from_str on a non-numeric string returns None, not NumberFormatException`() {
        // NatNatives.fromString wraps Long.parseLong and catches
        // NumberFormatException, returning None. Lock that in.
        val code = """
            use std::nat
            match nat::from_str { s = "not_a_number" } is
              Some { val } -> "unexpected-some"
              None -> "none"
        """.trimIndent()
        assertEquals("none", eval(code))
    }

    // ==================== 4. JSON: malformed → Err ("JSON parse error") ====================

    @Test
    fun `json parse_str on malformed input returns Err, not a thrown exception`() {
        // IONatives json parse try/catch → createErr("JSON parse error: ...").
        val code = """
            use std::codec::json
            match json::parse_str { s = "{ this is not json" } is
              Ok { val } -> "unexpected-ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== 5. HTTP: bad URL → InvalidUrl error variant ====================

    @Test
    fun `http request with a malformed URL returns InvalidUrl error variant, not a thrown exception`() {
        // SimpleHttpClient performs a real request; a syntactically bad
        // URL must map to InvalidUrl at the native boundary (no network).
        val code = """
            use std::effects::http::{ HttpClient, SimpleHttpClient, request }
            perform SimpleHttpClient in
              match request { url = "://bad" }.send {} is
                Ok { val } -> "unexpected-ok"
                Err { error } -> match error is
                  InvalidUrl { url, reason } -> "invalid-url"
                  ConnectionFailed { url, reason } -> "connection-failed"
                  _ -> "other-err"
        """.trimIndent()
        // Accept either InvalidUrl (preferred) or ConnectionFailed — the
        // key lock-in is that a bad URL yields a Mezze Err, never a throw.
        val result = eval(code)
        assertTrue(
            result == "invalid-url" || result == "connection-failed",
            "Expected a mapped HTTP error variant for a bad URL, got: $result"
        )
    }

    // ==================== 6. HTTP: unreachable host → ConnectionFailed/timeout ====================

    @Test
    fun `http request to an unreachable port returns ConnectionFailed or Timeout, not a thrown exception`() {
        // 127.0.0.1:1 — nothing listens there, connection is refused
        // immediately (no network round-trip, no external dependency).
        // A short connect timeout keeps the test fast even if the OS
        // does not refuse instantly. The point: a Mezze Err, not a throw.
        val code = """
            use std::effects::http::{ HttpClient, SimpleHttpClient, request, default_http_config }
            perform SimpleHttpClient in
              match request { url = "http://127.0.0.1:1" }.send_with {
                config = { ...default_http_config, timeout_ms = 500, connect_timeout_ms = 500 }
              } is
                Ok { val } -> "unexpected-ok"
                Err { error } -> match error is
                  ConnectionFailed { url, reason } -> "connection-failed"
                  Timeout { url, ms } -> "timeout"
                  InvalidUrl { url, reason } -> "invalid-url"
                  _ -> "other-err"
        """.trimIndent()
        val result = eval(code)
        assertTrue(
            result == "connection-failed" || result == "timeout",
            "Expected ConnectionFailed/Timeout for an unreachable port, got: $result"
        )
    }

    // ==================== 7. DateTime: malformed parse input → Err ====================

    @Test
    fun `datetime parse on malformed input returns Err, not a thrown exception`() {
        // DateTimeNatives.parse wraps DateTimeFormatter; a string that
        // does not match the pattern must come back as Err, not throw.
        val code = """
            use std::datetime::{ parse, zone_of }
            match zone_of { id = "UTC" } is
              Ok { val = z } -> match parse { s = "not-a-date", p = "yyyy-MM-dd", z = z } is
                Ok { val } -> "unexpected-ok"
                Err { error } -> "err"
              Err { error } -> "zone-err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== 7b. DateTime: bad zone id → Err ====================

    @Test
    fun `datetime zone_of on an invalid zone id returns Err, not a thrown exception`() {
        val code = """
            use std::datetime::{ zone_of }
            match zone_of { id = "Not/AZone" } is
              Ok { val } -> "unexpected-ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }
}
