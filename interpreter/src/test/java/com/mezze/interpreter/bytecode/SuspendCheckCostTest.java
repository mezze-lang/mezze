package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHAT DOES A SUSPENSION CHECK COST AT A CALL SITE? The last unmeasured number
 * in the continuations plan.
 *
 * <p>I had claimed this could not be measured until C1 (the effect row reaching
 * the call IR) exists. That was wrong: C1 decides HOW MANY sites carry a check,
 * not what ONE costs. And {@code CheckSuspendedPassThrough} already exists with
 * exactly the cost shape a {@code PropagateSuspend} check needs — one
 * {@code instanceof} against the call's result, value passed through.
 *
 * <p>Two roots, identical but for the check, called the same number of times
 * with the arms INTERLEAVED in one process so neither can drift.
 */
class SuspendCheckCostTest {

    /** inner(x) = x + 1 */
    private static MezzeBytecodeRootNode inner() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            b.beginAddLong();
            b.emitLoadArgument(0);
            b.emitLoadConstant(1L);
            b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    /** caller(x) = inner(x), with or without the suspend check wrapped round it. */
    private static MezzeBytecodeRootNode caller(CallTarget t, boolean withCheck) {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            if (withCheck) {
                b.beginCheckSuspendedPassThrough();
            }
            b.beginCallDirect1(t, false);
            b.emitLoadArgument(0);
            b.endCallDirect1();
            if (withCheck) {
                b.endCheckSuspendedPassThrough();
            }
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    private static long timePerCall(MezzeBytecodeRootNode root, int iters) {
        CallTarget t = root.getCallTarget();
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 9; rep++) {
            long t0 = System.nanoTime();
            long acc = 0;
            for (int i = 0; i < iters; i++) {
                acc += (Long) t.call(new Object[] { (long) i });
            }
            long dt = System.nanoTime() - t0;
            if (acc == Long.MIN_VALUE) System.err.print("");  // keep it live
            if (dt < best) best = dt;
        }
        return best * 1000 / iters;   // picoseconds per call
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void theSuspendCheckCostsAlmostNothingPerCallSite() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode in = inner();
                MezzeBytecodeRootNode plain = caller(in.getCallTarget(), false);
                MezzeBytecodeRootNode checked = caller(in.getCallTarget(), true);

                // Correctness first: the check must be value-preserving.
                assertEquals(8L, plain.getCallTarget().call(new Object[] { 7L }));
                assertEquals(8L, checked.getCallTarget().call(new Object[] { 7L }));

                int iters = 2_000_000;
                // warm both
                for (int i = 0; i < 4; i++) { timePerCall(plain, 200_000); timePerCall(checked, 200_000); }

                // INTERLEAVED arms, three rounds each, same invocation.
                long[] p = new long[3], c = new long[3];
                for (int r = 0; r < 3; r++) {
                    p[r] = timePerCall(plain, iters);
                    c[r] = timePerCall(checked, iters);
                }
                long pMin = Math.min(p[0], Math.min(p[1], p[2]));
                long cMin = Math.min(c[0], Math.min(c[1], c[2]));

                System.err.printf("[checkcost] plain   ps/call: %d %d %d  (min %d)%n", p[0], p[1], p[2], pMin);
                System.err.printf("[checkcost] checked ps/call: %d %d %d  (min %d)%n", c[0], c[1], c[2], cMin);
                System.err.printf("[checkcost] delta per call site: %d ps (%.2f ns)%n",
                    cMin - pMin, (cMin - pMin) / 1000.0);

                // Guard, deliberately loose: the point is that one type test on a
                // call result is not a structural cost. If this ever fails, the
                // check has started defeating something (inlining, boxing elim)
                // and the continuations plan needs re-costing.
                assertTrue(cMin - pMin < 20_000,
                    "a suspend check should cost well under 20 ns per call site; measured "
                    + (cMin - pMin) + " ps");
            } finally {
                context.leave();
            }
        }
    }
}
