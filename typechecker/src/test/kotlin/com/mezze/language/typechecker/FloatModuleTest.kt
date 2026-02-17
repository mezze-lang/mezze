package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for float module functions.
 * Verifies that importing and using float utility functions works correctly.
 */
class FloatModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("mezze.project.root", "/Users/shriyans/Projects/mezze")
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test float abs function`() {
        val source = """
            use std::float::{abs}
            let result1 = abs { n = -3.14 }
            let result2 = abs { n = 2.71 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float min function`() {
        val source = """
            use std::math::core::{min}
            let result = min { l = 3.14, r = 2.71 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float max function`() {
        val source = """
            use std::math::core::{max}
            let result = max { l = 3.14, r = 2.71 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float pow function`() {
        val source = """
            use std::math::core::{pow}
            let result = pow { base = 2.0, exp = 3.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float sqrt function`() {
        val source = """
            use std::math::core::{sqrt}
            let result = sqrt { n = 16.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float floor function`() {
        val source = """
            use std::math::core::{floor}
            let result = floor { n = 3.7 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float ceil function`() {
        val source = """
            use std::math::core::{ceil}
            let result = ceil { n = 3.2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float round function`() {
        val source = """
            use std::math::core::{round}
            let result = round { n = 3.5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float from_int function`() {
        val source = """
            use std::float::{from_int, from_string}
            let result = from_int { n = 42 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test float from_string function`() {
        val source = """
            use std::float::{from_string}
            let result = from_string { s = "3.14" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple float operations`() {
        val source = """
            use std::math::core::{min, max}
            let x = 3.14
            let y = 2.71
            let min_val = min { l = x, r = y }
            let max_val = max { l = x, r = y }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
