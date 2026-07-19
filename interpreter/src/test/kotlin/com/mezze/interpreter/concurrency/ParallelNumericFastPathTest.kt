package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #69 — parallel::sum / parallel::product native fast paths.
 *
 * <p>These delegate to __native_fjpool_sum_long and _product_long,
 * which chunk the underlying long[] across the FJP pool's workers
 * and do SIMD-per-chunk. Correctness must match the recursive-reduce
 * baseline for all inputs; these tests lock that in.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelNumericFastPathTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `parallel_sum small list`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, sum }

            perform ForkJoinParallel in do
                sum { xs = [1, 2, 3, 4, 5] }
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `parallel_sum empty list`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, sum }

            perform ForkJoinParallel in do
                sum { xs = [] }
        """
        assertEquals(0L, eval(code))
    }

    @Test
    fun `parallel_sum matches Gauss formula for range`() {
        val code = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, ForkJoinParallel, sum }

            perform ForkJoinParallel in do
                sum { xs = seq::range { start = 1, end = 10001 } }
        """
        // Sum 1..10000 = 10000 * 10001 / 2 = 50_005_000
        assertEquals(50_005_000L, eval(code))
    }

    @Test
    fun `parallel_product small list`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, product }

            perform ForkJoinParallel in do
                product { xs = [1, 2, 3, 4, 5] }
        """
        // 5! = 120
        assertEquals(120L, eval(code))
    }

    @Test
    fun `parallel_product empty list`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, product }

            perform ForkJoinParallel in do
                product { xs = [] }
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `parallel_sum under VirtualParallel still works`() {
        // vthread pool doesn't populate CURRENT_POOL — the native falls
        // back to inline (single-chunk) SIMD. Result should still match.
        val code = """
            use std::effects::parallel::{ Parallel, VirtualParallel, sum }

            perform VirtualParallel in do
                sum { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] }
        """
        assertEquals(55L, eval(code))
    }

    @Test
    fun `parallel_sum with negative values`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, sum }

            perform ForkJoinParallel in do
                sum { xs = [-100, 50, -25, 75] }
        """
        assertEquals(0L, eval(code))
    }
}
