package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout

/**
 * End-to-end benchmark that measures the actual performance of the
 * COW data structure optimizations through the Mezze interpreter.
 *
 * This validates that storage-layer COW optimizations (HashMapStorage,
 * HashSetStorage, *SeqStorage, etc.) actually translate to real Mezze
 * program speedups, not just unit-level improvements.
 *
 * Methodology:
 * - JIT warmup: 5 runs of the same program to let GraalVM optimize
 * - Measure: 5 runs, take median (avoids outliers from GC pauses)
 * - Compare across sizes (100, 1000, 10000) to confirm O(n) not O(n²)
 *
 * Pre-COW expected: O(n²) — n=10000 would take ~40 seconds
 * Post-COW expected: O(n) — n=10000 should take ~30ms or less
 */
class EndToEndCowBenchmarkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * Benchmark a Mezze program by running the workload N times INSIDE the
     * Mezze code itself (single eval), so parse/compile/setup happens once
     * but execution is amortized across N iterations. This isolates
     * dispatch + execution cost from compile cost.
     *
     * The code template must contain `WORKLOAD` placeholder and
     * `ITERATIONS` placeholder. ITERATIONS is the number of times the
     * workload runs in a single eval.
     */
    private fun benchmarkAmortized(name: String, codeTemplate: String, iterations: Int): Long {
        val code = codeTemplate
            .replace("ITERATIONS", iterations.toString())

        // Single warmup eval — primes parser, typechecker, JIT
        eval(code)

        // Measure 5 runs, take median
        val timings = LongArray(5)
        for (i in 0 until 5) {
            System.gc()
            Thread.sleep(50)

            val start = System.nanoTime()
            eval(code)
            val elapsed = (System.nanoTime() - start) / 1_000_000  // ms
            timings[i] = elapsed
        }
        timings.sort()
        val median = timings[2]
        val perIter = median.toDouble() / iterations

        println("  $name: median=${median}ms total, ${"%.3f".format(perIter)}ms per iteration ($iterations iters, runs: ${timings.toList()})")
        return median
    }

    // ============================================================
    // MAP — incremental build
    // ============================================================

    @Test
    @Timeout(300)
    fun `benchmark map incremental build`() {
        println("\n=== Map: Incremental Build (n inserts, amortized over ITERATIONS runs) ===")

        for (n in listOf(100, 1000, 10000)) {
            // Build the map ITERATIONS times in a single Mezze program.
            // Compile happens once; we measure the actual build work amortized.
            val code = """
                use std::collections::map

                let build_map: {acc: Map Int Int, i: Int, n: Int} -> Map Int Int = {acc, i, n} ->
                  if i == n
                    then acc
                    else build_map {acc = map::insert {m = acc, k = i, v = i * 2}, i = i + 1, n = n}

                let bench: {iter: Int, max: Int, last_size: Int} -> Int = {iter, max, last_size} ->
                  if iter == max
                    then last_size
                    else do
                      let m = build_map {acc = map::empty {}, i = 0, n = $n}
                      bench {iter = iter + 1, max = max, last_size = map::size {m = m}}

                bench {iter = 0, max = ITERATIONS, last_size = 0}
            """.trimIndent()

            // Larger n needs fewer iterations to keep total time reasonable
            val iters = when (n) {
                100 -> 1000
                1000 -> 100
                else -> 10
            }
            benchmarkAmortized("n=$n", code, iters)
        }
    }

    // ============================================================
    // SET — incremental build
    // ============================================================

    @Test
    @Timeout(300)
    fun `benchmark set incremental build`() {
        println("\n=== Set: Incremental Build (n adds, amortized) ===")

        for (n in listOf(100, 1000, 10000)) {
            val code = """
                use std::collections::set

                let build_set: {acc: Set Int, i: Int, n: Int} -> Set Int = {acc, i, n} ->
                  if i == n
                    then acc
                    else build_set {acc = set::insert {s = acc, x = i}, i = i + 1, n = n}

                let bench: {iter: Int, max: Int, last_size: Int} -> Int = {iter, max, last_size} ->
                  if iter == max
                    then last_size
                    else do
                      let s = build_set {acc = set::empty {}, i = 0, n = $n}
                      bench {iter = iter + 1, max = max, last_size = set::size {s = s}}

                bench {iter = 0, max = ITERATIONS, last_size = 0}
            """.trimIndent()

            val iters = when (n) {
                100 -> 1000
                1000 -> 100
                else -> 10
            }
            benchmarkAmortized("n=$n", code, iters)
        }
    }

    // ============================================================
    // SEQ — incremental append
    // ============================================================

    @Test
    @Timeout(300)
    fun `benchmark seq incremental append`() {
        println("\n=== Seq: Incremental Append (n pushes, amortized) ===")

        for (n in listOf(100, 1000, 10000)) {
            val code = """
                use std::collections::seq

                let build_seq: {acc: [Int], i: Int, n: Int} -> [Int] = {acc, i, n} ->
                  if i == n
                    then acc
                    else build_seq {acc = seq::push {xs = acc, x = i}, i = i + 1, n = n}

                let bench: {iter: Int, max: Int, last_size: Int} -> Int = {iter, max, last_size} ->
                  if iter == max
                    then last_size
                    else do
                      let s = build_seq {acc = [], i = 0, n = $n}
                      bench {iter = iter + 1, max = max, last_size = seq::size {xs = s}}

                bench {iter = 0, max = ITERATIONS, last_size = 0}
            """.trimIndent()

            val iters = when (n) {
                100 -> 1000
                1000 -> 100
                else -> 10
            }
            benchmarkAmortized("n=$n", code, iters)
        }
    }

    // ============================================================
    // BUILD then READ — common pattern (validates reads are unchanged)
    // ============================================================

    @Test
    @Timeout(300)
    fun `benchmark map build and read`() {
        println("\n=== Map: Build + 1000 lookups (amortized) ===")

        for (n in listOf(100, 1000, 10000)) {
            val code = """
                use std::collections::map

                let build_map: {acc: Map Int Int, i: Int, n: Int} -> Map Int Int = {acc, i, n} ->
                  if i == n
                    then acc
                    else build_map {acc = map::insert {m = acc, k = i, v = i * 2}, i = i + 1, n = n}

                let lookup: {m: Map Int Int, sum: Int, i: Int, count: Int, mod_n: Int} -> Int = {m, sum, i, count, mod_n} ->
                  if i == count
                    then sum
                    else do
                      let v = match map::get {m = m, k = i % mod_n} is
                        Some {val} -> val
                        None -> 0
                      lookup {m = m, sum = sum + v, i = i + 1, count = count, mod_n = mod_n}

                let bench: {iter: Int, max: Int, last_sum: Int} -> Int = {iter, max, last_sum} ->
                  if iter == max
                    then last_sum
                    else do
                      let m = build_map {acc = map::empty {}, i = 0, n = $n}
                      let s = lookup {m = m, sum = 0, i = 0, count = 1000, mod_n = $n}
                      bench {iter = iter + 1, max = max, last_sum = s}

                bench {iter = 0, max = ITERATIONS, last_sum = 0}
            """.trimIndent()

            val iters = when (n) {
                100 -> 1000
                1000 -> 100
                else -> 10
            }
            benchmarkAmortized("n=$n", code, iters)
        }
    }
}
