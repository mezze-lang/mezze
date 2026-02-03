package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for open record type aliases.
 *
 * These tests verify that:
 * 1. Open record type aliases (with ...) accept records with extra fields
 * 2. Closed record type aliases (without ...) reject records with extra fields
 * 3. Open records work correctly in function parameters and return types
 */
class OpenRecordTypeAliasTest {

    private fun parseAndTypeCheck(code: String): Program {
        val parser = FunkParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast).program
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val program = parseAndTypeCheck(code)
        val actualType = getVariableType(program, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeChecks(code: String) {
        parseAndTypeCheck(code)
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ===== Open Record Type Aliases =====

    @Test
    fun `test open record type alias accepts extra fields`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let x: Person = { name = "Alice", age = 30, city = "NYC" }
            x
            """.trimIndent(),
            "x",
            "Person"  // Type alias name is preserved
        )
    }

    @Test
    fun `test open record type alias with multiple extra fields`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let x: Person = { name = "Bob", age = 25, city = "SF", job = "Engineer", salary = 100000 }
            x
            """.trimIndent(),
            "x",
            "Person"  // Type alias name is preserved
        )
    }

    @Test
    fun `test open record type alias accepts exact fields`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let x: Person = { name = "Charlie", age = 35 }
            x
            """.trimIndent(),
            "x",
            "Person"  // Type alias name is preserved
        )
    }

    @Test
    fun `test open record type alias in function parameter`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let greet: { person: Person } -> String = { person } ->
                "$(person.name) is awesome"

            let result = greet { person = { name = "Diana", age = 28, city = "LA" } }
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    @Test
    fun `test open record type alias in function return type`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let makePerson: { name: String, age: Int, city: String } -> Person =
                { name, age, city } -> { name = name, age = age, city = city }

            let result = makePerson { name = "Eve", age = 40, city = "Boston" }
            result
            """.trimIndent(),
            "result",
            "Person"  // Type alias name is preserved from function return type
        )
    }

    // ===== Closed Record Type Aliases =====

    @Test
    fun `test closed record type alias rejects extra fields`() {
        assertTypeCheckFails(
            """
            type ClosedPerson = { name: String, age: Int }

            let x: ClosedPerson = { name = "Frank", age = 45, city = "Seattle" }
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test closed record type alias accepts exact fields`() {
        assertTypeChecks(
            """
            type ClosedPerson = { name: String, age: Int }

            let x: ClosedPerson = { name = "Grace", age = 50 }
            x
            """.trimIndent(),
            "x",
            "ClosedPerson"  // Type alias name is preserved
        )
    }

    @Test
    fun `test closed record type alias rejects missing fields`() {
        assertTypeCheckFails(
            """
            type ClosedPerson = { name: String, age: Int }

            let x: ClosedPerson = { name = "Henry" }
            x
            """.trimIndent()
        )
    }

    // ===== Mixed Scenarios =====

    @Test
    fun `test open and closed record type aliases together`() {
        assertTypeChecks(
            """
            type OpenPerson = { name: String, ... }
            type ClosedPerson = { name: String, age: Int }

            let open: OpenPerson = { name = "Irene", age = 22, city = "Austin" }
            let closed: ClosedPerson = { name = "Jack", age = 33 }

            open
            """.trimIndent(),
            "open",
            "OpenPerson"  // Type alias name is preserved
        )
    }

    @Test
    fun `test nested open records`() {
        assertTypeChecks(
            """
            type Address = { city: String, ... }
            type Person = { name: String, address: Address, ... }

            let x: Person = {
                name = "Kate",
                address = { city = "Denver", state = "CO", zip = 80202 },
                age = 27
            }
            x
            """.trimIndent(),
            "x",
            "Person"  // Type alias name is preserved (nested Address is also an alias)
        )
    }

    @Test
    fun `test open record with different field order`() {
        assertTypeChecks(
            """
            type Person = { name: String, age: Int, ... }

            let x: Person = { age = 55, city = "Portland", name = "Leo" }
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test open record type alias with polymorphism`() {
        assertTypeChecks(
            """
            type Container 'a = { value: 'a, ... }

            let x: Container Int = { value = 42, label = "answer" }
            x
            """.trimIndent(),
            "x",
            "Container Int"  // Polymorphic type alias name is preserved with type arguments
        )
    }

    @Test
    fun `test open record rejects incompatible field types`() {
        assertTypeCheckFails(
            """
            type Person = { name: String, age: Int, ... }

            let x: Person = { name = "Maya", age = "thirty" }
            x
            """.trimIndent()
        )
    }
}
