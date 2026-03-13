package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

/**
 * Tests for newtype deriving behavior.
 *
 * Newtypes have special rules:
 * - Inspect: Always auto-derived (universal debugging)
 * - Eq, Ord: Only available if explicitly listed in `deriving` clause
 */
class NewtypeDerivingTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    @Test
    fun `test newtype without deriving Eq cannot use equality`() {
        val source = """
            newtype UserId = UserId Int

            let compare = {a: UserId, b: UserId} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(ast)
        }

        // The error message should mention that UserId doesn't support equality
        // (either via "does not derive Eq" or "doesn't support equality comparison")
        assertTrue(
            exception.message?.contains("UserId") == true &&
            (exception.message?.contains("Eq") == true ||
             exception.message?.contains("equality") == true),
            "Expected error about UserId missing Eq, got: ${exception.message}"
        )

        println("✅ Newtype without deriving Eq correctly rejects equality!")
    }

    @Test
    fun `test newtype with deriving Eq can use equality`() {
        val source = """
            newtype UserId = UserId Int deriving Eq

            let compare = {a: UserId, b: UserId} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - returns TypeCheckResult on success
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Newtype with deriving Eq can use equality!")
    }

    @Test
    fun `test newtype Inspect always works without deriving`() {
        val source = """
            newtype Email = Email String

            let show = {e: Email} -> Inspect.to_str {val = e}
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - Inspect is always auto-derived for newtypes
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Newtype Inspect works without explicit deriving!")
    }

    @Test
    fun `test newtype with deriving multiple abilities`() {
        val source = """
            newtype Score = Score Int deriving Eq, Ord

            let compare = {a: Score, b: Score} -> a == b
            let order = {a: Score, b: Score} -> a < b
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - both Eq and Ord are derived
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Newtype with deriving multiple abilities works!")
    }

    @Test
    fun `test newtype without deriving Ord cannot use ordering`() {
        val source = """
            newtype UserId = UserId Int deriving Eq

            let order = {a: UserId, b: UserId} -> a < b
        """.trimIndent()

        val ast = parser.parse(source)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(ast)
        }

        // The error message should mention that UserId doesn't support ordering
        // (either via "does not derive Ord" or "doesn't support ordering comparison")
        assertTrue(
            exception.message?.contains("UserId") == true &&
            (exception.message?.contains("Ord") == true ||
             exception.message?.contains("ordering") == true),
            "Expected error about UserId missing Ord, got: ${exception.message}"
        )

        println("✅ Newtype with only Eq correctly rejects ordering!")
    }

    @Test
    fun `test regular variant still auto-derives Eq`() {
        // Regular variants (not newtypes) should still auto-derive Eq
        val source = """
            type Color = | Red, Green, Blue |

            let compare = {a: Color, b: Color} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - regular variants auto-derive Eq
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Regular variant still auto-derives Eq!")
    }

    @Test
    fun `test single-constructor variant is not treated as newtype`() {
        // A single-constructor variant should NOT be treated as a newtype
        // It should still auto-derive Eq
        val source = """
            type Wrapper = | Wrap Int |

            let compare = {a: Wrapper, b: Wrapper} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - single-constructor variants (declared with 'type') still auto-derive Eq
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Single-constructor variant is NOT treated as newtype - auto-derives Eq!")
    }

    @Test
    fun `test newtype with record wrapped type can derive Eq`() {
        val source = """
            newtype Point = Point { x: Int, y: Int } deriving Eq

            let same = {a: Point, b: Point} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        // Should not throw - Eq is explicitly derived
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program, "Expected successful type check with a program")

        println("✅ Newtype with record wrapped type can derive Eq!")
    }
}
