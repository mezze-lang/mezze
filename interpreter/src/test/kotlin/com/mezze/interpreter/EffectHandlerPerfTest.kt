package com.mezze.interpreter

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import kotlin.system.measureNanoTime

/**
 * Performance smoke test for the new effect-handler runtime (task #79).
 *
 * Goal: get rough order-of-magnitude numbers for the cost of effect-handler
 * dispatch on a few canonical paths. Not a microbenchmark — JUnit + Polyglot
 * Context startup adds enough noise that we shouldn't read >2x differences
 * as meaningful. What we want is "is `perform Handler in tight_loop` close
 * to a bare tight_loop, or 100x slower?"
 *
 * Three scenarios:
 *   1. Pure arithmetic, no effects. Establishes baseline interpreter cost.
 *   2. Same loop body, wrapped in `perform Handler in body` with no op-calls
 *      inside. Measures the per-perform fiber-allocation + driver cost.
 *   3. Same loop, but each iteration calls an op whose handler is
 *      fall-through (resume the body). Measures per-op-dispatch cost
 *      (Loom yield + handler call + resume).
 *
 * Disabled by default — these run as part of #79 / #88 audits, not in the
 * regular CI gating suite. Output to stderr for ad-hoc inspection.
 */
@Disabled("Run manually for #79 / #88; not part of the gating suite. " +
          "Run with: ./gradlew :interpreter:test --tests com.mezze.interpreter.EffectHandlerPerfTest")
class EffectHandlerPerfTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun bench(label: String, body: () -> Unit) {
        // Warmup
        repeat(3) { body() }
        // Measurement
        val runs = 5
        val times = LongArray(runs)
        for (i in 0 until runs) {
            times[i] = measureNanoTime { body() }
        }
        times.sort()
        val median = times[runs / 2]
        val min = times.first()
        System.err.println("[perf] $label: min=${min / 1_000_000}ms  median=${median / 1_000_000}ms  (runs=$runs)")
    }

    /**
     * Baseline: a tight arithmetic loop in pure Mezze, no effects, no
     * function calls. Sets the floor for what the interpreter can do
     * before any effect machinery kicks in.
     */
    @Test
    fun `baseline pure arithmetic loop 100k`() {
        val source = """
            let loop : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else loop { n = n - 1, acc = acc + n }

            loop { n = 100000, acc = 0 }
        """.trimIndent()

        bench("baseline_arith_100k") {
            val r = eval(source)
            assertEquals(5000050000L, r)
        }
    }

    /**
     * Pure perform overhead: a `perform Handler in body` where body does no
     * op-calls. Measures fiber allocation + chain push/pop + driver run/done
     * round-trip per perform, divided across 0 ops.
     */
    @Test
    fun `pure perform overhead 1k entries`() {
        // 1000 performs, each invokes Counter.tick exactly once. Measures
        // perform-entry/exit cost (fiber alloc + chain push/pop + driver
        // run/done round-trip), amortised over a single op-call per perform.
        val source = """
            effect Counter where {
                tick: {} -> Int <Counter>
            }

            impl Counter for Bump where {
                tick = {} -> resume { val = 1 }
            }

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else outer {
                    n = n - 1,
                    acc = acc + (perform Bump in Counter.tick {})
                }

            outer { n = 1000, acc = 0 }
        """.trimIndent()

        bench("pure_perform_1k") {
            val r = eval(source)
            assertEquals(1000L, r)
        }
    }

    /**
     * Per-op-dispatch overhead: one `perform Handler in body` with N op-calls
     * inside body. The fiber yields + resumes once per op. Divide reported
     * time by N for per-op cost.
     */
    @Test
    fun `op-call dispatch 1k ops one perform`() {
        // 1000 op-calls inside one perform — Loom yield/resume cycle per
        // op-call. (10k blows the stack because Mezze recursion isn't
        // tail-call optimised; this is a separate concern from
        // effect-handler perf.)
        val source = """
            effect Counter where {
                tick: { v: Int } -> Int <Counter>
            }

            impl Counter for Echo where {
                tick = { v } -> resume { val = v + 1 }
            }

            let body : { n: Int, acc: Int } -> Int <Counter> = { n, acc } ->
                if n <= 0 then acc
                else body { n = n - 1, acc = Counter.tick { v = acc } }

            perform Echo in body { n = 1000, acc = 0 }
        """.trimIndent()

        bench("op_dispatch_1k") {
            val r = eval(source)
            assertEquals(1000L, r)
        }
    }

    /**
     * Per-op-dispatch overhead with abort: one perform whose body always
     * aborts on the first op. Measures the abort-fast-path which is the
     * hot path for Exception-style handlers.
     */
    @Test
    fun `abort fast path 1k performs`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else do
                    let _ = perform ExceptionToResult in Exception.throw { error = "x" }
                    outer { n = n - 1, acc = acc + 1 }

            outer { n = 1000, acc = 0 }
        """.trimIndent()

        bench("abort_fastpath_1k") {
            val r = eval(source)
            assertEquals(1000L, r)
        }
    }

    /**
     * Async.spawn + await on small task. Stresses cross-thread + fiber
     * coordination. One spawn per iteration; measures realistic
     * task-pipeline overhead.
     */
    @Test
    fun `async spawn-await 100 tasks`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else do
                    let v = perform LoomAsync in do
                        let t = Async.spawn { f = {} -> 1 }
                        Async.await { p = t }
                    outer { n = n - 1, acc = acc + v }

            outer { n = 100, acc = 0 }
        """.trimIndent()

        bench("async_spawn_await_100") {
            val r = eval(source)
            assertEquals(100L, r)
        }
    }
}
