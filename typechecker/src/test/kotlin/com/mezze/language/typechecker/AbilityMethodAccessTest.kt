package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Tests for ability method access without calling them.
 * Direct ability method access like Ability.method (e.g., Inspect.to_dbg_str, Eq.eq)
 * is now BLOCKED by the typechecker — it throws TypeCheckError with "not allowed".
 */
class AbilityMethodAccessTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== INSPECT ABILITY ==========

    @Test
    fun `test Inspect to_dbg_str access should return function type`() {
        val code = """
            let x = Inspect.to_dbg_str
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    @Test
    fun `test Inspect to_dbg_str can be stored and called later`() {
        val code = """
            let stringify = Inspect.to_dbg_str
            let result = stringify {}
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== EQ ABILITY ==========

    @Test
    fun `test Eq eq access should return function type`() {
        val code = """
            let my_eq = Eq.eq
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    @Test
    fun `test Eq eq can be stored and called later`() {
        val code = """
            let equals = Eq.eq
            let result = equals {r = 2}
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== NUMADD ABILITY ==========

    @Test
    fun `test NumAdd add access should return function type`() {
        val code = """
            let add = NumAdd.add
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    @Test
    fun `test NumAdd add can be stored and called later`() {
        val code = """
            let add = NumAdd.add
            let result = add {r = 2}
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== NUMSUB ABILITY ==========

    @Test
    fun `test NumSub sub access should return function type`() {
        val code = """
            let sub = NumSub.sub
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== NUMMUL ABILITY ==========

    @Test
    fun `test NumMul mul access should return function type`() {
        val code = """
            let mul = NumMul.mul
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== NUMDIV ABILITY ==========

    @Test
    fun `test NumDiv div access should return function type`() {
        val code = """
            let div = NumDiv.div
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not allowed") == true,
            "Expected error containing 'not allowed', got: ${exception.message}")
    }

    // ========== ERROR CASES ==========

    @Test
    fun `test invalid method on ability should fail`() {
        val code = """
            let x = Inspect.invalid_method
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("invalid_method") == true ||
                   exception.message?.contains("not found") == true)
    }

    @Test
    fun `test invalid method on Eq should fail`() {
        val code = """
            let x = Eq.not_a_method
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("not_a_method") == true ||
                   exception.message?.contains("not found") == true)
    }
}
