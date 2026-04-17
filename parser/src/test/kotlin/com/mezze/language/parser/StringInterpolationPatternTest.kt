package com.mezze.language.parser

import com.mezze.language.parsedast.*
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
class ParsedStringInterpPatternTest {

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

            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val lambda = letBinding.value as ParsedLambda
            val matchExpr = lambda.body as ParsedMatch

            // First case should be a ParsedStringInterpPattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is ParsedStringInterpPattern, "Expected ParsedStringInterpPattern but got ${pattern::class.simpleName}")

            val interpPattern = pattern as ParsedStringInterpPattern
            assertEquals(2, interpPattern.parts.size)

            val literal = interpPattern.parts[0] as ParsedStringPatternPart.Literal
            assertEquals("hello ", literal.value)

            val capture = interpPattern.parts[1] as ParsedStringPatternPart.Capture
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

            val module = parser.parseParsedModule(source)
            val letBinding = module.decls[0] as ParsedLetDecl
            val lambda = letBinding.value as ParsedLambda
            val matchExpr = lambda.body as ParsedMatch

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(3, pattern.parts.size)

            assertEquals("prefix-", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
            assertEquals("middle", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("-suffix", (pattern.parts[2] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern with multiple captures`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(month)-$(day)-$(year)" -> { month, day, year }
                        _ -> { month = "", day = "", year = "" }
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val letBinding = module.decls[0] as ParsedLetDecl
            val lambda = letBinding.value as ParsedLambda
            val matchExpr = lambda.body as ParsedMatch

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(5, pattern.parts.size)

            // Check captures and literals alternate correctly
            assertEquals("month", (pattern.parts[0] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("-", (pattern.parts[1] as ParsedStringPatternPart.Literal).value)
            assertEquals("day", (pattern.parts[2] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("-", (pattern.parts[3] as ParsedStringPatternPart.Literal).value)
            assertEquals("year", (pattern.parts[4] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `pattern starting with capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(prefix) suffix" -> prefix
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(2, pattern.parts.size)

            assertEquals("prefix", (pattern.parts[0] as ParsedStringPatternPart.Capture).variableName)
            assertEquals(" suffix", (pattern.parts[1] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern ending with capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "prefix $(suffix)" -> suffix
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(2, pattern.parts.size)

            assertEquals("prefix ", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
            assertEquals("suffix", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `pattern with only capture`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(whole)" -> whole
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(1, pattern.parts.size)

            assertEquals("whole", (pattern.parts[0] as ParsedStringPatternPart.Capture).variableName)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("_unused", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `variable name with numbers`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(var1)-$(var2)" -> { var1, var2 }
                        _ -> { var1 = "", var2 = "" }
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("var1", (pattern.parts[0] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("var2", (pattern.parts[2] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `camelCase variable name`() {
            val source = """
                let test = { s } ->
                    match s is
                        "Hello $(firstName) $(lastName)" -> { firstName, lastName }
                        _ -> { firstName = "", lastName = "" }
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("firstName", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("lastName", (pattern.parts[3] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `snake_case variable name`() {
            val source = """
                let test = { s } ->
                    match s is
                        "$(first_name)-$(last_name)" -> { first_name, last_name }
                        _ -> { first_name = "", last_name = "" }
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("first_name", (pattern.parts[0] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("last_name", (pattern.parts[2] as ParsedStringPatternPart.Capture).variableName)
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

            // This should be a simple ParsedLiteralPattern, not interpolation
            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            // With escaped $, it should be a literal pattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is ParsedLiteralPattern || (pattern is ParsedStringInterpPattern && pattern.parts.size == 1 && pattern.parts[0] is ParsedStringPatternPart.Literal),
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("line1\n", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `tab escape in pattern`() {
            val source = """
                let test = { s } ->
                    match s is
                        "col1\t$(col2)" -> col2
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("col1\t", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
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
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val matchExpr = getParsedMatch(module)
            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(3, pattern.parts.size)
            assertEquals(" ", (pattern.parts[1] as ParsedStringPatternPart.Literal).value)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            // Should be a ParsedLiteralPattern, not ParsedStringInterpPattern
            val pattern = matchExpr.cases[0].patterns[0]
            assertTrue(pattern is ParsedLiteralPattern, "Expected ParsedLiteralPattern for plain string, got ${pattern::class.simpleName}")

            val literalPattern = pattern as ParsedLiteralPattern
            assertTrue(literalPattern.value is ParsedLiteralValue.StringValue)
            assertEquals("hello world", (literalPattern.value as ParsedLiteralValue.StringValue).value)
        }

        @Test
        fun `empty string pattern`() {
            val source = """
                let test = { s } ->
                    match s is
                        "" -> true
                        _ -> false
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedLiteralPattern
            assertTrue(pattern.value is ParsedLiteralValue.StringValue)
            assertEquals("", (pattern.value as ParsedLiteralValue.StringValue).value)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            // Variable name should be trimmed
            assertEquals("name", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `whitespace in literal part preserved`() {
            val source = """
                let test = { s } ->
                    match s is
                        "  hello  $(name)  world  " -> name
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("  hello  ", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
            assertEquals("  world  ", (pattern.parts[2] as ParsedStringPatternPart.Literal).value)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val firstCase = matchExpr.cases[0]
            assertTrue(firstCase.patterns[0] is ParsedStringInterpPattern)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            assertEquals(3, matchExpr.cases.size)
            assertTrue(matchExpr.cases[0].patterns[0] is ParsedStringInterpPattern)
            assertTrue(matchExpr.cases[1].patterns[0] is ParsedStringInterpPattern)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            assertTrue(matchExpr.cases[0].patterns[0] is ParsedStringInterpPattern)
            assertTrue(matchExpr.cases[1].patterns[0] is ParsedLiteralPattern)
            assertTrue(matchExpr.cases[2].patterns[0] is ParsedVariablePattern)
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

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals(" 🎉", (pattern.parts[2] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `cyrillic in literal part`() {
            val source = """
                let test = { s } ->
                    match s is
                        "Привет $(name)!" -> name
                        _ -> ""
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val matchExpr = getParsedMatch(module)

            val pattern = matchExpr.cases[0].patterns[0] as ParsedStringInterpPattern
            assertEquals("Привет ", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun getParsedMatch(module: ParsedModule): ParsedMatch {
        val letBinding = module.decls[0] as ParsedLetDecl
        val lambda = letBinding.value as ParsedLambda
        return lambda.body as ParsedMatch
    }
}
