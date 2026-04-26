package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Exception thrown when a continuation is suspended via suspend_with.
 *
 * This exception is used for control flow to escape immediately to the
 * perform/handle boundary, bypassing any intermediate expressions that
 * would otherwise wrap the suspended value.
 *
 * For example, in:
 *   perform ExceptionToResult in Ok {val = may_throw {}}
 *
 * When may_throw calls suspend_with {value = Err {error}}, this exception
 * ensures the Err value becomes the result of the entire perform expression,
 * not wrapped inside Ok {val = Err {...}}.
 */
public class ContinuationSuspendedException extends ControlFlowException {
    private static final long serialVersionUID = 1L;

    public final MezzeContinuationSuspended suspended;

    public ContinuationSuspendedException(MezzeContinuationSuspended suspended) {
        this.suspended = suspended;
    }

    /**
     * Get the suspended value (the value passed to suspend_with).
     */
    public Object getSuspendedValue() {
        return suspended.getSuspendedValue();
    }

    /**
     * Get the continuation (for resuming if needed).
     */
    public MezzeContinuation getContinuation() {
        return suspended.getContinuation();
    }
}
