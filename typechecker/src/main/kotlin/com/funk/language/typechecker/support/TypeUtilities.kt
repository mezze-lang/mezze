package com.funk.language.typechecker.support

import com.funk.language.ast.*

/**
 * Utility functions for type operations, predicates, and analysis.
 *
 * This file contains helper functions extracted from TypeChecker.kt that perform
 * common operations on types and expressions. These utilities are used by the
 * inference, validation, and resolution engines.
 *
 * # Categories
 *
 * - **Type Predicates**: Check type properties (isResultType, isNumericType, etc.)
 * - **Type Extraction**: Extract components from complex types (extractErrorType, etc.)
 * - **Type Operations**: Merge and transform types (mergeVariantTypes, etc.)
 * - **Expression Analysis**: Analyze expressions for type information
 * - **Miscellaneous**: Various helper functions
 */

/**
 * Checks if a type is a Result type.
 *
 * Handles TypeApplication and SuspendedType recursively.
 *
 * @param type The type to check
 * @return true if the type is a Result type
 */
fun isResultType(type: FunkType): Boolean {
    return when (type) {
        is FunkType.TypeApplication -> type.constructor == "Result"
        is FunkType.SuspendedType -> isResultType(type.valueType)
        else -> false
    }
}

/**
 * Checks if a type is numeric (Int, Float, Nat, Frac).
 *
 * Includes numeric types within SuspendedType.
 *
 * @param type The type to check
 * @return true if the type is numeric
 */
fun isNumericType(type: FunkType): Boolean {
    return when (type) {
        is FunkType.IntType, is FunkType.FloatType,
        is FunkType.NatType, is FunkType.FracType -> true
        is FunkType.SuspendedType -> isNumericType(type.valueType)
        else -> false
    }
}

/**
 * Checks if a type is a concrete numeric type (without SuspendedType wrapping).
 *
 * @param type The type to check
 * @return true if the type is a concrete numeric type
 */
fun isConcreteNumericType(type: FunkType): Boolean {
    return when (type) {
        is FunkType.IntType, is FunkType.FloatType,
        is FunkType.NatType, is FunkType.FracType -> true
        else -> false
    }
}

/**
 * Recursively checks if a type contains concrete types (vs purely polymorphic type variables).
 *
 * @param type The type to check
 * @return true if the type contains any concrete types
 */
fun containsConcreteTypes(type: FunkType): Boolean {
    return when (type) {
        // Base concrete types
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType -> true

        // Type variables are not concrete
        is FunkType.VarType -> false

        // Recursive cases
        is FunkType.FunctionType ->
            containsConcreteTypes(type.paramType) || containsConcreteTypes(type.returnType)
        is FunkType.RecordType ->
            type.fields.values.any { containsConcreteTypes(it) }
        is FunkType.VariantType ->
            type.constructors.values.any { it != null && containsConcreteTypes(it) }
        is FunkType.ListType ->
            containsConcreteTypes(type.elementType)
        is FunkType.ArrayType ->
            containsConcreteTypes(type.elementType)
        is FunkType.MapType ->
            containsConcreteTypes(type.keyType) || containsConcreteTypes(type.valueType)
        is FunkType.SetType ->
            containsConcreteTypes(type.elementType)
        is FunkType.TypeApplication ->
            type.args.any { containsConcreteTypes(it) }
        is FunkType.SuspendedType ->
            containsConcreteTypes(type.valueType)
        is FunkType.EffectRow -> false
    }
}

/**
 * Extracts the error type from a Result type.
 *
 * Result is TypeApplication("Result", [valueType, errorType])
 *
 * @param resultType The Result type
 * @return The error type (second argument) if this is a Result, null otherwise
 */
fun extractErrorType(resultType: FunkType): FunkType? {
    return when (resultType) {
        is FunkType.TypeApplication -> {
            if (resultType.constructor == "Result" && resultType.args.size == 2) {
                resultType.args[1]  // error type is second arg
            } else null
        }
        is FunkType.SuspendedType -> extractErrorType(resultType.valueType)
        else -> null
    }
}

/**
 * Extracts the value type from a Result type.
 *
 * Result is TypeApplication("Result", [valueType, errorType])
 *
 * @param resultType The Result type
 * @return The value type (first argument) if this is a Result, null otherwise
 */
fun extractValueType(resultType: FunkType): FunkType? {
    return when (resultType) {
        is FunkType.TypeApplication -> {
            if (resultType.constructor == "Result" && resultType.args.size == 2) {
                resultType.args[0]  // value type is first arg
            } else null
        }
        is FunkType.SuspendedType -> extractValueType(resultType.valueType)
        else -> null
    }
}

/**
 * Recursively unwraps SuspendedType layers to get the underlying value type.
 *
 * @param type The type to unwrap
 * @return The unwrapped value type
 */
fun unwrapSuspendedType(type: FunkType): FunkType {
    return when (type) {
        is FunkType.SuspendedType -> unwrapSuspendedType(type.valueType)
        else -> type
    }
}

/**
 * Merges two variant types by combining their constructors.
 *
 * Used for automatic error type merging in Result composition.
 *
 * @param type1 The first variant type
 * @param type2 The second variant type
 * @return A merged variant type with all constructors from both types
 */
fun mergeVariantTypes(
    type1: FunkType.VariantType,
    type2: FunkType.VariantType
): FunkType.VariantType {
    // Union all constructors
    val merged = mutableMapOf<String, FunkType?>()
    merged.putAll(type1.constructors)

    // Check for conflicts and merge
    type2.constructors.forEach { (name, type) ->
        val existing = merged[name]
        if (existing != null && existing != type) {
            // Constructor conflict - same name, different types
            // Keep the first one (could add constraint instead)
        } else {
            merged[name] = type
        }
    }

    // Determine openness - if either is open, result is open
    val rowVar = type1.rowVar ?: type2.rowVar

    return FunkType.VariantType(merged.toMap(), rowVar)
}

/**
 * Merges effects from two types using effect row polymorphism.
 *
 * This enables match and if-else expressions with branches having different effects
 * to unify correctly using effect row polymorphism.
 *
 * Rules:
 * 1. null ∪ null = null (both pure)
 * 2. null ∪ effects = effects (pure ∪ effectful = effectful)
 * 3. effects ∪ null = effects (effectful ∪ pure = effectful)
 * 4. effects1 ∪ effects2 = effects1 ∪ effects2 (union of effect sets)
 *
 * @param type1 The first type
 * @param type2 The second type
 * @return A type with merged effects
 */
fun mergeEffectsInTypes(
    type1: FunkType,
    type2: FunkType
): FunkType {
    // Extract value types and effects from both types
    val (value1, effects1) = when (type1) {
        is FunkType.SuspendedType -> Pair(type1.valueType, type1.effects)
        is FunkType.FunctionType -> Pair(type1, type1.effects)
        else -> Pair(type1, null)
    }

    val (value2, effects2) = when (type2) {
        is FunkType.SuspendedType -> Pair(type2.valueType, type2.effects)
        is FunkType.FunctionType -> Pair(type2, type2.effects)
        else -> Pair(type2, null)
    }

    // Merge effects according to effect row polymorphism rules
    val mergedEffects = when {
        effects1 == null && effects2 == null -> null  // Both pure
        effects1 == null -> effects2  // Pure ∪ effectful = effectful
        effects2 == null -> effects1  // Effectful ∪ pure = effectful
        else -> {
            // Union of effects
            val unionEffects = effects1.effects.union(effects2.effects)
            val mergedRowVar = effects1.rowVar ?: effects2.rowVar  // Keep open if either is open
            FunkType.EffectRow(unionEffects, mergedRowVar)
        }
    }

    // Return the first value type with merged effects
    return when {
        mergedEffects != null && value1 is FunkType.FunctionType ->
            FunkType.FunctionType(value1.paramType, value1.returnType, mergedEffects, value1.abilityConstraints)
        mergedEffects != null ->
            FunkType.SuspendedType(value1, mergedEffects)
        else -> value1
    }
}

/**
 * Recursively checks if a type contains type variables.
 *
 * This searches through the type structure to determine if any type variables (VarType) are present.
 * Used to distinguish polymorphic types from concrete types.
 *
 * @param type The type to check
 * @return true if the type contains any type variables
 */
fun isTypeVariable(type: FunkType): Boolean {
    return when (type) {
        is FunkType.VarType -> true
        is FunkType.FunctionType ->
            isTypeVariable(type.paramType) || isTypeVariable(type.returnType)
        is FunkType.RecordType ->
            type.fields.values.any { isTypeVariable(it) }
        is FunkType.TypeApplication ->
            type.args.any { isTypeVariable(it) }
        is FunkType.ArrayType ->
            isTypeVariable(type.elementType)
        is FunkType.MapType ->
            isTypeVariable(type.keyType) || isTypeVariable(type.valueType)
        is FunkType.SetType ->
            isTypeVariable(type.elementType)
        is FunkType.ListType ->
            isTypeVariable(type.elementType)
        is FunkType.VariantType ->
            type.constructors.values.any { it != null && isTypeVariable(it) }
        is FunkType.SuspendedType ->
            isTypeVariable(type.valueType)
        else -> false
    }
}

// ============================================================================
// String Utilities for Error Reporting
// ============================================================================

/**
 * Calculate the Levenshtein (edit) distance between two strings.
 *
 * The Levenshtein distance is the minimum number of single-character edits
 * (insertions, deletions, or substitutions) needed to change one string into another.
 *
 * Used for generating "did you mean?" suggestions in error messages.
 *
 * @param s1 First string
 * @param s2 Second string
 * @return The edit distance between s1 and s2
 */
fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j

    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
    }

    return dp[s1.length][s2.length]
}

/**
 * Find the closest matching name from a collection of candidates.
 *
 * Uses Levenshtein distance to find the candidate that is most similar
 * to the input string. Case-insensitive comparison.
 *
 * Used for generating helpful "did you mean X?" error messages.
 *
 * @param input The input string to match
 * @param candidates Collection of candidate strings
 * @return The closest matching candidate, or null if candidates is empty
 */
fun findClosestName(input: String, candidates: Collection<String>): String? {
    if (candidates.isEmpty()) return null
    return candidates
        .map { it to levenshteinDistance(input.lowercase(), it.lowercase()) }
        .minByOrNull { it.second }
        ?.first
}

/**
 * Format a suggestion message for error reporting.
 *
 * Creates a helpful error message fragment that includes:
 * - A "did you mean X?" suggestion if a close match exists
 * - A list of all available options
 *
 * @param input The incorrect input that triggered the error
 * @param availableNames Collection of valid names
 * @param itemType The type of item (e.g., "variable", "function", "type")
 * @return A formatted suggestion string
 */
fun formatSuggestionMessage(input: String, availableNames: Collection<String>, itemType: String): String {
    val closest = findClosestName(input, availableNames)
    val availableList = availableNames.sorted().joinToString(", ")

    return buildString {
        if (closest != null) {
            append("\n\nDid you mean '$closest'?")
        }
        append("\n\nAvailable ${itemType}s: $availableList")
    }
}

// Note: Additional expression analysis utilities (containsRecursiveReference, containsArithmeticOperations, etc.)
// are kept in TypeChecker.kt for now to avoid complex expression traversal code duplication.
// These can be extracted in a future iteration once the main engine extraction is complete.
