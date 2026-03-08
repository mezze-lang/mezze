package com.mezze.interpreter.v2.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

/**
 * Represents an effect operation that can be analyzed for dependencies.
 *
 * This is a self-contained implementation for V2 testing purposes.
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
 * Represents a group of effect operations that can be executed in parallel
 */
data class ParallelGroup(
    val effects: List<EffectOperation>,
    val maxConcurrency: Int,
    val timeout: Duration? = null
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
 * Represents a complete execution plan for a set of effect operations
 */
data class ExecutionPlan(
    val parallelGroups: List<ParallelGroup>,
    val dependencies: DependencyGraph,
    val estimatedExecutionTime: Duration
)

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
                    timeout = null
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
                timeout = null
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

/**
 * Tests for EffectDependencyAnalyzer - migrated from V1 to V2.
 *
 * This test validates the dependency analysis logic for effect operations,
 * which is used for automatic parallelization of independent effects.
 */
class EffectDependencyAnalyzerTest {

    private lateinit var analyzer: EffectDependencyAnalyzer

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        analyzer = EffectDependencyAnalyzer()
    }

    @Test
    fun `should detect no dependencies for independent operations`() {
        val op1 = EffectOperation("1", "IO", setOf("a"), setOf("x"), { "result1" })
        val op2 = EffectOperation("2", "IO", setOf("b"), setOf("y"), { "result2" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2))

        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)
        assertTrue(graph.dependencies[op2]?.isEmpty() ?: true)
        assertTrue(graph.parallelizableGroups.isNotEmpty())
    }

    @Test
    fun `should detect read-after-write dependency`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("x"), { "write_x" })
        val op2 = EffectOperation("2", "IO", setOf("x"), emptySet(), { "read_x" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2))

        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)
    }

    @Test
    fun `should detect write-after-read dependency`() {
        val op1 = EffectOperation("1", "IO", setOf("x"), emptySet(), { "read_x" })
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("x"), { "write_x" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2))

        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)
    }

    @Test
    fun `should detect write-after-write dependency`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("x"), { "write_x_1" })
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("x"), { "write_x_2" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2))

        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)
    }

    @Test
    fun `should handle complex dependency chains`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "write_a" })
        val op2 = EffectOperation("2", "IO", setOf("a"), setOf("b"), { "read_a_write_b" })
        val op3 = EffectOperation("3", "IO", setOf("b"), setOf("c"), { "read_b_write_c" })
        val op4 = EffectOperation("4", "IO", emptySet(), setOf("d"), { "write_d" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2, op3, op4))

        // op1 has no dependencies
        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)

        // op2 depends on op1
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)

        // op3 depends on op2
        assertTrue(graph.dependencies[op3]?.contains(op2) ?: false)

        // op4 is independent
        assertTrue(graph.dependencies[op4]?.isEmpty() ?: true)

        // Should identify op1 and op4 as root operations
        val roots = graph.getRootOperations()
        assertTrue(roots.contains(op1))
        assertTrue(roots.contains(op4))
        assertEquals(2, roots.size)
    }

    @Test
    fun `should find parallel groups correctly`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "op1" })
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("a"), setOf("c"), { "op3" })
        val op4 = EffectOperation("4", "IO", setOf("b"), setOf("d"), { "op4" })

        val operations = listOf(op1, op2, op3, op4)
        val dependencies = mapOf(
            op1 to emptySet<EffectOperation>(),
            op2 to emptySet<EffectOperation>(),
            op3 to setOf(op1),
            op4 to setOf(op2)
        )

        val groups = analyzer.findParallelizableGroups(operations, dependencies)

        // Should have groups for different dependency levels
        assertTrue(groups.isNotEmpty())

        // op1 and op2 should be in parallel groups (level 0)
        // op3 and op4 should be in parallel groups (level 1)
        val level0Ops = groups.flatMap { it.effects }.filter { it == op1 || it == op2 }
        val level1Ops = groups.flatMap { it.effects }.filter { it == op3 || it == op4 }

        assertEquals(2, level0Ops.size)
        assertEquals(2, level1Ops.size)
    }

    @Test
    fun `should create execution plan with correct structure`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "op1" }, Duration.ofMillis(10))
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "op2" }, Duration.ofMillis(20))
        val op3 = EffectOperation("3", "IO", setOf("a", "b"), emptySet(), { "op3" }, Duration.ofMillis(15))

        val graph = analyzer.analyzeDependencies(listOf(op1, op2, op3))
        val plan = analyzer.createExecutionPlan(graph)

        assertNotNull(plan)
        assertTrue(plan.parallelGroups.isNotEmpty())
        assertTrue(plan.estimatedExecutionTime.toMillis() > 0)

        // Should have reasonable execution time estimate
        assertTrue(plan.estimatedExecutionTime.toMillis() >= 15) // At least the longest single operation
    }

    @Test
    fun `should handle operations with multiple variable dependencies`() {
        val op1 = EffectOperation("1", "IO", setOf("a", "b"), setOf("x", "y"), { "op1" })
        val op2 = EffectOperation("2", "IO", setOf("x"), setOf("z"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("c"), setOf("w"), { "op3" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2, op3))

        // op2 should depend on op1 (reads x which op1 writes)
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)

        // op3 should be independent
        assertTrue(graph.dependencies[op3]?.isEmpty() ?: true)
    }

    @Test
    fun `should detect when operations can run in parallel`() {
        val op1 = EffectOperation("1", "IO", setOf("a"), setOf("x"), { "op1" })
        val op2 = EffectOperation("2", "IO", setOf("b"), setOf("y"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("x"), setOf("z"), { "op3" })

        // op1 and op2 can run in parallel (no shared variables)
        assertTrue(op1.canRunInParallelWith(op2))

        // op1 and op3 cannot run in parallel (op3 reads what op1 writes)
        assertFalse(op1.canRunInParallelWith(op3))

        // op2 and op3 can run in parallel
        assertTrue(op2.canRunInParallelWith(op3))
    }

    @Test
    fun `should handle empty operation list`() {
        val graph = analyzer.analyzeDependencies(emptyList())

        assertTrue(graph.operations.isEmpty())
        assertTrue(graph.dependencies.isEmpty())
        assertTrue(graph.parallelizableGroups.isEmpty())
        assertTrue(graph.getRootOperations().isEmpty())
    }

    @Test
    fun `should handle single operation`() {
        val op = EffectOperation("1", "IO", setOf("a"), setOf("b"), { "single" })
        val graph = analyzer.analyzeDependencies(listOf(op))

        assertEquals(1, graph.operations.size)
        assertTrue(graph.dependencies[op]?.isEmpty() ?: true)
        assertEquals(setOf(op), graph.getRootOperations())
    }

    @Test
    fun `should find dependents correctly`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("x"), { "op1" })
        val op2 = EffectOperation("2", "IO", setOf("x"), setOf("y"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("x"), setOf("z"), { "op3" })

        val graph = analyzer.analyzeDependencies(listOf(op1, op2, op3))

        val dependents = graph.getDependents(op1)
        assertTrue(dependents.contains(op2))
        assertTrue(dependents.contains(op3))
        assertEquals(2, dependents.size)
    }
}
