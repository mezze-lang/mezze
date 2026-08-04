package com.mezze.interpreter.bytecode;

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
 * Path D probe: prove Truffle's built-in Bytecode DSL {@code Yield}
 * operation gives us everything Loom does, without Loom.
 *
 * <p>Approach: build a tiny {@code MezzeBytecodeRootNode} that emits
 * a single {@code Yield} of a marker value, then a {@code Return} of
 * the resume-value + 1. Call the root; the framework returns a
 * {@link ContinuationResult}. Read the yielded value, resume via
 * {@link ContinuationResult#continueWith}, verify the returned value
 * came back through the yield.
 *
 * <p>Everything Path C's CpsContinuationBackend needed a whole thread +
 * lock/condition dance for, BDSL gives us as a first-class bytecode
 * operation — PE-friendly, no reflection, no {@code --add-opens},
 * multi-shot capable via {@link ContinuationResult#getContinuationCallTarget}.
 * (Path C was superseded and its code deleted, see #385.)
 */
class BdslContinuationPrototypeTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void yieldReturnsContinuationResultAndContinueWithResumes() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true)
                .allowAllAccess(true)
                .build()) {
            context.enter();
            try {
                BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
                    b.beginRoot();
                    // fn (x) -> {
                    //   let yielded = yield "op:marker"
                    //   yielded + 1L
                    // }
                    b.beginReturn();
                    b.beginAddLong();
                    b.beginYield();
                    b.emitLoadConstant("op:marker");
                    b.endYield();
                    b.emitLoadConstant(1L);
                    b.endAddLong();
                    b.endReturn();
                    b.endRoot();
                };

                BytecodeRootNodes<MezzeBytecodeRootNode> nodes =
                    MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser);
                MezzeBytecodeRootNode root = nodes.getNode(0);

                // Call the root with a dummy arg.
                Object first = root.getCallTarget().call(new Object[] { 0L });
                assertInstanceOf(ContinuationResult.class, first,
                    "First call should return a ContinuationResult, got " + first);

                ContinuationResult cr = (ContinuationResult) first;
                assertEquals("op:marker", cr.getResult(),
                    "The value yielded should surface as ContinuationResult.getResult()");

                // Resume with 41 — the yield expression evaluates to 41,
                // then we add 1 and return 42.
                Object resumed = cr.continueWith(41L);
                assertEquals(42L, resumed,
                    "After continueWith(41), the root should return 41+1 = 42");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multiShotResumeReExecutesTail() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true)
                .allowAllAccess(true)
                .build()) {
            context.enter();
            try {
                BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
                    b.beginRoot();
                    // fn (x) -> yield "op" + 0  (double the resume value)
                    b.beginReturn();
                    b.beginAddLong();
                    b.beginYield();
                    b.emitLoadConstant("op");
                    b.endYield();
                    b.emitLoadConstant(0L);
                    b.endAddLong();
                    b.endReturn();
                    b.endRoot();
                };

                BytecodeRootNodes<MezzeBytecodeRootNode> nodes =
                    MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser);
                MezzeBytecodeRootNode root = nodes.getNode(0);

                ContinuationResult cr = (ContinuationResult) root.getCallTarget().call(new Object[] { 0L });

                // Resume the SAME continuation twice — different values.
                // Loom's jdk.internal.vm.Continuation is one-shot; this
                // proves BDSL is multi-shot out of the box.
                Object a = cr.continueWith(100L);
                Object b = cr.continueWith(200L);
                assertEquals(100L, a);
                assertEquals(200L, b);
            } finally {
                context.leave();
            }
        }
    }
}
