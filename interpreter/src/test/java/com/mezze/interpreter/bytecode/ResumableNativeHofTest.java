package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAN A CALLBACK-TAKING NATIVE RESUME MID-LOOP? The third piece of the
 * remaining Phase C work.
 *
 * <p>{@link BdslYieldAcrossNativeFrameTest} shows that a native HOF whose loop
 * state lives in JAVA LOCALS is lost the moment its callback suspends: the loop
 * runs to completion with continuations where values should be. The fix is to
 * keep the loop state on the HEAP so the native can be re-entered at index
 * {@code i} with the output accumulated so far.
 *
 * <p>This models the native `map` under that discipline and checks two things:
 * it completes correctly when the callback suspends on EVERY element, and the
 * non-suspending fast path allocates no state object at all.
 */
class ResumableNativeHofTest {

    /** Heap-held loop state — what a Java local cannot survive a suspend. */
    record PartialMap(long[] xs, Object[] out, int at, ContinuationResult pending) {}

    /**
     * A resumable `map`. Returns the finished array, or a {@link PartialMap}
     * carrying everything needed to pick the loop back up.
     */
    static Object resumableMap(long[] xs, Object[] out, int from, LongFunction<Object> cb) {
        for (int i = from; i < xs.length; i++) {
            Object r = cb.apply(xs[i]);
            if (r instanceof ContinuationResult cr) {
                return new PartialMap(xs, out, i, cr);   // park, state preserved
            }
            out[i] = r;
        }
        return out;
    }

    /** inner(x) = (yield "op:park") + 1 — the suspending guest callback. */
    private static MezzeBytecodeRootNode yieldingCallback() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            b.beginAddLong();
            b.beginYield();
            b.emitLoadConstant("op:park");
            b.endYield();
            b.emitLoadConstant(1L);
            b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    /** plain(x) = x + 10 — never suspends. */
    private static MezzeBytecodeRootNode plainCallback() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            b.beginAddLong();
            b.emitLoadArgument(0);
            b.emitLoadConstant(10L);
            b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aNativeHofResumesMidLoopWhenItsCallbackSuspends() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode cbRoot = yieldingCallback();
                long[] xs = { 1L, 2L, 3L };
                Object[] out = new Object[xs.length];
                LongFunction<Object> cb = x -> cbRoot.getCallTarget().call(new Object[] { x });

                int parks = 0;
                Object r = resumableMap(xs, out, 0, cb);
                while (r instanceof PartialMap pm) {
                    parks++;
                    assertEquals("op:park", pm.pending().getResult(), "the parked op");
                    // The scheduler answers the op with x * 10, then the guest
                    // tail adds 1.
                    Object elem = pm.pending().continueWith(pm.xs()[pm.at()] * 10L);
                    assertFalse(elem instanceof ContinuationResult,
                        "single-level callback: resuming completes it");
                    pm.out()[pm.at()] = elem;
                    // RE-ENTER THE LOOP at the next index, output preserved.
                    r = resumableMap(pm.xs(), pm.out(), pm.at() + 1, cb);
                }

                assertEquals(3, parks, "it parked once per element");
                assertArrayEquals(new Object[] { 11L, 21L, 31L }, (Object[]) r,
                    "every element mapped, in order, across three suspensions");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theNonSuspendingFastPathAllocatesNoState() {
        // What the discipline must NOT cost: when nothing suspends, the native
        // runs its ordinary loop and returns the array. The only overhead is one
        // type test per element.
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode cbRoot = plainCallback();
                long[] xs = { 1L, 2L, 3L };
                Object[] out = new Object[xs.length];

                Object r = resumableMap(xs, out, 0,
                    x -> cbRoot.getCallTarget().call(new Object[] { x }));

                assertFalse(r instanceof PartialMap,
                    "no suspension, so no state object is ever built");
                assertArrayEquals(new Object[] { 11L, 12L, 13L }, (Object[]) r,
                    "ordinary mapping, unchanged: " + Arrays.toString((Object[]) r));
            } finally {
                context.leave();
            }
        }
    }
}
