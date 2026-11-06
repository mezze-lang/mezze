package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `|` separates the alternatives of an ARM, so it goes between whole patterns.
 *
 *     Some { val = Red } | Some { val = Pink } -> …      # the form that means it
 *     Some { val = Red | Pink } -> …                      # refused
 *
 * DECIDED, not a missing feature. Each alternative is a pattern in its own
 * right, and that is what lets it carry its own guard and bind its own
 * variables — `Some { val = Red } if n > 0 | Some { val = Pink } if m < 5`. A
 * `|` nested inside a payload could do neither, and reads as part of the
 * field's value rather than as a choice of arm.
 *
 * The verdict was already right; the MESSAGE was not. `Expected '}' but got
 * '|'` is true and tells the author nothing, so this pins the diagnosis as well
 * as the refusal.
 */
class NestedOrPatternTest {
    private val parser = MezzeParser()

    private fun parse(src: String) = parser.parseParsedModule(src.trimIndent(), "t.mz")

    @Test
    fun `an or-pattern inside a payload is refused, and says what to write`() {
        val e = assertThrows<ParseException> {
            parse(
                """
                pub let f = { v } -> match v is
                  Some { val = Red | Pink } -> 1
                  None -> 2
                """
            )
        }
        val msg = e.errorMessage + "\n" + e.hints.joinToString("\n")
        assertEquals(
            ErrorCode.P0138_OR_PATTERN_NESTED,
            e.code,
            "a coded diagnosis, so it can be explained and asserted on; got: $msg",
        )
        assertTrue(
            msg.contains("alternatives of a match ARM") || msg.contains("separates the"),
            "must say what `|` is FOR, not merely which token was expected; got: $msg",
        )
        assertTrue(
            msg.contains("Some { val = Red } | Some { val = Pink }"),
            "must show the form that means it — a diagnosis the author can act on " +
                "without guessing; got: $msg",
        )
    }

    @Test
    fun `the arm-level form parses, with one alternative per pattern`() {
        val module = parse(
            """
            pub let f = { v } -> match v is
              Some { val = Red } | Some { val = Pink } -> 1
              None -> 2
            """
        )
        val match = ((module.decls.single() as com.mezze.language.parsedast.ParsedLetDecl)
            .value as com.mezze.language.parsedast.ParsedLambda)
            .body as com.mezze.language.parsedast.ParsedMatch
        assertEquals(2, match.cases[0].patterns.size, "two whole patterns, not one nested")
        assertEquals(1, match.cases[1].patterns.size)
    }

    @Test
    fun `and each arm-level alternative may carry its own guard`() {
        val module = parse(
            """
            pub let f = { v, n } -> match v is
              Some { val = Red } if n > 0 | Some { val = Pink } if n < 5 -> 1
              None -> 2
            """
        )
        val match = ((module.decls.single() as com.mezze.language.parsedast.ParsedLetDecl)
            .value as com.mezze.language.parsedast.ParsedLambda)
            .body as com.mezze.language.parsedast.ParsedMatch
        assertEquals(
            listOf(true, true),
            match.cases[0].altGuards.map { it != null },
            "this is WHY `|` stays at the arm level: a nested `|` has nowhere to " +
                "hang a guard, and here each alternative has its own.",
        )
    }

    @Test
    fun `a nested or-pattern is refused in a plain record pattern too`() {
        // The same check guards both record-pattern parsers — the payload form
        // above and a bare `{ … }` destructuring here — so neither spelling
        // falls back to the shallow message.
        val e = assertThrows<ParseException> {
            parse(
                """
                pub let f = { v } -> match v is
                  { a = Red | Pink } -> 1
                """
            )
        }
        assertEquals(ErrorCode.P0138_OR_PATTERN_NESTED, e.code, "got: ${e.errorMessage}")
    }
}
