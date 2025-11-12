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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for virtual thread spawning and management
 * Tests requirement 1.1: Runtime-managed implicit parallelism
 */
class VirtualThreadManagementTest {
    
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
    fun `should spawn virtual thread successfully`() {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val threadInfo = AtomicReference<String>()
        
        val thread = concurrencyManager.spawnVirtualThread("test-spawn") {
            result.set("Virtual thread executed")
            threadInfo.set("${Thread.currentThread().name}:${Thread.currentThread().isVirtual}")
            latch.countDown()
        }
        
        assertTrue(thread.isVirtual, "Thread should be virtual")
        assertTrue(thread.name.contains("test-spawn"), "Thread name should contain specified name")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread should complete within timeout")
        assertEquals("Virtual thread executed", result.get())
        assertTrue(threadInfo.get().endsWith(":true"), "Thread should report as virtual")
    }
    
    @Test
    @Timeout(10)
    fun `should spawn multiple virtual threads concurrently`() {
        val threadCount = 100
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<String, String>()
        val startTime = System.currentTimeMillis()
        
        repeat(threadCount) { i ->
            concurrencyManager.spawnVirtualThread("concurrent-$i") {
                Thread.sleep(100) // Simulate work
                results["thread-$i"] = "completed"
                latch.countDown()
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete")
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertEquals(threadCount, results.size, "All threads should have completed")
        assertTrue(duration < 2000, "Concurrent execution should be faster than sequential (${duration}ms)")
    }
    
    @Test
    @Timeout(10)
    fun `should manage execution context properly`() {
        val contextResults = ConcurrentHashMap<String, ExecutionContext>()
        val latch = CountDownLatch(3)
        
        repeat(3) { i ->
            concurrencyManager.spawnVirtualThread("context-test-$i") {
                val context = LoomConcurrencyManager.getCurrentContext()
                assertNotNull(context, "Context should be available in virtual thread")
                contextResults["thread-$i"] = context!!
                latch.countDown()
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, contextResults.size)
        
        contextResults.values.forEach { context ->
            assertTrue(context.threadId > 0, "Thread ID should be positive")
            assertTrue(context.threadName.startsWith("context-test-"), "Thread name should match pattern")
            assertNotNull(context.startTime, "Start time should be set")
        }
    }
    
    @Test
    @Timeout(10)
    fun `should handle virtual thread exceptions gracefully`() {
        val errorLatch = CountDownLatch(1)
        val successLatch = CountDownLatch(1)
        val errorCaught = AtomicReference<Exception>()
        
        // Spawn a thread that will throw an exception
        val errorThread = concurrencyManager.spawnVirtualThread("error-thread") {
            try {
                throw RuntimeException("Test exception")
            } catch (e: Exception) {
                errorCaught.set(e)
                errorLatch.countDown()
                throw e
            }
        }
        
        // Spawn a normal thread to ensure the error doesn't affect other threads
        concurrencyManager.spawnVirtualThread("success-thread") {
            Thread.sleep(50)
            successLatch.countDown()
        }
        
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Error should be caught")
        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Success thread should complete")
        assertNotNull(errorCaught.get(), "Exception should be captured")
        assertEquals("Test exception", errorCaught.get().message)
    }
    
    @Test
    @Timeout(10)
    fun `should track thread statistics accurately`() {
        val initialStats = concurrencyManager.getThreadStatistics()
        val initialCount = initialStats.totalVirtualThreadsCreated
        
        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        
        repeat(threadCount) { i ->
            concurrencyManager.spawnVirtualThread("stats-test-$i") {
                Thread.sleep(100)
                latch.countDown()
            }
        }
        
        // Check stats while threads are running
        val runningStats = concurrencyManager.getThreadStatistics()
        assertTrue(runningStats.totalVirtualThreadsCreated >= initialCount + threadCount,
            "Should track created threads")
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        
        val finalStats = concurrencyManager.getThreadStatistics()
        assertEquals(initialCount + threadCount, finalStats.totalVirtualThreadsCreated,
            "Should accurately count total threads created")
    }
    
    @Test
    @Timeout(10)
    fun `should support nested virtual thread contexts`() {
        val results = ConcurrentHashMap<String, ExecutionContext>()
        val latch = CountDownLatch(2)
        
        concurrencyManager.spawnVirtualThread("parent") {
            val parentContext = LoomConcurrencyManager.getCurrentContext()
            results["parent"] = parentContext!!
            
            concurrencyManager.spawnVirtualThread("child") {
                val childContext = LoomConcurrencyManager.getCurrentContext()
                results["child"] = childContext!!
                latch.countDown()
            }
            latch.countDown()
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, results.size)
        
        val parentContext = results["parent"]!!
        val childContext = results["child"]!!
        
        assertEquals("parent", parentContext.threadName)
        assertEquals("child", childContext.threadName)
        assertEquals(parentContext, childContext.parentContext, "Child should have parent context")
    }
    
    @Test
    @Timeout(10)
    fun `should handle high concurrency loads`() {
        val threadCount = 1000
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)
        val startTime = System.currentTimeMillis()
        
        repeat(threadCount) { i ->
            concurrencyManager.spawnVirtualThread("load-test-$i") {
                counter.incrementAndGet()
                Thread.sleep(1) // Minimal work
                latch.countDown()
            }
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "High load should complete within timeout")
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertEquals(threadCount, counter.get(), "All threads should have executed")
        assertTrue(duration < 5000, "High concurrency should complete quickly (${duration}ms)")
        
        val stats = concurrencyManager.getThreadStatistics()
        assertTrue(stats.totalVirtualThreadsCreated >= threadCount, "Should track all created threads")
    }
    
    @Test
    @Timeout(10)
    fun `should preserve thread-local context across suspension points`() {
        val contextValues = ConcurrentHashMap<String, String>()
        val latch = CountDownLatch(1)
        
        concurrencyManager.spawnVirtualThread("context-preservation") {
            val context = LoomConcurrencyManager.getCurrentContext()
            val enhancedContext = context?.withMetadata("test-key", "test-value")
            
            if (enhancedContext != null) {
                LoomUtils.withContext(enhancedContext) {
                    // Simulate suspension point (blocking I/O)
                    Thread.sleep(100)
                    
                    val currentContext = LoomConcurrencyManager.getCurrentContext()
                    contextValues["before-sleep"] = currentContext?.metadata?.get("test-key")?.toString() ?: "null"
                    
                    // Another suspension point
                    Thread.sleep(50)
                    
                    val afterContext = LoomConcurrencyManager.getCurrentContext()
                    contextValues["after-sleep"] = afterContext?.metadata?.get("test-key")?.toString() ?: "null"
                    
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("test-value", contextValues["before-sleep"], "Context should be preserved before sleep")
        assertEquals("test-value", contextValues["after-sleep"], "Context should be preserved after sleep")
    }
}