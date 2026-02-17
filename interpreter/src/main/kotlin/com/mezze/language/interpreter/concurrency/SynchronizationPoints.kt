package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.effects.EffectContext
import com.mezze.language.interpreter.effects.CancellationToken
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration
import java.util.UUID

/**
 * Manages synchronization points that block only when values are actually needed
 * Implements lazy synchronization for optimal performance
 */
class SynchronizationPointManager(
    private val concurrencyManager: LoomConcurrencyManager
) {
    private val activeSyncPoints = ConcurrentHashMap<String, SynchronizationPoint>()
    private val completedValues = ConcurrentHashMap<String, MezzeValue>()
    
    /**
     * Create a synchronization point for a lazy operation
     */
    fun createSyncPoint(
        operationId: String,
        dependencies: Set<String>,
        computation: () -> MezzeValue,
        context: EffectContext
    ): SynchronizationPoint {
        val syncPoint = SynchronizationPoint(
            id = operationId,
            dependencies = dependencies,
            computation = computation,
            context = context,
            manager = this
        )
        
        activeSyncPoints[operationId] = syncPoint
        return syncPoint
    }
    
    /**
     * Get a value, blocking only if necessary
     */
    fun getValue(operationId: String): MezzeValue {
        // Check if value is already computed
        completedValues[operationId]?.let { return it }
        
        // Get the synchronization point
        val syncPoint = activeSyncPoints[operationId]
            ?: throw RuntimeException("No synchronization point found for operation: $operationId")
        
        val result = syncPoint.await()
        
        // Mark as completed
        markCompleted(operationId, result)
        
        return result
    }
    
    /**
     * Mark a value as completed
     */
    internal fun markCompleted(operationId: String, value: MezzeValue) {
        completedValues[operationId] = value
        activeSyncPoints.remove(operationId)
    }
    
    /**
     * Check if a value is available without blocking
     */
    fun isValueAvailable(operationId: String): Boolean {
        return completedValues.containsKey(operationId)
    }
    
    /**
     * Cancel all active synchronization points
     */
    fun cancelAll(reason: String = "Synchronization cancelled") {
        activeSyncPoints.values.forEach { syncPoint ->
            syncPoint.cancel(reason)
        }
        activeSyncPoints.clear()
    }
    
    /**
     * Get statistics about active synchronization points
     */
    fun getStatistics(): SyncPointStatistics {
        val activeCount = activeSyncPoints.size
        val completedCount = completedValues.size
        val blockedCount = activeSyncPoints.values.count { it.isBlocked() }
        val runningCount = activeSyncPoints.values.count { it.isRunning() }
        
        return SyncPointStatistics(
            activeCount = activeCount,
            completedCount = completedCount,
            blockedCount = blockedCount,
            runningCount = runningCount
        )
    }
}

/**
 * A synchronization point that manages lazy evaluation and blocking
 */
class SynchronizationPoint(
    val id: String,
    private val dependencies: Set<String>,
    private val computation: () -> MezzeValue,
    private val context: EffectContext,
    private val manager: SynchronizationPointManager
) {
    private val state = AtomicReference<SyncPointState>(SyncPointState.PENDING)
    private val result = AtomicReference<MezzeValue>()
    private val error = AtomicReference<Throwable>()
    private val future = AtomicReference<CompletableFuture<MezzeValue>>()
    private val cancellationToken = context.cancellationToken.createChild()
    
    /**
     * Await the result of this synchronization point
     */
    fun await(): MezzeValue {
        return await(Duration.ofMinutes(5)) // Default timeout
    }
    
    /**
     * Await the result with a timeout
     */
    fun await(timeout: Duration): MezzeValue {
        // Check if already completed
        when (state.get()) {
            SyncPointState.COMPLETED -> return result.get()
            SyncPointState.FAILED -> throw RuntimeException("Synchronization point failed", error.get())
            SyncPointState.CANCELLED -> throw RuntimeException("Synchronization point was cancelled")
            else -> { /* Continue to wait */ }
        }
        
        // Start computation if not already started
        startComputationIfNeeded()
        
        // Wait for computation to actually start (with timeout)
        var currentFuture = future.get()
        var attempts = 0
        while (currentFuture == null && attempts < 100 && state.get() != SyncPointState.FAILED && state.get() != SyncPointState.CANCELLED) {
            Thread.sleep(10) // Wait 10ms
            currentFuture = future.get()
            attempts++
        }
        
        // Check if computation failed while we were waiting
        when (state.get()) {
            SyncPointState.FAILED -> throw RuntimeException("Synchronization point failed", error.get())
            SyncPointState.CANCELLED -> throw RuntimeException("Synchronization point was cancelled")
            else -> { /* Continue */ }
        }
        
        if (currentFuture != null) {
            try {
                val result = currentFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                
                // Double-check state after completion in case of race conditions
                when (state.get()) {
                    SyncPointState.COMPLETED -> return result
                    SyncPointState.FAILED -> throw RuntimeException("Synchronization point failed", error.get())
                    SyncPointState.CANCELLED -> throw RuntimeException("Synchronization point was cancelled")
                    else -> return result // Fallback
                }
            } catch (e: Exception) {
                // Check if the state was already set to failed by the computation
                when (state.get()) {
                    SyncPointState.FAILED -> throw RuntimeException("Synchronization point failed", error.get())
                    SyncPointState.CANCELLED -> throw RuntimeException("Synchronization point was cancelled")
                    else -> {
                        state.set(SyncPointState.FAILED)
                        error.set(e)
                        throw RuntimeException("Failed to await synchronization point: $id", e)
                    }
                }
            }
        } else {
            throw RuntimeException("No computation started for synchronization point: $id")
        }
    }
    
    /**
     * Try to get the result without blocking
     */
    fun tryGet(): MezzeValue? {
        return when (state.get()) {
            SyncPointState.COMPLETED -> result.get()
            else -> null
        }
    }
    
    /**
     * Check if this synchronization point is blocked waiting for dependencies
     */
    fun isBlocked(): Boolean {
        return state.get() == SyncPointState.BLOCKED
    }
    
    /**
     * Check if this synchronization point is currently running
     */
    fun isRunning(): Boolean {
        return state.get() == SyncPointState.RUNNING
    }
    
    /**
     * Check if this synchronization point is completed
     */
    fun isCompleted(): Boolean {
        return state.get() == SyncPointState.COMPLETED
    }
    
    /**
     * Cancel this synchronization point
     */
    fun cancel(reason: String = "Synchronization point cancelled") {
        if (state.compareAndSet(SyncPointState.PENDING, SyncPointState.CANCELLED) ||
            state.compareAndSet(SyncPointState.BLOCKED, SyncPointState.CANCELLED) ||
            state.compareAndSet(SyncPointState.RUNNING, SyncPointState.CANCELLED)) {
            
            cancellationToken.cancel(reason)
            future.get()?.cancel(true)
        }
    }
    
    /**
     * Start computation if not already started
     */
    private fun startComputationIfNeeded() {
        if (state.compareAndSet(SyncPointState.PENDING, SyncPointState.BLOCKED)) {
            // Check if all dependencies are available
            val unavailableDeps = dependencies.filter { !manager.isValueAvailable(it) }

            if (unavailableDeps.isEmpty()) {
                // All dependencies available, start computation
                startComputation()
            } else {
                // Wait for dependencies in a virtual thread
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                val dependencyFuture = CompletableFuture.supplyAsync({
                    waitForDependencies(unavailableDeps)
                }, executor)

                dependencyFuture.whenComplete { _, _ ->
                    executor.shutdown() // Clean up executor
                    if (!cancellationToken.isCancelled()) {
                        startComputation()
                    }
                }
            }
        }
    }
    
    /**
     * Wait for dependencies to become available
     */
    private fun waitForDependencies(unavailableDeps: List<String>) {
        unavailableDeps.forEach { depId ->
            try {
                cancellationToken.throwIfCancelled()
                manager.getValue(depId) // This will block until the dependency is available
            } catch (e: Exception) {
                state.set(SyncPointState.FAILED)
                error.set(e)
                throw e
            }
        }
    }
    
    /**
     * Start the actual computation
     */
    private fun startComputation() {
        if (state.compareAndSet(SyncPointState.BLOCKED, SyncPointState.RUNNING) ||
            state.compareAndSet(SyncPointState.PENDING, SyncPointState.RUNNING)) {

            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val computationFuture = CompletableFuture.supplyAsync({
                executeComputation()
            }, executor)

            future.set(computationFuture)

            // Handle completion
            computationFuture.whenComplete { value, throwable ->
                executor.shutdown() // Clean up executor
                if (throwable != null) {
                    state.set(SyncPointState.FAILED)
                    error.set(throwable)
                } else {
                    state.set(SyncPointState.COMPLETED)
                    result.set(value)
                    manager.markCompleted(id, value)
                }
            }
        }
    }
    
    /**
     * Execute the computation with proper error handling and cancellation
     */
    private fun executeComputation(): MezzeValue {
        try {
            cancellationToken.throwIfCancelled()
            
            // Execute computation directly for now (simplified)
            return computation()
        } catch (e: Exception) {
            if (cancellationToken.isCancelled()) {
                throw RuntimeException("Computation was cancelled: ${cancellationToken.getCancellationReason()}", e)
            } else {
                // Re-throw the original exception to preserve its type
                throw e
            }
        }
    }
}

/**
 * States of a synchronization point
 */
enum class SyncPointState {
    PENDING,    // Not started yet
    BLOCKED,    // Waiting for dependencies
    RUNNING,    // Currently executing
    COMPLETED,  // Successfully completed
    FAILED,     // Failed with error
    CANCELLED   // Cancelled
}

/**
 * Statistics about synchronization points
 */
data class SyncPointStatistics(
    val activeCount: Int,
    val completedCount: Int,
    val blockedCount: Int,
    val runningCount: Int
) {
    val totalCount: Int get() = activeCount + completedCount
    val completionRate: Double get() = if (totalCount > 0) completedCount.toDouble() / totalCount else 0.0
}

/**
 * Lazy synchronization coordinator that manages multiple synchronization points
 */
class LazySynchronizationCoordinator(
    private val concurrencyManager: LoomConcurrencyManager
) {
    private val syncPointManager = SynchronizationPointManager(concurrencyManager)
    private val operationGraph = mutableMapOf<String, Set<String>>() // operation -> dependencies
    
    /**
     * Register an operation with its dependencies
     */
    fun registerOperation(
        operationId: String,
        dependencies: Set<String>,
        computation: () -> MezzeValue,
        context: EffectContext
    ): SynchronizationPoint {
        operationGraph[operationId] = dependencies
        return syncPointManager.createSyncPoint(operationId, dependencies, computation, context)
    }
    
    /**
     * Execute operations with lazy synchronization
     */
    fun executeWithLazySync(
        operations: Map<String, () -> MezzeValue>,
        dependencies: Map<String, Set<String>>,
        context: EffectContext
    ): Map<String, MezzeValue> {
        // Register all operations
        val syncPoints = operations.map { (operationId, computation) ->
            val deps = dependencies[operationId] ?: emptySet()
            operationId to registerOperation(operationId, deps, computation, context)
        }.toMap()
        
        // Execute all operations (they will start lazily as needed)
        val results = mutableMapOf<String, MezzeValue>()
        
        try {
            operations.keys.forEach { operationId ->
                results[operationId] = syncPointManager.getValue(operationId)
            }
        } catch (e: Exception) {
            // Cancel all remaining operations on error
            syncPointManager.cancelAll("Execution failed: ${e.message}")
            throw e
        }
        
        return results
    }
    
    /**
     * Get the value of an operation, starting it if necessary
     */
    fun getValue(operationId: String): MezzeValue {
        return syncPointManager.getValue(operationId)
    }
    
    /**
     * Check if an operation is completed
     */
    fun isCompleted(operationId: String): Boolean {
        return syncPointManager.isValueAvailable(operationId)
    }
    
    /**
     * Cancel all operations
     */
    fun cancelAll(reason: String = "Coordinator cancelled") {
        syncPointManager.cancelAll(reason)
    }
    
    /**
     * Get statistics about the coordination
     */
    fun getStatistics(): SyncPointStatistics {
        return syncPointManager.getStatistics()
    }
    
    /**
     * Create a dependency-aware execution plan
     */
    fun createExecutionPlan(
        operations: Set<String>,
        dependencies: Map<String, Set<String>>
    ): LazyExecutionPlan {
        return LazyExecutionPlan(
            operations = operations,
            dependencies = dependencies,
            coordinator = this
        )
    }
}

/**
 * Execution plan that respects dependencies and executes lazily
 */
class LazyExecutionPlan(
    private val operations: Set<String>,
    private val dependencies: Map<String, Set<String>>,
    private val coordinator: LazySynchronizationCoordinator
) {
    
    /**
     * Execute the plan, returning results for all operations
     */
    fun execute(): Map<String, MezzeValue> {
        val results = mutableMapOf<String, MezzeValue>()
        
        // Execute operations in dependency order, but lazily
        operations.forEach { operationId ->
            results[operationId] = coordinator.getValue(operationId)
        }
        
        return results
    }
    
    /**
     * Execute and return only the result of a specific operation
     */
    fun executeForResult(resultOperationId: String): MezzeValue {
        if (resultOperationId !in operations) {
            throw RuntimeException("Operation not found in plan: $resultOperationId")
        }
        
        return coordinator.getValue(resultOperationId)
    }
    
    /**
     * Get operations that have no dependencies
     */
    fun getRootOperations(): Set<String> {
        return operations.filter { op ->
            dependencies[op]?.isEmpty() ?: true
        }.toSet()
    }
    
    /**
     * Get operations that depend on the given operation
     */
    fun getDependents(operationId: String): Set<String> {
        return dependencies.entries
            .filter { (_, deps) -> operationId in deps }
            .map { (op, _) -> op }
            .toSet()
    }
    
    /**
     * Get the critical path (longest dependency chain)
     */
    fun getCriticalPath(): List<String> {
        // Find the longest path to any leaf operation (operations with no dependents)
        fun findLongestPathTo(operationId: String, visited: MutableSet<String>): List<String> {
            if (operationId in visited) return emptyList() // Cycle detection
            visited.add(operationId)
            
            val deps = dependencies[operationId] ?: emptySet()
            if (deps.isEmpty()) {
                visited.remove(operationId)
                return listOf(operationId)
            }
            
            val longestDepPath = deps.map { dep ->
                findLongestPathTo(dep, visited)
            }.maxByOrNull { it.size } ?: emptyList()
            
            visited.remove(operationId)
            return longestDepPath + operationId
        }
        
        // Find leaf operations (operations that no other operation depends on)
        val allDependencies = dependencies.values.flatten().toSet()
        val leafOperations = operations.filter { it !in allDependencies }
        
        // Find the longest path to any leaf operation
        return leafOperations.map { leaf ->
            findLongestPathTo(leaf, mutableSetOf())
        }.maxByOrNull { it.size } ?: emptyList()
    }
}