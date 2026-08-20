package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
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
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheckShouldFail(code: String): String {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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
        // Any of the accepted rejection formats is fine — what matters is that
        // adding a function value is still rejected.
        // Old format: "Cannot apply arithmetic operator '(+)' ... function type ... NumAdd ability"
        // Unification format: "Type mismatch Int with {...} -> ..." (unification of record fields fails)
        // Heterogeneous-NumAdd format: "Cannot use '+' with a function. Functions cannot implement NumAdd."
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch") ||
            error.contains("Cannot use '+' with a function"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject function subtraction with integer`() {
        val code = """
            let f = {x: Int} -> x * 2
            let result = f - 5
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Either the old error format or the new unification error format is acceptable
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject function multiplication`() {
        val code = """
            let f = {x: Int} -> x
            let g = {y: Int} -> y
            let result = f * g
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Either the old error format or the new unification/ability error format is acceptable
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch") ||
            error.contains("No implementation of NumMul"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject function division`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = 100 / f
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Either the old error format or the new unification error format is acceptable
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject float addition with function`() {
        val code = """
            let f = {x: Float} -> x + 1.0
            let result = 10.5 + f
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Any of the accepted rejection formats is fine — the function value must
        // still be rejected. Heterogeneous NumAdd emits
        // "Cannot use '+' with a function. Functions cannot implement NumAdd."
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch") ||
            error.contains("Cannot use '+' with a function"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject polymorphic function in arithmetic`() {
        // Direct ability method access (Parse.decode) is now blocked.
        // Instead, test that a function value cannot be used in arithmetic.
        val code = """
            let decode = {val: Str} -> val
            let x = decode
            let result = 10 + x
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Any of the accepted rejection formats is fine — the function value must
        // still be rejected. Heterogeneous NumAdd emits
        // "Cannot use '+' with a function. Functions cannot implement NumAdd."
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch") ||
            error.contains("Cannot use '+' with a function"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should reject function in complex arithmetic expression`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = (10 + 20) * f - 5
        """.trimIndent()

        val error = typeCheckShouldFail(code)
        // Either the old error format or the new unification error format is acceptable
        assertTrue(
            error.contains("Cannot apply arithmetic operator") ||
            error.contains("Type mismatch") ||
            error.contains("type mismatch"),
            "Expected arithmetic/unification error but got: $error"
        )
    }

    @Test
    fun `should allow valid arithmetic with integers`() {
        val code = """
            let x = 10
            let y = 20
            let result = x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        // Should not throw
        typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `should allow valid arithmetic with function results`() {
        val code = """
            let f = {x: Int} -> x + 1
            let result = 10 + f { x = 5 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        // Should not throw
        typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `should allow function application then arithmetic`() {
        val code = """
            let f = {x: Int} -> x * 2
            let g = {y: Int} -> y + 3
            let result = f { x = 5 } + g { y = 10 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        // Should not throw
        typeChecker.typeCheck(parsedModule, Paths.get("."))
    }
}
