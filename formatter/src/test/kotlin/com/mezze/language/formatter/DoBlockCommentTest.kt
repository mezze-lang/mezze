package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Comments that sit before a do-block's FINAL expression.
 *
 * A do block is `statements + result`. `ParsedStatement` carries
 * leadingComments/trailingComment, but `ParsedDo.result` is a bare
 * `ParsedExpr` with nowhere to put them — so a comment above the last
 * expression in the block was parsed and then dropped on the floor.
 *
 * That is silent data loss: running `mez fmt` over a documented file
 * deleted the documentation. Comments in every other position (top level,
 * first line of a block, above a non-final statement) survived, which is
 * what made it easy to miss.
 */
class DoBlockCommentTest {
    private val formatter = Formatter()

    @Test
    fun `comment above the final expression survives formatting`() {
        val input = """
            let main = {} -> do
              let one = 1

              # explains the result
              one
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# explains the result"),
            "comment above the do-block result was dropped:\n$output")
    }

    @Test
    fun `comment above the final expression survives with no blank line`() {
        val input = """
            let main = {} -> do
              let one = 1
              # explains the result
              one
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# explains the result"),
            "comment above the do-block result was dropped:\n$output")
    }

    @Test
    fun `several comments above the final expression all survive`() {
        val input = """
            let main = {} -> do
              let one = 1

              # first line
              # second line
              one
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# first line"), "first comment dropped:\n$output")
        assertTrue(output.contains("# second line"), "second comment dropped:\n$output")
    }

    @Test
    fun `comment above a non-final statement still survives`() {
        // Control: this path already worked. It must keep working.
        val input = """
            let main = {} -> do
              # about the first
              let one = 1
              one
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# about the first"),
            "leading statement comment regressed:\n$output")
    }

    @Test
    fun `formatting is idempotent for a do block with comments`() {
        val input = """
            let main = {} -> do
              let one = 1

              # explains the result
              one
        """.trimIndent()
        val once = formatter.format(input)
        val twice = formatter.format(once)
        assertEquals(once, twice, "formatter is not idempotent:\n---once---\n$once\n---twice---\n$twice")
    }

    @Test
    fun `a blank line between statements is preserved`() {
        val input = """
            let main = {} -> do
              let one = 1

              let two = 2
              two
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals(input + "\n", output,
            "blank line between do-block statements was dropped:\n$output")
    }

    @Test
    fun `a do block round-trips comments and blank lines exactly`() {
        val input = """
            let main = {} -> do
              # about the first
              let one = 1

              # about the result
              one
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals(input + "\n", output, "do block did not round-trip:\n$output")
    }

    /**
     * A multi-line pipe whose stages carry INLINE trailing comments —
     * `|> f { x = @ }   # note` — used to break the whole do block: the pipe
     * continuation peeked only past NEWLINE (not COMMENT) to find the next
     * `|>`, so it stopped at the first commented stage and orphaned the rest.
     * The block then reported its last `let` as the missing result. Two
     * commented stages were enough; one was not. Now the chain parses and each
     * stage keeps its comment (ParsedPipe.trailingComments).
     */
    @Test
    fun `a pipe with inline stage comments parses and keeps them`() {
        val input = """
            use std::str
            let main = {} -> do
              let name = "  a , b "
                |> str::to_upper { s = @ }   # shout
                |> str::trim { s = @ }       # tidy
              name
        """.trimIndent()
        val output = formatter.format(input)
        // Neither comment is lost, and the block survives (name is the result,
        // not a "must end with a result expression" error).
        assertTrue(output.contains("# shout"), "first inline stage comment dropped:\n$output")
        assertTrue(output.contains("# tidy"), "second inline stage comment dropped:\n$output")
        assertTrue(output.contains("name"), "do block lost its result:\n$output")
        // A non-final stage keeps its comment ON its own line, not hoisted away.
        assertTrue(output.lines().any { it.contains("to_upper") && it.contains("# shout") },
            "the non-final stage comment moved off its stage:\n$output")
    }

    @Test
    fun `formatting a pipe with inline stage comments is idempotent`() {
        val input = """
            use std::str
            let main = {} -> do
              let name = "  a , b "
                |> str::to_upper { s = @ }   # shout
                |> str::trim { s = @ }       # tidy
              name
        """.trimIndent()
        val once = formatter.format(input)
        val twice = formatter.format(once)
        assertEquals(once, twice, "not idempotent:\n---once---\n$once\n---twice---\n$twice")
    }

    @Test
    fun `an own-line comment between pipe stages is preserved`() {
        val input = """
            use std::str
            let main = {} -> do
              let n = "a"
                |> str::to_upper { s = @ }
                # between the stages
                |> str::to_lower { s = @ }
              n
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# between the stages"),
            "own-line comment between stages was dropped:\n$output")
    }
}
