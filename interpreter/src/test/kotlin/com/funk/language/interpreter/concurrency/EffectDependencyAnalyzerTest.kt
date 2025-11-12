package com.funk.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

class EffectDependencyAnalyzerTest {
    
    private lateinit var analyzer: EffectDependencyAnalyzer
    
    @BeforeEach
    fun setup() {
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