package com.mezze.interpreter.nodes.continuation;

import com.mezze.language.typedast.Hash;

/**
 * Payload an op call site passes to {@link EffectFiber#suspend(Object)}
 * when invoking an effect operation.
 *
 * <p>The driver reads this from {@link EffectFiber#takeSuspendedValue()},
 * looks up {@code handler.<opName>}, and calls it with
 * {@code {args, resume}}. Whatever the op handler returns becomes the
 * value the fiber's suspend returns when {@link EffectFiber#run()} is
 * called again.
 *
 * @param opName     the operation name (e.g. "raise", "yield")
 * @param args       the argument record passed by the call site
 * @param effectHash content hash of the effect declaration this op belongs to.
 *                   Used when the driver hits no handler and needs to bubble
 *                   the call up as a {@code MezzeSuspended} carrying the
 *                   unhandled effect. May be {@code null} for callers that
 *                   don't have a hash (e.g. cross-thread re-raise sites).
 */
public record OpCall(String opName, Object args, Hash effectHash) {
    /** Convenience constructor for callers without an effect hash. */
    public OpCall(String opName, Object args) {
        this(opName, args, null);
    }
}
