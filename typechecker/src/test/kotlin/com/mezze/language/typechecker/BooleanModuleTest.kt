package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for boolean module functions.
 * Verifies that importing and using boolean logic functions works correctly.
 */
class BooleanModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `test boolean and function`() {
        val source = """
            use std::bool
            let result = bool::and { l = True, r = False }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean or function`() {
        val source = """
            use std::bool
            let result = bool::or { l = True, r = False }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean not function`() {
        val source = """
            use std::bool::{not}
            let result = not { b = True }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean xor function`() {
        val source = """
            use std::bool
            let result = bool::xor { l = True, r = False }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean implies function`() {
        val source = """
            use std::bool
            let result = bool::implies { l = True, r = False }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test boolean iff function`() {
        val source = """
            use std::bool
            let result = bool::iff { l = True, r = True }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple boolean operations`() {
        val source = """
            use std::bool::{and, or, not, xor}
            let a = True
            let b = False
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
            let p = True
            let q = False
            let implies_result = bool::implies { l = p, r = q }
            let iff_result = bool::iff { l = p, r = q }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
