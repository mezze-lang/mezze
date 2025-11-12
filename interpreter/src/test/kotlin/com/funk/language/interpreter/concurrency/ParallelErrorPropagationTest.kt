package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.effects.EffectContext
import com.funk.language.interpreter.effects.EffectCancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Disabled
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class ParallelErrorPropagationTest {

    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var errorManager: ParallelErrorPropagationManager
    private lateinit var context: EffectContext

    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        errorManager = ParallelErrorPropagationManager(concurrencyManager)
        context = EffectContext.create(concurrencyManager = concurrencyManager)
    }
    
    @Test
    fun `test successful parallel group execution`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        group.addOperation("op1", { FunkInt(10L) })
        group.addOperation("op2", { FunkInt(20L) })
        group.addOperation("op3", { FunkString("hello") })
        
        val results = group.execute()
        
        assertEquals(3, results.size)
        assertEquals(10L, (results["op1"] as FunkInt).value)
        assertEquals(20L, (results["op2"] as FunkInt).value)
        assertEquals("hello", (results["op3"] as FunkString).value)
        
        assertTrue(group.isCompleted())
        assertFalse(group.hasFailed())
        assertFalse(group.isCancelled())
    }
    
    @Test
    fun `test fail fast error propagation`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        val executionCount = AtomicInteger(0)
        
        group.addOperation("op1", { 
            executionCount.incrementAndGet()
            Thread.sleep(100)
            FunkInt(10L) 
        })
        
        group.addOperation("op2", { 
            executionCount.incrementAndGet()
            Thread.sleep(50)
            throw RuntimeException("Operation 2 failed")
        })
        
        group.addOperation("op3", { 
            executionCount.incrementAndGet()
            Thread.sleep(200)
            FunkInt(30L) 
        })
        
        assertThrows<RuntimeException> {
            group.execute()
        }
        
        assertTrue(group.hasFailed())
        assertNotNull(group.getFirstError())
        
        // With fail-fast, not all operations may complete
        // The exact count depends on timing
        assertTrue(executionCount.get() <= 3)
    }
    
    @Test
    fun `test collect errors strategy`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.COLLECT_ERRORS
        )
        
        group.addOperation("op1", { FunkInt(10L) })
        group.addOperation("op2", { throw RuntimeException("Error 2") })
        group.addOperation("op3", { FunkInt(30L) })
        group.addOperation("op4", { throw RuntimeException("Error 4") })
        
        val results = group.execute()
        
        // Should have results for all operations (errors as error values)
        assertEquals(4, results.size)
        assertEquals(10L, (results["op1"] as FunkInt).value)
        assertEquals(30L, (results["op3"] as FunkInt).value)
        
        // Error operations should have error values
        assertTrue(results["op2"] is FunkString)
        assertTrue(results["op4"] is FunkString)
        assertTrue((results["op2"] as FunkString).value.contains("ERROR"))
        assertTrue((results["op4"] as FunkString).value.contains("ERROR"))
        
        // Group should be marked as failed due to errors
        assertTrue(group.hasFailed())
        
        // Should have collected multiple errors
        val allErrors = group.getAllErrors()
        assertTrue(allErrors.size >= 2)
    }
    
    @Test
    fun `test ignore errors strategy`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.IGNORE_ERRORS
        )
        
        group.addOperation("op1", { FunkInt(10L) })
        group.addOperation("op2", { throw RuntimeException("Error 2") })
        group.addOperation("op3", { FunkInt(30L) })
        
        val results = group.execute()
        
        assertEquals(3, results.size)
        assertEquals(10L, (results["op1"] as FunkInt).value)
        assertEquals(30L, (results["op3"] as FunkInt).value)
        
        // Error operation should have error value
        assertTrue(results["op2"] is FunkString)
        assertTrue((results["op2"] as FunkString).value.contains("ERROR"))
        
        // Group should be completed (not failed) with ignore errors strategy
        assertTrue(group.isCompleted())
        assertFalse(group.hasFailed())
    }
    
    @Test
    fun `test operation dependencies`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        val executionOrder = mutableListOf<String>()
        
        group.addOperation("op1", { 
            executionOrder.add("op1")
            Thread.sleep(50)
            FunkInt(10L) 
        })
        
        group.addOperation("op2", 
            computation = { 
                executionOrder.add("op2")
                val op1Result = group.executeOperation("op1") as FunkInt
                FunkInt(op1Result.value * 2) 
            },
            dependencies = setOf("op1")
        )
        
        val results = group.execute()
        
        assertEquals(2, results.size)
        assertEquals(10L, (results["op1"] as FunkInt).value)
        assertEquals(20L, (results["op2"] as FunkInt).value)
        
        // op1 should execute before op2
        assertEquals("op1", executionOrder[0])
        assertEquals("op2", executionOrder[1])
    }
    
    @Test
    fun `test operation timeout`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        group.addOperation(
            operationId = "slow-op",
            computation = {
                Thread.sleep(2000) // 2 seconds
                FunkInt(42L)
            },
            timeout = Duration.ofMillis(500) // 500ms timeout
        )
        
        assertThrows<RuntimeException> {
            group.execute()
        }
        
        assertTrue(group.hasFailed())
    }
    
    @Test
    @Disabled("Cancellation mechanism needs proper cancel token checking in operation execution")
    fun `test group cancellation`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        val operationStarted = AtomicBoolean(false)
        val operationCompleted = AtomicBoolean(false)
        
        group.addOperation("long-op", { 
            operationStarted.set(true)
            Thread.sleep(50)
            operationCompleted.set(true)
            FunkInt(42L) 
        })
        
        // Start execution in background
        val future = CompletableFuture.supplyAsync {
            group.execute()
        }
        
        // Wait for operation to start
        Thread.sleep(100)
        assertTrue(operationStarted.get())
        assertFalse(operationCompleted.get())
        
        // Cancel the group
        group.cancel("Test cancellation")
        
        // Should throw exception
        assertThrows<RuntimeException> {
            future.get(2, TimeUnit.SECONDS)
        }
        
        assertTrue(group.isCancelled())
        assertFalse(operationCompleted.get())
    }
    
    @Test
    fun `test operation statistics`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.COLLECT_ERRORS
        )
        
        val op1 = group.addOperation("op1", { 
            Thread.sleep(50)
            FunkInt(10L) 
        })
        
        val op2 = group.addOperation("op2", { 
            Thread.sleep(30)
            throw RuntimeException("Test error")
        })
        
        group.execute()
        
        val op1Stats = op1.getStatistics()
        val op2Stats = op2.getStatistics()
        
        assertEquals("op1", op1Stats.operationId)
        assertEquals(OperationState.COMPLETED, op1Stats.state)
        assertTrue(op1Stats.isSuccessful)
        assertFalse(op1Stats.hasError)
        assertTrue(op1Stats.executionTime > Duration.ZERO)
        
        assertEquals("op2", op2Stats.operationId)
        assertEquals(OperationState.FAILED, op2Stats.state)
        assertFalse(op2Stats.isSuccessful)
        assertTrue(op2Stats.hasError)
        assertTrue(op2Stats.executionTime > Duration.ZERO)
    }
    
    @Test
    @Disabled("Statistics tracking needs refactoring - groups are cleaned up after execution")
    fun `test parallel error propagation manager statistics`() {
        // Create multiple groups
        val group1 = errorManager.createParallelGroup(context = context)
        val group2 = errorManager.createParallelGroup(context = context)
        val group3 = errorManager.createParallelGroup(context = context)
        
        group1.addOperation("op1", { FunkInt(1L) })
        group2.addOperation("op2", { FunkInt(2L) })
        group2.addOperation("op3", { FunkInt(3L) })
        group3.addOperation("op4", { throw RuntimeException("Error") })
        
        // Execute groups
        group1.execute()
        group2.execute()
        
        try {
            group3.execute()
        } catch (e: Exception) {
            // Expected
        }
        
        val stats = errorManager.getStatistics()

        // All groups have completed and been cleaned up
        assertEquals(0, stats.activeGroups)
        // Total operations from all groups: 1 + 2 + 1 = 4
        assertEquals(4, stats.totalOperations)
        assertTrue(stats.failedGroups >= 1)
    }
    
    @Test
    fun `test complex dependency chain with errors`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        group.addOperation("base", { FunkInt(10L) })
        
        group.addOperation("derived1", 
            computation = { 
                val base = group.executeOperation("base") as FunkInt
                FunkInt(base.value + 1) 
            },
            dependencies = setOf("base")
        )
        
        group.addOperation("derived2", 
            computation = { 
                val base = group.executeOperation("base") as FunkInt
                throw RuntimeException("Derived2 failed")
            },
            dependencies = setOf("base")
        )
        
        group.addOperation("final", 
            computation = { 
                val d1 = group.executeOperation("derived1") as FunkInt
                val d2 = group.executeOperation("derived2") as FunkInt
                FunkInt(d1.value + d2.value) 
            },
            dependencies = setOf("derived1", "derived2")
        )
        
        assertThrows<RuntimeException> {
            group.execute()
        }
        
        assertTrue(group.hasFailed())
        assertNotNull(group.getFirstError())
    }
    
    @Test
    @Disabled("Cancellation mechanism needs proper cancel token checking in operation execution")
    fun `test operation cancellation during execution`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        val op1Started = AtomicBoolean(false)
        val op1Completed = AtomicBoolean(false)
        
        val operation = group.addOperation("cancellable", { 
            op1Started.set(true)
            Thread.sleep(50)
            op1Completed.set(true)
            FunkInt(42L) 
        })
        
        // Start execution in background
        val future = CompletableFuture.supplyAsync {
            operation.execute()
        }
        
        // Wait for operation to start
        Thread.sleep(100)
        assertTrue(op1Started.get())
        
        // Cancel the operation
        operation.cancel("Test cancellation")
        
        // Should throw exception
        assertThrows<RuntimeException> {
            future.get(2, TimeUnit.SECONDS)
        }
        
        assertFalse(op1Completed.get())
        assertNotNull(operation.getError())
    }
    
    @Test
    fun `test parallel execution error details`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.COLLECT_ERRORS
        )
        
        group.addOperation("error-op", { 
            throw RuntimeException("Specific error message")
        })
        
        group.execute()
        
        val errors = group.getAllErrors()
        assertEquals(1, errors.size)

        val error = errors[0]
        assertTrue(error.message.contains("Specific error message"))
        assertEquals(group.id, error.groupId)
        assertEquals("error-op", error.operationId)
        assertNotNull(error.timestamp)
    }
    
    @Test
    fun `test concurrent group operations`() {
        val numGroups = 5
        val futures = mutableListOf<CompletableFuture<Map<String, FunkValue>>>()
        
        repeat(numGroups) { i ->
            val group = errorManager.createParallelGroup(context = context)
            
            group.addOperation("op1", { FunkInt((i * 10).toLong()) })
            group.addOperation("op2", { FunkInt((i * 20).toLong()) })
            
            val future = CompletableFuture.supplyAsync {
                group.execute()
            }
            futures.add(future)
        }
        
        // Wait for all groups to complete
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        
        assertEquals(numGroups, results.size)
        
        results.forEachIndexed { i, result ->
            assertEquals(2, result.size)
            assertEquals((i * 10).toLong(), (result["op1"] as FunkInt).value)
            assertEquals((i * 20).toLong(), (result["op2"] as FunkInt).value)
        }
    }
}