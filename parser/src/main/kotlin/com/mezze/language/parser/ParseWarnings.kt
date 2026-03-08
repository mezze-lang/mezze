package com.mezze.language.parser

import com.mezze.language.ast.SourceLocation

/**
 * Represents a non-fatal parser warning.
 *
 * Warnings are collected during parsing but don't prevent successful parsing.
 * They inform users about potential issues, deprecated syntax, or style improvements.
 */
data class ParseWarning(
    val message: String,
    val location: SourceLocation,
    val severity: WarningSeverity,
    val category: WarningCategory,
    val suggestion: String? = null,
    val autoFixable: Boolean = false
) {
    /**
     * Format the warning for display.
     */
    fun format(): String {
        val icon = when (severity) {
            WarningSeverity.INFO -> "ℹ️"
            WarningSeverity.STYLE -> "✨"
            WarningSeverity.WARNING -> "⚠️"
            WarningSeverity.DEPRECATED -> "🔄"
        }

        val sb = StringBuilder()
        sb.append("$icon $message\n")
        sb.append("   --> ${location.file}:${location.line}:${location.column}\n")

        if (suggestion != null) {
            sb.append("   💡 Suggestion: $suggestion\n")
        }

        if (autoFixable) {
            sb.append("   🔧 Auto-fixable\n")
        }

        return sb.toString()
    }
}

/**
 * Severity level for warnings.
 */
enum class WarningSeverity {
    /** Informational message */
    INFO,

    /** Code style suggestion */
    STYLE,

    /** Potential issue or problem */
    WARNING,

    /** Deprecated syntax that should be updated */
    DEPRECATED
}

/**
 * Category of warning.
 */
enum class WarningCategory {
    /** Deprecated language syntax */
    DEPRECATED_SYNTAX,

    /** Code style and formatting */
    STYLE,

    /** Performance concerns */
    PERFORMANCE,

    /** Unused code */
    UNUSED_CODE,

    /** Best practice violations */
    BEST_PRACTICE,

    /** Compatibility issues */
    COMPATIBILITY
}

/**
 * Configuration for parser warnings.
 */
data class ParserWarningConfig(
    /** Warn about deprecated syntax */
    val warnDeprecated: Boolean = true,

    /** Warn about style issues */
    val warnStyle: Boolean = true,

    /** Warn about performance issues */
    val warnPerformance: Boolean = true,

    /** Warn about unused code */
    val warnUnused: Boolean = true,

    /** Warn about best practice violations */
    val warnBestPractice: Boolean = true,

    /** Maximum number of warnings to collect (prevents memory issues) */
    val maxWarnings: Int = 100
) {
    /**
     * Check if a warning category should be reported.
     */
    fun shouldWarn(category: WarningCategory): Boolean {
        return when (category) {
            WarningCategory.DEPRECATED_SYNTAX -> warnDeprecated
            WarningCategory.STYLE -> warnStyle
            WarningCategory.PERFORMANCE -> warnPerformance
            WarningCategory.UNUSED_CODE -> warnUnused
            WarningCategory.BEST_PRACTICE -> warnBestPractice
            WarningCategory.COMPATIBILITY -> true  // Always warn about compatibility
        }
    }
}

// Note: CommonWarnings object was removed as it was unused.
// ParseWarning and related types are still available for use.
