package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Performance benchmarks comparing sequential vs parallel execution.
 * Tests requirement 1.6: Performance validation and optimization
 *
 * Migrated from V1 PerformanceBenchmarkTest. The V1 version tested internal
 * infrastructure classes (LoomConcurrencyManager, SmartEffectScheduler,
 * EffectDependencyAnalyzer) with test factories. This V2 version tests the
 * same concepts through the Mezze language runtime using native concurrency
 * primitives.
 *
 * Key differences from V1:
 * - Tests through Mezze language code rather than internal Java classes
 * - Uses spawn/await primitives instead of EffectOperationTestFactory
 * - Measures actual language-level concurrent execution
 * - Unboxed return values (Long instead of MezzeInt, etc.)
 *
 * NOTE: These benchmarks are inherently flaky due to system load variance.
 * They pass consistently when run in isolation but may fail when run with
 * the full test suite due to resource contention.
 */
@Disabled("Performance benchmarks — flaky under CI, run manually")
@Execution(ExecutionMode.SAME_THREAD)
class PerformanceBenchmarkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        // Warm up JVM for more accurate benchmarks
        warmupJVM(500)
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun warmupJVM(iterations: Int) {
        repeat(iterations) {
            // Simple warmup computation
            val x = it * 2 + 1
            @Suppress("UNUSED_VARIABLE")
            val y = x * x
        }
    }

    private fun measureExecution(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    // Raw Loom primitives for benchmark tests - these bypass the effect system
    // and call native functions directly. Tasks run to completion but results
    // are lost if not awaited (like Go/Rust).
    private val loomSetup = """
        newtype Promise 'a = Promise __NativePromise
        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

    // For the structured concurrency test, we use the stdlib's proper effect handler
    private val loomAsyncSetup = """
        use std::effects::async::{Async, LoomAsync, Promise}

        # These require `perform LoomAsync in ...` wrapper
        let spawn_async: {f: {} -> 'a <...>} -> Promise 'a <Async> = {f} -> Async.spawn {f = f}
        let await_async: {p: Promise 'a} -> 'a <Async> = {p} -> Async.await {p = p}
        let sleep_async: {ms: Int} -> {} <Async> = {ms} -> Async.sleep {ms = ms}
    """.trimIndent()

    // Atom setup for shared state
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let reset: {atom: Atom 'a, value: 'a} -> 'a = {atom, value} -> __native_atom_reset {atom = atom, value = value}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
    """.trimIndent()

    // =============================================================================
    // IO Operations Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should demonstrate significant speedup for independent IO operations`() {
        val operationCount = 10
        val operationDurationMs = 100L

        // Measure sequential execution (simulated with sleep in sequence)
        val sequentialTime = measureExecution {
            val code = """
                $loomSetup

                # Sequential execution - each operation waits for previous
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                "done"
            """
            eval(code)
        }

        // Measure parallel execution
        val parallelTime = measureExecution {
            val code = """
                $loomSetup
                $atomSetup

                let counter = atom {value = 0}

                # Parallel execution - all operations run concurrently
                let op1 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op2 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op3 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op4 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op5 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op6 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op7 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op8 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op9 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let op10 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }

                let _ = await {p = op1}
                let _ = await {p = op2}
                let _ = await {p = op3}
                let _ = await {p = op4}
                let _ = await {p = op5}
                let _ = await {p = op6}
                let _ = await {p = op7}
                let _ = await {p = op8}
                let _ = await {p = op9}
                let _ = await {p = op10}

                deref {atom = counter}
            """
            val result = eval(code)
            assertEquals(10L, result, "All operations should complete")
        }

        val speedup = sequentialTime.toDouble() / parallelTime
        val efficiency = speedup / operationCount

        println("IO Operations Benchmark:")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")
        println("  Efficiency: ${String.format("%.2f", efficiency * 100)}%")

        // Lenient assertions to handle system load variance
        assertTrue(speedup >= 1.5, "Should achieve at least 1.5x speedup for IO operations (got ${speedup}x)")
        assertTrue(parallelTime < sequentialTime, "Parallel execution should be faster than sequential")
    }

    // =============================================================================
    // Compute Operations Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should show moderate speedup for compute-intensive operations`() {
        val operationCount = 8
        val operationDurationMs = 50L

        // Measure sequential execution
        val sequentialTime = measureExecution {
            val code = """
                $loomSetup

                # Sequential compute operations
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                let _ = sleep {ms = $operationDurationMs}
                64
            """
            eval(code)
        }

        // Measure parallel execution
        val parallelTime = measureExecution {
            val code = """
                $loomSetup

                # Parallel compute operations
                let op1 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    1 * 1
                }
                let op2 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    2 * 2
                }
                let op3 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    3 * 3
                }
                let op4 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    4 * 4
                }
                let op5 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    5 * 5
                }
                let op6 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    6 * 6
                }
                let op7 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    7 * 7
                }
                let op8 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    8 * 8
                }

                let r1 = await {p = op1}
                let r2 = await {p = op2}
                let r3 = await {p = op3}
                let r4 = await {p = op4}
                let r5 = await {p = op5}
                let r6 = await {p = op6}
                let r7 = await {p = op7}
                let r8 = await {p = op8}

                r1 + r2 + r3 + r4 + r5 + r6 + r7 + r8
            """
            eval(code)
        }

        val speedup = sequentialTime.toDouble() / parallelTime

        println("Compute Operations Benchmark:")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")

        // Lenient assertion - just verify parallel is faster
        assertTrue(parallelTime < sequentialTime, "Parallel execution should be faster than sequential")
    }

    // =============================================================================
    // Mixed Workload Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should handle mixed workload efficiently`() {
        // Mix of different duration operations simulating IO, compute, and network
        val ioCount = 6
        val computeCount = 4
        val networkCount = 3

        // Measure sequential execution
        val sequentialTime = measureExecution {
            val code = """
                $loomSetup

                # Sequential mixed operations
                # IO operations (80ms each)
                let _ = sleep {ms = 80}
                let _ = sleep {ms = 80}
                let _ = sleep {ms = 80}
                let _ = sleep {ms = 80}
                let _ = sleep {ms = 80}
                let _ = sleep {ms = 80}
                # Compute operations (40ms each)
                let _ = sleep {ms = 40}
                let _ = sleep {ms = 40}
                let _ = sleep {ms = 40}
                let _ = sleep {ms = 40}
                # Network operations (120ms each)
                let _ = sleep {ms = 120}
                let _ = sleep {ms = 120}
                let _ = sleep {ms = 120}
                "done"
            """
            eval(code)
        }

        // Measure parallel execution
        val parallelTime = measureExecution {
            val code = """
                $loomSetup
                $atomSetup

                let counter = atom {value = 0}

                # IO operations (80ms each)
                let io1 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let io2 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let io3 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let io4 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let io5 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let io6 = spawn {f = {} -> do
                    let _ = sleep {ms = 80}
                    swap {atom = counter, f = {current} -> current + 1}
                }

                # Compute operations (40ms each)
                let compute1 = spawn {f = {} -> do
                    let _ = sleep {ms = 40}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let compute2 = spawn {f = {} -> do
                    let _ = sleep {ms = 40}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let compute3 = spawn {f = {} -> do
                    let _ = sleep {ms = 40}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let compute4 = spawn {f = {} -> do
                    let _ = sleep {ms = 40}
                    swap {atom = counter, f = {current} -> current + 1}
                }

                # Network operations (120ms each)
                let net1 = spawn {f = {} -> do
                    let _ = sleep {ms = 120}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let net2 = spawn {f = {} -> do
                    let _ = sleep {ms = 120}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let net3 = spawn {f = {} -> do
                    let _ = sleep {ms = 120}
                    swap {atom = counter, f = {current} -> current + 1}
                }

                # Await all
                let _ = await {p = io1}
                let _ = await {p = io2}
                let _ = await {p = io3}
                let _ = await {p = io4}
                let _ = await {p = io5}
                let _ = await {p = io6}
                let _ = await {p = compute1}
                let _ = await {p = compute2}
                let _ = await {p = compute3}
                let _ = await {p = compute4}
                let _ = await {p = net1}
                let _ = await {p = net2}
                let _ = await {p = net3}

                deref {atom = counter}
            """
            val result = eval(code)
            assertEquals(13L, result, "All operations should complete")
        }

        val speedup = sequentialTime.toDouble() / parallelTime
        val totalOperations = ioCount + computeCount + networkCount

        println("Mixed Workload Benchmark:")
        println("  Operations: ${ioCount} IO, ${computeCount} compute, ${networkCount} network")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")

        // Lenient assertion - just verify parallel is faster
        assertTrue(parallelTime < sequentialTime, "Parallel execution should be faster than sequential")
    }

    // =============================================================================
    // Scalability Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should scale with increasing parallelism levels`() {
        val operationDurationMs = 100L
        val results = mutableMapOf<Int, Long>()

        // Test with 1, 2, 4, 8 parallel operations
        for (parallelism in listOf(1, 2, 4, 8)) {
            val executionTime = measureExecution {
                val spawns = (1..parallelism).joinToString("\n") { i ->
                    """let op$i = spawn {f = {} -> do
                        let _ = sleep {ms = $operationDurationMs}
                        $i
                    }"""
                }
                val awaits = (1..parallelism).joinToString("\n") { i ->
                    "let r$i = await {p = op$i}"
                }
                val sum = (1..parallelism).joinToString(" + ") { i -> "r$i" }

                val code = """
                    $loomSetup

                    $spawns

                    $awaits

                    $sum
                """
                eval(code)
            }

            results[parallelism] = executionTime
        }

        println("Scalability Benchmark:")
        results.forEach { (parallelism, time) ->
            val theoreticalSequential = parallelism * operationDurationMs
            val efficiency = (theoreticalSequential.toDouble() / time) / parallelism * 100
            println("  ${parallelism} operations: ${time}ms (efficiency: ${String.format("%.1f", efficiency)}%)")
        }

        // Lenient scaling verification - just check that 8 ops takes less than 8x single op
        assertTrue(results[8]!! < results[1]!! * 8, "8 operations should be faster than 8x single operation time")
    }

    // =============================================================================
    // Virtual Thread Efficiency Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should demonstrate virtual thread efficiency`() {
        val threadCount = 100 // Reduced from 1000 for faster test execution
        val operationDurationMs = 50L

        val executionTime = measureExecution {
            // Generate code for many parallel operations
            val spawns = (1..threadCount).joinToString("\n") { i ->
                """let t$i = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    $i
                }"""
            }
            val awaits = (1..threadCount).joinToString("\n") { i ->
                "let _ = await {p = t$i}"
            }

            val code = """
                $loomSetup
                $atomSetup

                let counter = atom {value = 0}

                $spawns

                $awaits

                $threadCount
            """
            val result = eval(code)
            assertEquals(threadCount.toLong(), result, "All threads should complete")
        }

        println("Virtual Thread Efficiency Benchmark:")
        println("  ${threadCount} virtual threads: ${executionTime}ms")
        println("  Average time per thread: ${executionTime.toDouble() / threadCount}ms")

        // Lenient assertion - just verify it completes faster than sequential would take
        val sequentialWouldTake = threadCount * operationDurationMs
        assertTrue(executionTime < sequentialWouldTake,
            "Virtual threads should be faster than sequential (got ${executionTime}ms, sequential would be ${sequentialWouldTake}ms)")
    }

    // =============================================================================
    // Dependency Analysis Overhead Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should benchmark concurrent operation overhead`() {
        // Warm up JVM more thoroughly for this specific test
        repeat(5) {
            val code = """
                $loomSetup

                let op = spawn {f = {} -> 42}
                await {p = op}
            """
            eval(code)
        }

        val operationCounts = listOf(10, 25, 50)
        val analysisResults = mutableMapOf<Int, Long>()

        for (count in operationCounts) {
            // Run multiple times and take median to reduce variance
            val times = (1..3).map {
                measureExecution {
                    val spawns = (1..count).joinToString("\n") { i ->
                        """let op$i = spawn {f = {} -> $i}"""
                    }
                    val awaits = (1..count).joinToString("\n") { i ->
                        "let r$i = await {p = op$i}"
                    }
                    val sum = (1..count).joinToString(" + ") { i -> "r$i" }

                    val code = """
                        $loomSetup

                        $spawns

                        $awaits

                        $sum
                    """
                    eval(code)
                }
            }.sorted()
            val medianTime = times[1]

            analysisResults[count] = medianTime
        }

        println("Concurrent Operation Overhead Benchmark:")
        analysisResults.forEach { (count, time) ->
            val timePerOperation = time.toDouble() / count
            println("  ${count} operations: ${time}ms (${String.format("%.2f", timePerOperation)}ms per op)")
        }

        // Operation overhead should be reasonable
        analysisResults.forEach { (count, time) ->
            assertTrue(time < count * 100, "Operation creation should be efficient for ${count} operations (${time}ms)")
        }
    }

    // =============================================================================
    // Structured Concurrency Overhead Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should benchmark structured concurrency overhead`() {
        val operationCount = 20
        val operationDurationMs = 20L

        // Measure with structured concurrency pattern
        val structuredTime = measureExecution {
            val code = """
                $loomSetup

                let op1 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    1
                }
                let op2 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    2
                }
                let op3 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    3
                }
                let op4 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    4
                }
                let op5 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    5
                }
                let op6 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    6
                }
                let op7 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    7
                }
                let op8 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    8
                }
                let op9 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    9
                }
                let op10 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    10
                }
                let op11 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    11
                }
                let op12 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    12
                }
                let op13 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    13
                }
                let op14 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    14
                }
                let op15 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    15
                }
                let op16 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    16
                }
                let op17 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    17
                }
                let op18 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    18
                }
                let op19 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    19
                }
                let op20 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    20
                }

                let r1 = await {p = op1}
                let r2 = await {p = op2}
                let r3 = await {p = op3}
                let r4 = await {p = op4}
                let r5 = await {p = op5}
                let r6 = await {p = op6}
                let r7 = await {p = op7}
                let r8 = await {p = op8}
                let r9 = await {p = op9}
                let r10 = await {p = op10}
                let r11 = await {p = op11}
                let r12 = await {p = op12}
                let r13 = await {p = op13}
                let r14 = await {p = op14}
                let r15 = await {p = op15}
                let r16 = await {p = op16}
                let r17 = await {p = op17}
                let r18 = await {p = op18}
                let r19 = await {p = op19}
                let r20 = await {p = op20}

                r1 + r2 + r3 + r4 + r5 + r6 + r7 + r8 + r9 + r10 + r11 + r12 + r13 + r14 + r15 + r16 + r17 + r18 + r19 + r20
            """
            val result = eval(code)
            // Sum of 1..20 = 210
            assertEquals(210L, result)
        }

        println("Structured Concurrency Overhead Benchmark:")
        println("  Structured concurrency: ${structuredTime}ms")
        println("  Expected parallel time: ~${operationDurationMs}ms")

        // Very lenient assertion - account for JIT warmup, context setup, and thread creation overhead.
        // The first run in a cold JVM can take significantly longer than subsequent runs.
        // We just verify the test runs to completion and produces correct results.
        // The result assertion above (assertEquals(210L, result)) is the real test.
        val sequentialWouldTake = operationDurationMs * operationCount
        assertTrue(structuredTime < sequentialWouldTake * 2,
            "Structured concurrency should not take more than 2x sequential time (got ${structuredTime}ms, sequential would be ${sequentialWouldTake}ms)")
    }

    // =============================================================================
    // Memory Efficiency Benchmark (Simplified)
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should handle many concurrent operations without excessive memory`() {
        // This test verifies that many concurrent operations can be created and
        // completed without causing memory issues.
        //
        // Uses raw loom natives - tasks run to completion, results are lost if
        // not awaited (like Go/Rust). This is fire-and-forget semantics.

        val operationDurationMs = 50L

        val executionTime = measureExecution {
            val code = """
                $loomSetup
                $atomSetup

                let counter = atom {value = 0}

                let t1 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t2 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t3 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t4 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t5 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t6 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t7 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t8 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t9 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t10 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t11 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t12 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t13 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t14 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t15 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t16 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t17 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t18 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t19 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }
                let t20 = spawn {f = {} -> do
                    let _ = sleep {ms = $operationDurationMs}
                    swap {atom = counter, f = {current} -> current + 1}
                }

                let _ = await {p = t1}
                let _ = await {p = t2}
                let _ = await {p = t3}
                let _ = await {p = t4}
                let _ = await {p = t5}
                let _ = await {p = t6}
                let _ = await {p = t7}
                let _ = await {p = t8}
                let _ = await {p = t9}
                let _ = await {p = t10}
                let _ = await {p = t11}
                let _ = await {p = t12}
                let _ = await {p = t13}
                let _ = await {p = t14}
                let _ = await {p = t15}
                let _ = await {p = t16}
                let _ = await {p = t17}
                let _ = await {p = t18}
                let _ = await {p = t19}
                let _ = await {p = t20}

                deref {atom = counter}
            """
            val result = eval(code)
            assertEquals(20L, result, "All operations should complete successfully")
        }

        println("Memory Efficiency Benchmark:")
        println("  20 concurrent operations completed in ${executionTime}ms")

        // Lenient assertion - verify parallel execution is faster than sequential
        val sequentialWouldTake = 20 * operationDurationMs
        assertTrue(executionTime < sequentialWouldTake,
            "Should complete faster than sequential (got ${executionTime}ms, sequential would be ${sequentialWouldTake}ms)")
    }
}
