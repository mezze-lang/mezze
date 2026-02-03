package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for open variant type aliases and variant constructor validation.
 *
 * These tests verify that:
 * 1. The `isOpen` flag is preserved when creating variant type aliases
 * 2. Closed variant types reject constructors not in their definition
 * 3. Open variant types accept extra constructors
 */
class OpenVariantTypeAliasTest {

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

    // ===== Basic Tests: Verify isOpen flag is preserved =====

    @Test
    fun `test closed variant type alias is created as closed`() {
        val code = """
            type Color = |Red, Blue, Green |
            Red
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        val colorType = program.items.filterIsInstance<TypeDeclaration>()
            .find { it.name == "Color" }?.inferredType

        assertNotNull(colorType)
        assert(colorType is FunkType.VariantType)
        assertEquals(false, (colorType as FunkType.VariantType).isOpen)
    }

    @Test
    fun `test open variant type alias is created as open`() {
        val code = """
            type Color = |Red, Blue, Green, ... |
            Red
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        val colorType = program.items.filterIsInstance<TypeDeclaration>()
            .find { it.name == "Color" }?.inferredType

        assertNotNull(colorType)
        assert(colorType is FunkType.VariantType)
        assertEquals(true, (colorType as FunkType.VariantType).isOpen)
    }

    // ===== RowVar Tests =====

    @Test
    fun `test open variant has rowVar`() {
        val code = """
            type Color = |Red, Blue, ... |
            Red
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        val colorType = program.items.filterIsInstance<TypeDeclaration>()
            .find { it.name == "Color" }?.inferredType as? FunkType.VariantType

        assertNotNull(colorType)
        assertNotNull(colorType.rowVar, "Open variant should have a rowVar")
    }

    @Test
    fun `test closed variant has no rowVar`() {
        val code = """
            type Color = |Red, Blue |
            Red
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        val colorType = program.items.filterIsInstance<TypeDeclaration>()
            .find { it.name == "Color" }?.inferredType as? FunkType.VariantType

        assertNotNull(colorType)
        assertEquals(null, colorType.rowVar, "Closed variant should not have a rowVar")
    }

    // ===== Constructor Validation Tests =====

    @Test
    fun `test closed variant rejects undefined constructor`() {
        assertTypeCheckFails(
            """
            type Color = |Red, Blue, Green |
            let y: Color = Yellow
            y
            """.trimIndent()
        )
    }

    @Test
    fun `test closed variant rejects constructor from different variant`() {
        assertTypeCheckFails(
            """
            type Color = |Red, Blue, Green |
            type Animal = |Cat, Dog, Yellow |
            let y: Color = Yellow
            y
            """.trimIndent()
        )
    }

    @Test
    fun `test closed variant accepts valid constructor`() {
        assertTypeChecks(
            """
            type Color = |Red, Blue, Green |
            let y: Color = Red
            y
            """.trimIndent(),
            "y",
            "Color"
        )
    }

    @Test
    fun `test open variant accepts undefined constructor`() {
        assertTypeChecks(
            """
            type Color = |Red, Blue, Green, ... |
            let y: Color = Yellow
            y
            """.trimIndent(),
            "y",
            "Color"
        )
    }

    @Test
    fun `test open variant accepts constructor from different variant`() {
        assertTypeChecks(
            """
            type Color = |Red, Blue, Green, ... |
            type Animal = |Cat, Dog, Yellow |
            let y: Color = Yellow
            y
            """.trimIndent(),
            "y",
            "Color"
        )
    }

    @Test
    fun `test closed variant with payload rejects wrong constructor`() {
        assertTypeCheckFails(
            """
            type Shape = |Circle { radius: Int }, Square { side: Int } |
            type Animal = |Cat, Dog |
            let s: Shape = Dog
            s
            """.trimIndent()
        )
    }

    @Test
    fun `test open variant with payload accepts extra constructor`() {
        assertTypeChecks(
            """
            type Shape = |Circle { radius: Int }, Square { side: Int }, ... |
            let s: Shape = Triangle
            s
            """.trimIndent(),
            "s",
            "Shape"
        )
    }

    @Test
    fun `test multiple constructors from same variant accepted`() {
        assertTypeChecks(
            """
            type Color = |Red, Blue, Green |
            let x: Color = Red
            let y: Color = Blue
            let z: Color = Green
            z
            """.trimIndent(),
            "z",
            "Color"
        )
    }

    @Test
    fun `test closed variant with nested records rejects wrong constructor`() {
        assertTypeCheckFails(
            """
            type Shape = |Circle { center: { x: Int, y: Int }, radius: Int } |
            type Color = |Red, Blue |
            let s: Shape = Red
            s
            """.trimIndent()
        )
    }
}
