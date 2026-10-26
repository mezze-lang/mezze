package com.mezze.language.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A `#` comment inside a dot-chain.
 *
 * The chain-continuation lookahead skipped NEWLINE only, so a comment ended the
 * expression: the links after it were orphaned and the enclosing do-block
 * reported "Do block must end with a result expression" — a message pointing
 * nowhere near the comment. It cost two detours while writing ordinary code,
 * and it hits anyone who annotates a route table.
 *
 * The `|>` chain already skipped comments for exactly this reason and says so
 * in its own note; this is the same rule for `.`.
 */
class CommentInDotChainTest {
    private val parser = MezzeParser()

    private fun parses(src: String): String? =
        try { parser.parseParsedModule(src.trimIndent(), "t.mz"); null }
        catch (e: Throwable) { e.message }

    @Test fun `a comment on its own line inside a chain`() {
        assertNull(
            parses(
                """
                let f = {} -> do
                  let xs = [1, 2, 3]
                  xs
                    # sum them
                    .fold { init = 0, f = { acc, x } -> acc + x }
                f {}
                """
            ),
        )
    }

    @Test fun `a trailing comment before the next link`() {
        assertNull(
            parses(
                """
                let f = {} -> do
                  let xs = [1, 2, 3]
                  xs.map { f = { x } -> x + 1 }   # bump each
                    .fold { init = 0, f = { acc, x } -> acc + x }
                f {}
                """
            ),
        )
    }

    @Test fun `several commented links in one chain`() {
        assertNull(
            parses(
                """
                let f = {} -> do
                  let xs = [1, 2, 3]
                  xs
                    # keep the big ones
                    .filter { p = { x } -> x > 1 }
                    # double them
                    .map { f = { x } -> x * 2 }
                    .fold { init = 0, f = { acc, x } -> acc + x }
                f {}
                """
            ),
        )
    }

    /**
     * The control: a comment that belongs to the NEXT statement must stay with
     * it. Nothing is consumed unless a `.` actually follows, so this is still
     * two statements and the `do` still ends with `y`.
     */
    @Test fun `a comment before the next statement is not swallowed`() {
        assertNull(
            parses(
                """
                let f = {} -> do
                  let x = [1, 2].fold { init = 0, f = { acc, e } -> acc + e }
                  # the answer
                  let y = x + 1
                  y
                f {}
                """
            ),
        )
    }
}
