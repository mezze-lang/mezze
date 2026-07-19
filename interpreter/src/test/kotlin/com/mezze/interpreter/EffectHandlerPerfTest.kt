package com.mezze.interpreter

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.bench.BenchRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Effect-handler dispatch microbenchmarks.
 *
 * Goal: per-op dispatch cost for the new effect-handler runtime.
 * Not a true microbenchmark — JUnit + Polyglot Context startup adds noise
 * that masks <2x differences. What we want is "is `perform Handler in
 * tight_loop` close to a bare tight_loop, or 100x slower?" and to detect
 * regressions ≥10%.
 *
 * Scenarios:
 *   1. Pure arithmetic, no effects. Baseline interpreter cost.
 *   2. `perform Handler in body` with no op-calls inside. Per-perform cost
 *      (fiber alloc + chain push/pop + driver run/done).
 *   3. Op-calls inside a perform. Per-op cost (Loom yield + handler call +
 *      resume).
 *   4. Abort fast-path. Hot path for Exception-style handlers.
 *   5. Async.spawn + await round-trip. Cross-thread fiber coordination.
 *
 * Runs as part of `:interpreter:bench`. Results recorded via
 * [BenchRecorder] and aggregated into docs/runtime/perf-baseline.md.
 */
class EffectHandlerPerfTest {

    // Bench uses runJit() so measurements reflect production (JIT-on) perf.
    private fun eval(source: String): Any = MezzeTestHelper.runJit(source)

    private fun bench(label: String, iters: Int = 1, body: () -> Unit) {
        BenchRecorder.bench(
            category = "effect-handler dispatch",
            label = label,
            iters = iters,
            body = body,
        )
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

        bench("baseline arith loop", iters = 100_000) {
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

        bench("perform entry+exit (1 op inside)", iters = 1_000) {
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
    fun `op-call dispatch 500 ops one perform`() {
        // 500 op-calls inside one perform — Loom yield/resume cycle per op-call.
        // Higher counts stack-overflow under the current op-call path (NOT a
        // true tail call through Counter.tick — the resume returns into the
        // body's recursion, growing the stack). 500 is the largest power-of-2
        // that fits today. Improving this is a separate perf goal (#TBD).
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

            perform Echo in body { n = 500, acc = 0 }
        """.trimIndent()

        bench("op-call dispatch (500 ops, 1 perform)", iters = 500) {
            val r = eval(source)
            assertEquals(500L, r)
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

        bench("abort fast-path (1k performs)", iters = 1_000) {
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
            use std::effects::async::{ Async, LoomAsync, await }

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else do
                    let v = perform LoomAsync in do
                        let t = Async.spawn { f = {} -> 1 }
                        match await { p = t } is
                            Ok { val } -> val
                            Err _ -> -1
                    outer { n = n - 1, acc = acc + v }

            outer { n = 100, acc = 0 }
        """.trimIndent()

        bench("Async.spawn + await (100 tasks)", iters = 100) {
            val r = eval(source)
            assertEquals(100L, r)
        }
    }
}
