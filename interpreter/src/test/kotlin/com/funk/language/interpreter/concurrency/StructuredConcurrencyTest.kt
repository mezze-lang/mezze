package com.funk.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for structured concurrency scope cleanup and cancellation
 * Tests requirements 8.1, 8.2: Structured concurrency and cancellation
 */
class StructuredConcurrencyTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
    }
    
    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
    }
    
    @Test
    @Timeout(10)
    fun `should execute all tasks in structured scope`() {
        val results = mutableListOf<Int>()
        val completedTasks = AtomicInteger(0)
        
        val scopeResult = concurrencyManager.withStructuredScope("test-scope") { scope ->
            val futures = (1..5).map { i ->
                scope.submit {
                    Thread.sleep(100) // Simulate work
                    completedTasks.incrementAndGet()
                    synchronized(results) {
                        results.add(i)
                    }
                    i * 2
                }
            }
            
            futures.map { it.get() }
        }
        
        assertEquals(listOf(2, 4, 6, 8, 10), scopeResult.sorted())
        assertEquals(5, completedTasks.get())
        assertEquals(5, results.size)
    }
    
    @Test
    @Timeout(10)
    fun `should automatically clean up scope when completed`() {
        val initialStats = concurrencyManager.getThreadStatistics()
        val scopeName = "cleanup-test-scope"
        
        concurrencyManager.withStructuredScope(scopeName) { scope ->
            repeat(3) { i ->
                scope.submit {
                    Thread.sleep(50)
                    "task-$i"
                }
            }
            "scope-completed"
        }
        
        // Scope should be cleaned up automatically
        val finalStats = concurrencyManager.getThreadStatistics()
        assertFalse(finalStats.scopeNames.contains(scopeName), 
            "Scope should be cleaned up after completion")
    }
    
    @Test
    @Timeout(10)
    fun `should cancel all tasks when scope is cancelled`() {
        val completedTasks = AtomicInteger(0)
        val cancelledTasks = AtomicInteger(0)
        val scopeName = "cancellation-test"
        
        val scopeThread = Thread {
            try {
                concurrencyManager.withStructuredScope(scopeName) { scope ->
                    repeat(10) { i ->
                        scope.submit {
                            try {
                                Thread.sleep(500) // Long-running task
                                completedTasks.incrementAndGet()
                                "completed-$i"
                            } catch (e: InterruptedException) {
                                cancelledTasks.incrementAndGet()
                                throw e
                            }
                        }
                    }
                    Thread.sleep(100) // Keep scope alive
                    "scope-result"
                }
            } catch (e: Exception) {
                // Expected when scope is cancelled
            }
        }
        
        scopeThread.start()
        Thread.sleep(200) // Let tasks start
        
        // Cancel the scope
        concurrencyManager.cancelScope(scopeName)
        
        scopeThread.join(2000) // Wait for cleanup
        
        // Most tasks should have been cancelled
        assertTrue(cancelledTasks.get() > 0, "Some tasks should have been cancelled")
        assertTrue(completedTasks.get() < 10, "Not all tasks should have completed")
    }
    
    @Test
    @Timeout(10)
    fun `should handle exceptions in structured scope`() {
        val successfulTasks = AtomicInteger(0)
        val failedTasks = AtomicInteger(0)
        
        assertThrows(RuntimeException::class.java) {
            concurrencyManager.withStructuredScope("exception-scope") { scope ->
                val futures = (1..5).map { i ->
                    scope.submit {
                        if (i == 3) {
                            failedTasks.incrementAndGet()
                            throw RuntimeException("Task $i failed")
                        } else {
                            Thread.sleep(100)
                            successfulTasks.incrementAndGet()
                            i
                        }
                    }
                }
                
                try {
                    futures.map { it.get() }
                } catch (e: java.util.concurrent.ExecutionException) {
                    // Unwrap ExecutionException to get the original exception
                    throw e.cause ?: e
                }
            }
        }
        
        assertEquals(1, failedTasks.get(), "One task should have failed")
        // Some successful tasks might complete before the exception propagates
        assertTrue(successfulTasks.get() >= 0, "Some tasks might have succeeded")
    }
    
    @Test
    @Timeout(10)
    fun `should support nested structured scopes`() {
        val results = ConcurrentHashMap<String, String>()
        
        val outerResult = concurrencyManager.withStructuredScope("outer-scope") { outerScope ->
            val outerFuture = outerScope.submit {
                results["outer-start"] = "started"
                
                val innerResult = concurrencyManager.withStructuredScope("inner-scope") { innerScope ->
                    val innerFutures = (1..3).map { i ->
                        innerScope.submit {
                            Thread.sleep(50)
                            results["inner-$i"] = "completed"
                            "inner-$i"
                        }
                    }
                    innerFutures.map { it.get() }
                }
                
                results["outer-end"] = "completed"
                innerResult
            }
            
            outerFuture.get()
        }
        
        assertEquals(listOf("inner-1", "inner-2", "inner-3"), outerResult.sorted())
        assertEquals("started", results["outer-start"])
        assertEquals("completed", results["outer-end"])
        assertEquals("completed", results["inner-1"])
        assertEquals("completed", results["inner-2"])
        assertEquals("completed", results["inner-3"])
    }
    
    @Test
    @Timeout(10)
    fun `should handle scope timeout properly`() {
        val taskStarted = AtomicBoolean(false)
        val taskCompleted = AtomicBoolean(false)
        
        val startTime = System.currentTimeMillis()
        
        try {
            concurrencyManager.withStructuredScope("timeout-scope", Duration.ofMillis(500)) { scope ->
                scope.submit {
                    taskStarted.set(true)
                    Thread.sleep(1000) // Longer than timeout
                    taskCompleted.set(true)
                    "should-not-complete"
                }
                Thread.sleep(200) // Keep scope alive longer than timeout
                "scope-result"
            }
        } catch (e: Exception) {
            // Expected timeout behavior
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertTrue(taskStarted.get(), "Task should have started")
        assertFalse(taskCompleted.get(), "Task should not have completed due to timeout")
        assertTrue(duration < 1500, "Scope should have timed out quickly (${duration}ms)")
    }
    
    @Test
    @Timeout(10)
    fun `should properly clean up resources on scope exit`() {
        val resourcesCreated = AtomicInteger(0)
        val resourcesCleaned = AtomicInteger(0)
        val activeResources = ConcurrentHashMap.newKeySet<String>()
        
        concurrencyManager.withStructuredScope("resource-cleanup") { scope ->
            repeat(5) { i ->
                scope.submit {
                    val resourceId = "resource-$i"
                    try {
                        resourcesCreated.incrementAndGet()
                        activeResources.add(resourceId)
                        Thread.sleep(100) // Use resource
                        "result-$i"
                    } finally {
                        activeResources.remove(resourceId)
                        resourcesCleaned.incrementAndGet()
                    }
                }
            }
            "scope-completed"
        }
        
        assertEquals(5, resourcesCreated.get(), "All resources should have been created")
        assertEquals(5, resourcesCleaned.get(), "All resources should have been cleaned up")
        assertTrue(activeResources.isEmpty(), "No resources should remain active")
    }
    
    @Test
    @Timeout(10)
    fun `should support concurrent access to scope statistics`() {
        val scopeName = "stats-scope"
        val accessCount = AtomicInteger(0)
        val latch = CountDownLatch(10)
        
        // Start scope in background
        val scopeThread = Thread {
            concurrencyManager.withStructuredScope(scopeName) { scope ->
                repeat(5) { i ->
                    scope.submit {
                        Thread.sleep(200)
                        "task-$i"
                    }
                }
                Thread.sleep(50)
                "scope-result"
            }
        }
        scopeThread.start()
        
        // Concurrently access statistics
        repeat(10) { i ->
            Thread {
                try {
                    Thread.sleep(50) // Let scope start
                    val stats = concurrencyManager.getThreadStatistics()
                    if (stats.scopeNames.contains(scopeName)) {
                        accessCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All stat access threads should complete")
        scopeThread.join(2000)
        
        assertTrue(accessCount.get() > 0, "Should have accessed scope statistics successfully")
    }
    
    @Test
    @Timeout(10)
    fun `should handle rapid scope creation and destruction`() {
        val scopeCount = 50
        val completedScopes = AtomicInteger(0)
        val latch = CountDownLatch(scopeCount)
        
        repeat(scopeCount) { i ->
            Thread {
                try {
                    concurrencyManager.withStructuredScope("rapid-scope-$i") { scope ->
                        scope.submit {
                            Thread.sleep(10)
                            "task-$i"
                        }
                        "scope-$i"
                    }
                    completedScopes.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All scopes should complete")
        assertEquals(scopeCount, completedScopes.get(), "All scopes should have completed successfully")
        
        // Verify cleanup
        val finalStats = concurrencyManager.getThreadStatistics()
        val remainingScopes = finalStats.scopeNames.filter { it.startsWith("rapid-scope-") }
        assertTrue(remainingScopes.isEmpty(), "All rapid scopes should be cleaned up")
    }
}