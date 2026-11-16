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
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IS IT FULLY MULTI-SHOT? Three shapes, because "multi-shot" is not one
 * property: resuming a single root twice, resuming a COMPOSED (nested)
 * continuation twice, and resuming one parked inside a resumable native twice.
 *
 * <p>The third is where it matters: a native holding loop state on the HEAP
 * (the C4 shape) shares that state between resumes, so multi-shot through one
 * is only sound if the state is COPIED. This pins which of the three are free
 * and which needs work.
 */
class MultiShotMatrixTest {

    private static MezzeBytecodeRootNode yielding() {
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

    private static MezzeBytecodeRootNode outerPropagating(CallTarget innerTarget) {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            b.beginAddLong();
            b.beginYield();
            b.beginCallDirect1(innerTarget, false);
            b.emitLoadArgument(0);
            b.endCallDirect1();
            b.endYield();
            b.emitLoadConstant(100L);
            b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    private static Object driveWithStack(Object initial, LongFunction<Object> unused, long answer) {
        Deque<ContinuationResult> waiting = new ArrayDeque<>();
        Object cur = initial;
        while (true) {
            if (cur instanceof ContinuationResult cr) {
                Object y = cr.getResult();
                if (y instanceof ContinuationResult nested) { waiting.push(cr); cur = nested; continue; }
                cur = cr.continueWith(answer);
                continue;
            }
            if (waiting.isEmpty()) return cur;
            cur = waiting.pop().continueWith(cur);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void oneRootResumesManyTimesIndependently() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                Object first = yielding().getCallTarget().call(new Object[] { 0L });
                ContinuationResult cr = (ContinuationResult) first;

                assertEquals(2L, cr.continueWith(1L), "first shot");
                assertEquals(3L, cr.continueWith(2L), "second shot, same continuation");
                assertEquals(43L, cr.continueWith(42L), "third shot");
                assertEquals(2L, cr.continueWith(1L),
                    "re-running the FIRST shot again gives the same answer — no state carried");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aComposedContinuationResumesManyTimes() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode in = yielding();
                MezzeBytecodeRootNode out = outerPropagating(in.getCallTarget());
                Object first = out.getCallTarget().call(new Object[] { 0L });

                // Drive the SAME composed continuation twice with different answers.
                assertEquals(142L, driveWithStack(first, null, 41L),
                    "(41+1)+100 — both tails, first shot");
                assertEquals(242L, driveWithStack(first, null, 141L),
                    "(141+1)+100 — both tails again, from the same outer continuation");
                assertEquals(142L, driveWithStack(first, null, 41L),
                    "and back to the first answer: multi-shot survives COMPOSITION");
            } finally {
                context.leave();
            }
        }
    }

    record PartialMap(long[] xs, Object[] out, int at, ContinuationResult pending) {}

    static Object resumableMap(long[] xs, Object[] out, int from, LongFunction<Object> cb) {
        for (int i = from; i < xs.length; i++) {
            Object r = cb.apply(xs[i]);
            if (r instanceof ContinuationResult cr) return new PartialMap(xs, out, i, cr);
            out[i] = r;
        }
        return out;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void multiShotThroughAResumableNativeSHARESItsLoopState() {
        // THE LIMIT. The guest continuation is multi-shot, but the native's loop
        // state is one heap object: two resumes write the SAME output slot, so
        // the second overwrites the first. Multi-shot through a native HOF is
        // only sound if the state is COPIED per shot.
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode cbRoot = yielding();
                LongFunction<Object> cb = x -> cbRoot.getCallTarget().call(new Object[] { x });
                long[] xs = { 1L, 2L, 3L };
                Object[] out = new Object[xs.length];

                Object r = resumableMap(xs, out, 0, cb);
                PartialMap pm = assertInstanceOf(PartialMap.class, r, "parks on element 0");

                Object shotA = pm.pending().continueWith(10L);
                Object shotB = pm.pending().continueWith(20L);
                assertEquals(11L, shotA, "guest tail is multi-shot: 10 + 1");
                assertEquals(21L, shotB, "and again: 20 + 1");

                // Both shots target the same slot in the same array.
                pm.out()[pm.at()] = shotA;
                pm.out()[pm.at()] = shotB;
                assertEquals(21L, pm.out()[0],
                    "the SECOND shot overwrote the first — one shared heap state");
                assertSame(out, pm.out(),
                    "there is only one output array; a second shot needs a COPY");
            } finally {
                context.leave();
            }
        }
    }
}
