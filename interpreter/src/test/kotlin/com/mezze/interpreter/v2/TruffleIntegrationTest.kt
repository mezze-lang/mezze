package com.mezze.interpreter.v2

import com.mezze.interpreter.v2.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.graalvm.polyglot.PolyglotException

/**
 * V2 interpreter integration tests.
 *
 * Migrated from V1 TruffleIntegrationTest. These tests verify the interpreter
 * correctly evaluates basic language constructs including literals, arithmetic,
 * comparisons, and type checking.
 */
class TruffleIntegrationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Helper to extract fraction components (Frac is represented as MezzeRecord in V2)
    private fun getNumerator(result: Any): Long {
        assertTrue(result is MezzeRecord, "Expected MezzeRecord for fraction, got: ${result.javaClass}")
        return (result as MezzeRecord).getField("numerator") as Long
    }

    private fun getDenominator(result: Any): Long {
        assertTrue(result is MezzeRecord, "Expected MezzeRecord for fraction, got: ${result.javaClass}")
        return (result as MezzeRecord).getField("denominator") as Long
    }

    // ========================================
    // Basic Literal Tests
    // ========================================

    @Test
    fun `test integer literal`() {
        val result = eval("42")
        assertEquals(42L, result)
    }

    @Test
    fun `test string literal`() {
        val result = eval("\"hello\"")
        assertEquals("hello", result)
    }

    @Test
    fun `test boolean literal true`() {
        val result = eval("True")
        assertEquals(true, result)
    }

    @Test
    fun `test boolean literal false`() {
        val result = eval("False")
        assertEquals(false, result)
    }

    @Test
    fun `test float literal`() {
        val result = eval("3.14")
        assertEquals(3.14, result)
    }

    // ========================================
    // Arithmetic Operations
    // ========================================

    @Test
    fun `test integer addition`() {
        val result = eval("10 + 5")
        assertEquals(15L, result)
    }

    @Test
    fun `test integer multiplication`() {
        val result = eval("6 * 7")
        assertEquals(42L, result)
    }

    @Test
    fun `test integer subtraction`() {
        val result = eval("100 - 25")
        assertEquals(75L, result)
    }

    @Test
    fun `test integer division`() {
        val result = eval("42 / 7")
        assertEquals(6L, result)
    }

    @Test
    fun `test float addition`() {
        val result = eval("3.14 + 2.86")
        assertEquals(6.0, result as Double, 0.001)
    }

    @Test
    fun `test float multiplication`() {
        val result = eval("2.5 * 4.0")
        assertEquals(10.0, result as Double, 0.001)
    }

    // ========================================
    // Comparison Operations
    // ========================================

    @Test
    fun `test equality with equal integers`() {
        val result = eval("42 == 42")
        assertEquals(true, result)
    }

    @Test
    fun `test equality with unequal integers`() {
        val result = eval("42 == 24")
        assertEquals(false, result)
    }

    @Test
    fun `test less than true`() {
        val result = eval("5 < 10")
        assertEquals(true, result)
    }

    @Test
    fun `test less than false`() {
        val result = eval("10 < 5")
        assertEquals(false, result)
    }

    @Test
    fun `test greater than true`() {
        val result = eval("10 > 5")
        assertEquals(true, result)
    }

    @Test
    fun `test greater than false`() {
        val result = eval("5 > 10")
        assertEquals(false, result)
    }

    @Test
    fun `test less than or equal`() {
        val result = eval("5 <= 5")
        assertEquals(true, result)
    }

    @Test
    fun `test greater than or equal`() {
        val result = eval("10 >= 10")
        assertEquals(true, result)
    }

    // ========================================
    // Variable Binding and Usage
    // ========================================

    @Test
    fun `test let binding and usage`() {
        val code = """
            let x = 42
            let y = 24
            x + y
        """.trimIndent()

        val result = eval(code)
        assertEquals(66L, result)
    }

    @Test
    fun `test multiple let bindings`() {
        val code = """
            let a = 10
            let b = 20
            let c = 30
            a + b + c
        """.trimIndent()

        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test nested scope with same variable name`() {
        // In Mezze, shadowing is not allowed in the same scope,
        // but we can test using different variable names
        val code = """
            let x = 10
            let y = x + 10
            y
        """.trimIndent()

        val result = eval(code)
        assertEquals(20L, result)
    }

    // ========================================
    // Complex Expressions
    // ========================================

    @Test
    fun `test complex arithmetic expression`() {
        // (10 + 20) * 30 = 30 * 30 = 900
        val code = "(10 + 20) * 30"
        val result = eval(code)
        assertEquals(900L, result)
    }

    @Test
    fun `test nested arithmetic`() {
        // ((5 * 2) + (3 * 4)) = 10 + 12 = 22
        val code = "(5 * 2) + (3 * 4)"
        val result = eval(code)
        assertEquals(22L, result)
    }

    @Test
    fun `test large number addition`() {
        val code = "1000000 + 1000"
        val result = eval(code)
        assertEquals(1001000L, result)
    }

    // ========================================
    // Repeated Evaluation Consistency
    // ========================================

    @Test
    fun `test repeated evaluation produces consistent results`() {
        repeat(10) {
            val result = eval("100 + 200")
            assertEquals(300L, result)
        }
    }

    // ========================================
    // Type Checking Errors
    // ========================================

    @Test
    fun `test mixed type addition fails type check`() {
        val code = "10.5 + 5"
        val exception = assertThrows(PolyglotException::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("TypeCheckError") == true ||
                   exception.message?.contains("Type mismatch") == true ||
                   exception.message?.contains("Cannot") == true)
    }

    // ========================================
    // Nat Type Operations
    // ========================================

    @Test
    fun `test nat addition`() {
        val result = eval("15n + 25n")
        assertEquals(40L, result)
    }

    @Test
    fun `test nat multiplication`() {
        val result = eval("5n * 8n")
        assertEquals(40L, result)
    }

    // ========================================
    // Frac Type Operations
    // ========================================

    @Test
    fun `test frac addition`() {
        // 1/2 + 1/3 = 5/6
        val code = """
            let a = 1//2
            let b = 1//3
            a + b
        """
        val result = eval(code)
        assertTrue(result is MezzeRecord)
        assertEquals(5L, getNumerator(result))
        assertEquals(6L, getDenominator(result))
    }

    @Test
    fun `test frac literal`() {
        val result = eval("1//2")
        assertTrue(result is MezzeRecord)
        assertEquals(1L, getNumerator(result))
        assertEquals(2L, getDenominator(result))
    }
}
