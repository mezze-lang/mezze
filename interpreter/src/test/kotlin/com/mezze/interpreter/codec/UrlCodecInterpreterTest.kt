package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for the URL codec native functions.
 *
 * Exercises the format-neutral `__native_url_parse` / `__native_url_render`
 * pair introduced in Phase 1 of the codec redesign (task #86).
 *
 * `Url` record shape (see `stdlib/std/codec/url.mz` + `UrlNatives.java`):
 * ```
 * type Url = {
 *   scheme:    Option Str,
 *   user_info: Option Str,
 *   host:      Option Str,
 *   port:      Option Int,
 *   path:      Str,          # empty when absent, per RFC 3986
 *   query:     Option Str,
 *   fragment:  Option Str,
 * }
 * ```
 */
class UrlCodecInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Helpers — result inspection
    // ========================================================================

    /** Unwrap Ok(Url) to the Url record. */
    private fun okUrl(result: Any): MezzeRecord {
        assertTrue(result is MezzeVariant, "expected MezzeVariant, got ${result?.javaClass}")
        val variant = result as MezzeVariant
        assertEquals("Ok", variant.constructorName,
            "expected Ok, got: ${variant.constructorName}")
        val inner = variant.payloadValue as MezzeRecord
        return inner.getField("val") as MezzeRecord
    }

    /** Assert an Option-Str field is None. */
    private fun assertNone(record: MezzeRecord, field: String) {
        val v = record.getField(field) as MezzeVariant
        assertEquals("None", v.constructorName,
            "field '$field' expected None, got ${v.constructorName}")
    }

    /** Assert an Option-Str field is Some with the given value. */
    private fun assertSomeStr(record: MezzeRecord, field: String, expected: String) {
        val v = record.getField(field) as MezzeVariant
        assertEquals("Some", v.constructorName,
            "field '$field' expected Some, got ${v.constructorName}")
        val inner = v.payloadValue as MezzeRecord
        assertEquals(expected, inner.getField("val") as String,
            "field '$field' Some payload mismatch")
    }

    /** Assert an Option-Int field is Some with the given value. */
    private fun assertSomeInt(record: MezzeRecord, field: String, expected: Long) {
        val v = record.getField(field) as MezzeVariant
        assertEquals("Some", v.constructorName,
            "field '$field' expected Some, got ${v.constructorName}")
        val inner = v.payloadValue as MezzeRecord
        val n = inner.getField("val")
        val actual = when (n) {
            is Long -> n
            is Int -> n.toLong()
            else -> fail("field '$field' inner val not numeric: $n")
        }
        assertEquals(expected, actual, "field '$field' Some payload mismatch")
    }

    /** Assert the result is an Err carrying a Phase-1 ParseError record. */
    private fun assertParseErr(result: Any) {
        assertTrue(result is MezzeVariant, "expected MezzeVariant, got ${result?.javaClass}")
        val variant = result as MezzeVariant
        assertEquals("Err", variant.constructorName,
            "expected Err, got: ${variant.constructorName}")
        val inner = variant.payloadValue as MezzeRecord
        val error = inner.getField("error") as MezzeRecord
        // Phase-1 ParseError: { path: [Str], reason: Str, input_ref: Option Str }
        assertNotNull(error.getField("path"), "ParseError.path missing")
        val reason = error.getField("reason") as String
        assertTrue(reason.isNotEmpty(), "ParseError.reason must be non-empty")
        val inputRef = error.getField("input_ref")
        assertTrue(inputRef is MezzeVariant, "input_ref must be Option (MezzeVariant)")
    }

    // ========================================================================
    // Parse — simple URLs
    // ========================================================================

    @Test
    fun `parse simple https URL`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "https://example.com" }
        """.trimIndent()))
        assertSomeStr(url, "scheme", "https")
        assertSomeStr(url, "host", "example.com")
        assertEquals("", url.getField("path") as String)
        assertNone(url, "port")
        assertNone(url, "user_info")
        assertNone(url, "query")
        assertNone(url, "fragment")
    }

    @Test
    fun `parse http URL with path`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "http://example.com/some/path" }
        """.trimIndent()))
        assertSomeStr(url, "scheme", "http")
        assertSomeStr(url, "host", "example.com")
        assertEquals("/some/path", url.getField("path") as String)
    }

    @Test
    fun `parse relative URL without scheme`() {
        // A bare "example.com" is a relative URI reference: the parser sees
        // no scheme, no authority — just a path segment.
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "example.com" }
        """.trimIndent()))
        assertNone(url, "scheme")
        assertNone(url, "host")
        assertEquals("example.com", url.getField("path") as String)
    }

    @Test
    fun `parse full URL with userinfo port query fragment`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "https://alice:secret@host.example:8443/p?q=1&r=2#frag" }
        """.trimIndent()))
        assertSomeStr(url, "scheme", "https")
        assertSomeStr(url, "user_info", "alice:secret")
        assertSomeStr(url, "host", "host.example")
        assertSomeInt(url, "port", 8443)
        assertEquals("/p", url.getField("path") as String)
        assertSomeStr(url, "query", "q=1&r=2")
        assertSomeStr(url, "fragment", "frag")
    }

    // ========================================================================
    // Component absence — None for missing bits
    // ========================================================================

    @Test
    fun `URL without port has None port`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "http://example.com/path" }
        """.trimIndent()))
        assertNone(url, "port")
    }

    @Test
    fun `URL without fragment has None fragment`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "http://example.com/path?q=1" }
        """.trimIndent()))
        assertSomeStr(url, "query", "q=1")
        assertNone(url, "fragment")
    }

    @Test
    fun `URL without userinfo has None user_info`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "http://example.com:8080/path" }
        """.trimIndent()))
        assertNone(url, "user_info")
        assertSomeInt(url, "port", 8080)
    }

    // ========================================================================
    // Round-trip — parse then render
    // ========================================================================

    @Test
    fun `roundtrip preserves scheme host path`() {
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "http://example.com/some/path" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("http://example.com/some/path", rendered)
    }

    @Test
    fun `roundtrip preserves port`() {
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "http://example.com:8080/x" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("http://example.com:8080/x", rendered)
    }

    @Test
    fun `roundtrip preserves query`() {
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "http://example.com/x?a=1&b=2" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("http://example.com/x?a=1&b=2", rendered)
    }

    @Test
    fun `roundtrip preserves fragment`() {
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "http://example.com/x#top" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("http://example.com/x#top", rendered)
    }

    @Test
    fun `roundtrip preserves userinfo`() {
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "https://user:pw@example.com/p" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("https://user:pw@example.com/p", rendered)
    }

    // ========================================================================
    // Unicode / percent-encoding
    // ========================================================================

    @Test
    fun `parse percent-encoded path decodes UTF-8`() {
        // "%C3%A9" is UTF-8 for é. The native uses URI#getPath() which
        // decodes percent-escapes, so the record carries the decoded
        // Unicode form. The (fenced) rendering side re-encodes.
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "https://example.com/caf%C3%A9" }
        """.trimIndent()))
        assertEquals("/café", url.getField("path") as String)
    }

    @Test
    fun `parse percent-encoded query decodes UTF-8`() {
        val url = okUrl(eval("""
            use std::codec::url
            url::parse { s = "https://example.com/s?q=caf%C3%A9" }
        """.trimIndent()))
        assertSomeStr(url, "query", "q=café")
    }

    @Test
    fun `roundtrip re-encodes decoded path`() {
        // parse decodes "%C3%A9" to "é", render should re-encode it — so
        // the input URL and the rendered URL are equal.
        val rendered = eval("""
            use std::codec::url
            match url::parse { s = "https://example.com/caf%C3%A9" } is
              Ok { val = u } -> url::render { url = u }
              Err { error = _ } -> "ERR"
        """.trimIndent()) as String
        assertEquals("https://example.com/caf%C3%A9", rendered)
    }

    // ========================================================================
    // Malformed URLs — Err with well-formed ParseError
    // ========================================================================

    @Test
    fun `parse triple-slash bare authority errors`() {
        // "://" — scheme name required by RFC 3986 for an authority.
        assertParseErr(eval("""
            use std::codec::url
            url::parse { s = "://" }
        """.trimIndent()))
    }

    @Test
    fun `parse URL with illegal space errors`() {
        // A raw space is not a legal URI character anywhere; must be
        // percent-encoded. java.net.URI rejects.
        assertParseErr(eval("""
            use std::codec::url
            url::parse { s = "http://example .com/path" }
        """.trimIndent()))
    }

    @Test
    fun `parse malformed IPv6 host errors`() {
        // Missing closing bracket — URI throws URISyntaxException.
        assertParseErr(eval("""
            use std::codec::url
            url::parse { s = "http://[::1/path" }
        """.trimIndent()))
    }

    // ========================================================================
    // Render — building a Url record from scratch
    // ========================================================================

    @Test
    fun `render minimal URL from record`() {
        // Build a Url with just scheme+host and confirm render produces the
        // expected canonical form.
        val rendered = eval("""
            use std::codec::url::{ Url, render }
            let u: Url = {
              scheme = Some { val = "https" },
              user_info = None,
              host = Some { val = "example.com" },
              port = None,
              path = "",
              query = None,
              fragment = None,
            }
            render { url = u }
        """.trimIndent()) as String
        assertEquals("https://example.com", rendered)
    }

    @Test
    fun `render URL with all components from record`() {
        val rendered = eval("""
            use std::codec::url::{ Url, render }
            let u: Url = {
              scheme = Some { val = "https" },
              user_info = Some { val = "alice:pw" },
              host = Some { val = "host.example" },
              port = Some { val = 8443 },
              path = "/p",
              query = Some { val = "q=1" },
              fragment = Some { val = "top" },
            }
            render { url = u }
        """.trimIndent()) as String
        assertEquals("https://alice:pw@host.example:8443/p?q=1#top", rendered)
    }

    /**
     * An unrenderable `Url` is a GUEST error, not a JVM exception.
     *
     * `Url` is a record of free-form `Option Str` fields, so a program can build one
     * whose components do not form a URI. `render` wrapped the resulting
     * `URISyntaxException` in an `IllegalArgumentException`, which Truffle treats as
     * a HOST failure — so ordinary code saw
     *
     *     java.lang.IllegalArgumentException: URL render error:
     *     Illegal character in scheme name at index 2: ht tp://example.com/
     *
     * The asymmetry was with `parse` in the same native file, which already caught
     * both URISyntaxException and RuntimeException and returned an `Err` rather than,
     * in its own comment's words, "propagating to the interpreter". Same shape as
     * S-9's `frac::make` beside `frac::divide`.
     *
     * `render` keeps its total `-> Str` type rather than becoming a `Result`: `parse`
     * only ever yields a VALID `Url`, so `render ∘ parse` cannot fail, and the only
     * way here is a hand-built `Url` — a program error, not untrusted input.
     */
    @Test
    fun `rendering an invalid Url is a guest error, not a host exception`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                use std::codec::url::{ Url, render }

                let bad: Url = {
                  scheme = Some { val = "ht tp" },
                  user_info = None,
                  host = Some { val = "example.com" },
                  port = None,
                  path = "/",
                  query = None,
                  fragment = None,
                }

                render { url = bad }
            """.trimIndent())
        }
        val message = t.message ?: ""
        assertTrue(
            message.contains("do not form a valid URI"),
            "expected the Url render diagnostic, got ${t::class.java.name}: $message",
        )
        val polyglot = t as? org.graalvm.polyglot.PolyglotException
        if (polyglot != null) {
            assertTrue(polyglot.isGuestException,
                "an unrenderable Url must be a guest error, not a host one: $message")
            assertTrue(!polyglot.isInternalError,
                "an unrenderable Url must not surface as an internal error: $message")
        }
    }
}
