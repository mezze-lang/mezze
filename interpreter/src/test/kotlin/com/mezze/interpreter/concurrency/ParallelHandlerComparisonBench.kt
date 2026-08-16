package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.bench.BenchRecorder
import com.mezze.language.typechecker.ModuleResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit

/**
 * Head-to-head comparison of LoomParallel vs OSThreadParallel on the
 * same workloads. In phase 1 (#464) both alias the same Loom-virtual-
 * thread substrate, so numbers should be within measurement noise.
 * Phase 2 (#465) rebuilds OSThreadParallel on a real platform-thread
 * pool — at that point this bench becomes the regression check for
 * which substrate wins on which workload.
 *
 * Named "*Bench" so :interpreter:bench picks it up but :interpreter:test
 * skips it.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelHandlerComparisonBench {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        var accum = 0L
        for (i in 0 until 5_000) accum += i * 2L
        require(accum >= 0)
    }

    private fun eval(source: String): Any = MezzeTestHelper.runJit(source)

    private fun runBoth(label: String, body: (handler: String) -> Unit) {
        body("LoomParallel")
        body("OSThreadParallel")
    }

    // ==================== sum 1M range ====================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `compare sum 1M range`() {
        runBoth("sum 1M range") { handler ->
            val src = """
                use std::collections::seq
                use std::effects::parallel::{ Parallel, $handler, sum }

                perform $handler in do
                    sum { xs = seq::range { start = 0, end = 1000000 } }
            """.trimIndent()
            BenchRecorder.bench(
                "Compare",
                "$handler :: sum 1M range",
                iters = 1, warmup = 5, runs = 5,
            ) { eval(src) }
        }
    }

    // ==================== map 10K x2 ====================

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `compare map 10K x2`() {
        runBoth("map 10K x2") { handler ->
            val src = """
                use std::collections::seq
                use std::effects::parallel::{ Parallel, $handler, map }

                perform $handler in do
                    map {
                        xs = seq::range { start = 0, end = 10000 },
                        f  = { x } -> x * 2
                    }
            """.trimIndent()
            BenchRecorder.bench(
                "Compare",
                "$handler :: map 10K x2",
                iters = 1, warmup = 5, runs = 5,
            ) { eval(src) }
        }
    }

    // ==================== fold 100K sum-of-x ====================

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    fun `compare fold 100K sum-of-x`() {
        runBoth("fold 100K") { handler ->
            val src = """
                use std::collections::seq
                use std::effects::parallel::{ Parallel, $handler, fold }

                perform $handler in do
                    fold {
                        xs = seq::range { start = 0, end = 100000 },
                        zero = 0,
                        map_fn = { x } -> x,
                        combine = { l, r } -> l + r
                    }
            """.trimIndent()
            BenchRecorder.bench(
                "Compare",
                "$handler :: fold 100K sum-of-x",
                iters = 1, warmup = 5, runs = 5,
            ) { eval(src) }
        }
    }

    // ==================== filter 100K even ====================

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `compare filter 100K even`() {
        runBoth("filter 100K") { handler ->
            val src = """
                use std::collections::seq
                use std::effects::parallel::{ Parallel, $handler, filter }

                perform $handler in do
                    filter {
                        xs = seq::range { start = 0, end = 100000 },
                        f  = { x } -> x % 2 == 0
                    }
            """.trimIndent()
            BenchRecorder.bench(
                "Compare",
                "$handler :: filter 100K even",
                iters = 1, warmup = 5, runs = 5,
            ) { eval(src) }
        }
    }

    // ==================== reduce 100K sum ====================

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `compare reduce 100K sum`() {
        runBoth("reduce 100K") { handler ->
            val src = """
                use std::collections::seq
                use std::effects::parallel::{ Parallel, $handler, reduce }

                perform $handler in do
                    reduce {
                        xs = seq::range { start = 0, end = 100000 },
                        f  = { l, r } -> l + r
                    }
            """.trimIndent()
            BenchRecorder.bench(
                "Compare",
                "$handler :: reduce 100K sum",
                iters = 1, warmup = 5, runs = 5,
            ) { eval(src) }
        }
    }
}
