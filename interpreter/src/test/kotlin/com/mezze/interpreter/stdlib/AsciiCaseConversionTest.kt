package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.Locale

/**
 * `str::to_upper` and `str::to_lower` must agree with the JDK's
 * `Locale.ROOT` answer on every input, and must keep agreeing if anyone tries
 * to make them faster.
 *
 * WHY THIS FILE EXISTS. Two reasons, and neither is a fast path that landed.
 *
 * FIRST, the LOCALE invariant, which is BUG 37's property. `str::to_upper` is
 * a LANGUAGE PRIMITIVE and must return the same string on every host; the
 * no-arg `toUpperCase()` does not. On a Turkish locale "i" upper cases to
 * U+0130 and to U+0049 everywhere else, so a program's output depended on the
 * machine it ran on. The expectations below are computed with `Locale.ROOT` in
 * the test rather than written out, so they cannot drift from what the
 * implementation is supposed to match.
 *
 * SECOND, it is the harness for an ASCII fast path, which was tried and
 * REVERTED. In ASCII exactly 'a'-'z' have an uppercase mapping and none expand,
 * so `c - 32` over that range looks like it should beat a table lookup per
 * character. Measured, it did beat it — 790 -> 520 ps a character — and lost
 * anyway: `toCharArray`/`new String` inflate Latin1 to UTF-16 and compress
 * back, which pushed the FIXED cost from 4 567 to 11 396 ps and made short
 * strings 0.86x. See PERF-LOG for the numbers and for why the real gap is
 * vectorisation rather than the per-character operation.
 *
 * WHAT A FUTURE ATTEMPT HAS TO SURVIVE, and what these cases therefore are:
 *
 *  - **A character at or above 0x80 must DEFER**, because outside ASCII the
 *    mapping is neither a constant offset nor length-preserving. `ß` upper
 *    cases to `SS` — two characters from one — so a fast path handling it
 *    arithmetically returns a string of the wrong LENGTH.
 *  - **Deferring has to happen even when the non-ASCII character is LAST**,
 *    after a loop has already begun transforming: the partial work must be
 *    discarded and the ORIGINAL string handed over.
 *  - **An already-converted string comes back as is**, which is the one part
 *    of the fast path that costs nothing and would be safe to keep.
 */
class AsciiCaseConversionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /**
     * The inputs, chosen to cover each hazard above. Expectations are NOT
     * hard-coded — each is the JDK's own `Locale.ROOT` answer, so this is a
     * differential test against the thing the fast path replaced.
     */
    private val cases = listOf(
        "",                       // empty
        "a",                      // one char, converts
        "A",                      // one char, already converted
        "hello world",            // plain ascii
        "HELLO WORLD",            // already upper
        "MiXeD 123 !@#",          // mixed, digits, punctuation
        "the quick brown fox",    // the bench's own input shape
        "i",                      // the Turkish-locale character
        "I",
        "straße",                 // ß -> SS: the LENGTH-CHANGING case
        "café",                   // non-ascii, last-but-one
        "ABCé",                   // non-ascii LAST, after no conversions
        "abcé",                   // non-ascii LAST, after conversions started
        "éabc",                   // non-ascii FIRST
        "ıstanbul",               // U+0131 dotless i
        "ÅÄÖ åäö",                // non-ascii both cases
        "~",                      // 0x7e, the top printable ASCII character
        "zéa",                 // non-ascii in the MIDDLE
    )

    /**
     * Only the three characters a Mezze string literal reserves.
     * Everything else — including every non-ASCII character these cases
     * turn on — goes in literally as UTF-8, because the grammar has no
     * unicode-escape form: an escape sequence is a backslash plus ONE
     * character.
     */
    private fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")

    private fun upperOf(s: String): Any = MezzeTestHelper.run(
        listOf(
            "use std::str",
            "str::to_upper { s = \"${esc(s)}\" }",
        ).joinToString("\n")
    )

    private fun lowerOf(s: String): Any = MezzeTestHelper.run(
        listOf(
            "use std::str",
            "str::to_lower { s = \"${esc(s)}\" }",
        ).joinToString("\n")
    )

    @Test
    fun `to_upper agrees with the JDK's Locale ROOT answer on every case`() {
        for (s in cases) {
            assertEquals(s.uppercase(Locale.ROOT), upperOf(s),
                "to_upper disagreed on ${esc(s)}")
        }
    }

    @Test
    fun `to_lower agrees with the JDK's Locale ROOT answer on every case`() {
        for (s in cases) {
            assertEquals(s.lowercase(Locale.ROOT), lowerOf(s),
                "to_lower disagreed on ${esc(s)}")
        }
    }

    @Test
    fun `the length-changing case is not handled arithmetically`() {
        // ß -> SS is one character becoming two. A fast path that did not
        // defer would return a 6-character string here.
        assertEquals("STRASSE", upperOf("straße"))
        assertEquals(7L, MezzeTestHelper.run(
            listOf(
                "use std::str",
                "str::size { s = str::to_upper { s = \"straße\" } }",
            ).joinToString("\n")
        ))
    }

    @Test
    fun `a non-ascii character at the end still defers after conversions began`() {
        // The loop converts a, b, c and only then meets the non-ASCII
        // character; it must discard that work rather than return it.
        assertEquals("ABCÉ", upperOf("abcé"))
        assertEquals("abcé", lowerOf("ABCÉ"))
    }

    @Test
    fun `an already-converted string comes back unchanged`() {
        assertEquals("HELLO WORLD", upperOf("HELLO WORLD"))
        assertEquals("hello world", lowerOf("hello world"))
        assertEquals("", upperOf(""))
        assertEquals("", lowerOf(""))
    }

    @Test
    fun `round-tripping through both cases is stable for ascii`() {
        assertEquals(1L, MezzeTestHelper.run(
            listOf(
                "use std::str",
                "let s = \"The Quick Brown Fox 123\"",
                "let up = str::to_upper { s = s }",
                "let down = str::to_lower { s = up }",
                "let up2 = str::to_upper { s = down }",
                "if up == up2 && str::size { s = down } == str::size { s = s } then 1 else 0",
            ).joinToString("\n")
        ))
    }
}
