package com.mezze.language.codebase

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash

/**
 * Content-addressed codebase interface.
 *
 * This is the single source of truth for all code in a Mezze project.
 * All tools (compiler, LSP, browser, etc.) read/write through this interface.
 *
 * Design principles:
 * - Content-addressed: definitions are keyed by hash, names are metadata
 * - Immutable definitions: once stored, a hash always returns the same AST
 * - Incremental: supports efficient updates via dependency tracking
 */
interface Codebase : AutoCloseable {

    // ========================================================================
    // DEFINITIONS (Content-Addressed Storage)
    // ========================================================================

    /**
     * Store a definition by its content hash.
     */
    fun putDefinition(definition: StoredDefinition)

    /**
     * Retrieve a definition by its content hash.
     */
    fun getDefinition(hash: MezzeHash): StoredDefinition?

    /**
     * Check if a definition exists.
     */
    fun hasDefinition(hash: MezzeHash): Boolean

    /**
     * Get all definition hashes.
     */
    fun getAllDefinitionHashes(): Set<MezzeHash>

    /**
     * Get definitions by kind.
     */
    fun getDefinitionsByKind(kind: DefinitionKind): List<StoredDefinition>

    /**
     * Get definitions in a module.
     */
    fun getDefinitionsByModule(modulePath: String): List<StoredDefinition>

    // ========================================================================
    // NAMES (Metadata)
    // ========================================================================

    /**
     * Register a name for a hash.
     */
    fun putName(modulePath: String?, name: String, hash: MezzeHash, isPrimary: Boolean = true)

    /**
     * Get the hash for a name.
     */
    fun getHashByName(modulePath: String?, name: String): MezzeHash?

    /**
     * Get all names for a hash.
     */
    fun getNamesByHash(hash: MezzeHash): List<QualifiedName>

    /**
     * Remove a name mapping.
     */
    fun removeName(modulePath: String?, name: String)

    // ========================================================================
    // TYPES (Inferred Types)
    // ========================================================================

    /**
     * Store the inferred type for a definition.
     */
    fun putType(hash: MezzeHash, type: MezzeType, typeText: String)

    /**
     * Get the inferred type for a definition.
     */
    fun getType(hash: MezzeHash): StoredType?

    // ========================================================================
    // DEPENDENCIES
    // ========================================================================

    /**
     * Record a dependency between definitions.
     */
    fun addDependency(fromHash: MezzeHash, toHash: MezzeHash, kind: DependencyKind)

    /**
     * Get direct dependencies of a definition.
     */
    fun getDependencies(hash: MezzeHash): List<Dependency>

    /**
     * Get direct dependents of a definition (reverse lookup).
     */
    fun getDependents(hash: MezzeHash): List<Dependency>

    /**
     * Clear all dependencies from a definition (for re-typechecking).
     */
    fun clearDependencies(fromHash: MezzeHash)

    // ========================================================================
    // MODULES
    // ========================================================================

    /**
     * Register a module.
     */
    fun putModule(module: StoredModule)

    /**
     * Get a module by path.
     */
    fun getModule(path: String): StoredModule?

    /**
     * Get all modules.
     */
    fun getAllModules(): List<StoredModule>

    /**
     * Check if a module exists.
     */
    fun hasModule(path: String): Boolean

    // ========================================================================
    // EXPORTS
    // ========================================================================

    /**
     * Register an export from a module.
     */
    fun putExport(modulePath: String, exportName: String, hash: MezzeHash, kind: DefinitionKind)

    /**
     * Get exports for a module.
     */
    fun getExports(modulePath: String): List<StoredExport>

    /**
     * Get a specific export.
     */
    fun getExport(modulePath: String, exportName: String): StoredExport?

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================

    /**
     * Begin a transaction for batch operations.
     */
    fun beginTransaction()

    /**
     * Commit the current transaction.
     */
    fun commit()

    /**
     * Rollback the current transaction.
     */
    fun rollback()

    /**
     * Clear all data (for fresh builds).
     */
    fun clear()

    // ========================================================================
    // METADATA
    // ========================================================================

    /**
     * Get schema version.
     */
    fun getSchemaVersion(): Int

    /**
     * Get a metadata value.
     */
    fun getMetadata(key: String): String?

    /**
     * Set a metadata value.
     */
    fun setMetadata(key: String, value: String)
}

// ============================================================================
// DATA TYPES
// ============================================================================

/**
 * Kind of definition stored in the codebase.
 */
enum class DefinitionKind {
    FUNCTION,
    TYPE_RECORD,
    TYPE_VARIANT,
    TYPE_NEWTYPE,
    ABILITY,
    ABILITY_IMPL,
    EFFECT,
    EFFECT_IMPL,
    MACRO,
    FOREIGN
}

/**
 * Kind of dependency between definitions.
 */
enum class DependencyKind {
    CALLS,          // Function calls another function
    USES_TYPE,      // Uses a type
    USES_ABILITY,   // Requires an ability
    USES_EFFECT,    // Performs an effect
    IMPLEMENTS      // Implements an ability/effect
}

/**
 * Visibility of a definition.
 */
enum class Visibility {
    PUBLIC,
    PRIVATE,
    INTERNAL
}

/**
 * A stored definition with metadata.
 */
data class StoredDefinition(
    val hash: MezzeHash,
    val kind: DefinitionKind,
    val name: String,
    val modulePath: String?,
    val visibility: Visibility,
    val astBlob: ByteArray,         // CBOR-serialized AST
    val sourceHash: String? = null  // For change detection
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredDefinition) return false
        return hash == other.hash
    }

    override fun hashCode(): Int = hash.hashCode()
}

/**
 * A stored type with text representation.
 */
data class StoredType(
    val hash: MezzeHash,
    val typeBlob: ByteArray,       // CBOR-serialized MezzeType
    val typeText: String           // Human-readable signature
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredType) return false
        return hash == other.hash
    }

    override fun hashCode(): Int = hash.hashCode()
}

/**
 * A qualified name.
 */
data class QualifiedName(
    val modulePath: String?,
    val name: String,
    val isPrimary: Boolean = true
) {
    val fullName: String
        get() = if (modulePath != null) "$modulePath::$name" else name

    override fun toString(): String = fullName
}

/**
 * A dependency between definitions.
 */
data class Dependency(
    val fromHash: MezzeHash,
    val toHash: MezzeHash,
    val kind: DependencyKind
)

/**
 * A stored module.
 */
data class StoredModule(
    val path: String,
    val filePath: String?,
    val sourceHash: String?,
    val isStdlib: Boolean = false,
    val isExternalDep: Boolean = false,
    val packageName: String? = null,
    val packageVersion: String? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val docComment: String? = null
)

/**
 * A stored export.
 */
data class StoredExport(
    val modulePath: String,
    val exportName: String,
    val hash: MezzeHash,
    val originalName: String? = null,
    val kind: DefinitionKind
)
