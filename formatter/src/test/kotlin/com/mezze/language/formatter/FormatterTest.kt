package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FormatterTest {
    private val formatter = Formatter()

    // =========================================================================
    // BASIC FORMATTING
    // =========================================================================

    @Test
    fun `format simple let binding`() {
        val input = "let x = 42"
        val output = formatter.format(input)
        assertEquals("let x = 42\n", output)
    }

    @Test
    fun `format let binding with type annotation`() {
        val input = "let x: Int = 42"
        val output = formatter.format(input)
        assertEquals("let x: Int = 42\n", output)
    }

    @Test
    fun `format pub let binding`() {
        val input = "pub let x = 42"
        val output = formatter.format(input)
        assertEquals("pub let x = 42\n", output)
    }

    // =========================================================================
    // OPERATORS
    // =========================================================================

    @Test
    fun `format binary operators with spaces`() {
        val input = "let x = 1+2"
        val output = formatter.format(input)
        assertEquals("let x = 1 + 2\n", output)
    }

    @Test
    fun `format chained operators`() {
        val input = "let x = 1 + 2 * 3"
        val output = formatter.format(input)
        assertEquals("let x = 1 + 2 * 3\n", output)
    }

    // =========================================================================
    // RECORDS
    // =========================================================================

    @Test
    fun `format empty record`() {
        val input = "let x = {}"
        val output = formatter.format(input)
        assertEquals("let x = {}\n", output)
    }

    @Test
    fun `format simple record on single line`() {
        val input = "let x = {a = 1, b = 2}"
        val output = formatter.format(input)
        assertEquals("let x = {a = 1, b = 2}\n", output)
    }

    @Test
    fun `format record shorthand`() {
        val input = "let y = {x}"
        val output = formatter.format(input)
        assertEquals("let y = {x}\n", output)
    }

    // =========================================================================
    // ARRAYS
    // =========================================================================

    @Test
    fun `format empty array`() {
        val input = "let x = []"
        val output = formatter.format(input)
        assertEquals("let x = []\n", output)
    }

    @Test
    fun `format simple array on single line`() {
        val input = "let x = [1, 2, 3]"
        val output = formatter.format(input)
        assertEquals("let x = [1, 2, 3]\n", output)
    }

    // =========================================================================
    // LAMBDAS
    // =========================================================================

    @Test
    fun `format simple lambda`() {
        val input = "let f = {x} -> x"
        val output = formatter.format(input)
        assertEquals("let f = {x} -> x\n", output)
    }

    @Test
    fun `format lambda with multiple parameters`() {
        val input = "let f = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = {x, y} -> x + y\n", output)
    }

    // =========================================================================
    // IF EXPRESSIONS
    // =========================================================================

    @Test
    fun `format if expression`() {
        val input = "let x = if True then 1 else 0"
        val output = formatter.format(input)
        assertEquals("let x = if True then 1 else 0\n", output)
    }

    // =========================================================================
    // VARIANTS
    // =========================================================================

    @Test
    fun `format variant construction without value`() {
        val input = "let x = None"
        val output = formatter.format(input)
        assertEquals("let x = None\n", output)
    }

    @Test
    fun `format variant construction with value`() {
        val input = "let x = Some {val = 42}"
        val output = formatter.format(input)
        assertEquals("let x = Some {val = 42}\n", output)
    }

    // =========================================================================
    // FRACTIONS (no spaces)
    // =========================================================================

    @Test
    fun `format fraction literal without spaces`() {
        val input = "let x = 1//2"
        val output = formatter.format(input)
        assertEquals("let x = 1//2\n", output)
    }

    // =========================================================================
    // IDEMPOTENCY
    // =========================================================================

    @Test
    fun `formatter is idempotent for simple code`() {
        val input = "let x = 42"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for complex code`() {
        val input = """
            let add = {x, y} -> x + y
            let result = add {x = 1, y = 2}
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    // =========================================================================
    // TYPE DECLARATIONS
    // =========================================================================

    @Test
    fun `format simple type alias`() {
        val input = "type Point = {x: Int, y: Int}"
        val output = formatter.format(input)
        assertEquals("type Point = { x: Int, y: Int }\n", output)
    }

    @Test
    fun `format variant type`() {
        val input = "type Option 'a = | Some { value: 'a }, None |"
        val output = formatter.format(input)
        assertEquals("type Option 'a = Some { value: 'a } | None\n", output)
    }
}
