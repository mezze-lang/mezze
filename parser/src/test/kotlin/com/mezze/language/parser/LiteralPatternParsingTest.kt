package com.mezze.language.parser

import com.mezze.language.ast.*
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is LiteralPattern)
        val literal = (firstPattern as LiteralPattern).value
        assertTrue(literal is IntLiteralValue)
        assertEquals(0L, (literal as IntLiteralValue).value)
    }

    @Test
    fun `parse match with positive integer literal pattern`() {
        val code = """
            match n is
              1 -> "one"
              2 -> "two"
              _ -> "other"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertEquals(1L, (pattern1.value as IntLiteralValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as LiteralPattern
        assertEquals(2L, (pattern2.value as IntLiteralValue).value)
    }

    @Test
    fun `parse match with large integer literal pattern`() {
        val code = """
            match code is
              404 -> "not found"
              500 -> "server error"
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertEquals(404L, (pattern1.value as IntLiteralValue).value)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertTrue(pattern1.value is StringLiteralValue)
        assertEquals("help", (pattern1.value as StringLiteralValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as LiteralPattern
        assertEquals("quit", (pattern2.value as StringLiteralValue).value)
    }

    @Test
    fun `parse match with empty string literal pattern`() {
        val code = """
            match s is
              "" -> "empty"
              _ -> "non-empty"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertEquals("", (pattern.value as StringLiteralValue).value)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        // True and False are now constructor patterns, not literal patterns
        val pattern1 = matchExpr.cases[0].patterns.first() as ConstructorPattern
        assertEquals("True", pattern1.constructor)

        val pattern2 = matchExpr.cases[1].patterns.first() as ConstructorPattern
        assertEquals("False", pattern2.constructor)
    }

    @Test
    fun `parse match with False constructor pattern`() {
        val code = """
            match done is
              False -> "continue"
              _ -> "stop"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern = matchExpr.cases[0].patterns.first() as ConstructorPattern
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals(1L, ((patterns[0] as LiteralPattern).value as IntLiteralValue).value)
        assertEquals(2L, ((patterns[1] as LiteralPattern).value as IntLiteralValue).value)
        assertEquals(3L, ((patterns[2] as LiteralPattern).value as IntLiteralValue).value)
    }

    @Test
    fun `parse match with multiple string patterns using pipe`() {
        val code = """
            match cmd is
              "y" | "yes" | "Y" -> true
              "n" | "no" | "N" -> false
              _ -> false
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals("y", ((patterns[0] as LiteralPattern).value as StringLiteralValue).value)
        assertEquals("yes", ((patterns[1] as LiteralPattern).value as StringLiteralValue).value)
        assertEquals("Y", ((patterns[2] as LiteralPattern).value as StringLiteralValue).value)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstCase = matchExpr.cases[0]

        val pattern = firstCase.patterns.first() as LiteralPattern
        assertEquals(0L, (pattern.value as IntLiteralValue).value)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        assertTrue(matchExpr.cases[0].patterns.first() is LiteralPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is LiteralPattern)
        assertTrue(matchExpr.cases[2].patterns.first() is WildcardPattern)
    }

    @Test
    fun `parse match with mixed literal and variable patterns`() {
        val code = """
            match x is
              0 -> "zero"
              n -> n + 1
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        assertTrue(matchExpr.cases[0].patterns.first() is LiteralPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is VariablePattern)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val innerMatch = matchExpr.cases[0].expression as MatchExpression

        assertTrue(matchExpr.cases[0].patterns.first() is LiteralPattern)
        assertTrue(innerMatch.cases[0].patterns.first() is LiteralPattern)
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

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertTrue(pattern1.value is FloatLiteralValue)
        assertEquals(0.0, (pattern1.value as FloatLiteralValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as LiteralPattern
        assertEquals(1.0, (pattern2.value as FloatLiteralValue).value)
    }

    @Test
    fun `parse match with negative float literal pattern`() {
        val code = """
            match x is
              3.14 -> "pi"
              2.718 -> "e"
              _ -> "other"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as LiteralPattern
        assertEquals(3.14, (pattern1.value as FloatLiteralValue).value, 0.001)

        val pattern2 = matchExpr.cases[1].patterns.first() as LiteralPattern
        assertEquals(2.718, (pattern2.value as FloatLiteralValue).value, 0.001)
    }

    @Test
    fun `parse match with multiple float patterns using pipe`() {
        val code = """
            match x is
              0.0 | 1.0 | 2.0 -> "small"
              _ -> "large"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val patterns = matchExpr.cases[0].patterns

        assertEquals(3, patterns.size)
        assertEquals(0.0, ((patterns[0] as LiteralPattern).value as FloatLiteralValue).value, 0.001)
        assertEquals(1.0, ((patterns[1] as LiteralPattern).value as FloatLiteralValue).value, 0.001)
        assertEquals(2.0, ((patterns[2] as LiteralPattern).value as FloatLiteralValue).value, 0.001)
    }
}
