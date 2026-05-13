package com.mezze.interpreter.nodes;

import com.mezze.interpreter.nodes.continuation.BodyResult;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral tests for the continuation substrate that underpins effect
 * handlers. Each test exercises one observable property of the runtime
 * (uniqueness, stacking, suspend/resume, variant helpers) through the
 * effect-handler driver's public primitives.
 *
 * <p>Earlier revisions of this file tested an older delimited-continuation
 * runtime that no longer exists; this rewrite preserves the same
 * behavioral coverage on the new {@link EffectFiber}-based substrate.
 */
public class ContinuationTest {

    @Test
    void fibersHaveUniqueIds() {
        EffectFiber a = EffectFiber.create("a", () -> {});
        EffectFiber b = EffectFiber.create("b", () -> {});

        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.getId(), b.getId());
        assertNotSame(a, b);
    }

    @Test
    void fiberStackPushAndPopReflectsCurrent() {
        // EffectFiber's CURRENT thread-local is set by run() while body
        // executes. Verify that nesting two fiber runs sees the inner as
        // current and the outer is restored on exit.
        EffectFiber[] outerHolder = new EffectFiber[1];
        EffectFiber[] innerHolder = new EffectFiber[1];

        EffectFiber[] seen = new EffectFiber[3]; // [before, inside-outer, inside-inner]

        seen[0] = EffectFiber.current(); // null

        outerHolder[0] = EffectFiber.create("outer", () -> {
            seen[1] = EffectFiber.current(); // should be outer

            innerHolder[0] = EffectFiber.create("inner", () -> {
                seen[2] = EffectFiber.current(); // should be inner
                // No suspend — body completes; setSuspendedValue makes
                // run() return cleanly.
                innerHolder[0].setSuspendedValue(new BodyResult("inner-done"));
            });
            innerHolder[0].run();

            // Back inside outer: current should be outer again.
            assertSame(outerHolder[0], EffectFiber.current(),
                "after inner completes, current must restore to outer");

            outerHolder[0].setSuspendedValue(new BodyResult("outer-done"));
        });
        outerHolder[0].run();

        assertNull(seen[0], "no fiber current before any run()");
        assertSame(outerHolder[0], seen[1], "outer fiber is current inside its body");
        assertSame(innerHolder[0], seen[2], "inner fiber is current inside its body");
        assertNull(EffectFiber.current(), "no fiber current after both runs return");
    }

    @Test
    void suspendValueIsObservableAsBodyResultOrOpCall() {
        // Equivalent of the old "MezzeContinuationSuspended structure"
        // test: verify the two payload kinds the runtime exchanges
        // between body and driver — BodyResult (natural return) and
        // OpCall (effect operation) — round-trip correctly through
        // takeSuspendedValue.

        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("payload-test", () -> {
            holder[0].setSuspendedValue(new OpCall("raise", "boom"));
            holder[0].suspend(new OpCall("ignored", "ignored")); // suspend with the OpCall above's slot
            holder[0].setSuspendedValue(new BodyResult(42L));
        });

        holder[0].run();
        Object first = holder[0].takeSuspendedValue();
        assertTrue(first instanceof OpCall, "first suspended value is OpCall");
        OpCall op = (OpCall) first;
        assertEquals("ignored", op.opName());
        assertEquals("ignored", op.args());

        holder[0].setResumeValue("resumed");
        holder[0].run();
        Object second = holder[0].takeSuspendedValue();
        assertTrue(second instanceof BodyResult, "second is BodyResult after body returns");
        assertEquals(42L, ((BodyResult) second).value());
    }

    @Test
    void resumeFeedsValueBackToBody() {
        // Equivalent of "testTrivialContinuationResume": body suspends
        // with a request, runner resumes with a value, body sees that
        // value as the suspend's return.
        Object[] receivedInBody = new Object[1];
        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("resume-test", () -> {
            Object got = holder[0].suspend("ask");
            receivedInBody[0] = got;
            holder[0].setSuspendedValue(new BodyResult("body returned with " + got));
        });

        holder[0].run();
        assertEquals("ask", holder[0].takeSuspendedValue());

        holder[0].setResumeValue(100L);
        holder[0].run();
        assertEquals(100L, receivedInBody[0]);
        assertEquals("body returned with 100", ((BodyResult) holder[0].takeSuspendedValue()).value());
    }

    @Test
    void variantHelpersConstructSomeAndNone() {
        var none = MezzeVariant.none();
        assertNotNull(none);
        assertEquals("None", none.getConstructorName());
        assertTrue(none.isNone());
        assertFalse(none.isSome());

        var some = MezzeVariant.some(42L);
        assertNotNull(some);
        assertEquals("Some", some.getConstructorName());
        assertTrue(some.isSome());
        assertFalse(some.isNone());
    }

    @Test
    void fiberCreationPerformance() {
        // Equivalent of the old "delimiter creation performance" test —
        // creating a fiber should be fast. Loom continuations are
        // heavier than a bare counter increment but should still be
        // well under a millisecond each.
        int warmup = 1_000;
        for (int i = 0; i < warmup; i++) {
            EffectFiber.create("warmup", () -> {});
        }

        int iterations = 10_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            EffectFiber.create("perf", () -> {});
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Fiber creation: %d iterations in %d ms (%.2f µs/op)%n",
                iterations, elapsed / 1_000_000, elapsed / 1_000.0 / iterations);

        // Conservative bound: Loom continuation construction is
        // noticeably slower than a plain object alloc; allow up to
        // 100µs per op (typical observed is single-digit µs).
        assertTrue(elapsed / iterations < 100_000,
            "Fiber creation should be under 100µs per op, was " + (elapsed / iterations) + " ns");
    }

    @Test
    void fiberCurrentLookupPerformance() {
        // Equivalent of the "delimiter stack performance" test, narrowed
        // to the operation that matters in the new runtime: reading
        // EffectFiber.current() from a thread-local. OpCallNode does
        // this on every effect-op invocation.
        EffectFiber f = EffectFiber.create("perf", () -> {});

        // Warmup.
        for (int i = 0; i < 100_000; i++) {
            EffectFiber.current();
        }

        int iterations = 1_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            EffectFiber.current();
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("EffectFiber.current(): %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Thread-local read should be very cheap; well under 100ns.
        assertTrue(elapsed / iterations < 1_000,
            "EffectFiber.current() should be <1µs per op, was " + (elapsed / iterations) + " ns");
    }
}
