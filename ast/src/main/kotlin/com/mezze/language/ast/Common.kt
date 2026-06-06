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
 * - SyntaxMetadata: Operator desugaring metadata (uses Loc from parsedast)
 * - Visibility: PUBLIC/PRIVATE visibility modifiers
 * - UsePath: Module system import paths
 */


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
data class TypeVar(val name: String, val level: Int = 0, val id: Int) {
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


/**
 * Thread-local metadata tracking desugared operator syntax for better error messages.
 * NOT part of the AST or content hash.
 */
object SyntaxMetadata {
    // Each thread gets its own metadata map - prevents race conditions
    private val operatorMetadata = ThreadLocal.withInitial<MutableMap<com.mezze.language.parsedast.Loc, String>> {
        mutableMapOf()
    }

    /**
     * Mark an expression as coming from operator syntax.
     * @param location Source location of the expression
     * @param operatorSyntax The operator that was desugared (e.g., "-", "+", "!")
     */
    fun markAsOperator(location: com.mezze.language.parsedast.Loc, operatorSyntax: String) {
        operatorMetadata.get()[location] = operatorSyntax
    }

    /**
     * Get the operator syntax for an expression if it was desugared from an operator.
     * @param location Source location of the expression
     * @return The operator syntax, or null if not from an operator
     */
    fun getOperatorSyntax(location: com.mezze.language.parsedast.Loc): String? {
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


/**
 * Typealias to the canonical Visibility enum in parsedast.Loc.
 */
typealias Visibility = com.mezze.language.parsedast.Visibility

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


/**
 * A binding key for looking up imported items: qualifier (module alias) + local name.
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


/**
 * Language keyword constants shared across parser, typechecker, and interpreter.
 * Centralizes keyword strings so they're never hardcoded as magic strings.
 */
/**
 * Module-path conventions. Centralizes the well-known prefix rules
 * (e.g., the `std::` namespace for stdlib modules) so callers don't
 * spread prefix-matching string literals across the codebase.
 */
object ModulePathConventions {
    const val STDLIB_PREFIX = "std::"

    /** True when the given dotted module path is a stdlib module
     *  (e.g., `std::seq`, `std::option`). Null is treated as "user
     *  module" — callers using a null path mean "no module context",
     *  which is never stdlib. */
    fun isStdlib(modulePath: String?): Boolean =
        modulePath != null && modulePath.startsWith(STDLIB_PREFIX)
}

object Keywords {
    /** The `it` implicit receiver binding in ability impl bodies. Used
     *  by TypedAstBuilder when desugaring `x.method {…}` into
     *  `method({it = x, …})`. Compilers downstream extract the receiver
     *  from this field. */
    const val IT = "it"

    /** The right operand of a binary op call. TypedAstBuilder desugars
     *  `x.add {r = y}` so the right operand lives at field `r`. Both
     *  the typechecker's inline-op path and the interpreter's
     *  TruffleCompiler extract this field name to feed the
     *  DirectAddNode/etc. */
    const val R = "r"

    /** The Inspect ability's debug string method */
    const val TO_DBG_STR = "to_dbg_str"
}

/**
 * Constructor-set → typeName aliases for stdlib variant types.
 *
 * Mezze's stdlib defines a handful of well-known variants by their
 * constructor sets without a leading `typeName` (e.g., `Option = None |
 * Some 'a`). When a `MezzeType.VariantType` doesn't have its
 * `typeName` set, but its constructor set matches one of these
 * conventions, we can reverse-lookup the alias for ability-impl
 * matching purposes (e.g., resolving `impl Sized for Option`).
 *
 * Used by [com.mezze.language.typechecker.compiler.TypedASTCompiler]
 * and [com.mezze.language.typechecker.mono.MonoTypeOps] —
 * keeping the table here ensures both consumers agree.
 *
 * Key: comma-joined sorted constructor names. Value: the canonical
 * type name. Entries should match the stdlib's Option/Result/List/
 * Bool/Maybe declarations.
 */
object VariantAliases {
    val byConstructorSet: Map<String, String> = mapOf(
        "None,Some" to "Option",
        "Err,Ok" to "Result",
        "Cons,Nil" to "List",
        "False,True" to "Bool",
        "Just,Nothing" to "Maybe",
    )

    /** Look up a type-name alias for a variant by its constructor set.
     *  `constructors` will be sorted + comma-joined to form the key. */
    fun aliasFor(constructors: Set<String>): String? =
        byConstructorSet[constructors.sorted().joinToString(",")]
}

