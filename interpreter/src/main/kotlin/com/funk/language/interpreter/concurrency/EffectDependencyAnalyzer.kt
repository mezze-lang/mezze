package com.funk.language.interpreter.concurrency

import java.time.Duration

/**
 * Represents an effect operation that can be analyzed for dependencies
 */
data class EffectOperation(
    val id: String,
    val effectType: String,
    val readVariables: Set<String>,
    val writeVariables: Set<String>,
    val operation: () -> Any,
    val estimatedDuration: Duration = Duration.ofMillis(1)
) {
    /**
     * Check if this operation can run in parallel with another operation
     */
    fun canRunInParallelWith(other: EffectOperation): Boolean {
        // Operations can run in parallel if they don't have conflicting variable access
        val hasWriteConflict = writeVariables.intersect(other.writeVariables).isNotEmpty()
        val hasReadWriteConflict = writeVariables.intersect(other.readVariables).isNotEmpty() ||
                                  readVariables.intersect(other.writeVariables).isNotEmpty()
        
        return !hasWriteConflict && !hasReadWriteConflict
    }
}

/**
 * Represents dependencies between effect operations
 */
data class EffectDependency(
    val operation: EffectOperation,
    val dependencies: Set<EffectOperation>,
    val canRunInParallel: Boolean
)

/**
 * Graph structure representing dependencies between effect operations
 */
data class DependencyGraph(
    val operations: List<EffectOperation>,
    val dependencies: Map<EffectOperation, Set<EffectOperation>>,
    val parallelizableGroups: List<ParallelGroup>
) {
    /**
     * Get operations that have no dependencies and can start immediately
     */
    fun getRootOperations(): Set<EffectOperation> {
        return operations.filter { op ->
            dependencies[op]?.isEmpty() ?: true
        }.toSet()
    }

    /**
     * Get operations that depend on the given operation
     */
    fun getDependents(operation: EffectOperation): Set<EffectOperation> {
        return dependencies.entries
            .filter { (_, deps) -> operation in deps }
            .map { (op, _) -> op }
            .toSet()
    }
}

/**
 * Analyzes effect operations to detect dependencies and parallelization opportunities
 */
class EffectDependencyAnalyzer {
    
    /**
     * Analyze a list of effect operations to build a dependency graph
     */
    fun analyzeDependencies(effects: List<EffectOperation>): DependencyGraph {
        val dependencies = mutableMapOf<EffectOperation, MutableSet<EffectOperation>>()
        
        // Initialize empty dependency sets
        effects.forEach { op ->
            dependencies[op] = mutableSetOf()
        }
        
        // Build dependencies based on variable access patterns
        for (i in effects.indices) {
            for (j in i + 1 until effects.size) {
                val current = effects[i]
                val next = effects[j]

                // If next operation reads variables that current writes, next depends on current
                if (current.writeVariables.intersect(next.readVariables).isNotEmpty()) {
                    dependencies[next]?.add(current)
                }

                // If next operation writes variables that current reads, next depends on current
                if (current.readVariables.intersect(next.writeVariables).isNotEmpty()) {
                    dependencies[next]?.add(current)
                }

                // If both write to same variables, next depends on current (ordering matters)
                if (current.writeVariables.intersect(next.writeVariables).isNotEmpty()) {
                    dependencies[next]?.add(current)
                }
            }
        }

        // Find parallelizable groups
        val parallelizableGroups = findParallelizableGroups(effects, dependencies)

        return DependencyGraph(
            operations = effects,
            dependencies = dependencies.mapValues { it.value.toSet() },
            parallelizableGroups = parallelizableGroups
        )
    }
    
    /**
     * Find groups of operations that can run in parallel
     */
    fun findParallelizableGroups(
        operations: List<EffectOperation>,
        dependencies: Map<EffectOperation, Set<EffectOperation>>
    ): List<ParallelGroup> {
        val groups = mutableListOf<ParallelGroup>()
        val processed = mutableSetOf<EffectOperation>()
        
        // Group operations by their dependency level
        val levels = mutableMapOf<Int, MutableList<EffectOperation>>()

        operations.forEach { op ->
            val level = calculateDependencyLevel(op, dependencies)
            levels.computeIfAbsent(level) { mutableListOf() }.add(op)
        }

        // Create parallel groups for each level
        levels.entries.sortedBy { it.key }.forEach { (level, ops) ->
            if (ops.size > 1) {
                // Find subgroups within this level that can truly run in parallel
                val parallelSubgroups = findParallelSubgroups(ops)
                groups.addAll(parallelSubgroups)
            } else {
                // Single operation - create a group of size 1
                groups.add(ParallelGroup(
                    effects = ops,
                    maxConcurrency = 1,
                    timeout = null  // No timeout - let operations run as long as needed
                ))
            }
        }
        
        return groups
    }
    
    private fun calculateDependencyLevel(
        operation: EffectOperation,
        dependencies: Map<EffectOperation, Set<EffectOperation>>
    ): Int {
        val deps = dependencies[operation] ?: emptySet()
        if (deps.isEmpty()) return 0
        
        return deps.maxOfOrNull { calculateDependencyLevel(it, dependencies) + 1 } ?: 0
    }
    
    private fun findParallelSubgroups(operations: List<EffectOperation>): List<ParallelGroup> {
        val groups = mutableListOf<ParallelGroup>()
        val remaining = operations.toMutableList()
        
        while (remaining.isNotEmpty()) {
            val current = remaining.removeFirst()
            val parallelOps = mutableListOf(current)
            
            // Find operations that can run in parallel with current
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (parallelOps.all { it.canRunInParallelWith(candidate) }) {
                    parallelOps.add(candidate)
                    iterator.remove()
                }
            }
            
            groups.add(ParallelGroup(
                effects = parallelOps,
                maxConcurrency = parallelOps.size,
                timeout = null  // No timeout - let operations run as long as needed
            ))
        }
        
        return groups
    }
    
    /**
     * Create execution plan from dependency analysis
     */
    fun createExecutionPlan(graph: DependencyGraph): ExecutionPlan {
        val estimatedTime = graph.parallelizableGroups
            .maxOfOrNull { group ->
                group.effects.maxOfOrNull { it.estimatedDuration } ?: Duration.ZERO
            } ?: Duration.ZERO

        return ExecutionPlan(
            parallelGroups = graph.parallelizableGroups,
            dependencies = graph,
            estimatedExecutionTime = estimatedTime
        )
    }
}