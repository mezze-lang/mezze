package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.effects.EffectContext
import com.mezze.language.interpreter.effects.CancellationToken
import com.mezze.language.interpreter.effects.EffectCancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Type alias for test compatibility
typealias OperationError = ParallelExecutionError

/**
 * Manages error propagation and cancellation for parallel effect groups
 * Implements structured concurrency principles with proper cleanup
 */
class ParallelErrorPropagationManager(
    private val concurrencyManager: LoomConcurrencyManager
) {
    private val activeGroups = ConcurrentHashMap<String, ParallelEffectGroup>()
    
    /**
     * Create a new parallel effect group with error propagation
     */
    fun createParallelGroup(
        groupId: String = UUID.randomUUID().toString(),
        context: EffectContext,
        errorStrategy: ErrorPropagationStrategy = ErrorPropagationStrategy.FAIL_FAST
    ): ParallelEffectGroup {
        val group = ParallelEffectGroup(
            id = groupId,
            context = context,
            errorStrategy = errorStrategy,
            manager = this
        )
        
        activeGroups[groupId] = group
        return group
    }
    
    /**
     * Remove a completed group
     */
    internal fun removeGroup(groupId: String) {
        activeGroups.remove(groupId)
    }
    
    /**
     * Cancel all active groups
     */
    fun cancelAllGroups(reason: String = "Manager shutdown") {
        activeGroups.values.forEach { group ->
            group.cancel(reason)
        }
        activeGroups.clear()
    }
    
    /**
     * Get statistics about active groups
     */
    fun getStatistics(): ParallelGroupStatistics {
        val groups = activeGroups.values.toList()
        return ParallelGroupStatistics(
            activeGroups = groups.size,
            totalOperations = groups.sumOf { it.getOperationCount() },
            failedGroups = groups.count { it.hasFailed() },
            cancelledGroups = groups.count { it.isCancelled() }
        )
    }
}

/**
 * Represents a group of parallel operations with coordinated error handling
 */
class ParallelEffectGroup(
    val id: String,
    private val context: EffectContext,
    private val errorStrategy: ErrorPropagationStrategy,
    private val manager: ParallelErrorPropagationManager
) {
    private val operations = ConcurrentHashMap<String, ParallelOperation>()
    private val groupState = AtomicReference<ParallelGroupState>(ParallelGroupState.ACTIVE)
    private val groupCancellationToken = context.cancellationToken.createChild()
    private val firstError = AtomicReference<ParallelExecutionError>()
    private val completedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private val startTime = Instant.now()
    
    /**
     * Add an operation to this parallel group
     */
    fun addOperation(
        operationId: String,
        computation: () -> MezzeValue,
        dependencies: Set<String> = emptySet(),
        timeout: Duration? = null
    ): ParallelOperation {
        if (groupState.get() != ParallelGroupState.ACTIVE) {
            throw RuntimeException("Cannot add operations to inactive group: $id")
        }
        
        val operation = ParallelOperation(
            id = operationId,
            groupId = id,
            computation = computation,
            dependencies = dependencies,
            timeout = timeout,
            context = context.withCancellationToken(groupCancellationToken),
            group = this
        )
        
        operations[operationId] = operation
        return operation
    }
    
    /**
     * Execute all operations in this group with error propagation
     */
    fun execute(): Map<String, MezzeValue> {
        if (!groupState.compareAndSet(ParallelGroupState.ACTIVE, ParallelGroupState.EXECUTING)) {
            throw RuntimeException("Group is not in active state: $id")
        }
        
        try {
            return executeOperations()
        } catch (e: Exception) {
            handleGroupError(ParallelExecutionError("Group execution failed", e, id))
            throw e
        } finally {
            cleanup()
        }
    }
    
    /**
     * Execute a specific operation and return its result
     */
    fun executeOperation(operationId: String): MezzeValue {
        val operation = operations[operationId]
            ?: throw RuntimeException("Operation not found: $operationId")
        
        return operation.execute()
    }
    
    /**
     * Cancel this group and all its operations
     */
    fun cancel(reason: String = "Group cancelled") {
        if (groupState.compareAndSet(ParallelGroupState.ACTIVE, ParallelGroupState.CANCELLED) ||
            groupState.compareAndSet(ParallelGroupState.EXECUTING, ParallelGroupState.CANCELLED)) {
            
            groupCancellationToken.cancel(reason)
            
            // Cancel all operations
            operations.values.forEach { operation ->
                operation.cancel(reason)
            }
        }
    }
    
    /**
     * Handle an error from one of the operations
     */
    internal fun handleOperationError(error: ParallelExecutionError) {
        failedCount.incrementAndGet()

        // Store the first error
        firstError.compareAndSet(null, error)

        when (errorStrategy) {
            ErrorPropagationStrategy.FAIL_FAST -> {
                // Cancel all other operations immediately
                cancel("Operation failed: ${error.message}")
                groupState.set(ParallelGroupState.FAILED)
            }
            ErrorPropagationStrategy.COLLECT_ERRORS -> {
                // Continue execution, collect errors
                // Group will fail only when all operations complete
                checkCompletion()
            }
            ErrorPropagationStrategy.IGNORE_ERRORS -> {
                // Continue execution, ignore errors
                // Failed operations will return error values
                checkCompletion()
            }
        }
    }
    
    /**
     * Handle successful completion of an operation
     */
    internal fun handleOperationSuccess(operationId: String, result: MezzeValue) {
        completedCount.incrementAndGet()
        checkCompletion()
    }

    /**
     * Check if all operations are complete and update group state accordingly
     */
    private fun checkCompletion() {
        val totalOperations = operations.size
        val completed = completedCount.get()
        val failed = failedCount.get()

        if (completed + failed >= totalOperations) {
            // All operations are done
            if (failed > 0 && errorStrategy != ErrorPropagationStrategy.IGNORE_ERRORS) {
                groupState.set(ParallelGroupState.FAILED)
            } else {
                groupState.set(ParallelGroupState.COMPLETED)
            }
        }
    }
    
    /**
     * Check if this group has failed
     */
    fun hasFailed(): Boolean {
        return groupState.get() == ParallelGroupState.FAILED
    }
    
    /**
     * Check if this group is cancelled
     */
    fun isCancelled(): Boolean {
        return groupState.get() == ParallelGroupState.CANCELLED
    }
    
    /**
     * Check if this group is completed
     */
    fun isCompleted(): Boolean {
        return groupState.get() == ParallelGroupState.COMPLETED
    }
    
    /**
     * Get the number of operations in this group
     */
    fun getOperationCount(): Int = operations.size
    
    /**
     * Get the first error that occurred in this group
     */
    fun getFirstError(): OperationError? = firstError.get()

    /**
     * Get all errors that occurred in this group
     */
    fun getAllErrors(): List<OperationError> {
        return operations.values.mapNotNull { it.getError() }
    }
    
    /**
     * Execute all operations with proper dependency handling
     */
    private fun executeOperations(): Map<String, MezzeValue> {
        val results = ConcurrentHashMap<String, MezzeValue>()
        val futures = ConcurrentHashMap<String, CompletableFuture<MezzeValue>>()
        
        // Start all operations
        operations.values.forEach { operation ->
            val future = CompletableFuture.supplyAsync({
                operation.execute()
            }, Executors.newVirtualThreadPerTaskExecutor())
            
            futures[operation.id] = future
        }
        
        // Wait for all operations to complete
        try {
            futures.forEach { (operationId, future) ->
                try {
                    val result = future.get()
                    results[operationId] = result
                } catch (e: Exception) {
                    if (errorStrategy == ErrorPropagationStrategy.FAIL_FAST) {
                        // Cancel remaining futures
                        futures.values.forEach { it.cancel(true) }
                        throw e
                    } else {
                        // Store error as result (for error collection strategies)
                        results[operationId] = createErrorValue(e)
                    }
                }
            }
        } catch (e: Exception) {
            throw ParallelExecutionError("Failed to execute parallel operations", e, id)
        }
        
        return results
    }
    
    /**
     * Handle a group-level error
     */
    private fun handleGroupError(error: ParallelExecutionError) {
        firstError.compareAndSet(null, error)
        groupState.set(ParallelGroupState.FAILED)
        cancel("Group error: ${error.message}")
    }
    
    /**
     * Clean up resources
     */
    private fun cleanup() {
        manager.removeGroup(id)
        
        // Cancel any remaining operations
        operations.values.forEach { operation ->
            if (!operation.isCompleted()) {
                operation.cancel("Group cleanup")
            }
        }
    }
    
    /**
     * Create an error value for failed operations
     */
    private fun createErrorValue(error: Throwable): MezzeValue {
        return com.mezze.language.interpreter.MezzeString("ERROR: ${error.message}")
    }
}

/**
 * Represents a single operation within a parallel group
 */
class ParallelOperation(
    val id: String,
    val groupId: String,
    private val computation: () -> MezzeValue,
    private val dependencies: Set<String>,
    private val timeout: Duration?,
    private val context: EffectContext,
    private val group: ParallelEffectGroup
) {
    private val operationState = AtomicReference<OperationState>(OperationState.PENDING)
    private val result = AtomicReference<MezzeValue>()
    private val error = AtomicReference<ParallelExecutionError>()
    private val future = AtomicReference<CompletableFuture<MezzeValue>>()
    private val startTime = AtomicReference<Instant>()
    private val endTime = AtomicReference<Instant>()
    
    /**
     * Execute this operation
     */
    fun execute(): MezzeValue {
        if (!operationState.compareAndSet(OperationState.PENDING, OperationState.RUNNING)) {
            // Already running or completed
            return awaitResult()
        }

        startTime.set(Instant.now())
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        try {
            // Check for cancellation
            context.throwIfCancelled()

            // Wait for dependencies if any
            waitForDependencies()

            // Execute the computation
            val computationFuture = CompletableFuture.supplyAsync({
                executeComputation()
            }, executor)

            future.set(computationFuture)

            // Apply timeout if specified
            val resultValue = if (timeout != null) {
                computationFuture.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                computationFuture.get()
            }

            // Mark as completed
            result.set(resultValue)
            operationState.set(OperationState.COMPLETED)
            endTime.set(Instant.now())

            // Notify group of success
            group.handleOperationSuccess(id, resultValue)

            return resultValue

        } catch (e: Exception) {
            // Handle error
            val parallelError = ParallelExecutionError(
                message = e.message ?: "Operation failed: $id",
                cause = e,
                groupId = groupId,
                operationId = id
            )

            error.set(parallelError)
            operationState.set(OperationState.FAILED)
            endTime.set(Instant.now())

            // Notify group of error
            group.handleOperationError(parallelError)

            throw parallelError
        } finally {
            // Clean up executor
            executor.shutdown()
        }
    }
    
    /**
     * Cancel this operation
     */
    fun cancel(reason: String = "Operation cancelled") {
        if (operationState.compareAndSet(OperationState.PENDING, OperationState.CANCELLED) ||
            operationState.compareAndSet(OperationState.RUNNING, OperationState.CANCELLED)) {
            
            future.get()?.cancel(true)
            endTime.set(Instant.now())
            
            val cancelError = ParallelExecutionError(
                message = "Operation cancelled: $reason",
                cause = EffectCancellationException(reason),
                groupId = groupId,
                operationId = id
            )
            error.set(cancelError)
        }
    }
    
    /**
     * Check if this operation is completed
     */
    fun isCompleted(): Boolean {
        val state = operationState.get()
        return state == OperationState.COMPLETED || 
               state == OperationState.FAILED || 
               state == OperationState.CANCELLED
    }
    
    /**
     * Get the error if this operation failed
     */
    fun getError(): OperationError? = error.get()
    
    /**
     * Get execution statistics for this operation
     */
    fun getStatistics(): OperationStatistics {
        val start = startTime.get()
        val end = endTime.get()
        val duration = if (start != null && end != null) {
            Duration.between(start, end)
        } else {
            Duration.ZERO
        }
        
        return OperationStatistics(
            operationId = id,
            groupId = groupId,
            state = operationState.get(),
            executionTime = duration,
            hasError = error.get() != null,
            error = error.get()
        )
    }
    
    /**
     * Wait for the result of this operation
     */
    private fun awaitResult(): MezzeValue {
        // Spin-wait for the operation to complete or future to be set
        while (true) {
            when (operationState.get()) {
                OperationState.COMPLETED -> return result.get()
                OperationState.FAILED -> throw error.get()
                OperationState.CANCELLED -> throw EffectCancellationException("Operation was cancelled")
                OperationState.RUNNING, OperationState.PENDING -> {
                    // Wait for future to be set
                    val f = future.get()
                    if (f != null) {
                        return try {
                            f.get()
                        } catch (e: Exception) {
                            // Future failed, check if error was set
                            error.get()?.let { throw it } ?: throw e
                        }
                    }
                    // Future not set yet, wait a bit and retry
                    Thread.sleep(1)
                }
            }
        }
    }
    
    /**
     * Wait for dependencies to complete
     */
    private fun waitForDependencies() {
        if (dependencies.isEmpty()) return
        
        dependencies.forEach { depId ->
            try {
                context.throwIfCancelled()
                group.executeOperation(depId) // This will block until dependency completes
            } catch (e: Exception) {
                throw ParallelExecutionError(
                    message = "Dependency failed: $depId",
                    cause = e,
                    groupId = groupId,
                    operationId = id
                )
            }
        }
    }
    
    /**
     * Execute the actual computation
     */
    private fun executeComputation(): MezzeValue {
        return try {
            context.throwIfCancelled()
            computation()
        } catch (e: Exception) {
            throw ParallelExecutionError(
                message = e.message ?: "Computation failed",
                cause = e,
                groupId = groupId,
                operationId = id
            )
        }
    }
}

/**
 * Error propagation strategies for parallel groups
 */
enum class ErrorPropagationStrategy {
    FAIL_FAST,      // Cancel all operations on first error
    COLLECT_ERRORS, // Continue execution, collect all errors
    IGNORE_ERRORS   // Continue execution, ignore errors
}

/**
 * States of a parallel group
 */
enum class ParallelGroupState {
    ACTIVE,     // Accepting new operations
    EXECUTING,  // Currently executing operations
    COMPLETED,  // All operations completed successfully
    FAILED,     // At least one operation failed
    CANCELLED   // Group was cancelled
}

/**
 * States of an individual operation
 */
enum class OperationState {
    PENDING,    // Not started yet
    RUNNING,    // Currently executing
    COMPLETED,  // Successfully completed
    FAILED,     // Failed with error
    CANCELLED   // Cancelled
}

/**
 * Error that occurred during parallel execution
 */
class ParallelExecutionError(
    message: String,
    cause: Throwable? = null,
    val groupId: String,
    val operationId: String? = null
) : RuntimeException(message, cause) {

    val timestamp: Instant = Instant.now()

    // Property for test compatibility
    val exception: Throwable? get() = cause

    override val message: String get() = buildString {
        append(super.message)
        append(" [Group: $groupId")
        operationId?.let { append(", Operation: $it") }
        append(", Time: $timestamp]")
    }
}

/**
 * Statistics about parallel groups
 */
data class ParallelGroupStatistics(
    val activeGroups: Int,
    val totalOperations: Int,
    val failedGroups: Int,
    val cancelledGroups: Int
) {
    val successfulGroups: Int get() = activeGroups - failedGroups - cancelledGroups
    val successRate: Double get() = if (activeGroups > 0) successfulGroups.toDouble() / activeGroups else 0.0
}

/**
 * Statistics about an individual operation
 */
data class OperationStatistics(
    val operationId: String,
    val groupId: String,
    val state: OperationState,
    val executionTime: Duration,
    val hasError: Boolean,
    val error: OperationError? = null
) {
    val isSuccessful: Boolean get() = state == OperationState.COMPLETED && !hasError
}