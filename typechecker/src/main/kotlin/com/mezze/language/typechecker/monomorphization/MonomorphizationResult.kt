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
        val warnings: List<String> = emptyList(),
        val stats: MonomorphizationStats = MonomorphizationStats()  // Statistics for observability
    ) : MonomorphizationResult()

    /**
     * Monomorphization failed.
     */
    data class Failure(
        val errors: List<MonomorphizationError>
    ) : MonomorphizationResult()
}

/**
 * Statistics for monomorphization - enables observability and performance tuning.
 */
data class MonomorphizationStats(
    val totalInstancesRequested: Int = 0,      // Total unique (function, types) pairs requested
    val instancesGenerated: Int = 0,           // Successfully generated instances
    val cacheHits: Int = 0,                    // Instances retrieved from cache
    val cacheMisses: Int = 0,                  // Instances that required generation
    val totalGenerationTimeNanos: Long = 0,   // Total time spent generating instances
    val analysisTimeNanos: Long = 0,          // Time spent analyzing program for requests
    val callSiteUpdateTimeNanos: Long = 0,    // Time spent updating call sites
    val instanceDetails: List<InstanceStats> = emptyList()  // Per-instance statistics
) {
    /**
     * Cache hit rate (0.0 to 1.0)
     */
    val cacheHitRate: Double
        get() = if (cacheHits + cacheMisses > 0) {
            cacheHits.toDouble() / (cacheHits + cacheMisses)
        } else 0.0

    /**
     * Average generation time per instance (excluding cache hits)
     */
    val avgGenerationTimeNanos: Long
        get() = if (cacheMisses > 0) {
            totalGenerationTimeNanos / cacheMisses
        } else 0L

    /**
     * Total monomorphization time
     */
    val totalTimeNanos: Long
        get() = analysisTimeNanos + totalGenerationTimeNanos + callSiteUpdateTimeNanos

    /**
     * Format as human-readable string
     */
    fun format(): String = buildString {
        appendLine("Monomorphization Statistics:")
        appendLine("  Instances: $instancesGenerated generated ($totalInstancesRequested requested)")
        appendLine("  Cache: ${(cacheHitRate * 100).toInt()}% hit rate ($cacheHits hits, $cacheMisses misses)")
        appendLine("  Timing:")
        appendLine("    Analysis: ${analysisTimeNanos / 1_000_000}ms")
        appendLine("    Generation: ${totalGenerationTimeNanos / 1_000_000}ms (avg ${avgGenerationTimeNanos / 1_000}us/instance)")
        appendLine("    Call site update: ${callSiteUpdateTimeNanos / 1_000_000}ms")
        appendLine("    Total: ${totalTimeNanos / 1_000_000}ms")

        if (instanceDetails.isNotEmpty()) {
            appendLine("  Instance breakdown:")
            // Group by original function
            val byFunction = instanceDetails.groupBy { it.originalName }
            for ((fn, instances) in byFunction.entries.sortedBy { it.key }) {
                appendLine("    $fn: ${instances.size} instantiation(s)")
                for (inst in instances.sortedByDescending { it.callCount }) {
                    val cached = if (inst.wasCached) " (cached)" else ""
                    appendLine("      ${inst.typeArgsSummary}: ${inst.callCount} call site(s)$cached")
                }
            }
        }
    }
}

/**
 * Statistics for a single monomorphic instance.
 */
data class InstanceStats(
    val originalName: String,          // e.g., "map"
    val monomorphicName: String,       // e.g., "map$Int"
    val typeArgsSummary: String,       // e.g., "Int, String"
    val callCount: Int,                // Number of call sites using this instance
    val generationTimeNanos: Long,     // Time to generate (0 if cached)
    val wasCached: Boolean             // Whether retrieved from cache
)

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
