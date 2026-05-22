package com.mezze.interpreter.bench

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Microbenchmarks for the concrete Async/Channel/Mutex operations called
 * out in the perf budget (docs/runtime/perf-baseline.md). Each test
 * pushes one budget item and records it via [BenchRecorder].
 *
 * These are written to be insensitive to absolute interpreter overhead
 * (always-loop-1000-iters-inside-one-eval) so that per-iteration costs
 * surface even with JUnit + Polyglot startup in the timing window.
 *
 * Class name matches `*Bench*` → picked up by `:interpreter:bench`.
 */
class AsyncMicroBench {

    // Bench uses runJit() so measurements reflect production (JIT-on) perf.
    private fun eval(source: String): Any = MezzeTestHelper.runJit(source)

    private fun bench(category: String, label: String, iters: Int, body: () -> Unit) {
        BenchRecorder.bench(category, label, iters = iters, body = body)
    }

    // ─── Async core ──────────────────────────────────────────────────────

    @Test
    fun `Async spawn await fast complete`() {
        // Spawn a thunk that returns immediately. The await commonly hits the
        // "already done" fast path (no parking). Target: this should be the
        // cheap end of spawn+await — most of the cost is virtual thread
        // creation + result handoff.
        val source = """
            use std::effects::async::{Async, LoomAsync}

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else do
                    let v = perform LoomAsync in do
                        let t = Async.spawn { f = {} -> 1 }
                        Async.await { p = t }
                    outer { n = n - 1, acc = acc + v }

            outer { n = 200, acc = 0 }
        """.trimIndent()
        bench("Async core (Loom)", "spawn + await (200 fast tasks)", 200) {
            val r = eval(source)
            assertEquals(200L, r)
        }
    }

    @Test
    fun `Async sleep tiny`() {
        // Many tiny sleeps in a single perform. Stresses the time-source
        // path + Async dispatch, not actual virtual-thread parking (sleep 0
        // returns immediately on most impls but still goes through dispatch).
        val source = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                let body : { n: Int } -> {} <Async> = { n } ->
                    if n <= 0 then {}
                    else do
                        Async.sleep { ms = 0 }
                        body { n = n - 1 }
                body { n = 200 }
                "done"
        """.trimIndent()
        bench("Async core (Loom)", "sleep(0) × 200 in one perform", 200) {
            val r = eval(source)
            assertEquals("done", r)
        }
    }

    // ─── Async concurrency ───────────────────────────────────────────────

    @Test
    fun `spawn N detached no await`() {
        // Profile-friendly: only the spawn cost, no await coord. Spawned
        // tasks return immediately; we don't observe their results. Each
        // spawn allocates: Object[] (closure+arg), MezzeRecord[0] chain
        // snapshot, MezzePromise×2, two lambdas, Thread, ConcurrentLinkedQueue
        // entry. After this we measure the join-via-Async.all so the
        // process doesn't exit before tasks complete.
        val n = 2000
        val source = """
            use std::effects::async::{ Async, LoomAsync }
            use std::collections::seq

            perform LoomAsync in do
                let ps = seq::map {
                    xs = seq::range { start = 0, end = $n },
                    f = { x } -> Async.spawn { f = {} -> x },
                }
                Async.all { ps = ps }
                $n
        """.trimIndent()
        BenchRecorder.bench(
            category = "Async core (Loom)",
            label = "spawn $n detached, await-all at end",
            iters = n,
            warmup = 0,
            runs = 1,
        ) {
            val r = eval(source)
            assertEquals(n.toLong(), r)
        }
    }

    @Test
    fun `spawn many lightweight tasks`() {
        // Ceiling probe for how cheap Mezze's Async.spawn is — how many
        // tasks can we spin up and join before things break? Each task
        // does trivial work (returns its index); the bench measures the
        // spawn-N-then-await-all round trip. Scaled down from 1M to a
        // value that still finishes in seconds today (we expect per-spawn
        // cost to drop with future work).
        val n = 2000
        val source = """
            use std::effects::async::{ Async, LoomAsync }
            use std::collections::seq

            perform LoomAsync in do
                let ps = seq::map {
                    xs = seq::range { start = 1, end = $n + 1 },
                    f = { x } -> Async.spawn { f = {} -> x },
                }
                let results = Async.all { ps = ps }
                seq::fold { xs = results, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()
        // sum 1..n
        val expected = (n.toLong() * (n + 1)) / 2
        BenchRecorder.bench(
            category = "Async core (Loom)",
            label = "spawn $n + await-all",
            iters = n,
            warmup = 0,
            runs = 1,
        ) {
            val r = eval(source)
            assertEquals(expected, r)
        }
    }

    @Test
    fun `non-tail recursion ceiling probe`() {
        // Direct recursion that is NOT in tail position — classic fib
        // shape but counting (so we can pick large N). Tests whether the
        // interpreter's per-frame Java overhead lets users do
        // O(N) recursive descent for moderate N.
        for (n in intArrayOf(100, 1_000, 10_000, 50_000)) {
            val source = """
                let count : { n: Int } -> Int = { n } ->
                    if n <= 0 then 0 else 1 + count { n = n - 1 }
                count { n = $n }
            """.trimIndent()
            try {
                BenchRecorder.bench(
                    category = "Recursion ceiling",
                    label = "non-tail count(n=$n)",
                    iters = n,
                    warmup = 0,
                    runs = 1,
                ) {
                    val r = eval(source)
                    assertEquals(n.toLong(), r)
                }
            } catch (e: Throwable) {
                System.err.println("[bench] non-tail count $n FAILED: ${e.message?.take(120)}")
                break
            }
        }
    }

    @Test
    fun `mutual recursion ceiling probe`() {
        // Cross-function tail recursion: even/odd ping-pong. SHOULD work
        // to any depth if cross-function tail calls are properly
        // trampolined; will SO if they recurse through Java frames.
        // Ladder identifies the actual ceiling.
        for (n in intArrayOf(1_000, 10_000, 100_000, 1_000_000)) {
            val source = """
                let even : { n: Int } -> Bool = { n } ->
                    if n == 0 then True else odd { n = n - 1 }
                let odd : { n: Int } -> Bool = { n } ->
                    if n == 0 then False else even { n = n - 1 }
                even { n = $n }
            """.trimIndent()
            val expected = (n % 2) == 0
            try {
                BenchRecorder.bench(
                    category = "Tail-call ceiling",
                    label = "mutual rec (even/odd) n=$n",
                    iters = n,
                    warmup = 0,
                    runs = 1,
                ) {
                    val r = eval(source)
                    assertEquals(expected, r)
                }
            } catch (e: Throwable) {
                System.err.println("[bench] mutual rec $n FAILED: ${e.message?.take(120)}")
                break
            }
        }
    }

    @Test
    fun `bare seq map ceiling probe`() {
        // No Async, no spawn — just seq::map with a trivial closure, to
        // isolate whether seq::map itself blows the stack at large N
        // (it shouldn't — it's a native Java for-loop).
        for (n in intArrayOf(1_000, 10_000, 50_000, 200_000, 1_000_000)) {
            val source = """
                use std::collections::seq
                let xs = seq::range { start = 1, end = $n + 1 }
                let ys = seq::map { xs = xs, f = { x } -> x + 1 }
                seq::fold { xs = ys, init = 0, f = { acc, x } -> acc + x }
            """.trimIndent()
            // sum 2..n+1 = (n*(n+3))/2... easier: sum 1..n + n = n(n+1)/2 + n
            val expected = (n.toLong() * (n + 1)) / 2 + n
            try {
                BenchRecorder.bench(
                    category = "Seq baseline (no Async)",
                    label = "seq::map + fold ($n elems)",
                    iters = n,
                    warmup = 1,
                    runs = 1,
                ) {
                    val r = eval(source)
                    assertEquals(expected, r)
                }
            } catch (e: Throwable) {
                System.err.println("[bench] bare seq::map $n FAILED: ${e.message?.take(120)}")
                break
            }
        }
    }

    @Test
    fun `bare spawn no await ceiling probe`() {
        // Spawn N tasks via seq::map (the native iterative one), discard
        // results immediately. No Async.all — so we isolate spawn from
        // the await-many path. If this scales but the with-await one
        // doesn't, the stack growth is on the await side.
        for (n in intArrayOf(1_000, 5_000, 10_000, 50_000)) {
            val source = """
                use std::effects::async::{ Async, LoomAsync }
                use std::collections::seq

                let drive = {} -> do
                    let _ = seq::map {
                        xs = seq::range { start = 1, end = $n + 1 },
                        f = { x } -> Async.spawn { f = {} -> x },
                    }
                    $n
                perform LoomAsync in drive {}
            """.trimIndent()
            try {
                BenchRecorder.bench(
                    category = "Async core (Loom)",
                    label = "spawn $n no-await",
                    iters = n,
                    warmup = 0,
                    runs = 1,
                ) {
                    val r = eval(source)
                    assertEquals(n.toLong(), r)
                }
            } catch (e: Throwable) {
                System.err.println("[bench] bare spawn $n FAILED: ${e.message?.take(120)}")
                break
            }
        }
    }

    @Test
    fun `spawn ceiling probe`() {
        // Find where Mezze's Async.spawn ceiling actually is today.
        // Ladder: 1K → 5K → 10K. Stops at the first scale that fails.
        // Aspiration is 1M but stack-depth limits (#128) cap us much
        // sooner today.
        //
        // Heap: bench task uses 10GB so headroom isn't the constraint;
        // the limit is Truffle interpreter call stack from chained
        // closures, not memory.
        // Climb the ladder: stop wherever it breaks. The benchmark runs
        // each scale and records, then attempts the next. If one fails
        // (stack overflow or OOM), the earlier numbers still tell us
        // where the ceiling is today.
        //
        // Uses seq::range + seq::map + Async.all + seq::fold. This is
        // realistic user code shape but bound by stack-depth ceilings
        // around 10K today (#128: Mezze recursion isn't TCO'd through
        // closure-applying intermediaries even though seq::map itself
        // is native). The numbers we DO get show the per-spawn cost
        // dropping as JIT compiles the hot path.
        for (n in intArrayOf(1_000, 10_000, 50_000, 200_000, 1_000_000)) {
            val source = """
                use std::effects::async::{ Async, LoomAsync }
                use std::collections::seq

                perform LoomAsync in do
                    let ps = seq::map {
                        xs = seq::range { start = 1, end = $n + 1 },
                        f = { x } -> Async.spawn { f = {} -> x },
                    }
                    let results = Async.all { ps = ps }
                    seq::fold { xs = results, init = 0, f = { acc, x } -> acc + x }
            """.trimIndent()
            val expected = (n.toLong() * (n + 1)) / 2
            try {
                BenchRecorder.bench(
                    category = "Async core (Loom)",
                    label = "spawn $n + await-all (JIT)",
                    iters = n,
                    warmup = 0,
                    runs = 1,
                ) {
                    val r = eval(source)
                    assertEquals(expected, r)
                }
            } catch (e: Throwable) {
                System.err.println("[bench] spawn $n FAILED: ${e.message?.take(120)}")
                break
            }
        }
    }

    @Test
    fun `concurrent recursive fib three threads`() {
        // The same shape that surfaced the shared-buffer race (now fixed).
        // Three concurrent recursive fib calls — measures both spawn-spawn-
        // spawn cost and the underlying CallDirectScalarNode hot path. Pure
        // CPU, no I/O, so this is mostly call-dispatch + thread coord.
        val source = """
            use std::effects::async::{Async, LoomAsync}

            let fib = {n} ->
                if n <= 1 then n
                else fib { n = n - 1 } + fib { n = n - 2 }

            perform LoomAsync in do
                let p1 = Async.spawn { f = {} -> fib { n = 15 } }
                let p2 = Async.spawn { f = {} -> fib { n = 14 } }
                let p3 = Async.spawn { f = {} -> fib { n = 13 } }
                Async.await { p = p1 } + Async.await { p = p2 } + Async.await { p = p3 }
        """.trimIndent()
        // fib(15)=610, fib(14)=377, fib(13)=233 — sum = 1220
        bench("Async core (Loom)", "fib triple-spawn (n=15/14/13)", iters = 1) {
            val r = eval(source)
            assertEquals(1220L, r)
        }
    }

    // ─── Channel ─────────────────────────────────────────────────────────

    @Test
    fun `Channel send recv tight loop`() {
        // Single producer, single consumer, both on the same thread (no
        // park). Channel.send fills, Channel.recv drains. Measures the
        // buffered-channel hot path.
        val source = """
            use std::effects::concurrent::channel::{ Channel, BlockingChannel, chan_bounded }

            perform BlockingChannel in do
                let ch = chan_bounded { cap = 256 }
                let send_loop : { n: Int } -> {} <Channel> = { n } ->
                    if n <= 0 then {}
                    else do
                        Channel.send { ch = ch, value = n }
                        send_loop { n = n - 1 }
                let recv_loop : { n: Int, acc: Int } -> Int <Channel> = { n, acc } ->
                    if n <= 0 then acc
                    else recv_loop { n = n - 1, acc = acc + Channel.recv { ch = ch } }
                send_loop { n = 200 }
                recv_loop { n = 200, acc = 0 }
        """.trimIndent()
        // sum 1..200 = 20100
        bench("Channel (Loom)", "send + recv (200 ints, 256-cap buffer)", iters = 200) {
            val r = eval(source)
            assertEquals(20100L, r)
        }
    }

    // ─── Mutex ───────────────────────────────────────────────────────────

    @Test
    fun `Mutex lock unlock uncontended`() {
        // Single-thread lock/unlock churn — measures the cheap path. No
        // contention; should be in the nanoseconds-per-iter range under
        // JIT. We use with_lock so the benchmark is the lock-acquire +
        // protected-body + release cycle.
        //
        // Now safe to run with warmup since #129 is fixed; using the
        // default cadence so the number reflects JIT-warm behavior.
        val source = """
            use std::effects::concurrent::mutex::{ mutex, with_lock }

            let m = mutex {}
            let loop : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else loop {
                    n = n - 1,
                    acc = with_lock { mutex = m, f = {} -> acc + 1 },
                }
            loop { n = 500, acc = 0 }
        """.trimIndent()
        BenchRecorder.bench(
            category = "Mutex (Loom)",
            label = "with_lock × 500 (uncontended)",
            iters = 500,
        ) {
            val r = eval(source)
            assertEquals(500L, r)
        }
    }
}
