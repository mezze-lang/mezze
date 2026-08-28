package com.mezze.language.formatter

/**
 * Widths the pretty-printer breaks on.
 *
 * The formatter is non-configurable by design (like gofmt): the CLI, the LSP
 * and `mez fmt` all use [FormatterConfig], whose values never change. This
 * interface exists only so the browser build can wrap to the width of the
 * editor the reader actually has, where a fixed 100 columns would either
 * overflow a narrow pane or waste a wide one.
 *
 * Nothing outside the WASM entry point should construct an alternative.
 */
interface FormatterWidths {
    val INDENT_STRING: String
    val INDENT_WIDTH: Int
    val MAX_LINE_LENGTH: Int
    val RECORD_SINGLE_LINE_THRESHOLD: Int
    val ARRAY_SINGLE_LINE_THRESHOLD: Int
    val VARIANT_SINGLE_LINE_MAX_CONSTRUCTORS: Int
    val USE_SELECTIVE_SINGLE_LINE_MAX_ITEMS: Int
    val MAX_CONSECUTIVE_BLANK_LINES: Int
    val TRAILING_COMMA_IN_MULTILINE: Boolean
    val TRAILING_COMMA_IN_SINGLE_LINE: Boolean
}

/**
 * Internal configuration for the Mezze formatter.
 *
 * These settings are NOT exposed to users - the formatter is intentionally
 * non-configurable (like gofmt). These constants exist for internal tuning only.
 */
object FormatterConfig : FormatterWidths {
    /** String used for indentation (2 spaces) */
    override val INDENT_STRING = "  "

    /** Indent width for line length calculation */
    override val INDENT_WIDTH = 2

    /** Maximum line length before breaking */
    override val MAX_LINE_LENGTH = 100

    /** Threshold for single-line records (characters) */
    override val RECORD_SINGLE_LINE_THRESHOLD = 60

    /** Threshold for single-line arrays (characters) */
    override val ARRAY_SINGLE_LINE_THRESHOLD = 60

    /** Max constructors for single-line variant types */
    override val VARIANT_SINGLE_LINE_MAX_CONSTRUCTORS = 3

    /** Max items in use selective before breaking to multi-line */
    override val USE_SELECTIVE_SINGLE_LINE_MAX_ITEMS = 4

    /** Maximum consecutive blank lines (extras collapsed to this) */
    override val MAX_CONSECUTIVE_BLANK_LINES = 1

    /** Add trailing commas in multi-line constructs */
    override val TRAILING_COMMA_IN_MULTILINE = true

    /** Add trailing commas in single-line constructs */
    override val TRAILING_COMMA_IN_SINGLE_LINE = false
}

/**
 * [FormatterConfig] with the line width replaced.
 *
 * Only the browser playground uses this — see MezzeWeb.format. The record and
 * array thresholds scale with the line width so a narrow editor does not get
 * long records on one line while short expressions break.
 */
class WidthOverride(override val MAX_LINE_LENGTH: Int) : FormatterWidths by FormatterConfig {
    override val RECORD_SINGLE_LINE_THRESHOLD =
        minOf(FormatterConfig.RECORD_SINGLE_LINE_THRESHOLD, MAX_LINE_LENGTH * 3 / 5)
    override val ARRAY_SINGLE_LINE_THRESHOLD =
        minOf(FormatterConfig.ARRAY_SINGLE_LINE_THRESHOLD, MAX_LINE_LENGTH * 3 / 5)
}
