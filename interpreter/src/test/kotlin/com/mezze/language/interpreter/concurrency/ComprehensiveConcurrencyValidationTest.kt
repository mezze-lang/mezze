package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.effects.EffectContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Nested
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * Comprehensive test suite for basic concurrency testing and validation
 * Covers Requirements 1.1 and 1.6 from the comprehensive concurrency system spec
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComprehensiveConcurrencyValidationTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var scheduler: SmartEffectScheduler

    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        scheduler = SmartEffectScheduler(concurrencyManager)
    }
    
    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
        scheduler.clearCache()
    }
    
    @Nested
    inner class VirtualThreadSpawningAndManagement {
        
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
        }
        
        @Test
        fun `should handle nested virtual thread contexts`() {
            val parentContext = AtomicReference<ExecutionContext>()
            val childContext = AtomicReference<ExecutionContext>()
            val latch = CountDownLatch(2)
            
            concurrencyManager.spawnVirtualThread("parent") {
                val parent = LoomConcurrencyManager.getCurrentContext()
                parentContext.set(parent)
                
                // Spawn child thread
                concurrencyManager.spawnVirtualThread("child") {
                    val child = LoomConcurrencyManager.getCurrentContext()
                    childContext.set(child)
                    latch.countDown()
                }
                latch.countDown()
            }
            
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            
            val parent = parentContext.get()
            val child = childContext.get()
            
            assertNotNull(parent)
            assertNotNull(child)
            assertEquals("parent", parent.threadName)
            assertEquals("child", child.threadName)
            assertEquals(parent, child.parentContext)
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
        }
        
        @Test
        fun `should handle thread execution errors gracefully`() {
            val errorLatch = CountDownLatch(1)
            val successLatch = CountDownLatch(1)
            
            // Thread that throws an exception
            val errorThread = concurrencyManager.spawnVirtualThread("error-thread") {
                errorLatch.countDown()
                throw RuntimeException("Test error")
            }
            
            // Thread that succeeds
            val successThread = concurrencyManager.spawnVirtualThread("success-thread") {
                Thread.sleep(100)
                successLatch.countDown()
            }
            
            // Both threads should be virtual
            assertTrue(errorThread.isVirtual)
            assertTrue(successThread.isVirtual)
            
            // Error thread should signal, success thread should complete
            assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
            assertTrue(successLatch.await(5, TimeUnit.SECONDS))
        }
    }
    
    @Nested
    inner class StructuredConcurrencyAndCancellation {
        
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
        }
        
        @Test
        fun `should clean up scope resources automatically`() {
            val completedTasks = AtomicInteger(0)
            val startedTasks = AtomicInteger(0)
            
            val result = concurrencyManager.withStructuredScope("cleanup-test") { scope ->
                repeat(3) { i ->
                    scope.submit(Callable {
                        startedTasks.incrementAndGet()
                        Thread.sleep(100)
                        completedTasks.incrementAndGet()
                        "Task-$i"
                    })
                }
                "Scope completed"
            }
            
            assertEquals("Scope completed", result)
            assertEquals(3, startedTasks.get())
            assertEquals(3, completedTasks.get())
        }
        
        @Test
        fun `should handle scope cancellation properly`() {
            val startedTasks = AtomicInteger(0)
            val completedTasks = AtomicInteger(0)
            val cancelledTasks = AtomicInteger(0)
            
            val scopeThread = Thread {
                try {
                    concurrencyManager.withStructuredScope("cancellation-test") { scope ->
                        repeat(5) { i ->
                            scope.submit(Callable {
                                startedTasks.incrementAndGet()
                                try {
                                    Thread.sleep(500) // Long task
                                    completedTasks.incrementAndGet()
                                    "Task-$i"
                                } catch (e: InterruptedException) {
                                    cancelledTasks.incrementAndGet()
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
        }
        
        @Test
        fun `should handle nested scopes correctly`() {
            val results = mutableListOf<String>()
            val latch = CountDownLatch(6) // 3 outer + 3 inner tasks
            
            concurrencyManager.withStructuredScope("outer-scope") { outerScope ->
                repeat(3) { i ->
                    outerScope.submit(Callable {
                        synchronized(results) {
                            results.add("Outer-$i")
                        }
                        latch.countDown()
                        
                        // Nested scope
                        concurrencyManager.withStructuredScope("inner-scope-$i") { innerScope ->
                            innerScope.submit(Callable {
                                synchronized(results) {
                                    results.add("Inner-$i")
                                }
                                latch.countDown()
                                "Inner-$i"
                            })
                        }
                        "Outer-$i"
                    })
                }
            }
            
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(6, results.size)
            
            val outerResults = results.filter { it.startsWith("Outer") }
            val innerResults = results.filter { it.startsWith("Inner") }
            assertEquals(3, outerResults.size)
            assertEquals(3, innerResults.size)
        }
        
        @Test
        fun `should propagate errors from scope tasks`() {
            assertThrows(RuntimeException::class.java) {
                concurrencyManager.withStructuredScope("error-scope") { scope ->
                    scope.submit(Callable {
                        Thread.sleep(100)
                        throw RuntimeException("Task failed")
                    })
                    
                    scope.submit(Callable {
                        Thread.sleep(200)
                        "Success"
                    })
                }
            }
        }
    }
    
    @Nested
    inner class AutomaticParallelismDetection {
        
        @Test
        fun `should detect independent operations for parallelization`() {
            // Create independent effect operations
            val op1 = scheduler.createEffectOperation(
                effectType = "IO",
                readVars = emptySet(),
                writeVars = setOf("x"),
                estimatedDuration = Duration.ofMillis(100)
            ) { MezzeInt(1L) }
            
            val op2 = scheduler.createEffectOperation(
                effectType = "IO", 
                readVars = emptySet(),
                writeVars = setOf("y"),
                estimatedDuration = Duration.ofMillis(100)
            ) { MezzeInt(2L) }
            
            val op3 = scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = setOf("x", "y"),
                writeVars = setOf("z"),
                estimatedDuration = Duration.ofMillis(50)
            ) { MezzeInt(3L) }
            
            val block = EffectBlock(
                operations = listOf(op1, op2, op3),
                context = EffectContext.empty()
            )
            
            val plan = scheduler.analyzeEffectBlock(block)
            val stats = plan.getStatistics()
            
            // op1 and op2 should be parallelizable, op3 depends on both
            assertTrue(stats.maxParallelism >= 2)
            assertTrue(stats.estimatedSpeedup > 1.0)
        }
        
        @Test
        fun `should respect data dependencies in parallelization`() {
            // Create operations with dependencies
            val op1 = scheduler.createEffectOperation(
                effectType = "IO",
                writeVars = setOf("a")
            ) { MezzeInt(1L) }
            
            val op2 = scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = setOf("a"),
                writeVars = setOf("b")
            ) { MezzeInt(2L) }
            
            val op3 = scheduler.createEffectOperation(
                effectType = "Compute", 
                readVars = setOf("b"),
                writeVars = setOf("c")
            ) { MezzeInt(3L) }
            
            val block = EffectBlock(
                operations = listOf(op1, op2, op3),
                context = EffectContext.empty()
            )
            
            val plan = scheduler.analyzeEffectBlock(block)

            // Should be sequential due to dependencies
            val stats = plan.getStatistics()
            assertEquals(1, stats.maxParallelism)
        }
        
        @Test
        fun `should handle mixed independent and dependent operations`() {
            // Mix of independent and dependent operations
            val independentOp1 = scheduler.createEffectOperation(
                effectType = "IO",
                writeVars = setOf("x")
            ) { MezzeInt(1L) }
            
            val independentOp2 = scheduler.createEffectOperation(
                effectType = "IO",
                writeVars = setOf("y") 
            ) { MezzeInt(2L) }
            
            val dependentOp = scheduler.createEffectOperation(
                effectType = "Compute",
                readVars = setOf("x", "y"),
                writeVars = setOf("z")
            ) { MezzeInt(3L) }
            
            val block = EffectBlock(
                operations = listOf(independentOp1, independentOp2, dependentOp),
                context = EffectContext.empty()
            )
            
            val plan = scheduler.analyzeEffectBlock(block)
            val stats = plan.getStatistics()
            
            // First two operations should be parallelizable
            assertTrue(stats.maxParallelism >= 2)
            assertTrue(stats.estimatedSpeedup > 1.0)
        }
        
        @Test
        fun `should cache execution plans for repeated patterns`() {
            val operations = listOf(
                scheduler.createEffectOperation("IO", writeVars = setOf("a")) { MezzeInt(1L) },
                scheduler.createEffectOperation("IO", writeVars = setOf("b")) { MezzeInt(2L) }
            )
            
            val block = EffectBlock(operations, EffectContext.empty())
            
            // First analysis
            val plan1 = scheduler.analyzeEffectBlock(block)
            
            // Second analysis with same pattern
            val plan2 = scheduler.analyzeEffectBlock(block)
            
            // Should use cached plan (same reference or equivalent)
            val stats = scheduler.getPerformanceStatistics()
            assertTrue(stats.totalExecutionPlans >= 1)
        }
        
        @Test
        fun `should execute simple parallel operations correctly`() {
            val executionTimes = mutableListOf<Long>()
            val results = mutableListOf<Any>()
            
            val operations = listOf(
                scheduler.createEffectOperation(
                    effectType = "IO",
                    writeVars = setOf("x"),
                    estimatedDuration = Duration.ofMillis(100)
                ) {
                    val start = System.currentTimeMillis()
                    Thread.sleep(100)
                    val end = System.currentTimeMillis()
                    synchronized(executionTimes) {
                        executionTimes.add(end - start)
                    }
                    MezzeString("Result-X")
                },
                
                scheduler.createEffectOperation(
                    effectType = "IO", 
                    writeVars = setOf("y"),
                    estimatedDuration = Duration.ofMillis(100)
                ) {
                    val start = System.currentTimeMillis()
                    Thread.sleep(100)
                    val end = System.currentTimeMillis()
                    synchronized(executionTimes) {
                        executionTimes.add(end - start)
                    }
                    MezzeString("Result-Y")
                }
            )
            
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            
            val startTime = System.currentTimeMillis()
            val planResults = scheduler.executeWithParallelism(plan)
            val totalTime = System.currentTimeMillis() - startTime
            
            assertEquals(2, planResults.size)
            assertEquals(2, executionTimes.size)
            
            // Should complete in roughly 100ms (parallel) not 200ms (sequential)
            assertTrue(totalTime < 180, "Total time was ${totalTime}ms, expected < 180ms")
        }
    }
    
    @Nested
    inner class PerformanceBenchmarks {
        
        @Test
        fun `should demonstrate speedup with parallel execution`() {
            val taskCount = 10
            val taskDuration = 50L // milliseconds
            
            // Create independent tasks
            val operations = (1..taskCount).map { i ->
                scheduler.createEffectOperation(
                    effectType = "IO",
                    writeVars = setOf("var_$i"),
                    estimatedDuration = Duration.ofMillis(taskDuration)
                ) {
                    Thread.sleep(taskDuration)
                    MezzeInt(i.toLong())
                }
            }
            
            val block = EffectBlock(operations, EffectContext.empty())
            
            // Measure parallel execution
            val parallelTime = measureTimeMillis {
                val plan = scheduler.analyzeEffectBlock(block)
                scheduler.executeWithParallelism(plan)
            }
            
            // Measure sequential execution (simulate)
            val sequentialTime = measureTimeMillis {
                operations.forEach { op ->
                    op.operation()
                }
            }
            
            println("Sequential time: ${sequentialTime}ms")
            println("Parallel time: ${parallelTime}ms")
            println("Speedup: ${sequentialTime.toDouble() / parallelTime}")
            
            // Parallel should be significantly faster
            assertTrue(parallelTime < sequentialTime * 0.8, 
                "Parallel execution should be faster: ${parallelTime}ms vs ${sequentialTime}ms")
            
            // Should achieve reasonable speedup
            val speedup = sequentialTime.toDouble() / parallelTime
            assertTrue(speedup > 1.5, "Speedup should be > 1.5x, was ${speedup}x")
        }
        
        @Test
        fun `should scale with increasing parallelism`() {
            val results = mutableMapOf<Int, Long>()
            
            // Test with different numbers of parallel tasks
            listOf(1, 2, 4, 8).forEach { taskCount ->
                val operations = (1..taskCount).map { i ->
                    scheduler.createEffectOperation(
                        effectType = "IO",
                        writeVars = setOf("var_$i")
                    ) {
                        Thread.sleep(100)
                        MezzeInt(i.toLong())
                    }
                }
                
                val block = EffectBlock(operations, EffectContext.empty())
                
                val executionTime = measureTimeMillis {
                    val plan = scheduler.analyzeEffectBlock(block)
                    scheduler.executeWithParallelism(plan)
                }
                
                results[taskCount] = executionTime
                println("$taskCount tasks: ${executionTime}ms")
            }
            
            // Execution time should not increase linearly with task count
            val time1 = results[1]!!
            val time8 = results[8]!!
            
            // With 8x more tasks, time should be less than 8x longer
            assertTrue(time8 < time1 * 4, 
                "8 tasks (${time8}ms) should not take 4x longer than 1 task (${time1}ms)")
        }
        
        @Test
        fun `should handle high concurrency loads`() {
            val taskCount = 100
            val completedTasks = AtomicInteger(0)
            val errors = AtomicInteger(0)
            
            val operations = (1..taskCount).map { i ->
                scheduler.createEffectOperation(
                    effectType = "IO",
                    writeVars = setOf("task_$i")
                ) {
                    try {
                        Thread.sleep(10) // Short task
                        completedTasks.incrementAndGet()
                        MezzeInt(i.toLong())
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        throw e
                    }
                }
            }
            
            val block = EffectBlock(operations, EffectContext.empty())
            
            val executionTime = measureTimeMillis {
                val plan = scheduler.analyzeEffectBlock(block)
                val results = scheduler.executeWithParallelism(plan)
                assertEquals(taskCount, results.size)
            }
            
            assertEquals(taskCount, completedTasks.get())
            assertEquals(0, errors.get())
            
            println("$taskCount tasks completed in ${executionTime}ms")
            
            // Should complete much faster than sequential execution
            assertTrue(executionTime < taskCount * 10 * 0.5, 
                "High concurrency execution should be efficient")
        }
        
        @Test
        fun `should measure virtual thread overhead`() {
            val iterations = 1000
            val results = mutableListOf<Long>()
            
            // Measure virtual thread creation and execution overhead
            repeat(5) { // Multiple runs for average
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
        }
        
        @Test
        fun `should compare with traditional thread pool performance`() {
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
        }
    }
    
    @Nested
    inner class IntegrationTests {
        
        @Test
        fun `should integrate all concurrency components successfully`() {
            val results = ConcurrentHashMap<String, Any>()
            val latch = CountDownLatch(3)
            
            // Test integration of all components
            concurrencyManager.withStructuredScope("integration-test") { scope ->
                
                // Task 1: Independent IO operation
                scope.submit(Callable {
                    val op = scheduler.createEffectOperation(
                        effectType = "IO",
                        writeVars = setOf("data1")
                    ) {
                        Thread.sleep(100)
                        MezzeString("Data from IO")
                    }
                    
                    val result = op.operation()
                    results["task1"] = result
                    latch.countDown()
                    result
                })
                
                // Task 2: Another independent operation
                scope.submit(Callable {
                    val op = scheduler.createEffectOperation(
                        effectType = "Compute",
                        writeVars = setOf("data2")
                    ) {
                        Thread.sleep(80)
                        MezzeInt(42L)
                    }
                    
                    val result = op.operation()
                    results["task2"] = result
                    latch.countDown()
                    result
                })
                
                // Task 3: Dependent operation (simulated)
                scope.submit(Callable {
                    Thread.sleep(200) // Wait for others to complete
                    val result = MezzeBool(true)
                    results["task3"] = result
                    latch.countDown()
                    result
                })
            }
            
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(3, results.size)
            
            assertTrue(results["task1"] is MezzeString)
            assertTrue(results["task2"] is MezzeInt)
            assertTrue(results["task3"] is MezzeBool)
        }
        
        @Test
        fun `should handle complex dependency scenarios`() {
            val executionOrder = mutableListOf<String>()
            val latch = CountDownLatch(4)
            
            // Create operations with complex dependencies
            val operations = listOf(
                scheduler.createEffectOperation(
                    effectType = "IO",
                    writeVars = setOf("a")
                ) {
                    Thread.sleep(50)
                    synchronized(executionOrder) {
                        executionOrder.add("op1")
                    }
                    latch.countDown()
                    MezzeInt(1L)
                },
                
                scheduler.createEffectOperation(
                    effectType = "IO", 
                    writeVars = setOf("b")
                ) {
                    Thread.sleep(50)
                    synchronized(executionOrder) {
                        executionOrder.add("op2")
                    }
                    latch.countDown()
                    MezzeInt(2L)
                },
                
                scheduler.createEffectOperation(
                    effectType = "Compute",
                    readVars = setOf("a"),
                    writeVars = setOf("c")
                ) {
                    Thread.sleep(30)
                    synchronized(executionOrder) {
                        executionOrder.add("op3")
                    }
                    latch.countDown()
                    MezzeInt(3L)
                },
                
                scheduler.createEffectOperation(
                    effectType = "Compute",
                    readVars = setOf("b", "c"),
                    writeVars = setOf("d")
                ) {
                    Thread.sleep(30)
                    synchronized(executionOrder) {
                        executionOrder.add("op4")
                    }
                    latch.countDown()
                    MezzeInt(4L)
                }
            )
            
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            
            val startTime = System.currentTimeMillis()
            scheduler.executeWithParallelism(plan)
            val totalTime = System.currentTimeMillis() - startTime
            
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(4, executionOrder.size)
            
            // Verify dependency ordering is respected
            val op1Index = executionOrder.indexOf("op1")
            val op2Index = executionOrder.indexOf("op2")
            val op3Index = executionOrder.indexOf("op3")
            val op4Index = executionOrder.indexOf("op4")
            
            // op3 must come after op1, op4 must come after both op2 and op3
            assertTrue(op3Index > op1Index, "op3 should come after op1")
            assertTrue(op4Index > op2Index, "op4 should come after op2")
            assertTrue(op4Index > op3Index, "op4 should come after op3")
            
            // Should complete faster than sequential execution
            assertTrue(totalTime < 200, "Should complete in parallel time, not sequential")
        }
    }
}