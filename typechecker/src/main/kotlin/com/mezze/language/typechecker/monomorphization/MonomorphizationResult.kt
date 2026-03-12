package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*
import com.mezze.language.typechecker.hashing.ContentHashComputer
import java.security.MessageDigest

/**
 * Result of monomorphization.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class MonomorphizationResult {
    /**
     * Monomorphization succeeded.
     */
    data class Success(
        val program: Program,                           // Program with updated call sites
        val monomorphicInstances: Map<String, LetBinding>, // Generated specialized functions
        val warnings: List<String> = emptyList()
    ) : MonomorphizationResult()

    /**
     * Monomorphization failed.
     */
    data class Failure(
        val errors: List<MonomorphizationError>
    ) : MonomorphizationResult()
}

/**
 * Monomorphization error.
 */
data class MonomorphizationError(
    val message: String,
    val functionName: String?,
    val typeArgs: Map<String, MezzeType>,
    val sourceLocation: SourceLocation? = null
)

/**
 * Tracked monomorphic instance request.
 *
 * During type checking, we track which polymorphic functions
 * need specialized instances and with what type arguments.
 */
data class InstanceRequest(
    val originalName: String,          // e.g., "map"
    val typeArgs: Map<String, MezzeType>,  // e.g., {"'a" -> IntType}
    val sourceLocation: SourceLocation? = null,
    val originalContentHash: String? = null  // Content hash of original function (if available)
) {
    /**
     * Convert to structured MonomorphicKey.
     *
     * This is the preferred way to identify monomorphic instances as it enables
     * type-directed optimizations in the interpreter without string parsing.
     */
    fun toMonomorphicKey(): MonomorphicKey = MonomorphicKey.fromInstanceRequest(this)

    /**
     * Generate the monomorphic name for this instance.
     *
     * Format: functionName$Type1$Type2
     * Example: map$Int or add$Int$Float
     *
     * @deprecated Use toMonomorphicKey() for structured access
     */
    fun generateMonomorphicName(): String {
        if (typeArgs.isEmpty()) return originalName

        val typeArgString = typeArgs.entries
            .sortedBy { it.key }
            .joinToString("$") { (_, type) -> ContentHashComputer.mezzeTypeToString(type) }

        return "${originalName}\$$typeArgString"
    }

    /**
     * Generate a content hash for this monomorphic instance.
     *
     * Combines the original function's content hash with the type arguments
     * to produce a unique, deterministic hash for this specialization.
     *
     * Returns null if original content hash is not available.
     *
     * @deprecated Use toMonomorphicKey().generateContentHash() for structured access
     */
    fun generateContentHash(): String? {
        return toMonomorphicKey().generateContentHash()
    }
}

/**
 * Generated monomorphic instance.
 *
 * Represents a specialized version of a polymorphic function.
 */
data class MonomorphicInstance(
    val originalName: String,              // Original polymorphic function name
    val monomorphicName: String,           // Generated name (e.g., "map$Int") - deprecated, use key
    val typeArguments: Map<String, MezzeType>, // Type arguments used for specialization
    val specializedBinding: LetBinding,    // The specialized function binding
    val contentHash: String? = null,       // Content hash for this specialization
    val monomorphicKey: MonomorphicKey? = null  // Structured key (preferred over monomorphicName)
) {
    /**
     * Get or compute the MonomorphicKey for this instance.
     * Returns the stored key or creates one from fields if not provided.
     */
    fun getOrComputeKey(): MonomorphicKey {
        return monomorphicKey ?: MonomorphicKey(
            originalName = originalName,
            originalContentHash = specializedBinding.contentHash,
            typeArgs = typeArguments.entries
                .sortedBy { it.key }
                .map { ConcreteType.fromMezzeType(it.value) }
        )
    }
}
