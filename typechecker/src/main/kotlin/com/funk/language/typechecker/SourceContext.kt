package com.funk.language.typechecker

import com.funk.language.ast.SourceLocation

/**
 * Utility for formatting error messages with source code context.
 * Provides rich error display with line numbers, code snippets, and error highlighting.
 */
object SourceContext {

    /**
     * Format an error with source code context
     */
    fun formatError(
        errorType: String,
        message: String,
        location: SourceLocation?,
        sourceCode: String?,
        suggestion: String? = null,
        additionalInfo: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()

        // Error header
        sb.appendLine("❌ $errorType")
        sb.appendLine("━".repeat(50))

        // Location info
        if (location != null) {
            val fileInfo = location.file ?: "<unknown>"
            sb.appendLine("   --> $fileInfo:${location.line}:${location.column}")
        }

        // Code snippet
        if (sourceCode != null && location != null) {
            sb.append(formatCodeSnippet(sourceCode, location))
        }

        // Error message
        sb.appendLine()
        sb.appendLine("Error: $message")

        // Additional info (like expected/actual types)
        if (additionalInfo.isNotEmpty()) {
            sb.appendLine()
            additionalInfo.forEach { (key, value) ->
                sb.appendLine("    $key: $value")
            }
        }

        // Suggestion
        if (suggestion != null) {
            sb.appendLine()
            sb.appendLine("💡 Suggestion:")
            suggestion.lines().forEach { line ->
                sb.appendLine("   $line")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Format a code snippet with line numbers and error pointer
     */
    fun formatCodeSnippet(
        sourceCode: String,
        location: SourceLocation,
        contextLines: Int = 2
    ): String {
        val lines = sourceCode.lines()
        val errorLine = location.line
        val errorColumn = location.column

        // Validate line number
        if (errorLine < 1 || errorLine > lines.size) {
            return ""
        }

        val sb = StringBuilder()

        // Calculate range of lines to show
        val startLine = maxOf(1, errorLine - contextLines)
        val endLine = minOf(lines.size, errorLine + contextLines)

        // Calculate the width needed for line numbers
        val lineNumberWidth = endLine.toString().length

        sb.appendLine("    |")

        // Show lines with context
        for (lineNum in startLine..endLine) {
            val lineContent = lines[lineNum - 1]
            // Expand tabs to spaces for consistent alignment (tabs = 4 spaces)
            val displayContent = expandTabs(lineContent)
            val lineNumStr = lineNum.toString().padStart(lineNumberWidth)

            if (lineNum == errorLine) {
                // Error line - show with marker
                sb.appendLine(" $lineNumStr | $displayContent")

                // Show error pointer - calculate visual column accounting for tabs
                val padding = " ".repeat(lineNumberWidth + 3) // account for " " + lineNum + " | "
                val visualColumn = calculateVisualColumn(lineContent, errorColumn)
                val pointer = " ".repeat(maxOf(0, visualColumn - 1)) + "^"
                sb.appendLine("$padding$pointer")
            } else {
                // Context line
                sb.appendLine(" $lineNumStr | $displayContent")
            }
        }

        sb.appendLine("    |")

        return sb.toString()
    }

    /**
     * Expand tab characters to spaces (4 spaces per tab)
     */
    private fun expandTabs(line: String, tabWidth: Int = 4): String {
        val result = StringBuilder()
        for (char in line) {
            if (char == '\t') {
                // Add spaces to next tab stop
                val spacesToAdd = tabWidth - (result.length % tabWidth)
                result.append(" ".repeat(spacesToAdd))
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }

    /**
     * Calculate the visual column position accounting for tab characters
     */
    private fun calculateVisualColumn(line: String, column: Int, tabWidth: Int = 4): Int {
        var visualCol = 1
        for (i in 0 until minOf(column - 1, line.length)) {
            if (line[i] == '\t') {
                // Move to next tab stop
                visualCol += tabWidth - ((visualCol - 1) % tabWidth)
            } else {
                visualCol++
            }
        }
        return visualCol
    }

    /**
     * Format a code snippet with a span highlighted (for multi-character errors)
     */
    fun formatCodeSnippetWithSpan(
        sourceCode: String,
        location: SourceLocation,
        spanLength: Int = 1,
        contextLines: Int = 2
    ): String {
        val lines = sourceCode.lines()
        val errorLine = location.line
        val errorColumn = location.column

        // Validate line number
        if (errorLine < 1 || errorLine > lines.size) {
            return ""
        }

        val sb = StringBuilder()

        // Calculate range of lines to show
        val startLine = maxOf(1, errorLine - contextLines)
        val endLine = minOf(lines.size, errorLine + contextLines)

        // Calculate the width needed for line numbers
        val lineNumberWidth = endLine.toString().length

        sb.appendLine("    |")

        // Show lines with context
        for (lineNum in startLine..endLine) {
            val lineContent = lines[lineNum - 1]
            // Expand tabs to spaces for consistent alignment (tabs = 4 spaces)
            val displayContent = expandTabs(lineContent)
            val lineNumStr = lineNum.toString().padStart(lineNumberWidth)

            if (lineNum == errorLine) {
                // Error line - show with marker
                sb.appendLine(" $lineNumStr | $displayContent")

                // Show error underline - calculate visual column accounting for tabs
                val padding = " ".repeat(lineNumberWidth + 3) // account for " " + lineNum + " | "
                val visualColumn = calculateVisualColumn(lineContent, errorColumn)
                val underline = " ".repeat(maxOf(0, visualColumn - 1)) + "^".repeat(maxOf(1, spanLength))
                sb.appendLine("$padding$underline")
            } else {
                // Context line
                sb.appendLine(" $lineNumStr | $displayContent")
            }
        }

        sb.appendLine("    |")

        return sb.toString()
    }

    /**
     * Extract source location from character position
     * Useful for converting parser position to line/column
     */
    fun positionToLocation(source: String, position: Int, file: String? = null): SourceLocation {
        var line = 1
        var column = 1

        for (i in 0 until minOf(position, source.length)) {
            if (source[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }

        return SourceLocation(line, column, file)
    }

    /**
     * Get the line content at a specific line number
     */
    fun getLine(source: String, lineNumber: Int): String? {
        val lines = source.lines()
        return if (lineNumber in 1..lines.size) {
            lines[lineNumber - 1]
        } else {
            null
        }
    }

    /**
     * Create a simple error message with location
     */
    fun simpleError(
        errorType: String,
        message: String,
        location: SourceLocation?
    ): String {
        val sb = StringBuilder()
        sb.append("❌ $errorType")
        if (location != null) {
            val fileInfo = location.file?.let { "$it:" } ?: ""
            sb.append(" at $fileInfo${location.line}:${location.column}")
        }
        sb.appendLine()
        sb.appendLine("━".repeat(50))
        sb.appendLine(message)
        return sb.toString().trim()
    }
}
