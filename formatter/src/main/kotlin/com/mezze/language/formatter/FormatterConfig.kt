package com.mezze.language.formatter

/**
 * Internal configuration for the Mezze formatter.
 *
 * These settings are NOT exposed to users - the formatter is intentionally
 * non-configurable (like gofmt). These constants exist for internal tuning only.
 */
object FormatterConfig {
    /** Character used for indentation (tab) */
    const val INDENT_CHAR = '\t'

    /** Tab width for line length calculation (not actual rendering) */
    const val TAB_WIDTH = 2

    /** Maximum line length before breaking */
    const val MAX_LINE_LENGTH = 100

    /** Threshold for single-line records (characters) */
    const val RECORD_SINGLE_LINE_THRESHOLD = 60

    /** Threshold for single-line arrays (characters) */
    const val ARRAY_SINGLE_LINE_THRESHOLD = 60

    /** Max constructors for single-line variant types */
    const val VARIANT_SINGLE_LINE_MAX_CONSTRUCTORS = 3

    /** Max items in use selective before breaking to multi-line */
    const val USE_SELECTIVE_SINGLE_LINE_MAX_ITEMS = 4

    /** Maximum consecutive blank lines (extras collapsed to this) */
    const val MAX_CONSECUTIVE_BLANK_LINES = 1

    /** Add trailing commas in multi-line constructs */
    const val TRAILING_COMMA_IN_MULTILINE = true

    /** Add trailing commas in single-line constructs */
    const val TRAILING_COMMA_IN_SINGLE_LINE = false
}
