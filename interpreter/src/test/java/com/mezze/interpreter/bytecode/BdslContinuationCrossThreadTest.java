package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAN A BDSL CONTINUATION BE RESUMED ON A DIFFERENT THREAD? The question that
 * decides the intended architecture: ONE core suspension mechanism (BDSL
 * continuations) with schedulers layered on top, where a Loom/virtual-thread
 * executor decides which thread resumes which continuation.
 *
 * <p>If a continuation can only be resumed on its originating thread, then a
 * BDSL-cont core is single-threaded-only and no executor on top can give
 * parallelism. If it can cross threads, the layering works and Loom is demoted
 * to a plain thread pool — {@code newVirtualThreadPerTaskExecutor}, PUBLIC api,
 * with no {@code jdk.internal.vm} and no {@code --add-exports}.
 */
class BdslContinuationCrossThreadTest {

    private static MezzeBytecodeRootNode yieldingRoot() {
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

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aContinuationSuspendedOnOneThreadResumesOnAnother() throws Exception {
        try (Context context = Context.newBuilder("mezze")
                .allowExperimentalOptions(true).allowAllAccess(true).build()) {

            ExecutorService a = Executors.newSingleThreadExecutor();
            ExecutorService b = Executors.newSingleThreadExecutor();
            try {
                // Suspend on thread A.
                ContinuationResult cr = a.submit((Callable<ContinuationResult>) () -> {
                    context.enter();
                    try {
                        Object first = yieldingRoot().getCallTarget().call(new Object[] { 0L });
                        assertInstanceOf(ContinuationResult.class, first);
                        return (ContinuationResult) first;
                    } finally {
                        context.leave();
                    }
                }).get(20, TimeUnit.SECONDS);

                System.err.println("[xthread] suspended on A, yielded=" + cr.getResult());

                // Resume on thread B — a DIFFERENT carrier thread.
                Object resumed = b.submit((Callable<Object>) () -> {
                    context.enter();
                    try {
                        return cr.continueWith(41L);
                    } finally {
                        context.leave();
                    }
                }).get(20, TimeUnit.SECONDS);

                System.err.println("[xthread] resumed on B, result=" + resumed);
                assertEquals(42L, resumed,
                    "a continuation suspended on thread A must resume correctly on thread B "
                    + "for a layered executor to provide parallelism");
            } finally {
                a.shutdownNow();
                b.shutdownNow();
            }
        }
    }
}
