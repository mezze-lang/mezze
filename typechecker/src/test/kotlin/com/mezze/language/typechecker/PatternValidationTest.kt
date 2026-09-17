package com.mezze.language.typechecker

import java.nio.file.Paths
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

    private fun parseAndTypeCheck(code: String): TypeCheckResult =
        typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))

    private fun parseAndTypeCheckWithWarnings(code: String): Pair<TypeCheckResult, List<String>> {
        val result = typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))
        return Pair(result, result.warnings.map { it.message })
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
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
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
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
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
            type Outcome = | Good { value: Int }, Bad { message: Str }, Loading |

            let handle = {result} ->
                match result is
                    Good { value } -> value
                    Bad { message } -> 0
                    Loading -> 0
        """.trimIndent()

        // Should pass - Ok and Err have payloads, Loading is unit
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test variant with payload ignoring value should pass`() {
        val code = """
            type Maybe = | Just { val: Int }, Nothing |

            let is_some: {opt: Maybe} -> Bool = {opt} ->
                match opt is
                    Just { val } -> True
                    Nothing -> False
        """.trimIndent()

        // Should pass - Some has payload and pattern destructures it
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== PAYLOAD VARIANT WITHOUT DESTRUCTURING (BUG FIX) ==========

    @Test
    fun `payload variant without destructuring pattern should fail`() {
        // This was a BUG: typing `Blue` instead of `Blue { n }` when Blue has payload
        val code = """
            type Color = | Red, Blue { n: Int }, Green |

            let my_func = {x, y, c} -> match c is
                Red -> x + y
                Blue -> x - y
                Green -> x
        """.trimIndent()

        // Should throw - Blue has payload but pattern doesn't destructure
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Blue") == true,
            "Message should contain 'Blue'. Actual: ${exception.message}")
        assertTrue(exception.message?.contains("payload") == true &&
                   exception.message?.contains("must be destructured") == true,
            "Message should say payload must be destructured. Actual: ${exception.message}")
    }

    @Test
    fun `payload variant with proper destructuring should pass`() {
        val code = """
            type Color = | Red, Blue { n: Int }, Green |

            let my_func = {x, y, c} -> match c is
                Red -> x + y
                Blue { n } -> x - y * n
                Green -> x
        """.trimIndent()

        // Should pass - Blue has payload and pattern destructures it
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `multiple payload variants without destructuring should fail`() {
        val code = """
            type Outcome = | Good { val: Int }, Bad { msg: Str } |

            let check = {result} ->
                match result is
                    Good -> 1
                    Bad -> 0
        """.trimIndent()

        // Should throw - both Ok and Err have payloads
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    @Test
    fun `polymorphic payload variant without destructuring should fail`() {
        // Test with type parameter 'a
        val code = """
            type Maybe 'a = | Just { val: 'a }, Nothing |

            let is_some = {opt} ->
                match opt is
                    Just -> True
                    Nothing -> False
        """.trimIndent()

        // Should throw - Some has payload
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Just") == true,
            "Message should contain 'Some'. Actual: ${exception.message}")
    }

    @Test
    fun `List Cons without destructuring should fail`() {
        // Built-in List type - Cons always has payload
        val code = """
            let head_or_zero = {lst: List Int} ->
                match lst is
                    Cons -> 1
                    Nil -> 0
        """.trimIndent()

        // Should throw - Cons has payload { head, tail }
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cons") == true,
            "Message should contain 'Cons'. Actual: ${exception.message}")
    }

    @Test
    fun `List Cons with proper destructuring should pass`() {
        val code = """
            let head_or_zero = {lst: List Int} ->
                match lst is
                    Cons { head, tail } -> head
                    Nil -> 0
        """.trimIndent()

        // Should pass
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
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

        val result = typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))
        assertNotNull(result, "Valid patterns should type check successfully")
    }

    // ========== UNUSED VARIABLE WARNINGS ==========

    @Test
    fun `unused pattern binding should produce warning`() {
        val code = """
            type Maybe = | Just { val: Int }, Nothing |

            let test = {opt: Maybe} ->
                match opt is
                    Just { val } -> 42
                    Nothing -> 0
        """.trimIndent()

        val (result, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(result)
        // Check warnings contain unused variable
        assertTrue(warnings.any { it.contains("Unused variable") && it.contains("val") },
            "Should warn about unused variable 'val'. Warnings: $warnings")
    }

    @Test
    fun `used pattern binding should not produce warning`() {
        val code = """
            type Maybe = | Just { val: Int }, Nothing |

            let test = {opt: Maybe} ->
                match opt is
                    Just { val } -> val
                    Nothing -> 0
        """.trimIndent()

        val (result, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(result)
        // Should not warn since 'val' is used
        assertTrue(warnings.none { it.contains("Unused variable") && it.contains("val") },
            "Should not warn about 'val' since it's used. Warnings: $warnings")
    }

    @Test
    fun `underscore prefix suppresses unused variable warning`() {
        val code = """
            type Maybe = | Just { val: Int }, Nothing |

            let test = {opt: Maybe} ->
                match opt is
                    Just { val = _unused } -> 42
                    Nothing -> 0
        """.trimIndent()

        val (result, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(result)
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

        val (result, warnings) = parseAndTypeCheckWithWarnings(code)
        assertNotNull(result)
        assertTrue(warnings.any { it.contains("Float literal pattern") || it.contains("floating-point") },
            "Should warn about float literal pattern. Warnings: $warnings")
    }

    // ========== FREE TYPE VARIABLE VALIDATION ==========

    @Test
    fun `free type variable in type definition should fail`() {
        val code = """
            type Color = | Red, Blue { n: 'a }, Green |
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("undeclared type variable") == true,
            "Error should mention undeclared type variable. Actual: ${exception.message}")
        assertTrue(exception.message?.contains("'a") == true,
            "Error should mention 'a. Actual: ${exception.message}")
        assertTrue(exception.message?.contains("type Color 'a =") == true,
            "Error should suggest adding type parameter. Actual: ${exception.message}")
    }

    @Test
    fun `declared type variable in type definition should pass`() {
        val code = """
            type Color 'a = | Red, Blue { n: 'a }, Green |
        """.trimIndent()

        // Should pass - 'a is declared as a type parameter
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `multiple free type variables should fail with all listed`() {
        val code = """
            type Pair = { first: 'a, second: 'b }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("'a") == true,
            "Error should mention 'a. Actual: ${exception.message}")
        assertTrue(exception.message?.contains("'b") == true,
            "Error should mention 'b. Actual: ${exception.message}")
    }

    @Test
    fun `partially declared type variables should fail`() {
        // 'a is declared but 'b is not
        val code = """
            type Container 'a = { value: 'a, metadata: 'b }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("'b") == true,
            "Error should mention undeclared 'b. Actual: ${exception.message}")
        // Should NOT mention 'a since it's declared
        assertTrue(exception.message?.contains("'a") != true ||
                   exception.message?.contains("type Container 'a") == true,
            "Error should not say 'a is undeclared. Actual: ${exception.message}")
    }

    @Test
    fun `newtype with free type variable should fail`() {
        val code = """
            newtype Wrapper = Wrap 'a
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("undeclared type variable") == true,
            "Error should mention undeclared type variable. Actual: ${exception.message}")
    }

    @Test
    fun `newtype with declared type variable should pass`() {
        val code = """
            newtype Wrapper 'a = 'a
        """.trimIndent()

        // Should pass - 'a is declared
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `record with function field containing free type variable should fail`() {
        // No implicit rank-2 polymorphism - all type vars must be declared
        val code = """
            type Continuation = { capture: {} -> 'a }
        """.trimIndent()

        // Should fail - 'a must be declared as type parameter
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("undeclared type variable") == true,
            "Error should mention undeclared type variable. Actual: ${exception.message}")
    }

    @Test
    fun `record with declared type parameter in function field should pass`() {
        // Type parameter is declared, so this is valid
        val code = """
            type Continuation 'a = { capture: {} -> 'a }
        """.trimIndent()

        // Should pass - 'a is declared
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `record with non-function polymorphic field should fail`() {
        val code = """
            type Container = { value: 'a }
        """.trimIndent()

        // Should fail - 'a must be declared as type parameter
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("undeclared type variable") == true,
            "Error should mention undeclared type variable. Actual: ${exception.message}")
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
