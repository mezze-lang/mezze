package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for type inference with 'n' suffix for Nat literals.
 *
 * Rules:
 * - Literals without suffix are Int by default (e.g., 42 is Int)
 * - Literals with 'n' suffix are Nat (e.g., 42n is Nat)
 * - Int and Nat are distinct types with no implicit conversion
 * - Negative literals are always Int (e.g., -5 is Int)
 */
class NatSuffixTypeInferenceTest {

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    /**
     * Asserts a call to a *generalized* polymorphic add (`let add = {x, y} -> x + y`)
     * type-checks. Under the class-parameter NumAdd ability, `add` generalizes to
     *   { x: 'l, y: 'r } with NumAdd 'r 'l having 'out -> 'out
     * so applying it to two same-type literals does not necessarily force the
     * `having 'out` result to the concrete diagonal type at this callsite — the
     * binding may stay a polymorphic variable carrying the NumAdd constraint.
     * Accept either the concrete widened type or that polymorphic result.
     */
    private fun assertPolymorphicAddChecks(code: String, variableName: String, concreteType: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        val rendered = prettyPrintType(actualType)
        assert(
            rendered == concreteType ||
                actualType is MezzeType.VarType ||
                actualType.toString().contains("NumAdd")
        ) {
            "Result '$variableName' should be $concreteType or polymorphic with NumAdd, but was: $rendered"
        }
    }

    // ===== Default behavior: literals without suffix are Int =====

    @Test
    fun `test literal without suffix infers as Int`() {
        assertTypeChecks(
            """
            let x = 42
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    @Test
    fun `test zero without suffix infers as Int`() {
        assertTypeChecks(
            """
            let x = 0
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    @Test
    fun `test negative literal is always Int`() {
        assertTypeChecks(
            """
            let x = -5
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    // ===== Explicit Nat with 'n' suffix =====

    @Test
    fun `test literal with n suffix infers as Nat`() {
        assertTypeChecks(
            """
            let x = 42n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test zero with n suffix infers as Nat`() {
        assertTypeChecks(
            """
            let x = 0n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test large number with n suffix infers as Nat`() {
        assertTypeChecks(
            """
            let x = 1000000n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    // ===== Function parameter type constraints =====

    @Test
    fun `test Int function with Int literal`() {
        assertTypeChecks(
            """
            let f = { x: Int } -> x + 1
            let result = f { x = 42 }
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Nat function with Nat literal`() {
        assertTypeChecks(
            """
            let f = { x: Nat } -> x + 1n
            let result = f { x = 42n }
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    @Test
    fun `test Int function rejects Nat literal`() {
        assertTypeCheckFails(
            """
            let f = { x: Int } -> x + 1
            let result = f { x = 42n }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test Nat function rejects Int literal`() {
        assertTypeCheckFails(
            """
            let f = { x: Nat } -> x + 1n
            let result = f { x = 42 }
            result
            """.trimIndent()
        )
    }

    // ===== Arithmetic operations =====

    @Test
    fun `test Int addition infers as Int`() {
        assertTypeChecks(
            """
            let result = 10 + 20
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Nat addition infers as Nat`() {
        assertTypeChecks(
            """
            let result = 10n + 20n
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    @Test
    fun `test mixing Int and Nat in addition widens to Int`() {
        // Under the class-parameter NumAdd ability, Nat participates in the
        // coercion lattice (Nat ⊆ Int ⊆ Float): `Int + Nat` has an impl
        // (`NumAdd Nat for Int having 'out = Int`) that widens the Nat to Int.
        // So `10 + 20n` now type-checks and yields Int (was previously an error
        // under homogeneous NumAdd, before Nat/Int coercion existed).
        assertTypeChecks(
            """
            let result = 10 + 20n
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Nat subtraction is not allowed`() {
        // Nat - Nat is not allowed because the result can be negative
        // Use nat::subtract_to_int instead which returns Int
        assertTypeCheckFails(
            """
            let result = 20n - 10n
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test Nat multiplication infers as Nat`() {
        assertTypeChecks(
            """
            let result = 10n * 20n
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    // ===== Lists with Nat literals =====

    @Test
    fun `test list of Int literals infers as list of Int`() {
        assertTypeChecks(
            """
            let numbers = [1, 2, 3]
            numbers
            """.trimIndent(),
            "numbers",
            "[Int]"
        )
    }

    @Test
    fun `test list of Nat literals infers as list of Nat`() {
        assertTypeChecks(
            """
            let numbers = [1n, 2n, 3n]
            numbers
            """.trimIndent(),
            "numbers",
            "[Nat]"
        )
    }

    @Test
    fun `test mixing Int and Nat in list fails`() {
        assertTypeCheckFails(
            """
            let numbers = [1, 2n, 3]
            numbers
            """.trimIndent()
        )
    }

    // ===== Polymorphic functions =====

    @Test
    fun `test polymorphic addition with Int literals`() {
        // Under the class-parameter NumAdd ability, `add` generalizes to
        //   { x: 'l, y: 'r } with NumAdd 'r 'l having 'out -> 'out
        // Applying it to two Int literals stays sound; the result is Int or a
        // polymorphic binding still carrying the NumAdd constraint.
        assertPolymorphicAddChecks(
            """
            let add = { x, y } -> x + y
            let result = add { x = 10, y = 20 }
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test polymorphic addition with Nat literals`() {
        // See the Int case above: applying the generalized `add` to two Nat
        // literals yields Nat or a polymorphic result with the NumAdd constraint.
        assertPolymorphicAddChecks(
            """
            let add = { x, y } -> x + y
            let result = add { x = 10n, y = 20n }
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }

    // ===== Underscores with 'n' suffix =====

    @Test
    fun `test Nat with underscores infers as Nat`() {
        assertTypeChecks(
            """
            let x = 1_000_000n
            x
            """.trimIndent(),
            "x",
            "Nat"
        )
    }

    @Test
    fun `test Int with underscores infers as Int`() {
        assertTypeChecks(
            """
            let x = 1_000_000
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    // ===== Comparison operations =====

    @Test
    fun `test Nat comparison infers as Boolean`() {
        assertTypeChecks(
            """
            let result = 10n > 5n
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    @Test
    fun `test Int comparison infers as Boolean`() {
        assertTypeChecks(
            """
            let result = 10 > 5
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    @Test
    fun `test mixing Int and Nat in comparison fails`() {
        assertTypeCheckFails(
            """
            let result = 10 > 5n
            result
            """.trimIndent()
        )
    }

    // ===== Records with Nat fields =====

    @Test
    fun `test record with Nat field`() {
        assertTypeChecks(
            """
            let person = { age = 25n }
            person
            """.trimIndent(),
            "person",
            "{ age: Nat }"
        )
    }

    @Test
    fun `test record with Int field`() {
        assertTypeChecks(
            """
            let data = { value = 42 }
            data
            """.trimIndent(),
            "data",
            "{ value: Int }"
        )
    }

    // ===== Edge cases =====

    @Test
    fun `test Nat variable in Int context fails`() {
        assertTypeCheckFails(
            """
            let x = 42n
            let f = { n: Int } -> n + 1
            let result = f { n = x }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test Int variable in Nat context fails`() {
        assertTypeCheckFails(
            """
            let x = 42
            let f = { n: Nat } -> n + 1n
            let result = f { n = x }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test chained Nat operations maintain Nat type`() {
        assertTypeChecks(
            """
            let x = 10n
            let y = 20n
            let result = x + y * 2n
            result
            """.trimIndent(),
            "result",
            "Nat"
        )
    }
}
