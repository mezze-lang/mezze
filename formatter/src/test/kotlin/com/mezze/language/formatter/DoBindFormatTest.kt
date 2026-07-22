package com.mezze.language.formatter

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * The formatter renders do-block binds with their required `let`:
 * `let pat <- expr`. Formatting must produce re-parseable output.
 */
class DoBindFormatTest {
    private val formatter = Formatter()
    private val parser = MezzeParser()

    @Test
    fun `bind statement is formatted with leading let`() {
        val input = "let f = {} -> do\n  let x <- fetch {}\n  x"
        val output = formatter.format(input)
        assertTrue(output.contains("let x <- fetch"),
            "expected `let x <- fetch` in formatted output, got:\n$output")
        // No bare `x <- ` (without let) should appear.
        assertFalse(Regex("(?m)^\\s+x <- ").containsMatchIn(output),
            "formatted bind must not drop the `let`:\n$output")
    }

    @Test
    fun `formatted do-block with bind re-parses`() {
        val input = "let f = {} -> do\n  let a = seed {}\n  let b <- fetch {x = a}\n  b"
        val output = formatter.format(input)
        // Round-trip: the formatter's output must itself be valid Mezze.
        assertDoesNotThrow { parser.parseParsedModule(output) }
    }
}
