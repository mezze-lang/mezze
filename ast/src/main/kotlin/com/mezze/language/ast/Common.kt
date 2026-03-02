@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Common types and utilities shared across all AST nodes.
 *
 * This file contains:
 * - TypeVar: Type variables for polymorphism
 * - RowVar: Row variables for extensible records/variants
 * - SourceLocation: Source code location tracking
 * - SyntaxMetadata: Operator desugaring metadata
 * - Visibility: PUBLIC/PRIVATE visibility modifiers
 * - UsePath: Module system import paths
 */

// ============================================================================
// TYPE VARIABLES
// ============================================================================

/**
 * Type variables for polymorphic type inference.
 *
 * Type variables represent unknown types during type checking.
 * Examples: 'a, 'b, 'c in polymorphic functions like {x: 'a} -> 'a
 *
 * The level tracks type variable scoping for let-polymorphism.
 * The id provides unique identity for each fresh type variable.
 */
@Serializable
data class TypeVar(val name: String, val level: Int = 0, val id: Int = 0) {
    companion object {
        private var globalCounter = 0
        private var currentLevel = 0
        private val scopeCounters = mutableMapOf<Int, Int>()

        fun fresh(level: Int = currentLevel): TypeVar {
            // Get or initialize counter for this level, starting from 0
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }

            // Generate name: 'a', 'b', ..., 'z', 'a1', 'b1', ..., 'z1', 'a2', etc.
            val letterIndex = scopeCounter % 26
            val suffix = if (scopeCounter >= 26) (scopeCounter / 26).toString() else ""
            val name = "'${('a' + letterIndex)}$suffix"

            // Increment counters
            scopeCounters[level] = scopeCounter + 1
            val id = globalCounter++

            return TypeVar(name, level, id)
        }

        fun freshNamed(baseName: String, level: Int = currentLevel): TypeVar {
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }
            val name = "'${baseName}${scopeCounter}"
            scopeCounters[level] = scopeCounter + 1
            val id = globalCounter++
            return TypeVar(name, level, id)
        }

        /**
         * Create a TypeVar with the given name but a unique ID from the global counter.
         * This is useful for type annotations where we want to preserve the user's name
         * but still need unique identity to avoid collision with inference variables.
         */
        fun freshWithName(name: String, level: Int = currentLevel): TypeVar {
            val id = globalCounter++
            return TypeVar(name, level, id)
        }

        fun resetCounter() {
            globalCounter = 0
            currentLevel = 0
            scopeCounters.clear()
        }

        fun resetScope(level: Int = 0) {
            // Reset counter for a specific scope level (useful for per-function reset)
            scopeCounters[level] = 0
        }

        fun enterScope(): Int {
            currentLevel++
            // Each new scope starts fresh with 'a
            scopeCounters[currentLevel] = 0
            return currentLevel
        }

        fun exitScope() {
            if (currentLevel > 0) {
                scopeCounters.remove(currentLevel)
                currentLevel--
            }
        }

        fun getCurrentLevel(): Int = currentLevel

        fun resetScopeCounter(level: Int = currentLevel) {
            scopeCounters[level] = 0
        }
    }
}

/**
 * Row variables for extensible records and variants (row polymorphism).
 *
 * Row variables allow "open" records and variants that can be extended with additional fields/constructors.
 * Examples:
 * - {x: Int, y: Int, ...} - open record type
 * - | Some, None, ... | - open variant type
 */
@Serializable
data class RowVar(val name: String) {
    companion object {
        private var counter = 0

        fun fresh(): RowVar {
            val name = "row${counter}"
            counter++
            return RowVar(name)
        }
    }
}

// ============================================================================
// SOURCE LOCATION TRACKING
// ============================================================================

/**
 * Source location for error reporting.
 *
 * Tracks the location of AST nodes in the original source code for precise error messages.
 * The length field indicates the span of the token/construct for underlining in error messages.
 */
@Serializable
data class SourceLocation(
    val line: Int,
    val column: Int,
    val file: String? = null,
    val length: Int? = null
) {
    override fun toString(): String = if (file != null) "$file:$line:$column" else "$line:$column"
}

// ============================================================================
// SYNTAX METADATA (Not part of AST, not part of content hash)
// ============================================================================

/**
 * Tracks syntax metadata for desugared constructs to improve error messages.
 *
 * This is NOT part of the AST and NOT included in content hashing.
 * It's used solely to provide better error messages by remembering the
 * original syntax that was desugared during parsing.
 *
 * For example:
 * - User writes: `-x`
 * - Parser desugars to: `NumSub.neg {n = x}`
 * - Metadata records: "This came from operator `-`"
 * - Error messages can say: "Type error in operator `-`" instead of "Type error in call to NumSub.neg"
 *
 * ## Thread Safety
 *
 * **THREAD-SAFE**: Uses ThreadLocal storage so each thread has its own metadata map.
 * This allows parallel parsing of multiple files without race conditions.
 */
object SyntaxMetadata {
    // Each thread gets its own metadata map - prevents race conditions
    private val operatorMetadata = ThreadLocal.withInitial<MutableMap<SourceLocation, String>> {
        mutableMapOf()
    }

    /**
     * Mark an expression as coming from operator syntax.
     * @param location Source location of the expression
     * @param operatorSyntax The operator that was desugared (e.g., "-", "+", "!")
     */
    fun markAsOperator(location: SourceLocation, operatorSyntax: String) {
        operatorMetadata.get()[location] = operatorSyntax
    }

    /**
     * Get the operator syntax for an expression if it was desugared from an operator.
     * @param location Source location of the expression
     * @return The operator syntax, or null if not from an operator
     */
    fun getOperatorSyntax(location: SourceLocation): String? {
        return operatorMetadata.get()[location]
    }

    /**
     * Clear all metadata for the current thread. Typically called when starting a new parse.
     */
    fun clear() {
        operatorMetadata.get().clear()
    }

    /**
     * Get the number of tracked metadata entries for the current thread (for debugging/testing)
     */
    fun size(): Int = operatorMetadata.get().size

    /**
     * Remove the ThreadLocal for the current thread to prevent memory leaks.
     * Call this in long-running servers after parse operations are complete.
     */
    fun cleanup() {
        operatorMetadata.remove()
    }
}

// ============================================================================
// MODULE SYSTEM
// ============================================================================

/**
 * Visibility modifier for declarations.
 */
@Serializable
enum class Visibility {
    PUBLIC,    // pub - visible to other modules
    PRIVATE    // default - only visible within module
}

/**
 * Use path types for import declarations.
 */
@Serializable
@JsonClassDiscriminator("usePathKind")
sealed interface UsePath

/**
 * Module path for simple imports.
 * Example: math::geometry
 *
 * Used throughout the system for structured module path representation.
 * Eliminates need for string parsing like `path.split("::")`.
 */
@Serializable
data class ModulePath(val segments: List<String>) : UsePath {
    /** Full qualified path string (e.g., "std::collections::array") */
    val fullPath: String get() = segments.joinToString("::")

    /** Short name - last segment (e.g., "array" from "std::collections::array") */
    val shortName: String get() = segments.lastOrNull() ?: ""

    /** Check if this is a qualified (multi-segment) path */
    val isQualified: Boolean get() = segments.size > 1

    /** Parent module path (e.g., "std::collections" from "std::collections::array") */
    val parent: ModulePath? get() = if (segments.size > 1) {
        ModulePath(segments.dropLast(1))
    } else null

    companion object {
        /** Parse a path string into ModulePath */
        fun parse(path: String): ModulePath = ModulePath(path.split("::"))

        /** Empty module path */
        val EMPTY = ModulePath(emptyList())
    }
}

/**
 * Selective path for importing specific items from a module.
 * Example: math::geometry::{area, Shape as alias}
 */
@Serializable
data class SelectivePath(
    val module: List<String>,
    val items: List<ImportItem>
) : UsePath

/**
 * Individual item in a selective import.
 */
@Serializable
data class ImportItem(
    val name: String,
    val alias: String? = null
)
