package com.mezze.language.langservice.printing

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.typechecker.*
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typechecker.solving.ConstraintSolver

object ErrorFormatter {
        fun format(
        error: TypeChecker.TypeCheckError,
        sourceCode: String? = null,
        colorize: Boolean = false
    ): String {
        return buildString {
            val migratedCode = error.code.takeIf {
                it != ErrorCode.UNCATEGORIZED
            }
            val bracket = if (migratedCode != null) {
                "[${migratedCode.display()}]"
            } else {
                "[${error.phase}]"
            }
            appendLine("error$bracket: ${error.message}")

            // Bind to a local: `loc` is a nullable public property on a type in
            // another module (:typechecker), so Kotlin won't smart-cast it after
            // the null check across the module boundary.
            val loc = error.loc
            if (loc != null) {
                val fileInfo = loc.file.ifEmpty { "<input>" }
                appendLine("  --> $fileInfo:${loc.startLine}:${loc.startCol}")
            }

            if (sourceCode != null && loc != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, loc))
            }

            for (hint in error.hints) {
                appendLine("  = hint: $hint")
            }
        }.trim()
    }

        fun format(
        error: TypeCheckError,
        sourceCode: String? = null,
        colorize: Boolean = false,
    ): String {
        return buildString {
            val migratedCode = error.code.takeIf {
                it != ErrorCode.UNCATEGORIZED
            }
            val codeSlot = when {
                migratedCode != null -> "[${migratedCode.display()}]"
                error.errorType == ErrorType.GENERAL -> ""
                else -> {
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

            if (sourceCode != null && location != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, location))
            }

            for (hint in error.hints) {
                appendLine("  = hint: $hint")
            }
        }.trim()
    }

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
        private fun renderModuleError(
        error: ModuleError,
        loc: Loc? = null,
    ): String = buildString {
        appendLine("error[${error.code.display()}]: ${error.message}")

        if (loc != null) {
            val fileInfo = loc.file.ifEmpty { "<input>" }
            appendLine("  --> $fileInfo:${loc.startLine}:${loc.startCol}")

            if (loc.file.isNotEmpty()) {
                try {
                    val file = java.io.File(loc.file)
                    if (file.exists()) {
                        val src = file.readText()
                        append(SourceContext.formatCodeSnippet(src, loc))
                    }
                } catch (_: Exception) {
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
