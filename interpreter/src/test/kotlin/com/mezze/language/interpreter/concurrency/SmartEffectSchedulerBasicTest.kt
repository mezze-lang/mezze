package com.mezze.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

/**
 * Basic test to verify Smart Effect Scheduler implementation without dependencies on other modules
 */
class SmartEffectSchedulerBasicTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var analyzer: EffectDependencyAnalyzer
    
    @BeforeEach
    fun setup() {
        concurrencyManager = LoomConcurrencyManager()
        analyzer = EffectDependencyAnalyzer()
    }
    
    @Test
    fun `should create effect operations`() {
        val op = EffectOperation(
            id = "test1",
            effectType = "IO",
            readVariables = setOf("a"),
            writeVariables = setOf("b"),
            operation = { "result" }
        )
        
        assertEquals("test1", op.id)
        assertEquals("IO", op.effectType)
        assertEquals(setOf("a"), op.readVariables)
        assertEquals(setOf("b"), op.writeVariables)
        assertEquals("result", op.operation())
    }
    
    @Test
    fun `should detect parallel operations`() {
        val op1 = EffectOperation("1", "IO", setOf("a"), setOf("x"), { "result1" })
        val op2 = EffectOperation("2", "IO", setOf("b"), setOf("y"), { "result2" })
        
        assertTrue(op1.canRunInParallelWith(op2))
        assertTrue(op2.canRunInParallelWith(op1))
    }
    
    @Test
    fun `should detect dependent operations`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("x"), { "write_x" })
        val op2 = EffectOperation("2", "IO", setOf("x"), emptySet(), { "read_x" })
        
        assertFalse(op1.canRunInParallelWith(op2))
        assertFalse(op2.canRunInParallelWith(op1))
    }
    
    @Test
    fun `should analyze dependencies correctly`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("x"), { "op1" })
        val op2 = EffectOperation("2", "IO", setOf("x"), setOf("y"), { "op2" })
        val op3 = EffectOperation("3", "IO", emptySet(), setOf("z"), { "op3" })
        
        val graph = analyzer.analyzeDependencies(listOf(op1, op2, op3))
        
        // op1 should have no dependencies
        assertTrue(graph.dependencies[op1]?.isEmpty() ?: true)
        
        // op2 should depend on op1
        assertTrue(graph.dependencies[op2]?.contains(op1) ?: false)
        
        // op3 should have no dependencies
        assertTrue(graph.dependencies[op3]?.isEmpty() ?: true)
        
        // Root operations should be op1 and op3
        val roots = graph.getRootOperations()
        assertTrue(roots.contains(op1))
        assertTrue(roots.contains(op3))
        assertEquals(2, roots.size)
    }
    
    @Test
    fun `should create parallel groups`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "op1" })
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("a"), setOf("c"), { "op3" })
        
        val operations = listOf(op1, op2, op3)
        val dependencies = mapOf(
            op1 to emptySet<EffectOperation>(),
            op2 to emptySet<EffectOperation>(),
            op3 to setOf(op1)
        )
        
        val groups = analyzer.findParallelizableGroups(operations, dependencies)
        
        assertFalse(groups.isEmpty())
        
        // Should have groups for different dependency levels
        val allEffects = groups.flatMap { it.effects }
        assertEquals(3, allEffects.size)
        assertTrue(allEffects.contains(op1))
        assertTrue(allEffects.contains(op2))
        assertTrue(allEffects.contains(op3))
    }
    
    @Test
    fun `should create execution plan`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "op1" }, Duration.ofMillis(10))
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "op2" }, Duration.ofMillis(20))
        
        val graph = analyzer.analyzeDependencies(listOf(op1, op2))
        val plan = analyzer.createExecutionPlan(graph)
        
        assertNotNull(plan)
        assertFalse(plan.parallelGroups.isEmpty())
        assertTrue(plan.estimatedExecutionTime.toMillis() >= 0)
    }
    
    @Test
    fun `should handle empty operations list`() {
        val graph = analyzer.analyzeDependencies(emptyList())
        
        assertTrue(graph.operations.isEmpty())
        assertTrue(graph.dependencies.isEmpty())
        assertTrue(graph.parallelizableGroups.isEmpty())
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
    fun `should create parallel group with correct properties`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "result1" }, Duration.ofMillis(10))
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "result2" }, Duration.ofMillis(20))
        
        val group = ParallelGroup(
            effects = listOf(op1, op2),
            maxConcurrency = 2,
            timeout = Duration.ofMillis(100)
        )
        
        assertEquals(2, group.effects.size)
        assertEquals(2, group.maxConcurrency)
        assertEquals(Duration.ofMillis(100), group.timeout)
        assertEquals(Duration.ofMillis(20), group.getEstimatedDuration())
    }
    
    @Test
    fun `should check if groups can merge`() {
        val op1 = EffectOperation("1", "IO", emptySet(), setOf("a"), { "op1" })
        val op2 = EffectOperation("2", "IO", emptySet(), setOf("b"), { "op2" })
        val op3 = EffectOperation("3", "IO", setOf("a"), emptySet(), { "op3" })
        
        val group1 = ParallelGroup(listOf(op1), 1)
        val group2 = ParallelGroup(listOf(op2), 1)
        val group3 = ParallelGroup(listOf(op3), 1)
        
        // group1 and group2 can merge (independent operations)
        assertTrue(group1.canMergeWith(group2))
        
        // group1 and group3 cannot merge (op3 reads what op1 writes)
        assertFalse(group1.canMergeWith(group3))
    }
}