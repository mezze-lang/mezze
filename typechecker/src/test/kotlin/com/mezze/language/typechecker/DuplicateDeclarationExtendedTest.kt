package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
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
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        parser = MezzeParser()
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
                log: {msg: Str} -> {} <Logger>
            }

            effect Logger where {
                debug: {msg: Str} -> {} <Logger>
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
                log: {msg: Str} -> {} <Logger>
            }

            effect Console where {
                print: {msg: Str} -> {} <Console>
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
                log: {msg: Str} -> {} <Log>
            }

            impl Log for SilentLogger where {
                log: {msg: Str} -> {} = {msg} -> {}
            }

            impl Log for SilentLogger where {
                log: {msg: Str} -> {} = {msg} -> {}
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
                log: {msg: Str} -> {} <Log>
            }

            impl Log for SilentLogger where {
                log: {msg: Str} -> {} = {msg} -> {}
            }

            impl Log for VerboseLogger where {
                log: {msg: Str} -> {} = {msg} -> {}
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
                show: {val: 'a} -> Str
            }

            ability Show 'a where {
                display: {val: 'a} -> Str
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
                show: {val: 'a} -> Str
            }

            ability Display 'a where {
                display: {val: 'a} -> Str
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
                show: {val: 'a} -> Str
            }

            impl Show Int where {
                show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Show Int where {
                show: {val: Int} -> Str = {val} -> "number"
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
                show: {val: 'a} -> Str
            }

            impl Show Int where {
                show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Show Str where {
                show: {val: Str} -> Str = {val} -> val
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
                show: {val: 'a} -> Str
            }

            impl Show (List Int) where {
                show: {val: List Int} -> Str = {val} -> "list"
            }

            impl Show (List Int) where {
                show: {val: List Int} -> Str = {val} -> "[...]"
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
                show: {val: 'a} -> Str
            }

            impl Show (List Int) where {
                show: {val: List Int} -> Str = {val} -> "list of ints"
            }

            impl Show (List Str) where {
                show: {val: List Str} -> Str = {val} -> "list of strings"
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

            impl Convert Str Int where {
                convert: {value: Str} -> Int = {value} -> 0
            }

            impl Convert Str Int where {
                convert: {value: Str} -> Int = {value} -> 1
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

            impl Convert Str Int where {
                convert: {value: Str} -> Int = {value} -> 0
            }

            impl Convert Int Str where {
                convert: {value: Int} -> Str = {value} -> int::to_str {n = value}
            }

            Convert.convert {value = "hello"}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
