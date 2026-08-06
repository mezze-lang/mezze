package com.mezze.interpreter.runtime;

import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Per-hash entry in the {@link SlotTable}. Holds the compiled {@link
 * RootCallTarget} for a reachable function and, for zero-arg thunks, a
 * lazily-evaluated cached result.
 *
 * <p>Written once per Truffle Engine at Phase-2 emit; read on every execute
 * thread through {@link com.oracle.truffle.api.nodes.DirectCallNode} /
 * {@link IndirectCallNode}. Truffle's partial evaluator treats a
 * {@code @CompilationFinal RootCallTarget} field as a constant after first
 * read, which is the entire point of the slot table.
 *
 * <p>Scaffold added in #391 Step 1 — nothing reads or writes this yet.
 */
public final class SlotEntry {

    private final Hash hash;

    /**
     * Compiled call target for this hash. Written once by Phase-2 emit
     * ({@link #publish}); null before then.
     */
    @CompilerDirectives.CompilationFinal
    private volatile RootCallTarget ct;

    /**
     * Cached result of evaluating a zero-arg thunk. Written at most once
     * per Engine on first read (see {@link #getOrEvaluate}). Guarded by
     * {@link #thunkStableAssumption}.
     */
    @CompilerDirectives.CompilationFinal
    private volatile Object cachedThunkValue;

    @CompilerDirectives.CompilationFinal
    private volatile boolean thunkEvaluated;

    /**
     * Held stable by callers that speculate on a still-unevaluated thunk;
     * invalidated when {@link #getOrEvaluate} publishes a value so
     * dependent compiled code deopts and re-specializes on the constant.
     */
    private final Assumption thunkStableAssumption;

    SlotEntry(Hash hash) {
        this.hash = hash;
        this.thunkStableAssumption =
                Truffle.getRuntime().createAssumption("slot-thunk:" + hash.getHex());
    }

    public Hash getHash() {
        return hash;
    }

    /** Set once per Engine at Phase-2 emit. */
    public void publish(RootCallTarget ct) {
        if (this.ct != null) {
            throw new IllegalStateException("slot " + hash.getHex() + " double-published");
        }
        this.ct = ct;
    }

    public RootCallTarget getCallTarget() {
        return ct;
    }

    public boolean isPublished() {
        return ct != null;
    }

    public Assumption getThunkStableAssumption() {
        return thunkStableAssumption;
    }

    public boolean isThunkEvaluated() {
        return thunkEvaluated;
    }

    public Object getCachedThunkValue() {
        return cachedThunkValue;
    }

    /**
     * Evaluate and cache. Called from {@code ReadGlobalByHashNode} when it
     * discovers a zero-arg FunctionIR slot without a cached value.
     *
     * <p>Concurrency: two threads may race the first evaluation. We accept
     * the double-eval (top-level thunks are pure by construction — a
     * typechecker invariant we rely on) and race the volatile writes; the
     * assumption invalidate happens once.
     */
    public Object getOrEvaluate(IndirectCallNode caller) {
        if (thunkEvaluated) {
            return cachedThunkValue;
        }
        Object result = caller.call(ct, MezzeUnit.INSTANCE);
        cachedThunkValue = result;
        thunkEvaluated = true;
        thunkStableAssumption.invalidate();
        return result;
    }

    /**
     * Read the slot's value the way {@code ReadGlobalByHashNode} would:
     * force zero-arg thunks (top-level {@code let x = expr}) on first read
     * and cache the result; otherwise return a {@link
     * com.mezze.interpreter.values.MezzeFunction} wrapping the call target.
     *
     * <p>Mirrors {@code ReadGlobalByHashNode.autoEvaluateThunk} — same
     * isThunk detection across {@code MezzeRootNode}, {@code
     * BdslTrampolineRootNode}, and bare {@code MezzeBytecodeRootNode}.
     */
    @CompilerDirectives.TruffleBoundary
    public Object resolveValue(String nameHint) {
        if (thunkEvaluated) {
            return cachedThunkValue;
        }
        if (ct == null) {
            throw new IllegalStateException("slot " + hash.getHex() + " not published");
        }
        var root = ct.getRootNode();
        boolean isThunk = false;
        if (root instanceof com.mezze.interpreter.nodes.MezzeRootNode mrn) {
            isThunk = mrn.getClosureCount() == 0 && mrn.getParameterCount() == 0;
        } else if (root instanceof com.mezze.interpreter.bytecode.BdslTrampolineRootNode t) {
            isThunk = t.getClosureCount() == 0 && t.getParameterCount() == 0;
        } else if (root instanceof com.mezze.interpreter.bytecode.MezzeBytecodeRootNode) {
            isThunk = true;
        }
        if (isThunk) {
            try {
                Object result = ct.call(MezzeUnit.INSTANCE);
                // #392 — do NOT cache when result is a MezzeSuspend: the
                // suspended value is handler-context-sensitive; caching it
                // would freeze the outer handler chain at first read and
                // give wrong answers to later reads under a different
                // perform block. Same for MezzeThunk. Return without
                // setting thunkEvaluated so the next read re-evaluates.
                if (result instanceof com.mezze.interpreter.values.MezzeSuspend
                        || result instanceof com.mezze.interpreter.values.MezzeThunk) {
                    return result;
                }
                cachedThunkValue = result;
                thunkEvaluated = true;
                thunkStableAssumption.invalidate();
                return result;
            } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                // #392 — abort is a handler-unwind signal. If we swallow it,
                // the abort's payload never reaches the perform's handler.
                // Let it propagate; the currently-active perform will catch
                // it and produce the correct Result value.
                throw abort;
            } catch (Exception e) {
                // Not really a thunk (side effects, wrong shape); fall through.
            }
        }
        return com.mezze.interpreter.values.MezzeFunction.create(ct, nameHint);
    }
}
