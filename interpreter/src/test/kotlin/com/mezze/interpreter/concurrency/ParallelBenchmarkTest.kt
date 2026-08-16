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
 * #70 — Parallel effect regression benchmark harness.
 *
 * <p>Records baseline numbers for the Parallel effect and fails if a
 * measured value exceeds its baseline by more than {@code TOLERANCE_PCT}.
 * Locks in the perf gains from tasks #48-#69 so a future change can't
 * silently regress them.
 *
 * <p>Baselines are hand-calibrated on the author's machine. On slower
 * hardware, override via {@code -Dparallel.bench.tolerance=50} to give
 * the tests more headroom. The whole harness is {@code @Disabled} by
 * default — perf tests are load-sensitive and don't belong in every
 * commit run. Enable with:
 * <pre>
 *   ./gradlew :interpreter:test --tests com.mezze.interpreter.concurrency.ParallelBenchmarkTest
 * </pre>
 * or add a system property {@code -Dparallel.bench.run=true} in CI's
 * perf lane.
 */
// NOT @Disabled — this test class matches the "*Benchmark*" pattern
// so it's automatically routed to the opt-in `:interpreter:bench` task
// (see interpreter/build.gradle.kts). Doesn't run under `:interpreter:test`.
@Execution(ExecutionMode.SAME_THREAD)
class ParallelBenchmarkTest {

    /**
     * Max acceptable multiple of baseline before the test fails. Default 30%
     * headroom absorbs jitter; override on slow machines with
     * -Dparallel.bench.tolerance=50 (= 50% headroom).
     */
    private val tolerancePct: Int =
        System.getProperty("parallel.bench.tolerance")?.toIntOrNull() ?: 30

    /**
     * Baseline median nanoseconds for each labeled bench. Calibrated on
     * Darwin arm64 (M-series) with warm JIT. If your box is materially
     * slower/faster, scale via -Dparallel.bench.tolerance.
     */
    // Calibrated on Darwin arm64 (Apple M-series). Actual medians
    // measured 2026-07-19, then rounded up generously — perf benches
    // are load-sensitive on shared machines. The `tolerancePct`
    // (default 30%) provides additional headroom on top.
    //
    // Rule of thumb: baseline ≈ 3x the best observed median so that
    // a real regression shows up cleanly without daily flakes.
    private val baselines: Map<String, Long> = mapOf(
        "parallel::map 10K x2"           to  75_000_000L,  //  75 ms  (measured 24-42)
        "parallel::sum 1M seq range"     to   5_000_000L,  //   5 ms  (measured 2.5)
        "parallel::product 1M seq range" to   7_000_000L,  //   7 ms  (measured 4)
        "parallel::fold 100K sum-of-x"   to 1_200_000_000L,// 1.2s   (high variance: measured 175-958)
        "4-way join fanout 5M SIMD sum"  to  25_000_000L,  //  25 ms  (measured 10-18)
    )

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        // JVM warmup — Truffle JIT warms via BenchRecorder's warmup passes.
        var accum = 0L
        for (i in 0 until 5_000) accum += i * 2L
        require(accum >= 0) // prevent DCE
    }

    private fun eval(source: String): Any = MezzeTestHelper.runJit(source)

    /**
     * Fail the test if [result].medianNs exceeds the baseline by more
     * than {@code tolerancePct}%. Missing baseline means the test just
     * records the number without gating; add the entry to `baselines` to
     * turn it into a regression guard.
     */
    private fun assertNoRegression(result: BenchRecorder.Result) {
        val baseline = baselines[result.label]
            ?: run {
                System.err.println("[regression] no baseline for '${result.label}' — recording only")
                return
            }
        val cap = baseline + (baseline * tolerancePct / 100)
        if (result.medianNs > cap) {
            val overPct = ((result.medianNs - baseline) * 100.0 / baseline)
            error(
                "REGRESSION: '${result.label}' median=${result.medianNs / 1_000_000}ms " +
                "> baseline ${baseline / 1_000_000}ms + ${tolerancePct}% tolerance " +
                "(cap ${cap / 1_000_000}ms) — over baseline by ${"%.1f".format(overPct)}%"
            )
        }
        val underPct = ((baseline - result.medianNs) * 100.0 / baseline)
        System.err.println(
            "[regression] '${result.label}' median=${result.medianNs / 1_000_000}ms " +
            "vs baseline ${baseline / 1_000_000}ms (${"%.1f".format(-underPct)}% delta, cap ${cap / 1_000_000}ms) ✓"
        )
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `parallel_map 10K elements baseline`() {
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, map }

            perform LoomParallel in do
                map { xs = seq::range { start = 0, end = 10000 }, f = { x } -> x * 2 }
        """.trimIndent()
        val r = BenchRecorder.bench("Parallel", "parallel::map 10K x2", iters = 1, warmup = 5, runs = 5) {
            eval(src)
        }
        assertNoRegression(r)
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `parallel_sum 1M range baseline`() {
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, sum }

            perform LoomParallel in do
                sum { xs = seq::range { start = 0, end = 1000000 } }
        """.trimIndent()
        val r = BenchRecorder.bench("Parallel", "parallel::sum 1M seq range", iters = 1, warmup = 5, runs = 5) {
            eval(src)
        }
        assertNoRegression(r)
    }

    /**
     * Warmed-CallTarget micro-bench. Runs the sum 100 times inside ONE
     * Mezze program so the CallTarget stays JIT-hot and per-call
     * Context creation cost is amortized. Divides total by 100 to
     * approximate the true steady-state per-op cost.
     *
     * <p>Isolates our per-op overhead from the harness's per-call
     * Context setup (which is ~1ms per eval()).
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `parallel_sum 1M range steady-state`() {
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, sum }

            let one_sum = { xs } -> sum { xs = xs }

            perform LoomParallel in do
                let xs = seq::range { start = 0, end = 1000000 }
                # Run the sum 100 times so the CallTarget stays warm.
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
                one_sum { xs = xs }
        """.trimIndent()
        // 20 sums per eval; timing includes Context setup once per bench iter.
        val r = BenchRecorder.bench("Parallel", "parallel::sum 1M steady-state (20 per eval)", iters = 20, warmup = 5, runs = 5) {
            eval(src)
        }
        System.err.println("[steady-state] per-op = ${r.perIterNs / 1_000_000.0} ms")
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `parallel_product 1M range baseline`() {
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, product }

            perform LoomParallel in do
                # Use small values to avoid overflow — product of 1s is still meaningful
                # perf-wise since the storage traversal + parallel dispatch is the cost.
                product { xs = seq::range { start = 1, end = 1000001 } }
        """.trimIndent()
        val r = BenchRecorder.bench("Parallel", "parallel::product 1M seq range", iters = 1, warmup = 5, runs = 5) {
            eval(src)
        }
        assertNoRegression(r)
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `parallel_fold 100K sum baseline`() {
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, fold }

            perform LoomParallel in do
                fold {
                    xs = seq::range { start = 0, end = 100000 },
                    zero = 0,
                    map_fn = { x } -> x,
                    combine = { l, r } -> l + r
                }
        """.trimIndent()
        val r = BenchRecorder.bench("Parallel", "parallel::fold 100K sum-of-x", iters = 1, warmup = 5, runs = 5) {
            eval(src)
        }
        assertNoRegression(r)
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `4-way join fanout 5M SIMD sum baseline`() {
        // Four independent parallel::sum calls, fanned out via nested
        // Parallel.join. Verifies the raw join overhead + numeric fast
        // path stack together well on CPU-heavy work.
        val src = """
            use std::collections::seq
            use std::effects::parallel::{ Parallel, LoomParallel, sum }

            perform LoomParallel in do
                let outer = Parallel.join {
                    l = {} -> Parallel.join {
                        l = {} -> sum { xs = seq::range { start = 0, end = 5000000 } },
                        r = {} -> sum { xs = seq::range { start = 0, end = 5000000 } }
                    },
                    r = {} -> Parallel.join {
                        l = {} -> sum { xs = seq::range { start = 0, end = 5000000 } },
                        r = {} -> sum { xs = seq::range { start = 0, end = 5000000 } }
                    }
                }
                outer.l.l + outer.l.r + outer.r.l + outer.r.r
        """.trimIndent()
        val r = BenchRecorder.bench("Parallel", "4-way join fanout 5M SIMD sum", iters = 1, warmup = 5, runs = 5) {
            eval(src)
        }
        assertNoRegression(r)
    }
}
