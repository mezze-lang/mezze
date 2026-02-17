package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/math/core module functions.
 * Verifies that importing and using mathematical functions works correctly.
 */
class MathCoreModuleTest {

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

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test pow function`() {
        val source = """
            use std::math::core
            let result = core::pow { base = 2.0, exp = 3.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test sqrt function`() {
        val source = """
            use std::math::core
            let result = core::sqrt { n = 16.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test ln function`() {
        val source = """
            use std::math::core::{ln}
            let result = ln { n = 2.718281828 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test log10 function`() {
        val source = """
            use std::math::core::{log10}
            let result = log10 { n = 100.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test exp function`() {
        val source = """
            use std::math::core::{exp}
            let result = exp { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test floor function`() {
        val source = """
            use std::math::core::{floor}
            let result = floor { n = 3.7 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test ceil function`() {
        val source = """
            use std::math::core::{ceil}
            let result = ceil { n = 3.2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test round function`() {
        val source = """
            use std::math::core::{round}
            let result = round { n = 3.5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test is_nan function`() {
        val source = """
            use std::math::core::{is_nan}
            let result = is_nan { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test is_infinite function`() {
        val source = """
            use std::math::core::{is_infinite}
            let result = is_infinite { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test is_finite function`() {
        val source = """
            use std::math::core::{is_finite}
            let result = is_finite { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test sign function for float`() {
        val source = """
            use std::math::core::{sign}
            let result = sign { n = -5.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test pow_int function`() {
        val source = """
            use std::math::core::{pow_int}
            let result = pow_int { base = 2, exp = 10 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test pow_nat function`() {
        val source = """
            use std::math::core::{pow_nat}
            let result = pow_nat { base = 2n, exp = 5n }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test sign_int function`() {
        val source = """
            use std::math::core::{sign_int}
            let result = sign_int { n = -42 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test abs polymorphic function with int`() {
        val source = """
            use std::int::{abs}
            let result = abs { n = -5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test min polymorphic function`() {
        val source = """
            use std::math::core::{min}
            let result = min { l = 10, r = 20 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test max polymorphic function`() {
        val source = """
            use std::math::core::{max}
            let result = max { l = 10, r = 20 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test chaining math operations`() {
        val source = """
            use std::math::core
            let x = core::sqrt { n = 16.0 }
            let y = core::pow { base = x, exp = 2.0 }
            let z = core::floor { n = y }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test mathematical constants`() {
        val source = """
            use std::math::core::{pi, e}
            let pi_val = pi
            let e_val = e
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
