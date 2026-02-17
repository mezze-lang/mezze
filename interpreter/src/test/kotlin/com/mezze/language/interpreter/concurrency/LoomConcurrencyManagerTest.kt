package com.mezze.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LoomConcurrencyManagerTest {
    
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
    fun `should spawn virtual thread successfully`() {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        
        val thread = concurrencyManager.spawnVirtualThread("test-thread") {
            result.set("Hello from virtual thread")
            latch.countDown()
        }
        
        assertTrue(thread.isVirtual)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("Hello from virtual thread", result.get())
    }
    
    @Test
    fun `should manage execution context with scoped values`() {
        val contextRef = AtomicReference<ExecutionContext>()
        val latch = CountDownLatch(1)
        
        concurrencyManager.spawnVirtualThread("context-test") {
            val context = LoomConcurrencyManager.getCurrentContext()
            contextRef.set(context)
            latch.countDown()
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val context = contextRef.get()
        assertNotNull(context)
        assertEquals("context-test", context.threadName)
        assertTrue(context.threadId > 0)
    }
    
    @Test
    fun `should execute structured concurrency scope`() {
        val results = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val latch = CountDownLatch(3)

        println("Starting structured concurrency scope test")
        val result = concurrencyManager.withStructuredScope("test-scope") { scope ->
            // Submit multiple tasks
            val futures = (1..3).map { i ->
                scope.submit {
                    println("Task $i starting")
                    Thread.sleep(100) // Simulate work
                    results.add(i)
                    latch.countDown()
                    println("Task $i completed")
                    i * 2
                }
            }

            println("Waiting for all tasks to complete")
            // Wait for all tasks to complete
            futures.map { it.get() }
        }

        println("Scope completed, result: $result")
        println("Results list: $results")
        println("Latch count: ${latch.count}")

        val latchCompleted = latch.await(5, TimeUnit.SECONDS)
        println("Latch completed: $latchCompleted")
        assertTrue(latchCompleted, "Latch did not complete in time")
        assertEquals(listOf(2, 4, 6), result.sorted(), "Result does not match expected: $result")
        assertEquals(3, results.size, "Results size is ${results.size}, expected 3: $results")
    }
    
    @Test
    fun `should execute all tasks concurrently`() {
        val startTime = System.currentTimeMillis()
        val counter = AtomicInteger(0)
        
        val tasks = (1..5).map { i ->
            Callable {
                Thread.sleep(200) // Each task takes 200ms
                counter.incrementAndGet()
                i
            }
        }
        
        val results = concurrencyManager.executeAll(tasks)
        val endTime = System.currentTimeMillis()
        
        assertEquals(listOf(1, 2, 3, 4, 5), results)
        assertEquals(5, counter.get())
        
        // Should complete in roughly 200ms (parallel) not 1000ms (sequential)
        val duration = endTime - startTime
        assertTrue(duration < 800, "Duration was ${duration}ms, expected < 800ms")
    }
    
    @Test
    fun `should execute race and return first result`() {
        val tasks = listOf(
            Callable { Thread.sleep(300); "slow" },
            Callable { Thread.sleep(100); "fast" },
            Callable { Thread.sleep(200); "medium" }
        )
        
        val result = concurrencyManager.executeRace(tasks)
        assertEquals("fast", result)
    }
    
    @Test
    fun `should track thread statistics`() {
        val latch = CountDownLatch(3)
        
        // Spawn some threads
        repeat(3) { i ->
            concurrencyManager.spawnVirtualThread("stats-test-$i") {
                Thread.sleep(100)
                latch.countDown()
            }
        }
        
        // Check statistics
        val stats = concurrencyManager.getThreadStatistics()
        assertTrue(stats.totalVirtualThreadsCreated >= 3)
        
        latch.await(5, TimeUnit.SECONDS)
    }
    
    @Test
    fun `should handle nested execution contexts`() {
        val contexts = mutableListOf<ExecutionContext>()
        val latch = CountDownLatch(2)
        
        concurrencyManager.spawnVirtualThread("parent") {
            val parentContext = LoomConcurrencyManager.getCurrentContext()
            contexts.add(parentContext!!)
            
            concurrencyManager.spawnVirtualThread("child") {
                val childContext = LoomConcurrencyManager.getCurrentContext()
                contexts.add(childContext!!)
                latch.countDown()
            }
            latch.countDown()
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, contexts.size)
        
        val parent = contexts.find { it.threadName == "parent" }
        val child = contexts.find { it.threadName == "child" }
        
        assertNotNull(parent)
        assertNotNull(child)
        assertEquals(parent, child?.parentContext)
    }
    
    @Test
    fun `should handle scope cancellation`() {
        val completedTasks = AtomicInteger(0)
        val scopeName = "cancellation-test"
        
        // Start a scope with long-running tasks
        val thread = Thread {
            concurrencyManager.withStructuredScope(scopeName) { scope ->
                repeat(5) { i ->
                    scope.submit {
                        try {
                            Thread.sleep(1000) // Long task
                            completedTasks.incrementAndGet()
                        } catch (e: InterruptedException) {
                            // Task was cancelled
                        }
                    }
                }
                Thread.sleep(5000) // Keep scope alive
            }
        }
        
        thread.start()
        Thread.sleep(100) // Let tasks start
        
        // Cancel the scope
        concurrencyManager.cancelScope(scopeName)
        
        thread.join(2000) // Wait for cleanup
        
        // Most tasks should not have completed due to cancellation
        assertTrue(completedTasks.get() < 5)
    }
    
    @Test
    fun `should preserve execution context metadata`() {
        val metadataRef = AtomicReference<Map<String, Any>>()
        val latch = CountDownLatch(1)
        
        concurrencyManager.spawnVirtualThread("metadata-test") {
            val context = LoomConcurrencyManager.getCurrentContext()
            val enhancedContext = context?.withMetadata("test-key", "test-value")
            
            if (enhancedContext != null) {
                LoomUtils.withContext(enhancedContext) {
                    val currentContext = LoomConcurrencyManager.getCurrentContext()
                    metadataRef.set(currentContext?.metadata)
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val metadata = metadataRef.get()
        assertNotNull(metadata)
        assertEquals("test-value", metadata["test-key"])
    }
}