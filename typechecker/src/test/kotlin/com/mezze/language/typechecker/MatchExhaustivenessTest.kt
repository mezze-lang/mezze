package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

class MatchExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ========== EXHAUSTIVE CASES (SHOULD PASS) ==========

    @Test
    fun `test exhaustive match with simple constructors`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let test: {color: Color} -> Str = {color} -> match color is
              Red -> "red"
              Blue -> "blue" 
              Green -> "green"
        """.trimIndent()

        // Should not throw - all constructors covered
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustive match with multiple patterns`() {
        val code = """
            type Color = | Red, Blue, Green, Yellow |
            
            let test: {color: Color} -> Str = {color} -> match color is
              Red | Blue -> "primary"
              Green | Yellow -> "secondary"
        """.trimIndent()

        // Should not throw - all constructors covered via multiple patterns
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustive match with wildcard`() {
        val code = """
            type Color = | Red, Blue, Green, Yellow, Purple |
            
            let test: {color: Color} -> Str = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              _ -> "other"
        """.trimIndent()

        // Should not throw - wildcard covers remaining cases
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustive match with guards and wildcard`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int } |
            
            let test: {temp: Temp} -> Str = {temp} -> match temp is
              Hot { degrees } if degrees > 100 -> "very hot"
              Cold { degrees } if degrees < -20 -> "very cold"
              _ -> "normal"
        """.trimIndent()

        // Should not throw - wildcard covers all conditional cases
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustive match with multiple patterns and guards`() {
        val code = """
            type Weather = | Sunny { temp: Int }, Rainy { temp: Int }, Cloudy |
            
            let test: {weather: Weather} -> Str = {weather} -> match weather is
              Sunny { temp } | Rainy { temp } if temp > 25 -> "warm weather"
              Sunny { temp } | Rainy { temp } -> "cool weather"
              Cloudy -> "cloudy"
        """.trimIndent()

        // Should not throw - all cases covered (conditional + fallback + separate)
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== NON-EXHAUSTIVE CASES (SHOULD FAIL) ==========

    @Test
    fun `test non-exhaustive match missing constructor`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> Str = {color} -> match color is
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

            let test: {color: Color} -> Str = {color} -> match color is
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

            let test: {temp: Temp} -> Str = {temp} -> match temp is
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

            let test: {temp: Temp} -> Str = {temp} -> match temp is
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

            let test: {weather: Weather} -> Str = {weather} -> match weather is
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
            
            let test: {color: Color} -> Str = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              _ -> "unknown"
        """.trimIndent()

        // Should not throw - wildcard covers remaining cases (treating as closed variant for now)
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test open variant without wildcard should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test: {color: Color} -> Str = {color} -> match color is
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
            type Status = | Active { priority: Int }, Inactive, Pending { reason: Str } |
            
            let test: {status: Status} -> Str = {status} -> match status is
              Active { priority } if priority > 5 -> "high priority"
              Active { priority } if priority > 0 -> "normal priority" 
              Active { priority } -> "low priority"
              Inactive -> "inactive"
              Pending { reason } -> "pending: $(reason)"
        """.trimIndent()

        // Should not throw - all Active cases covered by guards, others explicit
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test overlapping multiple patterns with guards`() {
        val code = """
            type Event = | Click { x: Int }, Hover { x: Int }, Scroll |
            
            let test: {event: Event} -> Str = {event} -> match event is
              Click { x } | Hover { x } if x > 100 -> "right side"
              Click { x } | Hover { x } if x < 50 -> "left side"
              Click { x } | Hover { x } -> "center"
              Scroll -> "scroll"
        """.trimIndent()

        // Should not throw - all Click/Hover ranges covered, Scroll explicit
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustiveness with logical operators in guards`() {
        val code = """
            type Grade = | Score { value: Int } |

            let test: {grade: Grade} -> Str = {grade} -> match grade is
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
            
            let test: {grade: Grade} -> Str = {grade} -> match grade is
              Score { value } if value >= 90 && value <= 100 -> "A"
              Score { value } if value >= 80 && value < 90 -> "B"
              Score { value } if value >= 70 && value < 80 -> "C"
              Score { value } if value >= 60 && value < 70 -> "D"
              _ -> "F"
        """.trimIndent()

        // Should not throw - wildcard covers edge cases
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustiveness with mixed pattern types`() {
        val code = """
            type Outcome = | Success { value: Int }, Error { message: Str }, Loading |
            
            let test: {result: Outcome} -> Str = {result} -> match result is
              Success { value } if value > 0 -> "positive result"
              Success { value } -> "zero or negative result"
              Error { message } -> "error: $(message)"
              Loading -> "loading..."
        """.trimIndent()

        // Should not throw - Success fully covered by guards, others explicit
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test single constructor variant exhaustiveness`() {
        val code = """
            use std::int

            type Single = | Only { value: Int } |

            let test: {single: Single} -> Str = {single} -> match single is
              Only { value } -> "value: $(int::to_str {n = value})"
        """.trimIndent()

        // Should not throw - single constructor fully covered
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test single constructor with guard requires wildcard`() {
        val code = """
            type Single = | Only { value: Int } |

            let test: {single: Single} -> Str = {single} -> match single is
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

    // ========== FIXED BUGS ==========

    @Test
    fun `closed variant rejects undefined constructor Pink not in Red or Blue`() {
        // Pink is not in Color = | Red, Blue |, so this should fail
        val code = """
            type Color = | Red, Blue |

            let add = {color: Color} -> match color is
              Red -> 1
              Blue -> 2

            let ans = add {color = Pink}
        """.trimIndent()

        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    @Test
    fun `closed variant rejects undefined constructor Yellow not in Color`() {
        // Yellow is not in Color = | Red, Blue, Green |, so this should fail
        val code = """
            type Color = | Red, Blue, Green |

            let add = {color: Color} -> match color is
              Red -> 1
              Blue -> 2
              Green -> 3

            let ans = add {color = Yellow}
        """.trimIndent()

        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
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
            exception.message!!.contains("Str") ||
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
            exception.message!!.contains("Str") ||
            exception.message!!.contains("Int")
        )
    }
}
