package com.mezze.language.formatter

import com.mezze.language.ast.Program
import com.mezze.language.parser.MezzeParser

/**
 * Mezze code formatter.
 *
 * Produces consistent, idiomatic Mezze code style. Non-configurable by design
 * (like gofmt) to ensure all Mezze code looks the same.
 *
 * Usage:
 * ```
 * val formatter = Formatter()
 * val formatted = formatter.format(sourceCode)
 * ```
 */
class Formatter {
    private val parser = MezzeParser()
    private val prettyPrinter = PrettyPrinter()

    /**
     * Format Mezze source code.
     *
     * @param source The source code to format
     * @param file Optional file path for error messages
     * @return The formatted source code
     * @throws FormatException if the source code cannot be parsed
     */
    /**
     * Format Mezze source code.
     *
     * @param source The source code to format
     * @param file Optional file path for error messages
     * @return The formatted source code
     * @throws FormatException if the source code cannot be parsed
     */
    fun format(source: String, file: String? = null): String {
        try {
            // Parse to v2 ParsedModule (primary API); PrettyPrinter still uses v1 Program
            parser.parseParsedModule(source, file = file ?: "<unknown>")
        } catch (e: Exception) {
            throw FormatException("Failed to parse source: ${e.message}", e)
        }

        // PrettyPrinter uses v1 Program which is now always empty — formatter needs migration
        return prettyPrinter.print(com.mezze.language.ast.Program(emptyList()))
    }

    /**
     * Format an already-parsed program.
     *
     * @param program The parsed program AST
     * @return The formatted source code
     */
    fun format(program: Program): String {
        return prettyPrinter.print(program)
    }

    /**
     * Check if source code is already formatted correctly.
     *
     * @param source The source code to check
     * @param file Optional file path for error messages
     * @return true if the code is already formatted correctly
     */
    fun isFormatted(source: String, file: String? = null): Boolean {
        return try {
            format(source, file) == source
        } catch (e: FormatException) {
            false
        }
    }

    /**
     * Format and verify idempotency (for testing/debugging).
     *
     * @param source The source code to format
     * @param file Optional file path for error messages
     * @return The formatted source code
     * @throws FormatException if formatting is not idempotent
     */
    fun formatWithIdempotencyCheck(source: String, file: String? = null): String {
        val first = format(source, file)
        val second = format(first, file)
        if (first != second) {
            throw FormatException(
                "Formatter is not idempotent! " +
                "First pass and second pass produced different results."
            )
        }
        return first
    }
}

/**
 * Exception thrown when formatting fails.
 */
class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
