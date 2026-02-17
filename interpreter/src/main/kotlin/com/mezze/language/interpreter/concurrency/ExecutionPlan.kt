package com.mezze.language.interpreter.concurrency

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Represents a group of effect operations that can be executed in parallel
 */
data class ParallelGroup(
    val effects: List<EffectOperation>,
    val maxConcurrency: Int,
    val timeout: Duration? = null
) {
    /**
     * Execute all effects in this group in parallel
     */
    fun executeInParallel(concurrencyManager: LoomConcurrencyManager): List<Any> {
        if (effects.isEmpty()) return emptyList()

        // Single effect - execute directly
        if (effects.size == 1) {
            return listOf(effects.first().operation())
        }

        // Multiple effects - execute in parallel using virtual threads
        // Create one executor for the group and shut it down after use
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            val futures = effects.map { effect ->
                CompletableFuture.supplyAsync({
                    effect.operation()
                }, executor)
            }

            try {
                if (timeout != null) {
                    futures.map { it.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) }
                } else {
                    futures.map { it.get() }
                }
            } catch (e: Exception) {
                // Cancel remaining futures on error
                futures.forEach { it.cancel(true) }
                throw e
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    
    /**
     * Get the estimated execution time for this group (max of all effects)
     */
    fun getEstimatedDuration(): Duration {
        return effects.maxOfOrNull { it.estimatedDuration } ?: Duration.ZERO
    }
    
    /**
     * Check if this group can be merged with another group
     */
    fun canMergeWith(other: ParallelGroup): Boolean {
        return effects.all { thisEffect ->
            other.effects.all { otherEffect ->
                thisEffect.canRunInParallelWith(otherEffect)
            }
        }
    }
}

/**
 * Represents a complete execution plan for a set of effect operations
 */
data class ExecutionPlan(
    val parallelGroups: List<ParallelGroup>,
    val dependencies: DependencyGraph,
    val estimatedExecutionTime: Duration
) {
    /**
     * Execute the entire plan, respecting dependencies between groups
     */
    fun execute(concurrencyManager: LoomConcurrencyManager): List<Any> {
        val results = mutableListOf<Any>()
        val completedOperations = mutableSetOf<EffectOperation>()

        // Execute groups in dependency order
        val remainingGroups = parallelGroups.toMutableList()

        while (remainingGroups.isNotEmpty()) {
            // Find groups that can execute now (all dependencies satisfied)
            val readyGroups = remainingGroups.filter { group ->
                group.effects.all { effect ->
                    val deps = dependencies.dependencies[effect] ?: emptySet()
                    deps.all { it in completedOperations }
                }
            }

            if (readyGroups.isEmpty()) {
                throw IllegalStateException("Circular dependency detected in execution plan")
            }

            // Execute ready groups in parallel
            val groupResults = readyGroups.map { group ->
                group.executeInParallel(concurrencyManager)
            }

            // Collect results and mark operations as completed
            groupResults.forEach { groupResult ->
                results.addAll(groupResult)
            }

            readyGroups.forEach { group ->
                completedOperations.addAll(group.effects)
                remainingGroups.remove(group)
            }
        }

        return results
    }
    
    /**
     * Get the critical path (longest dependency chain) in the execution plan
     */
    fun getCriticalPath(): List<EffectOperation> {
        val criticalPath = mutableListOf<EffectOperation>()
        var currentOps = dependencies.getRootOperations()
        
        while (currentOps.isNotEmpty()) {
            // Find the operation with the longest remaining path
            val longestPathOp = currentOps.maxByOrNull { op ->
                calculateRemainingPathLength(op, dependencies)
            }
            
            if (longestPathOp != null) {
                criticalPath.add(longestPathOp)
                currentOps = dependencies.getDependents(longestPathOp)
            } else {
                break
            }
        }
        
        return criticalPath
    }
    
    private fun calculateRemainingPathLength(
        operation: EffectOperation,
        dependencies: DependencyGraph
    ): Int {
        val dependents = dependencies.getDependents(operation)
        if (dependents.isEmpty()) return 1
        
        return 1 + dependents.maxOfOrNull { 
            calculateRemainingPathLength(it, dependencies) 
        }!!
    }
    
    /**
     * Optimize the execution plan by merging compatible parallel groups
     */
    fun optimize(): ExecutionPlan {
        val optimizedGroups = mutableListOf<ParallelGroup>()
        val remainingGroups = parallelGroups.toMutableList()

        while (remainingGroups.isNotEmpty()) {
            val current = remainingGroups.removeFirst()
            var merged = current

            // Try to merge with other groups
            val iterator = remainingGroups.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                // Check if groups can merge based on variable conflicts AND dependency ordering
                if (merged.canMergeWith(candidate) && !hasDependencyBetween(merged, candidate)) {
                    merged = ParallelGroup(
                        effects = merged.effects + candidate.effects,
                        maxConcurrency = merged.maxConcurrency + candidate.maxConcurrency,
                        timeout = maxOf(
                            merged.timeout ?: Duration.ZERO,
                            candidate.timeout ?: Duration.ZERO
                        ).takeIf { it > Duration.ZERO }
                    )
                    iterator.remove()
                }
            }

            optimizedGroups.add(merged)
        }

        return copy(parallelGroups = optimizedGroups)
    }

    /**
     * Check if there is a dependency (direct or transitive) between any operation in group1 and any operation in group2
     */
    private fun hasDependencyBetween(group1: ParallelGroup, group2: ParallelGroup): Boolean {
        // Check if any operation in group2 (transitively) depends on any operation in group1
        for (op2 in group2.effects) {
            if (hasTransitiveDependency(op2, group1.effects.toSet())) {
                return true
            }
        }

        // Check if any operation in group1 (transitively) depends on any operation in group2
        for (op1 in group1.effects) {
            if (hasTransitiveDependency(op1, group2.effects.toSet())) {
                return true
            }
        }

        return false
    }

    /**
     * Check if an operation transitively depends on any operation in the target set
     */
    private fun hasTransitiveDependency(operation: EffectOperation, targets: Set<EffectOperation>): Boolean {
        val visited = mutableSetOf<EffectOperation>()
        val queue = mutableListOf(operation)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited) continue
            visited.add(current)

            val deps = dependencies.dependencies[current] ?: emptySet()
            if (deps.any { it in targets }) {
                return true
            }

            queue.addAll(deps)
        }

        return false
    }
    
    /**
     * Get statistics about the execution plan
     */
    fun getStatistics(): ExecutionPlanStatistics {
        val totalOperations = parallelGroups.sumOf { it.effects.size }
        val maxParallelism = parallelGroups.maxOfOrNull { it.maxConcurrency } ?: 0
        val sequentialTime = dependencies.operations.sumOf { it.estimatedDuration.toMillis() }
        val parallelTime = estimatedExecutionTime.toMillis()
        val speedupRatio = if (parallelTime > 0) sequentialTime.toDouble() / parallelTime else 1.0
        
        return ExecutionPlanStatistics(
            totalOperations = totalOperations,
            parallelGroups = parallelGroups.size,
            maxParallelism = maxParallelism,
            estimatedSequentialTime = Duration.ofMillis(sequentialTime),
            estimatedParallelTime = estimatedExecutionTime,
            estimatedSpeedup = speedupRatio
        )
    }
}

/**
 * Statistics about an execution plan
 */
data class ExecutionPlanStatistics(
    val totalOperations: Int,
    val parallelGroups: Int,
    val maxParallelism: Int,
    val estimatedSequentialTime: Duration,
    val estimatedParallelTime: Duration,
    val estimatedSpeedup: Double
)