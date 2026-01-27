package com.funk.language.parser

import com.funk.language.ast.SourceLocation

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

/**
 * Common warning patterns for parser.
 */
object CommonWarnings {

    fun unnecessaryParentheses(location: SourceLocation, expression: String): ParseWarning {
        return ParseWarning(
            message = "Unnecessary parentheses around $expression",
            location = location,
            severity = WarningSeverity.STYLE,
            category = WarningCategory.STYLE,
            suggestion = "Remove parentheses",
            autoFixable = true
        )
    }

    fun deprecatedEmptyArraySyntax(location: SourceLocation): ParseWarning {
        return ParseWarning(
            message = "Empty [] syntax is deprecated",
            location = location,
            severity = WarningSeverity.DEPRECATED,
            category = WarningCategory.DEPRECATED_SYNTAX,
            suggestion = "Use: Array",
            autoFixable = true
        )
    }

    fun largeArrayLiteral(location: SourceLocation, size: Int): ParseWarning {
        return ParseWarning(
            message = "Large array literal ($size elements) may impact performance",
            location = location,
            severity = WarningSeverity.WARNING,
            category = WarningCategory.PERFORMANCE,
            suggestion = "Consider generating array programmatically or loading from file"
        )
    }

    fun deepNesting(location: SourceLocation, depth: Int, context: String): ParseWarning {
        return ParseWarning(
            message = "Very deep nesting ($depth levels) in $context",
            location = location,
            severity = WarningSeverity.STYLE,
            category = WarningCategory.BEST_PRACTICE,
            suggestion = "Consider refactoring to reduce nesting depth"
        )
    }

    fun unusedImport(location: SourceLocation, moduleName: String): ParseWarning {
        return ParseWarning(
            message = "Imported module '$moduleName' appears unused",
            location = location,
            severity = WarningSeverity.INFO,
            category = WarningCategory.UNUSED_CODE,
            suggestion = "Remove unused import"
        )
    }

    fun longFunction(location: SourceLocation, lineCount: Int, functionName: String): ParseWarning {
        return ParseWarning(
            message = "Function '$functionName' is very long ($lineCount lines)",
            location = location,
            severity = WarningSeverity.STYLE,
            category = WarningCategory.BEST_PRACTICE,
            suggestion = "Consider breaking into smaller functions"
        )
    }

    fun duplicatedCode(location: SourceLocation, pattern: String): ParseWarning {
        return ParseWarning(
            message = "Potential code duplication detected: $pattern",
            location = location,
            severity = WarningSeverity.INFO,
            category = WarningCategory.BEST_PRACTICE,
            suggestion = "Consider extracting common code into a helper function"
        )
    }

    fun inconsistentNaming(location: SourceLocation, name: String, convention: String): ParseWarning {
        return ParseWarning(
            message = "Name '$name' doesn't follow $convention convention",
            location = location,
            severity = WarningSeverity.STYLE,
            category = WarningCategory.STYLE,
            suggestion = "Follow naming convention: $convention"
        )
    }

    fun complexExpression(location: SourceLocation, complexity: Int): ParseWarning {
        return ParseWarning(
            message = "Very complex expression (complexity: $complexity)",
            location = location,
            severity = WarningSeverity.STYLE,
            category = WarningCategory.BEST_PRACTICE,
            suggestion = "Consider breaking into smaller expressions with intermediate variables"
        )
    }

    fun potentiallyConfusingSyntax(location: SourceLocation, issue: String, clarification: String): ParseWarning {
        return ParseWarning(
            message = "Potentially confusing syntax: $issue",
            location = location,
            severity = WarningSeverity.WARNING,
            category = WarningCategory.BEST_PRACTICE,
            suggestion = clarification
        )
    }
}
