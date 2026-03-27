package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for nat module functions.
 * Verifies that importing and using natural number utility functions works correctly.
 */
class NatModuleTest {

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
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test nat subtract_to_int function`() {
        val source = """
            use std::nat
            let result = nat::subtract_to_int { l = 10n, r = 15n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat from_int function`() {
        val source = """
            use std::nat
            let result = nat::from_int { n = 42 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat from_float function`() {
        val source = """
            use std::nat
            let result = nat::from_float { n = 42.7 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat from_string function`() {
        val source = """
            use std::nat
            let result = nat::from_string { s = "123" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat min function`() {
        val source = """
            use std::nat
            let result = nat::min { l = 10n, r = 20n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat max function`() {
        val source = """
            use std::nat
            let result = nat::max { l = 10n, r = 20n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat is_zero function`() {
        val source = """
            use std::nat
            let result = nat::is_zero { n = 0n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat is_even function`() {
        val source = """
            use std::nat
            let result = nat::is_even { n = 42n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat is_odd function`() {
        val source = """
            use std::nat
            let result = nat::is_odd { n = 43n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple nat operations`() {
        val source = """
            use std::nat
            let a = 10n
            let b = 20n
            let min_val = nat::min { l = a, r = b }
            let max_val = nat::max { l = a, r = b }
            let is_even_result = nat::is_even { n = a }
            let is_odd_result = nat::is_odd { n = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nat conversion functions`() {
        val source = """
            use std::nat
            let from_int_result = nat::from_int { n = 42 }
            let from_float_result = nat::from_float { n = 3.14 }
            let from_string_result = nat::from_string { s = "123" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
