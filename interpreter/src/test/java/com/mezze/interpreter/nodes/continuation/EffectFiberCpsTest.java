package com.mezze.interpreter.nodes.continuation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end acceptance for the Path C Phase 2.2 wiring: an
 * {@link EffectFiber} whose backend is the pure-Java
 * {@link CpsContinuationBackend} must behave identically (from the
 * driver's perspective) to a Loom-backed one, for the effect patterns
 * the runtime actually issues.
 *
 * <p>Runs the same suspend/resume dance the {@code EffectHandlerDriver}
 * loop performs when a user program does {@code perform Handler in body}.
 */
class EffectFiberCpsTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void bodyReturnsWithoutSuspending() {
        List<Object> yields = new ArrayList<>();
        EffectFiber[] fiberSlot = new EffectFiber[1];
        Runnable body = () -> {
            fiberSlot[0].setSuspendedValue("final");
        };
        EffectFiber fiber = EffectFiber.createCps("no-yield", body);
        fiberSlot[0] = fiber;

        fiber.run();
        assertTrue(fiber.isDone());
        assertEquals("final", fiber.takeSuspendedValue());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void singlePerformYieldsAndResumes() {
        // Simulates: body performs one op, driver reads suspended value,
        // resumes with an answer, body finishes.
        EffectFiber[] fiberSlot = new EffectFiber[1];
        Runnable body = () -> {
            Object answer = fiberSlot[0].suspend("op:add");
            fiberSlot[0].setSuspendedValue(answer);
        };
        EffectFiber fiber = EffectFiber.createCps("single-perform", body);
        fiberSlot[0] = fiber;

        fiber.run();
        assertFalse(fiber.isDone());
        assertEquals("op:add", fiber.takeSuspendedValue());

        fiber.setResumeValue(42L);
        fiber.run();
        assertTrue(fiber.isDone());
        assertEquals(42L, fiber.takeSuspendedValue());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void multiplePerformsAlternate() {
        EffectFiber[] fiberSlot = new EffectFiber[1];
        Runnable body = () -> {
            Object a = fiberSlot[0].suspend("op:1");
            Object b = fiberSlot[0].suspend("op:2");
            fiberSlot[0].setSuspendedValue(((Long) a) + ((Long) b));
        };
        EffectFiber fiber = EffectFiber.createCps("two-performs", body);
        fiberSlot[0] = fiber;

        fiber.run();
        assertEquals("op:1", fiber.takeSuspendedValue());

        fiber.setResumeValue(10L);
        fiber.run();
        assertEquals("op:2", fiber.takeSuspendedValue());

        fiber.setResumeValue(32L);
        fiber.run();
        assertTrue(fiber.isDone());
        assertEquals(42L, fiber.takeSuspendedValue());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void bodyExceptionSurfacesOnRun() {
        EffectFiber fiber = EffectFiber.createCps("throw", () -> {
            throw new RuntimeException("body-crash");
        });
        RuntimeException raised = assertThrows(RuntimeException.class, fiber::run);
        assertEquals("body-crash", raised.getMessage());
    }
}
