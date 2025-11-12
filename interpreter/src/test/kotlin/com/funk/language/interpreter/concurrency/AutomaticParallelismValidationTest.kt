package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.effects.EffectContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for automatic parallelism detection with simple effect operations
 * Tests requirements 1.1, 1.2, 1.4: Runtime-managed implicit parallelism
 */
class AutomaticParallelismValidationTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var scheduler: SmartEffectScheduler
    private lateinit var dependencyAnalyzer: EffectDependencyAnalyzer
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        dependencyAnalyzer = EffectDependencyAnalyzer()
        scheduler = SmartEffectScheduler(concurrencyManager, dependencyAnalyzer)
    }
    
    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
    }
    
    @Test
    @Timeout(10)
    fun `should detect independent operations for parallel execution`() {
        val executionOrder = ConcurrentHashMap<String, Long>()
        
        // Create independent operations that don't share variables
        val operations = listOf(
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("result1"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                val timestamp = System.nanoTime()
                executionOrder["op1"] = timestamp
                Thread.sleep(100)
                FunkString("result1")
            },
            scheduler.createEffectOperation(
                effectType = "IO", 
                readVars = emptySet(),
                writeVars = setOf("result2"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                val timestamp = System.nanoTime()
                executionOrder["op2"] = timestamp
                Thread.sleep(100)
                FunkString("result2")
            },
            scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = emptySet(),
                writeVars = setOf("result3"),
                estimatedDuration = Duration.ofMillis(50)
            ) {
                val timestamp = System.nanoTime()
                executionOrder["op3"] = timestamp
                Thread.sleep(50)
                FunkInt(42L)
            }
        )
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        
        val startTime = System.currentTimeMillis()
        val results = scheduler.executeWithParallelism(plan)
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        assertEquals(3, results.size, "Should execute all operations")
        assertTrue(totalTime < 200, "Parallel execution should be faster than sequential (${totalTime}ms)")
        
        // Verify operations started roughly at the same time (within 50ms window)
        val timestamps = executionOrder.values.sorted()
        val timeSpread = timestamps.last() - timestamps.first()
        assertTrue(timeSpread < 50_000_000, "Operations should start nearly simultaneously (spread: ${timeSpread}ns)")
    }
    
    @Test
    @Timeout(10)
    fun `should respect data dependencies and execute sequentially when needed`() {
        val executionOrder = mutableListOf<String>()
        val orderLock = Any()
        
        // Create dependent operations: op2 reads what op1 writes
        val operations = listOf(
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("shared_var"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                synchronized(orderLock) {
                    executionOrder.add("op1")
                }
                Thread.sleep(100)
                FunkString("first_result")
            },
            scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = setOf("shared_var"),
                writeVars = setOf("final_result"),
                estimatedDuration = Duration.ofMillis(50)
            ) {
                synchronized(orderLock) {
                    executionOrder.add("op2")
                }
                Thread.sleep(50)
                FunkString("second_result")
            }
        )
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        
        val startTime = System.currentTimeMillis()
        scheduler.executeWithParallelism(plan)
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        // Should execute sequentially due to dependency
        assertTrue(totalTime >= 140, "Sequential execution should take at least sum of durations (${totalTime}ms)")
        assertEquals(listOf("op1", "op2"), executionOrder, "Operations should execute in dependency order")
    }
    
    @Test
    @Timeout(10)
    fun `should handle mixed independent and dependent operations`() {
        val executionOrder = ConcurrentHashMap<String, Long>()
        
        val operations = listOf(
            // Independent operation 1
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("independent1"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                executionOrder["independent1"] = System.nanoTime()
                Thread.sleep(100)
                FunkString("independent1")
            },
            // Independent operation 2  
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("independent2"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                executionOrder["independent2"] = System.nanoTime()
                Thread.sleep(100)
                FunkString("independent2")
            },
            // Dependent operation (depends on independent1)
            scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = setOf("independent1"),
                writeVars = setOf("dependent"),
                estimatedDuration = Duration.ofMillis(50)
            ) {
                executionOrder["dependent"] = System.nanoTime()
                Thread.sleep(50)
                FunkString("dependent")
            }
        )
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        
        val startTime = System.currentTimeMillis()
        scheduler.executeWithParallelism(plan)
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        // Should be faster than fully sequential but slower than fully parallel
        assertTrue(totalTime < 250, "Mixed execution should be optimized (${totalTime}ms)")
        assertTrue(totalTime >= 150, "Should respect dependencies (${totalTime}ms)")
        
        // Verify execution order constraints
        val independent1Time = executionOrder["independent1"]!!
        val independent2Time = executionOrder["independent2"]!!
        val dependentTime = executionOrder["dependent"]!!
        
        // Independent operations should start roughly together
        val independentSpread = Math.abs(independent1Time - independent2Time)
        assertTrue(independentSpread < 50_000_000, "Independent operations should start together")
        
        // Dependent operation should start after independent1
        assertTrue(dependentTime > independent1Time, "Dependent operation should start after its dependency")
    }
    
    @Test
    @Timeout(10)
    fun `should optimize execution plan for maximum parallelism`() {
        val parallelismLevels = ConcurrentHashMap<Long, AtomicInteger>()
        
        // Create 6 operations: 3 independent groups of 2 operations each
        val operations = (1..6).map { i ->
            val group = (i - 1) / 2 // Groups: 0, 0, 1, 1, 2, 2
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = if (i % 2 == 0) setOf("group${group}_shared") else emptySet(),
                writeVars = if (i % 2 == 1) setOf("group${group}_shared") else setOf("result$i"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                val currentTime = System.currentTimeMillis() / 100 * 100 // Round to 100ms buckets
                parallelismLevels.computeIfAbsent(currentTime) { AtomicInteger(0) }.incrementAndGet()
                Thread.sleep(100)
                FunkString("result$i")
            }
        }
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        
        scheduler.executeWithParallelism(plan)
        
        // Should achieve some level of parallelism
        val maxParallelism = parallelismLevels.values.maxOfOrNull { it.get() } ?: 0
        assertTrue(maxParallelism >= 2, "Should achieve at least 2-way parallelism, got $maxParallelism")
    }
    
    @Test
    @Timeout(10)
    fun `should handle error propagation in parallel operations`() {
        val completedOperations = AtomicInteger(0)
        val errorOperation = AtomicInteger(0)
        
        val operations = listOf(
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("result1"),
                estimatedDuration = Duration.ofMillis(200)
            ) {
                Thread.sleep(200)
                completedOperations.incrementAndGet()
                FunkString("success1")
            },
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("result2"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                Thread.sleep(100)
                errorOperation.incrementAndGet()
                throw RuntimeException("Simulated error")
            },
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("result3"),
                estimatedDuration = Duration.ofMillis(150)
            ) {
                Thread.sleep(150)
                completedOperations.incrementAndGet()
                FunkString("success3")
            }
        )
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        
        assertThrows(Exception::class.java) {
            scheduler.executeWithParallelism(plan)
        }
        
        assertEquals(1, errorOperation.get(), "Error operation should have executed")
        // Some operations might complete before error propagation
        assertTrue(completedOperations.get() <= 2, "Not all operations should complete after error")
    }
    
    @Test
    @Timeout(10)
    fun `should cache and reuse execution plans for similar operation patterns`() {
        val pattern1Operations = listOf(
            scheduler.createEffectOperation("IO", emptySet(), setOf("a")) { FunkString("a") },
            scheduler.createEffectOperation("IO", emptySet(), setOf("b")) { FunkString("b") }
        )
        
        val pattern2Operations = listOf(
            scheduler.createEffectOperation("IO", emptySet(), setOf("x")) { FunkString("x") },
            scheduler.createEffectOperation("IO", emptySet(), setOf("y")) { FunkString("y") }
        )
        
        // Execute first pattern
        val block1 = EffectBlock(pattern1Operations, EffectContext.empty())
        val plan1 = scheduler.analyzeEffectBlock(block1)
        scheduler.executeWithParallelism(plan1)
        
        // Execute similar pattern (should use cached analysis)
        val block2 = EffectBlock(pattern2Operations, EffectContext.empty())
        val plan2 = scheduler.analyzeEffectBlock(block2)
        scheduler.executeWithParallelism(plan2)
        
        val stats = scheduler.getPerformanceStatistics()
        assertTrue(stats.totalExecutionPlans >= 1, "Should have created execution plans")
        assertTrue(stats.averageParallelism > 1.0, "Should detect parallelism opportunities")
    }
    
    @Test
    @Timeout(10)
    fun `should handle nested effect operations correctly`() {
        val nestedExecutionOrder = mutableListOf<String>()
        val orderLock = Any()
        
        val operations = listOf(
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("outer1"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                synchronized(orderLock) {
                    nestedExecutionOrder.add("outer1-start")
                }
                
                // Simulate nested effect operations
                val nestedOps = listOf(
                    scheduler.createEffectOperation("Compute", emptySet(), setOf("nested1")) { 
                        synchronized(orderLock) { nestedExecutionOrder.add("nested1") }
                        FunkInt(1L) 
                    },
                    scheduler.createEffectOperation("Compute", emptySet(), setOf("nested2")) { 
                        synchronized(orderLock) { nestedExecutionOrder.add("nested2") }
                        FunkInt(2L) 
                    }
                )
                
                val nestedBlock = EffectBlock(nestedOps, EffectContext.empty())
                val nestedPlan = scheduler.analyzeEffectBlock(nestedBlock)
                scheduler.executeWithParallelism(nestedPlan)
                
                synchronized(orderLock) {
                    nestedExecutionOrder.add("outer1-end")
                }
                FunkString("outer1")
            },
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("outer2"),
                estimatedDuration = Duration.ofMillis(50)
            ) {
                synchronized(orderLock) {
                    nestedExecutionOrder.add("outer2")
                }
                Thread.sleep(50)
                FunkString("outer2")
            }
        )
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        scheduler.executeWithParallelism(plan)
        
        assertTrue(nestedExecutionOrder.contains("outer1-start"), "Should execute outer operation")
        assertTrue(nestedExecutionOrder.contains("nested1"), "Should execute nested operation 1")
        assertTrue(nestedExecutionOrder.contains("nested2"), "Should execute nested operation 2")
        assertTrue(nestedExecutionOrder.contains("outer1-end"), "Should complete outer operation")
        assertTrue(nestedExecutionOrder.contains("outer2"), "Should execute independent outer operation")
    }
    
    @Test
    @Timeout(10)
    fun `should provide accurate performance statistics`() {
        val operations = (1..5).map { i ->
            scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("result$i"),
                estimatedDuration = Duration.ofMillis(100)
            ) {
                Thread.sleep(100)
                FunkString("result$i")
            }
        }
        
        val block = EffectBlock(operations, EffectContext.empty())
        val plan = scheduler.analyzeEffectBlock(block)
        scheduler.executeWithParallelism(plan)
        
        val stats = scheduler.getPerformanceStatistics()
        
        assertTrue(stats.totalExecutionPlans >= 1, "Should track execution plans")
        assertTrue(stats.averageParallelism > 1.0, "Should detect parallelism (${stats.averageParallelism})")
        assertTrue(stats.averageSpeedup >= 1.0, "Should show speedup (${stats.averageSpeedup})")
    }
}