@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * AST nodes for pattern matching in the Mezze language.
 *
 * This file contains:
 * - Pattern: Sealed interface for all pattern types
 * - WildcardPattern: Matches anything without binding
 * - VariablePattern: Matches and binds a variable
 * - ConstructorPattern: Matches variant constructors
 * - RecordPattern: Matches record structures
 * - LiteralPattern: Matches exact literal values
 * - SeqPattern: Matches seq structures
 * - LiteralValue: Values that can appear in patterns
 * - Pattern utilities: isIrrefutable(), extractBindings(), kindDescription()
 *
 * Part of the refactored AST module - previously in MezzeAST.kt.
 */

// ============================================================================
// PATTERNS
// ============================================================================

@Serializable
@JsonClassDiscriminator("patternKind")
sealed interface Pattern

@Serializable
data class WildcardPattern(
    val dummy: Unit = Unit,
    val sourceLocation: SourceLocation? = null
) : Pattern

@Serializable
data class VariablePattern(
    val name: String,
    val sourceLocation: SourceLocation? = null
) : Pattern

@Serializable
data class ConstructorPattern(
    val constructor: String,
    val recordPattern: RecordPattern? = null,
    val sourceLocation: SourceLocation? = null
) : Pattern

@Serializable
data class RecordPattern(
    val entries: List<RecordPatternEntry>,
    val isOpen: Boolean = false,  // true if ... was used in the pattern
    val sourceLocation: SourceLocation? = null
) : Pattern

@Serializable
data class RecordPatternEntry(
    val name: String,
    val pattern: Pattern? = null,  // null for shorthand {x} instead of {x = x}
    val sourceLocation: SourceLocation? = null
)

/**
 * Literal pattern for matching exact values (Int, String, Boolean)
 * Examples: 0 -> "zero", "hello" -> "greeting", true -> "yes"
 */
@Serializable
data class LiteralPattern(
    val value: LiteralValue,
    val sourceLocation: SourceLocation? = null
) : Pattern

/**
 * Seq pattern for matching seqs
 * Examples: [] -> "empty", [x, y] -> "pair", [head, ...tail] -> "list"
 */
@Serializable
data class SeqPattern(
    val elements: List<Pattern>,       // Fixed-position patterns
    val restPattern: String? = null,   // Optional rest pattern name (...tail)
    val sourceLocation: SourceLocation? = null
) : Pattern

/**
 * String interpolation pattern for matching and extracting parts of strings.
 *
 * Examples:
 *   "hello $(name)" -> matches "hello world" with name = "world"
 *   "$(a)-$(b)-$(c)" -> matches "x-y-z" with a = "x", b = "y", c = "z"
 *   "prefix-$(x)-suffix" -> matches "prefix-foo-suffix" with x = "foo"
 *
 * Key restrictions (unlike expression string interpolation):
 *   - Only variable names allowed in captures, not expressions
 *   - Each capture binds a String value
 *   - Adjacent captures without separator are not allowed
 *
 * Matching behavior:
 *   - Literal parts must match exactly
 *   - Captures are non-greedy (left-to-right), except the last which is greedy
 *   - Escape \$( to match literal "$("
 */
@Serializable
data class StringInterpolationPattern(
    val parts: List<StringPatternPart>,
    val sourceLocation: SourceLocation? = null
) : Pattern

/**
 * A part of a string interpolation pattern - either a literal segment or a capture.
 */
@Serializable
@JsonClassDiscriminator("stringPatternPartKind")
sealed interface StringPatternPart

/**
 * A literal string segment that must match exactly.
 */
@Serializable
data class StringPatternLiteral(val value: String) : StringPatternPart

/**
 * A variable capture that binds the matched portion to a variable name.
 * The variable will have type String.
 */
@Serializable
data class StringPatternCapture(val variableName: String) : StringPatternPart

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
 * - SeqPattern: may not match if seq length doesn't match
 */
fun Pattern.isIrrefutable(): Boolean = when (this) {
    is VariablePattern -> true
    is WildcardPattern -> true
    is RecordPattern -> entries.all { entry ->
        entry.pattern?.isIrrefutable() ?: true  // null pattern = shorthand binding, always irrefutable
    }
    is ConstructorPattern -> false  // Variant constructors can fail to match
    is LiteralPattern -> false      // Literal values can fail to match
    is SeqPattern -> false          // Seq patterns can fail to match (length mismatch)
    is StringInterpolationPattern -> false  // String patterns can fail to match
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
    is SeqPattern -> {
        val elementBindings = elements.flatMap { it.extractBindings() }
        val restBinding = restPattern?.let { listOf(it) } ?: emptyList()
        elementBindings + restBinding
    }
    is StringInterpolationPattern -> parts.mapNotNull { part ->
        when (part) {
            is StringPatternCapture -> part.variableName
            is StringPatternLiteral -> null
        }
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
    is SeqPattern -> "seq pattern"
    is StringInterpolationPattern -> "string interpolation pattern"
}
