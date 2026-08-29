package com.mezze.language.formatter

import com.mezze.language.parsedast.ParsedModule
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
class Formatter(widths: FormatterWidths = FormatterConfig) {
    private val parser = MezzeParser()
    private val prettyPrinter = PrettyPrinter(widths)

    /**
     * Format Mezze source code.
     *
     * @param source The source code to format
     * @param file Optional file path for error messages
     * @return The formatted source code
     * @throws FormatException if the source code cannot be parsed
     */
    fun format(source: String, file: String? = null): String {
        val parsedModule = try {
            parser.parseParsedModule(source, file = file ?: "<unknown>")
        } catch (e: Exception) {
            throw FormatException("Failed to parse source: ${e.message}", e)
        }

        return prettyPrinter.printModule(parsedModule)
    }

    /**
     * Format an already-parsed module.
     *
     * @param module The parsed module AST
     * @return The formatted source code
     */
    fun format(module: ParsedModule): String {
        return prettyPrinter.printModule(module)
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
     * Format a small Mezze snippet (one or a handful of top-level decls)
     * the same way the full-file formatter would. Used by LSP code actions
     * that need to insert generated code (auto-import lines, etc.) so the
     * inserted text matches the project style instead of whatever the
     * action builder typed inline.
     *
     * Falls back to the input unchanged if the snippet doesn't parse as a
     * valid set of top-level decls — callers shouldn't crash a quick-fix
     * just because we can't pretty-print it.
     */
    fun formatSnippet(snippet: String, file: String? = null): String {
        return try {
            // Strip a single trailing newline so we don't double-up — the
            // pretty printer always ends with one.
            val formatted = format(snippet, file)
            formatted
        } catch (_: FormatException) {
            snippet
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
