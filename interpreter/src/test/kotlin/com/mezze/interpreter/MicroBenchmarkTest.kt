package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout

/**
 * Micro-benchmarks to isolate WHERE the dispatch overhead lives.
 *
 * Compare:
 * 1. Pure recursion (no native calls) — measures call dispatch alone
 * 2. Recursion + arithmetic only — measures inline op overhead
 * 3. Recursion + native call (map insert) — measures full dispatch
 *
 * If (1) is already 10ms at n=10K, the bottleneck is recursion/calls,
 * not native dispatch — DS-Wire won't help.
 *
 * If (1) is 1ms but (3) is 10ms, native dispatch IS the bottleneck.
 */
class MicroBenchmarkTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun benchmark(name: String, code: String, iterations: Int): Long {
        eval(code)  // warmup
        val timings = LongArray(5)
        for (i in 0 until 5) {
            System.gc()
            Thread.sleep(50)
            val start = System.nanoTime()
            eval(code)
            timings[i] = (System.nanoTime() - start) / 1_000_000
        }
        timings.sort()
        val median = timings[2]
        val perIter = median.toDouble() / iterations
        println("  $name: ${median}ms total, ${"%.4f".format(perIter)}ms/iter ($iterations iters)")
        return median
    }

    @Test
    @Timeout(300)
    fun `01 - pure recursion - no native calls`() {
        println("\n=== Pure recursion (just count down to 0) ===")
        for (n in listOf(100, 1000, 10000)) {
            val code = """
                let count_down: {n: Int} -> Int = {n} ->
                  if n == 0 then 0 else count_down {n = n - 1}

                let bench: {iter: Int, max: Int} -> Int = {iter, max} ->
                  if iter == max
                    then count_down {n = $n}
                    else do
                      let _ = count_down {n = $n}
                      bench {iter = iter + 1, max = max}

                bench {iter = 0, max = ITERATIONS}
            """.trimIndent().replace("ITERATIONS", when (n) { 100 -> "1000"; 1000 -> "100"; else -> "10" })
            benchmark("n=$n", code, when (n) { 100 -> 1000; 1000 -> 100; else -> 10 })
        }
    }

    @Test
    @Timeout(300)
    fun `02 - recursion + arithmetic`() {
        println("\n=== Recursion + arithmetic (sum 0..n) ===")
        for (n in listOf(100, 1000, 10000)) {
            val code = """
                let sum_to: {acc: Int, i: Int, n: Int} -> Int = {acc, i, n} ->
                  if i == n then acc else sum_to {acc = acc + i, i = i + 1, n = n}

                let bench: {iter: Int, max: Int} -> Int = {iter, max} ->
                  if iter == max
                    then sum_to {acc = 0, i = 0, n = $n}
                    else do
                      let _ = sum_to {acc = 0, i = 0, n = $n}
                      bench {iter = iter + 1, max = max}

                bench {iter = 0, max = ITERATIONS}
            """.trimIndent().replace("ITERATIONS", when (n) { 100 -> "1000"; 1000 -> "100"; else -> "10" })
            benchmark("n=$n", code, when (n) { 100 -> 1000; 1000 -> 100; else -> 10 })
        }
    }

    @Test
    @Timeout(300)
    fun `03 - recursion + map insert (full native dispatch)`() {
        println("\n=== Recursion + map::insert (full dispatch) ===")
        for (n in listOf(100, 1000, 10000)) {
            val code = """
                use std::collections::map

                let build_map: {acc: Map Int Int, i: Int, n: Int} -> Map Int Int = {acc, i, n} ->
                  if i == n
                    then acc
                    else build_map {acc = map::insert {m = acc, k = i, v = i * 2}, i = i + 1, n = n}

                let bench: {iter: Int, max: Int, last: Int} -> Int = {iter, max, last} ->
                  if iter == max
                    then last
                    else do
                      let m = build_map {acc = map::empty {}, i = 0, n = $n}
                      bench {iter = iter + 1, max = max, last = map::size {m = m}}

                bench {iter = 0, max = ITERATIONS, last = 0}
            """.trimIndent().replace("ITERATIONS", when (n) { 100 -> "1000"; 1000 -> "100"; else -> "10" })
            benchmark("n=$n", code, when (n) { 100 -> 1000; 1000 -> 100; else -> 10 })
        }
    }
}
