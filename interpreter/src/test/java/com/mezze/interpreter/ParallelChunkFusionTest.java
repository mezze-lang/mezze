package com.mezze.interpreter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for fused parallel pipelines: chunk_fold + chunk_map.
 *
 * These verify that chunk-level parallel combinators work correctly and
 * allow Iter pipelines (FLP-fused) to run inside each parallel chunk.
 *
 * Uses DeterministicSequential handler to avoid timing flakiness while
 * testing the combinator logic.
 */
class ParallelChunkFusionTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache();
    }

    private Object eval(String src) {
        return MezzeTestHelper.run(src);
    }

    // ── chunk_fold ──────────────────────────────────────────────────────

    @Test
    void chunkFoldSumOfSquares() {
        // Fused map+sum via chunk_fold: one pass, no intermediate Seq
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_fold }
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            perform DeterministicSequential in do
              chunk_fold {
                xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                zero = 0,
                f = { xs } -> xs.to_iter{}.map { f = { x } -> x * x }.sum{},
                combine = { l, r } -> l + r,
              }
            """);
        // 1+4+9+16+25+36+49+64+81+100 = 385
        assertEquals(385L, result);
    }

    @Test
    void chunkFoldFilterCount() {
        // Fused filter+count via chunk_fold
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_fold }
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            perform DeterministicSequential in do
              chunk_fold {
                xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                zero = 0,
                f = { xs } -> xs.to_iter{}.filter { p = { x } -> x % 2 == 0 }.count{},
                combine = { l, r } -> l + r,
              }
            """);
        assertEquals(5L, result);
    }

    @Test
    void chunkFoldEmpty() {
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_fold }

            perform DeterministicSequential in do
              chunk_fold {
                xs = [],
                zero = 42,
                f = { xs } -> 0,
                combine = { l, r } -> l + r,
              }
            """);
        assertEquals(42L, result);
    }

    @Test
    void chunkFoldMapFilterSum() {
        // Three-stage pipeline: map then filter then sum — all fused per chunk
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_fold }
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            perform DeterministicSequential in do
              chunk_fold {
                xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                zero = 0,
                f = { xs } ->
                  xs.to_iter{}
                    .map { f = { x } -> x * 3 }
                    .filter { p = { x } -> x > 15 }
                    .sum{},
                combine = { l, r } -> l + r,
              }
            """);
        // x*3 > 15 → x > 5: 6*3=18, 7*3=21, 8*3=24, 9*3=27, 10*3=30
        // sum = 18+21+24+27+30 = 120
        assertEquals(120L, result);
    }

    // ── chunk_map ───────────────────────────────────────────────────────

    @Test
    void chunkMapTransformFilter() {
        // Fused parallel map+filter → collected to Seq
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_map }
            use std::collections::iter
            use std::collections::seq
            use std::abilities::iter::{ Iterable }

            perform DeterministicSequential in do
              let result = chunk_map {
                xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                f = { xs } ->
                  xs.to_iter{}
                    .filter { p = { x } -> x % 2 == 0 }
                    .map { f = { x } -> x * 10 }
                    .to_seq{},
              }
              seq::sum { xs = result }
            """);
        // Even: 2,4,6,8,10 → *10: 20,40,60,80,100 → sum = 300
        assertEquals(300L, result);
    }

    @Test
    void chunkMapPreservesOrder() {
        // chunk_map should preserve element order
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_map }
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            perform DeterministicSequential in do
              chunk_map {
                xs = [1, 2, 3, 4, 5],
                f = { xs } -> xs.to_iter{}.map { f = { x } -> x * 2 }.to_seq{},
              }
            """);
        assertEquals(eval("[2, 4, 6, 8, 10]"), result);
    }

    @Test
    void chunkMapEmpty() {
        var result = eval("""
            use std::effects::parallel::{ Parallel, DeterministicSequential, chunk_map }

            perform DeterministicSequential in do
              chunk_map {
                xs = [],
                f = { xs } -> xs,
              }
            """);
        assertEquals(eval("[]"), result);
    }

    // ── chunk_fold with LoomParallel ────────────────────────────────────

    @Test
    void chunkFoldWithLoomParallel() {
        // Same test as chunkFoldSumOfSquares but with actual parallelism
        var result = eval("""
            use std::effects::parallel::{ Parallel, LoomParallel, chunk_fold }
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            perform LoomParallel in do
              chunk_fold {
                xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                zero = 0,
                f = { xs } -> xs.to_iter{}.map { f = { x } -> x * x }.sum{},
                combine = { l, r } -> l + r,
              }
            """);
        assertEquals(385L, result);
    }

    // ── chunk_map with LoomParallel on larger data ──────────────────────

    @Test
    void chunkMapLargerData() {
        // 1000 elements: map+filter via chunk_map
        var result = eval("""
            use std::effects::parallel::{ Parallel, LoomParallel, chunk_fold }
            use std::collections::iter
            use std::collections::seq
            use std::abilities::iter::{ Iterable }

            perform LoomParallel in do
              chunk_fold {
                xs = seq::range { start = 1, end = 1001 },
                zero = 0,
                f = { xs } ->
                  xs.to_iter{}
                    .filter { p = { x } -> x % 10 == 0 }
                    .map { f = { x } -> x / 10 }
                    .sum{},
                combine = { l, r } -> l + r,
              }
            """);
        // Multiples of 10 in [1,1000]: 10,20,...,1000 → /10: 1,2,...,100
        // Sum = 100*101/2 = 5050
        assertEquals(5050L, result);
    }
}
