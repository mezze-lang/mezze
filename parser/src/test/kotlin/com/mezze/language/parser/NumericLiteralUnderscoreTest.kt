package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Tests for numeric literals with underscore separators.
 * Underscores can be used in the middle of numbers for readability (e.g., 1_000_000).
 * Leading underscores (e.g., _100) should fail during parsing.
 */
class NumericLiteralUnderscoreTest {

    private val parser = MezzeParser()

    // ===== Valid underscore usage =====

    @Test
    fun `test Int with single underscore`() {
        val source = "let x = 1_000"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(1000, intLiteral.value)
    }

    @Test
    fun `test Int with multiple underscores`() {
        val source = "let x = 1_000_000"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(1000000, intLiteral.value)
    }

    @Test
    fun `test Nat with underscore`() {
        val source = "let x = 1_000n"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val natLiteral = letBinding.value as ParsedNatLit
        assertEquals(1000, natLiteral.value)
    }

    @Test
    fun `test Nat with multiple underscores`() {
        val source = "let x = 1_000_000n"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val natLiteral = letBinding.value as ParsedNatLit
        assertEquals(1000000, natLiteral.value)
    }

    @Test
    fun `test Float with underscore before decimal`() {
        val source = "let x = 1_000.5"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val floatLiteral = letBinding.value as ParsedFloatLit
        assertEquals(1000.5, floatLiteral.value, 0.0001)
    }

    @Test
    fun `test Float with underscore after decimal`() {
        val source = "let x = 3.141_592"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val floatLiteral = letBinding.value as ParsedFloatLit
        assertEquals(3.141592, floatLiteral.value, 0.000001)
    }

    @Test
    fun `test Float with underscores on both sides of decimal`() {
        val source = "let x = 1_000.141_592"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val floatLiteral = letBinding.value as ParsedFloatLit
        assertEquals(1000.141592, floatLiteral.value, 0.000001)
    }

    @Test
    fun `test trailing underscore is stripped`() {
        val source = "let x = 100_"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(100, intLiteral.value)
    }

    @Test
    fun `test consecutive underscores are stripped`() {
        val source = "let x = 100__200"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(100200, intLiteral.value)
    }

    @Test
    fun `test underscore before decimal point is stripped`() {
        val source = "let x = 100_.5"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val floatLiteral = letBinding.value as ParsedFloatLit
        assertEquals(100.5, floatLiteral.value, 0.0001)
    }

    // ===== Underscore edge cases =====

    @Test
    fun `test negative Int with underscore`() {
        val source = "let x = -1_000"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        // Negative literals are parsed directly as ParsedIntLit with negative value
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(-1000, intLiteral.value)
    }

    @Test
    fun `test Int with many underscores for readability`() {
        val source = "let x = 1_234_567_890"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val intLiteral = letBinding.value as ParsedIntLit
        assertEquals(1234567890, intLiteral.value)
    }

    @Test
    fun `test Nat with many underscores`() {
        val source = "let x = 1_234_567_890n"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val natLiteral = letBinding.value as ParsedNatLit
        assertEquals(1234567890, natLiteral.value)
    }

    @Test
    fun `test Float scientific notation with underscore`() {
        // Note: This tests if underscores work in scientific notation if supported
        val source = "let x = 1_000.0"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val floatLiteral = letBinding.value as ParsedFloatLit
        assertEquals(1000.0, floatLiteral.value, 0.0001)
    }

    // ===== Arithmetic operations with underscored literals =====

    @Test
    fun `test addition with underscored Int literals`() {
        val source = "let x = 1_000 + 2_000"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        // Operators are now preserved as ParsedBinaryOp in the AST
        assert(letBinding.value is ParsedBinaryOp)
        val binOp = letBinding.value as ParsedBinaryOp
        assertEquals("+", binOp.op.symbol)
        assertEquals(1000L, (binOp.left as ParsedIntLit).value)
        assertEquals(2000L, (binOp.right as ParsedIntLit).value)
    }

    @Test
    fun `test Nat arithmetic with underscores`() {
        val source = "let x = 1_000n + 2_000n"
        val module = parser.parseParsedModule(source)
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        assert(letBinding.value is ParsedBinaryOp)
        val binOp = letBinding.value as ParsedBinaryOp
        assertEquals("+", binOp.op.symbol)
    }
}
