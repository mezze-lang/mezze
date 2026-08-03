package com.mezze.interpreter.values;

import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * Deferred effect-op call: {@code (opName, args, effectHash)}.
 *
 * <p>Produced by {@link com.mezze.interpreter.nodes.effect.OpCallNode} and
 * friends when a perform lands with no fiber and no matching handler in the
 * chain. Wrapped in a {@link MezzeSuspend} and forced later — force()
 * dispatches through the then-installed handler chain.
 *
 * <p>Historically this class also held a "regular function thunk" shape
 * (an unhandled Handle body wrapped as a callable). That role is now
 * carried by {@link MezzeSuspend#captureContinuation} (Path E, #336) —
 * this class is op-defers only.
 */
public class MezzeThunk implements TruffleObject {

    @CompilationFinal
    private final Object arguments;

    @CompilationFinal
    private final String operationName;

    /** Content hash for effect lookup. Required — see class-level note in
     *  {@link com.mezze.interpreter.nodes.continuation.OpCall} on why every
     *  caller must thread the hash to avoid #246/#55 silent misroute. */
    @CompilationFinal
    private final Hash effectContentHash;

    public MezzeThunk(String operationName, Object arguments, Hash effectContentHash) {
        this.arguments = arguments;
        this.operationName = operationName;
        this.effectContentHash = effectContentHash;
    }

    public Object getArguments() {
        return arguments;
    }

    public String getOperationName() {
        return operationName;
    }

    public Hash getEffectContentHash() {
        return effectContentHash;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "<thunk:" + operationName + ">";
    }
}
