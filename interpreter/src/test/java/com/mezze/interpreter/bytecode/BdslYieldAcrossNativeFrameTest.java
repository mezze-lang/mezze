package com.mezze.interpreter.bytecode;

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
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAN A BDSL YIELD CROSS A NATIVE (plain Java) FRAME? Characterization of the
 * limit that decides how far BDSL continuations can be taken, measured rather
 * than argued.
 *
 * <p>Mezze's native higher-order functions ({@code map}, {@code fold}, the
 * {@code Seq} natives) are Java methods that call back into guest code. A BDSL
 * continuation suspends only frames whose root is a {@code ContinuationRootNode}
 * — a Java frame is not one. These tests pin what actually happens, because the
 * answer decides whether `cont` can be used inside such a callback.
 *
 * <p>NOTE what is NOT broken: an effectful callback whose handler RESUMES
 * IMMEDIATELY needs no suspension (today's perform is a direct call), and an
 * ABORT is a Java throw that propagates through any frame. Both are verified at
 * the language level and keep working. Only PARKING inside a native callback is
 * at issue here.
 */
class BdslYieldAcrossNativeFrameTest {

    /** Stand-in for a native HOF: the loop state lives in a JAVA frame. */
    private static List<Object> nativeMap(long[] xs, LongFunction<Object> cb) {
        List<Object> out = new ArrayList<>();
        for (long x : xs) {
            out.add(cb.apply(x));
        }
        return out;
    }

    private static MezzeBytecodeRootNode yieldingRoot() {
        BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
            b.beginRoot();
            // fn (x) -> (yield "op:park") + 1
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
        BytecodeRootNodes<MezzeBytecodeRootNode> nodes =
            MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser);
        return nodes.getNode(0);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aYieldCannotSuspendTheNativeFrameThatCalledIt() {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                MezzeBytecodeRootNode root = yieldingRoot();
                long[] xs = { 1L, 2L, 3L };

                // The native HOF runs to completion and hands back whatever the
                // callback returned. It CANNOT be suspended.
                List<Object> out = nativeMap(xs, x -> root.getCallTarget().call(new Object[] { x }));

                assertEquals(3, out.size(), "the native loop ran to completion");
                for (Object o : out) {
                    assertInstanceOf(ContinuationResult.class, o,
                        "each element is a ContinuationResult, NOT a mapped value — "
                        + "a native HOF would treat this as the element");
                }

                // Resuming one resumes ONLY the guest tail. The native loop's
                // state is gone: there is no way to re-enter the map.
                ContinuationResult cr = (ContinuationResult) out.get(0);
                assertEquals("op:park", cr.getResult());
                assertEquals(42L, cr.continueWith(41L),
                    "the guest tail resumes, but the native frame around it is already gone");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aDirectCallAcrossANativeFrameIsUnaffected() {
        // The common case: no yield at all. A native HOF calling guest code that
        // returns normally is exactly as it is today — this is what must not break.
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {
            context.enter();
            try {
                BytecodeParser<MezzeBytecodeRootNodeGen.Builder> parser = b -> {
                    b.beginRoot();
                    b.beginReturn();
                    b.beginAddLong();
                    b.emitLoadArgument(0);
                    b.emitLoadConstant(10L);
                    b.endAddLong();
                    b.endReturn();
                    b.endRoot();
                };
                BytecodeRootNodes<MezzeBytecodeRootNode> nodes =
                    MezzeBytecodeRootNodeGen.create(null, BytecodeConfig.DEFAULT, parser);
                MezzeBytecodeRootNode root = nodes.getNode(0);

                List<Object> out = nativeMap(new long[] { 1L, 2L, 3L },
                    x -> root.getCallTarget().call(new Object[] { x }));

                assertEquals(List.of(11L, 12L, 13L), out,
                    "no yield, no ContinuationResult — plain values through the native frame");
            } finally {
                context.leave();
            }
        }
    }
}
