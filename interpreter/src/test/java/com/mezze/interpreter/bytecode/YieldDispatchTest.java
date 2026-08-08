package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Path D3 acceptance: prove {@link YieldDispatch} bridges a
 * BDSL-yielded op to the existing {@link HandlerChainRegistry} handler chain
 * and drives execution to completion.
 *
 * <p>Hand-builds:
 * <ul>
 *   <li>a BDSL root that yields {@code {effect_hash, op_name="double",
 *       arg=21}} then adds 0 (identity around the yielded value)</li>
 *   <li>a "handler" MezzeRecord whose {@code double} field is a
 *       {@link MezzeFunction} that reads {@code arg} and returns
 *       {@code arg * 2}</li>
 *   <li>installs the handler on the current thread's chain via
 *       {@link HandlerChainRegistry#pushHandlerOnChain}</li>
 * </ul>
 *
 * <p>Drives via {@link YieldDispatch#driveWithChainDispatch} and
 * expects {@code 42} — the handler doubled {@code 21}, the root added
 * 0.
 *
 * <p>Full-integration coverage (real Mezze source lowered through the
 * whole pipeline) lands at Phase D4.
 */
class BdslYieldDispatchTest {

    private static final Hash TEST_EFFECT =
        new Hash("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void yieldedOpDispatchesToChainHandlerAndResumes() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode root = buildYieldRoot();

                // Handler impl: a MezzeFunction taking one arg (the perform's
                // argument record), returning arg * 2. Under Path D3 fall-
                // through mode, the return value IS the value fed back to
                // continueWith.
                MezzeFunction doubleImpl = makeDoubleFunction();

                // Install a handler record on the current thread's chain.
                // Field name matches the yielded op_name.
                MezzeRecord handler = MezzeRecord.withAllObjects(
                    com.mezze.interpreter.values.ShapeCache.getOrCreateAllObjects(new String[] { "double" }),
                    new Object[] { doubleImpl }
                );
                HandlerChainRegistry.pushHandlerOnChain(handler, TEST_EFFECT);
                try {
                    Object initial = root.getCallTarget().call(new Object[] { 0L });
                    Object driven = YieldDispatch.driveWithChainDispatch(initial);
                    assertEquals(42L, driven);
                } finally {
                    HandlerChainRegistry.popHandlerOffChain();
                }
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void directDispatchWithoutRootReturnsMezzeSuspend() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                // Direct call — no BDSL root, no continuation to resume;
                // just verify the unhandled-op MezzeSuspend-wrap behavior
                // in isolation. Path A does the same via
                // EffectHandlerDriver.makeUnhandledSuspended.
                Object result = YieldDispatch.dispatch(
                    TEST_EFFECT, "double", 21L);
                assertInstanceOf(
                    com.mezze.interpreter.values.MezzeSuspend.class, result,
                    "expected MezzeSuspend for unhandled op, got " + result);
            } finally {
                context.leave();
            }
        }
    }

    // ── builders ────────────────────────────────────────────────────────

    private static final String[] YIELD_FIELDS = { "effect_hash", "op_name", "arg" };

    private static MezzeBytecodeRootNode buildYieldRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            // fn (_) -> (yield { effect_hash, op_name="double", arg=21L }) + 0L
            b.beginReturn();
              b.beginAddLong();
                b.beginYield();
                  b.beginMakeRecord(YIELD_FIELDS);
                    b.emitLoadConstant(TEST_EFFECT);
                    b.emitLoadConstant("double");
                    b.emitLoadConstant(21L);
                  b.endMakeRecord();
                b.endYield();
                b.emitLoadConstant(0L);
              b.endAddLong();
            b.endReturn();
            b.endRoot();
        };
        return MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser).getNode(0);
    }

    /**
     * Build a MezzeFunction whose body doubles its Long argument.
     * The impl receives the perform arg directly (not wrapped in a
     * record) because Path D yields the raw arg.
     */
    private static MezzeFunction makeDoubleFunction() {
        RootCallTarget target = new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] args = frame.getArguments();
                // No closure values; arg is at index 0.
                long v = (Long) args[0];
                return v * 2L;
            }
        }.getCallTarget();
        return new MezzeFunction(target, "double-impl", new Object[0]);
    }
}
