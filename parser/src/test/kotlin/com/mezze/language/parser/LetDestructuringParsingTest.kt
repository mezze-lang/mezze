package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for parsing let destructuring patterns
 */
class LetDestructuringParsingTest {

    private val parser = MezzeParser()

    // ========== Simple Variable Bindings ==========

    @Test
    fun `parse simple let binding with variable pattern`() {
        val code = """
            let x = 1
            x
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        assertTrue(letBinding.pattern is VariablePattern)
        assertEquals("x", (letBinding.pattern as VariablePattern).name)
        assertTrue(letBinding.isSimpleBinding)
        assertEquals("x", letBinding.variableName)
    }

    // ========== Wildcard Pattern in Let ==========

    @Test
    fun `parse let binding with wildcard pattern`() {
        val code = """
            let _ = sideEffect { }
            1
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        assertTrue(letBinding.pattern is WildcardPattern)
    }

    // ========== Record Pattern in Let ==========

    @Test
    fun `parse let binding with record pattern`() {
        val code = """
            let point = { x = 1, y = 2 }
            let { x, y } = point
            x + y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding2 = program.items[1] as LetBinding
        assertTrue(letBinding2.pattern is RecordPattern)

        val recordPattern = letBinding2.pattern as RecordPattern
        assertEquals(2, recordPattern.entries.size)
        assertEquals("x", recordPattern.entries[0].name)
        assertEquals("y", recordPattern.entries[1].name)
    }

    @Test
    fun `parse let binding with nested record pattern`() {
        val code = """
            let nested = { point = { x = 1, y = 2 }, z = 3 }
            let { point = { x, y }, z } = nested
            x + y + z
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding2 = program.items[1] as LetBinding
        assertTrue(letBinding2.pattern is RecordPattern)

        val recordPattern = letBinding2.pattern as RecordPattern
        assertEquals(2, recordPattern.entries.size)

        // First entry has a nested pattern
        val pointEntry = recordPattern.entries.find { it.name == "point" }
        assertNotNull(pointEntry)
        assertTrue(pointEntry.pattern is RecordPattern)
    }

    // ========== Let Statement in Do Block ==========

    @Test
    fun `parse let statement with variable pattern in do block`() {
        val code = """
            do
              let x = 1
              let y = 2
              x + y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val doExpr = (program.items.first() as TopLevelExpression).expression as DoExpression
        assertEquals(2, doExpr.statements.size)

        val stmt1 = doExpr.statements[0] as LetStatement
        assertTrue(stmt1.pattern is VariablePattern)
        assertEquals("x", (stmt1.pattern as VariablePattern).name)
    }

    @Test
    fun `parse let statement with record pattern in do block`() {
        val code = """
            do
              let point = { x = 1, y = 2 }
              let { x, y } = point
              x + y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val doExpr = (program.items.first() as TopLevelExpression).expression as DoExpression

        val stmt2 = doExpr.statements[1] as LetStatement
        assertTrue(stmt2.pattern is RecordPattern)
    }

    // ========== Irrefutability Validation ==========

    @Test
    fun `simple variable patterns are irrefutable`() {
        val pattern = VariablePattern("x")
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `wildcard patterns are irrefutable`() {
        val pattern = WildcardPattern()
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `record patterns with irrefutable entries are irrefutable`() {
        val pattern = RecordPattern(
            entries = listOf(
                RecordPatternEntry("x", null),  // shorthand binding
                RecordPatternEntry("y", VariablePattern("yVal"))
            )
        )
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `constructor patterns are refutable`() {
        val pattern = ConstructorPattern("Some", null)
        assertTrue(!pattern.isIrrefutable())
    }

    @Test
    fun `literal patterns are refutable`() {
        val pattern = LiteralPattern(IntLiteralValue(42))
        assertTrue(!pattern.isIrrefutable())
    }

    // ========== Extract Bindings ==========

    @Test
    fun `extract bindings from variable pattern`() {
        val pattern = VariablePattern("x")
        assertEquals(listOf("x"), pattern.extractBindings())
    }

    @Test
    fun `extract bindings from wildcard pattern`() {
        val pattern = WildcardPattern()
        assertEquals(emptyList(), pattern.extractBindings())
    }

    @Test
    fun `extract bindings from record pattern`() {
        val pattern = RecordPattern(
            entries = listOf(
                RecordPatternEntry("x", null),  // shorthand - extracts "x"
                RecordPatternEntry("y", VariablePattern("yVal"))  // explicit - extracts "yVal"
            )
        )
        val bindings = pattern.extractBindings()
        assertTrue("x" in bindings)
        assertTrue("yVal" in bindings)
    }

    // ========== Helper Factory Methods ==========

    @Test
    fun `LetBinding simple factory creates correct pattern`() {
        val letBinding = LetBinding.simple(
            name = "x",
            expression = IntLiteral(42)
        )
        assertTrue(letBinding.isSimpleBinding)
        assertEquals("x", letBinding.variableName)
        assertTrue(letBinding.pattern is VariablePattern)
    }

    @Test
    fun `LetStatement simple factory creates correct pattern`() {
        val letStmt = LetStatement.simple(
            name = "x",
            expression = IntLiteral(42)
        )
        assertTrue(letStmt.isSimpleBinding)
        assertEquals("x", letStmt.variableName)
        assertTrue(letStmt.pattern is VariablePattern)
    }
}
