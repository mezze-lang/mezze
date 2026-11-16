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
 * WHAT DOES A PARK COST 1000 FRAMES DEEP? The ~25 ns/level figure in the plan
 * came from a TWO-level measurement; extrapolating it to a deep stack is
 * exactly the kind of guess that should be a number instead.
 *
 * <p>Builds an N-level chain where every level propagates — level 0 parks,
 * each level above yields its callee's continuation onward — then times one
 * full park + resume through the stack-walking driver at a range of depths, so
 * the SLOPE is observed rather than assumed linear.
 */
class DeepPropagationCostTest {

    /** level 0: fn(x) -> (yield "op:park") + 1 */
    private static MezzeBytecodeRootNode leaf() {
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

    /** level i: fn(x) -> (yield callee(x)) + 1 — a NON-TAIL effectful frame. */
    private static MezzeBytecodeRootNode level(CallTarget callee) {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> p = b -> {
            b.beginRoot(); b.beginReturn();
            b.beginAddLong();
            b.beginYield();
            b.beginCallDirect1(callee, false); b.emitLoadArgument(0); b.endCallDirect1();
            b.endYield();
            b.emitLoadConstant(1L);
            b.endAddLong();
            b.endReturn(); b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, p).getNode(0);
    }

    private static CallTarget chain(int depth) {
        CallTarget t = leaf().getCallTarget();
        for (int i = 1; i < depth; i++) t = level(t).getCallTarget();
        return t;
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

    private static long nsPerPark(CallTarget t, int iters) {
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 7; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) driveWithStack(t.call(new Object[]{0L}), 1L);
            long dt = System.nanoTime() - t0;
            if (dt < best) best = dt;
        }
        return best / iters;
    }

    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    void parkCostByDepth() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                // correctness at depth 3: 1 + 1 + 1 + 1 = 4
                assertEquals(4L, driveWithStack(chain(3).call(new Object[]{0L}), 1L),
                    "every level's tail must run");

                int[] depths = { 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1000 };
                for (int d : new int[]{1, 4, 16}) nsPerPark(chain(d), 2000);   // warm

                System.err.println("[deep] depth   ns/park   ns/level");
                for (int d : depths) {
                    CallTarget t = chain(d);
                    int iters = d <= 16 ? 60_000 : d <= 128 ? 12_000 : d <= 512 ? 3_000 : 1_500;
                    long ns = nsPerPark(t, iters);
                    System.err.printf("[deep] %5d %9d %10.2f%n", d, ns, (double) ns / d);
                }
            } finally {
                context.leave();
            }
        }
    }
}
