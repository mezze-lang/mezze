package com.mezze.language.typechecker

import com.mezze.language.parsedast.Loc

object SourceContext {

    fun formatCodeSnippet(
        sourceCode: String,
        location: Loc,
        contextLines: Int = 2
    ): String {
        val lines = sourceCode.lines()
        val errorLine = location.startLine
        val errorColumn = location.startCol

        if (errorLine < 1 || errorLine > lines.size) return ""

        val startLine = maxOf(1, errorLine - contextLines)
        val endLine = minOf(lines.size, errorLine + contextLines)
        val lineNumberWidth = endLine.toString().length
        val gutterLine = " ".repeat(lineNumberWidth + 2) + "|"

        return buildString {
            appendLine(gutterLine)
            for (lineNum in startLine..endLine) {
                val lineContent = lines[lineNum - 1]
                val displayContent = expandTabs(lineContent)
                val lineNumStr = lineNum.toString().padStart(lineNumberWidth)
                appendLine(" $lineNumStr | $displayContent")
                if (lineNum == errorLine) {
                    val pointerPadding = " ".repeat(lineNumberWidth + 4)
                    val visualColumn = calculateVisualColumn(lineContent, errorColumn)
                    val underlineLength = if (location.startLine == location.endLine && location.endCol >= location.startCol)
                        location.endCol - location.startCol + 1
                    else 1
                    val pointer = " ".repeat(maxOf(0, visualColumn - 1)) + "^".repeat(maxOf(1, underlineLength))
                    appendLine("$pointerPadding$pointer")
                }
            }
            appendLine(gutterLine)
        }
    }

    private fun expandTabs(line: String, tabWidth: Int = 4): String = buildString {
        for (char in line) {
            if (char == '\t') append(" ".repeat(tabWidth - (length % tabWidth)))
            else append(char)
        }
    }

        private fun calculateVisualColumn(line: String, column: Int, tabWidth: Int = 4): Int {
        var visualCol = 1
        for (i in 0 until minOf(column - 1, line.length)) {
            if (line[i] == '\t') {
                visualCol += tabWidth - ((visualCol - 1) % tabWidth)
            } else {
                visualCol++
            }
        }
        return visualCol
    }

}
