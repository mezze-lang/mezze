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

    // =========================================================================
    // USE STATEMENTS
    // =========================================================================

    @Test
    fun `format use statements grouped without blank lines`() {
        val input = """
            use std::int::Int
            use std::string::String
            let x = 42
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("use std::int::Int\nuse std::string::String\n\nlet x = 42\n", output)
    }

    @Test
    fun `format use statements with blank line before code`() {
        val input = """
            use std::int::Int
            let x = 42
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("use std::int::Int\n\nlet x = 42\n", output)
    }

    // =========================================================================
    // MATCH EXPRESSIONS
    // =========================================================================

    @Test
    fun `format simple match expression`() {
        val input = """
            let x = match y is
                Some {val} -> val
                None -> 0
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = match y is\n\tSome {val} -> val\n\tNone -> 0\n", output)
    }

    // =========================================================================
    // DO BLOCKS
    // =========================================================================

    @Test
    fun `format do block`() {
        val input = """
            let x = do
                let a = 1
                let b = 2
                a + b
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = do\n\tlet a = 1\n\tlet b = 2\n\ta + b\n", output)
    }

    // =========================================================================
    // FUNCTION CALLS
    // =========================================================================

    @Test
    fun `format function call with record argument`() {
        val input = "let x = foo {a = 1, b = 2}"
        val output = formatter.format(input)
        assertEquals("let x = foo {a = 1, b = 2}\n", output)
    }

    // Note: Pipe operator |> is desugared at parse time, so we can't test it directly
    // The desugared form replaces @ placeholders with the piped value

    // =========================================================================
    // MEMBER ACCESS
    // =========================================================================

    @Test
    fun `format member access`() {
        val input = "let x = record.field"
        val output = formatter.format(input)
        assertEquals("let x = record.field\n", output)
    }

    @Test
    fun `format chained member access`() {
        val input = "let x = a.b.c"
        val output = formatter.format(input)
        assertEquals("let x = a.b.c\n", output)
    }

    // =========================================================================
    // UNARY OPERATORS
    // =========================================================================

    @Test
    fun `format negation`() {
        val input = "let x = -5"
        val output = formatter.format(input)
        assertEquals("let x = -5\n", output)
    }

    @Test
    fun `format not operator`() {
        val input = "let x = !True"
        val output = formatter.format(input)
        assertEquals("let x = !True\n", output)
    }

    // =========================================================================
    // STRING LITERALS
    // =========================================================================

    @Test
    fun `format string literal`() {
        val input = """let x = "hello""""
        val output = formatter.format(input)
        assertEquals("let x = \"hello\"\n", output)
    }

    // =========================================================================
    // COMPARISON OPERATORS
    // =========================================================================

    @Test
    fun `format equality`() {
        val input = "let x = a==b"
        val output = formatter.format(input)
        assertEquals("let x = a == b\n", output)
    }

    @Test
    fun `format less than`() {
        val input = "let x = a<b"
        val output = formatter.format(input)
        assertEquals("let x = a < b\n", output)
    }

    @Test
    fun `format greater than or equal`() {
        val input = "let x = a>=b"
        val output = formatter.format(input)
        assertEquals("let x = a >= b\n", output)
    }

    // =========================================================================
    // LOGICAL OPERATORS
    // =========================================================================

    @Test
    fun `format logical and`() {
        val input = "let x = a&&b"
        val output = formatter.format(input)
        assertEquals("let x = a && b\n", output)
    }

    @Test
    fun `format logical or`() {
        val input = "let x = a||b"
        val output = formatter.format(input)
        assertEquals("let x = a || b\n", output)
    }

    // =========================================================================
    // EFFECT DECLARATIONS
    // =========================================================================

    @Test
    fun `format effect declaration`() {
        val input = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("effect Log where {\n\tlog: { msg: String } -> {} <Log>\n}\n", output)
    }

    // =========================================================================
    // PERFORM EXPRESSIONS
    // =========================================================================

    @Test
    fun `format perform expression`() {
        val input = "let x = perform Handler in body"
        val output = formatter.format(input)
        assertEquals("let x = perform Handler in body\n", output)
    }

    // =========================================================================
    // SELECTIVE USE
    // =========================================================================

    @Test
    fun `format selective use`() {
        val input = "use std::option::{Some, None}"
        val output = formatter.format(input)
        assertEquals("use std::option::{Some, None}\n", output)
    }

    // =========================================================================
    // COMPLEX IDEMPOTENCY
    // =========================================================================

    @Test
    fun `formatter is idempotent for match expression`() {
        val input = """
            let x = match y is
                Some {val} -> val
                None -> 0
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for do block`() {
        val input = """
            let x = do
                let a = 1
                a + 1
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for effect declaration`() {
        val input = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }
}
