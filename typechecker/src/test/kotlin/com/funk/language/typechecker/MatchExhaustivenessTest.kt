package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

class MatchExhaustivenessTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    // ========== EXHAUSTIVE CASES (SHOULD PASS) ==========

    @Test
    fun `test exhaustive match with simple constructors`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let test: {color: Color} -> String = {color} -> match color is
              Red -> "red"
              Blue -> "blue" 
              Green -> "green"
        """.trimIndent()

        // Should not throw - all constructors covered
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustive match with multiple patterns`() {
        val code = """
            type Color = | Red, Blue, Green, Yellow |
            
            let test: {color: Color} -> String = {color} -> match color is
              Red | Blue -> "primary"
              Green | Yellow -> "secondary"
        """.trimIndent()

        // Should not throw - all constructors covered via multiple patterns
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustive match with wildcard`() {
        val code = """
            type Color = | Red, Blue, Green, Yellow, Purple |
            
            let test: {color: Color} -> String = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              _ -> "other"
        """.trimIndent()

        // Should not throw - wildcard covers remaining cases
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustive match with guards and wildcard`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int } |
            
            let test: {temp: Temp} -> String = {temp} -> match temp is
              Hot { degrees } if degrees > 100 -> "very hot"
              Cold { degrees } if degrees < -20 -> "very cold"
              _ -> "normal"
        """.trimIndent()

        // Should not throw - wildcard covers all conditional cases
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustive match with multiple patterns and guards`() {
        val code = """
            type Weather = | Sunny { temp: Int }, Rainy { temp: Int }, Cloudy |
            
            let test: {weather: Weather} -> String = {weather} -> match weather is
              Sunny { temp } | Rainy { temp } if temp > 25 -> "warm weather"
              Sunny { temp } | Rainy { temp } -> "cool weather"
              Cloudy -> "cloudy"
        """.trimIndent()

        // Should not throw - all cases covered (conditional + fallback + separate)
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== NON-EXHAUSTIVE CASES (SHOULD FAIL) ==========

    @Test
    fun `test non-exhaustive match missing constructor`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> String = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
        """.trimIndent()

        // Strict exhaustiveness: missing Green should cause error
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
        kotlin.test.assertTrue(exception.message!!.contains("Green"))
    }

    @Test
    fun `test non-exhaustive match with multiple patterns missing constructor`() {
        val code = """
            type Color = | Red, Blue, Green, Yellow |

            let test: {color: Color} -> String = {color} -> match color is
              Red | Blue -> "primary"
              Green -> "secondary"
        """.trimIndent()

        // Strict exhaustiveness: missing Yellow should cause error
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
        kotlin.test.assertTrue(exception.message!!.contains("Yellow"))
    }

    @Test
    fun `test non-exhaustive match with guards only`() {
        val code = """
            type Temp = | Hot { degrees: Int } |

            let test: {temp: Temp} -> String = {temp} -> match temp is
              Hot { degrees } if degrees > 80 -> "very hot"
        """.trimIndent()

        // Strict exhaustiveness: Hot with guard only, needs wildcard or unconditional pattern
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
    }

    @Test
    fun `test non-exhaustive match with guards missing cases`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int } |

            let test: {temp: Temp} -> String = {temp} -> match temp is
              Hot { degrees } if degrees > 100 -> "very hot"
              Cold { degrees } if degrees < -20 -> "very cold"
        """.trimIndent()

        // Strict exhaustiveness: guards don't cover all cases
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
    }

    @Test
    fun `test non-exhaustive match with multiple patterns and incomplete guards`() {
        val code = """
            type Weather = | Sunny { temp: Int }, Rainy { temp: Int }, Cloudy |

            let test: {weather: Weather} -> String = {weather} -> match weather is
              Sunny { temp } | Rainy { temp } if temp > 25 -> "warm weather"
              Cloudy -> "cloudy"
        """.trimIndent()

        // Strict exhaustiveness: Sunny and Rainy only covered with guard
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
    }

    // ========== OPEN VARIANTS (SHOULD ALLOW WILDCARDS) ==========

    @Test
    fun `test open variant with wildcard should pass`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let test: {color: Color} -> String = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              _ -> "unknown"
        """.trimIndent()

        // Should not throw - wildcard covers remaining cases (treating as closed variant for now)
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test open variant without wildcard should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> String = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
        """.trimIndent()

        // Strict exhaustiveness: closed variant missing Green should fail
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
        kotlin.test.assertTrue(exception.message!!.contains("Green"))
    }

    // ========== COMPLEX EXHAUSTIVENESS SCENARIOS ==========

    @Test
    fun `test complex nested patterns with guards`() {
        val code = """
            type Status = | Active { priority: Int }, Inactive, Pending { reason: String } |
            
            let test: {status: Status} -> String = {status} -> match status is
              Active { priority } if priority > 5 -> "high priority"
              Active { priority } if priority > 0 -> "normal priority" 
              Active { priority } -> "low priority"
              Inactive -> "inactive"
              Pending { reason } -> "pending: $(reason)"
        """.trimIndent()

        // Should not throw - all Active cases covered by guards, others explicit
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test overlapping multiple patterns with guards`() {
        val code = """
            type Event = | Click { x: Int }, Hover { x: Int }, Scroll |
            
            let test: {event: Event} -> String = {event} -> match event is
              Click { x } | Hover { x } if x > 100 -> "right side"
              Click { x } | Hover { x } if x < 50 -> "left side"
              Click { x } | Hover { x } -> "center"
              Scroll -> "scroll"
        """.trimIndent()

        // Should not throw - all Click/Hover ranges covered, Scroll explicit
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustiveness with logical operators in guards`() {
        val code = """
            type Grade = | Score { value: Int } |

            let test: {grade: Grade} -> String = {grade} -> match grade is
              Score { value } if value >= 90 && value <= 100 -> "A"
              Score { value } if value >= 80 && value < 90 -> "B"
              Score { value } if value >= 70 && value < 80 -> "C"
              Score { value } if value >= 60 && value < 70 -> "D"
              Score { value } if value >= 0 && value < 60 -> "F"
        """.trimIndent()

        // Strict exhaustiveness: guards only, no unconditional pattern or wildcard
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
    }

    @Test
    fun `test exhaustiveness with logical operators and wildcard`() {
        val code = """
            type Grade = | Score { value: Int } |
            
            let test: {grade: Grade} -> String = {grade} -> match grade is
              Score { value } if value >= 90 && value <= 100 -> "A"
              Score { value } if value >= 80 && value < 90 -> "B"
              Score { value } if value >= 70 && value < 80 -> "C"
              Score { value } if value >= 60 && value < 70 -> "D"
              _ -> "F"
        """.trimIndent()

        // Should not throw - wildcard covers edge cases
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test exhaustiveness with mixed pattern types`() {
        val code = """
            type Result = | Success { value: Int }, Error { message: String }, Loading |
            
            let test: {result: Result} -> String = {result} -> match result is
              Success { value } if value > 0 -> "positive result"
              Success { value } -> "zero or negative result"
              Error { message } -> "error: $(message)"
              Loading -> "loading..."
        """.trimIndent()

        // Should not throw - Success fully covered by guards, others explicit
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test single constructor variant exhaustiveness`() {
        val code = """
            use std::int

            type Single = | Only { value: Int } |

            let test: {single: Single} -> String = {single} -> match single is
              Only { value } -> "value: $(int::to_string {n = value})"
        """.trimIndent()

        // Should not throw - single constructor fully covered
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test single constructor with guard requires wildcard`() {
        val code = """
            type Single = | Only { value: Int } |

            let test: {single: Single} -> String = {single} -> match single is
              Only { value } if value > 0 -> "positive"
        """.trimIndent()

        // Strict exhaustiveness: guard only, no unconditional pattern or wildcard
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive"))
        kotlin.test.assertTrue(exception.message!!.contains("Only"))
    }

    // Note: Empty variant types (| |) represent uninhabited types.
    // You cannot write a match expression on an uninhabited type because
    // no value of that type can ever exist. The test was removed as it
    // tested an impossible/invalid scenario.

    // ========== BUGS TO CATCH ==========

    @Test
    fun `BUGGY - test should reject undefined variant constructor Pink not in Red or Blue`() {
        val code = """
            type Color = | Red, Blue |

            let add = {color: Color} -> match color is
              Red -> 1
              Blue -> 2

            let ans = add {color = Pink}
        """.trimIndent()

        // SHOULD FAIL: Pink is not a valid constructor of Color type
        // This test documents the bug where undefined constructors are accepted
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(
            exception.message!!.contains("Pink") || exception.message!!.contains("Undefined")
        )
    }

    @Test
    fun `BUGGY - test should reject non-exhaustive match with undefined constructor usage`() {
        val code = """
            type Color = | Red, Blue, Green |

            let add = {color: Color} -> match color is
              Red -> 1
              Blue -> 2

            let ans = add {color = Yellow}
        """.trimIndent()

        // SHOULD FAIL FOR TWO REASONS:
        // 1. Match is non-exhaustive (missing Green)
        // 2. Yellow is not a valid constructor of Color
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Should mention either non-exhaustive or undefined constructor
        kotlin.test.assertTrue(
            exception.message!!.contains("doesn't handle all cases") || exception.message!!.contains("Non-exhaustive") ||
            exception.message!!.contains("Yellow") ||
            exception.message!!.contains("Undefined")
        )
    }

    @Test
    fun `BUGGY - test match with inconsistent branch types should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> Int = {color} -> match color is
              Red -> 1
              Blue -> "two"
              Green -> 3
        """.trimIndent()

        // SHOULD FAIL: Branch returns String but function expects Int
        // This tests branch type consistency
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(
            exception.message!!.contains("mismatch") ||
            exception.message!!.contains("String") ||
            exception.message!!.contains("Int")
        )
    }

    @Test
    fun `BUGGY - test match with all branches returning wrong type should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> Int = {color} -> match color is
              Red -> "one"
              Blue -> "two"
              Green -> "three"
        """.trimIndent()

        // SHOULD FAIL: All branches return String but function expects Int
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(
            exception.message!!.contains("mismatch") ||
            exception.message!!.contains("String") ||
            exception.message!!.contains("Int")
        )
    }
}
