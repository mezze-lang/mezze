package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit

/**
 * Task #50/#54 — verifies that seq::fold call sites scale across cores
 * when invoked in parallel via the {@code Parallel} effect (not the
 * {@code Async} effect: Async uses Loom virtual threads which serialize
 * CPU-bound work on shared carriers, giving no core-level parallelism).
 *
 * <p>Bench compares:
 *   - Sequential: four cpu_work calls one after another
 *   - Parallel: same four cpu_work calls but fanned out via three
 *     {@code Parallel.join} calls (recursive binary split) under
 *     {@code LoomParallel}, i.e. a real ForkJoinPool with
 *     one platform-thread worker per core (Rayon-style).
 *
 * <p>Historical numbers on this branch:
 *   - Async.spawn 4-way: ratio par:seq ≈ 1.26× (loom carriers serialize)
 *   - Parallel.join 4-way ForkJoinPool: expected ratio ≈ 0.30× on a
 *     4+-core machine (real parallelism)
 *
 * Disabled by default (perf tests are load-sensitive on shared CI).
 */
@Disabled("perf smoke — run manually. Baseline 5M, true 4-way fanout: seq~310ms par~120ms ratio 0.38× (2.6× real speedup). Per-leaf spread <10ms rules out JIT staggering; residual gap to 4× is memory bandwidth on concurrent 40MB fold reads.")
@Execution(ExecutionMode.SAME_THREAD)
class SeqFoldParallelScalingTest {

    private val program = """
        use std::collections::seq
        use std::effects::parallel::{ Parallel, LoomParallel, LoomParallelWith }

        let cpu_work = { n } -> do
            let xs = seq::range { start = 0, end = n }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x * x - x / 2 }

        let scope_t0 = __native_time_now_nanos {}
        perform LoomParallel in do
            let scope_t1 = __native_time_now_nanos {}
            # Warmup — repeat cpu_work to get the fold call site JIT-hot.
            let w1 = cpu_work { n = 5000000 }
            let w2 = cpu_work { n = 5000000 }
            let w3 = cpu_work { n = 5000000 }
            let w4 = cpu_work { n = 5000000 }
            let w5 = cpu_work { n = 5000000 }
            let w6 = cpu_work { n = 5000000 }
            let w7 = cpu_work { n = 5000000 }
            let w8 = cpu_work { n = 5000000 }
            let w9 = cpu_work { n = 5000000 }
            let w10 = cpu_work { n = 5000000 }
            let w11 = cpu_work { n = 5000000 }
            let w12 = cpu_work { n = 5000000 }
            let w13 = cpu_work { n = 5000000 }
            let w14 = cpu_work { n = 5000000 }
            let w15 = cpu_work { n = 5000000 }
            let w16 = cpu_work { n = 5000000 }
            let w17 = cpu_work { n = 5000000 }
            let w18 = cpu_work { n = 5000000 }
            let w19 = cpu_work { n = 5000000 }
            let w20 = cpu_work { n = 5000000 }

            # Sequential baseline: four cpu_work calls one at a time.
            let s0 = __native_time_now_nanos {}
            let a = cpu_work { n = 5000000 }
            let b = cpu_work { n = 5000000 }
            let c = cpu_work { n = 5000000 }
            let d = cpu_work { n = 5000000 }
            let seq_sum = a + b + c + d
            let s1 = __native_time_now_nanos {}

            # Parallel: four cpu_work calls fanned out via three Parallel.join
            # under LoomParallel — recursive binary split so all four leaves
            # can execute on separate ForkJoinPool workers. Each leaf reports
            # its own wall-clock duration so we can see the spread — a wide
            # spread (e.g. 40ms/40ms/40ms/150ms) indicates JIT staggering:
            # the slow leaf ran interpreted while the others ran JIT'd.
            let timed_cpu_work = { n } -> do
                let t0 = __native_time_now_nanos {}
                let v = cpu_work { n = n }
                let t1 = __native_time_now_nanos {}
                { v = v, ns = t1 - t0 }

            # True 4-way fanout: outer join, each side forks another join.
            # Structure:
            #   top ── ab ── a (leaf)
            #      │       └ b (leaf)
            #      └─ cd ── c (leaf)
            #             └ d (leaf)
            # The outer join blocks until both ab and cd complete. Because ab
            # and cd run in parallel (via the outer join), and each of them
            # forks its own leaves in parallel, all 4 leaves execute concurrently
            # on a 4+-core box.
            let p0 = __native_time_now_nanos {}
            let outer = Parallel.join {
                l = {} -> Parallel.join {
                    l = {} -> timed_cpu_work { n = 5000000 },
                    r = {} -> timed_cpu_work { n = 5000000 }
                },
                r = {} -> Parallel.join {
                    l = {} -> timed_cpu_work { n = 5000000 },
                    r = {} -> timed_cpu_work { n = 5000000 }
                }
            }
            let ab = outer.l
            let cd = outer.r
            let par_sum = ab.l.v + ab.r.v + cd.l.v + cd.r.v
            let p1 = __native_time_now_nanos {}

            let scope_t2 = __native_time_now_nanos {}
            {
                seq_ns = s1 - s0,
                par_ns = p1 - p0,
                seq_sum = seq_sum,
                par_sum = par_sum,
                leaf_a_ns = ab.l.ns,
                leaf_b_ns = ab.r.ns,
                leaf_c_ns = cd.l.ns,
                leaf_d_ns = cd.r.ns,
                scope_enter_ns = scope_t1 - scope_t0,
                inside_scope_ns = scope_t2 - scope_t1
            }
    """.trimIndent()

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `seq_fold scales across cores under LoomParallel`() {
        val result = MezzeTestHelper.runJit(program)
        val record = result as com.mezze.interpreter.values.MezzeRecord
        fun long(name: String): Long = when (val v = record.getField(name)) {
            is Long -> v
            is Number -> v.toLong()
            else -> error("$name is not a number: $v")
        }
        val seqNs = long("seq_ns")
        val parNs = long("par_ns")
        val seqSum = long("seq_sum")
        val parSum = long("par_sum")
        val leafANs = long("leaf_a_ns")
        val leafBNs = long("leaf_b_ns")
        val leafCNs = long("leaf_c_ns")
        val leafDNs = long("leaf_d_ns")
        val leaves = listOf(leafANs, leafBNs, leafCNs, leafDNs).sorted()
        val leafMin = leaves.first() / 1_000_000
        val leafMax = leaves.last() / 1_000_000
        val leafMedian = leaves[2] / 1_000_000
        val ratio = parNs.toDouble() / seqNs.toDouble()
        val seqMs = seqNs / 1_000_000
        val parMs = parNs / 1_000_000
        println("[SeqFold LoomParallel scaling] seq=${seqMs}ms  par=${parMs}ms  ratio(par/seq)=${"%.2f".format(ratio)}×  " +
            "seq_sum=$seqSum  par_sum=$parSum")
        println("[Per-leaf] min=${leafMin}ms  median=${leafMedian}ms  max=${leafMax}ms  spread=${leafMax - leafMin}ms  " +
            "leaves(ms)=${leaves.map { it / 1_000_000 }}")
        val scopeEnterMs = long("scope_enter_ns") / 1_000_000
        val insideScopeMs = long("inside_scope_ns") / 1_000_000
        println("[Scope] scope_enter=${scopeEnterMs}ms  inside_scope=${insideScopeMs}ms  " +
            "(scope_enter includes fjpoolInit which creates N worker threads + enters context per worker)")
        assertEquals(seqSum, parSum, "sums must be identical between sequential and parallel runs")

        // Real parallelism: on a machine with >= 2 cores, par should beat seq.
        // Guard is loose (< 1.0) so any real speedup passes; a tighter guard
        // would flake under load.
        assert(ratio < 1.0) {
            "expected parallel < sequential under LoomParallel on a multi-core box; " +
            "seq=${seqMs}ms par=${parMs}ms (${"%.2f".format(ratio)}×)"
        }
    }

    private fun assertEquals(expected: Long, actual: Long, msg: String) {
        assert(expected == actual) { "$msg (expected=$expected, actual=$actual)" }
    }
}
