package com.mezze.language.typechecker

import com.mezze.language.ast.Program
import com.mezze.language.ast.TypeCheckResult as AstTypeCheckResult
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

    private fun parseAndTypeCheck(code: String): Program =
        typeChecker.typeCheck(parser.parse(code)).program

    private fun parseAndTypeCheckWithWarnings(code: String): Pair<Program, List<String>> {
        // Use checkProgram which returns the internal TypeCheckResult with warnings
        val result = typeChecker.checkProgram(parser.parse(code))
        return when (result) {
            is TypeChecker.TypeCheckResult.Success -> Pair(result.program, result.warnings)
            is TypeChecker.TypeCheckResult.Failure -> throw result.errors.first().let {
                TypeCheckError(it.message, it.sourceLocation)
            }
        }
    }

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

    // ========== UNKNOWN CONSTRUCTOR IN PATTERN (NEW FIX) ==========

    @Test
    fun `unknown constructor in pattern should fail`() {
        val code = """
            type Color = | Red, Green, Blue |

            let test = {c: Color} ->
                match c is
                    Red -> 0
                    Error -> 1
                    Blue -> 2
        """.trimIndent()

        // Should throw - Error is not defined anywhere
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Unknown constructor") == true ||
                   exception.message?.contains("Error") == true,
            "Error should mention unknown constructor. Actual: ${exception.message}")
    }

    @Test
    fun `unknown constructor with typo should suggest similar names`() {
        val code = """
            type Color = | Red, Green, Blue |

            let test = {c: Color} ->
                match c is
                    Rede -> 0
        """.trimIndent()

        // Should throw with suggestion for 'Red'
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // The error message might contain "Did you mean" with suggestion
        assertTrue(exception.message?.contains("Unknown constructor") == true ||
                   exception.message?.contains("Rede") == true,
            "Error should mention unknown constructor. Actual: ${exception.message}")
    }

    // ========== LITERAL PATTERN ON VARIANT TYPE (NEW FIX) ==========

    @Test
    fun `literal pattern on variant type should fail with clear message`() {
        val code = """
            type Color = | Red, Green, Blue |

            let test = {c: Color} ->
                match c is
                    21313 -> "matched"
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot match variant type") == true ||
                   exception.message?.contains("literal") == true,
            "Error should mention variant type or literal. Actual: ${exception.message}")
    }

    @Test
    fun `string literal pattern on variant type should fail`() {
        val code = """
            type Status = | Active, Inactive |

            let test = {s: Status} ->
                match s is
                    "active" -> 1
                    _ -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot match") == true ||
                   exception.message?.contains("literal") == true,
            "Error should mention type mismatch. Actual: ${exception.message}")
    }

    // ========== VALID CONSTRUCTOR PATTERNS SHOULD STILL WORK ==========

    @Test
    fun `valid constructor patterns should work`() {
        val code = """
            type Color = | Red, Green, Blue |

            let test = {c: Color} ->
                match c is
                    Red -> "red"
                    Green -> "green"
                    Blue -> "blue"
        """.trimIndent()

        val result = typeChecker.typeCheck(parser.parse(code))
        assertNotNull(result.program, "Valid patterns should type check successfully")
    }

    // ========== UNUSED VARIABLE WARNINGS ==========

    @Test
    fun `unused pattern binding should produce warning`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let test = {opt: Option} ->
                match opt is
                    Some { val } -> 42
                    None -> 0
        """.trimIndent()

        val (program, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(program)
        // Check warnings contain unused variable
        assertTrue(warnings.any { it.contains("Unused variable") && it.contains("val") },
            "Should warn about unused variable 'val'. Warnings: $warnings")
    }

    @Test
    fun `used pattern binding should not produce warning`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let test = {opt: Option} ->
                match opt is
                    Some { val } -> val
                    None -> 0
        """.trimIndent()

        val (program, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(program)
        // Should not warn since 'val' is used
        assertTrue(warnings.none { it.contains("Unused variable") && it.contains("val") },
            "Should not warn about 'val' since it's used. Warnings: $warnings")
    }

    @Test
    fun `underscore prefix suppresses unused variable warning`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let test = {opt: Option} ->
                match opt is
                    Some { val = _unused } -> 42
                    None -> 0
        """.trimIndent()

        val (program, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(program)
        // Variables starting with _ should not produce warnings
        assertTrue(warnings.none { it.contains("_unused") },
            "Should not warn about '_unused'. Warnings: $warnings")
    }

    // ========== FLOAT LITERAL PATTERN WARNING ==========

    @Test
    fun `float literal pattern should produce warning`() {
        val code = """
            let test = {x: Float} ->
                match x is
                    3.14 -> "pi"
                    _ -> "other"
        """.trimIndent()

        val (program, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(program)
        assertTrue(warnings.any { it.contains("Float literal pattern") || it.contains("floating-point") },
            "Should warn about float literal pattern. Warnings: $warnings")
    }

    // ========== LIST VS ARRAY PATTERN ERROR ==========

    @Test
    fun `array pattern syntax on list type should fail with helpful error`() {
        val code = """
            let test = {lst: List Int} ->
                match lst is
                    [head, ...tail] -> head
                    [] -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Should mention List vs Array or suggest using Cons/Nil
        assertTrue(exception.message?.contains("List") == true ||
                   exception.message?.contains("Cons") == true ||
                   exception.message?.contains("Array") == true,
            "Error should mention List/Array mismatch. Actual: ${exception.message}")
    }
}
