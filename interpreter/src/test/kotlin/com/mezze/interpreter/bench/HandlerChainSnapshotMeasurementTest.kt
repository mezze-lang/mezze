package com.mezze.interpreter.bench

import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Direct measurement of HandlerChainRegistry.snapshotHandlerChainEntries() at
 * different chain depths. Used to decide whether it's worth caching /
 * versioning the per-thread handler chain to skip the array copy on
 * scopeSpawn (spawn-under-handlers hot path).
 *
 * Empty chain returns a singleton EMPTY_ENTRY_CHAIN — allocation-free.
 * Non-empty chains allocate an Object[] of the current chain size and
 * copy element references in via ArrayDeque.toArray. That copy is what
 * we want to price.
 *
 * The bench is Java-level (no Mezze eval) so the signal isn't buried
 * under Truffle/dispatch overhead. Each recorded per-iteration cost is
 * "one snapshot", which is what a scoped spawn pays today.
 *
 * NOTE: this class name matches `*Bench*` so under normal build wiring
 * it would route to `:interpreter:bench`. That task is currently
 * NO-SOURCE-broken (pre-existing, both this branch and main). We route
 * around it by exposing each measurement as a plain JUnit @Test that
 * prints its numbers to stdout — read them from the test log. When
 * `:interpreter:bench` is fixed this file should switch back to using
 * BenchRecorder.bench { ... }.
 */
class HandlerChainSnapshotMeasurementTest {

    // Cheap sentinel handler + effect hash — content doesn't matter for
    // the snapshot cost; only depth does.
    private val handler = MezzeRecord.empty()
    private val effectHash = Hash.ZERO

    @AfterEach
    fun clearChain() {
        // Defensive: reset the ThreadLocal chain between tests so state
        // from one bench doesn't leak into the next.
        HandlerChainRegistry.truncateHandlerChainTo(0)
    }

    private fun measure(label: String, depth: Int) {
        val warmup = 50_000
        val iters = 200_000
        val runs = 5

        repeat(warmup) { HandlerChainRegistry.snapshotHandlerChainEntries() }

        val samples = LongArray(runs)
        for (r in 0 until runs) {
            var sink = 0L
            val t0 = System.nanoTime()
            for (i in 0 until iters) {
                val chain = HandlerChainRegistry.snapshotHandlerChainEntries()
                sink = sink xor chain.size.toLong()
            }
            val t1 = System.nanoTime()
            samples[r] = (t1 - t0) / iters
            // Fold sink somewhere the JIT can't prove is dead.
            if (sink == Long.MIN_VALUE) println("impossible: $sink")
        }
        samples.sort()
        val min = samples[0]
        val med = samples[runs / 2]
        val max = samples[runs - 1]
        println("[snapshot-bench] depth=$depth  min=${min}ns  med=${med}ns  max=${max}ns  ($label)")
    }

    @Test
    fun `snapshot at chain depth 0 (empty — returns singleton)`() {
        // Baseline: no handlers pushed. Should return EMPTY_ENTRY_CHAIN
        // singleton, no allocation, no ArrayDeque.toArray call. This
        // is the "top-level Async.spawn" case — should be near zero.
        measure("empty singleton", 0)
    }

    @Test
    fun `snapshot at chain depth 1 (one perform)`() {
        // Typical: one perform installed, then spawn inside. Every
        // scoped_with body running under a single handler chain hits
        // this shape.
        withChainDepth(1) { measure("one perform installed", 1) }
    }

    @Test
    fun `snapshot at chain depth 4 (nested perform)`() {
        // Realistic worst-case-ish: user code with a few nested
        // perform layers (Reader inside Async inside LoomAsync inside
        // an Exception handler, etc).
        withChainDepth(4) { measure("nested performs", 4) }
    }

    @Test
    fun `snapshot at chain depth 16 (pathological)`() {
        // Deep chain — well past what real user code should build up.
        // Included to show the linear-in-depth trend.
        withChainDepth(16) { measure("deep chain", 16) }
    }

    private inline fun withChainDepth(depth: Int, block: () -> Unit) {
        repeat(depth) { HandlerChainRegistry.pushHandlerOnChain(handler, effectHash) }
        try {
            block()
        } finally {
            repeat(depth) { HandlerChainRegistry.popHandlerOffChain() }
        }
    }
}
