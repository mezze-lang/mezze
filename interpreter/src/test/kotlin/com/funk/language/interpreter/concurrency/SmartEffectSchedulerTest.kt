package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.effects.EffectContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class SmartEffectSchedulerTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var scheduler: SmartEffectScheduler
    private lateinit var effectContext: EffectContext
    
    @BeforeEach
    fun setup() {
        concurrencyManager = LoomConcurrencyManager()
        scheduler = SmartEffectScheduler(concurrencyManager)
        effectContext = EffectContext.empty()
    }
    
    @Test
    fun `should detect independent operations`() {
        // Create independent operations (no shared variables)
        val op1 = scheduler.createEffectOperation(
            effectType = "IO",
            readVars = setOf("a"),
            writeVars = setOf("x"),
            operation = { "result1" }
        )
        
        val op2 = scheduler.createEffectOperation(
            effectType = "IO", 
            readVars = setOf("b"),
            writeVars = setOf("y"),
            operation = { "result2" }
        )
        
        val block = EffectBlock(listOf(op1, op2), effectContext)
        val plan = scheduler.analyzeEffectBlock(block)
        
        // Should create parallel groups since operations are independent
        assertTrue(plan.parallelGroups.isNotEmpty())
        
        // Check if operations can run in parallel
        assertTrue(op1.canRunInParallelWith(op2))
    }
    
    @Test
    fun `should detect dependent operations`() {
        // Create dependent operations (op2 reads what op1 writes)
        val op1 = scheduler.createEffectOperation(
            effectType = "IO",
            readVars = emptySet(),
            writeVars = setOf("x"),
            operation = { "result1" }
        )
        
        val op2 = scheduler.createEffectOperation(
            effectType = "IO",
            readVars = setOf("x"),
            writeVars = setOf("y"),
            operation = { "result2" }
        )
        
        val block = EffectBlock(listOf(op1, op2), effectContext)
        val plan = scheduler.analyzeEffectBlock(block)
        
        // Should detect dependency
        val dependencies = plan.dependencies.dependencies[op2]
        assertNotNull(dependencies)
        assertTrue(dependencies!!.contains(op1))
        
        // Operations should not be able to run in parallel
        assertFalse(op1.canRunInParallelWith(op2))
    }
    
    @Test
    fun `should execute independent operations in parallel`() {
        val executionOrder = mutableListOf<String>()
        val counter = AtomicInteger(0)
        
        // Create operations that track execution order
        val op1 = scheduler.createEffectOperation(
            effectType = "Test",
            readVars = emptySet(),
            writeVars = setOf("a"),
            estimatedDuration = Duration.ofMillis(50),
            operation = {
                Thread.sleep(50)
                val order = counter.incrementAndGet()
                synchronized(executionOrder) {
                    executionOrder.add("op1_$order")
                }
                "result1"
            }
        )
        
        val op2 = scheduler.createEffectOperation(
            effectType = "Test",
            readVars = emptySet(), 
            writeVars = setOf("b"),
            estimatedDuration = Duration.ofMillis(50),
            operation = {
                Thread.sleep(50)
                val order = counter.incrementAndGet()
                synchronized(executionOrder) {
                    executionOrder.add("op2_$order")
                }
                "result2"
            }
        )
        
        val block = EffectBlock(listOf(op1, op2), effectContext)
        val startTime = System.currentTimeMillis()
        val results = scheduler.analyzeAndExecute(block)
        val endTime = System.currentTimeMillis()
        
        // Should complete in roughly parallel time (less than sequential)
        val executionTime = endTime - startTime
        assertTrue(executionTime < 90, "Execution took ${executionTime}ms, expected < 90ms for parallel execution")
        
        // Should have both results
        assertEquals(2, results.size)
        assertTrue(results.contains("result1"))
        assertTrue(results.contains("result2"))
        
        // Both operations should have executed (possibly in parallel)
        assertEquals(2, executionOrder.size)
    }
    
    @Test
    fun `should execute dependent operations sequentially`() {
        var sharedValue = 0
        
        val op1 = scheduler.createEffectOperation(
            effectType = "Test",
            readVars = emptySet(),
            writeVars = setOf("shared"),
            operation = {
                sharedValue = 42
                "set_value"
            }
        )
        
        val op2 = scheduler.createEffectOperation(
            effectType = "Test", 
            readVars = setOf("shared"),
            writeVars = emptySet(),
            operation = {
                "read_value_$sharedValue"
            }
        )
        
        val block = EffectBlock(listOf(op1, op2), effectContext)
        val results = scheduler.analyzeAndExecute(block)
        
        // Should execute in correct order
        assertEquals(2, results.size)
        assertTrue(results.contains("set_value"))
        assertTrue(results.contains("read_value_42"))
    }
    
    @Test
    fun `should create optimized execution plan`() {
        // Create a mix of independent and dependent operations
        val ops = listOf(
            scheduler.createEffectOperation("IO", emptySet(), setOf("a")) { "op1" },
            scheduler.createEffectOperation("IO", emptySet(), setOf("b")) { "op2" },
            scheduler.createEffectOperation("IO", setOf("a"), setOf("c")) { "op3" },
            scheduler.createEffectOperation("IO", setOf("b"), setOf("d")) { "op4" },
            scheduler.createEffectOperation("IO", setOf("c", "d"), emptySet()) { "op5" }
        )
        
        val block = EffectBlock(ops, effectContext)
        val plan = scheduler.analyzeEffectBlock(block)
        
        // Should have multiple parallel groups
        assertTrue(plan.parallelGroups.size > 1)
        
        // Should have reasonable estimated execution time
        assertTrue(plan.estimatedExecutionTime.toMillis() > 0)
        
        // Should be able to get statistics
        val stats = plan.getStatistics()
        assertEquals(5, stats.totalOperations)
        assertTrue(stats.maxParallelism > 0)
    }
    
    @Test
    fun `should handle empty effect block`() {
        val block = EffectBlock(emptyList(), effectContext)
        val plan = scheduler.analyzeEffectBlock(block)
        
        assertTrue(plan.parallelGroups.isEmpty())
        assertEquals(Duration.ZERO, plan.estimatedExecutionTime)
        
        val results = scheduler.executeWithParallelism(plan)
        assertTrue(results.isEmpty())
    }
    
    @Test
    fun `should handle single operation`() {
        val op = scheduler.createEffectOperation(
            effectType = "Test",
            operation = { "single_result" }
        )
        
        val block = EffectBlock(listOf(op), effectContext)
        val results = scheduler.analyzeAndExecute(block)
        
        assertEquals(1, results.size)
        assertEquals("single_result", results[0])
    }
    
    @Test
    fun `should cache execution plans`() {
        val op1 = scheduler.createEffectOperation("IO", emptySet(), setOf("a")) { "result1" }
        val op2 = scheduler.createEffectOperation("IO", emptySet(), setOf("b")) { "result2" }
        
        val block1 = EffectBlock(listOf(op1, op2), effectContext)
        val block2 = EffectBlock(listOf(op1, op2), effectContext)
        
        // First analysis
        val plan1 = scheduler.analyzeEffectBlock(block1)
        
        // Second analysis should potentially use cache (same structure)
        val plan2 = scheduler.analyzeEffectBlock(block2)
        
        // Plans should be structurally similar
        assertEquals(plan1.parallelGroups.size, plan2.parallelGroups.size)
    }
    
    @Test
    fun `should provide performance statistics`() {
        val op = scheduler.createEffectOperation("Test") { "result" }
        val block = EffectBlock(listOf(op), effectContext)
        
        scheduler.analyzeEffectBlock(block)
        
        val stats = scheduler.getPerformanceStatistics()
        assertTrue(stats.totalExecutionPlans > 0)
        assertTrue(stats.averageParallelism >= 0)
        assertTrue(stats.averageSpeedup >= 0)
    }
}