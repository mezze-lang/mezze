package com.mezze.interpreter

import jdk.jfr.Configuration
import jdk.jfr.Recording
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths

/**
 * Profiling harness for #95.
 *
 * Runs a representative-shape Mezze workload many times inside a single
 * Context (so we measure steady-state execution, not parse/typecheck/compile).
 * Designed to be run under JFR to produce a flame graph of the hot paths.
 *
 * To capture a JFR recording, run with:
 *   ./gradlew :interpreter:test --tests "com.mezze.interpreter.HotPathProfileTest" \
 *     -Dorg.gradle.jvmargs="-XX:+FlightRecorder -XX:StartFlightRecording=duration=30s,filename=/tmp/mezze_hotpath.jfr,settings=profile"
 *
 * Then convert to folded stacks for flame graphs:
 *   jfr print --events jdk.ExecutionSample /tmp/mezze_hotpath.jfr
 */
class HotPathProfileTest {

    /**
     * A workload shaped like real Mezze code: mixed DS ops + arithmetic +
     * pattern matching + recursion. Avoids the synthetic "every line is
     * map::get" worst case which exaggerates dispatch overhead.
     */
    private val workload = """
        use std::collections::seq
        use std::collections::map
        use std::int

        # Recursive build with non-trivial arithmetic per iteration
        let build_data: {i: Int, n: Int, m: Map Int Int} -> Map Int Int = {i, n, m} ->
          if i == n then m
          else build_data {
            i = i + 1,
            n = n,
            m = map::insert {m = m, k = i, v = i * i + 1}
          }

        # Fold with pattern match on Option — common shape in real code
        let unwrap = {opt} ->
          match opt is
            Some {val} -> val
            None -> 0

        let sum_squares: {m: Map Int Int, i: Int, n: Int, acc: Int} -> Int = {m, i, n, acc} ->
          if i == n then acc
          else sum_squares {
            m = m,
            i = i + 1,
            n = n,
            acc = acc + unwrap {opt = map::get {m = m, k = i}}
          }

        # Stdlib HOF pipeline — exercises native dispatch + lambda invoke
        let pipeline_sum = {xs} ->
          seq::fold {
            xs = seq::filter {
              xs = seq::map {xs = xs, f = {x} -> x * 2 + 1},
              p = {x} -> x > 100
            },
            init = 0,
            f = {acc, x} -> acc + x
          }

        let n = 1000
        let m = build_data {i = 0, n = n, m = map::empty {}}
        let s = sum_squares {m = m, i = 0, n = n, acc = 0}

        let xs = seq::range {start = 0, end = n}
        let p = pipeline_sum {xs = xs}

        s + p
    """.trimIndent()

    private fun makeContext(): Context = Context.newBuilder("mezze")
        .allowAllAccess(true)
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    @Test
    @Timeout(120)
    fun `warm up and run profiling workload`() {
        val ctx = makeContext()
        try {
            val src = Source.newBuilder("mezze", workload, "hotpath.mz").build()

            // Warm up — let Truffle compile
            repeat(50) { ctx.eval(src) }

            // Start JFR for the main loop only (skip warmup samples).
            val recording = Recording(Configuration.getConfiguration("profile")).apply {
                name = "mezze-hotpath"
                setToDisk(true)
                setDestination(Paths.get("/tmp/mezze_hotpath.jfr"))
                start()
            }

            try {
                val start = System.nanoTime()
                var iters = 0
                val deadline = start + 30_000_000_000L  // 30s
                while (System.nanoTime() < deadline) {
                    ctx.eval(src)
                    iters++
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                val perIterMs = elapsedMs.toDouble() / iters
                println("=== Profiling workload ===")
                println("  Iterations: $iters")
                println("  Total: ${elapsedMs}ms")
                println("  Per iter: %.3fms".format(perIterMs))
            } finally {
                recording.stop()
                recording.close()
                println("  JFR recording: /tmp/mezze_hotpath.jfr")
            }
        } finally {
            ctx.close()
        }
    }
}
