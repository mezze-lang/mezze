package com.mezze.language.typechecker.printing

import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.typechecker.*
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typechecker.solving.ConstraintSolver

/**
 * Main error formatter for TypeChecker errors, MezzeDiagnostics, and module errors.
 *
 * Validation errors are converted to [TypeCheckError] by [TypeChecker] before
 * reaching this formatter — there is no separate validation-error rendering path.
 */
object ErrorFormatter {
    /**
     * Format a type check error with full context.
     *
     * This is the main entry point for formatting TypeChecker errors.
     */
    fun format(
        error: TypeChecker.TypeCheckError,
        sourceCode: String? = null,
        colorize: Boolean = false
    ): String {
        return buildString {
            // Prefer the stable ErrorCode when set (migrated site).
            // Otherwise fall back to the phase string (legacy path).
            val migratedCode = error.code.takeIf {
                it != com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED
            }
            val bracket = if (migratedCode != null) {
                "[${migratedCode.display()}]"
            } else {
                "[${error.phase}]"
            }
            appendLine("error$bracket: ${error.message}")

            if (error.loc != null) {
                val fileInfo = error.loc.file.ifEmpty { "<input>" }
                appendLine("  --> $fileInfo:${error.loc.startLine}:${error.loc.startCol}")
            }

            if (sourceCode != null && error.loc != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, error.loc))
            }

            // Hint lines under the snippet, matching the exception-class overload.
            for (hint in error.hints) {
                appendLine("  = hint: $hint")
            }
        }.trim()
    }

    /**
     * Format a standalone TypeCheckError exception with full context.
     *
     * This overload handles the TypeCheckError exception class (as opposed to
     * the TypeChecker.TypeCheckError data class).
     */
    fun format(
        error: TypeCheckError,
        sourceCode: String? = null,
        colorize: Boolean = false,
    ): String {
        return buildString {
            // Rust-style header: `error[CODE]: message`.
            // If the new ErrorCode is set (migrated site), render the
            // stable code like `T0210`. Otherwise fall back to the
            // legacy ErrorType name. UNCATEGORIZED + GENERAL skip the
            // bracket entirely.
            val migratedCode = error.code.takeIf {
                it != com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED
            }
            val codeSlot = when {
                migratedCode != null -> "[${migratedCode.display()}]"
                error.errorType == ErrorType.GENERAL -> ""
                else -> {
                    // TYPE_MISMATCH -> "Type Mismatch"
                    val human = error.errorType.name.split('_').joinToString(" ") {
                        it.lowercase().replaceFirstChar { c -> c.uppercase() }
                    }
                    "[$human]"
                }
            }
            appendLine("error$codeSlot: ${error.message}")

            val location = error.loc
            if (location != null) {
                val fileInfo = location.file.ifEmpty { "<input>" }
                appendLine("  --> $fileInfo:${location.startLine}:${location.startCol}")
            }

            // Source code context — the caret snippet stays; it's the
            // most useful single piece of info per error.
            if (sourceCode != null && location != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, location))
            }

            // Rust-style hint lines under the snippet.
            for (hint in error.hints) {
                appendLine("  = hint: $hint")
            }
        }.trim()
    }

    /**
     * Format a [MezzeDiagnostic] — the canonical Rust-style rendering for
     * every error in the compiler. Output shape:
     *
     *     error[T0201]: <message>
     *       --> file:line:col
     *        |
     *      4 | <source line>
     *        |       ^^^^^
     *        |
     *        = hint: <hint 1>
     *        = hint: <hint 2>
     *        = note: <note message>  --> file:line:col
     *
     * `sourceCode` is the full file body; needed for the caret snippet.
     * Pass null in tests / when the source isn't available.
     */
    fun formatDiagnostic(
        diag: com.mezze.language.parsedast.diagnostics.MezzeDiagnostic,
        sourceCode: String? = null,
    ): String = buildString {
        val codeStr = diag.code.display()
        appendLine("error[$codeStr]: ${diag.message}")

        val loc = diag.loc
        val fileInfo = loc.file.ifEmpty { "<input>" }
        appendLine("  --> $fileInfo:${loc.startLine}:${loc.startCol}")

        if (sourceCode != null) {
            append(SourceContext.formatCodeSnippet(sourceCode, loc))
        }

        if (diag.hints.isNotEmpty() || diag.notes.isNotEmpty()) {
            // Hint and note lines (Rust uses `= note:` / `= help:`).
            for (hint in diag.hints) {
                appendLine("  = hint: $hint")
            }
            for (note in diag.notes) {
                val sameFile = note.loc.file == loc.file
                val locStr = if (sameFile) {
                    ":${note.loc.startLine}:${note.loc.startCol}"
                } else {
                    "${note.loc.file}:${note.loc.startLine}:${note.loc.startCol}"
                }
                appendLine("  = note: ${note.message} ($locStr)")
            }
        }
    }.trimEnd()

    /**
     * Format constraint solver error.
     */
    fun formatSolverError(
        error: ConstraintSolver.ConstraintError,
        sourceCode: String? = null
    ): String {
        return buildString {
            appendLine("❌ Type Error [Constraint Solving]")
            appendLine("━".repeat(50))

            // Error message
            appendLine()
            appendLine("Error: ${error.message}")

            // Context
            if (error.context != null) {
                appendLine()
                appendLine("Context: ${error.context}")
            }

            // Constraint info
            appendLine()
            appendLine("Failed constraint: ${error.constraint}")

            // Suggestions
            val suggestions = SuggestionGenerator.suggestForSolverError(error)
            if (suggestions.isNotEmpty()) {
                appendLine()
                appendLine("💡 Suggestions:")
                suggestions.forEach { suggestion ->
                    appendLine("   - $suggestion")
                }
            }
        }.trim()
    }

    /**
     * Format module error.
     */
    fun formatModuleError(error: ModuleError): String {
        return when (error) {
            is ModuleError.ModuleNotFound -> formatModuleNotFoundError(error)
            is ModuleError.CircularDependency -> formatModuleCircularDependencyError(error)
            is ModuleError.ParseError -> formatModuleParseError(error)
            is ModuleError.ImportError -> formatModuleImportError(error)
            is ModuleError.ExportNotFound -> formatModuleExportNotFoundError(error)
            is ModuleError.PrivateExport -> formatModulePrivateExportError(error)
            is ModuleError.InternalModuleAccess -> formatModuleInternalModuleAccessError(error)
        }
    }

    // ========================================================================
    // MODULE ERROR FORMATTERS
    // ========================================================================

    /**
     * Common Rust-style header for module errors:
     *
     *     error[<code>]: <message>
     *       --> file:line:col
     *      <snippet>
     *       = hint: ...
     *
     * `loc` is optional — when absent we omit the file pointer / snippet.
     */
    private fun renderModuleError(
        error: ModuleError,
        loc: com.mezze.language.parsedast.Loc? = null,
    ): String = buildString {
        appendLine("error[${error.code.display()}]: ${error.message}")

        if (loc != null) {
            val fileInfo = loc.file.ifEmpty { "<input>" }
            appendLine("  --> $fileInfo:${loc.startLine}:${loc.startCol}")

            // Show a snippet from disk if the file path is real. Module
            // errors are reported before the source string is threaded
            // through, so we read from disk here (best-effort).
            if (loc.file.isNotEmpty()) {
                try {
                    val file = java.io.File(loc.file)
                    if (file.exists()) {
                        val src = file.readText()
                        append(SourceContext.formatCodeSnippet(src, loc))
                    }
                } catch (_: Exception) {
                    // Ignore file read errors — fall back to header only.
                }
            }
        }

        for (hint in error.hints) {
            appendLine("  = hint: $hint")
        }
    }.trimEnd()

    private fun formatModuleNotFoundError(error: ModuleError.ModuleNotFound): String =
        renderModuleError(error)

    private fun formatModuleCircularDependencyError(error: ModuleError.CircularDependency): String =
        renderModuleError(error)

    private fun formatModuleParseError(error: ModuleError.ParseError): String =
        renderModuleError(error)

    private fun formatModuleImportError(error: ModuleError.ImportError): String =
        renderModuleError(error)

    private fun formatModuleExportNotFoundError(error: ModuleError.ExportNotFound): String =
        renderModuleError(error, error.loc)

    private fun formatModulePrivateExportError(error: ModuleError.PrivateExport): String =
        renderModuleError(error, error.loc)

    private fun formatModuleInternalModuleAccessError(error: ModuleError.InternalModuleAccess): String =
        renderModuleError(error, error.loc)
}
