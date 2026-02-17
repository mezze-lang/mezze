package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for parsing standalone record patterns in match expressions
 */
class RecordPatternParsingTest {

    private val parser = MezzeParser()

    // ========== Basic Standalone Record Patterns ==========

    @Test
    fun `parse match with single field record pattern`() {
        val code = """
            match person is
              { name } -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is RecordPattern)
        val recordPattern = firstPattern as RecordPattern
        assertEquals(1, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
        assertNull(recordPattern.entries[0].pattern) // shorthand
    }

    @Test
    fun `parse match with multiple field record pattern`() {
        val code = """
            match point is
              { x, y } -> x + y
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertEquals(2, recordPattern.entries.size)
        assertEquals("x", recordPattern.entries[0].name)
        assertEquals("y", recordPattern.entries[1].name)
    }

    @Test
    fun `parse match with record pattern with explicit bindings`() {
        val code = """
            match data is
              { name = n, age = a } -> n
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertEquals(2, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
        assertTrue(recordPattern.entries[0].pattern is VariablePattern)
        assertEquals("n", (recordPattern.entries[0].pattern as VariablePattern).name)

        assertEquals("age", recordPattern.entries[1].name)
        assertTrue(recordPattern.entries[1].pattern is VariablePattern)
        assertEquals("a", (recordPattern.entries[1].pattern as VariablePattern).name)
    }

    @Test
    fun `parse match with mixed shorthand and explicit bindings`() {
        val code = """
            match person is
              { name, age = years } -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertEquals(2, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
        assertNull(recordPattern.entries[0].pattern) // shorthand

        assertEquals("age", recordPattern.entries[1].name)
        assertTrue(recordPattern.entries[1].pattern is VariablePattern)
        assertEquals("years", (recordPattern.entries[1].pattern as VariablePattern).name)
    }

    // ========== Open Record Patterns ==========

    @Test
    fun `parse match with open record pattern`() {
        val code = """
            match data is
              { name, ... } -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertTrue(recordPattern.isOpen)
        assertEquals(1, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
    }

    @Test
    fun `parse match with open record pattern with comma before ellipsis`() {
        val code = """
            match data is
              { x, y, ... } -> x + y
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertTrue(recordPattern.isOpen)
        assertEquals(2, recordPattern.entries.size)
    }

    @Test
    fun `parse match with closed record pattern`() {
        val code = """
            match point is
              { x, y } -> x + y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertTrue(!recordPattern.isOpen)
    }

    // ========== Nested Patterns in Record Patterns ==========

    @Test
    fun `parse record pattern with wildcard in field`() {
        val code = """
            match data is
              { name = _, age } -> age
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertTrue(recordPattern.entries[0].pattern is WildcardPattern)
    }

    @Test
    fun `parse record pattern with literal in field`() {
        val code = """
            match data is
              { status = 200 } -> "OK"
              { status = 404 } -> "Not Found"
              _ -> "Unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as RecordPattern
        assertTrue(pattern1.entries[0].pattern is LiteralPattern)
        assertEquals(200L, ((pattern1.entries[0].pattern as LiteralPattern).value as IntLiteralValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as RecordPattern
        assertEquals(404L, ((pattern2.entries[0].pattern as LiteralPattern).value as IntLiteralValue).value)
    }

    // ========== Record Pattern with Guards ==========

    @Test
    fun `parse record pattern with guard`() {
        val code = """
            match person is
              { age } if age > 18 -> "adult"
              { age } -> "minor"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val firstCase = matchExpr.cases[0]
        assertTrue(firstCase.patterns.first() is RecordPattern)
        assertNotNull(firstCase.guard)

        val secondCase = matchExpr.cases[1]
        assertTrue(secondCase.patterns.first() is RecordPattern)
        assertNull(secondCase.guard)
    }

    // ========== Multiple Record Patterns with | ==========

    @Test
    fun `parse multiple record patterns with pipe`() {
        val code = """
            match point is
              { x = 0, y = 0 } | { x = 0 } -> "on y-axis or origin"
              _ -> "elsewhere"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val patterns = matchExpr.cases[0].patterns

        assertEquals(2, patterns.size)
        assertTrue(patterns[0] is RecordPattern)
        assertTrue(patterns[1] is RecordPattern)
    }

    // ========== Mixed Pattern Types ==========

    @Test
    fun `parse match with record and wildcard patterns`() {
        val code = """
            match data is
              { name } -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        assertTrue(matchExpr.cases[0].patterns.first() is RecordPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is WildcardPattern)
    }

    @Test
    fun `parse match with record and variable patterns`() {
        val code = """
            match data is
              { x, y } -> x + y
              other -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        assertTrue(matchExpr.cases[0].patterns.first() is RecordPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is VariablePattern)
    }

    // ========== Empty Record Pattern ==========

    @Test
    fun `parse empty record pattern`() {
        val code = """
            match unit is
              {} -> "empty"
              _ -> "not empty"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val recordPattern = matchExpr.cases[0].patterns.first() as RecordPattern

        assertEquals(0, recordPattern.entries.size)
        assertTrue(!recordPattern.isOpen)
    }
}
