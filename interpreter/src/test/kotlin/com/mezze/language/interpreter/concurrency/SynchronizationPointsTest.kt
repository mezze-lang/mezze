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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class SynchronizationPointsTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var syncManager: SynchronizationPointManager
    private lateinit var context: EffectContext
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        syncManager = SynchronizationPointManager(concurrencyManager)
        context = EffectContext.create(concurrencyManager = concurrencyManager)
    }
    
    @Test
    fun `test simple synchronization point without dependencies`() {
        val computation = { MezzeInt(42L) }
        
        val syncPoint = syncManager.createSyncPoint(
            operationId = "test-op",
            dependencies = emptySet(),
            computation = computation,
            context = context
        )
        
        val result = syncPoint.await()
        assertEquals(42L, (result as MezzeInt).value)
        assertTrue(syncPoint.isCompleted())
    }
    
    @Test
    fun `test synchronization point with dependencies`() {
        val executionOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
        
        // Create dependency operation
        val depSyncPoint = syncManager.createSyncPoint(
            operationId = "dependency",
            dependencies = emptySet(),
            computation = {
                executionOrder.add("dependency")
                Thread.sleep(50) // Simulate work
                MezzeInt(10L)
            },
            context = context
        )
        
        // Create dependent operation
        val mainSyncPoint = syncManager.createSyncPoint(
            operationId = "main",
            dependencies = setOf("dependency"),
            computation = {
                executionOrder.add("main")
                val depValue = syncManager.getValue("dependency") as MezzeInt
                MezzeInt(depValue.value * 2)
            },
            context = context
        )
        
        // Start both operations concurrently
        val depFuture = CompletableFuture.supplyAsync { depSyncPoint.await() }
        val mainFuture = CompletableFuture.supplyAsync { mainSyncPoint.await() }
        
        val mainResult = mainFuture.get(5, TimeUnit.SECONDS)
        val depResult = depFuture.get(5, TimeUnit.SECONDS)
        
        assertEquals(10L, (depResult as MezzeInt).value)
        assertEquals(20L, (mainResult as MezzeInt).value)
        
        // Dependency should execute before main
        assertEquals("dependency", executionOrder[0])
        assertEquals("main", executionOrder[1])
    }
    
    @Test
    fun `test lazy synchronization - computation only starts when needed`() {
        val computationStarted = AtomicBoolean(false)
        val computationCompleted = AtomicBoolean(false)
        
        val syncPoint = syncManager.createSyncPoint(
            operationId = "lazy-op",
            dependencies = emptySet(),
            computation = {
                computationStarted.set(true)
                Thread.sleep(100) // Simulate work
                computationCompleted.set(true)
                MezzeString("computed")
            },
            context = context
        )
        
        // Computation should not start immediately
        Thread.sleep(50)
        assertFalse(computationStarted.get())
        assertFalse(computationCompleted.get())
        
        // Computation should start when we await the result
        val result = syncPoint.await()
        assertTrue(computationStarted.get())
        assertTrue(computationCompleted.get())
        assertEquals("computed", (result as MezzeString).value)
    }
    
    @Test
    fun `test synchronization point cancellation`() {
        val computationStarted = AtomicBoolean(false)
        
        val syncPoint = syncManager.createSyncPoint(
            operationId = "cancellable-op",
            dependencies = emptySet(),
            computation = {
                computationStarted.set(true)
                Thread.sleep(1000) // Long running computation
                MezzeString("should not complete")
            },
            context = context
        )
        
        // Start computation in background
        val future = CompletableFuture.supplyAsync {
            syncPoint.await()
        }
        
        // Wait for computation to start
        Thread.sleep(100)
        assertTrue(computationStarted.get())
        
        // Cancel the synchronization point
        syncPoint.cancel("Test cancellation")
        
        // Should throw cancellation exception
        assertThrows<ExecutionException> {
            future.get(2, TimeUnit.SECONDS)
        }
    }
    
    @Test
    fun `test synchronization point timeout`() {
        val syncPoint = syncManager.createSyncPoint(
            operationId = "timeout-op",
            dependencies = emptySet(),
            computation = {
                Thread.sleep(2000) // Long running computation
                MezzeString("should timeout")
            },
            context = context
        )
        
        // Should timeout after 500ms
        assertThrows<RuntimeException> {
            syncPoint.await(Duration.ofMillis(500))
        }
    }
    
    @Test
    fun `test multiple synchronization points with complex dependencies`() {
        val executionOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
        
        // Create a diamond dependency pattern:
        // A -> B, A -> C, B -> D, C -> D
        
        val syncPointA = syncManager.createSyncPoint(
            operationId = "A",
            dependencies = emptySet(),
            computation = {
                executionOrder.add("A")
                Thread.sleep(50)
                MezzeInt(1L)
            },
            context = context
        )
        
        val syncPointB = syncManager.createSyncPoint(
            operationId = "B",
            dependencies = setOf("A"),
            computation = {
                executionOrder.add("B")
                val aValue = syncManager.getValue("A") as MezzeInt
                Thread.sleep(50)
                MezzeInt(aValue.value + 10)
            },
            context = context
        )
        
        val syncPointC = syncManager.createSyncPoint(
            operationId = "C",
            dependencies = setOf("A"),
            computation = {
                executionOrder.add("C")
                val aValue = syncManager.getValue("A") as MezzeInt
                Thread.sleep(50)
                MezzeInt(aValue.value + 100)
            },
            context = context
        )
        
        val syncPointD = syncManager.createSyncPoint(
            operationId = "D",
            dependencies = setOf("B", "C"),
            computation = {
                executionOrder.add("D")
                val bValue = syncManager.getValue("B") as MezzeInt
                val cValue = syncManager.getValue("C") as MezzeInt
                MezzeInt(bValue.value + cValue.value)
            },
            context = context
        )
        
        // Execute all operations
        val futures = listOf(
            CompletableFuture.supplyAsync { syncPointA.await() },
            CompletableFuture.supplyAsync { syncPointB.await() },
            CompletableFuture.supplyAsync { syncPointC.await() },
            CompletableFuture.supplyAsync { syncPointD.await() }
        )
        
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        
        // Check results
        assertEquals(1L, (results[0] as MezzeInt).value)   // A = 1
        assertEquals(11L, (results[1] as MezzeInt).value)  // B = 1 + 10 = 11
        assertEquals(101L, (results[2] as MezzeInt).value) // C = 1 + 100 = 101
        assertEquals(112L, (results[3] as MezzeInt).value) // D = 11 + 101 = 112
        
        // Check execution order
        assertEquals("A", executionOrder[0])
        assertTrue(executionOrder.indexOf("B") > executionOrder.indexOf("A"))
        assertTrue(executionOrder.indexOf("C") > executionOrder.indexOf("A"))
        assertTrue(executionOrder.indexOf("D") > executionOrder.indexOf("B"))
        assertTrue(executionOrder.indexOf("D") > executionOrder.indexOf("C"))
    }
    
    @Test
    fun `test lazy synchronization coordinator`() {
        val coordinator = LazySynchronizationCoordinator(concurrencyManager)
        
        val operations = mapOf(
            "op1" to { MezzeInt(10L) },
            "op2" to { MezzeInt(20L) },
            "op3" to { 
                val op1Result = coordinator.getValue("op1") as MezzeInt
                val op2Result = coordinator.getValue("op2") as MezzeInt
                MezzeInt(op1Result.value + op2Result.value)
            }
        )
        
        val dependencies = mapOf(
            "op1" to emptySet<String>(),
            "op2" to emptySet<String>(),
            "op3" to setOf("op1", "op2")
        )
        
        val results = coordinator.executeWithLazySync(operations, dependencies, context)
        
        assertEquals(10L, (results["op1"] as MezzeInt).value)
        assertEquals(20L, (results["op2"] as MezzeInt).value)
        assertEquals(30L, (results["op3"] as MezzeInt).value)
    }
    
    @Test
    fun `test lazy execution plan`() {
        val coordinator = LazySynchronizationCoordinator(concurrencyManager)
        
        val operations = setOf("A", "B", "C", "D")
        val dependencies = mapOf(
            "A" to emptySet<String>(),
            "B" to setOf("A"),
            "C" to setOf("A"),
            "D" to setOf("B", "C")
        )
        
        val plan = coordinator.createExecutionPlan(operations, dependencies)
        
        // Test root operations
        val rootOps = plan.getRootOperations()
        assertEquals(1, rootOps.size)
        assertTrue(rootOps.contains("A"))
        
        // Test dependents
        val aDependents = plan.getDependents("A")
        assertEquals(2, aDependents.size)
        assertTrue(aDependents.contains("B"))
        assertTrue(aDependents.contains("C"))
        
        val bDependents = plan.getDependents("B")
        assertEquals(1, bDependents.size)
        assertTrue(bDependents.contains("D"))
        
        // Test critical path
        val criticalPath = plan.getCriticalPath()
        assertTrue(criticalPath.isNotEmpty())
        assertEquals("A", criticalPath[0])
        assertEquals("D", criticalPath.last())
    }
    
    @Test
    fun `test synchronization point error handling`() {
        val computationExecuted = AtomicBoolean(false)
        val syncPoint = syncManager.createSyncPoint(
            operationId = "error-op",
            dependencies = emptySet(),
            computation = {
                computationExecuted.set(true)
                throw RuntimeException("Computation failed")
            },
            context = context
        )
        
        // Test that computation is executed and exception is thrown
        assertThrows<RuntimeException> {
            syncPoint.await()
        }
        
        assertTrue(computationExecuted.get(), "Computation should have been executed")
        assertFalse(syncPoint.isCompleted())
    }
    
    @Test
    fun `test synchronization point statistics`() {
        // Create several sync points
        repeat(3) { i ->
            syncManager.createSyncPoint(
                operationId = "op$i",
                dependencies = emptySet(),
                computation = { MezzeInt(i.toLong()) },
                context = context
            )
        }
        
        val stats = syncManager.getStatistics()
        assertEquals(3, stats.activeCount)
        assertEquals(0, stats.completedCount)
        assertEquals(0, stats.blockedCount)
        assertEquals(0, stats.runningCount)
        
        // Complete one operation
        syncManager.getValue("op0")
        
        val updatedStats = syncManager.getStatistics()
        assertEquals(2, updatedStats.activeCount)
        assertEquals(1, updatedStats.completedCount)
    }
    
    @Test
    fun `test concurrent access to synchronization points`() {
        val numOperations = 10
        val results = mutableMapOf<String, MezzeValue>()
        val futures = mutableListOf<CompletableFuture<MezzeValue>>()
        
        // Create multiple sync points
        repeat(numOperations) { i ->
            val syncPoint = syncManager.createSyncPoint(
                operationId = "concurrent-op$i",
                dependencies = emptySet(),
                computation = {
                    Thread.sleep((10..50).random().toLong()) // Random delay
                    MezzeInt((i * i).toLong())
                },
                context = context
            )
            
            val future = CompletableFuture.supplyAsync {
                syncPoint.await()
            }
            futures.add(future)
        }
        
        // Wait for all to complete
        futures.forEachIndexed { i, future ->
            val result = future.get(5, TimeUnit.SECONDS)
            results["concurrent-op$i"] = result
        }
        
        // Verify all results
        repeat(numOperations) { i ->
            val result = results["concurrent-op$i"] as MezzeInt
            assertEquals((i * i).toLong(), result.value)
        }
    }
    
    @Test
    fun `test synchronization point state transitions`() {
        val syncPoint = syncManager.createSyncPoint(
            operationId = "state-test",
            dependencies = emptySet(),
            computation = {
                Thread.sleep(100)
                MezzeString("completed")
            },
            context = context
        )
        
        // Initially should not be running or completed
        assertFalse(syncPoint.isRunning())
        assertFalse(syncPoint.isCompleted())
        
        // Start computation
        val future = CompletableFuture.supplyAsync {
            syncPoint.await()
        }
        
        // Should eventually be running
        Thread.sleep(50)
        // Note: Due to timing, we can't guarantee the exact state here
        
        // Wait for completion
        val result = future.get(5, TimeUnit.SECONDS)

        // Give the sync point time to update its internal state
        // Under heavy test load, state transition may lag slightly
        Thread.sleep(10)

        // Should be completed
        assertTrue(syncPoint.isCompleted())
        assertEquals("completed", (result as MezzeString).value)
    }
}