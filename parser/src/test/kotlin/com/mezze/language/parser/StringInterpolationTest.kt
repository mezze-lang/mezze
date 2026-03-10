package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class StringInterpolationTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple interpolation parsing`() {
        val source = """let greeting = "Hello $(name)!""""

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertEquals("greeting", letBinding.variableName)

        val stringLit = letBinding.expression as StringLiteral
        assertTrue(stringLit.isInterpolated)
        assertNotNull(stringLit.parts)
        assertEquals(3, stringLit.parts!!.size)

        // First part: "Hello "
        val part0 = stringLit.parts!![0] as StringPart
        assertEquals("Hello ", part0.value)

        // Second part: expression (name)
        val part1 = stringLit.parts!![1] as ExpressionPart
        assertTrue(part1.expression is Identifier)
        assertEquals("name", (part1.expression as Identifier).localName)

        // Third part: "!"
        val part2 = stringLit.parts!![2] as StringPart
        assertEquals("!", part2.value)
    }

    @Test
    fun `test multiple interpolations`() {
        val source = """let msg = "$(a) + $(b) = $(result)""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(5, stringLit.parts!!.size)

        // Check expression parts
        val expr1 = (stringLit.parts!![0] as ExpressionPart).expression as Identifier
        assertEquals("a", expr1.localName)

        val strPart1 = stringLit.parts!![1] as StringPart
        assertEquals(" + ", strPart1.value)

        val expr2 = (stringLit.parts!![2] as ExpressionPart).expression as Identifier
        assertEquals("b", expr2.localName)

        val strPart2 = stringLit.parts!![3] as StringPart
        assertEquals(" = ", strPart2.value)

        val expr3 = (stringLit.parts!![4] as ExpressionPart).expression as Identifier
        assertEquals("result", expr3.localName)
    }

    @Test
    fun `test interpolation with complex expressions`() {
        val source = """let x = "Value: $(foo{n = 10})""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(2, stringLit.parts!!.size)

        val strPart = stringLit.parts!![0] as StringPart
        assertEquals("Value: ", strPart.value)

        val exprPart = stringLit.parts!![1] as ExpressionPart
        assertTrue(exprPart.expression is FunctionCall)
    }

    @Test
    fun `test plain string without interpolation`() {
        val source = """let msg = "Hello World""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertFalse(stringLit.isInterpolated)
        assertNull(stringLit.parts)
        assertEquals("Hello World", stringLit.value)
    }

    @Test
    fun `test escaped interpolation`() {
        val source = """let msg = "Use \$(var) for interpolation""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        // Escaped $() should NOT be interpolated
        assertFalse(stringLit.isInterpolated)
        assertEquals("Use \$(var) for interpolation", stringLit.value)
    }

    @Test
    fun `test multiline string with interpolation`() {
        val source = """let msg = "Line 1: $(a)
Line 2: $(b)
Line 3""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        // Should have parts for Line 1, a, newline + Line 2, b, newline + Line 3
    }

    @Test
    fun `test interpolation with nested string`() {
        val source = """let x = "Outer: $(inner{val = "nested"})""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertNotNull(stringLit.parts)
    }

    @Test
    fun `test only interpolation no surrounding text`() {
        val source = """let x = "$(value)""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(1, stringLit.parts!!.size)

        val exprPart = stringLit.parts!![0] as ExpressionPart
        assertTrue(exprPart.expression is Identifier)
    }

    @Test
    fun `test nested string with interpolation inside interpolation`() {
        // This is valid: outer interpolation contains a string literal that has its own interpolation
        val source = """let x = "outer: $("inner $(name)")""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(2, stringLit.parts!!.size)

        val strPart = stringLit.parts!![0] as StringPart
        assertEquals("outer: ", strPart.value)

        val exprPart = stringLit.parts!![1] as ExpressionPart
        // The inner expression should be an interpolated StringLiteral
        assertTrue(exprPart.expression is StringLiteral)
        val innerString = exprPart.expression as StringLiteral
        assertTrue(innerString.isInterpolated)
    }

    @Test
    fun `test empty string`() {
        val source = """let x = """""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertFalse(stringLit.isInterpolated)
        assertEquals("", stringLit.value)
    }

    @Test
    fun `test consecutive interpolations no space`() {
        val source = """let x = "$(a)$(b)$(c)""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(3, stringLit.parts!!.size)

        // All parts should be expression parts
        assertTrue(stringLit.parts!![0] is ExpressionPart)
        assertTrue(stringLit.parts!![1] is ExpressionPart)
        assertTrue(stringLit.parts!![2] is ExpressionPart)
    }

    @Test
    fun `test interpolation with parentheses in expression`() {
        val source = """let x = "result: $(add{a = (1 + 2), b = 3})""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(2, stringLit.parts!!.size)

        val exprPart = stringLit.parts!![1] as ExpressionPart
        assertTrue(exprPart.expression is FunctionCall)
    }

    @Test
    fun `test interpolation with if expression`() {
        val source = """let x = "$(if true then "yes" else "no")""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(1, stringLit.parts!!.size)

        val exprPart = stringLit.parts!![0] as ExpressionPart
        assertTrue(exprPart.expression is IfExpression)
    }

    @Test
    fun `test escape sequences mixed with interpolation`() {
        val source = """let x = "line1\n$(middle)\ttab""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(3, stringLit.parts!!.size)

        val part0 = stringLit.parts!![0] as StringPart
        assertEquals("line1\n", part0.value)

        val part2 = stringLit.parts!![2] as StringPart
        assertEquals("\ttab", part2.value)
    }

    @Test
    fun `test multiple escape sequences for dollar sign`() {
        val source = """let x = "price: \$100 and \$(not interpolated)""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        // Should NOT be interpolated because all $ are escaped
        assertFalse(stringLit.isInterpolated)
        assertEquals("price: \$100 and \$(not interpolated)", stringLit.value)
    }

    @Test
    fun `test direct nested interpolation fails`() {
        // $($(x)) should fail because $ is not a valid expression token
        val source = """let x = "$($(name))""""

        assertThrows<Exception> {
            parser.parse(source)
        }
    }

    @Test
    fun `test interpolation at start of string`() {
        val source = """let x = "$(prefix) suffix""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(2, stringLit.parts!!.size)

        assertTrue(stringLit.parts!![0] is ExpressionPart)
        val strPart = stringLit.parts!![1] as StringPart
        assertEquals(" suffix", strPart.value)
    }

    @Test
    fun `test interpolation at end of string`() {
        val source = """let x = "prefix $(suffix)""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        assertEquals(2, stringLit.parts!!.size)

        val strPart = stringLit.parts!![0] as StringPart
        assertEquals("prefix ", strPart.value)
        assertTrue(stringLit.parts!![1] is ExpressionPart)
    }

    @Test
    fun `test multiline string without interpolation`() {
        val source = """let x = "line 1
line 2
line 3""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertFalse(stringLit.isInterpolated)
        assertEquals("line 1\nline 2\nline 3", stringLit.value)
    }

    @Test
    fun `test unicode in interpolated string`() {
        val source = """let x = "Hello $(name) 🎉""""

        val program = parser.parse(source)
        val letBinding = program.items[0] as LetBinding
        val stringLit = letBinding.expression as StringLiteral

        assertTrue(stringLit.isInterpolated)
        val lastPart = stringLit.parts!!.last() as StringPart
        assertEquals(" 🎉", lastPart.value)
    }
}
