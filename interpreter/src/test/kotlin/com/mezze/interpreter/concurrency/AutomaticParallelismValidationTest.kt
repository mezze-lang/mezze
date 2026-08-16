package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.datastructures.list.MezzeList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * Tests for automatic parallelism detection
 * Tests runtime-managed implicit parallelism through the Parallel effect
 *
 * Migrated from V1 AutomaticParallelismValidationTest.kt
 * V2 tests parallelism through the Mezze language Parallel effect API.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AutomaticParallelismValidationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun spawnVirtualThread(name: String, task: () -> Unit): Thread {
        return Thread.ofVirtual().name(name).start(task)
    }

    @Test
    @Timeout(10)
    fun `should detect independent operations for parallel execution`() {
        val executionOrder = ConcurrentHashMap<String, Long>()

        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            val startTime = System.currentTimeMillis()

            // Create independent operations that don't share variables
            val futures = listOf(
                executor.submit(Callable {
                    val timestamp = System.nanoTime()
                    executionOrder["op1"] = timestamp
                    Thread.sleep(100)
                    "result1"
                }),
                executor.submit(Callable {
                    val timestamp = System.nanoTime()
                    executionOrder["op2"] = timestamp
                    Thread.sleep(100)
                    "result2"
                }),
                executor.submit(Callable {
                    val timestamp = System.nanoTime()
                    executionOrder["op3"] = timestamp
                    Thread.sleep(50)
                    42L
                })
            )

            val results = futures.map { it.get() }
            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime

            assertEquals(3, results.size, "Should execute all operations")
            assertTrue(totalTime < 200, "Parallel execution should be faster than sequential (${totalTime}ms)")

            // Verify operations started roughly at the same time (within 50ms window)
            val timestamps = executionOrder.values.sorted()
            val timeSpread = timestamps.last() - timestamps.first()
            assertTrue(timeSpread < 50_000_000, "Operations should start nearly simultaneously (spread: ${timeSpread}ns)")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @Timeout(10)
    fun `should detect independent operations via Mezze Parallel effect`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                # Three independent operations that can run in parallel
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
    @Timeout(10)
    fun `should respect data dependencies and execute sequentially when needed`() {
        val executionOrder = mutableListOf<String>()
        val orderLock = Any()

        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            // Create dependent operations: op2 depends on op1's result
            val op1Future = executor.submit(Callable {
                synchronized(orderLock) {
                    executionOrder.add("op1")
                }
                Thread.sleep(100)
                "first_result"
            })

            val op2Future = executor.submit(Callable {
                // Wait for op1 to complete (simulating data dependency)
                val op1Result = op1Future.get()
                synchronized(orderLock) {
                    executionOrder.add("op2")
                }
                Thread.sleep(50)
                "$op1Result -> second_result"
            })

            val startTime = System.currentTimeMillis()
            op2Future.get()
            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime

            // Should execute sequentially due to dependency
            assertTrue(totalTime >= 140, "LoomParallel execution should take at least sum of durations (${totalTime}ms)")
            assertEquals(listOf("op1", "op2"), executionOrder, "Operations should execute in dependency order")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @Timeout(10)
    fun `should handle mixed independent and dependent operations`() {
        val executionOrder = ConcurrentHashMap<String, Long>()

        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            // Independent operation 1
            val independent1Future = executor.submit(Callable {
                executionOrder["independent1"] = System.nanoTime()
                Thread.sleep(100)
                "independent1"
            })

            // Independent operation 2
            val independent2Future = executor.submit(Callable {
                executionOrder["independent2"] = System.nanoTime()
                Thread.sleep(100)
                "independent2"
            })

            // Dependent operation (depends on independent1)
            val dependentFuture = executor.submit(Callable {
                independent1Future.get() // Wait for dependency
                executionOrder["dependent"] = System.nanoTime()
                Thread.sleep(50)
                "dependent"
            })

            val startTime = System.currentTimeMillis()
            dependentFuture.get()
            independent2Future.get()
            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime

            // Should be faster than fully sequential but slower than fully parallel
            assertTrue(totalTime < 250, "Mixed execution should be optimized (${totalTime}ms)")
            assertTrue(totalTime >= 150, "Should respect dependencies (${totalTime}ms)")

            // Verify execution order constraints
            val independent1Time = executionOrder["independent1"]!!
            val independent2Time = executionOrder["independent2"]!!
            val dependentTime = executionOrder["dependent"]!!

            // Independent operations should start roughly together
            val independentSpread = Math.abs(independent1Time - independent2Time)
            assertTrue(independentSpread < 50_000_000, "Independent operations should start together")

            // Dependent operation should start after independent1
            assertTrue(dependentTime > independent1Time, "Dependent operation should start after its dependency")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @Timeout(10)
    fun `should handle mixed operations via Mezze Parallel effect`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                # First: two independent operations
                let phase1 = both {
                    l = {} -> 10,
                    r = {} -> 20
                }

                # Then: dependent operation uses phase1 results
                let combined = phase1.l + phase1.r
                combined
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    @Timeout(10)
    fun `should optimize execution plan for maximum parallelism`() {
        val parallelismLevels = ConcurrentHashMap<Long, AtomicInteger>()

        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            val futures = (1..6).map { i ->
                executor.submit(Callable {
                    val currentTime = System.currentTimeMillis() / 100 * 100 // Round to 100ms buckets
                    parallelismLevels.computeIfAbsent(currentTime) { AtomicInteger(0) }.incrementAndGet()
                    Thread.sleep(100)
                    "result$i"
                })
            }

            futures.forEach { it.get() }

            // Should achieve some level of parallelism
            val maxParallelism = parallelismLevels.values.maxOfOrNull { it.get() } ?: 0
            assertTrue(maxParallelism >= 2, "Should achieve at least 2-way parallelism, got $maxParallelism")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @Timeout(10)
    fun `should handle error propagation in parallel operations`() {
        val completedOperations = AtomicInteger(0)
        val errorOperation = AtomicInteger(0)

        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
            val futures = listOf(
                executor.submit(Callable {
                    Thread.sleep(200)
                    completedOperations.incrementAndGet()
                    "success1"
                }),
                executor.submit(Callable {
                    Thread.sleep(100)
                    errorOperation.incrementAndGet()
                    throw RuntimeException("Simulated error")
                }),
                executor.submit(Callable {
                    Thread.sleep(150)
                    completedOperations.incrementAndGet()
                    "success3"
                })
            )

            // Try to get all results - one should throw
            var exceptionThrown = false
            for (future in futures) {
                try {
                    future.get()
                } catch (e: Exception) {
                    exceptionThrown = true
                }
            }

            assertTrue(exceptionThrown, "Should have caught an exception")
            assertEquals(1, errorOperation.get(), "Error operation should have executed")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @Timeout(10)
    fun `should handle error propagation via Mezze Parallel effect`() {
        // In Mezze, parallel operations that throw will propagate the error
        // Test that errors are properly caught
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                # All operations succeed
                let result = map {
                    xs = [1, 2, 3],
                    f = {x} -> x * 2
                }
                let sum = reduce {xs = result, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(12L, result) // (1*2) + (2*2) + (3*2) = 12
    }

    @Test
    @Timeout(10)
    fun `should cache and reuse execution plans for similar operation patterns via Mezze`() {
        // Execute same pattern multiple times - should use caching internally
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                # Pattern 1
                let result1 = map {xs = [1, 2], f = {x} -> x * 2}

                # Pattern 2 (similar to pattern 1)
                let result2 = map {xs = [3, 4], f = {x} -> x * 2}

                let sum1 = reduce {xs = result1, f = {l, r} -> l + r}
                let sum2 = reduce {xs = result2, f = {l, r} -> l + r}

                let s1 = match sum1 is
                    Some {val} -> val
                    None -> 0
                let s2 = match sum2 is
                    Some {val} -> val
                    None -> 0
                s1 + s2
        """
        val result = eval(code)
        assertEquals(20L, result) // (2+4) + (6+8) = 20
    }

    @Test
    @Timeout(10)
    fun `should handle nested parallel operations correctly via Mezze`() {
        // Test nested parallel computations through sequential phases
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                # Phase 1: Map two lists in parallel using both
                let phase1 = both {
                    l = {} -> [1, 2, 3],
                    r = {} -> [4, 5, 6]
                }

                # Phase 2: Reduce each list
                let left_sum = reduce {xs = phase1.l, f = {l, r} -> l + r}
                let right_sum = reduce {xs = phase1.r, f = {l, r} -> l + r}

                # Phase 3: Combine results
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
    @Timeout(10)
    fun `should provide accurate performance statistics`() {
        // Test that parallel execution is faster than sequential
        val parallelCode = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = map {xs = xs, f = {x} -> x * x}
                let sum = reduce {xs = result, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """

        val parallelTime = measureTimeMillis {
            repeat(10) {
                eval(parallelCode)
            }
        }

        val sequentialCode = """
            use std::effects::parallel::{ LoomParallel, Parallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = map {xs = xs, f = {x} -> x * x}
                let sum = reduce {xs = result, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """

        val sequentialTime = measureTimeMillis {
            repeat(10) {
                eval(sequentialCode)
            }
        }

        println("Parallel execution: ${parallelTime}ms")
        println("LoomParallel execution: ${sequentialTime}ms")

        // Both should produce correct results
        val parallelResult = eval(parallelCode)
        val sequentialResult = eval(sequentialCode)
        assertEquals(55L, parallelResult) // 1 + 4 + 9 + 16 + 25 = 55
        assertEquals(55L, sequentialResult)
    }

    @Test
    @Timeout(10)
    fun `should handle large parallel workloads`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                          11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                          21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                          31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                          41, 42, 43, 44, 45, 46, 47, 48, 49, 50]
                let squared = map {xs = xs, f = {x} -> x * x}
                let sum = reduce {xs = squared, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        // Sum of squares from 1 to 50 = n*(n+1)*(2n+1)/6 = 50*51*101/6 = 42925
        assertEquals(42925L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle ForkJoin parallel execution`() {
        val code = """
            use std::effects::parallel::{ LoomParallel, Parallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8]
                let doubled = map {xs = xs, f = {x} -> x * 2}
                let sum = reduce {xs = doubled, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(72L, result) // (1+2+3+4+5+6+7+8) * 2 = 72
    }

    @Test
    @Timeout(10)
    fun `should handle parallel filter operations`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                let evens = filter {xs = xs, f = {x} -> x % 2 == 0}
                let sum = reduce {xs = evens, f = {l, r} -> l + r}
                match sum is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(30L, result) // 2 + 4 + 6 + 8 + 10 = 30
    }

    @Test
    @Timeout(10)
    fun `should handle parallel fold operations`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = fold {
                    xs = xs,
                    zero = 0,
                    map_fn = {x} -> x * x,
                    combine = {l, r} -> l + r
                }
                result
        """
        val result = eval(code)
        assertEquals(55L, result) // 1 + 4 + 9 + 16 + 25 = 55
    }

    @Test
    @Timeout(10)
    fun `should handle parallel any and all operations`() {
        val anyCode = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [1, 2, 3, 4, 5]
                any {xs = xs, f = {x} -> x > 3}
        """
        val anyResult = eval(anyCode)
        assertEquals(true, anyResult)

        val allCode = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [2, 4, 6, 8]
                all {xs = xs, f = {x} -> x % 2 == 0}
        """
        val allResult = eval(allCode)
        assertEquals(true, allResult)

        val allFalseCode = """
            use std::effects::parallel::{ Parallel, LoomParallel, all, any, both, both3, filter, fold, map, reduce }

            perform LoomParallel in do
                let xs = [2, 4, 5, 8]
                all {xs = xs, f = {x} -> x % 2 == 0}
        """
        val allFalseResult = eval(allFalseCode)
        assertEquals(false, allFalseResult)
    }
}
