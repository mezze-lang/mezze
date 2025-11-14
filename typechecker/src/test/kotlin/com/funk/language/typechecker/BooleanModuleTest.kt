package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for boolean module functions.
 * Verifies that importing and using boolean logic functions works correctly.
 */
class BooleanModuleTest {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun parseAndTypeCheck(source: String): com.funk.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test boolean and function`() {
        val source = """
            use std::bool
            let result = bool::and { l = true, r = false }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean or function`() {
        val source = """
            use std::bool
            let result = bool::or { l = true, r = false }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean not function`() {
        val source = """
            use std::bool::{not}
            let result = not { b = true }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean xor function`() {
        val source = """
            use std::bool
            let result = bool::xor { l = true, r = false }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean implies function`() {
        val source = """
            use std::bool
            let result = bool::implies { l = true, r = false }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean iff function`() {
        val source = """
            use std::bool
            let result = bool::iff { l = true, r = true }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple boolean operations`() {
        val source = """
            use std::bool::{and, or, not, xor}
            let a = true
            let b = false
            let and_result = and { l = a, r = b }
            let or_result = or { l = a, r = b }
            let not_result = not { b = a }
            let xor_result = xor { l = a, r = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean logic composition`() {
        val source = """
            use std::bool
            let p = true
            let q = false
            let implies_result = bool::implies { l = p, r = q }
            let iff_result = bool::iff { l = p, r = q }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
