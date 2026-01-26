@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.funk.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * AST nodes for pattern matching in the Funk language.
 *
 * This file contains:
 * - Pattern: Sealed interface for all pattern types
 * - WildcardPattern: Matches anything without binding
 * - VariablePattern: Matches and binds a variable
 * - ConstructorPattern: Matches variant constructors
 * - RecordPattern: Matches record structures
 * - LiteralPattern: Matches exact literal values
 * - ArrayPattern: Matches array structures
 * - LiteralValue: Values that can appear in patterns
 * - Pattern utilities: isIrrefutable(), extractBindings(), kindDescription()
 *
 * Part of the refactored AST module - previously in FunkAST.kt.
 */

// ============================================================================
// PATTERNS
// ============================================================================

@Serializable
@JsonClassDiscriminator("patternKind")
sealed interface Pattern

@Serializable
data class WildcardPattern(
    val dummy: Unit = Unit
) : Pattern

@Serializable
data class VariablePattern(
    val name: String
) : Pattern

@Serializable
data class ConstructorPattern(
    val constructor: String,
    val recordPattern: RecordPattern? = null
) : Pattern

@Serializable
data class RecordPattern(
    val entries: List<RecordPatternEntry>,
    val isOpen: Boolean = false  // true if ... was used in the pattern
) : Pattern

@Serializable
data class RecordPatternEntry(
    val name: String,
    val pattern: Pattern? = null  // null for shorthand {x} instead of {x = x}
)

/**
 * Literal pattern for matching exact values (Int, String, Boolean)
 * Examples: 0 -> "zero", "hello" -> "greeting", true -> "yes"
 */
@Serializable
data class LiteralPattern(
    val value: LiteralValue
) : Pattern

/**
 * Array pattern for matching arrays
 * Examples: [] -> "empty", [x, y] -> "pair", [head, ...tail] -> "list"
 */
@Serializable
data class ArrayPattern(
    val elements: List<Pattern>,       // Fixed-position patterns
    val restPattern: String? = null    // Optional rest pattern name (...tail)
) : Pattern

/**
 * Represents literal values that can appear in patterns
 */
@Serializable
@JsonClassDiscriminator("literalKind")
sealed interface LiteralValue

@Serializable
data class IntLiteralValue(val value: Long) : LiteralValue

@Serializable
data class NatLiteralValue(val value: Long) : LiteralValue

@Serializable
data class FloatLiteralValue(val value: Double) : LiteralValue

@Serializable
data class StringLiteralValue(val value: String) : LiteralValue

@Serializable
data class BooleanLiteralValue(val value: Boolean) : LiteralValue

// ============================================================================
// PATTERN UTILITIES
// ============================================================================

/**
 * Check if a pattern is irrefutable (always matches).
 *
 * Irrefutable patterns:
 * - VariablePattern: always matches and binds the value
 * - WildcardPattern: always matches but doesn't bind
 * - RecordPattern: always matches if all nested patterns are irrefutable
 *
 * Refutable patterns (can fail to match):
 * - ConstructorPattern: may not match if wrong constructor
 * - LiteralPattern: may not match if wrong value
 * - ArrayPattern: may not match if array length doesn't match
 */
fun Pattern.isIrrefutable(): Boolean = when (this) {
    is VariablePattern -> true
    is WildcardPattern -> true
    is RecordPattern -> entries.all { entry ->
        entry.pattern?.isIrrefutable() ?: true  // null pattern = shorthand binding, always irrefutable
    }
    is ConstructorPattern -> false  // Variant constructors can fail to match
    is LiteralPattern -> false      // Literal values can fail to match
    is ArrayPattern -> false        // Array patterns can fail to match (length mismatch)
}

/**
 * Extract all variable bindings from a pattern.
 * Returns list of (name, nested_path) pairs where nested_path is used for record access.
 */
fun Pattern.extractBindings(): List<String> = when (this) {
    is VariablePattern -> listOf(name)
    is WildcardPattern -> emptyList()
    is RecordPattern -> entries.flatMap { entry ->
        val nested = entry.pattern?.extractBindings() ?: listOf(entry.name)
        nested
    }
    is ConstructorPattern -> recordPattern?.extractBindings() ?: emptyList()
    is LiteralPattern -> emptyList()
    is ArrayPattern -> {
        val elementBindings = elements.flatMap { it.extractBindings() }
        val restBinding = restPattern?.let { listOf(it) } ?: emptyList()
        elementBindings + restBinding
    }
}

/**
 * Get a human-readable description of the pattern kind for error messages.
 */
fun Pattern.kindDescription(): String = when (this) {
    is VariablePattern -> "variable pattern"
    is WildcardPattern -> "wildcard pattern"
    is RecordPattern -> "record pattern"
    is ConstructorPattern -> "constructor pattern"
    is LiteralPattern -> "literal pattern"
    is ArrayPattern -> "array pattern"
}
