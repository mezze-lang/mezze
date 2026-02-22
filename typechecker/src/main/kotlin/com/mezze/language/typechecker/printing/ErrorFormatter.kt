package com.mezze.language.typechecker.printing

import com.mezze.language.ast.SourceLocation
import com.mezze.language.typechecker.*
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typechecker.module.ModuleTypeCheckResult
import com.mezze.language.typechecker.monomorphization.MonomorphizationError
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
            // Header
            appendLine("❌ Type Error [${error.phase}]")
            appendLine("━".repeat(50))

            // Location
            if (error.sourceLocation != null) {
                val fileInfo = error.sourceLocation.file ?: "<input>"
                appendLine("   --> $fileInfo:${error.sourceLocation.line}:${error.sourceLocation.column}")
            }

            // Source code context
            if (sourceCode != null && error.sourceLocation != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, error.sourceLocation))
            }

            // Error message
            appendLine()
            appendLine("Error: ${error.message}")

            // Phase-specific formatting can be added here if needed
        }.trim()
    }

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
        return when (error) {
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
            is ValidationError.MainFunctionTypeError -> formatMainFunctionTypeError(error, sourceCode)
        }
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
            is ModuleError.InternalModuleAccess -> formatModuleInternalModuleAccessError(error)
        }
    }

    /**
     * Format monomorphization error.
     */
    fun formatMonomorphizationError(
        error: MonomorphizationError,
        sourceCode: String? = null
    ): String {
        return buildString {
            appendLine("❌ Type Error [Monomorphization]")
            appendLine("━".repeat(50))

            // Location
            if (error.sourceLocation != null) {
                val fileInfo = error.sourceLocation.file ?: "<input>"
                appendLine("   --> $fileInfo:${error.sourceLocation.line}:${error.sourceLocation.column}")
            }

            // Source code context
            if (sourceCode != null && error.sourceLocation != null) {
                append(SourceContext.formatCodeSnippet(sourceCode, error.sourceLocation))
            }

            // Error message
            appendLine()
            appendLine("Error: ${error.message}")

            // Function info
            if (error.functionName != null) {
                appendLine()
                appendLine("Function: ${error.functionName}")
                if (error.typeArgs.isNotEmpty()) {
                    appendLine("Type arguments: ${error.typeArgs.entries.joinToString { "${it.key} = ${TypeDiffPrinter.formatType(it.value)}" }}")
                }
            }

            // Suggestions
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Check that the function is defined and accessible")
            appendLine("   - Verify that type arguments are concrete (not type variables)")
            appendLine("   - Ensure monomorphization is enabled")
        }.trim()
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
                val fileInfo = loc.file ?: "<input>"
                appendLine("   --> $fileInfo:${loc.line}:${loc.column}")
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
                val fileInfo = loc.file ?: "<input>"
                appendLine("   --> $fileInfo:${loc.line}:${loc.column}")
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
                val fileInfo = loc.file ?: "<input>"
                appendLine("   --> $fileInfo:${loc.line}:${loc.column}")
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
                val fileInfo = loc.file ?: "<input>"
                appendLine("   --> $fileInfo:${loc.line}:${loc.column}")
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

    private fun formatMainFunctionTypeError(
        error: ValidationError.MainFunctionTypeError,
        sourceCode: String?
    ): String {
        return SourceContext.formatError(
            errorType = "Type Error [Validation - Main Function Type]",
            message = error.message(),
            location = error.location(),
            sourceCode = sourceCode,
            suggestion = "main should be: main = {} -> perform Handler in ... where Handler handles all effects"
        )
    }

    // ========================================================================
    // MODULE ERROR FORMATTERS
    // ========================================================================

    private fun formatModuleNotFoundError(error: ModuleError.ModuleNotFound): String {
        return buildString {
            appendLine("❌ Module Error [Module Not Found]")
            appendLine("━".repeat(50))
            appendLine()
            appendLine("Error: ${error.message}")
            appendLine()
            appendLine("Searched in:")
            error.searchPaths.forEach { path ->
                appendLine("   - $path")
            }
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Check the module path spelling")
            appendLine("   - Ensure the module file exists")
            appendLine("   - Verify the module is in the search path")
        }.trim()
    }

    private fun formatModuleCircularDependencyError(error: ModuleError.CircularDependency): String {
        return buildString {
            appendLine("❌ Module Error [Circular Dependency]")
            appendLine("━".repeat(50))
            appendLine()
            appendLine("Error: ${error.message}")
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Break the circular dependency by extracting shared types to a new module")
            appendLine("   - Consider using forward declarations if supported")
        }.trim()
    }

    private fun formatModuleParseError(error: ModuleError.ParseError): String {
        return buildString {
            appendLine("❌ Module Error [Parse Error]")
            appendLine("━".repeat(50))
            appendLine()
            appendLine("Error: ${error.message}")
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Check the module syntax")
            appendLine("   - Ensure all declarations are well-formed")
        }.trim()
    }

    private fun formatModuleImportError(error: ModuleError.ImportError): String {
        return buildString {
            appendLine("❌ Module Error [Import Error]")
            appendLine("━".repeat(50))
            appendLine()
            appendLine("Error: ${error.message}")
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Verify the import path is correct")
            appendLine("   - Check that the module exports the requested item")
            appendLine("   - Ensure the imported item is public (exported)")
        }.trim()
    }

    private fun formatModuleExportNotFoundError(error: ModuleError.ExportNotFound): String {
        return buildString {
            appendLine("❌ Module Error [Export Not Found]")
            appendLine("━".repeat(50))

            // Show source location if available
            error.sourceLocation?.let { loc ->
                val fileInfo = loc.file ?: "<input>"
                appendLine("   --> $fileInfo:${loc.line}:${loc.column}")

                // Try to show source code snippet if we can
                loc.file?.let { filePath ->
                    try {
                        val file = java.io.File(filePath)
                        if (file.exists()) {
                            val lines = file.readLines()
                            if (loc.line > 0 && loc.line <= lines.size) {
                                val line = lines[loc.line - 1]
                                appendLine("    |")
                                appendLine("${loc.line.toString().padStart(3)} | $line")
                                val underlineLen = loc.length ?: 1
                                appendLine("    | ${" ".repeat(loc.column - 1)}${"^".repeat(maxOf(1, underlineLen))}")
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore file read errors
                    }
                }
                appendLine()
            }

            appendLine("Error: Export '${error.exportName}' not found in module '${error.modulePath}'")
            appendLine()
            appendLine("💡 Suggestions:")
            appendLine("   - Check the export name spelling")
            appendLine("   - Verify the module exports this item with 'pub'")
            appendLine("   - Use 'use ${error.modulePath}' to see available exports")
        }.trim()
    }

    private fun formatModuleInternalModuleAccessError(error: ModuleError.InternalModuleAccess): String {
        return buildString {
            appendLine("❌ Module Error [Internal Module Access]")
            appendLine("━".repeat(50))

            // Show source location if available
            error.sourceLocation?.let { loc ->
                appendLine()
                val fileInfo = loc.file?.let { " --> $it" } ?: ""
                appendLine("$fileInfo:${loc.line}:${loc.column}")
            }

            appendLine()
            appendLine("Error: ${error.message}")
            appendLine()

            // Extract the internal folder name for better suggestions
            val internalFolder = when {
                error.toModule.contains("::internal::") -> "internal/"
                error.toModule.contains("::private::") -> "private/"
                error.toModule.startsWith("internal::") -> "internal/"
                error.toModule.startsWith("private::") -> "private/"
                else -> "internal/"
            }

            appendLine("💡 The '$internalFolder' folder convention means this module is")
            appendLine("   only accessible from within its parent package.")
            appendLine()
            appendLine("Suggestions:")
            appendLine("   - Move the code that needs this module into the parent package")
            appendLine("   - Move the module out of the '$internalFolder' folder to make it public")
            appendLine("   - Create a public API that wraps the internal functionality")
        }.trim()
    }
}
