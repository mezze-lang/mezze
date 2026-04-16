package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for string interpolation pattern parsing.
 *
 * String interpolation patterns allow matching and extracting parts of strings:
 *   "hello $(name)"     -> matches "hello world" with name = "world"
 *   "$(a)-$(b)-$(c)"    -> matches "x-y-z" with a = "x", b = "y", c = "z"
 *
 * Unlike expression interpolation, pattern interpolation only allows variable names,
 * not full expressions.
 */
class StringInterpolationPatternTest {

    private val parser = MezzeParser()

    // =========================================================================
    // BASIC PATTERN PARSING
    // =========================================================================

    @Nested
    inner class BasicPatternParsing {

        @Test
        fun `simple pattern with single capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "hello $(name)" -> name
                        _ -> "unknown"
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val lambda = letBinding.expression as LambdaExpression
            val matchExpr = lambda.body as MatchExpression

            // First case should be a StringInterpolationPattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is StringInterpolationPattern, "Expected StringInterpolationPattern but got ${pattern::class.simpleName}")

            val interpPattern = pattern as StringInterpolationPattern
            assertEquals(2, interpPattern.parts.size)

            val literal = interpPattern.parts[0] as StringPatternLiteral
            assertEquals("hello ", literal.value)

            val capture = interpPattern.parts[1] as StringPatternCapture
            assertEquals("name", capture.variableName)
        }

        @Test
        fun `pattern with prefix and suffix`() {
            val source = """
                let test = { s } ->
                    match s is
                        "prefix-$(middle)-suffix" -> middle
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val letBinding = program.items[0] as LetBinding
            val lambda = letBinding.expression as LambdaExpression
            val matchExpr = lambda.body as MatchExpression

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(3, pattern.parts.size)

            assertEquals("prefix-", (pattern.parts[0] as StringPatternLiteral).value)
            assertEquals("middle", (pattern.parts[1] as StringPatternCapture).variableName)
            assertEquals("-suffix", (pattern.parts[2] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern with multiple captures`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(month)-$(day)-$(year)" -> { month, day, year }
                        _ -> { month = "", day = "", year = "" }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val letBinding = program.items[0] as LetBinding
            val lambda = letBinding.expression as LambdaExpression
            val matchExpr = lambda.body as MatchExpression

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(5, pattern.parts.size)

            // Check captures and literals alternate correctly
            assertEquals("month", (pattern.parts[0] as StringPatternCapture).variableName)
            assertEquals("-", (pattern.parts[1] as StringPatternLiteral).value)
            assertEquals("day", (pattern.parts[2] as StringPatternCapture).variableName)
            assertEquals("-", (pattern.parts[3] as StringPatternLiteral).value)
            assertEquals("year", (pattern.parts[4] as StringPatternCapture).variableName)
        }

        @Test
        fun `pattern starting with capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(prefix) suffix" -> prefix
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(2, pattern.parts.size)

            assertEquals("prefix", (pattern.parts[0] as StringPatternCapture).variableName)
            assertEquals(" suffix", (pattern.parts[1] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern ending with capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "prefix $(suffix)" -> suffix
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(2, pattern.parts.size)

            assertEquals("prefix ", (pattern.parts[0] as StringPatternLiteral).value)
            assertEquals("suffix", (pattern.parts[1] as StringPatternCapture).variableName)
        }

        @Test
        fun `pattern with only capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(whole)" -> whole
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(1, pattern.parts.size)

            assertEquals("whole", (pattern.parts[0] as StringPatternCapture).variableName)
        }
    }

    // =========================================================================
    // VARIABLE NAME VALIDATION
    // =========================================================================

    @Nested
    inner class VariableNameValidation {

        @Test
        fun `underscore prefix variable name`() {
            val source = """
                let test = { s } ->
                    match s is
                        "prefix-$(_unused)" -> "matched"
                        _ -> "no match"
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("_unused", (pattern.parts[1] as StringPatternCapture).variableName)
        }

        @Test
        fun `variable name with numbers`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(var1)-$(var2)" -> { var1, var2 }
                        _ -> { var1 = "", var2 = "" }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("var1", (pattern.parts[0] as StringPatternCapture).variableName)
            assertEquals("var2", (pattern.parts[2] as StringPatternCapture).variableName)
        }

        @Test
        fun `camelCase variable name`() {
            val source = """
                let test = { s } ->
                    match s is
                        "Hello $(firstName) $(lastName)" -> { firstName, lastName }
                        _ -> { firstName = "", lastName = "" }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("firstName", (pattern.parts[1] as StringPatternCapture).variableName)
            assertEquals("lastName", (pattern.parts[3] as StringPatternCapture).variableName)
        }

        @Test
        fun `snake_case variable name`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(first_name)-$(last_name)" -> { first_name, last_name }
                        _ -> { first_name = "", last_name = "" }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("first_name", (pattern.parts[0] as StringPatternCapture).variableName)
            assertEquals("last_name", (pattern.parts[2] as StringPatternCapture).variableName)
        }
    }

    // =========================================================================
    // ESCAPE SEQUENCES
    // =========================================================================

    @Nested
    inner class EscapeSequences {

        @Test
        fun `escaped dollar sign is literal`() {
            val source = """
                let test = { s } ->
                    match s is
                        "price: ${'$'}100" -> "matched"
                        _ -> "no match"
            """.trimIndent()

            // This should be a simple LiteralPattern, not interpolation
            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            // With escaped $, it should be a literal pattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is LiteralPattern || (pattern is StringInterpolationPattern && pattern.parts.size == 1 && pattern.parts[0] is StringPatternLiteral),
                "Escaped dollar should not create interpolation pattern")
        }

        @Test
        fun `newline escape in pattern`() {
            val source = """
                let test = { s } ->
                    match s is
                        "line1\n$(line2)" -> line2
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("line1\n", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `tab escape in pattern`() {
            val source = """
                let test = { s } ->
                    match s is
                        "col1\t$(col2)" -> col2
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("col1\t", (pattern.parts[0] as StringPatternLiteral).value)
        }
    }

    // =========================================================================
    // ERROR CASES
    // =========================================================================

    @Nested
    inner class ErrorCases {

        @Test
        fun `expression in capture fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $(x + 1)" -> x
                        _ -> 0
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            assertTrue(
                exception.message?.contains("Expected") == true ||
                exception.message?.contains("variable name") == true,
                "Expected error about only variable names allowed, got: ${exception.message}"
            )
        }

        @Test
        fun `function call in capture fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $(toString{x})" -> x
                        _ -> 0
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            assertTrue(
                exception.message?.contains("variable name") == true ||
                exception.message?.contains("Expected") == true,
                "Expected error about only variable names allowed, got: ${exception.message}"
            )
        }

        @Test
        fun `unclosed capture fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $(name" -> name
                        _ -> ""
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            // Should fail to parse
            assertNotNull(exception)
        }

        @Test
        fun `empty capture fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $()" -> "matched"
                        _ -> ""
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            assertTrue(
                exception.message?.contains("variable name") == true,
                "Expected error about missing variable name, got: ${exception.message}"
            )
        }

        @Test
        fun `number starting variable name fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $(123name)" -> "matched"
                        _ -> ""
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            assertTrue(
                exception.message?.contains("variable name") == true,
                "Expected error about invalid variable name, got: ${exception.message}"
            )
        }
    }

    // =========================================================================
    // ADJACENT CAPTURES RESTRICTION
    // =========================================================================

    @Nested
    inner class AdjacentCapturesRestriction {

        @Test
        fun `adjacent captures without separator fails`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(a)$(b)" -> { a, b }
                        _ -> { a = "", b = "" }
            """.trimIndent()

            val exception = assertThrows<Exception> {
                parser.parseParsedModule(source)
            }
            assertTrue(
                exception.message?.contains("Adjacent") == true ||
                exception.message?.contains("separator") == true,
                "Expected error about adjacent captures, got: ${exception.message}"
            )
        }

        @Test
        fun `captures with separator works`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(a)-$(b)" -> { a, b }
                        _ -> { a = "", b = "" }
            """.trimIndent()

            // Should parse successfully
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val matchExpr = getMatchExpression(program)
            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(3, pattern.parts.size)
        }

        @Test
        fun `captures with single char separator works`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(a) $(b)" -> { a, b }
                        _ -> { a = "", b = "" }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(3, pattern.parts.size)
            assertEquals(" ", (pattern.parts[1] as StringPatternLiteral).value)
        }
    }

    // =========================================================================
    // PLAIN STRING PATTERNS (NO INTERPOLATION)
    // =========================================================================

    @Nested
    inner class PlainStringPatterns {

        @Test
        fun `plain string pattern without interpolation`() {
            val source = """
                let test = { s } ->
                    match s is
                        "hello world" -> true
                        _ -> false
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            // Should be a LiteralPattern, not StringInterpolationPattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is LiteralPattern, "Expected LiteralPattern for plain string, got ${pattern::class.simpleName}")

            val literalPattern = pattern as LiteralPattern
            assertTrue(literalPattern.value is StringLiteralValue)
            assertEquals("hello world", (literalPattern.value as StringLiteralValue).value)
        }

        @Test
        fun `empty string pattern`() {
            val source = """
                let test = { s } ->
                    match s is
                        "" -> true
                        _ -> false
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as LiteralPattern
            assertTrue(pattern.value is StringLiteralValue)
            assertEquals("", (pattern.value as StringLiteralValue).value)
        }
    }

    // =========================================================================
    // WHITESPACE HANDLING
    // =========================================================================

    @Nested
    inner class WhitespaceHandling {

        @Test
        fun `whitespace around variable name in capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "value: $( name )" -> name
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            // Variable name should be trimmed
            assertEquals("name", (pattern.parts[1] as StringPatternCapture).variableName)
        }

        @Test
        fun `whitespace in literal part preserved`() {
            val source = """
                let test = { s } ->
                    match s is
                        "  hello  $(name)  world  " -> name
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("  hello  ", (pattern.parts[0] as StringPatternLiteral).value)
            assertEquals("  world  ", (pattern.parts[2] as StringPatternLiteral).value)
        }
    }

    // =========================================================================
    // INTEGRATION WITH MATCH EXPRESSIONS
    // =========================================================================

    @Nested
    inner class MatchExpressionIntegration {

        @Test
        fun `string pattern with guard`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(x)" if x == "hello" -> true
                        _ -> false
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val firstCase = matchExpr.cases[0]
            assertTrue(firstCase.patterns[0] is StringInterpolationPattern)
            assertNotNull(firstCase.guard, "Guard should be present")
        }

        @Test
        fun `multiple match cases with string patterns`() {
            // Multiple pattern alternatives are separate cases in Mezze
            val source = """
                let test = { s } ->
                    match s is
                        "hello $(name)" -> name
                        "hi $(name)" -> name
                        _ -> "stranger"
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            assertEquals(3, matchExpr.cases.size)
            assertTrue(matchExpr.cases[0].patterns[0] is StringInterpolationPattern)
            assertTrue(matchExpr.cases[1].patterns[0] is StringInterpolationPattern)
        }

        @Test
        fun `mixed pattern types in match`() {
            val source = """
                let test = { s } ->
                    match s is
                        "hello $(name)" -> Some { val = name }
                        "" -> None
                        x -> Some { val = x }
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            assertTrue(matchExpr.cases[0].patterns[0] is StringInterpolationPattern)
            assertTrue(matchExpr.cases[1].patterns[0] is LiteralPattern)
            assertTrue(matchExpr.cases[2].patterns[0] is VariablePattern)
        }
    }

    // =========================================================================
    // UNICODE SUPPORT
    // =========================================================================

    @Nested
    inner class UnicodeSupport {

        @Test
        fun `unicode in literal part`() {
            val source = """
                let test = { s } ->
                    match s is
                        "Hello $(name) 🎉" -> name
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals(" 🎉", (pattern.parts[2] as StringPatternLiteral).value)
        }

        @Test
        fun `cyrillic in literal part`() {
            val source = """
                let test = { s } ->
                    match s is
                        "Привет $(name)!" -> name
                        _ -> ""
            """.trimIndent()

            val program = parser.parseParsedModule(source)
            val matchExpr = getMatchExpression(program)

            val pattern = matchExpr.cases[0].patterns[0] as StringInterpolationPattern
            assertEquals("Привет ", (pattern.parts[0] as StringPatternLiteral).value)
        }
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun getMatchExpression(program: Program): MatchExpression {
        val letBinding = program.items[0] as LetBinding
        val lambda = letBinding.expression as LambdaExpression
        return lambda.body as MatchExpression
    }
}
