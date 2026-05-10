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
        // Heavier warmup — multiple runs to ensure JIT compilation fires
        for (w in 0 until 10) eval(code)
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
    fun `00a - simple call - no recursion`() {
        // What's the cost of a single function call? Compares to recursion.
        println("\n=== Single call: f {n} returns n ===")
        for (n in listOf(100, 1000, 10000)) {
            val code = """
                let identity: {n: Int} -> Int = {n} -> n

                let bench: {iter: Int, max: Int, sum: Int} -> Int = {iter, max, sum} ->
                  if iter == max
                    then sum
                    else bench {iter = iter + 1, max = max, sum = sum + identity {n = $n}}

                bench {iter = 0, max = ITERATIONS, sum = 0}
            """.trimIndent().replace("ITERATIONS", "10000")
            benchmark("call_count=$n (irrelevant, n is data)", code, 10000)
        }
    }

    @Test
    @Timeout(300)
    fun `00b - tight loop calling the same function`() {
        // Pure call cost: how much does calling identity 10K times cost?
        println("\n=== Tight call loop: 10K calls to identity ===")
        val code = """
            let identity: {n: Int} -> Int = {n} -> n

            let bench: {iter: Int, max: Int, sum: Int} -> Int = {iter, max, sum} ->
              if iter == max
                then sum
                else bench {iter = iter + 1, max = max, sum = sum + identity {n = iter}}

            bench {iter = 0, max = 10000, sum = 0}
        """.trimIndent()
        benchmark("10K calls (1 iter)", code, 1)
    }

    @Test
    @Timeout(300)
    fun `00c - same as 00b but no inner call`() {
        // Same loop structure, but no inner call. Measures recursion overhead alone.
        println("\n=== Pure loop, no inner call ===")
        val code = """
            let bench: {iter: Int, max: Int, sum: Int} -> Int = {iter, max, sum} ->
              if iter == max
                then sum
                else bench {iter = iter + 1, max = max, sum = sum + iter}

            bench {iter = 0, max = 10000, sum = 0}
        """.trimIndent()
        benchmark("10K loop iters (no inner call)", code, 1)
    }

    @Test
    @Timeout(600)
    fun `00d8 - 2-arg HOF callback (fold-style indirect call)`() {
        // Stresses InvokeScalarNNode for N=2 — fold-style {acc, x} -> acc + x
        // callback invoked in a tight loop. Validates the permutation-cache path
        // when caller and callee param order match.
        println("\n=== 1M 2-arg HOF callback invocations ===")
        val code = """
            let fold_loop: {f: {acc: Int, x: Int} -> Int, n: Int, acc: Int, max: Int} -> Int =
              {f, n, acc, max} ->
                if n == max
                  then acc
                  else fold_loop {f = f, n = n + 1, acc = f {acc = acc, x = n}, max = max}

            let combine: {acc: Int, x: Int} -> Int = {acc, x} -> acc + x
            fold_loop {f = combine, n = 0, acc = 0, max = 1000000}
        """.trimIndent()
        benchmark("1M 2-arg HOF callback (fold-style)", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d4 - HOF callback (indirect call hot path)`() {
        // Stresses indirect calls: an inner loop that repeatedly calls a
        // function passed as a parameter. Mirrors what list::map does
        // internally. Currently goes through the record path even with
        // scalar enabled — measures whether indirect-call optimization
        // would be worth pursuing.
        println("\n=== 1M HOF callback invocations ===")
        val code = """
            let apply: {f: {x: Int} -> Int, n: Int, acc: Int, max: Int} -> Int =
              {f, n, acc, max} ->
                if n == max
                  then acc
                  else apply {f = f, n = n + 1, acc = acc + f {x = n}, max = max}

            let inc: {x: Int} -> Int = {x} -> x + 1
            apply {f = inc, n = 0, acc = 0, max = 1000000}
        """.trimIndent()
        benchmark("1M HOF callback (indirect call to inc)", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d7 - mixed-kind tail recursion (Object + 3 Long, no inner call)`() {
        // Pure mixed-kind path: callee is (function-value, n: Int, acc: Int, max: Int)
        // but the function arg is just passed through, never invoked. Isolates
        // the outer mixed-kind call cost from the inner HOF callback.
        println("\n=== 1M mixed-kind tail recursion, no inner call ===")
        val code = """
            let walk: {f: {x: Int} -> Int, n: Int, acc: Int, max: Int} -> Int =
              {f, n, acc, max} ->
                if n == max then acc
                else walk {f = f, n = n + 1, acc = acc + n, max = max}

            let inc: {x: Int} -> Int = {x} -> x + 1
            walk {f = inc, n = 0, acc = 0, max = 1000000}
        """.trimIndent()
        benchmark("1M mixed-kind tail recursion (no inner call)", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d6 - 4 Long params tail recursion (mixed-kind path size proxy)`() {
        // Pure all-LONG callee with 4 params, no HOF callback inside.
        // Compares against the 1-param recursion to see how the mixed-kind/
        // multi-param dispatch overhead scales with arity.
        println("\n=== 1M tail recursion, 4 Long params ===")
        val code = """
            let walk: {a: Int, b: Int, c: Int, d: Int} -> Int = {a, b, c, d} ->
              if a == 0 then b + c + d
              else walk {a = a - 1, b = b + 1, c = c + 1, d = d + 1}

            walk {a = 1000000, b = 0, c = 0, d = 0}
        """.trimIndent()
        benchmark("1M 4-Long tail recursion", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d5 - mixed-kind tail recursion (Object + 3 Long params)`() {
        // Stresses mixed-kind callee. apply has 4 params: f (OBJECT), n/acc/max (LONG).
        // Today scalar Object[] boxes the 3 Longs every recursive call.
        // Step 72 should bucket into (Object[1], long[3]) and skip boxing.
        println("\n=== 1M mixed-kind (Object+3Long) tail recursion ===")
        val code = """
            let apply: {f: {x: Int} -> Int, n: Int, acc: Int, max: Int} -> Int =
              {f, n, acc, max} ->
                if n == max
                  then acc
                  else apply {f = f, n = n + 1, acc = acc + f {x = n}, max = max}

            let inc: {x: Int} -> Int = {x} -> x + 1
            apply {f = inc, n = 0, acc = 0, max = 1000000}
        """.trimIndent()
        benchmark("1M mixed-kind tail recursion", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d3 - tail recursion over Float (typed-DOUBLE tail path)`() {
        // Stresses TailCallDirectScalarNode's typed-DOUBLE path: tail-recursive
        // function over a Float param. All-DOUBLE detection should fire.
        println("\n=== 1M tail recursion over Float (DOUBLE typed payload) ===")
        val code = """
            let walk: {x: Float} -> Float = {x} ->
              if x <= 0.0 then x else walk {x = x - 1.0}

            walk {x = 1000000.0}
        """.trimIndent()
        benchmark("1M Float tail recursion", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00d2 - non-tail factorial (typed-LONG non-tail path)`() {
        // Stresses the non-tail self-recursive Long-arg call path:
        // CallDirectScalarNode with useTypedLongPayload should fire here.
        // Recursive call is inside `*`, so it's NOT a tail call.
        println("\n=== 200K-iter factorial-style non-tail recursion ===")
        val code = """
            let fact_step: {n: Int} -> Int = {n} ->
              if n == 0 then 1 else n * fact_step {n = n - 1}

            let bench: {iter: Int, max: Int, sum: Int} -> Int = {iter, max, sum} ->
              if iter == max
                then sum
                else bench {iter = iter + 1, max = max, sum = sum + fact_step {n = 12}}

            bench {iter = 0, max = 200000, sum = 0}
        """.trimIndent()
        benchmark("200K iters * 13 fact calls = 2.6M non-tail calls", code, 200000 * 13)
    }

    @Test
    @Timeout(600)
    fun `00d - VERY large recursion to ensure JIT compiles`() {
        // 1M iterations - JIT will definitely compile
        println("\n=== 1M recursive calls (JIT should kick in) ===")
        val code = """
            let count: {n: Int} -> Int = {n} ->
              if n == 0 then 0 else count {n = n - 1}

            count {n = 1000000}
        """.trimIndent()
        benchmark("1M recursion", code, 1000000)
    }

    @Test
    @Timeout(600)
    fun `00f - allocation count per iteration (EA diagnostic)`() {
        // Measure allocations per recursion iteration. If escape analysis
        // is fully eliminating the per-call record, allocation count should
        // be near zero. If not, we'll see ~4 allocations per call.
        println("\n=== Allocation diagnostic ===")

        val code = """
            let count: {n: Int} -> Int = {n} ->
              if n == 0 then 0 else count {n = n - 1}

            count {n = 100000}
        """.trimIndent()

        // Warmup
        for (i in 0 until 5) eval(code)

        // Force GC to baseline
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val rt = Runtime.getRuntime()
        val beforeMem = rt.totalMemory() - rt.freeMemory()

        // Measure
        eval(code)

        val afterMem = rt.totalMemory() - rt.freeMemory()
        val deltaMb = (afterMem - beforeMem) / 1024.0 / 1024.0
        val deltaPerCall = (afterMem - beforeMem).toDouble() / 100_000

        println("  Memory delta: ${"%.2f".format(deltaMb)} MB total, ${"%.1f".format(deltaPerCall)} bytes/call (100K iters)")
        println("  (If escape analysis works, this should be close to 0 bytes/call)")
        println("  (Each MezzeRecord = MezzeRecord obj + long[] + double[] + Object[] = ~80 bytes)")
    }

    @Test
    @Timeout(600)
    fun `00e - run after warmup INSIDE eval (true steady state)`() {
        // Warmup INSIDE the same eval: run count_down many times before timing
        // This ensures JIT has compiled by the time we measure.
        println("\n=== Steady state recursion (warmup inside eval) ===")

        // First test: warmup with 1M, then benchmark with 100K (10K each, 10 iters)
        val code = """
            let count: {n: Int} -> Int = {n} ->
              if n == 0 then 0 else count {n = n - 1}

            # Warmup inside eval — 1M iterations to trigger JIT
            let warmup = count {n = 1000000}

            # Now measure: 10K iterations to compare to original benchmark
            let measured = count {n = 10000}
            measured
        """.trimIndent()
        benchmark("warmup 1M then 10K", code, 10000)
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
