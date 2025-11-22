package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for ability method access without calling them.
 * Ability methods like Inspect.to_str, Eq.eq should return proper function types
 * when accessed without being called.
 */
class AbilityMethodAccessTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code))

    private fun getVariableType(code: String, varName: String): String {
        val program = parseAndTypeCheck(code)
        val binding = program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }
        assertNotNull(binding)
        assertNotNull(binding.inferredType)
        return prettyPrintType(binding.inferredType!!)
    }

    // ========== INSPECT ABILITY ==========

    @Test
    fun `test Inspect to_str access should return function type`() {
        val code = """
            let x = Inspect.to_str
        """.trimIndent()

        val type = getVariableType(code, "x")
        assertTrue(type.contains("val") && type.contains("String"),
            "Expected function type { val: 'a } -> String, got: $type")
    }

    @Test
    fun `test Inspect to_str can be stored and called later`() {
        val code = """
            let stringify = Inspect.to_str
            let result = stringify {val = 42}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)

        val resultType = getVariableType(code, "result")
        assertTrue(resultType == "String", "Expected String, got: $resultType")
    }

    // ========== EQ ABILITY ==========

    @Test
    fun `test Eq eq access should return function type`() {
        val code = """
            let my_eq = Eq.eq
        """.trimIndent()

        val type = getVariableType(code, "my_eq")
        assertTrue(type.contains("l") && type.contains("r") && type.contains("Bool"),
            "Expected function type { l: 'a, r: 'a } -> Bool, got: $type")
    }

    @Test
    fun `test Eq eq can be stored and called later`() {
        val code = """
            let equals = Eq.eq
            let result = equals {l = 1, r = 2}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)

        val resultType = getVariableType(code, "result")
        assertTrue(resultType == "Bool", "Expected Bool, got: $resultType")
    }

    // ========== NUMADD ABILITY ==========

    @Test
    fun `test NumAdd num_add access should return function type`() {
        val code = """
            let add = NumAdd.num_add
        """.trimIndent()

        val type = getVariableType(code, "add")
        assertTrue(type.contains("l") && type.contains("r"),
            "Expected function type { l: 'a, r: 'a } -> 'a, got: $type")
    }

    @Test
    fun `test NumAdd num_add can be stored and called later`() {
        val code = """
            let add = NumAdd.num_add
            let result = add {l = 1, r = 2}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)

        val resultType = getVariableType(code, "result")
        assertTrue(resultType == "Int", "Expected Int, got: $resultType")
    }

    // ========== NUMSUB ABILITY ==========

    @Test
    fun `test NumSub num_sub access should return function type`() {
        val code = """
            let sub = NumSub.num_sub
        """.trimIndent()

        val type = getVariableType(code, "sub")
        assertTrue(type.contains("l") && type.contains("r"),
            "Expected function type { l: 'a, r: 'a } -> 'a, got: $type")
    }

    // ========== NUMMUL ABILITY ==========

    @Test
    fun `test NumMul num_mul access should return function type`() {
        val code = """
            let mul = NumMul.num_mul
        """.trimIndent()

        val type = getVariableType(code, "mul")
        assertTrue(type.contains("l") && type.contains("r"),
            "Expected function type { l: 'a, r: 'a } -> 'a, got: $type")
    }

    // ========== NUMDIV ABILITY ==========

    @Test
    fun `test NumDiv num_div access should return function type`() {
        val code = """
            let div = NumDiv.num_div
        """.trimIndent()

        val type = getVariableType(code, "div")
        assertTrue(type.contains("l") && type.contains("r"),
            "Expected function type { l: 'a, r: 'a } -> 'a, got: $type")
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
