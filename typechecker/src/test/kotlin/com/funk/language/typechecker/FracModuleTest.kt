package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for frac module functions.
 * Verifies that importing and using fraction utility functions works correctly.
 */
class FracModuleTest {

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
    fun `test frac make function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac from_int function`() {
        val source = """
            use std::frac
            let f = frac::from_int { n = 42 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac from_nat function`() {
        val source = """
            use std::frac
            let f = frac::from_nat { n = 10 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac add function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 1, denominator = 2 }
            let f2 = frac::make { numerator = 1, denominator = 3 }
            let result = frac::add { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac subtract function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 3, denominator = 4 }
            let f2 = frac::make { numerator = 1, denominator = 4 }
            let result = frac::subtract { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac multiply function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 2, denominator = 3 }
            let f2 = frac::make { numerator = 3, denominator = 4 }
            let result = frac::multiply { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac divide function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 1, denominator = 2 }
            let f2 = frac::make { numerator = 1, denominator = 3 }
            let result = frac::divide { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac equals function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 1, denominator = 2 }
            let f2 = frac::make { numerator = 2, denominator = 4 }
            let result = frac::equals { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac less_than function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 1, denominator = 3 }
            let f2 = frac::make { numerator = 1, denominator = 2 }
            let result = frac::less_than { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac greater_than function`() {
        val source = """
            use std::frac
            let f1 = frac::make { numerator = 3, denominator = 4 }
            let f2 = frac::make { numerator = 1, denominator = 2 }
            let result = frac::greater_than { l = f1, r = f2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac to_float function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
            let result = frac::to_float { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac to_string function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
            let result = frac::to_string { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac numerator function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
            let result = frac::numerator { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac denominator function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
            let result = frac::denominator { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac abs function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = -3, denominator = 4 }
            let result = frac::abs { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac negate function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 3, denominator = 4 }
            let result = frac::negate { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test frac reduce function`() {
        val source = """
            use std::frac
            let f = frac::make { numerator = 6, denominator = 8 }
            let result = frac::reduce { n = f }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
