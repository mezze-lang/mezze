package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for source location tracking in AST nodes.
 * These tests verify that the parser correctly sets sourceLocation on expressions.
 */
class SourceLocationTest {

    private val parser = FunkParser()

    @Test
    fun `test identifier has source location`() {
        val source = "let x = foo"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val identifier = binding.expression as Identifier

        assertNotNull(identifier.sourceLocation, "Identifier should have sourceLocation")
        assertEquals(1, identifier.sourceLocation?.line, "Identifier should be on line 1")
        assertEquals(9, identifier.sourceLocation?.column, "Identifier 'foo' should start at column 9")
    }

    @Test
    fun `test integer literal has source location`() {
        val source = "let x = 42"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val literal = binding.expression as IntLiteral

        assertNotNull(literal.sourceLocation, "IntLiteral should have sourceLocation")
        assertEquals(1, literal.sourceLocation?.line, "IntLiteral should be on line 1")
        assertEquals(9, literal.sourceLocation?.column, "IntLiteral '42' should start at column 9")
    }

    @Test
    fun `test string literal has source location`() {
        val source = """let x = "hello""""
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val literal = binding.expression as StringLiteral

        assertNotNull(literal.sourceLocation, "StringLiteral should have sourceLocation")
        assertEquals(1, literal.sourceLocation?.line, "StringLiteral should be on line 1")
        assertEquals(9, literal.sourceLocation?.column, "StringLiteral should start at column 9")
    }

    @Test
    fun `test boolean literal has source location`() {
        val source = "let x = true"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val literal = binding.expression as BooleanLiteral

        assertNotNull(literal.sourceLocation, "BooleanLiteral should have sourceLocation")
        assertEquals(1, literal.sourceLocation?.line, "BooleanLiteral should be on line 1")
        assertEquals(9, literal.sourceLocation?.column, "BooleanLiteral 'true' should start at column 9")
    }

    @Test
    fun `test binary operation has source location`() {
        val source = "let x = 1 + 2"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val binOp = binding.expression as BinaryOperation

        assertNotNull(binOp.sourceLocation, "BinaryOperation should have sourceLocation")
        assertEquals(1, binOp.sourceLocation?.line, "BinaryOperation should be on line 1")
        // BinaryOperation uses left operand's location
        assertEquals(9, binOp.sourceLocation?.column, "BinaryOperation should start at column 9 (left operand)")
    }

    @Test
    fun `test multiline source location tracking`() {
        val source = """
let x = 42
let y = "hello"
let z = x + y
        """.trimIndent()

        val program = parser.parse(source)

        // First binding: let x = 42
        val binding1 = program.items[0] as LetBinding
        val literal1 = binding1.expression as IntLiteral
        assertEquals(1, literal1.sourceLocation?.line, "First literal should be on line 1")

        // Second binding: let y = "hello"
        val binding2 = program.items[1] as LetBinding
        val literal2 = binding2.expression as StringLiteral
        assertEquals(2, literal2.sourceLocation?.line, "Second literal should be on line 2")

        // Third binding: let z = x + y
        val binding3 = program.items[2] as LetBinding
        val binOp = binding3.expression as BinaryOperation
        assertEquals(3, binOp.sourceLocation?.line, "Binary operation should be on line 3")
    }

    @Test
    fun `test function call has source location`() {
        val source = "let result = myFunc {x = 1}"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val funcCall = binding.expression as FunctionCall

        assertNotNull(funcCall.sourceLocation, "FunctionCall should have sourceLocation")
        assertEquals(1, funcCall.sourceLocation?.line, "FunctionCall should be on line 1")
        assertEquals(14, funcCall.sourceLocation?.column, "FunctionCall 'myFunc' should start at column 14")
    }

    @Test
    fun `test variant construction has source location`() {
        val source = "let x = Some {value = 42}"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val variant = binding.expression as VariantConstruction

        assertNotNull(variant.sourceLocation, "VariantConstruction should have sourceLocation")
        assertEquals(1, variant.sourceLocation?.line, "VariantConstruction should be on line 1")
        assertEquals(9, variant.sourceLocation?.column, "VariantConstruction 'Some' should start at column 9")
    }

    @Test
    fun `test variant without payload has source location`() {
        val source = "let x = None"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val variant = binding.expression as VariantConstruction

        assertNotNull(variant.sourceLocation, "VariantConstruction without payload should have sourceLocation")
        assertEquals(1, variant.sourceLocation?.line, "VariantConstruction should be on line 1")
        assertEquals(9, variant.sourceLocation?.column, "VariantConstruction 'None' should start at column 9")
    }

    @Test
    fun `test array literal has source location`() {
        val source = "let arr = [1, 2, 3]"
        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val array = binding.expression as ArrayLiteral

        assertNotNull(array.sourceLocation, "ArrayLiteral should have sourceLocation")
        assertEquals(1, array.sourceLocation?.line, "ArrayLiteral should be on line 1")
        assertEquals(11, array.sourceLocation?.column, "ArrayLiteral should start at column 11")
    }

    @Test
    fun `test match expression has source location`() {
        val source = """
let result = match x is
  1 -> "one"
  _ -> "other"
        """.trimIndent()

        val program = parser.parse(source)

        val binding = program.items.first() as LetBinding
        val matchExpr = binding.expression as MatchExpression

        assertNotNull(matchExpr.sourceLocation, "MatchExpression should have sourceLocation")
        assertEquals(1, matchExpr.sourceLocation?.line, "MatchExpression should be on line 1")
        assertEquals(14, matchExpr.sourceLocation?.column, "MatchExpression 'match' should start at column 14")
    }

    @Test
    fun `test file name is preserved in source location`() {
        val source = "let x = 42"
        val fileName = "test.funk"
        val program = parser.parse(source, file = fileName)

        val binding = program.items.first() as LetBinding
        val literal = binding.expression as IntLiteral

        assertEquals(fileName, literal.sourceLocation?.file, "File name should be preserved in sourceLocation")
    }
}
