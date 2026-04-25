package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for parser error recovery — continuing to parse after errors.
 *
 * Uses syntactically invalid but lexer-valid inputs (no illegal characters).
 * The parser should recover at the next top-level declaration keyword.
 */
class ErrorRecoveryTest {

    private val parser = MezzeParser()

    @Test
    fun `recovery continues after parse error`() {
        // "let y = = =" is a parse error (unexpected =) but lexer-valid
        val source = """
let x = 42
let y = = =
let z = 100
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors, "Should have parse errors")
        val names = result.module.decls.filterIsInstance<ParsedLetDecl>().map { it.variableName }
        assertTrue("x" in names, "Should parse 'x' before error")
        assertTrue("z" in names, "Should parse 'z' after error")
    }

    @Test
    fun `recovery parses declarations before and after error`() {
        val source = """
let good_before = 1
let bad = if then
let good_after = 2
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors)
        val names = result.module.decls.filterIsInstance<ParsedLetDecl>().map { it.variableName }
        assertTrue("good_before" in names, "Should parse declaration before error")
        assertTrue("good_after" in names, "Should parse declaration after error")
    }

    @Test
    fun `recovery handles error in middle of many declarations`() {
        val source = """
let a = 1
let b = 2
let c = if then
let d = 3
let e = 4
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors)
        val names = result.module.decls.filterIsInstance<ParsedLetDecl>().map { it.variableName }
        assertTrue(names.size >= 4, "Should parse a, b, d, e (skip c). Got: $names")
    }

    @Test
    fun `recovery handles error at end of file`() {
        val source = """
let x = 42
let y = if then
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors)
        val names = result.module.decls.filterIsInstance<ParsedLetDecl>().map { it.variableName }
        assertTrue("x" in names, "Should parse 'x' before bad end")
    }

    @Test
    fun `recovery preserves comments on valid declarations`() {
        val source = """
# Comment on a
let a = 1
let b = if then
# Comment on c
let c = 2
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        val aDecl = result.module.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "a" }
        assertTrue(aDecl != null, "Should parse 'a'")
        assertTrue(aDecl!!.leadingComments.isNotEmpty(), "Comment on 'a' should be preserved")
        assertTrue(aDecl.leadingComments.first().text.contains("Comment on a"))
    }

    @Test
    fun `recovery with no errors returns no errors`() {
        val source = """
let x = 42
let y = "hello"
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.isSuccess, "Should have no errors")
        assertEquals(2, result.module.decls.size)
    }

    @Test
    fun `recovery handles different declaration types around error`() {
        val source = """
use std::int

let x = if then

type Point = { x: Int, y: Int }
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors)
        val hasUse = result.module.decls.any { it is ParsedUseDecl }
        val hasType = result.module.decls.any { it is ParsedTypeDecl }
        assertTrue(hasUse, "Should parse use declaration")
        assertTrue(hasType, "Should parse type declaration after error")
    }

    @Test
    fun `recovery reports error location`() {
        val source = """
let x = 42
let y = if then
let z = 100
""".trimIndent()
        val result = parser.parseWithRecovery(source, "test.mz")
        assertTrue(result.hasErrors)
        val error = result.errors.first()
        assertEquals(2, error.line, "Error should be on line 2")
    }
}
