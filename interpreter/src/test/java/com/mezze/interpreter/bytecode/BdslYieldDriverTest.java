package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Path D2 acceptance: exercise {@link BdslYieldDriver} against a BDSL
 * root that yields the same three-field record shape
 * {@link BytecodeCompiler} emits at PerformOpIR sites when
 * {@code MEZZE_BDSL_YIELD=1}.
 *
 * <p>Hand-builds the yielding root so this test can run in isolation
 * from the full compile pipeline (which today refuses to lower any
 * effect-carrying function — see BytecodeCompiler.canYieldSuspended).
 * Phase D4 promotes this to end-to-end coverage.
 */
class BdslYieldDriverTest {

    private static final Hash TEST_EFFECT =
        new Hash("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void singlePerformYieldsDispatchesAndResumes() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode root = buildYieldingRoot();

                // Sanity: without the driver, we just get a ContinuationResult.
                Object raw = root.getCallTarget().call(new Object[] { 0L });
                assertInstanceOf(ContinuationResult.class, raw);

                // Driven end-to-end. Dispatcher records the (hash, name, arg)
                // triple and returns 41; add-one in the root produces 42.
                AtomicReference<Hash> seenHash = new AtomicReference<>();
                AtomicReference<String> seenName = new AtomicReference<>();
                AtomicReference<Object> seenArg = new AtomicReference<>();

                Object result = BdslYieldDriver.drive(
                    root.getCallTarget().call(new Object[] { 0L }),
                    (h, n, a) -> {
                        seenHash.set(h);
                        seenName.set(n);
                        seenArg.set(a);
                        return 41L;
                    }
                );

                assertEquals(TEST_EFFECT, seenHash.get());
                assertEquals("myOp", seenName.get());
                assertEquals("payload", seenArg.get());
                assertEquals(42L, result);
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void driverPassesThroughNonContinuationResult() {
        Object result = BdslYieldDriver.drive(99L, (h, n, a) -> {
            throw new AssertionError("dispatcher should not be called");
        });
        assertEquals(99L, result);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoPerformsInSequenceEachDispatched() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode root = buildTwoYieldRoot();

                List<String> names = new ArrayList<>();
                Object result = BdslYieldDriver.drive(
                    root.getCallTarget().call(new Object[] { 0L }),
                    (h, n, a) -> {
                        names.add(n);
                        return (long) names.size() * 10L;   // 10, then 20
                    }
                );

                assertEquals(List.of("first", "second"), names);
                // first resume = 10, second resume = 20; body adds them.
                assertEquals(30L, result);
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void dispatcherExceptionEscapesDriver() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode root = buildYieldingRoot();

                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> BdslYieldDriver.drive(
                        root.getCallTarget().call(new Object[] { 0L }),
                        (h, n, a) -> { throw new RuntimeException("handler aborted"); }
                    ));
                assertEquals("handler aborted", thrown.getMessage());
            } finally {
                context.leave();
            }
        }
    }

    // ── builders ────────────────────────────────────────────────────────

    private static final String[] FIELDS = { "effect_hash", "op_name", "arg" };

    private static MezzeBytecodeRootNode buildYieldingRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            // fn (_) -> yield { effect_hash=..., op_name="myOp", arg="payload" } + 1
            b.beginReturn();
              b.beginAddLong();
                b.beginYield();
                  b.beginMakeRecord(FIELDS);
                    b.emitLoadConstant(TEST_EFFECT);
                    b.emitLoadConstant("myOp");
                    b.emitLoadConstant("payload");
                  b.endMakeRecord();
                b.endYield();
                b.emitLoadConstant(1L);
              b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    private static MezzeBytecodeRootNode buildTwoYieldRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            // fn (_) -> (yield {first}) + (yield {second})
            b.beginReturn();
              b.beginAddLong();
                b.beginYield();
                  b.beginMakeRecord(FIELDS);
                    b.emitLoadConstant(TEST_EFFECT);
                    b.emitLoadConstant("first");
                    b.emitLoadConstant("payload-1");
                  b.endMakeRecord();
                b.endYield();
                b.beginYield();
                  b.beginMakeRecord(FIELDS);
                    b.emitLoadConstant(TEST_EFFECT);
                    b.emitLoadConstant("second");
                    b.emitLoadConstant("payload-2");
                  b.endMakeRecord();
                b.endYield();
              b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }
}
