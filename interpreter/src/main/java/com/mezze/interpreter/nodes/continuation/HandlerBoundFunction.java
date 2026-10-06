package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * S-20: a function value with a handler chain bound into it.
 *
 * `perform Console in my_func` produces `my_func` — it does not call it. A
 * Handle lowers to push / body / pop, so without this the handler is popped
 * before the returned function is ever invoked:
 *
 *     let handled = perform Console in my_func   -- type: {} -> Str <*>
 *     handled {}                                 -- printed NOTHING, exit 0
 *
 * while the identical `perform Console in my_func {}` (handler around the CALL)
 * printed correctly. The type system discharges the effect from the function's
 * type and 19 tests pin that, so the value has to carry the handler to make the
 * type true.
 *
 * <p>{@link #wrap} snapshots the chain that is live AT THE POINT THE FUNCTION IS
 * PRODUCED — inside the Handle, with its handlers installed — and returns a new
 * {@link MezzeFunction} that re-installs that chain for the duration of each
 * call. The original function is untouched, which is what
 * {@code ComprehensiveEffectHandlingTest} "perform on uncalled function does not
 * mutate original function" asks for.
 *
 * <p>Capturing the WHOLE live chain rather than only this Handle's own entries
 * is deliberate and is the same thing a spawned task does
 * ({@code AsyncNatives} → {@link HandlerChainRegistry#snapshotHandlerChainEntries}):
 * the function should run under the handlers that were in effect where it was
 * made. Re-installing an entry that is still live at call time is harmless — it
 * is the same handler, and the innermost wins either way.
 */
public final class HandlerBoundFunction {

    private HandlerBoundFunction() {}

    /**
     * Bind the currently-installed handler chain into {@code value}.
     *
     * Returns {@code value} unchanged when it is not a function (the Handle's
     * body produced something else) or when no chain is installed — in both
     * cases there is nothing to carry, and allocating a wrapper would only cost.
     */
    @TruffleBoundary
    public static Object bindCurrentChain(Object value) {
        if (!(value instanceof MezzeFunction fn)) {
            return value;
        }
        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        if (chain.length == 0) {
            return value;
        }
        return wrap(fn, chain);
    }

    @TruffleBoundary
    private static MezzeFunction wrap(MezzeFunction inner, HandlerChainEntry[] chain) {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Child private IndirectCallNode callNode = IndirectCallNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                return invokeUnderChain(inner, chain, frame.getArguments(), callNode);
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            body,
            "handler-bound",
            0,
            1,
            new int[0]
        ).getCallTarget();
        // Parameter names carry over so partial application keeps working on the
        // wrapped value exactly as it did on the original.
        return new MezzeFunction(target, inner.getName(), new Object[0], inner.getParameterNames());
    }

    /**
     * Install {@code chain}, call {@code inner} with {@code args}, pop.
     *
     * The result is carried out through a one-element array because
     * {@link HandlerChainRegistry#runWithHandlerChain} takes a {@link Runnable} —
     * reusing it rather than open-coding push/pop keeps the teardown (including
     * scope restoration) in the one place that already gets it right.
     */
    @TruffleBoundary
    private static Object invokeUnderChain(
            MezzeFunction inner,
            HandlerChainEntry[] chain,
            Object[] args,
            IndirectCallNode callNode) {
        Object[] out = new Object[1];
        HandlerChainRegistry.runWithHandlerChain(
            chain,
            () -> out[0] = callNode.call(inner.getCallTarget(), args));
        return out[0];
    }
}
