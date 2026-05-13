package com.mezze.interpreter.nodes.continuation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates EffectFiber as the runtime substrate for effect handlers.
 *
 * <p>The patterns we want the design to support:
 * <ol>
 *   <li>Body completes without ever suspending — runner reads body's
 *       return as the "suspended value"</li>
 *   <li>Body suspends with a value — runner reads, decides not to resume</li>
 *   <li>Body suspends, runner resumes with a value — body sees that
 *       value as the suspend's return</li>
 *   <li>Cross-thread resume — fiber stashed by thread A, run by thread B</li>
 * </ol>
 */
class EffectFiberTest {

    @Test
    void supported_on_test_jvm() {
        if (!LoomSupport.isSupported()) {
            throw new AssertionError(
                "Loom not supported. Init error: " + LoomSupport.getInitError(),
                LoomSupport.getInitError());
        }
    }

    @Test
    void body_completes_without_suspending() {
        // Pattern 1: body returns naturally. We use setSuspendedValue
        // before returning, so the runner can read body's "result" via
        // takeSuspendedValue, the same way it reads explicit suspends.
        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("complete", () -> {
            holder[0].setSuspendedValue("done");
        });

        holder[0].run();
        assertTrue(holder[0].isDone());
        assertEquals("done", holder[0].takeSuspendedValue());
    }

    @Test
    void body_suspends_runner_reads_value_drops_resume() {
        // Pattern 2: body suspends with value, runner reads it but
        // does NOT call run() again. Body never resumes. The fiber
        // is just GC'd later.
        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("suspend-drop", () -> {
            holder[0].suspend("escape value");
            // never reached
            holder[0].setSuspendedValue("not-reached");
        });

        holder[0].run();
        assertFalse(holder[0].isDone(), "body suspended, not done");
        assertEquals("escape value", holder[0].takeSuspendedValue());

        // Runner intentionally drops the fiber — no .run() call.
        // (In production, fiber would be GC'd. Here we just close it
        //  for hygiene.)
        holder[0].close();
        assertTrue(holder[0].isClosed());
    }

    @Test
    void body_suspends_then_resumes_with_value_flowing_back() {
        // Pattern 3: body suspends, runner resumes with X, body's
        // suspend() returns X.
        Object[] receivedAfterResume = new Object[1];
        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("resume-flow", () -> {
            Object got = holder[0].suspend("ask");
            receivedAfterResume[0] = got;
            holder[0].setSuspendedValue("body returned with " + got);
        });

        holder[0].run();
        assertEquals("ask", holder[0].takeSuspendedValue());
        assertFalse(holder[0].isDone());

        holder[0].setResumeValue(42);
        holder[0].run();
        assertTrue(holder[0].isDone());
        assertEquals(42, receivedAfterResume[0]);
        assertEquals("body returned with 42", holder[0].takeSuspendedValue());
    }

    @Test
    void cross_thread_resume() throws Exception {
        // Pattern 4: fiber created+suspended on thread A, resumed on thread B.
        Object[] received = new Object[1];
        EffectFiber[] holder = new EffectFiber[1];
        holder[0] = EffectFiber.create("cross-thread", () -> {
            received[0] = holder[0].suspend("from-A");
        });

        holder[0].run();
        assertEquals("from-A", holder[0].takeSuspendedValue());

        Thread t = new Thread(() -> {
            holder[0].setResumeValue("from-B");
            holder[0].run();
        });
        t.start();
        t.join();

        assertEquals("from-B", received[0]);
        assertTrue(holder[0].isDone());
    }
}
