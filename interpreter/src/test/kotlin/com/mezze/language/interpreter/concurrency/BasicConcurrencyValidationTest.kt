package com.mezze.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * Basic concurrency validation tests for task 5
 * Tests virtual thread spawning, structured concurrency, and performance benchmarks
 */
class BasicConcurrencyValidationTest {
    
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
    fun `should spawn virtual threads successfully`() {
        val results = mutableListOf<String>()
        val latch = CountDownLatch(5)
        
        // Spawn multiple virtual threads
        val threads = (1..5).map { i ->
            concurrencyManager.spawnVirtualThread("test-thread-$i") {
                Thread.sleep(50) // Simulate work
                synchronized(results) {
                    results.add("Thread-$i completed")
                }
                latch.countDown()
            }
        }
        
        // Verify all threads are virtual
        threads.forEach { thread ->
            assertTrue(thread.isVirtual, "Thread should be virtual")
            assertTrue(thread.name.startsWith("test-thread-"))
        }
        
        // Wait for completion
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete")
        assertEquals(5, results.size, "All threads should have completed")
        
        println("✓ Virtual thread spawning test passed - spawned ${threads.size} virtual threads")
    }
    
    @Test
    fun `should manage execution context properly`() {
        val contexts = ConcurrentHashMap<String, ExecutionContext>()
        val latch = CountDownLatch(3)
        
        // Spawn threads with different contexts
        repeat(3) { i ->
            concurrencyManager.spawnVirtualThread("context-test-$i") {
                val context = LoomConcurrencyManager.getCurrentContext()
                assertNotNull(context, "Context should be available")
                contexts[context!!.threadName] = context
                latch.countDown()
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, contexts.size)
        
        // Verify each context has correct thread information
        contexts.forEach { (threadName, context) ->
            assertTrue(threadName.startsWith("context-test-"))
            assertEquals(threadName, context.threadName)
            assertTrue(context.threadId > 0)
            assertTrue(context.getElapsedTime().toMillis() >= 0)
        }
        
        println("✓ Execution context management test passed - managed ${contexts.size} contexts")
    }
    
    @Test
    fun `should execute structured concurrency scope successfully`() {
        val results = mutableListOf<Int>()
        val latch = CountDownLatch(5)
        
        val scopeResult = concurrencyManager.withStructuredScope("test-scope") { scope ->
            val futures = (1..5).map { i ->
                scope.submit(Callable {
                    Thread.sleep(50)
                    synchronized(results) {
                        results.add(i)
                    }
                    latch.countDown()
                    i * 2
                })
            }
            
            futures.map { it.get() }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(2, 4, 6, 8, 10), scopeResult.sorted())
        assertEquals(5, results.size)
        
        println("✓ Structured concurrency test passed - executed ${scopeResult.size} tasks in scope")
    }
    
    @Test
    fun `should handle scope cancellation properly`() {
        val startedTasks = AtomicInteger(0)
        val completedTasks = AtomicInteger(0)
        
        val scopeThread = Thread {
            try {
                concurrencyManager.withStructuredScope("cancellation-test") { scope ->
                    repeat(5) { i ->
                        scope.submit(Callable {
                            startedTasks.incrementAndGet()
                            try {
                                Thread.sleep(1000) // Long task
                                completedTasks.incrementAndGet()
                                "Task-$i"
                            } catch (e: InterruptedException) {
                                throw e
                            }
                        })
                    }
                    Thread.sleep(5000) // Keep scope alive
                    "Scope result"
                }
            } catch (e: Exception) {
                // Expected due to cancellation
            }
        }
        
        scopeThread.start()
        Thread.sleep(200) // Let tasks start
        
        // Cancel the scope
        concurrencyManager.cancelScope("cancellation-test")
        
        scopeThread.join(2000)
        
        // Most tasks should have started but not completed
        assertTrue(startedTasks.get() > 0)
        assertTrue(completedTasks.get() < startedTasks.get())
        
        println("✓ Scope cancellation test passed - started ${startedTasks.get()}, completed ${completedTasks.get()}")
    }
    
    @Test
    fun `should track thread statistics accurately`() {
        val initialStats = concurrencyManager.getThreadStatistics()
        val initialCount = initialStats.totalVirtualThreadsCreated
        
        val latch = CountDownLatch(10)
        
        // Spawn 10 threads
        repeat(10) { i ->
            concurrencyManager.spawnVirtualThread("stats-test-$i") {
                Thread.sleep(100)
                latch.countDown()
            }
        }
        
        // Check statistics during execution
        val duringStats = concurrencyManager.getThreadStatistics()
        assertTrue(duringStats.totalVirtualThreadsCreated >= initialCount + 10)
        
        // Wait for completion
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        
        val finalStats = concurrencyManager.getThreadStatistics()
        assertEquals(initialCount + 10, finalStats.totalVirtualThreadsCreated)
        
        println("✓ Thread statistics test passed - tracked ${finalStats.totalVirtualThreadsCreated} total threads")
    }
    
    @Test
    fun `should demonstrate parallel vs sequential performance`() {
        val taskCount = 10
        val taskDuration = 50L // milliseconds
        
        // Measure parallel execution using virtual threads
        val parallelTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)
            
            repeat(taskCount) { i ->
                concurrencyManager.spawnVirtualThread("parallel-task-$i") {
                    Thread.sleep(taskDuration)
                    latch.countDown()
                }
            }
            
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        }
        
        // Measure sequential execution
        val sequentialTime = measureTimeMillis {
            repeat(taskCount) {
                Thread.sleep(taskDuration)
            }
        }
        
        println("Sequential time: ${sequentialTime}ms")
        println("Parallel time: ${parallelTime}ms")
        println("Speedup: ${sequentialTime.toDouble() / parallelTime}x")
        
        // Parallel should be significantly faster
        assertTrue(parallelTime < sequentialTime * 0.8, 
            "Parallel execution should be faster: ${parallelTime}ms vs ${sequentialTime}ms")
        
        // Should achieve reasonable speedup
        val speedup = sequentialTime.toDouble() / parallelTime
        assertTrue(speedup > 2.0, "Speedup should be > 2x, was ${speedup}x")
        
        println("✓ Performance benchmark test passed - achieved ${speedup}x speedup")
    }
    
    @Test
    fun `should measure virtual thread overhead`() {
        val iterations = 1000
        val results = mutableListOf<Long>()
        
        // Measure virtual thread creation and execution overhead
        repeat(3) { // Multiple runs for average
            val time = measureTimeMillis {
                val latch = CountDownLatch(iterations)
                
                repeat(iterations) { i ->
                    concurrencyManager.spawnVirtualThread("overhead-test-$i") {
                        // Minimal work
                        latch.countDown()
                    }
                }
                
                assertTrue(latch.await(10, TimeUnit.SECONDS))
            }
            
            results.add(time)
        }
        
        val averageTime = results.average()
        val overheadPerThread = averageTime / iterations
        
        println("Average time for $iterations virtual threads: ${averageTime}ms")
        println("Overhead per virtual thread: ${overheadPerThread}ms")
        
        // Virtual thread overhead should be very low
        assertTrue(overheadPerThread < 1.0, 
            "Virtual thread overhead should be < 1ms per thread, was ${overheadPerThread}ms")
        
        println("✓ Virtual thread overhead test passed - ${overheadPerThread}ms per thread")
    }
    
    @Test
    fun `should scale with increasing parallelism`() {
        val results = mutableMapOf<Int, Long>()
        
        // Test with different numbers of parallel tasks
        listOf(1, 2, 4, 8).forEach { taskCount ->
            val executionTime = measureTimeMillis {
                val latch = CountDownLatch(taskCount)
                
                repeat(taskCount) { i ->
                    concurrencyManager.spawnVirtualThread("scale-test-$i") {
                        Thread.sleep(100)
                        latch.countDown()
                    }
                }
                
                assertTrue(latch.await(10, TimeUnit.SECONDS))
            }
            
            results[taskCount] = executionTime
            println("$taskCount tasks: ${executionTime}ms")
        }
        
        // Execution time should not increase linearly with task count
        val time1 = results[1]!!
        val time8 = results[8]!!
        
        // With 8x more tasks, time should be less than 4x longer
        assertTrue(time8 < time1 * 4, 
            "8 tasks (${time8}ms) should not take 4x longer than 1 task (${time1}ms)")
        
        println("✓ Scalability test passed - good scaling characteristics")
    }
    
    @Test
    fun `should handle high concurrency loads`() {
        val taskCount = 100
        val completedTasks = AtomicInteger(0)
        val errors = AtomicInteger(0)
        
        val executionTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)
            
            repeat(taskCount) { i ->
                concurrencyManager.spawnVirtualThread("load-test-$i") {
                    try {
                        Thread.sleep(10) // Short task
                        completedTasks.incrementAndGet()
                        latch.countDown()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        latch.countDown()
                    }
                }
            }
            
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        }
        
        assertEquals(taskCount, completedTasks.get())
        assertEquals(0, errors.get())
        
        println("$taskCount tasks completed in ${executionTime}ms")
        
        // Should complete efficiently
        assertTrue(executionTime < taskCount * 10 * 0.5, 
            "High concurrency execution should be efficient")
        
        println("✓ High concurrency load test passed - handled $taskCount concurrent tasks")
    }
    
    @Test
    fun `should compare virtual threads with traditional thread pool`() {
        val taskCount = 50
        val taskDuration = 20L
        
        // Virtual thread execution
        val virtualThreadTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)
            
            repeat(taskCount) { i ->
                concurrencyManager.spawnVirtualThread("virtual-$i") {
                    Thread.sleep(taskDuration)
                    latch.countDown()
                }
            }
            
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        }
        
        // Traditional thread pool execution (for comparison)
        val threadPoolTime = measureTimeMillis {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors()
            )
            
            try {
                val latch = CountDownLatch(taskCount)
                
                repeat(taskCount) { i ->
                    executor.submit {
                        Thread.sleep(taskDuration)
                        latch.countDown()
                    }
                }
                
                assertTrue(latch.await(10, TimeUnit.SECONDS))
            } finally {
                executor.shutdown()
            }
        }
        
        println("Virtual threads: ${virtualThreadTime}ms")
        println("Thread pool: ${threadPoolTime}ms")
        
        // Virtual threads should be competitive or better
        assertTrue(virtualThreadTime <= threadPoolTime * 1.5, 
            "Virtual threads should be competitive with thread pools")
        
        println("✓ Virtual thread vs thread pool comparison passed")
    }
    
    @Test
    fun `should integrate all concurrency components successfully`() {
        val results = ConcurrentHashMap<String, Any>()
        val latch = CountDownLatch(3)
        
        // Test integration of all components
        concurrencyManager.withStructuredScope("integration-test") { scope ->
            
            // Task 1: Independent operation
            scope.submit(Callable {
                Thread.sleep(100)
                val result = "Data from IO"
                results["task1"] = result
                latch.countDown()
                result
            })
            
            // Task 2: Another independent operation
            scope.submit(Callable {
                Thread.sleep(80)
                val result = 42
                results["task2"] = result
                latch.countDown()
                result
            })
            
            // Task 3: Dependent operation (simulated)
            scope.submit(Callable {
                Thread.sleep(200) // Wait for others to complete
                val result = true
                results["task3"] = result
                latch.countDown()
                result
            })
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, results.size)
        
        assertTrue(results["task1"] is String)
        assertTrue(results["task2"] is Int)
        assertTrue(results["task3"] is Boolean)
        
        println("✓ Integration test passed - all concurrency components working together")
    }
}