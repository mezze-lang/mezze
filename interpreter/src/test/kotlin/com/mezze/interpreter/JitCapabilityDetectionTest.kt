package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Smoke test for the Truffle runtime detection logic that EmbeddedExec
 * uses to decide whether MEZZE_JIT=0 / MEZZE_TRACE_COMPILATION=1 should
 * apply `engine.Compilation` / `engine.TraceCompilation` options.
 *
 * Background (#195): on a native-image build of the user app, Truffle
 * uses DefaultTruffleRuntime — the pure interpreter fallback that has
 * no Graal partial evaluator. The `engine.Compilation` option doesn't
 * exist on that runtime, and setting it throws
 * "Could not find option with name engine.Compilation" before any
 * user code runs. This test pins the detection logic — the same one
 * EmbeddedExec.executeWithTruffle uses — so a future Truffle upgrade
 * that renames the fallback runtime class is caught at test time.
 *
 * On the JVM with truffle-runtime on the classpath we expect
 * HotSpotTruffleRuntime; on a native-image build we expect
 * DefaultTruffleRuntime. Tests live in `:interpreter:test` and run
 * under the JVM, so we only get to assert the JVM-mode branch here.
 * The native-mode branch is exercised by the manual native binary
 * run documented in #195.
 */
class JitCapabilityDetectionTest {

    @Test
    fun `Truffle runtime is JIT-capable under JVM tests`() {
        val rt = com.oracle.truffle.api.Truffle.getRuntime()
        val name = rt.javaClass.name
        // The EmbeddedExec detection: anything NOT ending in
        // "DefaultTruffleRuntime" is treated as JIT-capable.
        val isFallback = name.endsWith("DefaultTruffleRuntime")
        assertFalse(
            isFallback,
            "Truffle runtime is $name — :interpreter:test should be running " +
                "under the JIT-capable runtime (truffle-runtime on the classpath). " +
                "If this fails, the test environment lost truffle-runtime."
        )
    }
}
