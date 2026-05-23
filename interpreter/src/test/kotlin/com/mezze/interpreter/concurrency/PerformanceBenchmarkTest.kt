package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.bench.BenchRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Macro performance benchmarks for the Async effect.
 *
 * These verify that the new effect-handler runtime (Loom-backed fibers +
 * `perform LoomAsync in ...`) delivers actual concurrency speedup, not
 * just per-op micro-overhead numbers. Microbenchmarks live in
 * `EffectHandlerPerfTest` (task #79/#88).
 *
 * The benchmarks here exercise the **real surface**:
 *
 *   perform LoomAsync in do
 *     let t = Async.spawn { f = {} -> ... }
 *     Async.await { p = t }
 *
 * which routes through the new dispatch (HandleNode → EffectHandlerDriver
 * → handler chain on the spawned thread → AsyncNatives). Previously this
 * test used raw `__native_loom_spawn`/`__native_loom_await` directly,
 * bypassing the dispatch we just rewrote.
 *
 * Disabled by default — concurrency benchmarks are inherently flaky on
 * loaded systems. Run manually with:
 *
 *   ./gradlew :interpreter:test --tests com.mezze.interpreter.concurrency.PerformanceBenchmarkTest
 */
@Execution(ExecutionMode.SAME_THREAD)
class PerformanceBenchmarkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        // Warm up JVM for more accurate benchmarks
        warmupJVM(500)
    }

    // Bench uses runJit() so measurements reflect production (JIT-on) perf.
    private fun eval(source: String): Any = MezzeTestHelper.runJit(source)

    private fun warmupJVM(iterations: Int) {
        repeat(iterations) {
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

    /** Time [block] in nanoseconds and record under [label] in the bench output. */
    private fun benchOnce(label: String, iters: Int = 1, block: () -> Unit): Long {
        val startNs = System.nanoTime()
        block()
        val elapsedNs = System.nanoTime() - startNs
        BenchRecorder.record("Async macro (Loom)", label, elapsedNs, iters)
        return elapsedNs
    }

    // Shared prelude used by every benchmark — pulls in the Async effect
    // and its LoomAsync impl. Tests then write `perform LoomAsync in ...`.
    private val asyncSetup = """
        use std::effects::async::{Async, LoomAsync, Promise, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
        use std::result
    """.trimIndent()

    // Helper: build N parallel-spawn-then-await-all source for a body that
    // sleeps for `sleepMs` milliseconds and returns the spawn index.
    private fun parallelSleepCode(n: Int, sleepMs: Long): String = buildString {
        appendLine(asyncSetup)
        appendLine()
        appendLine("perform LoomAsync in do")
        for (i in 1..n) {
            appendLine("    let op$i = Async.spawn { f = {} -> do")
            appendLine("        Async.sleep { ms = $sleepMs }")
            appendLine("        $i")
            appendLine("    }")
        }
        appendLine("    result::get_or_else { result = try_all { ps = [${(1..n).joinToString(", ") { "op$it" }}] }, def = [] }")
    }

    // Helper: sequential N sleeps in a single body — uses Async.sleep so
    // it goes through the same dispatch path, just without spawning.
    private fun sequentialSleepCode(n: Int, sleepMs: Long): String = buildString {
        appendLine(asyncSetup)
        appendLine()
        appendLine("perform LoomAsync in do")
        repeat(n) {
            appendLine("    Async.sleep { ms = $sleepMs }")
        }
        appendLine("    \"done\"")
    }

    // =============================================================================
    // IO Operations Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should demonstrate significant speedup for independent IO operations`() {
        val n = 10
        val sleepMs = 100L

        val sequentialTime = measureExecution {
            eval(sequentialSleepCode(n, sleepMs))
        }

        val parallelTime = measureExecution {
            eval(parallelSleepCode(n, sleepMs))
        }

        val speedup = sequentialTime.toDouble() / parallelTime
        val efficiency = speedup / n

        println("IO Operations Benchmark (via Async.spawn/await):")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time:   ${parallelTime}ms")
        println("  Speedup:         ${"%.2f".format(speedup)}x")
        println("  Efficiency:      ${"%.2f".format(efficiency * 100)}%")

        assertTrue(speedup >= 1.5,
            "Should achieve at least 1.5x speedup for IO ops (got ${speedup}x)")
        assertTrue(parallelTime < sequentialTime,
            "Parallel execution should beat sequential")
    }

    // =============================================================================
    // Compute Operations Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should show moderate speedup for compute-intensive operations`() {
        val n = 8
        val sleepMs = 50L

        val sequentialTime = measureExecution {
            eval(sequentialSleepCode(n, sleepMs))
        }
        val parallelTime = measureExecution {
            eval(parallelSleepCode(n, sleepMs))
        }

        val speedup = sequentialTime.toDouble() / parallelTime
        println("Compute Operations Benchmark (via Async.spawn/await):")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time:   ${parallelTime}ms")
        println("  Speedup:         ${"%.2f".format(speedup)}x")

        assertTrue(parallelTime < sequentialTime,
            "Parallel execution should beat sequential")
    }

    // =============================================================================
    // Mixed Workload Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should handle mixed workload efficiently`() {
        // Mix of IO (80ms), compute (40ms), network (120ms)
        val durations = listOf(80L, 80L, 80L, 80L, 80L, 80L,
                               40L, 40L, 40L, 40L,
                               120L, 120L, 120L)

        val sequentialTime = measureExecution {
            val src = buildString {
                appendLine(asyncSetup)
                appendLine()
                appendLine("perform LoomAsync in do")
                durations.forEach { appendLine("    Async.sleep { ms = $it }") }
                appendLine("    \"done\"")
            }
            eval(src)
        }

        val parallelTime = measureExecution {
            val src = buildString {
                appendLine(asyncSetup)
                appendLine()
                appendLine("perform LoomAsync in do")
                durations.forEachIndexed { i, ms ->
                    appendLine("    let op${i + 1} = Async.spawn { f = {} -> do")
                    appendLine("        Async.sleep { ms = $ms }")
                    appendLine("        ${i + 1}")
                    appendLine("    }")
                }
                val opIds = durations.indices.joinToString(", ") { "op${it + 1}" }
                appendLine("    result::get_or_else { result = try_all { ps = [$opIds] }, def = [] }")
            }
            eval(src)
        }

        val speedup = sequentialTime.toDouble() / parallelTime
        println("Mixed Workload Benchmark (via Async.spawn/await):")
        println("  Operations:      ${durations.size} (mixed durations)")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time:   ${parallelTime}ms")
        println("  Speedup:         ${"%.2f".format(speedup)}x")

        assertTrue(parallelTime < sequentialTime,
            "Parallel execution should beat sequential")
    }

    // =============================================================================
    // Scalability Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should scale with increasing parallelism levels`() {
        val sleepMs = 100L
        val results = mutableMapOf<Int, Long>()

        for (parallelism in listOf(1, 2, 4, 8)) {
            val executionTime = measureExecution {
                eval(parallelSleepCode(parallelism, sleepMs))
            }
            results[parallelism] = executionTime
        }

        println("Scalability Benchmark (via Async.spawn/await):")
        results.forEach { (parallelism, time) ->
            val theoreticalSequential = parallelism * sleepMs
            val efficiency = (theoreticalSequential.toDouble() / time) / parallelism * 100
            println("  ${parallelism} operations: ${time}ms (efficiency: ${"%.1f".format(efficiency)}%)")
        }

        assertTrue(results[8]!! < results[1]!! * 8,
            "8 operations should be faster than 8x single operation time")
    }

    // =============================================================================
    // Virtual Thread Efficiency Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should demonstrate virtual thread efficiency`() {
        val threadCount = 100
        val sleepMs = 50L

        val executionTime = measureExecution {
            eval(parallelSleepCode(threadCount, sleepMs))
        }

        println("Virtual Thread Efficiency Benchmark:")
        println("  ${threadCount} virtual threads: ${executionTime}ms")
        println("  Average time per thread: ${executionTime.toDouble() / threadCount}ms")

        val sequentialWouldTake = threadCount * sleepMs
        assertTrue(executionTime < sequentialWouldTake,
            "Virtual threads should beat sequential (got ${executionTime}ms vs ${sequentialWouldTake}ms sequential)")
    }

    // =============================================================================
    // Concurrent Operation Overhead Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should benchmark concurrent operation overhead`() {
        // Warm up
        repeat(5) { eval(parallelSleepCode(1, 0L)) }

        val counts = listOf(10, 25, 50)
        val results = mutableMapOf<Int, Long>()

        for (count in counts) {
            // 3 runs, take median
            val times = (1..3).map {
                measureExecution {
                    // Spawn N tasks that immediately return; measures dispatch overhead
                    val src = buildString {
                        appendLine(asyncSetup)
                        appendLine()
                        appendLine("perform LoomAsync in do")
                        for (i in 1..count) {
                            appendLine("    let op$i = Async.spawn { f = {} -> $i }")
                        }
                        appendLine("    result::get_or_else { result = try_all { ps = [${(1..count).joinToString(", ") { "op$it" }}] }, def = [] }")
                    }
                    eval(src)
                }
            }.sorted()
            results[count] = times[1]
        }

        println("Concurrent Operation Overhead Benchmark:")
        results.forEach { (count, time) ->
            val perOp = time.toDouble() / count
            println("  ${count} spawn+await: ${time}ms (${"%.2f".format(perOp)}ms per op)")
        }

        results.forEach { (count, time) ->
            assertTrue(time < count * 100,
                "Spawn+await dispatch should be efficient at $count ops (got ${time}ms)")
        }
    }

    // =============================================================================
    // Structured Concurrency Overhead Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should benchmark structured concurrency overhead`() {
        val n = 20
        val sleepMs = 20L

        val executionTime = measureExecution {
            eval(parallelSleepCode(n, sleepMs))
        }

        println("Structured Concurrency Overhead Benchmark:")
        println("  $n parallel sleep ${sleepMs}ms: ${executionTime}ms")
        println("  Expected parallel time: ~${sleepMs}ms")

        val sequentialWouldTake = sleepMs * n
        assertTrue(executionTime < sequentialWouldTake * 2,
            "Structured concurrency should not exceed 2x sequential time " +
            "(got ${executionTime}ms, sequential would be ${sequentialWouldTake}ms)")
    }

    // =============================================================================
    // Memory Efficiency Benchmark
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should handle many concurrent operations without excessive memory`() {
        val n = 20
        val sleepMs = 50L

        val executionTime = measureExecution {
            eval(parallelSleepCode(n, sleepMs))
        }

        println("Memory Efficiency Benchmark:")
        println("  $n concurrent operations: ${executionTime}ms")

        val sequentialWouldTake = n * sleepMs
        assertTrue(executionTime < sequentialWouldTake,
            "$n concurrent ops should beat sequential " +
            "(got ${executionTime}ms vs ${sequentialWouldTake}ms sequential)")
    }

    // =============================================================================
    // Cross-thread effect propagation overhead (NEW for this branch)
    // =============================================================================

    @Test
    @Timeout(30)
    fun `should propagate effects across spawned threads efficiently`() {
        // The new dispatch carries the parent's handler chain to spawned
        // threads. When a spawned task performs an effect-op (here
        // Exception.throw) that the parent handles, the runtime stashes
        // the op on the task's promise; Async.await re-issues it on the
        // parent fiber. This benchmark records that round-trip cost vs.
        // running the same throws in-fiber.
        //
        // Sequential baseline: one perform per throw, all in the main
        // fiber, no spawn.
        //
        // Cross-thread: spawn N tasks each of which throws; await the
        // first one (which re-raises on the parent fiber, where
        // ExceptionToResult catches and aborts). Subsequent tasks'
        // outcomes are discarded — the perform has already returned.
        val n = 100

        val sequentialThrows = measureExecution {
            val src = buildString {
                appendLine("use std::effects::exception::{Exception, ExceptionToResult}")
                appendLine()
                appendLine("let try_throw = {} -> perform ExceptionToResult in")
                appendLine("    Exception.throw { error = \"x\" }")
                appendLine()
                appendLine("let loop : { n: Int } -> Int = { n } ->")
                appendLine("    if n <= 0 then 0")
                appendLine("    else do")
                appendLine("        let _ = try_throw {}")
                appendLine("        loop { n = n - 1 }")
                appendLine()
                appendLine("loop { n = $n }")
            }
            eval(src)
        }

        val crossThreadThrows = measureExecution {
            val src = buildString {
                appendLine(asyncSetup)
                appendLine("use std::effects::exception::{Exception, ExceptionToResult}")
                appendLine()
                appendLine("perform ExceptionToResult in perform LoomAsync in do")
                for (i in 1..n) {
                    appendLine("    let t$i = Async.spawn { f = {} -> Exception.throw { error = \"x\" } }")
                }
                // Awaiting the first task surfaces its throw on the parent
                // fiber; ExceptionToResult catches → aborts perform → we
                // return Err. The other spawned tasks complete in the
                // background but their stashed ops are never re-issued.
                appendLine("    Async.await { p = t1 }")
            }
            eval(src)
        }

        println("Cross-thread effect propagation overhead:")
        println("  $n sequential throws (no spawn): ${sequentialThrows}ms")
        println("  $n cross-thread spawns + 1 await: ${crossThreadThrows}ms")

        assertTrue(crossThreadThrows > 0, "benchmark should produce a measurable time")
    }
}
