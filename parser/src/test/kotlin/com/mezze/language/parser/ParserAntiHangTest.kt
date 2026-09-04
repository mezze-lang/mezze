package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration

/**
 * The hard invariant: the parser NEVER hangs or stack-overflows on ANY input,
 * in the production `parseWithRecovery` path. Deep recursion must come back as a
 * clean located depth error (P0131), not a StackOverflowError (which is an
 * Error, not a ParseException, so it would escape the recovery catch and blow
 * up the wrapper below). Each case runs under a preemptive tripwire so a
 * regression to an actual hang fails loudly instead of stalling the suite.
 */
class ParserAntiHangTest {

    private val parser = MezzeParser()
    private val limit: Duration = Duration.ofSeconds(5)

    private fun errorsFor(src: String): List<ParseException> =
        parser.parseWithRecovery(src, "<antihang>").errors

    @Test
    fun `deeply nested parens is a clean depth error, not a stack overflow`() {
        val n = 5000
        val src = "let x = " + "(".repeat(n) + "1" + ")".repeat(n) + "\n"
        assertTimeoutPreemptively(limit) {
            val errs = errorsFor(src)
            assertTrue(
                errs.any { it.code == ErrorCode.P0131_NESTING_TOO_DEEP },
                "expected P0131 depth error; got ${errs.map { it.code }}"
            )
        }
    }

    @Test
    fun `deeply nested seq patterns don't stack-overflow`() {
        val n = 5000
        val pat = "[".repeat(n) + "1" + "]".repeat(n)
        val src = "let f = {x} -> match x is\n  $pat -> 1\n  _ -> 0\n"
        assertTimeoutPreemptively(limit) {
            // The only hard requirement: it returns cleanly (no StackOverflowError,
            // no hang). Deep nesting yields at least one located error.
            val errs = errorsFor(src)
            assertTrue(errs.isNotEmpty(), "deep pattern should surface a located error")
        }
    }

    @Test
    fun `deeply curried arrow type terminates without stack overflow`() {
        val n = 5000
        val arrows = "{} -> ".repeat(n)
        val src = "type T = $arrows Int\n"
        assertTimeoutPreemptively(limit) {
            val errs = errorsFor(src)
            assertTrue(
                errs.any { it.code == ErrorCode.P0131_NESTING_TOO_DEEP },
                "expected P0131 depth error for a runaway arrow chain; got ${errs.map { it.code }}"
            )
        }
    }

    @Test
    fun `a huge flat operator chain parses without hanging`() {
        // Not pathological, just large: exercises the precedence loops at scale
        // and confirms the work budget doesn't false-trip on legitimate input.
        // (Kept well under the depth at which the recursive AST hasher itself
        // would stack-overflow on the left-nested tree — that's a separate axis.)
        val n = 1000
        val chain = (1..n).joinToString(" || ") { "a$it" }
        val src = "let f = {} -> $chain\n"
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val errs = errorsFor(src)
            assertTrue(
                errs.none { it.code == ErrorCode.P0132_PARSER_LIMIT_EXCEEDED },
                "legitimate large input must not trip the work budget: ${errs.map { it.code }}"
            )
        }
    }
}
