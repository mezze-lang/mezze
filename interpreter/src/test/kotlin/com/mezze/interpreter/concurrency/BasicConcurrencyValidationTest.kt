package com.mezze.interpreter.concurrency

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
 * Basic concurrency validation tests for V2
 * Tests virtual thread spawning, structured concurrency, and performance benchmarks
 *
 * Migrated from V1 BasicConcurrencyValidationTest.kt
 * V2 uses Java's virtual threads directly rather than a LoomConcurrencyManager wrapper.
 */
class BasicConcurrencyValidationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun spawnVirtualThread(name: String, task: () -> Unit): Thread {
        return Thread.ofVirtual().name(name).start(task)
    }

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

        println("Virtual thread spawning test passed - spawned ${threads.size} virtual threads")
    }

    @Test
    fun `should track thread names properly`() {
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

        println("Thread name tracking test passed - managed ${threadNames.size} threads")
    }

    @Test
    fun `should execute structured concurrency scope successfully`() {
        val results = mutableListOf<Int>()
        val latch = CountDownLatch(5)

        val scopeResults = mutableListOf<Int>()

        // JDK 25 restructured StructuredTaskScope: the ShutdownOn* subclasses
        // were replaced with a Joiner-based `open()` factory. Default open()
        // (no joiner) shuts down on the first failure, waits for the rest,
        // and rethrows on join(). Subtask.get() reads a completed value.
        // #254.
        java.util.concurrent.StructuredTaskScope.open<Int>().use { scope ->
            val subtasks = (1..5).map { i ->
                scope.fork(Callable {
                    Thread.sleep(50)
                    synchronized(results) { results.add(i) }
                    latch.countDown()
                    i * 2
                })
            }
            scope.join()
            subtasks.forEach { st -> scopeResults.add(st.get()) }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(2, 4, 6, 8, 10), scopeResults.sorted())
        assertEquals(5, results.size)

        println("Structured concurrency test passed - executed ${scopeResults.size} tasks in scope")
    }

    @Test
    fun `should track thread statistics accurately`() {
        val createdThreads = AtomicInteger(0)
        val completedThreads = AtomicInteger(0)
        val latch = CountDownLatch(10)

        // Spawn 10 threads
        repeat(10) { i ->
            createdThreads.incrementAndGet()
            spawnVirtualThread("stats-test-$i") {
                Thread.sleep(100)
                completedThreads.incrementAndGet()
                latch.countDown()
            }
        }

        // Check statistics during execution
        assertTrue(createdThreads.get() >= 10)

        // Wait for completion
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(10, completedThreads.get())

        println("Thread statistics test passed - tracked ${completedThreads.get()} total threads")
    }

    @Test
    fun `should demonstrate parallel vs sequential performance`() {
        val taskCount = 10
        val taskDuration = 50L // milliseconds

        // Measure parallel execution using virtual threads
        val parallelTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)

            repeat(taskCount) { i ->
                spawnVirtualThread("parallel-task-$i") {
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

        println("Performance benchmark test passed - achieved ${speedup}x speedup")
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
                    spawnVirtualThread("overhead-test-$i") {
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

        println("Virtual thread overhead test passed - ${overheadPerThread}ms per thread")
    }

    @Test
    fun `should scale with increasing parallelism`() {
        val results = mutableMapOf<Int, Long>()

        // Test with different numbers of parallel tasks
        listOf(1, 2, 4, 8).forEach { taskCount ->
            val executionTime = measureTimeMillis {
                val latch = CountDownLatch(taskCount)

                repeat(taskCount) { i ->
                    spawnVirtualThread("scale-test-$i") {
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

        println("Scalability test passed - good scaling characteristics")
    }

    @Test
    fun `should handle high concurrency loads`() {
        val taskCount = 100
        val completedTasks = AtomicInteger(0)
        val errors = AtomicInteger(0)

        val executionTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)

            repeat(taskCount) { i ->
                spawnVirtualThread("load-test-$i") {
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

        println("High concurrency load test passed - handled $taskCount concurrent tasks")
    }

    @Test
    fun `should compare virtual threads with traditional thread pool`() {
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

        println("Virtual thread vs thread pool comparison passed")
    }

    @Test
    fun `should integrate all concurrency components successfully`() {
        val results = ConcurrentHashMap<String, Any>()
        val latch = CountDownLatch(3)

        // Test integration of all components using virtual thread executor
        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            // Task 1: Independent operation
            executor.submit {
                Thread.sleep(100)
                val result = "Data from IO"
                results["task1"] = result
                latch.countDown()
            }

            // Task 2: Another independent operation
            executor.submit {
                Thread.sleep(80)
                val result = 42L
                results["task2"] = result
                latch.countDown()
            }

            // Task 3: Dependent operation (simulated)
            executor.submit {
                Thread.sleep(200) // Wait for others to complete
                val result = true
                results["task3"] = result
                latch.countDown()
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }

        assertEquals(3, results.size)

        assertTrue(results["task1"] is String)
        assertTrue(results["task2"] is Long)
        assertTrue(results["task3"] is Boolean)

        println("Integration test passed - all concurrency components working together")
    }
}
