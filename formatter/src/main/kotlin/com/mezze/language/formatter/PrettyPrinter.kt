package com.mezze.language.formatter

import com.mezze.language.parsedast.*

/**
 * Pretty printer for Mezze AST.
 *
 * Converts ParsedModule nodes back to formatted source code with consistent style.
 * Handles comment attachment based on source positions.
 *
 * NOTE: This is a stub implementation. The v1 types (Program, LetBinding, etc.)
 * have been deleted. A full v2 PrettyPrinter operating on ParsedModule is needed
 * for complete formatting support.
 */
class PrettyPrinter {
    private val config = FormatterConfig
    private val output = StringBuilder()
    private var indentLevel = 0
    private var currentLine = 1
    private var currentColumn = 0

    /**
     * Print a complete ParsedModule to formatted source code.
     * Stub implementation — returns minimal representation.
     */
    fun printModule(module: ParsedModule): String {
        output.clear()
        indentLevel = 0
        currentLine = 1
        currentColumn = 0

        for (decl in module.decls) {
            printParsedDecl(decl)
        }

        return output.toString().trimEnd() + "\n"
    }

    private fun printParsedDecl(decl: ParsedDecl) {
        when (decl) {
            is ParsedLetDecl -> {
                if (decl.visibility == Visibility.PUBLIC) append("pub ")
                append("let ")
                append(decl.variableName)
                append(" = ...")
                newLine()
            }
            is ParsedEffectDecl -> {
                append("effect ")
                append(decl.name)
                append(" where { ... }")
                newLine()
            }
            is ParsedAbilityDecl -> {
                append("ability ")
                append(decl.name)
                append(" where { ... }")
                newLine()
            }
            is ParsedUseDecl -> {
                append("use ")
                append(formatUsePath(decl.path))
                newLine()
            }
            is ParsedPubUseDecl -> {
                append("pub use ")
                append(formatUsePath(decl.path))
                newLine()
            }
            else -> {
                // Other declaration types — skip for now
            }
        }
    }

    private fun formatUsePath(path: ParsedUsePath): String {
        return when (path) {
            is ParsedUsePath.Module -> path.path.segments.joinToString("::")
            is ParsedUsePath.Selective -> {
                val base = path.modulePath.segments.joinToString("::")
                val items = path.items.joinToString(", ") { item ->
                    item.alias?.let { "${item.name} as $it" } ?: item.name
                }
                "$base::{ $items }"
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun append(s: String) {
        output.append(s)
        val lastNewline = s.lastIndexOf('\n')
        if (lastNewline >= 0) {
            currentColumn = s.length - lastNewline - 1
        } else {
            currentColumn += s.length
        }
    }

    private fun newLine() {
        output.append("\n")
        currentLine++
        currentColumn = 0
    }
}
