package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DOES A YIELD COMPOSE ACROSS NESTED BDSL ROOTS? The question that decides
 * whether "proper continuations" are reachable at all.
 *
 * <p>A BDSL continuation suspends the frames of ONE root. If an inner root's
 * yield cannot also suspend the OUTER root that called it, then continuations
 * are per-function only and nothing composes — no scheduler, no parking inside a
 * higher-order function, guest-written or not.
 *
 * <p>Outer: {@code fn(x) -> inner(x) + 100}. Inner: {@code fn(x) -> yield "op" + 1}.
 * MEASURED: it does NOT compose. Being a ContinuationRootNode is necessary but
 * NOT SUFFICIENT — the call site must propagate the suspension, and
 * {@code CallDirect1} simply returns the callee's value.
 */
class BdslYieldComposesAcrossRootsTest {

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

    private static MezzeBytecodeRootNode outer(CallTarget innerTarget) {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            b.beginReturn();
            b.beginAddLong();
            b.beginCallDirect1(innerTarget, false);
            b.emitLoadArgument(0);
            b.endCallDirect1();
            b.emitLoadConstant(100L);
            b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen
            .create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void anInnerYieldDoesNotSuspendTheOuterRoot() {
        // MEASURED 2026-11-16: it does NOT. The inner root yields, its
        // ContinuationResult is returned by CallDirect1 as an ordinary value,
        // and the outer root's next operation receives it as data. The outer
        // root IS a continuation root (enableYield = true) and that is NOT
        // enough: the CALL SITE must also propagate the suspension.
        //
        // If this test ever starts failing because composition now WORKS,
        // that is good news — update
        // planned_features/runtime/continuations-and-swappable-async.md,
        // where this limit is the premise of the whole Phase C costing.
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode in = inner();
                MezzeBytecodeRootNode out = outer(in.getCallTarget());

                Throwable t = assertThrows(Throwable.class,
                    () -> out.getCallTarget().call(new Object[] { 0L }),
                    "the outer root should choke on the ContinuationResult it was "
                    + "handed as a value — if it suspended instead, composition works");

                String chain = describe(t);
                assertTrue(chain.contains("ContinuationResult"),
                    "the failure must be the ContinuationResult arriving as a VALUE, "
                    + "not some unrelated error. Got: " + chain);
            } finally {
                context.leave();
            }
        }
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 4000; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }
}
