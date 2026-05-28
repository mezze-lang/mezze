package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * P0122 catches Rust/Swift-style match syntax attempts:
 *   match x case Foo -> ...
 *   match x { case Foo -> ... }
 *
 * Without the pre-scan in matchExpression, the inner expression parser
 * would eat `x case` as juxtaposed function application and surface the
 * misleading P0118 "Function calls must use {} syntax" error pointing
 * at `case` — which sends users in the wrong direction.
 */
class MatchSyntaxHintTest {
    private val parser = MezzeParser()

    private fun expectFail(src: String): ParseException =
        assertThrows(ParseException::class.java) {
            parser.parseParsedModule(src, "match_hint.mz")
        }

    @Test
    fun `match scrutinee followed by case fires P0122 not P0118`() {
        val src = """
            let bad = {n} -> match n case
              1 -> "one"
              _ -> "other"
        """.trimIndent()
        val ex = expectFail(src)
        assertEquals(ErrorCode.P0122_MATCH_NEEDS_IS, ex.code,
            "Expected P0122_MATCH_NEEDS_IS, got ${ex.code}: ${ex.message}")
        val msg = ex.errorMessage
        assertTrue(msg.contains("does NOT use a `case` keyword"),
            "Message should call out the case-keyword mistake: $msg")
    }

    // Note: the bare-`{` shape (`match n { 1 -> "one" }`) is intentionally
    // NOT caught by the pre-scan because it's ambiguous with legitimate
    // function-call scrutinees like `match f { x = 1 } is …`. The existing
    // post-`is` lookahead in matchExpression still catches that case when
    // it gets that far; see the test that ensures `match f { x } is` parses.

    @Test
    fun `valid match with is parses cleanly`() {
        val src = """
            let good = {n} -> match n is
              1 -> "one"
              _ -> "other"
        """.trimIndent()
        val module = parser.parseParsedModule(src, "match_ok.mz")
        assertNotNull(module)
    }
}
