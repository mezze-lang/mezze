package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Test that arithmetic operations with function types are properly rejected.
 *
 * Bug fix: The type checker was incorrectly allowing arithmetic operations
 * with function types (e.g., `10 + f` where f is a function). Functions
 * don't implement numeric abilities like NumAdd, NumSub, etc.
 */
class ArithmeticWithFunctionsTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheckShouldFail(code: String): String {
        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
        return exception.message ?: ""
    }

    @Test
    fun `should reject integer addition with function`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = 10 + f
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(+)'"))
        assertTrue(error.contains("function type"))
        assertTrue(error.contains("don't implement the NumAdd ability"))
    }

    @Test
    fun `should reject function subtraction with integer`() {
        val code = """
            let f = {x: Int} -> x * 2
            let result = f - 5
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(-)'"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should reject function multiplication`() {
        val code = """
            let f = {x: Int} -> x
            let g = {y: Int} -> y
            let result = f * g
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(*)'"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should reject function division`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = 100 / f
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(/)'"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should reject float addition with function`() {
        val code = """
            let f = {x: Float} -> x + 1.0
            let result = 10.5 + f
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(+)'"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should reject polymorphic function in arithmetic`() {
        val code = """
            ability Parse 'a where {
               decode: {val: String} -> 'a
            }

            impl Parse Int where {
               decode = {val} -> 42
            }

            let x = Parse.decode
            let result = 10 + x
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator '(+)'"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should reject function in complex arithmetic expression`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = (10 + 20) * f - 5
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        assertTrue(error.contains("Cannot apply arithmetic operator"))
        assertTrue(error.contains("function type"))
    }

    @Test
    fun `should allow valid arithmetic with integers`() {
        val code = """
            let x = 10
            let y = 20
            let result = x + y
        """.trimIndent()

        val program = parser.parse(code)
        // Should not throw
        typeChecker.typeCheck(program).program
    }

    @Test
    fun `should allow valid arithmetic with function results`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = 10 + f { x = 5 }
        """.trimIndent()

        val program = parser.parse(code)
        // Should not throw
        typeChecker.typeCheck(program).program
    }

    @Test
    fun `should allow function application then arithmetic`() {
        val code = """
            let f = {x: Int} -> x * 2
            let g = {y: Int} -> y + 3
            let result = f { x = 5 } + g { y = 10 }
        """.trimIndent()

        val program = parser.parse(code)
        // Should not throw
        typeChecker.typeCheck(program).program
    }
}
