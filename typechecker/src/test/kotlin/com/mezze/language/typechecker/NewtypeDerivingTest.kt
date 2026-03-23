package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

/**
 * Tests for newtype ability behavior.
 *
 * Newtypes do NOT auto-derive Eq/Ord — users must write explicit impl blocks.
 * Only Inspect is universally available.
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
    fun `test newtype without Eq cannot use equality`() {
        val source = """
            newtype UserId = UserId Int
            let compare = {a: UserId, b: UserId} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(ast)
        }
    }

    @Test
    fun `test newtype without Ord cannot use ordering`() {
        val source = """
            newtype UserId = UserId Int
            let order = {a: UserId, b: UserId} -> a < b
        """.trimIndent()

        val ast = parser.parse(source)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(ast)
        }
    }

    @Test
    fun `test newtype Inspect always works`() {
        val source = """
            newtype Email = Email String
            let show = {e: Email} -> e.to_dbg_str {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program)
    }

    @Test
    fun `test regular variant still auto-derives Eq`() {
        val source = """
            type Color = | Red, Green, Blue |
            let compare = {a: Color, b: Color} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program)
    }

    @Test
    fun `test single-constructor variant is not treated as newtype`() {
        val source = """
            type Wrapper = | Wrap Int |
            let compare = {a: Wrapper, b: Wrapper} -> a == b
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.typeCheck(ast)
        assertNotNull(result.program)
    }
}
