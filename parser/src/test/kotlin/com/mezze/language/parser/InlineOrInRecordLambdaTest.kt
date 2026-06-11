package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration

/**
 * Parser non-termination on `||` between two record-arg calls inside a
 * record-field lambda body. Caught during #239 OnSuccess work — see
 * memory/project_239_match_user_adt_bug.md.
 *
 * The failing shape (exact repro from std::effects::async::join_on_success
 * before the workaround):
 *
 *     Async.park { predicate = {} ->
 *       seq::any { xs = ps, p = { x } -> is_done { p = x } } ||
 *       seq::all { xs = ps, p = { x } -> is_done { p = x } } }
 *
 * The parser appears to greedily extend the first `seq::any { ... }` past
 * the trailing `||`, never stabilising. We use `assertTimeoutPreemptively`
 * which runs the parser in a worker thread and force-kills it on timeout
 * — `Thread.interrupt()` alone is not enough because the parser's tight
 * Kotlin loop has no safepoints, so JUnit's `@Timeout` annotation would
 * hang the whole test process.
 *
 * Tests are intentionally NOT @Disabled — they should fail until the
 * parser bug is fixed, so the test bar visibly tracks the followup.
 * Workaround in stdlib is to let-bind the operands first.
 */
class InlineOrInRecordLambdaTest {

    private val parser = MezzeParser()
    private val limit: Duration = Duration.ofSeconds(5)

    @Test
    fun `sanity - simple || between two function calls outside a lambda`() {
        // Sanity: outside a lambda, `||` between two `{...}`-arg calls
        // parses fine. If this regresses, the bug got worse.
        val src = """
            let main = {} -> bar { x = 1 } || baz { y = 2 }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `sanity - || between two function calls inside a 'do' block`() {
        // Sanity: with `do`, the workaround in stdlib's join_on_success
        // parses fine. This is the let-bound variant — explicit statement
        // boundaries kill the ambiguity.
        val src = """
            let main = {} -> foo { f = {} -> do
              let l = bar { x = 1 }
              let r = baz { y = 2 }
              l || r }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `failing - || between two record-arg calls inside a record-field lambda body`() {
        // The repro. Currently hangs (caught by the 5s timeout, which
        // fails the test). Fix the parser to make this pass.
        val src = """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } ||
              baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `failing - the exact OnSuccess shape that caused the bug`() {
        // The shape that started the investigation: Async.park with a
        // compound predicate spanning two seq::* calls.
        val src = """
            let main = {} -> Async.park { predicate = {} ->
              seq::any { xs = ps, p = { x } -> is_done { p = x } } ||
              seq::all { xs = ps, p = { x } -> is_done { p = x } } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `failing - same shape with && instead of ||`() {
        // The bug likely affects && too since both are binary infix
        // operators handled with the same loop structure.
        val src = """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } &&
              baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `failing - same shape with + (additive) instead of ||`() {
        // Each operator precedence level uses the same loop pattern
        // (logicalOrExpression / logicalAndExpression / additiveExpression /
        // multiplicativeExpression / comparisonExpression in
        // ExpressionParsers.kt). If `||` loops on this shape, `+` should
        // too — and the parser fix has to address all of them at once.
        val src = """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } +
              baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `failing - same shape with == (comparison) instead of ||`() {
        val src = """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } ==
              baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }
}
