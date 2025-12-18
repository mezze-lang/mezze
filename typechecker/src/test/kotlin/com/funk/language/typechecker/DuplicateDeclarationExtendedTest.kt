package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Bug #9: Extended duplicate declaration validation
 * - Bug #9a: Duplicate variable declarations in same scope
 * - Bug #9b: Duplicate effect declarations
 * - Bug #9c: Duplicate effect handler/impl declarations
 * - Bug #9d: Duplicate ability declarations
 * - Bug #9e: Duplicate ability implementations for same type
 */
class DuplicateDeclarationExtendedTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== BUG #9a: DUPLICATE VARIABLE DECLARATIONS ==========

    @Test
    fun `test duplicate variable declaration should fail`() {
        val code = """
            let x = 1
            let x = 2
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate variable declaration") == true ||
                   exception.message?.contains("already defined") == true)
    }

    @Test
    fun `test duplicate function declaration should fail`() {
        val code = """
            let add = {x, y} -> x + y
            let add = {x, y} -> x - y
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate variable declaration") == true ||
                   exception.message?.contains("already defined") == true)
    }

    @Test
    fun `test different variable names should pass`() {
        val code = """
            let x = 1
            let y = 2
            x + y
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== BUG #9b: DUPLICATE EFFECT DECLARATIONS ==========

    @Test
    fun `test duplicate effect declaration should fail`() {
        val code = """
            effect Logger where {
                log: {msg: String} -> {} <Logger>
            }

            effect Logger where {
                debug: {msg: String} -> {} <Logger>
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate effect declaration") == true ||
                   exception.message?.contains("already defined") == true)
    }

    @Test
    fun `test different effect names should pass`() {
        val code = """
            effect Logger where {
                log: {msg: String} -> {} <Logger>
            }

            effect Console where {
                print: {msg: String} -> {} <Console>
            }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== BUG #9c: DUPLICATE EFFECT HANDLER DECLARATIONS ==========

    @Test
    fun `test duplicate performer declaration should fail`() {
        val code = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }

            impl Log for SilentLogger where {
                log: {msg: String} -> {} = {msg} -> {}
            }

            impl Log for SilentLogger where {
                log: {msg: String} -> {} = {msg} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate effect handler declaration") == true ||
                   exception.message?.contains("already defined") == true)
    }

    @Test
    fun `test different performer names should pass`() {
        val code = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }

            impl Log for SilentLogger where {
                log: {msg: String} -> {} = {msg} -> {}
            }

            impl Log for VerboseLogger where {
                log: {msg: String} -> {} = {msg} -> {}
            }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== BUG #9d: DUPLICATE ABILITY DECLARATIONS ==========

    @Test
    fun `test duplicate ability declaration should fail`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            ability Show 'a where {
                display: {val: 'a} -> String
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate ability declaration") == true ||
                   exception.message?.contains("already defined") == true)
    }

    @Test
    fun `test different ability names should pass`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            ability Display 'a where {
                display: {val: 'a} -> String
            }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== BUG #9e: DUPLICATE ABILITY IMPLEMENTATIONS ==========

    @Test
    fun `test duplicate ability implementation for same type should fail`() {
        val code = """
            use std::int

            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> "number"
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate ability implementation") == true ||
                   exception.message?.contains("already implemented") == true)
    }

    @Test
    fun `test ability implementation for different types should pass`() {
        val code = """
            use std::int

            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Show String where {
                show: {val: String} -> String = {val} -> val
            }

            Show.show {val = 42}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test duplicate ability implementation for generic type should fail`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show (List Int) where {
                show: {val: List Int} -> String = {val} -> "list"
            }

            impl Show (List Int) where {
                show: {val: List Int} -> String = {val} -> "[...]"
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate ability implementation") == true ||
                   exception.message?.contains("already implemented") == true)
    }

    @Test
    fun `test ability implementation for different generic types should pass`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show (List Int) where {
                show: {val: List Int} -> String = {val} -> "list of ints"
            }

            impl Show (List String) where {
                show: {val: List String} -> String = {val} -> "list of strings"
            }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== MULTI-PARAMETER ABILITY IMPLEMENTATIONS ==========

    @Test
    fun `test duplicate multi-parameter ability implementation should fail`() {
        val code = """
            ability Convert 'from 'to where {
                convert: {value: 'from} -> 'to
            }

            impl Convert String Int where {
                convert: {value: String} -> Int = {value} -> 0
            }

            impl Convert String Int where {
                convert: {value: String} -> Int = {value} -> 1
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Duplicate ability implementation") == true ||
                   exception.message?.contains("already implemented") == true)
    }

    @Test
    fun `test different multi-parameter ability implementations should pass`() {
        val code = """
            use std::int

            ability Convert 'from 'to where {
                convert: {value: 'from} -> 'to
            }

            impl Convert String Int where {
                convert: {value: String} -> Int = {value} -> 0
            }

            impl Convert Int String where {
                convert: {value: Int} -> String = {value} -> int::to_string {n = value}
            }

            Convert.convert {value = "hello"}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
