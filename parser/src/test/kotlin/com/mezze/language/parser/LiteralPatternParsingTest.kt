package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for parsing literal patterns in match expressions
 */
class LiteralPatternParsingTest {

    private val parser = MezzeParser()

    // ========== Integer Literal Patterns ==========

    @Test
    fun `parse match with integer literal pattern zero`() {
        val code = """
            match x is
              0 -> "zero"
              _ -> "non-zero"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedLiteralPattern)
        val literal = (firstPattern as ParsedLiteralPattern).value
        assertTrue(literal is ParsedLiteralValue.IntValue)
        assertEquals(0L, (literal as ParsedLiteralValue.IntValue).value)
    }

    @Test
    fun `parse match with positive integer literal pattern`() {
        val code = """
            match n is
              1 -> "one"
              2 -> "two"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertEquals(1L, (pattern1.value as ParsedLiteralValue.IntValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedLiteralPattern
        assertEquals(2L, (pattern2.value as ParsedLiteralValue.IntValue).value)
    }

    @Test
    fun `parse match with large integer literal pattern`() {
        val code = """
            match code is
              404 -> "not found"
              500 -> "server error"
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertEquals(404L, (pattern1.value as ParsedLiteralValue.IntValue).value)
    }

    // ========== String Literal Patterns ==========

    @Test
    fun `parse match with string literal pattern`() {
        val code = """
            match command is
              "help" -> "showing help"
              "quit" -> "exiting"
              _ -> "unknown command"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertTrue(pattern1.value is ParsedLiteralValue.StringValue)
        assertEquals("help", (pattern1.value as ParsedLiteralValue.StringValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedLiteralPattern
        assertEquals("quit", (pattern2.value as ParsedLiteralValue.StringValue).value)
    }

    @Test
    fun `parse match with empty string literal pattern`() {
        val code = """
            match s is
              "" -> "empty"
              _ -> "non-empty"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertEquals("", (pattern.value as ParsedLiteralValue.StringValue).value)
    }

    // ========== Boolean Constructor Patterns ==========
    // Note: Boolean values are now variant constructors (True/False), not literals

    @Test
    fun `parse match with True constructor pattern`() {
        val code = """
            match flag is
              True -> "yes"
              False -> "no"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        // True and False are now constructor patterns, not literal patterns
        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedConstructorPattern
        assertEquals("True", pattern1.constructor)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedConstructorPattern
        assertEquals("False", pattern2.constructor)
    }

    @Test
    fun `parse match with False constructor pattern`() {
        val code = """
            match done is
              False -> "continue"
              _ -> "stop"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern = matchExpr.cases[0].patterns.first() as ParsedConstructorPattern
        assertEquals("False", pattern.constructor)
    }

    // ========== Multiple Literal Patterns with | ==========

    @Test
    fun `parse match with multiple integer patterns using pipe`() {
        val code = """
            match n is
              1 | 2 | 3 -> "small"
              _ -> "large"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals(1L, ((patterns[0] as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
        assertEquals(2L, ((patterns[1] as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
        assertEquals(3L, ((patterns[2] as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
    }

    @Test
    fun `parse match with multiple string patterns using pipe`() {
        val code = """
            match cmd is
              "y" | "yes" | "Y" -> true
              "n" | "no" | "N" -> false
              _ -> false
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals("y", ((patterns[0] as ParsedLiteralPattern).value as ParsedLiteralValue.StringValue).value)
        assertEquals("yes", ((patterns[1] as ParsedLiteralPattern).value as ParsedLiteralValue.StringValue).value)
        assertEquals("Y", ((patterns[2] as ParsedLiteralPattern).value as ParsedLiteralValue.StringValue).value)
    }

    // ========== Literal Patterns with Guards ==========

    @Test
    fun `parse match with literal pattern and guard`() {
        val code = """
            match n is
              0 if debug -> "debug zero"
              0 -> "zero"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        val pattern = firstCase.patterns.first() as ParsedLiteralPattern
        assertEquals(0L, (pattern.value as ParsedLiteralValue.IntValue).value)
        assertNotNull(firstCase.guard)
    }

    // ========== Mixed Patterns ==========

    @Test
    fun `parse match with mixed literal and wildcard patterns`() {
        val code = """
            match x is
              0 -> "zero"
              1 -> "one"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        assertTrue(matchExpr.cases[0].patterns.first() is ParsedLiteralPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is ParsedLiteralPattern)
        assertTrue(matchExpr.cases[2].patterns.first() is ParsedWildcardPattern)
    }

    @Test
    fun `parse match with mixed literal and variable patterns`() {
        val code = """
            match x is
              0 -> "zero"
              n -> n + 1
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        assertTrue(matchExpr.cases[0].patterns.first() is ParsedLiteralPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is ParsedVariablePattern)
    }

    // ========== Nested Match with Literal Patterns ==========

    @Test
    fun `parse nested match with literal patterns`() {
        val code = """
            match x is
              0 -> match y is
                     0 -> "both zero"
                     _ -> "x is zero"
              _ -> "x is not zero"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val innerMatch = matchExpr.cases[0].body as ParsedMatch

        assertTrue(matchExpr.cases[0].patterns.first() is ParsedLiteralPattern)
        assertTrue(innerMatch.cases[0].patterns.first() is ParsedLiteralPattern)
    }

    // ========== Float Literal Patterns ==========

    @Test
    fun `parse match with float literal pattern`() {
        val code = """
            match x is
              0.0 -> "zero"
              1.0 -> "one"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertTrue(pattern1.value is ParsedLiteralValue.FloatValue)
        assertEquals(0.0, (pattern1.value as ParsedLiteralValue.FloatValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedLiteralPattern
        assertEquals(1.0, (pattern2.value as ParsedLiteralValue.FloatValue).value)
    }

    @Test
    fun `parse match with negative float literal pattern`() {
        val code = """
            match x is
              3.14 -> "pi"
              2.718 -> "e"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedLiteralPattern
        assertEquals(3.14, (pattern1.value as ParsedLiteralValue.FloatValue).value, 0.001)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedLiteralPattern
        assertEquals(2.718, (pattern2.value as ParsedLiteralValue.FloatValue).value, 0.001)
    }

    @Test
    fun `parse match with multiple float patterns using pipe`() {
        val code = """
            match x is
              0.0 | 1.0 | 2.0 -> "small"
              _ -> "large"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals(0.0, ((patterns[0] as ParsedLiteralPattern).value as ParsedLiteralValue.FloatValue).value, 0.001)
        assertEquals(1.0, ((patterns[1] as ParsedLiteralPattern).value as ParsedLiteralValue.FloatValue).value, 0.001)
        assertEquals(2.0, ((patterns[2] as ParsedLiteralPattern).value as ParsedLiteralValue.FloatValue).value, 0.001)
    }
}
