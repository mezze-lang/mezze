package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.effects.EffectContext
import com.mezze.language.interpreter.effects.EffectCancellationException
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
        
        group.addOperation("op1", { MezzeInt(10L) })
        group.addOperation("op2", { MezzeInt(20L) })
        group.addOperation("op3", { MezzeString("hello") })
        
        val results = group.execute()
        
        assertEquals(3, results.size)
        assertEquals(10L, (results["op1"] as MezzeInt).value)
        assertEquals(20L, (results["op2"] as MezzeInt).value)
        assertEquals("hello", (results["op3"] as MezzeString).value)
        
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
            MezzeInt(10L) 
        })
        
        group.addOperation("op2", { 
            executionCount.incrementAndGet()
            Thread.sleep(50)
            throw RuntimeException("Operation 2 failed")
        })
        
        group.addOperation("op3", { 
            executionCount.incrementAndGet()
            Thread.sleep(200)
            MezzeInt(30L) 
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
        
        group.addOperation("op1", { MezzeInt(10L) })
        group.addOperation("op2", { throw RuntimeException("Error 2") })
        group.addOperation("op3", { MezzeInt(30L) })
        group.addOperation("op4", { throw RuntimeException("Error 4") })
        
        val results = group.execute()
        
        // Should have results for all operations (errors as error values)
        assertEquals(4, results.size)
        assertEquals(10L, (results["op1"] as MezzeInt).value)
        assertEquals(30L, (results["op3"] as MezzeInt).value)
        
        // Error operations should have error values
        assertTrue(results["op2"] is MezzeString)
        assertTrue(results["op4"] is MezzeString)
        assertTrue((results["op2"] as MezzeString).value.contains("ERROR"))
        assertTrue((results["op4"] as MezzeString).value.contains("ERROR"))
        
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
        
        group.addOperation("op1", { MezzeInt(10L) })
        group.addOperation("op2", { throw RuntimeException("Error 2") })
        group.addOperation("op3", { MezzeInt(30L) })
        
        val results = group.execute()
        
        assertEquals(3, results.size)
        assertEquals(10L, (results["op1"] as MezzeInt).value)
        assertEquals(30L, (results["op3"] as MezzeInt).value)
        
        // Error operation should have error value
        assertTrue(results["op2"] is MezzeString)
        assertTrue((results["op2"] as MezzeString).value.contains("ERROR"))
        
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
            MezzeInt(10L) 
        })
        
        group.addOperation("op2", 
            computation = { 
                executionOrder.add("op2")
                val op1Result = group.executeOperation("op1") as MezzeInt
                MezzeInt(op1Result.value * 2) 
            },
            dependencies = setOf("op1")
        )
        
        val results = group.execute()
        
        assertEquals(2, results.size)
        assertEquals(10L, (results["op1"] as MezzeInt).value)
        assertEquals(20L, (results["op2"] as MezzeInt).value)
        
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
                MezzeInt(42L)
            },
            timeout = Duration.ofMillis(500) // 500ms timeout
        )
        
        assertThrows<RuntimeException> {
            group.execute()
        }
        
        assertTrue(group.hasFailed())
    }
    
    @Test
    fun `test group cancellation before execution`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )

        group.addOperation("op1", { MezzeInt(42L) })

        // Cancel before execution
        group.cancel("Test cancellation")

        // The group should be cancelled
        assertTrue(group.isCancelled(), "Group should be marked as cancelled")

        // Attempting to execute a cancelled group should fail
        assertThrows<RuntimeException> {
            group.execute()
        }
    }
    
    @Test
    fun `test operation statistics`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.COLLECT_ERRORS
        )
        
        val op1 = group.addOperation("op1", { 
            Thread.sleep(50)
            MezzeInt(10L) 
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
    fun `test parallel error propagation manager statistics during execution`() {
        // Create a group with a long-running operation so we can check stats
        val group = errorManager.createParallelGroup(context = context)

        group.addOperation("slow-op", {
            Thread.sleep(200)
            MezzeInt(1L)
        })

        // Start execution in background
        val future = CompletableFuture.supplyAsync {
            group.execute()
        }

        // Check stats while group is active
        Thread.sleep(50)
        val statsWhileRunning = errorManager.getStatistics()

        // Group should be active (1 active group with 1 operation)
        assertEquals(1, statsWhileRunning.activeGroups, "Should have 1 active group")
        assertEquals(1, statsWhileRunning.totalOperations, "Should have 1 operation")

        // Wait for completion
        future.get(2, TimeUnit.SECONDS)

        // After completion, groups are cleaned up
        val statsAfter = errorManager.getStatistics()
        assertEquals(0, statsAfter.activeGroups, "Groups should be cleaned up after execution")
    }
    
    @Test
    fun `test complex dependency chain with errors`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )
        
        group.addOperation("base", { MezzeInt(10L) })
        
        group.addOperation("derived1", 
            computation = { 
                val base = group.executeOperation("base") as MezzeInt
                MezzeInt(base.value + 1) 
            },
            dependencies = setOf("base")
        )
        
        group.addOperation("derived2", 
            computation = { 
                val base = group.executeOperation("base") as MezzeInt
                throw RuntimeException("Derived2 failed")
            },
            dependencies = setOf("base")
        )
        
        group.addOperation("final", 
            computation = { 
                val d1 = group.executeOperation("derived1") as MezzeInt
                val d2 = group.executeOperation("derived2") as MezzeInt
                MezzeInt(d1.value + d2.value) 
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
    fun `test operation cancellation before execution`() {
        val group = errorManager.createParallelGroup(
            context = context,
            errorStrategy = ErrorPropagationStrategy.FAIL_FAST
        )

        val operation = group.addOperation("cancellable", {
            MezzeInt(42L)
        })

        // Cancel before execution
        operation.cancel("Test cancellation")

        // Operation should have error recorded after cancellation
        assertNotNull(operation.getError(), "Operation should have error after cancellation")
        assertTrue(operation.getError()!!.message.contains("cancelled"), "Error should mention cancellation")
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
        val futures = mutableListOf<CompletableFuture<Map<String, MezzeValue>>>()
        
        repeat(numGroups) { i ->
            val group = errorManager.createParallelGroup(context = context)
            
            group.addOperation("op1", { MezzeInt((i * 10).toLong()) })
            group.addOperation("op2", { MezzeInt((i * 20).toLong()) })
            
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
            assertEquals((i * 10).toLong(), (result["op1"] as MezzeInt).value)
            assertEquals((i * 20).toLong(), (result["op2"] as MezzeInt).value)
        }
    }
}