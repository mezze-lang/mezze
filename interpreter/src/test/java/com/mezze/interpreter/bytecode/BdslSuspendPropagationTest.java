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
 * CAN SUSPENSION BE MADE TO COMPOSE? {@link BdslYieldComposesAcrossRootsTest}
 * shows a yield suspends exactly one root and the caller receives the
 * {@link ContinuationResult} as data. This test builds the fix and checks it.
 *
 * <p>Two halves, which are the first two items of the remaining Phase C work:
 *
 * <ol>
 *   <li><b>Propagate at the call site.</b> The caller yields the callee's
 *       {@code ContinuationResult} onward, so the caller suspends too. Built
 *       here from EXISTING builder vocabulary — {@code yield} accepts any
 *       value — so no new operation is needed to prove the semantics.</li>
 *   <li><b>A stack-walking driver.</b> {@code YieldDriver.drive} loops over a
 *       SEQUENCE of yields from one root. Nested suspension is a STACK: a
 *       continuation whose yielded value is itself a continuation. The driver
 *       below keeps that stack explicitly.</li>
 * </ol>
 *
 * <p>The program is the one from the composition test:
 * {@code outer(x) = inner(x) + 100}, {@code inner(x) = (yield "op:park") + 1}.
 * Resuming the parked op with 41 must give (41 + 1) + 100 = 142 — BOTH tails.
 */
class BdslSuspendPropagationTest {

    /** inner(x) = (yield "op:park") + 1 */
    private static MezzeBytecodeRootNode inner() {
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

    /**
     * outer(x) = (yield inner(x)) + 100 — the call site PROPAGATES.
     *
     * <p>This is what a `PropagateSuspend` lowering does, minus the conditional:
     * the real emit would yield only when the callee actually suspended, which
     * the effect row decides per site. Unconditional here so the semantics are
     * tested without a new operation.
     */
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

    /**
     * The stack-walking driver. Descends when a yielded value is itself a
     * continuation; on completion, feeds the value back to the frame that was
     * waiting for it.
     */
    private static Object driveWithStack(Object initial, java.util.function.Function<Object, Object> dispatch) {
        Deque<ContinuationResult> waiting = new ArrayDeque<>();
        Object cur = initial;
        while (true) {
            if (cur instanceof ContinuationResult cr) {
                Object yielded = cr.getResult();
                if (yielded instanceof ContinuationResult nested) {
                    waiting.push(cr);        // this frame waits for the inner one
                    cur = nested;            // descend
                    continue;
                }
                cur = cr.continueWith(dispatch.apply(yielded));
                continue;
            }
            if (waiting.isEmpty()) {
                return cur;                  // fully done
            }
            cur = waiting.pop().continueWith(cur);   // hand the value outward
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void propagatingAtTheCallSiteMakesSuspensionCompose() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode in = inner();
                MezzeBytecodeRootNode out = outerPropagating(in.getCallTarget());

                Object first = out.getCallTarget().call(new Object[] { 0L });
                assertInstanceOf(ContinuationResult.class, first,
                    "the outer root must itself suspend now");

                // The outer's yielded value IS the inner's continuation — a nest.
                Object yielded = ((ContinuationResult) first).getResult();
                assertInstanceOf(ContinuationResult.class, yielded,
                    "the outer yielded the inner's continuation: this is the STACK the "
                    + "flat driver cannot walk");

                Object result = driveWithStack(first, op -> {
                    assertEquals("op:park", op, "the only op performed");
                    return 41L;
                });

                assertEquals(142L, result,
                    "BOTH tails must run: 41 + 1 (inner) + 100 (outer)");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theFlatDriverCannotWalkANest() {
        // Why the driver work is real and not just tidying: YieldDriver's shape
        // (a flat `while (cur instanceof ContinuationResult)` loop feeding each
        // yielded value to a dispatcher) would hand the INNER CONTINUATION to
        // the dispatcher as though it were an op record.
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode in = inner();
                MezzeBytecodeRootNode out = outerPropagating(in.getCallTarget());
                Object first = out.getCallTarget().call(new Object[] { 0L });

                Object[] sawOp = new Object[1];
                Object cur = first;
                int guard = 0;
                while (cur instanceof ContinuationResult cr && guard++ < 4) {
                    sawOp[0] = cr.getResult();       // flat loop: no descent
                    cur = cr.continueWith(41L);
                }
                assertInstanceOf(ContinuationResult.class, sawOp[0],
                    "the flat loop handed a CONTINUATION to the dispatcher instead of "
                    + "an op — it cannot tell a nested suspend from a performed op");
            } finally {
                context.leave();
            }
        }
    }
}
