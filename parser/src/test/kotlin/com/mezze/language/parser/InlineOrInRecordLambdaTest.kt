package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration

/**
 * Parser termination + correctness on a binary operator between two record-arg
 * calls inside a record-field lambda body. Caught during #239 OnSuccess work —
 * see memory/project_239_match_user_adt_bug.md.
 *
 * The shape (exact repro from std::effects::async::join_on_success before its
 * workaround):
 *
 *     Async.park { predicate = {} ->
 *       seq::any { xs = ps, p = { x } -> is_done { p = x } } ||
 *       seq::all { xs = ps, p = { x } -> is_done { p = x } } }
 *
 * This used to HANG: the record-arg list loop (`many`/`sepEndBy`) spun without
 * consuming, so the parser greedily re-tried the first `{ ... }` past the
 * trailing operator, never stabilising. FIXED by the `many` progress guard
 * (break when an iteration doesn't advance the stream) plus the P0132 work
 * budget. The shapes now PARSE correctly — a left-associative binary op with
 * both call operands — so these tests assert the parse, not merely that it
 * terminates. The `assertTimeoutPreemptively` wrapper stays as a belt-and-
 * suspenders tripwire against any regression back to a hang.
 */
class InlineOrInRecordLambdaTest {

    private val parser = MezzeParser()
    private val limit: Duration = Duration.ofSeconds(5)

    /** Parse under a hang-tripwire; assert no errors and, if given, that the
     *  tree contains [mustContain] (the operator / RHS that used to be lost). */
    private fun parsesClean(src: String, vararg mustContain: String) {
        assertTimeoutPreemptively(limit) {
            val r = parser.parseWithRecovery(src, "<test>")
            assertTrue(
                r.errors.isEmpty(),
                "expected a clean parse; got errors: ${r.errors.map { it.code to it.errorMessage }}"
            )
            val tree = r.module.decls.joinToString("\n") { it.toString() }
            for (needle in mustContain) {
                assertTrue(tree.contains(needle), "parsed tree is missing `$needle`:\n$tree")
            }
        }
    }

    @Test
    fun `sanity - simple || between two function calls outside a lambda`() {
        parsesClean("let main = {} -> bar { x = 1 } || baz { y = 2 }\n", "ParsedBinaryOp", "baz")
    }

    @Test
    fun `sanity - || between two function calls inside a 'do' block`() {
        parsesClean(
            """
            let main = {} -> foo { f = {} -> do
              let l = bar { x = 1 }
              let r = baz { y = 2 }
              l || r }
            """.trimIndent(),
            "ParsedBinaryOp"
        )
    }

    @Test
    fun `parses - || between two record-arg calls inside a record-field lambda body`() {
        parsesClean(
            """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } ||
              baz { y = 2 } }
            """.trimIndent(),
            "ParsedBinaryOp", "bar", "baz"
        )
    }

    @Test
    fun `parses - the exact OnSuccess shape that caused the bug`() {
        parsesClean(
            """
            let main = {} -> Async.park { predicate = {} ->
              seq::any { xs = ps, p = { x } -> is_done { p = x } } ||
              seq::all { xs = ps, p = { x } -> is_done { p = x } } }
            """.trimIndent(),
            "ParsedBinaryOp", "any", "all"
        )
    }

    @Test
    fun `parses - same shape with && instead of ||`() {
        parsesClean(
            """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } &&
              baz { y = 2 } }
            """.trimIndent(),
            "ParsedBinaryOp", "baz"
        )
    }

    @Test
    fun `parses - same shape with + (additive) instead of ||`() {
        parsesClean(
            """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } +
              baz { y = 2 } }
            """.trimIndent(),
            "ParsedBinaryOp", "baz"
        )
    }

    @Test
    fun `parses - same shape with == (comparison) instead of ||`() {
        parsesClean(
            """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } ==
              baz { y = 2 } }
            """.trimIndent(),
            "ParsedBinaryOp", "baz"
        )
    }
}
