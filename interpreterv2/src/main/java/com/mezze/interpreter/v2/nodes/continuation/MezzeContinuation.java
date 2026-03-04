package com.mezze.interpreter.v2.nodes.continuation;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * Captured continuation - a resumable computation.
 *
 * V2 Truffle-optimized implementation:
 * - Uses CallTarget for JIT-compiled continuation execution
 * - Closure values for captured environment
 * - State index for multi-state continuations
 *
 * Key insight: A continuation is just a function that represents
 * "the rest of the computation" from the capture point.
 */
public final class MezzeContinuation implements TruffleObject {

    @CompilationFinal
    private final CallTarget callTarget;

    @CompilationFinal(dimensions = 1)
    private final Object[] capturedValues;

    @CompilationFinal
    private final int stateIndex;

    @CompilationFinal
    private final long delimiterId;

    public MezzeContinuation(CallTarget callTarget, Object[] capturedValues, int stateIndex, long delimiterId) {
        this.callTarget = callTarget;
        this.capturedValues = capturedValues;
        this.stateIndex = stateIndex;
        this.delimiterId = delimiterId;
    }

    /**
     * Create a simple continuation with no captured values.
     */
    public static MezzeContinuation create(CallTarget callTarget, long delimiterId) {
        return new MezzeContinuation(callTarget, new Object[0], 0, delimiterId);
    }

    /**
     * Create a continuation with captured values.
     */
    public static MezzeContinuation create(CallTarget callTarget, Object[] capturedValues, long delimiterId) {
        return new MezzeContinuation(callTarget, capturedValues, 0, delimiterId);
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object[] getCapturedValues() {
        return capturedValues;
    }

    public int getStateIndex() {
        return stateIndex;
    }

    public long getDelimiterId() {
        return delimiterId;
    }

    /**
     * Resume this continuation with a value.
     * The value is passed as the first argument after captured values.
     */
    public Object resume(Object value) {
        int capturedLen = capturedValues.length;
        Object[] args = new Object[capturedLen + 1];
        System.arraycopy(capturedValues, 0, args, 0, capturedLen);
        args[capturedLen] = value;
        return callTarget.call(args);
    }

    /**
     * Create a new continuation with a different state index.
     * Used for multi-state continuations (generators, etc.).
     */
    public MezzeContinuation withStateIndex(int newStateIndex) {
        return new MezzeContinuation(callTarget, capturedValues, newStateIndex, delimiterId);
    }

    @Override
    public String toString() {
        return "MezzeContinuation@" + delimiterId + "[state=" + stateIndex + "]";
    }
}
