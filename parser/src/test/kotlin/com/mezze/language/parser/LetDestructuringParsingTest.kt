package com.mezze.language.parser

import com.mezze.language.parsedast.*
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
    private val testLoc = Loc("<test>", 0, 0, 0, 0)

    // ========== Simple Variable Bindings ==========

    @Test
    fun `parse simple let binding with variable pattern`() {
        val code = """
            let x = 1
            x
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        assertTrue(letBinding.pattern is ParsedVariablePattern)
        assertEquals("x", (letBinding.pattern as ParsedVariablePattern).name)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        assertTrue(letBinding.pattern is ParsedWildcardPattern)
    }

    // ========== Record Pattern in Let ==========

    @Test
    fun `parse let binding with record pattern`() {
        val code = """
            let point = { x = 1, y = 2 }
            let { x, y } = point
            x + y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding2 = module.decls[1] as ParsedLetDecl
        assertTrue(letBinding2.pattern is ParsedRecordPattern)

        val recordPattern = letBinding2.pattern as ParsedRecordPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding2 = module.decls[1] as ParsedLetDecl
        assertTrue(letBinding2.pattern is ParsedRecordPattern)

        val recordPattern = letBinding2.pattern as ParsedRecordPattern
        assertEquals(2, recordPattern.entries.size)

        // First entry has a nested pattern
        val pointEntry = recordPattern.entries.find { it.name == "point" }
        assertNotNull(pointEntry)
        assertTrue(pointEntry.pattern is ParsedRecordPattern)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val doExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedDo
        assertEquals(2, doExpr.statements.size)

        val stmt1 = doExpr.statements[0] as ParsedStatement.Let
        assertTrue(stmt1.pattern is ParsedVariablePattern)
        assertEquals("x", (stmt1.pattern as ParsedVariablePattern).name)
    }

    @Test
    fun `parse let statement with record pattern in do block`() {
        val code = """
            do
              let point = { x = 1, y = 2 }
              let { x, y } = point
              x + y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val doExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedDo

        val stmt2 = doExpr.statements[1] as ParsedStatement.Let
        assertTrue(stmt2.pattern is ParsedRecordPattern)
    }

    // ========== Irrefutability Validation ==========

    @Test
    fun `simple variable patterns are irrefutable`() {
        val pattern = ParsedVariablePattern("x", testLoc)
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `wildcard patterns are irrefutable`() {
        val pattern = ParsedWildcardPattern(testLoc)
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `record patterns with irrefutable entries are irrefutable`() {
        val pattern = ParsedRecordPattern(
            entries = listOf(
                ParsedRecordPatternEntry("x", null, testLoc),  // shorthand binding
                ParsedRecordPatternEntry("y", ParsedVariablePattern("yVal", testLoc), testLoc)
            ),
            loc = testLoc
        )
        assertTrue(pattern.isIrrefutable())
    }

    @Test
    fun `constructor patterns are refutable`() {
        val pattern = ParsedConstructorPattern("Some", null, testLoc)
        assertTrue(!pattern.isIrrefutable())
    }

    @Test
    fun `literal patterns are refutable`() {
        val pattern = ParsedLiteralPattern(ParsedLiteralValue.IntValue(42), testLoc)
        assertTrue(!pattern.isIrrefutable())
    }

    // ========== Extract Bindings ==========

    @Test
    fun `extract bindings from variable pattern`() {
        val pattern = ParsedVariablePattern("x", testLoc)
        assertEquals(listOf("x"), pattern.extractBindings())
    }

    @Test
    fun `extract bindings from wildcard pattern`() {
        val pattern = ParsedWildcardPattern(testLoc)
        assertEquals(emptyList(), pattern.extractBindings())
    }

    @Test
    fun `extract bindings from record pattern`() {
        val pattern = ParsedRecordPattern(
            entries = listOf(
                ParsedRecordPatternEntry("x", null, testLoc),  // shorthand - extracts "x"
                ParsedRecordPatternEntry("y", ParsedVariablePattern("yVal", testLoc), testLoc)  // explicit - extracts "yVal"
            ),
            loc = testLoc
        )
        val bindings = pattern.extractBindings()
        assertTrue("x" in bindings)
        assertTrue("yVal" in bindings)
    }

    // ========== Helper Factory Methods ==========

    @Test
    fun `ParsedLetDecl with simple variable pattern`() {
        val letBinding = ParsedLetDecl(
            pattern = ParsedVariablePattern("x", testLoc),
            value = ParsedIntLit(42, testLoc),
            loc = testLoc
        )
        assertTrue(letBinding.isSimpleBinding)
        assertEquals("x", letBinding.variableName)
        assertTrue(letBinding.pattern is ParsedVariablePattern)
    }

    @Test
    fun `ParsedStatement Let with simple variable pattern`() {
        val letStmt = ParsedStatement.Let(
            pattern = ParsedVariablePattern("x", testLoc),
            value = ParsedIntLit(42, testLoc),
            loc = testLoc
        )
        assertTrue(letStmt.pattern is ParsedVariablePattern)
        assertEquals("x", (letStmt.pattern as ParsedVariablePattern).name)
    }
}
