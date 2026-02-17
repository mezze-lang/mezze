package com.mezze.language.interpreter.async

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.continuations.ContinuationStateMachine
import java.util.concurrent.atomic.AtomicLong

/**
 * Adaptive async handler that intelligently switches between continuations
 * and Loom virtual threads based on a checkpoint strategy.
 *
 * This is the core of Phase 4's hybrid execution system. It provides:
 * - Automatic selection between continuation (durable) and Loom (fast) modes
 * - Pluggable checkpoint strategies for different use cases
 * - Seamless integration with existing async code
 *
 * Architecture:
 * - Uses CheckpointStrategy to decide when to checkpoint
 * - Continuations provide: serialization, inspection, durability
 * - Loom provides: high performance, blocking I/O support
 *
 * Example usage in Mezze:
 *   perform AdaptiveAsync {
 *       cont = continuation::new {},
 *       strategy = PeriodicCheckpoint {interval = 5000}
 *   } in do
 *       # Fast operations use Loom automatically
 *       let data = await {promise = read_cache {}}
 *
 *       # Every 5 seconds, checkpoints with continuation
 *       let result = await {promise = process {data = data}}
 *       result
 */
class AdaptiveAsyncHandler(
    private val continuation: ContinuationStateMachine?,
    private val strategy: CheckpointStrategy
) {
    /**
     * Handle an async await operation.
     *
     * Decision logic:
     * 1. Ask strategy: shouldCheckpoint()?
     * 2. If true: Use continuation (suspend + serialize)
     * 3. If false: Use Loom (block virtual thread)
     *
     * @param promise The promise to await
     * @return The promise's value (or suspended continuation)
     */
    fun await(promise: MezzePromise): MezzeValue {
        stats.totalOperations.incrementAndGet()
        return if (strategy.shouldCheckpoint()) {
            stats.continuationOperations.incrementAndGet()
            awaitWithContinuation(promise)
        } else {
            stats.loomOperations.incrementAndGet()
            awaitWithLoom(promise)
        }
    }

    /**
     * Await using continuation - provides durability and serializability.
     *
     * This path:
     * - Suspends the continuation with the promise
     * - Allows serialization of the suspended state
     * - Enables inspection of execution state
     * - Can resume after process restart
     */
    private fun awaitWithContinuation(promise: MezzePromise): MezzeValue {
        if (continuation == null) {
            throw AdaptiveAsyncException(
                "Continuation-based await requires a continuation, but none was provided"
            )
        }

        // Notify strategy that we're checkpointing
        strategy.onCheckpoint(continuation)

        // Suspend the continuation with the promise value
        // This creates a MezzeContinuationSuspended that can be serialized later
        return MezzeContinuationSuspended(
            continuation = MezzeContinuation(
                // TODO: Replace with actual resume function when continuation API is updated
                resumeFunction = NativeMezzeFunction("resume") { args ->
                    // This will be implemented properly when we integrate with
                    // the continuation resume mechanism
                    MezzeNull
                },
                capturePoint = "adaptive-async-${continuation.id}"
            ),
            suspendedValue = promise
        )
    }

    /**
     * Await using Loom virtual thread - provides high performance.
     *
     * This path:
     * - Blocks the current virtual thread until promise completes
     * - Virtual thread is parked efficiently (no CPU usage)
     * - Excellent for blocking I/O operations
     * - Cannot be serialized (no durability)
     */
    private fun awaitWithLoom(promise: MezzePromise): MezzeValue {
        // Simply block the virtual thread until the promise completes
        // LockSupport.park() is used internally for efficient blocking
        return promise.await()
    }

    /**
     * Get statistics about checkpoint decisions.
     * Useful for debugging and optimization.
     */
    fun getStats(): AdaptiveAsyncStats {
        return AdaptiveAsyncStats(
            totalOperations = stats.totalOperations.get(),
            continuationOperations = stats.continuationOperations.get(),
            loomOperations = stats.loomOperations.get()
        )
    }

    /**
     * Thread-safe statistics tracking using atomic operations.
     */
    private val stats = object {
        val totalOperations = AtomicLong(0)
        val continuationOperations = AtomicLong(0)
        val loomOperations = AtomicLong(0)
    }

    init {
        // Validate configuration
        if (strategy is AlwaysContinuation && continuation == null) {
            throw AdaptiveAsyncException(
                "AlwaysContinuation strategy requires a continuation to be provided"
            )
        }
    }
}

/**
 * Statistics for adaptive async execution.
 * Helps understand and optimize checkpoint strategies.
 */
data class AdaptiveAsyncStats(
    val totalOperations: Long,
    val continuationOperations: Long,
    val loomOperations: Long
) {
    val continuationPercentage: Double
        get() = if (totalOperations > 0) {
            (continuationOperations.toDouble() / totalOperations) * 100.0
        } else 0.0

    val loomPercentage: Double
        get() = if (totalOperations > 0) {
            (loomOperations.toDouble() / totalOperations) * 100.0
        } else 0.0

    override fun toString(): String {
        return """
            AdaptiveAsync Statistics:
            - Total operations: $totalOperations
            - Continuation: $continuationOperations (${String.format("%.1f", continuationPercentage)}%)
            - Loom: $loomOperations (${String.format("%.1f", loomPercentage)}%)
        """.trimIndent()
    }
}

/**
 * Exception thrown by AdaptiveAsyncHandler on configuration errors.
 */
class AdaptiveAsyncException(message: String) : RuntimeException(message)
