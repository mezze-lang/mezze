package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #65 — min_chunk hint on ForkJoinParallelWith / VirtualParallelWith.
 *
 * <p>The derived combinators (map, filter, fold, ...) recurse via
 * binary split all the way to singletons by default. When per-element
 * work is small, fork overhead dominates. The With-Chunk impls let
 * callers set a threshold: sub-batches at or below `min_chunk`
 * elements run sequentially via the corresponding {@code seq::}
 * combinator instead of forking further.
 *
 * <p>These tests verify:
 * 1. Chunk hint produces the same result as no hint (correctness).
 * 2. Default behavior (min_chunk = 1) matches the old semantics.
 * 3. Hint applies across all common combinators.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MinChunkHintTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `ForkJoinParallelWith map produces same result as ForkJoinParallel`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallelWith, map }

            perform ForkJoinParallelWith { parallelism = 4, min_chunk = 8 } in do
                map { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], f = { x } -> x * 2 }
        """
        val result = eval(code) as MezzeSeq<*>
        assertEquals(10, result.size())
        assertEquals(2L, result.get(0))
        assertEquals(20L, result.get(9))
    }

    @Test
    fun `ForkJoinParallelWith fold correct result`() {
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallelWith, fold }

            perform ForkJoinParallelWith { parallelism = 4, min_chunk = 16 } in do
                fold {
                    xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20],
                    zero = 0,
                    map_fn = { x } -> x,
                    combine = { l, r } -> l + r
                }
        """
        // 1 + 2 + ... + 20 = 20 * 21 / 2 = 210
        assertEquals(210L, eval(code))
    }

    @Test
    fun `ForkJoinParallelWith with large min_chunk keeps small workload sequential`() {
        // With min_chunk = 100 and a 10-element list, the initial size (10)
        // is below the threshold, so the combinator delegates directly to
        // seq::map — no forking happens at all. Verifies the fast path.
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallelWith, map }

            perform ForkJoinParallelWith { parallelism = 4, min_chunk = 100 } in do
                map { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], f = { x } -> x + 100 }
        """
        val result = eval(code) as MezzeSeq<*>
        assertEquals(10, result.size())
        assertEquals(101L, result.get(0))
        assertEquals(110L, result.get(9))
    }

    @Test
    fun `VirtualParallelWith filter correct result`() {
        val code = """
            use std::effects::parallel::{ Parallel, VirtualParallelWith, filter }

            perform VirtualParallelWith { max_concurrent = 4, min_chunk = 4 } in do
                filter { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], f = { x } -> x > 5 }
        """
        val result = eval(code) as MezzeSeq<*>
        assertEquals(5, result.size())
        assertEquals(6L, result.get(0))
        assertEquals(10L, result.get(4))
    }

    @Test
    fun `default ForkJoinParallel behavior unchanged`() {
        // ForkJoinParallel (no chunk hint) should still split down to
        // singletons — verifies backward compatibility.
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallel, reduce }

            perform ForkJoinParallel in do
                match reduce { xs = [1, 2, 3, 4, 5], f = { l, r } -> l + r } is
                    Some { val } -> val
                    None -> 0
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `ForkJoinParallelWith reduce works via wrapper`() {
        // parallel::reduce uses {l, r} but seq::reduce uses {acc, x};
        // the wrapper lambda inside the chunk-fallback bridges them.
        val code = """
            use std::effects::parallel::{ Parallel, ForkJoinParallelWith, reduce }

            perform ForkJoinParallelWith { parallelism = 4, min_chunk = 100 } in do
                match reduce { xs = [1, 2, 3, 4, 5], f = { l, r } -> l + r } is
                    Some { val } -> val
                    None -> 0
        """
        assertEquals(15L, eval(code))
    }
}
