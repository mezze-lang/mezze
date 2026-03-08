package com.mezze.language.parser

/**
 * Represents a hint for fixing a parse error.
 *
 * Hints provide actionable suggestions to help users quickly resolve parse errors.
 */
data class ParseHint(
    val message: String,
    val code: String? = null,
    val docUrl: String? = null,
    val severity: HintSeverity = HintSeverity.SUGGESTION
)

/**
 * Severity level for hints.
 */
enum class HintSeverity {
    /** General information about the error */
    INFO,

    /** Suggested fix for the error */
    SUGGESTION,

    /** Warning about a potential issue */
    WARNING,

    /** Best practice or tip */
    TIP
}

// Note: ParseHints factory object was removed as it was unused.
// The ParseHint and HintSeverity types are still used in ParseException.
