package com.mezze.interpreter.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Exception thrown to signal a cross-function tail call.
 *
 * Mutable fields — allocated once per call site via @Cached and reused
 * across throws. Truffle ControlFlowExceptions intentionally do not
 * preserve stack traces, so reuse is safe. Matches the pattern used by
 * {@link TailCallScalarException} for self-recursive tail calls.
 */
public final class TailCallException extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    private CallTarget callTarget;
    private Object[] arguments;

    public TailCallException() {
        // Empty — fields populated by fill() before each throw.
    }

    public TailCallException(CallTarget callTarget, Object[] arguments) {
        this.callTarget = callTarget;
        this.arguments = arguments;
    }

    public TailCallException fill(CallTarget callTarget, Object[] arguments) {
        this.callTarget = callTarget;
        this.arguments = arguments;
        return this;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object[] getArguments() {
        return arguments;
    }
}
