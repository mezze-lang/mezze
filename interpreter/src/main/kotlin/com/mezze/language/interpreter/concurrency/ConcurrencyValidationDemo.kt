package com.mezze.language.interpreter.concurrency

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * Demonstration of basic concurrency testing and validation
 * This validates the implementation of task 5 requirements:
 * - Virtual thread spawning and management
 * - Structured concurrency scope cleanup and cancellation
 * - Performance benchmarks comparing sequential vs parallel execution
 */
object ConcurrencyValidationDemo {
    
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Mezze Concurrency System Validation Demo ===")
        println()
        
        val concurrencyManager = LoomConcurrencyManager()
        
        try {
            // Test 1: Virtual Thread Spawning and Management
            println("1. Testing Virtual Thread Spawning and Management...")
            testVirtualThreadSpawning(concurrencyManager)
            println()
            
            // Test 2: Execution Context Management
            println("2. Testing Execution Context Management...")
            testExecutionContextManagement(concurrencyManager)
            println()
            
            // Test 3: Structured Concurrency
            println("3. Testing Structured Concurrency...")
            testStructuredConcurrency(concurrencyManager)
            println()
            
            // Test 4: Scope Cancellation
            println("4. Testing Scope Cancellation...")
            testScopeCancellation(concurrencyManager)
            println()
            
            // Test 5: Performance Benchmarks
            println("5. Testing Performance Benchmarks...")
            testPerformanceBenchmarks(concurrencyManager)
            println()
            
            // Test 6: Thread Statistics
            println("6. Testing Thread Statistics...")
            testThreadStatistics(concurrencyManager)
            println()
            
            // Test 7: High Concurrency Load
            println("7. Testing High Concurrency Load...")
            testHighConcurrencyLoad(concurrencyManager)
            println()
            
            println("=== All Concurrency Validation Tests Passed! ===")
            
        } finally {
            concurrencyManager.shutdown()
        }
    }
    
    private fun testVirtualThreadSpawning(concurrencyManager: LoomConcurrencyManager) {
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
            assert(thread.isVirtual) { "Thread should be virtual" }
            assert(thread.name.startsWith("test-thread-")) { "Thread name should start with test-thread-" }
        }
        
        // Wait for completion
        assert(latch.await(5, TimeUnit.SECONDS)) { "All threads should complete" }
        assert(results.size == 5) { "All threads should have completed" }
        
        println("   ✓ Successfully spawned ${threads.size} virtual threads")
        println("   ✓ All threads completed work: ${results.sorted()}")
    }
    
    private fun testExecutionContextManagement(concurrencyManager: LoomConcurrencyManager) {
        val contexts = ConcurrentHashMap<String, ExecutionContext>()
        val latch = CountDownLatch(3)
        
        // Spawn threads with different contexts
        repeat(3) { i ->
            concurrencyManager.spawnVirtualThread("context-test-$i") {
                val context = LoomConcurrencyManager.getCurrentContext()
                assert(context != null) { "Context should be available" }
                contexts[context!!.threadName] = context
                latch.countDown()
            }
        }
        
        assert(latch.await(5, TimeUnit.SECONDS)) { "All context tests should complete" }
        assert(contexts.size == 3) { "Should have 3 contexts" }
        
        // Verify each context has correct thread information
        contexts.forEach { (threadName, context) ->
            assert(threadName.startsWith("context-test-")) { "Thread name should start with context-test-" }
            assert(context.threadName == threadName) { "Context thread name should match" }
            assert(context.threadId > 0) { "Thread ID should be positive" }
            assert(context.getElapsedTime().toMillis() >= 0) { "Elapsed time should be non-negative" }
        }
        
        println("   ✓ Successfully managed ${contexts.size} execution contexts")
        println("   ✓ All contexts have correct thread information")
    }
    
    private fun testStructuredConcurrency(concurrencyManager: LoomConcurrencyManager) {
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
        
        assert(latch.await(5, TimeUnit.SECONDS)) { "All structured tasks should complete" }
        assert(scopeResult.sorted() == listOf(2, 4, 6, 8, 10)) { "Scope results should be correct" }
        assert(results.size == 5) { "All tasks should have executed" }
        
        println("   ✓ Successfully executed ${scopeResult.size} tasks in structured scope")
        println("   ✓ Scope results: ${scopeResult.sorted()}")
    }
    
    private fun testScopeCancellation(concurrencyManager: LoomConcurrencyManager) {
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
        assert(startedTasks.get() > 0) { "Some tasks should have started" }
        assert(completedTasks.get() < startedTasks.get()) { "Not all tasks should have completed due to cancellation" }
        
        println("   ✓ Scope cancellation working: started ${startedTasks.get()}, completed ${completedTasks.get()}")
    }
    
    private fun testPerformanceBenchmarks(concurrencyManager: LoomConcurrencyManager) {
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
            
            assert(latch.await(10, TimeUnit.SECONDS)) { "Parallel tasks should complete" }
        }
        
        // Measure sequential execution
        val sequentialTime = measureTimeMillis {
            repeat(taskCount) {
                Thread.sleep(taskDuration)
            }
        }
        
        val speedup = sequentialTime.toDouble() / parallelTime
        
        println("   ✓ Sequential time: ${sequentialTime}ms")
        println("   ✓ Parallel time: ${parallelTime}ms")
        println("   ✓ Speedup: ${String.format("%.2f", speedup)}x")
        
        // Parallel should be significantly faster
        assert(parallelTime < sequentialTime * 0.8) { 
            "Parallel execution should be faster: ${parallelTime}ms vs ${sequentialTime}ms" 
        }
        
        // Should achieve reasonable speedup
        assert(speedup > 2.0) { "Speedup should be > 2x, was ${speedup}x" }
        
        println("   ✓ Performance benchmark passed - achieved ${String.format("%.2f", speedup)}x speedup")
    }
    
    private fun testThreadStatistics(concurrencyManager: LoomConcurrencyManager) {
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
        assert(duringStats.totalVirtualThreadsCreated >= initialCount + 10) { 
            "Statistics should show increased thread count" 
        }
        
        // Wait for completion
        assert(latch.await(5, TimeUnit.SECONDS)) { "Statistics test threads should complete" }
        
        val finalStats = concurrencyManager.getThreadStatistics()
        assert(finalStats.totalVirtualThreadsCreated == initialCount + 10) { 
            "Final statistics should show correct thread count" 
        }
        
        println("   ✓ Thread statistics tracking working")
        println("   ✓ Total virtual threads created: ${finalStats.totalVirtualThreadsCreated}")
        println("   ✓ Active virtual threads: ${finalStats.activeVirtualThreads}")
    }
    
    private fun testHighConcurrencyLoad(concurrencyManager: LoomConcurrencyManager) {
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
            
            assert(latch.await(10, TimeUnit.SECONDS)) { "High concurrency load test should complete" }
        }
        
        assert(completedTasks.get() == taskCount) { "All tasks should complete successfully" }
        assert(errors.get() == 0) { "No errors should occur" }
        
        println("   ✓ High concurrency load test passed")
        println("   ✓ $taskCount tasks completed in ${executionTime}ms")
        println("   ✓ Completed tasks: ${completedTasks.get()}, Errors: ${errors.get()}")
        
        // Should complete efficiently
        assert(executionTime < taskCount * 10 * 0.5) { 
            "High concurrency execution should be efficient" 
        }
    }
}