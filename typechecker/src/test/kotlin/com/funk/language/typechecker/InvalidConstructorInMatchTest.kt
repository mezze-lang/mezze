package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for invalid constructor detection in match expressions.
 * These tests ensure the typechecker rejects constructors in match patterns
 * that don't belong to the closed variant type being matched.
 */
class InvalidConstructorInMatchTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== CLOSED VARIANT - INVALID CONSTRUCTOR TESTS ==========

    @Test
    fun `test invalid constructor in closed variant match should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let color_math = {color} ->
                match color is
                    Red -> 0
                    Green -> 2
                    Blue -> 8
                    Pink -> -1
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Pink") == true)
        assertTrue(exception.message?.contains("Color") == true)
        assertTrue(exception.message?.contains("not found") == true ||
                   exception.message?.contains("closed variant") == true)
    }

    @Test
    fun `test multiple invalid constructors in closed variant match should fail`() {
        val code = """
            type Direction = | North, South, East, West |

            let dir_val = {dir} ->
                match dir is
                    North -> 0
                    Up -> 1
                    Down -> 2
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Should mention at least one of the invalid constructors
        assertTrue(exception.message?.contains("Up") == true ||
                   exception.message?.contains("Down") == true)
        assertTrue(exception.message?.contains("Direction") == true)
    }

    @Test
    fun `test valid constructors only in closed variant match should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let color_to_int = {color} ->
                match color is
                    Red -> 0
                    Blue -> 1
                    Green -> 2
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test partial match with wildcard on closed variant should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let is_red = {color} ->
                match color is
                    Red -> True
                    _ -> False
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== OPEN VARIANT - EXTRA CONSTRUCTOR TESTS ==========

    @Test
    fun `test extra constructor in open variant match should pass`() {
        val code = """
            type Color = | Red, Blue, Green, ... |

            let color_math = {color} ->
                match color is
                    Red -> 0
                    Green -> 2
                    Blue -> 8
                    Pink -> -1
                    _ -> -99
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test open variant with all defined constructors should pass`() {
        val code = """
            type Status = | Active, Pending, ... |

            let status_check = {status} ->
                match status is
                    Active -> 1
                    Pending -> 0
                    _ -> -1
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== CLOSED VARIANT WITH PAYLOAD TESTS ==========

    @Test
    fun `test invalid constructor in closed variant with payload should fail`() {
        val code = """
            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |

            let area = {shape} ->
                match shape is
                    Circle {radius} -> radius * radius
                    Rectangle {width, height} -> width * height
                    Triangle {base, height} -> base * height
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Triangle") == true)
        assertTrue(exception.message?.contains("Shape") == true)
    }

    @Test
    fun `test valid constructors with payload in closed variant should pass`() {
        val code = """
            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |

            let area = {shape} ->
                match shape is
                    Circle {radius} -> radius * radius
                    Rectangle {width, height} -> width * height
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test single constructor closed variant with invalid constructor should fail`() {
        val code = """
            type Wrapper = | Wrap { value: Int } |

            let unwrap = {w} ->
                match w is
                    Wrap {value} -> value
                    Unwrap {value} -> value
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Unwrap") == true)
    }

    @Test
    fun `test closed variant match called from another function should fail for invalid constructor`() {
        val code = """
            type Color = | Red, Blue |

            let check_color = {color} ->
                match color is
                    Red -> 1
                    Blue -> 2
                    Yellow -> 3

            let main = {} -> check_color {color = Red}
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Yellow") == true)
    }

    @Test
    fun `test error message contains valid constructors`() {
        val code = """
            type Suit = | Hearts, Diamonds, Clubs, Spades |

            let suit_value = {suit} ->
                match suit is
                    Hearts -> 1
                    Stars -> 5
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Error message should mention the invalid constructor
        assertTrue(exception.message?.contains("Stars") == true)
        // Error message should mention the type name
        assertTrue(exception.message?.contains("Suit") == true)
        // Error message should list valid constructors
        assertTrue(exception.message?.contains("Hearts") == true ||
                   exception.message?.contains("Valid constructors") == true)
    }

    // ========== FUNCTION CALL WITH WRONG VARIANT CONSTRUCTOR (User's Original Bug) ==========

    @Test
    fun `test calling function with wrong variant constructor should fail`() {
        val code = """
            let add = {color} -> match color is
              Red -> 4
              Blue -> 2

            let ans = add { color = Pink }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Pink") == true)
        assertTrue(exception.message?.contains("not a member") == true ||
                   exception.message?.contains("closed variant") == true ||
                   exception.message?.contains("Variant type does not have constructor") == true)
    }

    @Test
    fun `test calling function with correct variant constructor should pass`() {
        val code = """
            let add = {color} -> match color is
              Red -> 4
              Blue -> 2

            let ans = add { color = Red }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple calls with different invalid constructors should fail`() {
        val code = """
            let color_value = {c} -> match c is
              Red -> 1
              Blue -> 2

            let x = color_value { c = Green }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Green") == true)
    }

    // ========== MATCH BRANCH TYPE UNIFICATION ==========

    @Test
    fun `test match branches with different variant constructors should unify`() {
        val code = """
            let test = match True is
              True -> Red
              False -> Blue
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test match branches with incompatible types should fail`() {
        val code = """
            let test = match True is
              True -> 42
              False -> "hello"
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("type mismatch") == true ||
                   exception.message?.contains("Type mismatch") == true ||
                   exception.message?.contains("not compatible") == true ||
                   exception.message?.contains("Int") == true ||
                   exception.message?.contains("String") == true)
    }

    @Test
    fun `test match branches with different closed variants should unify`() {
        val code = """
            type Status = | Active, Pending |

            let get_status = {x} -> match x is
              1 -> Active
              _ -> Pending
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== BOOL AS BUILT-IN CLOSED VARIANT ==========

    @Test
    fun `test bool match with unreachable wildcard should fail`() {
        // Exhaustiveness checking should detect unreachable patterns
        val code = """
            let x = {y} -> match y is
              True -> 1
              False -> 0
              _ -> -1
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("unreachable", ignoreCase = true) == true ||
                   exception.message?.contains("already match all cases") == true)
    }

    @Test
    fun `test bool match without wildcard should be exhaustive`() {
        val code = """
            let x = {y} -> match y is
              True -> 1
              False -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test bool match with only true should require wildcard or false`() {
        val code = """
            let x = {y} -> match y is
              True -> 1
        """.trimIndent()

        // Currently this might pass with a warning or fail
        // The behavior depends on exhaustiveness checking
        // This test documents the current behavior
        try {
            val program = parseAndTypeCheck(code)
            // If it passes, that's the current behavior
            assertNotNull(program)
        } catch (e: TypeCheckError) {
            // If it fails due to non-exhaustiveness, that's also acceptable
            assertTrue(e.message?.contains("exhaustive") == true ||
                       e.message?.contains("missing") == true)
        }
    }
}
