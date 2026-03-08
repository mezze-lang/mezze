package com.mezze.interpreter.v2.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Exception thrown to signal a tail call.
 *
 * Instead of creating a new stack frame for tail-recursive calls,
 * we throw this exception which is caught by TailCallLoopNode.
 * The loop then rebinds the arguments and continues execution
 * without growing the stack.
 */
public final class TailCallException extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    private final CallTarget callTarget;
    private final Object[] arguments;

    public TailCallException(CallTarget callTarget, Object[] arguments) {
        this.callTarget = callTarget;
        this.arguments = arguments;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object[] getArguments() {
        return arguments;
    }
}
