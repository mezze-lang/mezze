package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class StringInterpolationTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple interpolation parsing`() {
        val source = """let greeting = "Hello $(name)!""""

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        assertEquals("greeting", letBinding.variableName)

        val stringLit = letBinding.value as ParsedStringLit
        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertNotNull(stringLit.parts)
        assertEquals(3, stringLit.parts.size)

        // First part: "Hello "
        val part0 = stringLit.parts[0] as ParsedStringPart.Literal
        assertEquals("Hello ", part0.value)

        // Second part: expression (name)
        val part1 = stringLit.parts[1] as ParsedStringPart.Interpolation
        assertTrue(part1.expr is ParsedIdentifier)
        assertEquals("name", (part1.expr as ParsedIdentifier).localName)

        // Third part: "!"
        val part2 = stringLit.parts[2] as ParsedStringPart.Literal
        assertEquals("!", part2.value)
    }

    @Test
    fun `test multiple interpolations`() {
        val source = """let msg = "$(a) + $(b) = $(result)""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(5, stringLit.parts.size)

        // Check expression parts
        val expr1 = (stringLit.parts[0] as ParsedStringPart.Interpolation).expr as ParsedIdentifier
        assertEquals("a", expr1.localName)

        val strPart1 = stringLit.parts[1] as ParsedStringPart.Literal
        assertEquals(" + ", strPart1.value)

        val expr2 = (stringLit.parts[2] as ParsedStringPart.Interpolation).expr as ParsedIdentifier
        assertEquals("b", expr2.localName)

        val strPart2 = stringLit.parts[3] as ParsedStringPart.Literal
        assertEquals(" = ", strPart2.value)

        val expr3 = (stringLit.parts[4] as ParsedStringPart.Interpolation).expr as ParsedIdentifier
        assertEquals("result", expr3.localName)
    }

    @Test
    fun `test interpolation with complex expressions`() {
        val source = """let x = "Value: $(foo{n = 10})""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(2, stringLit.parts.size)

        val strPart = stringLit.parts[0] as ParsedStringPart.Literal
        assertEquals("Value: ", strPart.value)

        val exprPart = stringLit.parts[1] as ParsedStringPart.Interpolation
        assertTrue(exprPart.expr is ParsedFunctionCall)
    }

    @Test
    fun `test plain string without interpolation`() {
        val source = """let msg = "Hello World""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertFalse(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(1, stringLit.parts.size)
        assertEquals("Hello World", (stringLit.parts[0] as ParsedStringPart.Literal).value)
    }

    @Test
    fun `test escaped interpolation`() {
        val source = """let msg = "Use \$(var) for interpolation""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        // Escaped $() should NOT be interpolated
        assertFalse(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals("Use \$(var) for interpolation", (stringLit.parts[0] as ParsedStringPart.Literal).value)
    }

    @Test
    fun `test multiline string with interpolation`() {
        val source = """let msg = "Line 1: $(a)
Line 2: $(b)
Line 3""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        // Should have parts for Line 1, a, newline + Line 2, b, newline + Line 3
    }

    @Test
    fun `test interpolation with nested string`() {
        val source = """let x = "Outer: $(inner{val = "nested"})""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertNotNull(stringLit.parts)
    }

    @Test
    fun `test only interpolation no surrounding text`() {
        val source = """let x = "$(value)""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(1, stringLit.parts.size)

        val exprPart = stringLit.parts[0] as ParsedStringPart.Interpolation
        assertTrue(exprPart.expr is ParsedIdentifier)
    }

    @Test
    fun `test nested string with interpolation inside interpolation`() {
        // This is valid: outer interpolation contains a string literal that has its own interpolation
        val source = """let x = "outer: $("inner $(name)")""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(2, stringLit.parts.size)

        val strPart = stringLit.parts[0] as ParsedStringPart.Literal
        assertEquals("outer: ", strPart.value)

        val exprPart = stringLit.parts[1] as ParsedStringPart.Interpolation
        // The inner expression should be an interpolated StringLiteral
        assertTrue(exprPart.expr is ParsedStringLit)
        val innerString = exprPart.expr as ParsedStringLit
        assertTrue(innerString.parts.any { it is ParsedStringPart.Interpolation })
    }

    @Test
    fun `test empty string`() {
        val source = """let x = """""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertFalse(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals("", (stringLit.parts[0] as ParsedStringPart.Literal).value)
    }

    @Test
    fun `test consecutive interpolations no space`() {
        val source = """let x = "$(a)$(b)$(c)""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(3, stringLit.parts.size)

        // All parts should be expression parts
        assertTrue(stringLit.parts[0] is ParsedStringPart.Interpolation)
        assertTrue(stringLit.parts[1] is ParsedStringPart.Interpolation)
        assertTrue(stringLit.parts[2] is ParsedStringPart.Interpolation)
    }

    @Test
    fun `test interpolation with parentheses in expression`() {
        val source = """let x = "result: $(add{a = (1 + 2), b = 3})""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(2, stringLit.parts.size)

        val exprPart = stringLit.parts[1] as ParsedStringPart.Interpolation
        assertTrue(exprPart.expr is ParsedFunctionCall)
    }

    @Test
    fun `test interpolation with if expression`() {
        val source = """let x = "$(if true then "yes" else "no")""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(1, stringLit.parts.size)

        val exprPart = stringLit.parts[0] as ParsedStringPart.Interpolation
        assertTrue(exprPart.expr is ParsedIf)
    }

    @Test
    fun `test escape sequences mixed with interpolation`() {
        val source = """let x = "line1\n$(middle)\ttab""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(3, stringLit.parts.size)

        val part0 = stringLit.parts[0] as ParsedStringPart.Literal
        assertEquals("line1\n", part0.value)

        val part2 = stringLit.parts[2] as ParsedStringPart.Literal
        assertEquals("\ttab", part2.value)
    }

    @Test
    fun `test multiple escape sequences for dollar sign`() {
        val source = """let x = "price: \$100 and \$(not interpolated)""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        // Should NOT be interpolated because all $ are escaped
        assertFalse(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals("price: \$100 and \$(not interpolated)", (stringLit.parts[0] as ParsedStringPart.Literal).value)
    }

    @Test
    fun `test direct nested interpolation fails`() {
        // $($(x)) should fail because $ is not a valid expression token
        val source = """let x = "$($(name))""""

        assertThrows<Exception> {
            parser.parseParsedModule(source)
        }
    }

    @Test
    fun `test interpolation at start of string`() {
        val source = """let x = "$(prefix) suffix""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(2, stringLit.parts.size)

        assertTrue(stringLit.parts[0] is ParsedStringPart.Interpolation)
        val strPart = stringLit.parts[1] as ParsedStringPart.Literal
        assertEquals(" suffix", strPart.value)
    }

    @Test
    fun `test interpolation at end of string`() {
        val source = """let x = "prefix $(suffix)""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals(2, stringLit.parts.size)

        val strPart = stringLit.parts[0] as ParsedStringPart.Literal
        assertEquals("prefix ", strPart.value)
        assertTrue(stringLit.parts[1] is ParsedStringPart.Interpolation)
    }

    @Test
    fun `test multiline string without interpolation`() {
        val source = """let x = "line 1
line 2
line 3""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertFalse(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        assertEquals("line 1\nline 2\nline 3", (stringLit.parts[0] as ParsedStringPart.Literal).value)
    }

    @Test
    fun `test unicode in interpolated string`() {
        val source = """let x = "Hello $(name) 🎉""""

        val module = parser.parseParsedModule(source)
        val letBinding = module.decls[0] as ParsedLetDecl
        val stringLit = letBinding.value as ParsedStringLit

        assertTrue(stringLit.parts.any { it is ParsedStringPart.Interpolation })
        val lastPart = stringLit.parts.last() as ParsedStringPart.Literal
        assertEquals(" 🎉", lastPart.value)
    }
}
