package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EffectParserTest {
    private val parser = FunkParser()

    @Test
    fun testPerformExpressionWithSingleHandler() {
        val source = """
            perform Console in Log.print {msg = "hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(1, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].name)
        assertNull(performExpr.handlers[0].parameters)

        // Check the expression being performed
        assertTrue(performExpr.expression is FunctionCall)
        val funcCall = performExpr.expression as FunctionCall
        assertTrue(funcCall.function is MemberAccess)

        val memberAccess = funcCall.function as MemberAccess
        // Handle both Identifier and VariantConstruction cases
        val receiverName = when (memberAccess.receiver) {
            is Identifier -> (memberAccess.receiver as Identifier).name
            is VariantConstruction -> (memberAccess.receiver as VariantConstruction).constructor
            else -> fail("Expected receiver to be Identifier or VariantConstruction, got ${memberAccess.receiver::class.simpleName}")
        }
        assertEquals("Log", receiverName)
        assertEquals("print", memberAccess.member)
    }

    @Test
    fun testPerformExpressionWithMultipleHandlers() {
        val source = """
            perform Console, MathRandom, FileSystem in someFunction {}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(3, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].name)
        assertEquals("MathRandom", performExpr.handlers[1].name)
        assertEquals("FileSystem", performExpr.handlers[2].name)
    }

    @Test
    fun testPerformExpressionWithParameterizedHandler() {
        val source = """
            perform FileLogger {path = "/tmp/log.txt"} in Log.print {msg = "hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(1, performExpr.handlers.size)
        assertEquals("FileLogger", performExpr.handlers[0].name)
        assertNotNull(performExpr.handlers[0].parameters)

        val params = performExpr.handlers[0].parameters!!
        assertEquals(1, params.entries.size)
        val firstEntry = params.entries[0] as RecordFieldEntry
        assertEquals("path", firstEntry.name)
        assertTrue(firstEntry.value is StringLiteral)
        assertEquals("/tmp/log.txt", (firstEntry.value as StringLiteral).value)
    }

    @Test
    fun testPerformExpressionWithMixedParameterizedHandlers() {
        val source = """
            perform Console, FileLogger {path = "/var/log/app.log"}, SystemRandom in someFunction {x = 1, y = 2}
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val topLevelExpr = program.items[0] as TopLevelExpression
        val performExpr = topLevelExpr.expression as PerformExpression

        // Should have 3 handlers
        assertEquals(3, performExpr.handlers.size)

        // First handler: Console (no parameters)
        assertEquals("Console", performExpr.handlers[0].name)
        assertNull(performExpr.handlers[0].parameters)

        // Second handler: FileLogger with parameters
        assertEquals("FileLogger", performExpr.handlers[1].name)
        assertNotNull(performExpr.handlers[1].parameters)
        val fileLoggerParams = performExpr.handlers[1].parameters!!
        assertEquals(1, fileLoggerParams.entries.size)
        val fileLoggerEntry = fileLoggerParams.entries[0] as RecordFieldEntry
        assertEquals("path", fileLoggerEntry.name)
        assertEquals("/var/log/app.log", (fileLoggerEntry.value as StringLiteral).value)

        // Third handler: SystemRandom (no parameters)
        assertEquals("SystemRandom", performExpr.handlers[2].name)
        assertNull(performExpr.handlers[2].parameters)

        // Expression should be a function call
        assertTrue(performExpr.expression is FunctionCall)
        val functionCall = performExpr.expression as FunctionCall
        assertEquals("someFunction", (functionCall.function as Identifier).name)
    }
}
