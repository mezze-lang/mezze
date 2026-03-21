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

        /** Save TypeVar counter state so it can be restored after a side operation (e.g., prelude loading). */
        data class State(val globalCounter: Int, val currentLevel: Int, val scopeCounters: Map<Int, Int>)

        fun saveState(): State = State(globalCounter, currentLevel, scopeCounters.toMap())

        fun restoreState(state: State) {
            globalCounter = state.globalCounter
            currentLevel = state.currentLevel
            scopeCounters.clear()
            scopeCounters.putAll(state.scopeCounters)
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

        /**
         * Create a fresh row variable with an auto-generated name.
         * These may be closed by closeGeneratedRowVars during type inference finalization.
         */
        fun fresh(): RowVar {
            val name = "row${counter}"
            counter++
            return RowVar(name)
        }

        /**
         * Create a user-specified open row variable.
         * These have a "userOpen_" prefix and will NOT be closed by closeGeneratedRowVars,
         * preserving user-specified openness in type annotations like {x: Int, ...}.
         */
        fun userOpen(): RowVar {
            val name = "userOpen_row${counter}"
            counter++
            return RowVar(name)
        }
    }

    /**
     * Whether this row variable was explicitly specified by the user (won't be auto-closed).
     */
    fun isUserSpecified(): Boolean = name.startsWith("userOpen_")
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
    /**
     * Full qualified path string (e.g., "std::collections::seq").
     * Use for display/error messages and module loader API calls.
     * For programmatic path operations, prefer using segments directly.
     */
    val fullPath: String get() = segments.joinToString("::")

    /** Short name - last segment (e.g., "array" from "std::collections::seq") */
    val shortName: String get() = segments.lastOrNull() ?: ""

    /** Check if this is a qualified (multi-segment) path */
    val isQualified: Boolean get() = segments.size > 1

    /** Parent module path (e.g., "std::collections" from "std::collections::seq") */
    val parent: ModulePath? get() = if (segments.size > 1) {
        ModulePath(segments.dropLast(1))
    } else null

    /**
     * Check if this path ends with the given suffix path.
     * Example: ModulePath(["std", "int"]).endsWith(ModulePath(["int"])) == true
     * This is the structured replacement for string-based endsWith("::$name")
     */
    fun endsWith(suffix: ModulePath): Boolean {
        if (suffix.segments.size > segments.size) return false
        val offset = segments.size - suffix.segments.size
        return suffix.segments.withIndex().all { (i, segment) ->
            segments[offset + i] == segment
        }
    }

    companion object {
        /**
         * Parse a path string into ModulePath.
         * This is the ONLY place that should parse :: from strings (except the parser).
         * Used for legacy API compatibility and reading from configuration.
         */
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
 * Kind of imported item - determines whether the import needs runtime binding.
 */
@Serializable
enum class ImportItemKind {
    /** Value imports (functions, constructors) need runtime binding */
    VALUE,
    /** Type imports are compile-time only, no runtime binding needed */
    TYPE,
    /** Ability imports are compile-time only, no runtime binding needed */
    ABILITY,
    /** Effect imports are compile-time only, no runtime binding needed */
    EFFECT
}

/**
 * Individual item in a selective import.
 * The contentHash is populated by the typechecker during import resolution,
 * enabling O(1) content-addressed lookups instead of name-based lookups.
 * The kind field indicates whether this import needs runtime binding.
 */
@Serializable
data class ImportItem(
    val name: String,
    val alias: String? = null,
    val contentHash: String? = null,
    val kind: ImportItemKind = ImportItemKind.VALUE  // Default to VALUE for backward compatibility
) {
    /** Returns true if this import requires a runtime binding (value lookup) */
    val needsRuntimeBinding: Boolean get() = kind == ImportItemKind.VALUE
}

// ============================================================================
// QUALIFIED REFERENCES
// ============================================================================

/**
 * A binding key for looking up imported items.
 *
 * This replaces the old pattern of using `"$alias::$name"` string concatenation.
 * The key consists of a qualifier (module alias or path) and a local name.
 *
 * ## Design Principle
 *
 * Binding keys should be compared structurally, not as strings.
 * The `toKeyString()` method produces a stable string key for map lookups,
 * but the `::` in that string is an implementation detail of the key format.
 *
 * ## Examples
 *
 * ```kotlin
 * // Qualified key for "log::info"
 * val key1 = BindingKey(qualifier = "log", localName = "info")
 *
 * // Unqualified key for "info"
 * val key2 = BindingKey.unqualified("info")
 * ```
 */
@Serializable
data class BindingKey(
    /** The qualifier (module alias or short name) */
    val qualifier: String?,
    /** The local name of the item */
    val localName: String
) {
    /**
     * Produce a stable string key for map lookups.
     * Note: The `::` in this string is an implementation detail, not user-facing syntax.
     */
    fun toKeyString(): String = if (qualifier != null && qualifier.isNotEmpty()) {
        "$qualifier::$localName"
    } else {
        localName
    }

    companion object {
        /**
         * Create an unqualified binding key.
         */
        fun unqualified(name: String): BindingKey = BindingKey(qualifier = null, localName = name)

        /**
         * Create a qualified binding key.
         */
        fun qualified(qualifier: String, name: String): BindingKey = BindingKey(qualifier = qualifier, localName = name)

        /**
         * Create a binding key from a QualifiedRef.
         */
        fun fromQualifiedRef(ref: QualifiedRef): BindingKey = BindingKey(
            qualifier = ref.qualifier?.shortName,
            localName = ref.localName
        )

        /**
         * Parse a string that may contain `::` into a BindingKey.
         *
         * Examples:
         * - "foo" -> BindingKey(null, "foo")
         * - "int::to_string" -> BindingKey("int", "to_string")
         * - "std::collections::seq::empty" -> BindingKey("std::collections::seq", "empty")
         *
         * Note: This is for internal use to convert legacy string keys.
         * New code should construct BindingKey directly with structured data.
         */
        fun parse(keyString: String): BindingKey {
            val lastSeparator = keyString.lastIndexOf("::")
            return if (lastSeparator == -1) {
                unqualified(keyString)
            } else {
                val qualifier = keyString.substring(0, lastSeparator)
                val localName = keyString.substring(lastSeparator + 2)
                qualified(qualifier, localName)
            }
        }
    }
}

// ============================================================================
// LANGUAGE KEYWORDS
// ============================================================================

/**
 * Language keyword constants shared across parser, typechecker, and interpreter.
 * Centralizes keyword strings so they're never hardcoded as magic strings.
 */
object Keywords {
    /** The `for` keyword — used in `ability X for 'a` and `Ability.for { it = value }` */
    const val FOR = "for"

    /** The `it` implicit receiver binding in ability impl bodies */
    const val IT = "it"

    /** The Inspect ability's debug string method */
    const val TO_DBG_STR = "to_dbg_str"
}

/**
 * A qualified reference to a named item (effect, ability, performer, etc.).
 *
 * This is the canonical way to represent references that may be qualified
 * with a module path (e.g., `log::Console` or just `Console`).
 *
 * ## Design Principle
 *
 * The `::` syntax is a parsing concern only. After parsing, all qualified
 * references are represented as structured `QualifiedRef` objects. No code
 * outside the parser should construct or parse `::` strings.
 *
 * ## Resolution Flow
 *
 * 1. Parser produces QualifiedRef(qualifier=["log"], localName="Console")
 * 2. Import resolver resolves qualifier to find the module
 * 3. TypeChecker looks up localName in the module's exports
 * 4. TypeChecker populates resolvedContentHash
 * 5. Interpreter uses resolvedContentHash for O(1) lookup
 *
 * ## Examples
 *
 * ```mezze
 * use std::effects::log
 *
 * # Qualified reference - qualifier=["log"], localName="Console"
 * perform log::Console in log::info {msg = "hello"}
 *
 * # Unqualified reference - qualifier=null, localName="Console"
 * use std::effects::log::{Console}
 * perform Console in info {msg = "hello"}
 * ```
 */
@Serializable
data class QualifiedRef(
    /** The local name of the referenced item (e.g., "Console" in "log::Console") */
    val localName: String,

    /** Module qualifier if this is a qualified reference (e.g., ModulePath(["log"])) */
    val qualifier: ModulePath? = null,

    /**
     * Content hash of the resolved item (populated by typechecker).
     * Used for O(1) content-addressed lookups at runtime.
     */
    val resolvedContentHash: String? = null,

    /** Source location for error reporting */
    val sourceLocation: SourceLocation? = null
) {
    /** Whether this is a qualified reference (e.g., log::Console vs just Console) */
    val isQualified: Boolean get() = qualifier != null && qualifier.segments.isNotEmpty()

    /**
     * Create a copy with the resolved content hash populated.
     * Used by the typechecker after resolving the reference.
     */
    fun withResolvedHash(hash: String): QualifiedRef = copy(resolvedContentHash = hash)

    /**
     * Create a binding key for looking up this reference.
     * Uses the last segment of the qualifier (short name) as the key qualifier.
     */
    fun toBindingKey(): BindingKey = BindingKey.fromQualifiedRef(this)

    companion object {
        /**
         * Create an unqualified reference (local name only).
         */
        fun local(name: String, sourceLocation: SourceLocation? = null): QualifiedRef =
            QualifiedRef(localName = name, sourceLocation = sourceLocation)

        /**
         * Create a qualified reference with a single-segment qualifier.
         */
        fun qualified(qualifier: String, localName: String, sourceLocation: SourceLocation? = null): QualifiedRef =
            QualifiedRef(
                localName = localName,
                qualifier = ModulePath(listOf(qualifier)),
                sourceLocation = sourceLocation
            )

        /**
         * Create a qualified reference with a multi-segment qualifier.
         */
        fun qualified(qualifierSegments: List<String>, localName: String, sourceLocation: SourceLocation? = null): QualifiedRef =
            QualifiedRef(
                localName = localName,
                qualifier = if (qualifierSegments.isNotEmpty()) ModulePath(qualifierSegments) else null,
                sourceLocation = sourceLocation
            )
    }
}
