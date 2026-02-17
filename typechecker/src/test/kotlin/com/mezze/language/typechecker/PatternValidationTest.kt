package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for pattern validation in match expressions.
 * These tests ensure that patterns are validated correctly against variant types.
 */
class PatternValidationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== UNIT VARIANT PAYLOAD DESTRUCTURING (BUG FIX) ==========

    @Test
    fun `test unit variant with payload pattern should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let color_math = {color} ->
                match color is
                    Red -> 0
                    Blue { val } -> 3 * val
                    Green -> 2
        """.trimIndent()

        // Should throw - Blue has no payload but pattern tries to destructure { val }
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Blue") == true, "Message should contain 'Blue'. Actual: ${exception.message}")
        // The error message says "does not have a payload" when trying to destructure a unit variant
        assertTrue(exception.message?.contains("does not have a payload") == true || exception.message?.contains("no payload") == true,
            "Message should contain 'does not have a payload' or 'no payload'. Actual: ${exception.message}")
    }

    @Test
    fun `test unit variant without payload pattern should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let color_math = {color} ->
                match color is
                    Red -> 0
                    Blue -> 1
                    Green -> 2
        """.trimIndent()

        // Should pass - all patterns match unit variants correctly
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test variant with payload pattern should pass`() {
        val code = """
            type Color = | Red, Blue { intensity: Int }, Green |

            let color_math = {color} ->
                match color is
                    Red -> 0
                    Blue { intensity } -> intensity * 3
                    Green -> 2
        """.trimIndent()

        // Should pass - Blue has payload and pattern destructures it correctly
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple unit variants with payload patterns should fail`() {
        val code = """
            type Status = | Active, Inactive, Pending |

            let check = {status} ->
                match status is
                    Active { id } -> id
                    Inactive -> 0
                    Pending { reason } -> 1
        """.trimIndent()

        // Should throw - both Active and Pending have no payload
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    @Test
    fun `test mixed variants with correct patterns should pass`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: String }, Loading |

            let handle = {result} ->
                match result is
                    Ok { value } -> value
                    Err { message } -> 0
                    Loading -> 0
        """.trimIndent()

        // Should pass - Ok and Err have payloads, Loading is unit
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test variant with payload ignoring value should pass`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let is_some: {opt: Option} -> Bool = {opt} ->
                match opt is
                    Some { val } -> True
                    None -> False
        """.trimIndent()

        // Should pass - Some has payload and pattern destructures it
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
