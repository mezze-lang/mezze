package com.mezze.interpreter.nodes.statemachine;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Thread-local context for tracking the currently executing state machine.
 *
 * This enables continuation capture to know which state machine to capture
 * and what state to resume from.
 */
public final class StateMachineContext {

    /**
     * Execution context snapshot - captured when continuation is created.
     */
    public static final class ExecutionSnapshot {
        public final StateMachineNode stateMachine;
        public final VirtualFrame frame;
        public final int nextStateIndex;
        public final Object[] capturedLocals;

        public ExecutionSnapshot(StateMachineNode stateMachine, VirtualFrame frame,
                                 int nextStateIndex, Object[] capturedLocals) {
            this.stateMachine = stateMachine;
            this.frame = frame;
            this.nextStateIndex = nextStateIndex;
            this.capturedLocals = capturedLocals;
        }
    }

    // Thread-local current execution context
    private static final ThreadLocal<ExecutionSnapshot> CURRENT_CONTEXT = new ThreadLocal<>();

    /**
     * Get the current execution context.
     */
    @TruffleBoundary
    public static ExecutionSnapshot getCurrentContext() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * Set the current execution context.
     */
    @TruffleBoundary
    public static void setCurrentContext(ExecutionSnapshot context) {
        CURRENT_CONTEXT.set(context);
    }

    /**
     * Clear the current execution context.
     */
    @TruffleBoundary
    public static void clearCurrentContext() {
        CURRENT_CONTEXT.remove();
    }
}
