package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
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

    private data class TypeCheckResults(val result: TypeCheckResult, val typeChecker: TypeChecker)

    private fun parseAndTypeCheck(code: String): TypeCheckResults {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        return TypeCheckResults(result, typeChecker)
    }

    private fun parseAndTypeCheckResult(code: String): TypeCheckResult {
        return parseAndTypeCheck(code).result
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val results = parseAndTypeCheck(code)
        val actualType = getVariableType(results.result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintTypeWithAliases(actualType, results.typeChecker.getTypeAliases()))
    }

    private fun assertTypeChecks(code: String) {
        parseAndTypeCheckResult(code)
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheckResult(code)
        }
    }

    // ===== Basic Tests: Verify isOpen flag is preserved =====

    private fun getTypeDeclaration(code: String, typeName: String): MezzeType? {
        val (_, tc) = parseAndTypeCheck(code)
        return tc.getTypeAliases()[typeName]?.definition
    }

    @Test
    fun `test closed variant type alias is created as closed`() {
        val code = """
            type Color = |Red, Blue, Green |
            Red
        """.trimIndent()

        val colorType = getTypeDeclaration(code, "Color")

        assertNotNull(colorType)
        assert(colorType is MezzeType.VariantType)
        assertEquals(false, (colorType as MezzeType.VariantType).isOpen)
    }

    @Test
    fun `test open variant type alias is created as open`() {
        val code = """
            type Color = |Red, Blue, Green, ... |
            Red
        """.trimIndent()

        val colorType = getTypeDeclaration(code, "Color")

        assertNotNull(colorType)
        assert(colorType is MezzeType.VariantType)
        assertEquals(true, (colorType as MezzeType.VariantType).isOpen)
    }

    // ===== RowVar Tests =====

    @Test
    fun `test open variant has rowVar`() {
        val code = """
            type Color = |Red, Blue, ... |
            Red
        """.trimIndent()

        val colorType = getTypeDeclaration(code, "Color") as? MezzeType.VariantType

        assertNotNull(colorType)
        assertNotNull(colorType.rowVar, "Open variant should have a rowVar")
    }

    @Test
    fun `test closed variant has no rowVar`() {
        val code = """
            type Color = |Red, Blue |
            Red
        """.trimIndent()

        val colorType = getTypeDeclaration(code, "Color") as? MezzeType.VariantType

        assertNotNull(colorType)
        assertEquals(null, colorType.rowVar, "Closed variant should not have a rowVar")
    }

    // ===== Constructor Validation Tests =====

    @Test
    fun `test closed variant rejects undefined constructor`() {
        // Yellow is not in Color = | Red, Blue, Green |, so this should fail
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
        // Yellow is in Animal but not in Color, so this should fail
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
    fun `test open variant rejects constructor from different declared type`() {
        // Even though Color is open, Yellow belongs to Animal (a declared type)
        // so it cannot be assigned to Color. Open variants accept anonymous
        // constructors, not constructors from other declared types.
        assertTypeCheckFails(
            """
            type Color = |Red, Blue, Green, ... |
            type Animal = |Cat, Dog, Yellow |
            let y: Color = Yellow
            y
            """.trimIndent()
        )
    }

    @Test
    fun `test open variant accepts anonymous constructor`() {
        // When there's no type declaration for Yellow, it's anonymous
        // and can be accepted by the open variant
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
    fun `test closed variant with payload rejects wrong constructor`() {
        // Dog from Animal cannot be assigned to Shape (different closed variant types)
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
        // Red from Color cannot be assigned to Shape (different closed variant types)
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
