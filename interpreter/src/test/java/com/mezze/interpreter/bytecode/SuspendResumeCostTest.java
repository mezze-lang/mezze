package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHAT DOES A BDSL PARK + RESUME ACTUALLY COST? The headline performance number
 * for a BDSL-continuation core, never measured before — the ~100 ns figure in
 * the plan is LOOM's, from a different mechanism.
 *
 * <p>Four arms, interleaved in one process:
 * <ol>
 *   <li>baseline: a plain call, no yield (what a non-parking op costs)</li>
 *   <li>park + resume: one full suspend/resume cycle</li>
 *   <li>park + resume through a ONE-SHOT GUARD (wrapper object + used flag)</li>
 *   <li>a COMPOSED two-level park + resume driven by the stack driver</li>
 * </ol>
 */
class SuspendResumeCostTest {

    /** A one-shot continuation handle — what `cont` would be if multi-shot is refused. */
    static final class OneShot {
        private final ContinuationResult cr;
        private boolean used;
        OneShot(ContinuationResult cr) { this.cr = cr; }
        Object resume(Object v) {
            if (used) throw new IllegalStateException("continuation already resumed");
            used = true;
            return cr.continueWith(v);
        }
    }

    private static MezzeBytecodeRootNode plainRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> p = b -> {
            b.beginRoot(); b.beginReturn();
            b.beginAddLong(); b.emitLoadArgument(0); b.emitLoadConstant(1L); b.endAddLong();
            b.endReturn(); b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, p).getNode(0);
    }

    private static MezzeBytecodeRootNode yieldingRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> p = b -> {
            b.beginRoot(); b.beginReturn();
            b.beginAddLong();
            b.beginYield(); b.emitLoadConstant("op:park"); b.endYield();
            b.emitLoadConstant(1L);
            b.endAddLong();
            b.endReturn(); b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, p).getNode(0);
    }

    private static MezzeBytecodeRootNode outerPropagating(CallTarget inner) {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> p = b -> {
            b.beginRoot(); b.beginReturn();
            b.beginAddLong();
            b.beginYield();
            b.beginCallDirect1(inner, false); b.emitLoadArgument(0); b.endCallDirect1();
            b.endYield();
            b.emitLoadConstant(100L);
            b.endAddLong();
            b.endReturn(); b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, p).getNode(0);
    }

    private static Object driveWithStack(Object initial, long answer) {
        Deque<ContinuationResult> waiting = new ArrayDeque<>();
        Object cur = initial;
        while (true) {
            if (cur instanceof ContinuationResult cr) {
                Object y = cr.getResult();
                if (y instanceof ContinuationResult nested) { waiting.push(cr); cur = nested; continue; }
                cur = cr.continueWith(answer); continue;
            }
            if (waiting.isEmpty()) return cur;
            cur = waiting.pop().continueWith(cur);
        }
    }

    private interface Arm { void run(int iters); }

    private static long ps(Arm arm, int iters) {
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 9; rep++) {
            long t0 = System.nanoTime();
            arm.run(iters);
            long dt = System.nanoTime() - t0;
            if (dt < best) best = dt;
        }
        return best * 1000 / iters;
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void parkAndResumeCost() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                CallTarget plain = plainRoot().getCallTarget();
                CallTarget park = yieldingRoot().getCallTarget();
                MezzeBytecodeRootNode in = yieldingRoot();
                CallTarget composed = outerPropagating(in.getCallTarget()).getCallTarget();

                Arm aPlain = n -> { for (int i = 0; i < n; i++) plain.call(new Object[]{(long) i}); };
                Arm aPark = n -> { for (int i = 0; i < n; i++)
                    ((ContinuationResult) park.call(new Object[]{(long) i})).continueWith(1L); };
                Arm aGuard = n -> { for (int i = 0; i < n; i++)
                    new OneShot((ContinuationResult) park.call(new Object[]{(long) i})).resume(1L); };
                Arm aComposed = n -> { for (int i = 0; i < n; i++)
                    driveWithStack(composed.call(new Object[]{(long) i}), 41L); };

                // correctness
                assertEquals(2L, ((ContinuationResult) park.call(new Object[]{0L})).continueWith(1L));
                assertEquals(142L, driveWithStack(composed.call(new Object[]{0L}), 41L));

                int iters = 500_000;
                for (int i = 0; i < 4; i++) {
                    ps(aPlain, 50_000); ps(aPark, 50_000); ps(aGuard, 50_000); ps(aComposed, 50_000);
                }
                long[] p = new long[3], k = new long[3], g = new long[3], c = new long[3];
                for (int r = 0; r < 3; r++) {
                    p[r] = ps(aPlain, iters);
                    k[r] = ps(aPark, iters);
                    g[r] = ps(aGuard, iters);
                    c[r] = ps(aComposed, iters);
                }
                long pm = Math.min(p[0], Math.min(p[1], p[2]));
                long km = Math.min(k[0], Math.min(k[1], k[2]));
                long gm = Math.min(g[0], Math.min(g[1], g[2]));
                long cm = Math.min(c[0], Math.min(c[1], c[2]));

                System.err.printf("[cost] plain call      : %7d ps  (%.2f ns)%n", pm, pm/1000.0);
                System.err.printf("[cost] park + resume   : %7d ps  (%.2f ns)%n", km, km/1000.0);
                System.err.printf("[cost] + one-shot guard: %7d ps  (%.2f ns)  delta %d ps%n", gm, gm/1000.0, gm-km);
                System.err.printf("[cost] composed 2-level: %7d ps  (%.2f ns)%n", cm, cm/1000.0);
                System.err.printf("[cost] suspend overhead over a plain call: %d ps (%.2f ns)%n",
                    km-pm, (km-pm)/1000.0);

                assertTrue(km > 0 && cm > 0, "arms ran");
            } finally {
                context.leave();
            }
        }
    }
}
