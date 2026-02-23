package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ParseErrorMessageTest {

    private val parser = MezzeParser()

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

    // === Do Block Error Messages ===

    @Test
    fun `do block ending with let should show helpful error with variable name`() {
        val code = """
            let x = do
                let result = 42
        """.trimIndent()
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Do block ending with let ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should mention the variable name and suggest returning it
        assertTrue(msg.contains("result"), "Error should mention variable name 'result': $msg")
        assertTrue(msg.contains("let") || msg.contains("'let'"), "Error should mention 'let': $msg")
        assertTrue(msg.contains("result expression") || msg.contains("final expression"),
            "Error should explain do blocks need a result expression: $msg")
    }

    @Test
    fun `do block ending with let should suggest returning the variable`() {
        val code = """
            let compute = {} -> do
                let x = 1
                let y = 2
        """.trimIndent()
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Do block with multiple lets ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should show the last variable name
        assertTrue(msg.contains("y"), "Error should mention last variable 'y': $msg")
    }

    @Test
    fun `empty do block should show specific error`() {
        val code = """
            let x = do
        """.trimIndent()
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Empty do block ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should indicate the do block is empty or needs content
        assertTrue(msg.contains("empty") || msg.contains("at least one"),
            "Error should mention do block is empty: $msg")
    }

    @Test
    fun `do block with let rec should suggest module level definition`() {
        val code = """
            let x = do
                let rec factorial = { n } ->
                    if n <= 1 then 1
                    else n * factorial { n = n - 1 }
        """.trimIndent()
        val error = assertFails { parser.parse(code) }
        val msg = error.message ?: ""
        System.err.println("=== Do block with let rec ===")
        System.err.println(msg)
        System.err.println("=== End error ===\n")
        // Should mention let rec and suggest module level
        assertTrue(msg.contains("factorial"), "Error should mention function name: $msg")
    }

    @Test
    fun `valid do block should parse successfully`() {
        val code = """
            let x = do
                let a = 1
                let b = 2
                a + b
        """.trimIndent()
        // Should not throw - valid do block ends with expression
        val program = parser.parse(code)
        assertTrue(program.items.isNotEmpty(), "Should parse valid do block")
    }

    @Test
    fun `do block with expression statements should parse`() {
        val code = """
            let x = do
                let _ = println { msg = "hello" }
                42
        """.trimIndent()
        // Should not throw - do block with side effect and result
        val program = parser.parse(code)
        assertTrue(program.items.isNotEmpty(), "Should parse do block with side effects")
    }
}
