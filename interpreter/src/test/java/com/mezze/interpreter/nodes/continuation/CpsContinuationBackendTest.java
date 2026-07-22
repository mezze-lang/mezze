package com.mezze.interpreter.nodes.continuation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level acceptance for the Path C Phase 2.1 pure-Java backend.
 * Drives the {@link CpsContinuationBackend} through the same run/yield
 * dance the real effect runtime would produce, without wiring it into
 * EffectFiber.
 */
class CpsContinuationBackendTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void bodyRunsToCompletionOnFirstRun() {
        AtomicInteger counter = new AtomicInteger();
        CpsContinuationBackend backend =
            new CpsContinuationBackend("test", counter::incrementAndGet);

        assertFalse(backend.isDone());
        backend.run();
        assertTrue(backend.isDone());
        assertEquals(1, counter.get());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void yieldSuspendsAndResumesInOrder() {
        AtomicInteger stage = new AtomicInteger(0);
        CpsContinuationBackend[] slot = new CpsContinuationBackend[1];
        Runnable body = () -> {
            stage.set(1);
            slot[0].yield();
            stage.set(2);
            slot[0].yield();
            stage.set(3);
        };
        CpsContinuationBackend backend = new CpsContinuationBackend("yield-test", body);
        slot[0] = backend;

        assertEquals(0, stage.get());

        backend.run();
        assertEquals(1, stage.get());
        assertFalse(backend.isDone());

        backend.run();
        assertEquals(2, stage.get());
        assertFalse(backend.isDone());

        backend.run();
        assertEquals(3, stage.get());
        assertTrue(backend.isDone());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void bodyExceptionReRaisesOnCaller() {
        CpsContinuationBackend backend = new CpsContinuationBackend("throw-test", () -> {
            throw new RuntimeException("boom");
        });
        RuntimeException raised = assertThrows(RuntimeException.class, backend::run);
        assertEquals("boom", raised.getMessage());
        assertTrue(backend.isDone());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void doubleRunAfterCompletionThrows() {
        CpsContinuationBackend backend = new CpsContinuationBackend("done-test", () -> {});
        backend.run();
        assertThrows(IllegalStateException.class, backend::run);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void yieldFromNonWorkerThreadThrows() {
        CpsContinuationBackend backend =
            new CpsContinuationBackend("nonworker-yield", () -> {});
        assertThrows(IllegalStateException.class, backend::yield);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void closeReleasesUnusedFiber() {
        CpsContinuationBackend backend =
            new CpsContinuationBackend("close-test", () -> {
                // Never gets a run() signal; interrupt should tear it down.
            });
        backend.close();
        // Nothing more to assert — the worker thread is a daemon anyway,
        // but close() should not throw.
    }
}
