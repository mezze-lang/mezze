package com.mezze.language.parser

import com.mezze.language.parser.internal.TokenType
import com.mezze.language.ast.SourceLocation

/**
 * Structured error types for programmatic error handling.
 *
 * These types allow tools and IDEs to handle specific error scenarios
 * differently, provide better error messages, and implement auto-fixes.
 */
sealed class ParseErrorType {

    /**
     * Expected a specific token but got something else.
     *
     * Example: Expected ')' but got 'EOF'
     *
     * @param expected List of acceptable tokens at this position
     * @param actual The token that was actually found
     * @param context Description of the parsing context (e.g., "in function call")
     */
    data class UnexpectedToken(
        val expected: List<String>,
        val actual: TokenType,
        val context: String
    ) : ParseErrorType()

    /**
     * Missing required delimiter or token.
     *
     * Example: Missing closing brace in record literal
     *
     * @param delimiter The missing delimiter (e.g., "}", ")", "]")
     * @param opened Location where the delimiter was opened
     * @param context Description of what was being parsed (e.g., "record literal")
     */
    data class MissingDelimiter(
        val delimiter: String,
        val opened: SourceLocation,
        val context: String
    ) : ParseErrorType()

    /**
     * Invalid literal value.
     *
     * Example: "123abc" is not a valid integer
     *
     * @param literalType Type of literal (e.g., "integer", "float", "string")
     * @param value The invalid value that was found
     * @param reason Explanation of why it's invalid
     */
    data class InvalidLiteral(
        val literalType: String,
        val value: String,
        val reason: String
    ) : ParseErrorType()

    /**
     * Syntax rule violation.
     *
     * Example: Type variable must start with '
     *
     * @param rule Identifier for the syntax rule (e.g., "type_variable_syntax")
     * @param description Human-readable description of the violation
     * @param suggestion Optional suggestion for fixing the error
     */
    data class SyntaxError(
        val rule: String,
        val description: String,
        val suggestion: String? = null
    ) : ParseErrorType()

    /**
     * Invalid pattern in match expression.
     *
     * @param patternType Type of pattern (e.g., "constructor", "literal", "variable")
     * @param issue Description of what's wrong with the pattern
     * @param value The invalid pattern value
     */
    data class InvalidPattern(
        val patternType: String,
        val issue: String,
        val value: String
    ) : ParseErrorType()

    /**
     * Ambiguous syntax that could be parsed multiple ways.
     *
     * Example: `{x}` could be a lambda or record literal
     *
     * @param alternatives List of possible interpretations
     * @param hint Suggestion for disambiguating
     */
    data class AmbiguousSyntax(
        val alternatives: List<String>,
        val hint: String
    ) : ParseErrorType()

    /**
     * Effect or ability syntax errors.
     *
     * @param errorType Type of effect error (e.g., "missing_having_type")
     * @param effectName Name of the effect/ability
     * @param suggestion How to fix the error
     */
    data class EffectSyntaxError(
        val errorType: String,
        val effectName: String,
        val suggestion: String
    ) : ParseErrorType()

    /**
     * Generic syntax error (fallback for uncategorized errors).
     *
     * @param description Description of the error
     */
    data class GenericError(
        val description: String
    ) : ParseErrorType()

    /**
     * String interpolation errors.
     *
     * @param issue Description of the interpolation problem
     * @param content The problematic interpolation content
     */
    data class InterpolationError(
        val issue: String,
        val content: String
    ) : ParseErrorType()
}
