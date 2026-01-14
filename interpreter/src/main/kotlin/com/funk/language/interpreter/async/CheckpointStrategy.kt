package com.funk.language.interpreter.async

import com.funk.language.interpreter.continuations.ContinuationStateMachine

/**
 * Strategy interface for determining when to checkpoint (use continuations)
 * vs when to use Loom virtual threads in hybrid async execution.
 *
 * This interface provides the core decision-making mechanism for the
 * AdaptiveAsync handler. Different strategies can be implemented to
 * optimize for different use cases:
 * - AlwaysContinuation: Maximum durability, slower performance
 * - AlwaysLoom: Maximum performance, no durability
 * - PeriodicCheckpoint: Balanced approach with time-based checkpoints
 *
 * Future extensions (when serialization is available):
 * - Add serialize/deserialize methods
 * - Add checkpoint metadata tracking
 * - Add workflow state management hooks
 */
interface CheckpointStrategy {
    /**
     * Determine whether to use a continuation (checkpoint) at this point.
     *
     * Returns:
     * - true: Use continuation (serializable, inspectable, durable)
     * - false: Use Loom virtual thread (fast, blocking I/O support)
     */
    fun shouldCheckpoint(): Boolean

    /**
     * Notify the strategy that a checkpoint occurred.
     * Allows strategies to track checkpoint history.
     */
    fun onCheckpoint(state: ContinuationStateMachine) {
        // Default: no-op
    }

    /**
     * Notify the strategy that execution resumed.
     * Allows strategies to adjust behavior based on resume events.
     */
    fun onResume(state: ContinuationStateMachine) {
        // Default: no-op
    }
}

/**
 * Always use continuations - maximum durability, no Loom usage.
 *
 * Use this when:
 * - Every operation must be serializable
 * - Debugging/inspection is critical
 * - Workflow durability is paramount
 */
open class AlwaysContinuation : CheckpointStrategy {
    override fun shouldCheckpoint(): Boolean = true
}

/**
 * Always use Loom virtual threads - maximum performance, no checkpointing.
 *
 * Use this when:
 * - High throughput is critical
 * - Operations complete quickly
 * - No need for durability or inspection
 * - Heavy blocking I/O operations
 */
open class AlwaysLoom : CheckpointStrategy {
    override fun shouldCheckpoint(): Boolean = false
}

/**
 * Checkpoint periodically based on time intervals.
 *
 * Provides a balanced approach:
 * - Fast execution with Loom between checkpoints
 * - Regular durability via continuation checkpoints
 *
 * @param intervalMs Time between checkpoints in milliseconds
 *
 * Example usage:
 *   # Checkpoint every 5 seconds for durability
 *   let strategy = PeriodicCheckpoint {interval = 5000}
 */
open class PeriodicCheckpoint(private val intervalMs: Long) : CheckpointStrategy {
    private var lastCheckpoint = 0L  // Start at 0 so first call always checkpoints

    override fun shouldCheckpoint(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastCheckpoint >= intervalMs) {
            lastCheckpoint = now
            true
        } else {
            false
        }
    }

    override fun onCheckpoint(state: ContinuationStateMachine) {
        lastCheckpoint = System.currentTimeMillis()
    }
}

/**
 * Checkpoint after a specific number of operations.
 *
 * Provides durability at operation boundaries:
 * - Fast execution with Loom for N operations
 * - Checkpoint on the Nth operation
 *
 * @param operationCount Number of operations between checkpoints
 *
 * Example usage:
 *   # Checkpoint every 100 operations
 *   let strategy = OperationCountCheckpoint {count = 100}
 */
open class OperationCountCheckpoint(private val operationCount: Int) : CheckpointStrategy {
    private var currentCount = 0

    override fun shouldCheckpoint(): Boolean {
        currentCount++
        return if (currentCount >= operationCount) {
            currentCount = 0
            true
        } else {
            false
        }
    }

    override fun onCheckpoint(state: ContinuationStateMachine) {
        currentCount = 0
    }
}

/**
 * Explicit checkpoint control - checkpoints only when requested.
 *
 * Provides fine-grained control:
 * - Application decides exactly when to checkpoint
 * - No automatic checkpoint decisions
 *
 * Example usage:
 *   let strategy = ExplicitCheckpoint {}
 *   # Later in the code
 *   strategy.requestCheckpoint {}  # Force next operation to checkpoint
 */
open class ExplicitCheckpoint : CheckpointStrategy {
    @Volatile
    private var checkpointRequested = false

    override fun shouldCheckpoint(): Boolean {
        val result = checkpointRequested
        if (result) {
            checkpointRequested = false
        }
        return result
    }

    /**
     * Request that the next async operation use a checkpoint.
     */
    fun requestCheckpoint() {
        checkpointRequested = true
    }

    override fun onCheckpoint(state: ContinuationStateMachine) {
        checkpointRequested = false
    }
}

/**
 * Never checkpoint - equivalent to AlwaysLoom but explicit in intent.
 *
 * Use this to explicitly document that no checkpointing is desired.
 */
class NeverCheckpoint : CheckpointStrategy {
    override fun shouldCheckpoint(): Boolean = false
}
