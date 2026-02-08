package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test compatibility with v1 parser functionality
 * These tests are taken from v1 parser tests to ensure v2 can handle the same syntax
 */
class V1CompatibilityTest {

    private val parser = FunkParser()

    @Test
    fun `lambda expressions should parse without noise`() {
        // This should parse cleanly without any parser errors
        val source = "let test = {x, y} -> x + y"
        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `simple arithmetic should parse cleanly`() {
        val source = "let result = 1 + 2 * 3"
        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `do expressions should parse without noise`() {
        val source = """
            let test = do
                let x = 1
                let y = 2
                x + y
        """.trimIndent()

        java.io.File("/tmp/v1test_source.txt").writeText(source)
        java.io.File("/tmp/v1test_source_bytes.txt").writeText(source.toByteArray().joinToString(","))

        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `qualified names should parse correctly`() {
        val source = "let result = int::to_string {value = 42}"
        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `record literals should parse correctly`() {
        val source = "let person = {name = \"Alice\", age = 30}"
        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `array literals should parse correctly`() {
        val source = "let numbers = [1, 2, 3, 4, 5]"
        val result = parser.parse(source)

        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `match expressions should parse correctly`() {
        // Updated to current match syntax with proper newlines between cases
        val source = """
            let result = match value is
                Some {val} -> val
                None -> 0
        """.trimIndent()

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `effect declarations should parse`() {
        val source = """
            effect Log where {
                info: { msg: String } -> {} <>
            }
        """.trimIndent()

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `impl declarations should parse`() {
        val source = """
            impl Log for Console where {
                info: { msg: String } -> {} = { msg } -> __native_log { msg = msg }
            }
        """.trimIndent()

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `type declarations should parse`() {
        val source = "type Option 'a = | Some { value: 'a }, None |"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `use declarations should parse`() {
        val source = "use std::math::basic"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `record spread should parse`() {
        val source = "let updated = { ...person, age = 31 }"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `array spread should parse`() {
        // Valid syntax: spread first, then append elements
        val source = "let combined = [...rest, 4, 5]"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)

        // Verify the array has spread elements
        val letBinding = result.items.first() as LetBinding
        val arrayLiteral = letBinding.expression as ArrayLiteral
        assertEquals(3, arrayLiteral.elements.size)
        assertTrue(arrayLiteral.elements[0] is ArraySpreadElement)
        assertTrue(arrayLiteral.elements[1] is ArrayRegularElement)
        assertTrue(arrayLiteral.elements[2] is ArrayRegularElement)
    }

    @Test
    fun `array spread with prepend should fail`() {
        // Invalid syntax: elements before spread
        val source = "let combined = [1, ...rest, 5]"

        val exception = assertThrows<ParseException> {
            parser.parse(source)
        }
        assertTrue(exception.message?.contains("spread must come before") == true)
    }

    @Test
    fun `operators should parse correctly`() {
        val source = "let result = a == b && c != d || e < f"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `field access should parse correctly`() {
        val source = "let name = person.name"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `perform expressions should parse`() {
        val source = "let result = perform info { msg = \"Hello\" }"

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `complete stdlib effect and impl should parse`() {
        val source = """
pub effect Log where {
    info: { msg: String } -> {},
    warn: { msg: String } -> {},
    error: { msg: String } -> {} }

impl Log for Console where {
    info: { msg: String } -> {} = { msg } -> __native_log_info { msg = msg },
    warn: { msg: String } -> {} = { msg } -> __native_log_warn { msg = msg },
    error: { msg: String } -> {} = { msg } -> __native_log_error { msg = msg }
}
""".trimIndent()

        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(2, result.items.size)  // effect, impl
    }
}
