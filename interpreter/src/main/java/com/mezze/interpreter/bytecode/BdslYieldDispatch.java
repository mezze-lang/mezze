package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Path D3 glue between {@link BdslYieldDriver} and the existing
 * per-thread effect handler chain
 * ({@link EffectFiber#peekHandlerForEffect}).
 *
 * <p>When a BDSL-compiled function yields (Phase D1 emits
 * {@code beginYield(MakeRecord{effect_hash, op_name, arg}) endYield}
 * at every {@code PerformOpIR}), {@link BdslYieldDriver#drive} passes
 * the (hash, name, arg) triple here. We:
 *
 * <ol>
 *   <li>Look up the innermost handler for {@code effectHash} on the
 *       thread's {@link EffectFiber#peekHandlerForEffect chain}.</li>
 *   <li>Find its op impl by name via
 *       {@link MezzeRecord#getField(String)}.</li>
 *   <li>Push a dispatch-only fiber whose per-op-invocation handle
 *       stack carries a fresh {@code resume} / {@code abort} pair that
 *       close over the current {@link ContinuationResult}. Existing
 *       {@code EffectBindingIR} reads inside the op impl body pick
 *       those up unchanged.</li>
 *   <li>Invoke the op impl with {@code arg}.</li>
 *   <li>Handle the fall-through case (impl returned without calling
 *       resume) by returning that value as the perform's result — same
 *       semantics as the Loom path.</li>
 * </ol>
 *
 * <p>MVP scope (D3):
 * <ul>
 *   <li>Only handles the fall-through pattern (impl returns a value
 *       without invoking resume). Handler that explicitly calls resume
 *       is deferred to D3.1 — needs a {@link MezzeFunction}-shaped
 *       resume closure that consumes the continuation.</li>
 *   <li>Abort surfaces as a Java exception the driver already re-throws
 *       (see {@link BdslYieldDriver#drive}).</li>
 *   <li>Unhandled op (no handler on chain for {@code effectHash})
 *       throws — no MezzeSuspended wrap yet.</li>
 * </ul>
 */
public final class BdslYieldDispatch {

    private BdslYieldDispatch() {}

    /**
     * Dispatch one yielded op against the current thread's handler
     * chain and return the value the perform expression should
     * evaluate to.
     *
     * <p>Uses a shared {@link IndirectCallNode} — this is a fallback
     * path, not a hot dispatch; the JIT will inline through it fine
     * once specialized.
     */
    @TruffleBoundary
    public static Object dispatch(Hash effectHash, String opName, Object arg) {
        MezzeRecord handler = EffectFiber.peekHandlerForEffect(effectHash);
        if (handler == null) {
            throw new UnhandledPathDOp(effectHash, opName);
        }
        Object opImplRaw = handler.getField(opName);
        if (!(opImplRaw instanceof MezzeFunction opImpl)) {
            throw new IllegalStateException(
                "Path D dispatch: handler for " + effectHash + "." + opName
                + " field is not a MezzeFunction (got "
                + (opImplRaw == null ? "null" : opImplRaw.getClass().getName()) + ")");
        }

        // For MVP D3 we don't yet bind resume/abort — impl body must
        // work in "fall-through" mode (return a value, which becomes
        // the perform's result). D3.1 will wire resume/abort closures
        // via a dispatch-only fiber, at which point existing
        // EffectBindingIR reads pick them up unchanged.
        IndirectCallNode callNode = IndirectCallNode.getUncached();
        Object[] cv = opImpl.getClosureValues();
        Object[] opArgs = new Object[cv.length + 1];
        System.arraycopy(cv, 0, opArgs, 0, cv.length);
        opArgs[cv.length] = arg;
        return callNode.call(opImpl.getCallTarget(), opArgs);
    }

    /**
     * Marker exception for a yielded op with no matching handler on
     * the current thread's chain. Signals a bug for now — Phase D4
     * hooks in the MezzeSuspended-wrap fallback so unhandled ops
     * propagate lazily like the Loom path does.
     */
    public static final class UnhandledPathDOp extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnhandledPathDOp(Hash effectHash, String opName) {
            super("No handler in scope for effect " + effectHash + " op " + opName);
        }
    }

    /**
     * Convenience: wrap {@link BdslYieldDriver#drive} with a dispatcher
     * pointed at this thread's handler chain. Useful in the epilog
     * hook and in any call site that wants to drive a
     * {@link ContinuationResult} to completion transparently.
     */
    @TruffleBoundary
    public static Object driveWithChainDispatch(Object initial) {
        return BdslYieldDriver.drive(initial, BdslYieldDispatch::dispatch);
    }

    /**
     * Debug helper: snapshot the current thread's handler chain
     * length. Used in tests to confirm handler installation happened
     * before Yield firing.
     */
    public static int currentChainDepth() {
        HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();
        return chain.length;
    }
}
