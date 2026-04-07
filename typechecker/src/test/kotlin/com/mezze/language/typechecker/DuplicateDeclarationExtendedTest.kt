package com.mezze.language.typechecker

import java.nio.file.Paths
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

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))

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
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            ability Show for 'a where {
                display: {} -> Str
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
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            ability Display for 'a where {
                display: {} -> Str
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

            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> int::to_str {n = it}
            }

            impl Show for Int where {
                show = {} -> "number"
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

            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> int::to_str {n = it}
            }

            impl Show for Str where {
                show = {it} -> it
            }

            42.show {}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test duplicate ability implementation for generic type should fail`() {
        val code = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for (List Int) where {
                show = {} -> "list"
            }

            impl Show for (List Int) where {
                show = {} -> "[...]"
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
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for (List Int) where {
                show = {} -> "list of ints"
            }

            impl Show for (List Str) where {
                show = {} -> "list of strings"
            }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== MULTI-PARAMETER ABILITY IMPLEMENTATIONS ==========

    @Test
    fun `test duplicate multi-parameter ability implementation should fail`() {
        val code = """
            ability Convert 'to for 'from where {
                convert: {} -> 'to
            }

            impl Convert for Int Str where {
                convert = {} -> 0
            }

            impl Convert for Int Str where {
                convert = {} -> 1
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

            ability Convert 'to for 'from where {
                convert: {} -> 'to
            }

            impl Convert for Int Str where {
                convert = {} -> 0
            }

            impl Convert for Str Int where {
                convert = {it} -> int::to_str {n = it}
            }

            "hello".convert {}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
