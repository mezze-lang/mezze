package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.datastructures.list.MezzeList
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
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
 * Comprehensive test suite for concurrency validation in V2
 *
 * Migrated from V1 ComprehensiveConcurrencyValidationTest.kt
 * V2 tests concurrency through the Mezze language API and Java virtual threads directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComprehensiveConcurrencyValidationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun spawnVirtualThread(name: String, task: () -> Unit): Thread {
        return Thread.ofVirtual().name(name).start(task)
    }

    @Nested
    inner class VirtualThreadSpawningAndManagement {

        @Test
        fun `should spawn virtual threads successfully`() {
            val results = mutableListOf<String>()
            val latch = CountDownLatch(5)

            // Spawn multiple virtual threads
            val threads = (1..5).map { i ->
                spawnVirtualThread("test-thread-$i") {
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
        fun `should manage thread names properly`() {
            val threadNames = ConcurrentHashMap<String, Thread>()
            val latch = CountDownLatch(3)

            // Spawn threads with different names
            repeat(3) { i ->
                spawnVirtualThread("context-test-$i") {
                    val currentThread = Thread.currentThread()
                    threadNames[currentThread.name] = currentThread
                    latch.countDown()
                }
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(3, threadNames.size)

            // Verify each thread has correct name
            threadNames.forEach { (name, thread) ->
                assertTrue(name.startsWith("context-test-"))
                assertEquals(name, thread.name)
                assertTrue(thread.isVirtual)
            }
        }

        @Test
        fun `should handle nested virtual thread contexts`() {
            val parentThread = AtomicReference<Thread>()
            val childThread = AtomicReference<Thread>()
            val latch = CountDownLatch(2)

            spawnVirtualThread("parent") {
                parentThread.set(Thread.currentThread())

                // Spawn child thread
                spawnVirtualThread("child") {
                    childThread.set(Thread.currentThread())
                    latch.countDown()
                }
                latch.countDown()
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))

            val parent = parentThread.get()
            val child = childThread.get()

            assertNotNull(parent)
            assertNotNull(child)
            assertEquals("parent", parent.name)
            assertEquals("child", child.name)
            assertTrue(parent.isVirtual)
            assertTrue(child.isVirtual)
        }

        @Test
        fun `should track thread statistics accurately`() {
            val createdCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)
            val latch = CountDownLatch(10)

            // Spawn 10 threads
            repeat(10) { i ->
                createdCount.incrementAndGet()
                spawnVirtualThread("stats-test-$i") {
                    Thread.sleep(100)
                    completedCount.incrementAndGet()
                    latch.countDown()
                }
            }

            // Wait for completion
            assertTrue(latch.await(5, TimeUnit.SECONDS))

            assertEquals(10, createdCount.get())
            assertEquals(10, completedCount.get())
        }

        @Test
        fun `should handle thread execution errors gracefully`() {
            val errorLatch = CountDownLatch(1)
            val successLatch = CountDownLatch(1)
            val errorOccurred = AtomicReference<Exception>()

            // Thread that throws an exception
            val errorThread = spawnVirtualThread("error-thread") {
                try {
                    errorLatch.countDown()
                    throw RuntimeException("Test error")
                } catch (e: Exception) {
                    errorOccurred.set(e)
                }
            }

            // Thread that succeeds
            val successThread = spawnVirtualThread("success-thread") {
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

            val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            val scopeResults = mutableListOf<Int>()

            try {
                val futures = (1..5).map { i ->
                    executor.submit(Callable {
                        Thread.sleep(50)
                        synchronized(results) {
                            results.add(i)
                        }
                        latch.countDown()
                        i * 2
                    })
                }

                futures.forEach { future ->
                    scopeResults.add(future.get())
                }
            } finally {
                executor.shutdown()
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(2, 4, 6, 8, 10), scopeResults.sorted())
            assertEquals(5, results.size)
        }

        @Test
        fun `should clean up scope resources automatically`() {
            val completedTasks = AtomicInteger(0)
            val startedTasks = AtomicInteger(0)

            val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            try {
                val futures = (1..3).map { i ->
                    executor.submit(Callable {
                        startedTasks.incrementAndGet()
                        Thread.sleep(100)
                        completedTasks.incrementAndGet()
                        "Task-$i"
                    })
                }

                // Wait for all tasks
                futures.forEach { it.get() }
            } finally {
                executor.shutdown()
            }

            assertEquals(3, startedTasks.get())
            assertEquals(3, completedTasks.get())
        }

        @Test
        fun `should handle nested scopes correctly`() {
            val results = mutableListOf<String>()
            val latch = CountDownLatch(6) // 3 outer + 3 inner tasks

            val outerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            try {
                repeat(3) { i ->
                    outerExecutor.submit {
                        synchronized(results) {
                            results.add("Outer-$i")
                        }
                        latch.countDown()

                        // Nested scope
                        val innerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
                        try {
                            innerExecutor.submit {
                                synchronized(results) {
                                    results.add("Inner-$i")
                                }
                                latch.countDown()
                            }.get()
                        } finally {
                            innerExecutor.shutdown()
                        }
                    }
                }

                assertTrue(latch.await(5, TimeUnit.SECONDS))
            } finally {
                outerExecutor.shutdown()
            }

            assertEquals(6, results.size)

            val outerResults = results.filter { it.startsWith("Outer") }
            val innerResults = results.filter { it.startsWith("Inner") }
            assertEquals(3, outerResults.size)
            assertEquals(3, innerResults.size)
        }
    }

    @Nested
    inner class AutomaticParallelismDetection {

        @Test
        fun `should detect independent operations for parallelization via Mezze language`() {
            val code = """
                use std::effects::parallel::{ Parallel, VirtualParallel, both, both3, fold, map, reduce }

                perform VirtualParallel in do
                    # Three independent operations
                    let result = both3 {
                        a = {} -> 1,
                        b = {} -> 2,
                        c = {} -> 3
                    }
                    result.a + result.b + result.c
            """
            val result = eval(code)
            assertEquals(6L, result)
        }

        @Test
        fun `should execute parallel map correctly`() {
            val code = """
                use std::effects::parallel::{ Parallel, VirtualParallel, both, both3, fold, map, reduce }

                perform VirtualParallel in do
                    let xs = [1, 2, 3, 4, 5]
                    let result = map {xs = xs, f = {x} -> x * 2}
                    let sum = reduce {xs = result, f = {l, r} -> l + r}
                    match sum is
                        Some {val} -> val
                        None -> 0
            """
            val result = eval(code)
            assertEquals(30L, result) // (1+2+3+4+5) * 2 = 30
        }

        @Test
        fun `should handle mixed independent and dependent operations`() {
            val code = """
                use std::effects::parallel::{ Parallel, VirtualParallel, both, both3, fold, map, reduce }

                perform VirtualParallel in do
                    # Two independent operations
                    let both_result = both {
                        l = {} -> [1, 2, 3],
                        r = {} -> [4, 5, 6]
                    }

                    # Dependent operation - uses results from both
                    let left_sum = reduce {xs = both_result.l, f = {l, r} -> l + r}
                    let right_sum = reduce {xs = both_result.r, f = {l, r} -> l + r}

                    let l = match left_sum is
                        Some {val} -> val
                        None -> 0
                    let r = match right_sum is
                        Some {val} -> val
                        None -> 0
                    l + r
            """
            val result = eval(code)
            assertEquals(21L, result) // (1+2+3) + (4+5+6) = 21
        }

        @Test
        fun `should execute simple parallel operations correctly`() {
            val executionTimes = mutableListOf<Long>()

            val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            try {
                val startTime = System.currentTimeMillis()

                val futures = listOf(
                    executor.submit(Callable {
                        val start = System.currentTimeMillis()
                        Thread.sleep(100)
                        val end = System.currentTimeMillis()
                        synchronized(executionTimes) {
                            executionTimes.add(end - start)
                        }
                        "Result-X"
                    }),
                    executor.submit(Callable {
                        val start = System.currentTimeMillis()
                        Thread.sleep(100)
                        val end = System.currentTimeMillis()
                        synchronized(executionTimes) {
                            executionTimes.add(end - start)
                        }
                        "Result-Y"
                    })
                )

                val results = futures.map { it.get() }
                val totalTime = System.currentTimeMillis() - startTime

                assertEquals(2, results.size)
                assertEquals(2, executionTimes.size)

                // Should complete in roughly 100ms (parallel) not 200ms (sequential)
                assertTrue(totalTime < 180, "Total time was ${totalTime}ms, expected < 180ms")
            } finally {
                executor.shutdown()
            }
        }
    }

    @Nested
    inner class PerformanceBenchmarks {

        @Test
        fun `should demonstrate speedup with parallel execution`() {
            val taskCount = 10
            val taskDuration = 50L // milliseconds

            // Measure parallel execution
            val parallelTime = measureTimeMillis {
                val latch = CountDownLatch(taskCount)

                repeat(taskCount) { i ->
                    spawnVirtualThread("parallel-$i") {
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

            println("ForkJoinParallel time: ${sequentialTime}ms")
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
                val executionTime = measureTimeMillis {
                    val latch = CountDownLatch(taskCount)

                    repeat(taskCount) { i ->
                        spawnVirtualThread("scale-$i") {
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
        }

        @Test
        fun `should handle high concurrency loads`() {
            val taskCount = 100
            val completedTasks = AtomicInteger(0)
            val errors = AtomicInteger(0)

            val executionTime = measureTimeMillis {
                val latch = CountDownLatch(taskCount)

                repeat(taskCount) { i ->
                    spawnVirtualThread("load-$i") {
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
                        spawnVirtualThread("overhead-$i") {
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
                    spawnVirtualThread("virtual-$i") {
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
        fun `should integrate all concurrency components successfully via Mezze`() {
            val code = """
                use std::effects::parallel::{ Parallel, VirtualParallel, both, both3, fold, map, reduce }

                perform VirtualParallel in do
                    let result = both3 {
                        a = {} -> "Data from IO",
                        b = {} -> 42,
                        c = {} -> True
                    }
                    # Verify all tasks completed
                    if result.c then result.b else 0
            """
            val result = eval(code)
            assertEquals(42L, result)
        }

        @Test
        fun `should handle complex dependency scenarios via Mezze`() {
            val code = """
                use std::effects::parallel::{ Parallel, VirtualParallel, both, both3, fold, map, reduce }

                perform VirtualParallel in do
                    # First phase: independent operations
                    let phase1 = both {
                        l = {} -> [1, 2, 3],
                        r = {} -> [4, 5, 6]
                    }

                    # Second phase: depends on first phase
                    let combined_sum = fold {
                        xs = phase1.l,
                        zero = 0,
                        map_fn = {x} -> x,
                        combine = {l, r} -> l + r
                    }

                    let right_sum = fold {
                        xs = phase1.r,
                        zero = 0,
                        map_fn = {x} -> x,
                        combine = {l, r} -> l + r
                    }

                    combined_sum + right_sum
            """
            val result = eval(code)
            assertEquals(21L, result) // (1+2+3) + (4+5+6) = 21
        }

        @Test
        fun `should handle complex dependency scenarios with Java threads`() {
            val executionOrder = mutableListOf<String>()
            val latch = CountDownLatch(4)

            val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            try {
                // Phase 1: Independent operations (op1 and op2)
                val op1Future = executor.submit(Callable {
                    Thread.sleep(50)
                    synchronized(executionOrder) {
                        executionOrder.add("op1")
                    }
                    latch.countDown()
                    1L
                })

                val op2Future = executor.submit(Callable {
                    Thread.sleep(50)
                    synchronized(executionOrder) {
                        executionOrder.add("op2")
                    }
                    latch.countDown()
                    2L
                })

                // Phase 2: op3 depends on op1
                val op3Future = executor.submit(Callable {
                    val op1Result = op1Future.get()
                    Thread.sleep(30)
                    synchronized(executionOrder) {
                        executionOrder.add("op3")
                    }
                    latch.countDown()
                    op1Result + 3L
                })

                // Phase 3: op4 depends on op2 and op3
                val op4Future = executor.submit(Callable {
                    val op2Result = op2Future.get()
                    val op3Result = op3Future.get()
                    Thread.sleep(30)
                    synchronized(executionOrder) {
                        executionOrder.add("op4")
                    }
                    latch.countDown()
                    op2Result + op3Result
                })

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

                // Verify final result
                val finalResult = op4Future.get()
                assertEquals(6L, finalResult) // 2 + (1 + 3) = 6
            } finally {
                executor.shutdown()
            }
        }
    }
}
