package com.mezze.interpreter.values;

import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Thunk - a lazy function call with its arguments captured.
 *
 * Used to defer execution of functions with unhandled effects.
 * Unlike partial application, MezzeThunk represents a complete function call
 * that's ready to execute but is waiting for effect handlers.
 *
 * Example:
 *   perform Console in demo {}
 *   - demo is a function with <Log, FileSystem> effects
 *   - Console handles Log, but FileSystem is unhandled
 *   - Instead of executing demo{} immediately, we create MezzeThunk(demo, {})
 *   - The thunk is wrapped in MezzeSuspended with unhandledEffects={FileSystem}
 *   - When FileSystem handler is provided, the thunk executes
 *
 * Thunks can also wrap deferred effect operations - when an effect operation
 * is called without a handler, we create a thunk that captures the operation
 * and its arguments for later execution.
 */
public class MezzeThunk implements TruffleObject {

    // For Truffle function thunks
    @CompilationFinal
    private final MezzeFunction function;

    // Captured arguments for the call
    @CompilationFinal
    private final Object arguments;

    // For deferred effect operations
    @CompilationFinal
    private final String effectName;

    @CompilationFinal
    private final String operationName;

    /** Content hash for effect lookup (preferred over effectName when available) */
    @CompilationFinal
    private final Hash effectContentHash;

    /**
     * Create a thunk for a regular function call.
     */
    public MezzeThunk(MezzeFunction function, Object arguments) {
        this.function = function;
        this.arguments = arguments;
        this.effectName = null;
        this.operationName = null;
        this.effectContentHash = null;
    }

    /**
     * Create a thunk for a deferred effect operation.
     * The function will be resolved later when handlers become available.
     */
    public MezzeThunk(String effectName, String operationName, Object arguments) {
        this.function = null;
        this.arguments = arguments;
        this.effectName = effectName;
        this.operationName = operationName;
        this.effectContentHash = null;
    }

    /**
     * Create a thunk for a deferred effect operation with content hash.
     * The content hash enables O(1) collision-free handler lookup.
     */
    public MezzeThunk(String effectName, String operationName, Object arguments, Hash effectContentHash) {
        this.function = null;
        this.arguments = arguments;
        this.effectName = effectName;
        this.operationName = operationName;
        this.effectContentHash = effectContentHash;
    }

    /**
     * Protected constructor for subclasses (e.g., ComposedBlockThunk).
     */
    protected MezzeThunk() {
        this.function = null;
        this.arguments = null;
        this.effectName = null;
        this.operationName = null;
        this.effectContentHash = null;
    }

    public MezzeFunction getFunction() {
        return function;
    }

    public Object getArguments() {
        return arguments;
    }

    public String getEffectName() {
        return effectName;
    }

    public String getOperationName() {
        return operationName;
    }

    /** Get the content hash for effect lookup (may be null) */
    public Hash getEffectContentHash() {
        return effectContentHash;
    }

    public boolean isDeferredEffectCall() {
        return effectName != null;
    }

    /**
     * Execute the thunk using the provided call node.
     * For deferred effect calls, the handler must be resolved from context first.
     */
    public Object execute(IndirectCallNode callNode) {
        if (function == null) {
            throw new RuntimeException(
                "Cannot execute deferred effect thunk directly. " +
                "Effect: " + effectName + ", operation: " + operationName
            );
        }

        CallTarget target = function.getCallTarget();
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;

        // Build arguments array with closure values prepended
        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{arguments};
        } else {
            fullArgs = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                fullArgs[i] = closureValues[i];
            }
            fullArgs[closureLength] = arguments;
        }

        return callNode.call(target, fullArgs);
    }

    /**
     * Execute the thunk with closure values already provided.
     */
    public Object executeWithClosure(IndirectCallNode callNode, Object[] closureValues) {
        if (function == null) {
            throw new RuntimeException(
                "Cannot execute deferred effect thunk directly. " +
                "Effect: " + effectName + ", operation: " + operationName
            );
        }

        CallTarget target = function.getCallTarget();
        int closureLength = closureValues.length;

        // Build arguments array with closure values prepended
        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{arguments};
        } else {
            fullArgs = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                fullArgs[i] = closureValues[i];
            }
            fullArgs[closureLength] = arguments;
        }

        return callNode.call(target, fullArgs);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        if (effectName != null) {
            return "<thunk:" + effectName + "." + operationName + ">";
        }
        return "<thunk>";
    }
}
