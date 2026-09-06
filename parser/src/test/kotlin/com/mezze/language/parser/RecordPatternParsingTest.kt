package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertContains

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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedRecordPattern)
        val recordPattern = firstPattern as ParsedRecordPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

        assertEquals(2, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
        assertTrue(recordPattern.entries[0].pattern is ParsedVariablePattern)
        assertEquals("n", (recordPattern.entries[0].pattern as ParsedVariablePattern).name)

        assertEquals("age", recordPattern.entries[1].name)
        assertTrue(recordPattern.entries[1].pattern is ParsedVariablePattern)
        assertEquals("a", (recordPattern.entries[1].pattern as ParsedVariablePattern).name)
    }

    @Test
    fun `parse match with mixed shorthand and explicit bindings`() {
        val code = """
            match person is
              { name, age = years } -> name
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

        assertEquals(2, recordPattern.entries.size)
        assertEquals("name", recordPattern.entries[0].name)
        assertNull(recordPattern.entries[0].pattern) // shorthand

        assertEquals("age", recordPattern.entries[1].name)
        assertTrue(recordPattern.entries[1].pattern is ParsedVariablePattern)
        assertEquals("years", (recordPattern.entries[1].pattern as ParsedVariablePattern).name)
    }

    // ========== Open Record Patterns ==========

    @Test
    fun `parse match with open record pattern`() {
        val code = """
            match data is
              { name, ... } -> name
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

        assertTrue(recordPattern.isOpen)
        assertEquals(2, recordPattern.entries.size)
    }

    @Test
    fun `parse match with closed record pattern`() {
        val code = """
            match point is
              { x, y } -> x + y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

        assertTrue(recordPattern.entries[0].pattern is ParsedWildcardPattern)
    }

    @Test
    fun `parse record pattern with literal in field`() {
        val code = """
            match data is
              { status = 200 } -> "OK"
              { status = 404 } -> "Not Found"
              _ -> "Unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedRecordPattern
        assertTrue(pattern1.entries[0].pattern is ParsedLiteralPattern)
        assertEquals(200L, ((pattern1.entries[0].pattern as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedRecordPattern
        assertEquals(404L, ((pattern2.entries[0].pattern as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
    }

    // ========== Record Pattern with Guards ==========

    @Test
    fun `parse record pattern with guard`() {
        val code = """
            match person is
              { age } if age > 18 -> "adult"
              { age } -> "minor"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val firstCase = matchExpr.cases[0]
        assertTrue(firstCase.patterns.first() is ParsedRecordPattern)
        assertNotNull(firstCase.guard)

        val secondCase = matchExpr.cases[1]
        assertTrue(secondCase.patterns.first() is ParsedRecordPattern)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val patterns = matchExpr.cases[0].patterns

        assertEquals(2, patterns.size)
        assertTrue(patterns[0] is ParsedRecordPattern)
        assertTrue(patterns[1] is ParsedRecordPattern)
    }

    // ========== Mixed Pattern Types ==========

    @Test
    fun `parse match with record and wildcard patterns`() {
        val code = """
            match data is
              { name } -> name
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        assertTrue(matchExpr.cases[0].patterns.first() is ParsedRecordPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is ParsedWildcardPattern)
    }

    @Test
    fun `parse match with record and variable patterns`() {
        val code = """
            match data is
              { x, y } -> x + y
              other -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        assertTrue(matchExpr.cases[0].patterns.first() is ParsedRecordPattern)
        assertTrue(matchExpr.cases[1].patterns.first() is ParsedVariablePattern)
    }

    // ========== Empty Record Pattern ==========

    @Test
    fun `parse empty record pattern`() {
        val code = """
            match unit is
              {} -> "empty"
              _ -> "not empty"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val recordPattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern

        assertEquals(0, recordPattern.entries.size)
        assertTrue(!recordPattern.isOpen)
    }
    @Test
    fun `nested record destructure with = parses`() {
        // `let { a = { b } } = r` — a record pattern binds a field to a
        // sub-pattern with =, mirroring `Ok { val = x }`.
        val code = "let f = { r } -> do\n  let { product = { product_name } } = r\n  product_name"
        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `field colon in a record pattern gives a clear error, not a cryptic one`() {
        // Common slip: writing `:` (type-annotation syntax) instead of `=`.
        val code = "let f = { r } -> do\n  let { product: { product_name } } = r\n  product_name"
        val error = assertFails { parser.parseParsedModule(code) }
        val msg = error.message ?: ""
        assertContains(msg, "`=`")
        assertContains(msg, "`:`")
        assertFalse(msg.contains("Do block is empty"),
            "should not fall through to the cryptic empty-do-block error: $msg")
    }

}
