package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `str::split`'s observable behaviour, pinned case by case.
 *
 * WHY THIS IS A CHARACTERIZATION TEST AND NOT A REGRESSION TEST. The usual rule
 * here is that a test must fail without the fix it accompanies. This one passes
 * both before and after, deliberately: the change it guards is a PERFORMANCE
 * change — `s.split(Pattern.quote(delimiter))` became a literal `indexOf` walk,
 * because `Pattern.quote(" ")` is `\Q \E` and that five-character pattern
 * defeats `String.split`'s fast path, so every call was compiling a regex. The
 * whole point is that nothing observable moves. A test that failed before the
 * change would mean the change broke something.
 *
 * Every expectation below was READ OFF THE OLD IMPLEMENTATION before it was
 * touched, not derived from the documentation, because three of them are
 * surprising:
 *
 *   - TRAILING empty parts are dropped: `"a,b,"` is 2 parts, and `",,"` is
 *     ZERO parts, not three.
 *   - a LEADING empty part is KEPT: `",a"` is 2 parts.
 *   - an empty INPUT is one empty part rather than none, including when the
 *     delimiter is also empty — `String.split` reaches that through its "no
 *     match" branch, which returns the input whole and so never applies the
 *     trailing-empty removal.
 *
 * The literal cases matter just as much. `std::fs` splits paths on `"."` and
 * `"/"`, and `"."` as a regular expression matches every character — which is
 * what `Pattern.quote` was there to prevent, and what `indexOf` gets for free.
 */
class StringSplitSemanticsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** Parts joined with `|` so empty parts are visible, prefixed by the count. */
    private fun split(s: String, delimiter: String): String {
        val source = """
            use std::str
            use std::collections::seq
            use std::int
            let parts = str::split { s = "$s", delimiter = "$delimiter" }
            str::concat {
              l = int::to_str { n = seq::size { xs = parts } },
              r = str::concat { l = ":", r = str::join { strings = parts, separator = "|" } }
            }
        """.trimIndent()
        return MezzeTestHelper.run(source) as String
    }

    @Test
    fun `splits on a single-character delimiter`() {
        assertEquals("3:a|b|c", split("a b c", " "))
    }

    @Test
    fun `a trailing delimiter does NOT produce a trailing empty part`() {
        assertEquals("2:a|b", split("a,b,", ","))
    }

    @Test
    fun `a leading delimiter DOES produce a leading empty part`() {
        assertEquals("2:|a", split(",a", ","))
    }

    @Test
    fun `a string of only delimiters is ZERO parts`() {
        assertEquals("0:", split(",,", ","))
    }

    @Test
    fun `an interior empty part is kept`() {
        assertEquals("3:a||b", split("a,,b", ","))
    }

    @Test
    fun `an empty input is ONE empty part, not zero`() {
        assertEquals("1:", split("", ","))
    }

    @Test
    fun `an empty input with an empty delimiter is also one empty part`() {
        assertEquals("1:", split("", ""))
    }

    @Test
    fun `an empty delimiter splits into single characters`() {
        assertEquals("3:a|b|c", split("abc", ""))
    }

    @Test
    fun `a delimiter that does not occur yields the whole string`() {
        assertEquals("1:abc", split("abc", "x"))
    }

    /**
     * `.` is a regex metacharacter matching ANY character. If the delimiter
     * ever reached a regex engine unquoted, this would be three empty parts and
     * then zero after trailing removal. `std::fs::extension` depends on it.
     */
    @Test
    fun `a dot delimiter is literal, not the regex any-character`() {
        assertEquals("2:a|b", split("a.b", "."))
    }

    /** `|` is regex alternation; unquoted it would split at every position. */
    @Test
    fun `a pipe delimiter is literal, not regex alternation`() {
        assertEquals("2:a|b", split("a|b", "|"))
    }

    /** Multi-character, and overlapping: the scan does not rescan what it consumed. */
    @Test
    fun `a multi-character delimiter consumes its whole match`() {
        assertEquals("2:|a", split("aaa", "aa"))
    }

    @Test
    fun `a multi-character delimiter in the middle`() {
        assertEquals("2:a|b", split("a--b", "--"))
    }
}
