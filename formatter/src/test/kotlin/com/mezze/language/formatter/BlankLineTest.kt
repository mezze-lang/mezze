package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Blank lines between top-level declarations.
 *
 * The formatter is deliberately opinionated in exactly one place: it always
 * separates the `use` block from the code below it, whether or not the author
 * left a blank line there. Every file in the stdlib is formatted that way.
 * Everywhere else the author's grouping is preserved — runs of blank lines
 * collapse to one, but a separation is never invented.
 */
class BlankLineTest {
    private val formatter = Formatter()

    @Test
    fun `the use block is always separated from the code below it`() {
        val input = """
            use std::effects::log::{ Console, println }
            let main = {} -> perform Console in println { msg = "x" }
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals(
            "use std::effects::log::{ Console, println }\n" +
            "\n" +
            "let main = {} -> perform Console in println { msg = \"x\" }\n",
            output,
        )
    }

    @Test
    fun `consecutive use lines are not separated from each other`() {
        val input = """
            use std::effects::log::{ Console }
            use std::effects::http::{ HttpClient }
            let x = 1
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals(
            "use std::effects::log::{ Console }\n" +
            "use std::effects::http::{ HttpClient }\n" +
            "\n" +
            "let x = 1\n",
            output,
        )
    }

    @Test
    fun `an author's single blank line between decls is preserved`() {
        val input = """
            let a = 1

            let b = 2
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let a = 1\n\nlet b = 2\n", output)
    }

    @Test
    fun `adjacent one-line decls stay adjacent`() {
        val input = """
            let a = 1
            let b = 2
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let a = 1\nlet b = 2\n", output)
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        val input = "let a = 1\n\n\n\nlet b = 2"
        val output = formatter.format(input)
        assertEquals("let a = 1\n\nlet b = 2\n", output)
    }
}
