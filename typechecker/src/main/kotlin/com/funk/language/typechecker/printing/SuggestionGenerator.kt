package com.funk.language.typechecker.printing

import com.funk.language.ast.FunkType
import com.funk.language.typechecker.solving.ConstraintSolver
import com.funk.language.typechecker.validation.ValidationError

/**
 * Generates helpful suggestions for type errors.
 *
 * Analyzes error context to provide actionable suggestions that help
 * users fix their code.
 *
 * Design:
 * - Context-aware suggestions
 * - Detects common mistakes
 * - Uses Levenshtein distance for name suggestions
 * - Provides multiple suggestions when applicable
 *
 * Usage:
 * ```kotlin
 * val suggestions = SuggestionGenerator.suggestForTypeMismatch(expected, actual)
 * suggestions.forEach { println("- $it") }
 * ```
 */
object SuggestionGenerator {
    /**
     * Generate suggestions for a validation error.
     */
    fun suggestForValidationError(error: ValidationError): List<String> {
        return when (error) {
            is ValidationError.AbilityNotSatisfied -> suggestForMissingAbility(
                error.type,
                error.abilityName,
                error.availableAbilities
            )

            is ValidationError.EffectNotHandled -> listOf(
                "Add a handler for the '${error.effectName}' effect",
                "Propagate the effect in the function signature"
            )

            is ValidationError.NonExhaustiveMatch -> buildList {
                if (error.missingConstructors.isNotEmpty()) {
                    add("Add cases for: ${error.missingConstructors.joinToString()}")
                }
                add("Add a catch-all pattern: _ -> ...")
            }

            is ValidationError.UnreachablePattern -> listOf(
                "Remove the unreachable pattern",
                "Add a guard condition if this case should sometimes match"
            )

            is ValidationError.NonExhaustiveRecordPattern -> buildList {
                if (error.missingFields.isNotEmpty()) {
                    add("Add missing fields to the pattern: ${error.missingFields.joinToString()}")
                }
                add("Use {...} to explicitly ignore remaining fields")
            }

            is ValidationError.TypeMismatch -> suggestForTypeMismatch(
                error.expected,
                error.actual,
                error.context
            )

            is ValidationError.AnnotationMismatch -> listOf(
                "Remove the type annotation to let the type checker infer the type",
                "Correct the type annotation to match the inferred type: ${TypeDiffPrinter.formatType(error.inferredType)}",
                "Change the expression to match the annotated type: ${TypeDiffPrinter.formatType(error.annotatedType)}"
            )

            is ValidationError.FieldNotFound -> suggestForUndefinedVariable(
                error.fieldName,
                error.availableFields
            )

            is ValidationError.ConstructorNotFound -> suggestForUndefinedVariable(
                error.constructorName,
                error.availableConstructors
            )

            is ValidationError.VariableNotInScope -> suggestForUndefinedVariable(
                error.variableName,
                error.similarNames
            )

            is ValidationError.AbilityUsedAsValue -> listOf(
                "Access ability methods using dot notation: ${error.abilityName}.method_name",
                "Check available methods for this ability"
            )

            is ValidationError.InvalidTypeApplication -> listOf(
                "Provide exactly ${error.expectedArity} type argument(s) to '${error.constructor}'",
                "Check the type constructor definition"
            )

            is ValidationError.InvalidRecursiveType -> listOf(
                "Ensure recursive types have a base case",
                "Add a variant constructor that doesn't reference the type"
            )

            is ValidationError.CircularDependency -> listOf(
                "Break the circular dependency by introducing an intermediate type",
                "Reorder type definitions to avoid forward references"
            )

            is ValidationError.OrphanInstance -> listOf(
                "Move this implementation to the module that defines the ability",
                "Move this implementation to the module that defines the type"
            )

            is ValidationError.OverlappingInstance -> listOf(
                "Remove one of the overlapping implementations",
                "Check if this ability is already implemented elsewhere"
            )

            is ValidationError.MissingAbilityImplementation -> listOf(
                "Add an implementation: impl ${error.abilityName} ${error.typeName} where { ... }",
                "Import a module that provides this implementation"
            )

            is ValidationError.HandlerTypeMismatch -> listOf(
                "Use a handler that expects ${TypeDiffPrinter.formatType(error.actualType)}",
                "Ensure all throw calls in the expression throw values of type ${TypeDiffPrinter.formatType(error.expectedType)}",
                "Check the handler's associated type binding"
            )

            is ValidationError.UnitVariantPayloadMismatch -> listOf(
                "Remove the payload pattern from constructor '${error.constructorName}'",
                "If you need to match a payload, add fields to the variant constructor definition"
            )

            is ValidationError.TopLevelPerformedEffects -> listOf(
                "Wrap the value in a function: let ${error.bindingName} = {} -> ...",
                "Move the effectful computation inside main or another function"
            )

            is ValidationError.TopLevelExpressionPerformedEffects -> listOf(
                "Wrap in a function: let myFunc = {} -> <expression>",
                "Or move inside an existing function body"
            )

            is ValidationError.MainFunctionTypeError -> listOf(
                "main must be: main = {} -> perform Handler in ...",
                "Use 'perform' to handle all effects in main",
                "Ensure main returns {} (empty record)"
            )
        }
    }

    /**
     * Generate suggestions for a type mismatch.
     */
    fun suggestForTypeMismatch(
        expected: FunkType,
        actual: FunkType,
        context: String?
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Common numeric type mismatches
        if (isNumericType(expected) && isNumericType(actual)) {
            suggestions.add("Convert ${formatSimpleType(actual)} to ${formatSimpleType(expected)} using an explicit conversion")
        }

        // String vs numeric
        if (expected is FunkType.StringType && isNumericType(actual)) {
            suggestions.add("Convert number to String using: toString(${formatSimpleType(actual).lowercase()}Value)")
        }
        if (isNumericType(expected) && actual is FunkType.StringType) {
            suggestions.add("Parse String to ${formatSimpleType(expected)} using: parse${formatSimpleType(expected)}(stringValue)")
        }

        // Record type mismatches
        if (expected is FunkType.RecordType && actual is FunkType.RecordType) {
            suggestions.addAll(suggestForRecordMismatch(expected, actual))
        }

        // Function type mismatches
        if (expected is FunkType.FunctionType && actual is FunkType.FunctionType) {
            suggestions.addAll(suggestForFunctionMismatch(expected, actual))
        }

        // Generic suggestions
        if (suggestions.isEmpty()) {
            suggestions.add("Add explicit type annotation to clarify intent")
            suggestions.add("Check that the value matches the expected type")
        }

        return suggestions
    }

    /**
     * Generate suggestions for missing ability.
     */
    fun suggestForMissingAbility(
        type: FunkType,
        abilityName: String,
        availableAbilities: List<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Suggest implementing the ability
        suggestions.add("Implement the '$abilityName' ability for type ${TypeDiffPrinter.formatType(type)}")

        // Check if it's a custom type that needs an impl
        if (type is FunkType.TypeApplication || type is FunkType.RecordType || type is FunkType.VariantType) {
            suggestions.add("Add: impl $abilityName ${getTypeName(type)} { ... }")
        }

        // Suggest similar abilities if available
        if (availableAbilities.isNotEmpty()) {
            val similar = findSimilar(abilityName, availableAbilities, maxDistance = 3)
            if (similar.isNotEmpty()) {
                suggestions.add("Did you mean: ${similar.joinToString(" or ")}")
            }
        }

        // Specific ability suggestions
        when (abilityName) {
            "Eq" -> suggestions.add("Implement equality comparison for this type")
            "Ord" -> suggestions.add("Implement ordering comparison (requires Eq)")
            "Show" -> suggestions.add("Implement string representation for this type")
            "Num" -> suggestions.add("Implement numeric operations for this type")
            "Functor" -> suggestions.add("Implement map operation for this type")
        }

        return suggestions
    }

    /**
     * Generate suggestions for undefined variable.
     */
    fun suggestForUndefinedVariable(
        variableName: String,
        similarNames: List<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Find similar names
        if (similarNames.isNotEmpty()) {
            val similar = findSimilar(variableName, similarNames)
            if (similar.isNotEmpty()) {
                suggestions.add("Did you mean: ${similar.joinToString(" or ")}")
            } else if (similarNames.size <= 5) {
                suggestions.add("Available: ${similarNames.joinToString(", ")}")
            }
        }

        // Generic suggestions
        suggestions.add("Check the name spelling")
        suggestions.add("Ensure the item is declared before use")
        suggestions.add("For imported items, use 'use module_name'")

        return suggestions
    }

    /**
     * Generate suggestions for solver errors.
     */
    fun suggestForSolverError(error: ConstraintSolver.ConstraintError): List<String> {
        val suggestions = mutableListOf<String>()

        // Parse the error message for context
        val message = error.message.lowercase()

        when {
            "type mismatch" in message || "not compatible" in message -> {
                suggestions.add("Ensure both sides of the expression have compatible types")
                suggestions.add("Add explicit type annotations to clarify intent")
            }

            "arity" in message -> {
                suggestions.add("Check that the function is called with the correct number of arguments")
                suggestions.add("Use a record for multiple arguments: func {x = 1, y = 2}")
            }

            "row" in message && "field" in message -> {
                suggestions.add("Ensure all required record fields are present")
                suggestions.add("Check field names for typos")
            }

            "effect" in message -> {
                suggestions.add("Handle the effect with a handler")
                suggestions.add("Propagate the effect in the function signature")
            }

            else -> {
                suggestions.add("Add explicit type annotations")
                suggestions.add("Check the types involved in the constraint")
            }
        }

        return suggestions
    }

    // ========================================================================
    // HELPER FUNCTIONS
    // ========================================================================

    /**
     * Find similar names using Levenshtein distance.
     */
    private fun findSimilar(
        target: String,
        candidates: List<String>,
        maxDistance: Int = 2
    ): List<String> {
        return candidates
            .map { it to levenshteinDistance(target, it) }
            .filter { (_, distance) -> distance <= maxDistance }
            .sortedBy { (_, distance) -> distance }
            .map { (name, _) -> name }
            .take(3) // Show at most 3 suggestions
    }

    /**
     * Calculate Levenshtein distance between two strings.
     *
     * This is the minimum number of single-character edits (insertions,
     * deletions, or substitutions) required to change one string into the other.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Create a 2D array for dynamic programming
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Initialize base cases
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        // Fill the DP table
        for (i in 1..len1) {
            for (j in 1..len2) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1] // No operation needed
                } else {
                    1 + minOf(
                        dp[i - 1][j],     // Deletion
                        dp[i][j - 1],     // Insertion
                        dp[i - 1][j - 1]  // Substitution
                    )
                }
            }
        }

        return dp[len1][len2]
    }

    private fun suggestForRecordMismatch(
        expected: FunkType.RecordType,
        actual: FunkType.RecordType
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Find missing fields
        val missingFields = expected.fields.keys - actual.fields.keys
        if (missingFields.isNotEmpty()) {
            suggestions.add("Add missing field(s): ${missingFields.joinToString()}")
        }

        // Find extra fields
        val extraFields = actual.fields.keys - expected.fields.keys
        if (extraFields.isNotEmpty()) {
            suggestions.add("Remove extra field(s): ${extraFields.joinToString()}")
        }

        // Find fields with wrong types
        for (field in expected.fields.keys.intersect(actual.fields.keys)) {
            val expectedType = expected.fields[field]
            val actualType = actual.fields[field]
            if (expectedType != actualType) {
                suggestions.add("Change field '$field' from ${TypeDiffPrinter.formatType(actualType!!)} to ${TypeDiffPrinter.formatType(expectedType!!)}")
            }
        }

        return suggestions
    }

    private fun suggestForFunctionMismatch(
        expected: FunkType.FunctionType,
        actual: FunkType.FunctionType
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Parameter type mismatch
        if (expected.paramType != actual.paramType) {
            suggestions.add("Ensure parameter type matches: expected ${TypeDiffPrinter.formatType(expected.paramType)}")
        }

        // Return type mismatch
        if (expected.returnType != actual.returnType) {
            suggestions.add("Ensure return type matches: expected ${TypeDiffPrinter.formatType(expected.returnType)}")
        }

        // Effect mismatch
        if (expected.effects != actual.effects) {
            val effectStr = expected.effects?.let { TypeDiffPrinter.formatType(it) } ?: "<pure>"
            suggestions.add("Ensure effects match: expected $effectStr")
        }

        return suggestions
    }

    private fun isNumericType(type: FunkType): Boolean {
        return type is FunkType.IntType ||
                type is FunkType.FloatType ||
                type is FunkType.NatType ||
                type is FunkType.FracType
    }

    private fun formatSimpleType(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            else -> TypeDiffPrinter.formatType(type)
        }
    }

    private fun getTypeName(type: FunkType): String {
        return when (type) {
            is FunkType.TypeApplication -> type.constructor
            is FunkType.RecordType -> "Record" // For anonymous records, suggest a name
            is FunkType.VariantType -> "Variant" // For anonymous variants, suggest a name
            else -> TypeDiffPrinter.formatType(type)
        }
    }
}
