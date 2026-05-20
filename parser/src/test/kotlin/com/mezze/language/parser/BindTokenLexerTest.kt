package com.mezze.language.parser

import com.mezze.language.parser.internal.Lexer
import com.mezze.language.parser.internal.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lexer tests for the `<-` token (TokenType.BIND).
 *
 * `<-` is a single token, distinct from `<` followed by `-`. Maximal munch
 * applies: `<-` wins over a separate `<` and `-` even when whitespace
 * doesn't separate them.
 *
 * `<-` does NOT collide with `<=` (which is its own token) or with bare `<`
 * used in comparison / effect-row syntax (e.g. `<Log>`).
 */
class BindTokenLexerTest {

    private fun tokenize(source: String): List<Pair<TokenType, String>> {
        return Lexer(source).tokenize()
            .filter { it.type != TokenType.NEWLINE && it.type != TokenType.EOF }
            .map { it.type to it.text }
    }

    @Test
    fun `bare arrow lexes as BIND`() {
        val tokens = tokenize("<-")
        assertEquals(listOf(TokenType.BIND to "<-"), tokens)
    }

    @Test
    fun `BIND in a bind statement context`() {
        val tokens = tokenize("a <- expr")
        assertEquals(
            listOf(
                TokenType.IDENTIFIER to "a",
                TokenType.BIND to "<-",
                TokenType.IDENTIFIER to "expr"
            ),
            tokens
        )
    }

    @Test
    fun `LTE still lexes correctly — not confused with BIND`() {
        // `<=` must not be confused with `<-`.
        val tokens = tokenize("x <= y")
        assertEquals(
            listOf(
                TokenType.IDENTIFIER to "x",
                TokenType.LTE to "<=",
                TokenType.IDENTIFIER to "y"
            ),
            tokens
        )
    }

    @Test
    fun `bare LT followed by identifier — not BIND`() {
        // `<` followed by space and identifier (no `-`) is a comparison.
        val tokens = tokenize("x < y")
        assertEquals(
            listOf(
                TokenType.IDENTIFIER to "x",
                TokenType.LT to "<",
                TokenType.IDENTIFIER to "y"
            ),
            tokens
        )
    }

    @Test
    fun `BIND without surrounding whitespace`() {
        // Maximal munch: x<-1 is x, <-, 1 (not x, <, -1). User would normally
        // write x < -1 with spaces if they meant comparison-with-negative.
        val tokens = tokenize("x<-1")
        assertEquals(
            listOf(
                TokenType.IDENTIFIER to "x",
                TokenType.BIND to "<-",
                TokenType.INTEGER to "1"
            ),
            tokens
        )
    }

    @Test
    fun `effect row angle brackets unaffected`() {
        // `<Log>` is the effect-row syntax; the `<` should stay LT (which is
        // how effect rows are parsed at the type level).
        val tokens = tokenize("<Log>")
        assertEquals(
            listOf(
                TokenType.LT to "<",
                TokenType.IDENTIFIER to "Log",
                TokenType.GT to ">"
            ),
            tokens
        )
    }

    @Test
    fun `multiple binds in sequence lex correctly`() {
        val tokens = tokenize("a <- e1\nb <- e2")
        // Newline tokens were filtered out in tokenize()
        assertEquals(
            listOf(
                TokenType.IDENTIFIER to "a",
                TokenType.BIND to "<-",
                TokenType.IDENTIFIER to "e1",
                TokenType.IDENTIFIER to "b",
                TokenType.BIND to "<-",
                TokenType.IDENTIFIER to "e2"
            ),
            tokens
        )
    }

    @Test
    fun `arrow and bind are distinct`() {
        // -> is ARROW, <- is BIND; they are unrelated tokens.
        val tokens = tokenize("{x} -> x <- y")
        // Mostly to assert ARROW and BIND coexist and are different.
        val types = tokens.map { it.first }
        assertTrue(types.contains(TokenType.ARROW), "ARROW missing: $types")
        assertTrue(types.contains(TokenType.BIND), "BIND missing: $types")
    }
}
