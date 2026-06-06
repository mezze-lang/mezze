package com.mezze.language.typechecker.printing

import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.typechecker.*
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typechecker.module.ModuleTypeCheckResult
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.validation.ValidationError

/**
 * Main error formatter for TypeChecker errors.
 *
 * Provides beautiful, helpful error messages with:
 * - Source code context
 * - Type diffs (when applicable)
 * - Helpful suggestions
 * - Consistent formatting
 *
 * Design:
 * - Reuses SourceContext.kt for code snippets
 * - Delegates to TypeDiffPrinter for type comparisons
 * - Delegates to SuggestionGenerator for suggestions
 * - Exhaustive pattern matching on all error types
 *
 * Usage:
 * ```kotlin
 * val formatted = ErrorFormatter.format(error, sourceCode)
 * println(formatted)
 * ```
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
     * Format validation error.
     *
     * ValidationErrors already have good messages, but we enhance them with:
     * - Source code context
     * - Type diffs (for type mismatches)
     * - Helpful suggestions
     */
    fun formatValidationError(
        error: ValidationError,
        sourceCode: String? = null
    ): String {
        val body = when (error) {
            is ValidationError.AbilityNotSatisfied -> formatAbilityError(error, sourceCode)
            is ValidationError.EffectNotHandled -> formatEffectError(error, sourceCode)
            is ValidationError.NonExhaustiveMatch -> formatExhaustivenessError(error, sourceCode)
            is ValidationError.UnreachablePattern -> formatUnreachablePatternError(error, sourceCode)
            is ValidationError.NonExhaustiveRecordPattern -> formatNonExhaustiveRecordPatternError(error, sourceCode)
            is ValidationError.TypeMismatch -> formatTypeMismatchError(error, sourceCode)
            is ValidationError.AnnotationMismatch -> formatAnnotationMismatchError(error, sourceCode)
            is ValidationError.FieldNotFound -> formatFieldNotFoundError(error, sourceCode)
            is ValidationError.ConstructorNotFound -> formatConstructorNotFoundError(error, sourceCode)
            is ValidationError.VariableNotInScope -> formatVariableNotInScopeError(error, sourceCode)
            is ValidationError.AbilityUsedAsValue -> formatAbilityUsedAsValueError(error, sourceCode)
            is ValidationError.InvalidTypeApplication -> formatInvalidTypeApplicationError(error, sourceCode)
            is ValidationError.InvalidRecursiveType -> formatInvalidRecursiveTypeError(error, sourceCode)
            is ValidationError.CircularDependency -> formatCircularDependencyError(error, sourceCode)
            is ValidationError.OrphanInstance -> formatOrphanInstanceError(error, sourceCode)
            is ValidationError.OverlappingInstance -> formatOverlappingInstanceError(error, sourceCode)
            is ValidationError.MissingAbilityImplementation -> formatMissingAbilityImplementationError(error, sourceCode)
            is ValidationError.HandlerTypeMismatch -> formatHandlerTypeMismatchError(error, sourceCode)
            is ValidationError.UnitVariantPayloadMismatch -> formatUnitVariantPayloadMismatchError(error, sourceCode)
            is ValidationError.TopLevelPerformedEffects -> formatTopLevelPerformedEffectsError(error, sourceCode)
            is ValidationError.TopLevelExpressionPerformedEffects -> formatTopLevelExpressionPerformedEffectsError(error, sourceCode)
            is ValidationError.StructuralError -> formatStructuralError(error, sourceCode)
            is ValidationError.MissingContentHash -> formatMissingContentHashError(error, sourceCode)
        }

        // Rust-style `error[Vxxxx]: <message>` header prepended to the
        // existing rendered body. Hints (if any) appended at the end as
        // `  = hint: <text>` lines. Body output from per-subtype renderers
        // is preserved unchanged for backwards compatibility with snapshots
        // that depend on the type-diff / suggestion sections.
        return buildString {
            appendLine("error[${error.code.display()}]: ${error.message()}")
            append(body)
            for (hint in error.hints) {
                appendLine()
                append("  = hint: $hint")
            }
        }.trimEnd()
    }

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
    // VALIDATION ERROR FORMATTERS
    // ========================================================================

    private fun formatAbilityError(
        error: ValidationError.AbilityNotSatisfied,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Ability]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = SuggestionGenerator.suggestForMissingAbility(
                error.type,
                error.abilityName,
                error.availableAbilities
            ).joinToString("\n")
        )
    }

    private fun formatEffectError(
        error: ValidationError.EffectNotHandled,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Effect]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Add a handler for the '${error.effectName}' effect or propagate it in the function signature"
        )
    }

    private fun formatExhaustivenessError(
        error: ValidationError.NonExhaustiveMatch,
        sourceCode: String?
    ): String {
        val suggestions = buildString {
            if (error.missingConstructors.isNotEmpty()) {
                appendLine("Add cases for: ${error.missingConstructors.joinToString()}")
            }
            appendLine("Or add a catch-all pattern: _ -> ...")
        }

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Exhaustiveness]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestions.trim()
        )
    }

    private fun formatUnreachablePatternError(
        error: ValidationError.UnreachablePattern,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Unreachable Pattern]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Remove the unreachable pattern, or add a guard condition if this case should sometimes match"
        )
    }

    private fun formatNonExhaustiveRecordPatternError(
        error: ValidationError.NonExhaustiveRecordPattern,
        sourceCode: String?
    ): String {
        val suggestions = buildString {
            if (error.missingFields.isNotEmpty()) {
                appendLine("Add missing fields to the pattern: ${error.missingFields.joinToString()}")
            }
            appendLine("Or use {...} to explicitly ignore remaining fields")
        }

        val additionalInfo = mapOf(
            "Missing fields" to error.missingFields.joinToString(", "),
            "Record type" to TypeDiffPrinter.formatType(error.recordType)
        )

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Non-Exhaustive Record Pattern]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestions.trim(),
            additionalInfo = additionalInfo
        )
    }

    private fun formatTypeMismatchError(
        error: ValidationError.TypeMismatch,
        sourceCode: String?
    ): String {
        val typeDiff = TypeDiffPrinter.formatDiff(error.expected, error.actual, error.context)

        return buildString {
            appendLine("❌ Type Error [Validation - Type Mismatch]")
            appendLine("━".repeat(50))

            // Location
            error.location()?.let { loc ->
                val fileInfo = loc.file.ifEmpty { "<input>" }
                appendLine("   --> $fileInfo:${loc.startLine}:${loc.startCol}")
            }

            // Source code context
            error.location()?.let { loc ->
                if (sourceCode != null) {
                    append(SourceContext.formatCodeSnippet(sourceCode, loc))
                }
            }

            // Error message with type diff
            appendLine()
            appendLine("Error: ${error.message()}")
            appendLine()
            append(typeDiff)

            // Suggestions
            val suggestions = SuggestionGenerator.suggestForTypeMismatch(
                error.expected,
                error.actual,
                error.context
            )
            if (suggestions.isNotEmpty()) {
                appendLine()
                appendLine("💡 Suggestions:")
                suggestions.forEach { suggestion ->
                    appendLine("   - $suggestion")
                }
            }
        }.trim()
    }

    private fun formatAnnotationMismatchError(
        error: ValidationError.AnnotationMismatch,
        sourceCode: String?
    ): String {
        val typeDiff = TypeDiffPrinter.formatDiff(error.annotatedType, error.inferredType, "annotation")

        return buildString {
            appendLine("❌ Type Error [Validation - Annotation Mismatch]")
            appendLine("━".repeat(50))

            // Location
            error.location()?.let { loc ->
                val fileInfo = loc.file.ifEmpty { "<input>" }
                appendLine("   --> $fileInfo:${loc.startLine}:${loc.startCol}")
            }

            // Source code context
            error.location()?.let { loc ->
                if (sourceCode != null) {
                    append(SourceContext.formatCodeSnippet(sourceCode, loc))
                }
            }

            // Error message with type diff
            appendLine()
            appendLine("Error: ${error.message()}")
            appendLine()
            append(typeDiff)

            // Suggestions
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Remove the type annotation to let the type checker infer the type")
            appendLine("   - Correct the type annotation to match the inferred type")
            appendLine("   - Change the expression to match the annotated type")
        }.trim()
    }

    private fun formatFieldNotFoundError(
        error: ValidationError.FieldNotFound,
        sourceCode: String?
    ): String {
        val additionalInfo = mutableMapOf<String, String>()
        additionalInfo["Record type"] = TypeDiffPrinter.formatType(error.recordType)
        if (error.availableFields.isNotEmpty()) {
            additionalInfo["Available fields"] = error.availableFields.joinToString(", ")
        }

        val suggestions = SuggestionGenerator.suggestForUndefinedVariable(
            error.fieldName,
            error.availableFields
        )

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Field Not Found]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestions.joinToString("\n"),
            additionalInfo = additionalInfo
        )
    }

    private fun formatConstructorNotFoundError(
        error: ValidationError.ConstructorNotFound,
        sourceCode: String?
    ): String {
        val additionalInfo = mutableMapOf<String, String>()
        additionalInfo["Variant type"] = TypeDiffPrinter.formatType(error.variantType)
        if (error.availableConstructors.isNotEmpty()) {
            additionalInfo["Available constructors"] = error.availableConstructors.joinToString(", ")
        }

        val suggestions = SuggestionGenerator.suggestForUndefinedVariable(
            error.constructorName,
            error.availableConstructors
        )

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Constructor Not Found]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestions.joinToString("\n"),
            additionalInfo = additionalInfo
        )
    }

    private fun formatVariableNotInScopeError(
        error: ValidationError.VariableNotInScope,
        sourceCode: String?
    ): String {
        val suggestions = if (error.similarNames.isNotEmpty()) {
            "Did you mean: ${error.similarNames.joinToString(" or ")}"
        } else {
            "Check the variable name spelling\nEnsure the variable is declared before use\nFor imported functions, use 'use module_name'"
        }

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Variable Not In Scope]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestions
        )
    }

    private fun formatAbilityUsedAsValueError(
        error: ValidationError.AbilityUsedAsValue,
        sourceCode: String?
    ): String {
        val suggestion = "Access ability methods using dot notation, like ${error.abilityName}.method_name"

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Ability Used As Value]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = suggestion
        )
    }

    private fun formatInvalidTypeApplicationError(
        error: ValidationError.InvalidTypeApplication,
        sourceCode: String?
    ): String {
        val additionalInfo = mapOf(
            "Type constructor" to error.constructor,
            "Expected arity" to error.expectedArity.toString(),
            "Actual arity" to error.actualArity.toString()
        )

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Invalid Type Application]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            additionalInfo = additionalInfo
        )
    }

    private fun formatInvalidRecursiveTypeError(
        error: ValidationError.InvalidRecursiveType,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Invalid Recursive Type]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Ensure recursive types have a base case to terminate recursion"
        )
    }

    private fun formatCircularDependencyError(
        error: ValidationError.CircularDependency,
        sourceCode: String?
    ): String {
        val cycle = error.typeNames.joinToString(" -> ")

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Circular Dependency]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            additionalInfo = mapOf("Cycle" to cycle),
            suggestion = "Break the circular dependency by introducing an intermediate type"
        )
    }

    private fun formatOrphanInstanceError(
        error: ValidationError.OrphanInstance,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Orphan Instance]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Move this implementation to the module that defines either the ability or the type"
        )
    }

    private fun formatOverlappingInstanceError(
        error: ValidationError.OverlappingInstance,
        sourceCode: String?
    ): String {
        val additionalInfo = mutableMapOf<String, String>()
        if (error.existingLocation != null) {
            additionalInfo["Existing implementation"] = error.existingLocation.toString()
        }

        return SourceContext.formatError(
            errorType = "Type Error [Validation - Overlapping Instance]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            additionalInfo = additionalInfo,
            suggestion = "Remove one of the overlapping implementations"
        )
    }

    private fun formatMissingAbilityImplementationError(
        error: ValidationError.MissingAbilityImplementation,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Missing Ability Implementation]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            additionalInfo = emptyMap(),
            suggestion = "Add an implementation: impl ${error.abilityName} ${error.typeName} where { ... }"
        )
    }

    private fun formatHandlerTypeMismatchError(
        error: ValidationError.HandlerTypeMismatch,
        sourceCode: String?
    ): String {
        val typeDiff = TypeDiffPrinter.formatDiff(error.expectedType, error.actualType, "handler type")

        return buildString {
            appendLine("❌ Type Error [Validation - Handler Type Mismatch]")
            appendLine("━".repeat(50))

            // Location
            error.location()?.let { loc ->
                val fileInfo = loc.file.ifEmpty { "<input>" }
                appendLine("   --> $fileInfo:${loc.startLine}:${loc.startCol}")
            }

            // Source code context
            error.location()?.let { loc ->
                if (sourceCode != null) {
                    append(SourceContext.formatCodeSnippet(sourceCode, loc))
                }
            }

            // Error message with type diff
            appendLine()
            appendLine("Error: ${error.message()}")
            appendLine()
            append(typeDiff)

            // Suggestions
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Use a handler that expects ${TypeDiffPrinter.formatType(error.actualType)}")
            appendLine("   - Ensure all throw calls throw values of type ${TypeDiffPrinter.formatType(error.expectedType)}")
        }.trim()
    }

    private fun formatUnitVariantPayloadMismatchError(
        error: ValidationError.UnitVariantPayloadMismatch,
        sourceCode: String?
    ): String {
        return buildString {
            appendLine("❌ Type Error [Validation - Unit Variant Payload Mismatch]")
            appendLine("━".repeat(50))

            // Location
            error.location()?.let { loc ->
                val fileInfo = loc.file.ifEmpty { "<input>" }
                appendLine("   --> $fileInfo:${loc.startLine}:${loc.startCol}")
            }

            // Source code context
            error.location()?.let { loc ->
                if (sourceCode != null) {
                    append(SourceContext.formatCodeSnippet(sourceCode, loc))
                }
            }

            // Error message
            appendLine()
            appendLine("Error: ${error.message()}")

            // Suggestions
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Remove the payload pattern from the constructor '${error.constructorName}'")
            appendLine("   - If you need to match a payload, add fields to the variant constructor")
        }.trim()
    }

    private fun formatTopLevelPerformedEffectsError(
        error: ValidationError.TopLevelPerformedEffects,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Wrap this in a function: let ${error.bindingName} = {} -> ..."
        )
    }

    private fun formatTopLevelExpressionPerformedEffectsError(
        error: ValidationError.TopLevelExpressionPerformedEffects,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "Wrap in a function: let myFunc = {} -> <expression>"
        )
    }

private fun formatStructuralError(
        error: ValidationError.StructuralError,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Structural Error",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = null
        )
    }

    private fun formatMissingContentHashError(
        error: ValidationError.MissingContentHash,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Internal Error [Missing Content Hash]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "This is a compiler bug. Please report it."
        )
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
