package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.TimingSensitive;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DOES MAKING A NATIVE HOF RESUMABLE COST ANYTHING WHEN NOTHING SUSPENDS?
 *
 * <p>C4 requires callback-taking natives to hold loop state on the heap and to
 * test each callback result for a {@link ContinuationResult}. The question that
 * decides whether that is acceptable: what does the ORDINARY path pay — the
 * 99.9% of `map` calls where no callback ever parks.
 *
 * <p>Two loops over the same data with the same guest callback, one plain and
 * one resumable, arms interleaved in one process.
 */
@TimingSensitive
class ResumableNativeCostTest {

    record PartialMap(long[] xs, Object[] out, int at, ContinuationResult pending) {}

    /** Today's shape: loop state in Java locals, no check. */
    static Object[] plainMap(long[] xs, Object[] out, LongFunction<Object> cb) {
        for (int i = 0; i < xs.length; i++) {
            out[i] = cb.apply(xs[i]);
        }
        return out;
    }

    /** C4's shape: re-enterable, one type test per element. */
    static Object resumableMap(long[] xs, Object[] out, int from, LongFunction<Object> cb) {
        for (int i = from; i < xs.length; i++) {
            Object r = cb.apply(xs[i]);
            if (r instanceof ContinuationResult cr) {
                return new PartialMap(xs, out, i, cr);
            }
            out[i] = r;
        }
        return out;
    }

    private static MezzeBytecodeRootNode plainCallback() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> p = b -> {
            b.beginRoot(); b.beginReturn();
            b.beginAddLong(); b.emitLoadArgument(0); b.emitLoadConstant(10L); b.endAddLong();
            b.endReturn(); b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, p).getNode(0);
    }

    private interface Arm { void run(int reps); }

    private static long psPerElement(Arm arm, int reps, int n) {
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 9; r++) {
            long t0 = System.nanoTime();
            arm.run(reps);
            long dt = System.nanoTime() - t0;
            if (dt < best) best = dt;
        }
        return best * 1000 / ((long) reps * n);
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void theResumableShapeIsFreeWhenNothingSuspends() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                CallTarget cbT = plainCallback().getCallTarget();
                LongFunction<Object> cb = x -> cbT.call(new Object[] { x });

                int n = 256;
                long[] xs = new long[n];
                for (int i = 0; i < n; i++) xs[i] = i;
                Object[] out = new Object[n];

                assertArrayEquals(plainMap(xs, new Object[n], cb),
                    (Object[]) resumableMap(xs, new Object[n], 0, cb),
                    "same results either way");

                Arm aPlain = reps -> { for (int r = 0; r < reps; r++) plainMap(xs, out, cb); };
                Arm aResum = reps -> { for (int r = 0; r < reps; r++) resumableMap(xs, out, 0, cb); };

                for (int i = 0; i < 4; i++) { psPerElement(aPlain, 200, n); psPerElement(aResum, 200, n); }

                int reps = 4000;
                long[] p = new long[3], q = new long[3];
                for (int r = 0; r < 3; r++) {
                    p[r] = psPerElement(aPlain, reps, n);
                    q[r] = psPerElement(aResum, reps, n);
                }
                long pm = Math.min(p[0], Math.min(p[1], p[2]));
                long qm = Math.min(q[0], Math.min(q[1], q[2]));
                System.err.printf("[nativecost] plain     ps/element: %d %d %d (min %d)%n", p[0],p[1],p[2],pm);
                System.err.printf("[nativecost] resumable ps/element: %d %d %d (min %d)%n", q[0],q[1],q[2],qm);
                System.err.printf("[nativecost] delta per element: %d ps (%.3f ns)%n", qm-pm, (qm-pm)/1000.0);

                assertTrue(qm - pm < 5_000,
                    "the resumable shape should cost well under 5 ns per element on the "
                    + "non-suspending path; measured " + (qm - pm) + " ps");
            } finally {
                context.leave();
            }
        }
    }
}
