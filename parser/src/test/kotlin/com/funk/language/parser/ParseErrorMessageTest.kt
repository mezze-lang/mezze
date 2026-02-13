package com.funk.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ParseErrorMessageTest {

    private val parser = FunkParser()

    @Test
    fun `missing closing brace should show friendly error`() {
        val code = "let x = { y = 1"
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Missing closing brace error ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should say "Expected '}'" not "Expected RBRACE"
        assertTrue(msg.contains("'}'") || msg.contains("closing"),
            "Error should mention '}' not RBRACE: $msg")
        assertFalse(msg.contains("RBRACE"), "Should not contain raw token type RBRACE: $msg")
    }

    @Test
    fun `missing expression should show friendly error`() {
        val code = "let x = if True then"
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Missing expression error ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
    }

    @Test
    fun `unexpected token should show actual token not type`() {
        val code = "let x = + 1"
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Unexpected token error ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should say "got '+'" not "got PLUS"
        assertTrue(msg.contains("'+'"), "Error should show actual token '+': $msg")
        assertFalse(msg.contains("PLUS") && !msg.contains("'+"), "Should not contain raw token type PLUS: $msg")
    }

    @Test
    fun `error with identifier should show identifier name`() {
        val code = "let x = { foo"
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Error with identifier ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should show the actual identifier 'foo' not just IDENTIFIER
        assertFalse(msg.contains("IDENTIFIER") && !msg.contains("'foo'"),
            "Should show 'foo' not just IDENTIFIER: $msg")
    }
}
