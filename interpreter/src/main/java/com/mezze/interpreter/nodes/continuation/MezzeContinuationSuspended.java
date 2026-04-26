package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * Suspended continuation with an attached value.
 *
 * This is what suspend_with returns - a continuation paired with a value.
 * Used for generators (yield) and effect handlers (early return).
 *
 * Pattern:
 *   let result = perform Handler in do
 *       let x = Effect.operation {}  // <-- suspend_with returns here
 *       x + 1                        // <-- this is the continuation
 *
 * The result of the perform expression is MezzeContinuationSuspended:
 *   - suspendedValue: the yielded value from suspend_with
 *   - continuation: function to resume with a new value
 */
public final class MezzeContinuationSuspended implements TruffleObject {

    @CompilationFinal
    private final Object suspendedValue;

    @CompilationFinal
    private final MezzeContinuation continuation;

    public MezzeContinuationSuspended(Object suspendedValue, MezzeContinuation continuation) {
        this.suspendedValue = suspendedValue;
        this.continuation = continuation;
    }

    public Object getSuspendedValue() {
        return suspendedValue;
    }

    public MezzeContinuation getContinuation() {
        return continuation;
    }

    /**
     * Check if this is a suspended continuation.
     * Static helper for pattern matching.
     */
    public static boolean isSuspended(Object value) {
        return value instanceof MezzeContinuationSuspended;
    }

    @Override
    public String toString() {
        return "Suspended{value=" + suspendedValue + ", cont=" + continuation + "}";
    }
}
