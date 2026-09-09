package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying the generalized PIC for non-Mut effect op-calls.
 *
 * Before: non-Mut op-calls always went through chain dispatch:
 *   snapshotHandlerChainEntries → array alloc → chain walk → dispatch
 *   fiber acquire → resume/abort setup → IndirectCallNode call → cleanup
 *
 * After: PIC caches the handler + DirectCallNode with forceInlining.
 *   peekHandlerForEffect → identity check → DirectCallNode call.
 *   No chain snapshot, no dispatch fiber, no resume/abort plumbing.
 */
class EffectPicIntegrationTest {

    private Object eval(String src) {
        return MezzeTestHelper.runJit(src);
    }

    /**
     * Custom effect with a simple resume handler — steady-state loop.
     * Single perform, many ops inside. Measures per-op dispatch cost
     * when the handler is stable (PerformD.doCached hot path).
     */
    @Test
    void customEffectSteadyState() {
        var source = """
            effect Counter where {
                tick: { v: Int } -> Int <Counter>
            }

            impl Counter for Inc where {
                tick = { v } -> resume { val = v + 1 }
            }

            let body : { n: Int, acc: Int } -> Int <Counter> = { n, acc } ->
                if n <= 0 then acc
                else body { n = n - 1, acc = Counter.tick { v = acc } }

            let main = {} -> do
                perform Inc in body { n = 5000, acc = 0 }
            main {}
            """;

        // Warmup
        for (int i = 0; i < 5; i++) {
            eval(source);
        }

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            Object result = eval(source);
            long elapsed = System.nanoTime() - t0;
            assertEquals(5000L, result);
            if (elapsed < best) best = elapsed;
        }
        double usPerOp = best / 1000.0 / 5000.0;
        System.out.printf("[PIC] custom-effect steady-state (5000 ops): %.2f us/op  (%.2f ms)%n",
            usPerOp, best / 1_000_000.0);
    }

    /**
     * Exception handler abort — each iteration installs handler and aborts.
     * Tests the abort-catch handling path.
     */
    @Test
    void exceptionAbort() {
        var source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let outer : { n: Int, acc: Int } -> Int = { n, acc } ->
                if n <= 0 then acc
                else do
                    let _ = perform ExceptionToResult in Exception.throw { error = "x" }
                    outer { n = n - 1, acc = acc + 1 }

            let main = {} -> do
                outer { n = 1000, acc = 0 }
            main {}
            """;

        // Warmup
        for (int i = 0; i < 5; i++) {
            eval(source);
        }

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            Object result = eval(source);
            long elapsed = System.nanoTime() - t0;
            assertEquals(1000L, result);
            if (elapsed < best) best = elapsed;
        }
        double usPerPerform = best / 1000.0 / 1000.0;
        System.out.printf("[PIC] exception abort (1000 performs): %.2f us/perform  (%.2f ms)%n",
            usPerPerform, best / 1_000_000.0);
    }

    /**
     * Multi-effect: two effects in scope, tight loop inside.
     * Tests PIC per-effect hash discrimination.
     */
    @Test
    void multiEffectSteadyState() {
        var source = """
            effect Counter where {
                tick: { v: Int } -> Int <Counter>
            }

            impl Counter for Inc where {
                tick = { v } -> resume { val = v + 1 }
            }

            effect Logger where {
                log: { v: Int } -> Int <Logger>
            }

            impl Logger for Echo where {
                log = { v } -> resume { val = v }
            }

            let body : { n: Int, acc: Int } -> Int <Counter, Logger> = { n, acc } ->
                if n <= 0 then acc
                else do
                    let a = Counter.tick { v = acc }
                    let b = Logger.log { v = a }
                    body { n = n - 1, acc = b }

            let main = {} -> do
                perform Inc in
                    perform Echo in
                        body { n = 5000, acc = 0 }
            main {}
            """;

        // Warmup
        for (int i = 0; i < 5; i++) {
            eval(source);
        }

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            Object result = eval(source);
            long elapsed = System.nanoTime() - t0;
            assertEquals(5000L, result);
            if (elapsed < best) best = elapsed;
        }
        double usPerOp = best / 1000.0 / 10000.0; // 5000 iters × 2 ops
        System.out.printf("[PIC] multi-effect steady-state (5000×2=10000 ops): %.2f us/op  (%.2f ms)%n",
            usPerOp, best / 1_000_000.0);
    }

    /**
     * Per-perform overhead: many short-lived performs, 1 op each.
     * Tests the perform entry/exit + handler chain push/pop cost.
     */
    @Test
    void performEntryExit() {
        var source = """
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

            let main = {} -> do
                outer { n = 2000, acc = 0 }
            main {}
            """;

        // Warmup
        for (int i = 0; i < 5; i++) {
            eval(source);
        }

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            Object result = eval(source);
            long elapsed = System.nanoTime() - t0;
            assertEquals(2000L, result);
            if (elapsed < best) best = elapsed;
        }
        double usPerPerform = best / 1000.0 / 2000.0;
        System.out.printf("[PIC] perform entry+exit (2000 × 1 op each): %.2f us/perform  (%.2f ms)%n",
            usPerPerform, best / 1_000_000.0);
    }
}
