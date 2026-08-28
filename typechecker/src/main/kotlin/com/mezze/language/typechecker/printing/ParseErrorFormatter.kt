package com.mezze.language.typechecker.printing

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.parser.ParseException

/**
 * Rust-style formatter for parse errors.
 *
 * Mirrors the layout of [ErrorFormatter], its neighbour here, so parse + type
 * errors look the same in `mez check` / `mez build` / `mez dev`:
 *
 *   error: <one-line message>
 *     --> file:line:col
 *      |
 *    N | <source line>
 *      |       ^^^
 *      |
 *
 * Long-form fix advice baked into legacy parser messages is trimmed to
 * the first short sentence; the rest is dropped (will eventually move
 * behind `mez --explain CODE`).
 */
object ParseErrorFormatter {

    fun format(error: ParseException): String = buildString {
        val message = trimToFirstSentence(error.errorMessage)
        val header = if (error.code != ErrorCode.UNCATEGORIZED) {
            "error[${error.code.display()}]: $message"
        } else {
            "error: $message"
        }
        appendLine(header)

        if (error.line > 0) {
            val fileInfo = error.file ?: "<input>"
            appendLine("  --> $fileInfo:${error.line}:${error.column}")
        }

        val source = error.sourceCode
        if (source != null && error.line > 0) {
            val rawLines = source.lines()
            // If the parser pointed past the end of the file (common when an
            // unclosed brace causes the lexer to hit EOF), the target line
            // is either out-of-range or an empty trailing line. Walk back
            // to the last non-blank line and render `<eof>` as the caret
            // target so the user can see *where* the file ran out.
            val (renderLine, renderContent, atEof) = run {
                val idx = error.line - 1
                if (idx >= rawLines.size || rawLines[idx].isBlank()) {
                    var back = minOf(idx, rawLines.lastIndex)
                    while (back >= 0 && rawLines[back].isBlank()) back--
                    if (back >= 0) Triple(back + 1, rawLines[back], true)
                    else Triple(error.line, "", true)
                } else {
                    Triple(error.line, rawLines[idx], false)
                }
            }
            val lineNumStr = renderLine.toString()
            val padding = " ".repeat(lineNumStr.length)
            appendLine("$padding |")
            appendLine("$lineNumStr | $renderContent")
            val column = if (atEof) renderContent.length + 1 else error.column
            val pointer = " ".repeat(maxOf(0, column - 1)) +
                if (atEof) "^ unexpected end of file" else "^".repeat(maxOf(1, error.length))
            appendLine("$padding | $pointer")
            appendLine("$padding |")
        }

        for (hint in error.hints) {
            appendLine("  = hint: $hint")
        }
    }.trimEnd()

    /**
     * Some parser errors carry multi-paragraph fix advice in the message
     * itself (`"Do block must end...\n\nFix: ...\n  do\n    let x = ..."`).
     * For Rust-style output we want a single-line headline. Keep just the
     * first sentence (up to the first `\n` or `.`-followed-by-space).
     */
    private fun trimToFirstSentence(raw: String): String {
        val firstLine = raw.substringBefore('\n').trim()
        // If the first line is itself a multi-sentence blob, keep just sentence 1.
        val dotIdx = firstLine.indexOf(". ")
        return if (dotIdx > 0) firstLine.substring(0, dotIdx + 1) else firstLine
    }

    /**
     * Format every parse error as a single Rust-style block, blank-line
     * separated. Footer mirrors `formatErrors` from `TypeCheckRunner`.
     */
    fun formatAll(errors: List<ParseException>): String = buildString {
        for ((idx, err) in errors.withIndex()) {
            if (idx > 0) append("\n\n")
            append(format(err))
        }
        if (errors.isNotEmpty()) {
            val n = errors.size
            append("\n\nerror: aborting due to $n ")
            append(if (n == 1) "previous parse error" else "previous parse errors")
        }
    }
}
