package com.mezze.language.typechecker

import com.mezze.language.parsedast.Loc

/**
 * Utility for formatting source code snippets in error messages.
 */
object SourceContext {

    fun formatCodeSnippet(
        sourceCode: String,
        location: Loc,
        contextLines: Int = 2
    ): String {
        val lines = sourceCode.lines()
        val errorLine = location.startLine
        val errorColumn = location.startCol

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
        // Gutter line: needs to align with content lines which have " $lineNumStr | "
        // That's: 1 space + lineNumberWidth chars + " |" = lineNumberWidth + 3 chars before the |
        // We want spaces before |, so: lineNumberWidth + 2 spaces, then |
        val gutterLine = " ".repeat(lineNumberWidth + 2) + "|"

        sb.appendLine(gutterLine)

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
                val pointerPadding = " ".repeat(lineNumberWidth + 4) // account for " " + lineNum + " | "
                val visualColumn = calculateVisualColumn(lineContent, errorColumn)
                // Compute underline length from Loc span (same-line only)
                // Loc is inclusive on both ends, so length = endCol - startCol + 1
                val underlineLength = if (location.startLine == location.endLine && location.endCol >= location.startCol)
                    location.endCol - location.startCol + 1
                else 1
                val pointer = " ".repeat(maxOf(0, visualColumn - 1)) + "^".repeat(maxOf(1, underlineLength))
                sb.appendLine("$pointerPadding$pointer")
            } else {
                // Context line
                sb.appendLine(" $lineNumStr | $displayContent")
            }
        }

        sb.appendLine(gutterLine)

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

}
