package com.mezze.interpreter.nodes.continuation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the effect-handler dispatch mechanics in pure Java, as a
 * minimal sketch of what the runtime does when {@code perform Handler in body}
 * is lowered.
 *
 * <p>The model:
 * <ul>
 *   <li>Body is a Runnable that may call {@code perform(opName, args)}</li>
 *   <li>perform looks up the operation in the handler, dispatches with args
 *       and a {@code resume} function</li>
 *   <li>Operation handler returns a value: that becomes the perform's result
 *       at the call site. If the handler called {@code resume.apply(x)},
 *       the body has already advanced through the suspension; the
 *       handler's return value is the perform's eventual result.</li>
 *   <li>If the handler returns without calling {@code resume}, body never
 *       resumes — escape path. Handler's return is perform's result.</li>
 * </ul>
 *
 * <p>Exists as a Java-only validation that the protocol mechanically
 * works before wiring it through the Mezze compiler / interpreter.
 */
class EffectHandlerProtocolTest {

    /**
     * A simulated "perform" runner. Drives a body inside an EffectFiber.
     *
     * @param handler maps op-name → (args, resume) → result
     * @param body the body, calls runner.perform(op, args) to invoke ops
     * @return body's eventual result (either body's natural return value
     *         or what an op handler returned via escape)
     */
    static <T> T runWith(
            Handler handler,
            Function<Runner, T> body
    ) {
        Object[] performResult = new Object[1];
        EffectFiber[] fiberHolder = new EffectFiber[1];

        Runner runner = new Runner() {
            @Override
            public Object perform(String op, Object args) {
                // Body called an effect operation. Suspend the body, run the
                // op handler, and decide what to do.
                //
                // Step 1: capture the body's continuation by suspending the
                // fiber. The "suspendedValue" is the op-call descriptor.
                OpCall opCall = new OpCall(op, args);
                Object resumeValue = fiberHolder[0].suspend(opCall);
                // When body resumes (via setResumeValue + run), this returns
                // whatever the runner placed.
                return resumeValue;
            }
        };

        // Body wrapped in the fiber's runnable.
        fiberHolder[0] = EffectFiber.create("run_with-fiber", () -> {
            T result = body.apply(runner);
            // Body completed naturally; stash result for the runner.
            fiberHolder[0].setSuspendedValue(new BodyResult(result));
        });

        // Drive the fiber.
        while (true) {
            fiberHolder[0].run();
            Object suspended = fiberHolder[0].takeSuspendedValue();

            if (suspended instanceof BodyResult br) {
                // Body completed; its return is perform's result.
                @SuppressWarnings("unchecked")
                T value = (T) br.value;
                return value;
            }

            // Body suspended via an op call. Dispatch to the handler.
            OpCall opCall = (OpCall) suspended;
            HandlerOp opHandler = handler.ops.get(opCall.op);
            if (opHandler == null) {
                throw new RuntimeException("No handler for op: " + opCall.op);
            }

            // The "resume" function the op handler may call. Calling
            // resume drives the body forward and returns whatever value
            // body eventually produces (or another op's handler returns
            // if body suspends again).
            ResumeFn resume = new ResumeFn() {
                @Override
                public Object apply(Object resumeArg) {
                    return driveFromResume(handler, fiberHolder[0], resumeArg);
                }
            };

            // Op handler decides whether to call resume or escape.
            Object handlerResult = opHandler.invoke(opCall.args, resume);

            // If body completed via resume.apply, takeSuspendedValue would
            // have returned a BodyResult on the next iteration — we got out
            // through the inner loop. handlerResult is that value.
            //
            // If op handler escaped (didn't call resume), handlerResult is
            // the escape value. The op handler may have stashed `resume`
            // before escaping; if so, external code can drive the fiber
            // later via that stashed reference.
            //
            // Either way, handlerResult is the perform's result. We do NOT
            // close the fiber here — if it was stashed, GC will release
            // it when nobody references it anymore.
            @SuppressWarnings("unchecked")
            T r = (T) handlerResult;
            return r;
        }
    }

    /**
     * Helper called when an op handler invokes resume. Drives the body
     * forward and recursively dispatches if body suspends again before
     * completing.
     */
    static Object driveFromResume(Handler handler, EffectFiber fiber, Object resumeArg) {
        fiber.setResumeValue(resumeArg);
        fiber.run();
        Object next = fiber.takeSuspendedValue();
        if (next instanceof BodyResult br) {
            return br.value;
        }
        // Body suspended again on another op call — dispatch.
        OpCall opCall = (OpCall) next;
        HandlerOp opHandler = handler.ops.get(opCall.op);
        if (opHandler == null) {
            throw new RuntimeException("No handler for op: " + opCall.op);
        }
        ResumeFn nestedResume = (rArg) -> driveFromResume(handler, fiber, rArg);
        return opHandler.invoke(opCall.args, nestedResume);
    }

    // -- Test fixtures ---------------------------------------------------

    /** Runner gives the body a way to invoke an effect operation. */
    interface Runner {
        Object perform(String op, Object args);
    }

    interface ResumeFn {
        Object apply(Object value);
    }

    interface HandlerOp {
        Object invoke(Object args, ResumeFn resume);
    }

    static class Handler {
        final java.util.Map<String, HandlerOp> ops = new java.util.HashMap<>();
    }

    /** Marker: the body has completed normally. */
    record BodyResult(Object value) {}

    /** An effect-operation call from inside body. */
    record OpCall(String op, Object args) {}

    // -- Tests -----------------------------------------------------------

    @Test
    void body_completes_without_calling_any_op() {
        // The simplest case. Body just returns a value, no effects.
        Handler handler = new Handler();
        // No ops defined — body doesn't call any.

        Object result = runWith(handler, runner -> "hello");
        assertEquals("hello", result);
    }

    @Test
    void try_to_result_with_normal_return() {
        // Mimics: perform TryToResult in do
        //   compute_something
        //   Ok {val = ...}
        Handler handler = new Handler();
        handler.ops.put("fail", (args, resume) -> {
            // Escape: don't call resume; handler's return becomes perform's result.
            return new Err((String) args);
        });

        Object result = runWith(handler, runner -> {
            // body computes 42, no fail() call
            return new Ok(42);
        });

        assertEquals(new Ok(42), result);
    }

    @Test
    void try_to_result_with_fail() {
        // Mimics: perform TryToResult in do
        //   if bad then Try.fail {error = "..."}
        //   Ok {val = ...}
        Handler handler = new Handler();
        handler.ops.put("fail", (args, resume) -> {
            return new Err((String) args);    // escape
        });

        Object result = runWith(handler, runner -> {
            runner.perform("fail", "computation failed");
            // unreachable
            return new Ok(42);
        });

        assertEquals(new Err("computation failed"), result);
    }

    @Test
    void log_handler_calls_resume_with_unit() {
        // Mimics a Console-style handler: do work, then resume with {}.
        // body's value flows through normally.
        List<String> printed = new ArrayList<>();
        Handler handler = new Handler();
        handler.ops.put("log", (args, resume) -> {
            printed.add((String) args);
            return resume.apply("unit");    // continue body
        });

        Object result = runWith(handler, runner -> {
            runner.perform("log", "hello");
            runner.perform("log", "world");
            return "done";
        });

        assertEquals("done", result);
        assertEquals(List.of("hello", "world"), printed);
    }

    @Test
    void stash_pattern_streaming_generator() {
        // Streaming generator pattern: yield handler stashes resume into
        // an external slot, escapes with the yielded value. External
        // consumer pulls each value by reading the slot AND invoking
        // the saved resume to drive the producer to its next yield.
        //
        // This mirrors what std::control::stream's `next` would do.

        // External "slot" the handler stashes resume into.
        Object[] pendingResume = new Object[1];

        Handler handler = new Handler();
        handler.ops.put("yield", (args, resume) -> {
            // Stash resume so the consumer can call it later to advance
            // the producer.
            pendingResume[0] = resume;
            // Escape with the yielded value.
            return new Yielded(args);
        });

        // Producer body — yields 10, 20, 30, then returns.
        Function<Runner, Object> producer = runner -> {
            runner.perform("yield", 10);
            runner.perform("yield", 20);
            runner.perform("yield", 30);
            return new Done();
        };

        // First "next" — drives producer to first yield.
        Object first = runWith(handler, producer);
        assertTrue(first instanceof Yielded, "first should be a Yielded value");
        assertEquals(10, ((Yielded) first).value);

        // Now invoke the stashed resume — drives producer to next yield.
        // The resume is a ResumeFn we stashed; calling it returns whatever
        // the next op-handler returned (or body's final value).
        ResumeFn resume1 = (ResumeFn) pendingResume[0];
        Object second = resume1.apply("ack");
        assertTrue(second instanceof Yielded);
        assertEquals(20, ((Yielded) second).value);

        ResumeFn resume2 = (ResumeFn) pendingResume[0];
        Object third = resume2.apply("ack");
        assertTrue(third instanceof Yielded);
        assertEquals(30, ((Yielded) third).value);

        // Last resume — body returns naturally (no more yields).
        ResumeFn resume3 = (ResumeFn) pendingResume[0];
        Object end = resume3.apply("ack");
        assertTrue(end instanceof Done, "body's natural return reaches the consumer");
    }

    // -- Records mimicking Mezze variants for type-clarity in tests ------

    record Ok(Object val) {}
    record Err(String error) {}

    /** Generator yields this for each value. */
    record Yielded(Object value) {}

    /** Stream's natural completion. */
    record Done() {}
}
