@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
         * Fresh TypeVar with a caller-chosen name, stored VERBATIM (unlike
         * [fresh]/[freshNamed], which always prepend `'`). The leading `'` is a
         * load-bearing tag, not decoration: instantiation branches on it
         * (ExpressionConstraints — `if (name.startsWith("'")) freshWithName(name)
         * else fresh()`), so a name's `'`-prefix decides whether its identity is
         * preserved across instantiation.
         *
         * Two intents share this one function, distinguished only by the name:
         *  - Real / associated type variables (`'a`, `'it`) — pass a `'`-prefixed
         *    name (usually straight from source or a type param) to keep identity.
         *  - Internal placeholders (`"unknown"`, `"ret"`, `"elem"`, `"pat"`, …) —
         *    pass a BARE name so instantiation treats them as replaceable.
         * Keep this split: giving an internal placeholder a `'` (or dropping the
         * `'` from a real type var) silently changes instantiation behavior.
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

@Serializable
data class RowVar(val name: String) {
    companion object {
        private var counter = 0

        // May be closed by closeGeneratedRowVars during finalization.
        fun fresh(): RowVar {
            val name = "row${counter}"
            counter++
            return RowVar(name)
        }

        fun userOpen(): RowVar {
            val name = "userOpen_row${counter}"
            counter++
            return RowVar(name)
        }
    }

    fun isUserSpecified(): Boolean = name.startsWith("userOpen_")
}


// NOT part of the AST or content hash.
object SyntaxMetadata {
    private val operatorMetadata = ThreadLocal.withInitial<MutableMap<com.mezze.language.parsedast.Loc, String>> {
        mutableMapOf()
    }

    fun markAsOperator(location: com.mezze.language.parsedast.Loc, operatorSyntax: String) {
        operatorMetadata.get()[location] = operatorSyntax
    }

    fun getOperatorSyntax(location: com.mezze.language.parsedast.Loc): String? {
        return operatorMetadata.get()[location]
    }

    fun clear() {
        operatorMetadata.get().clear()
    }

}


/**
 * Typealias to the canonical Visibility enum in parsedast.Loc.
 */
typealias Visibility = com.mezze.language.parsedast.Visibility

@Serializable
@JsonClassDiscriminator("usePathKind")
sealed interface UsePath

// Structured module path — eliminates string-based `path.split("::")`.
@Serializable
data class ModulePath(val segments: List<String>) : UsePath {
    val fullPath: String get() = segments.joinToString("::")

    val shortName: String get() = segments.lastOrNull() ?: ""

    val isQualified: Boolean get() = segments.size > 1

    val parent: ModulePath? get() = if (segments.size > 1) {
        ModulePath(segments.dropLast(1))
    } else null

    fun endsWith(suffix: ModulePath): Boolean {
        if (suffix.segments.size > segments.size) return false
        val offset = segments.size - suffix.segments.size
        return suffix.segments.withIndex().all { (i, segment) ->
            segments[offset + i] == segment
        }
    }

    companion object {
        // The ONLY place that should parse :: from strings (except the parser).
        fun parse(path: String): ModulePath = ModulePath(path.split("::"))

    }
}

@Serializable
data class SelectivePath(
    val module: List<String>,
    val items: List<ImportItem>
) : UsePath

@Serializable
enum class ImportItemKind {
    VALUE,
    TYPE,
    ABILITY,
    EFFECT
}

// contentHash populated by typechecker during import resolution for O(1) content-addressed lookups.
@Serializable
data class ImportItem(
    val name: String,
    val alias: String? = null,
    val contentHash: String? = null,
    val kind: ImportItemKind = ImportItemKind.VALUE  // Default to VALUE for backward compatibility
) {
    val needsRuntimeBinding: Boolean get() = kind == ImportItemKind.VALUE
}


@Serializable
data class BindingKey(
    val qualifier: String?,
    val localName: String
) {
    // The `::` in this string is an implementation detail, not user-facing syntax.
    fun toKeyString(): String = if (qualifier != null && qualifier.isNotEmpty()) {
        "$qualifier::$localName"
    } else {
        localName
    }

    companion object {
        fun unqualified(name: String): BindingKey = BindingKey(qualifier = null, localName = name)

        fun qualified(qualifier: String, name: String): BindingKey = BindingKey(qualifier = qualifier, localName = name)

        // Internal use only — converts legacy string keys. New code should construct BindingKey directly.
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


object ModulePathConventions {
    const val STDLIB_PREFIX = "std::"

    // Null means "no module context" → never stdlib.
    fun isStdlib(modulePath: String?): Boolean =
        modulePath != null && modulePath.startsWith(STDLIB_PREFIX)
}

object Keywords {
    // Implicit receiver in ability impl bodies; TypedAstBuilder desugars `x.method {…}` into `method({it = x, …})`.
    const val IT = "it"
    // Right operand field for binary op desugaring; consumed by TruffleCompiler's DirectAddNode etc.
    const val R = "r"
    const val TO_DBG_STR = "to_dbg_str"
}

// Reverse-lookup constructor-set → typeName for ability-impl matching (e.g. `impl Sized for Option`).
// Used by TypedASTCompiler and MonoTypeOps — keeping the table here ensures both agree.
object VariantAliases {
    val byConstructorSet: Map<String, String> = mapOf(
        "None,Some" to "Option",
        "Err,Ok" to "Result",
        "Cons,Nil" to "List",
        "False,True" to "Bool",
        "Just,Nothing" to "Maybe",
    )

    fun aliasFor(constructors: Set<String>): String? =
        byConstructorSet[constructors.sorted().joinToString(",")]
}

