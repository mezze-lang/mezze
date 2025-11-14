package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/math/trig module functions.
 * Verifies that importing and using trigonometric functions works correctly.
 */
class MathTrigModuleTest {

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
    fun `test sin function`() {
        val source = """
            use std::math::trig
            let result = trig::sin { n = 1.57079632679 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test cos function`() {
        val source = """
            use std::math::trig
            let result = trig::cos { n = 0.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test tan function`() {
        val source = """
            use std::math::trig
            let result = trig::tan { n = 0.785398163 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test asin function`() {
        val source = """
            use std::math::trig
            let result = trig::asin { n = 0.5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test acos function`() {
        val source = """
            use std::math::trig
            let result = trig::acos { n = 0.5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test atan function`() {
        val source = """
            use std::math::trig
            let result = trig::atan { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test atan2 function`() {
        val source = """
            use std::math::trig
            let result = trig::atan2 { y = 1.0, x = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test degrees_to_radians function`() {
        val source = """
            use std::math::trig
            let result = trig::degrees_to_radians { degrees = 180.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test radians_to_degrees function`() {
        val source = """
            use std::math::trig
            let result = trig::radians_to_degrees { radians = 3.14159265 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test sinh function`() {
        val source = """
            use std::math::trig
            let result = trig::sinh { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test cosh function`() {
        val source = """
            use std::math::trig
            let result = trig::cosh { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test tanh function`() {
        val source = """
            use std::math::trig
            let result = trig::tanh { n = 1.0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test chaining trig operations`() {
        val source = """
            use std::math::trig
            use std::math::core
            let angle = trig::degrees_to_radians { degrees = 45.0 }
            let sine = trig::sin { n = angle }
            let cosine = trig::cos { n = angle }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test inverse trig round trip`() {
        val source = """
            use std::math::trig
            let angle = 0.5
            let asin_result = trig::asin { n = angle }
            let sin_result = trig::sin { n = asin_result }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
