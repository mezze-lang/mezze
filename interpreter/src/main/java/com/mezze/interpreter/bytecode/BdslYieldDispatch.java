package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspended;
import com.mezze.interpreter.values.MezzeThunk;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Path D perform-op runtime — dispatches a BDSL-lowered
 * {@link com.mezze.language.truffleir.PerformOpIR} against the current
 * thread's effect handler chain
 * ({@link EffectFiber#peekHandlerForEffect}) without spinning up a
 * Loom-backed fiber.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Find the innermost handler for {@code effectHash} on the
 *       thread's chain.</li>
 *   <li>Look up the op impl by name in the handler record.</li>
 *   <li>Acquire a dispatch-only {@link EffectFiber} (recycled from a
 *       per-thread pool) and push a fresh {@code (resume, abort)} pair
 *       onto its per-op effect-handle stack. Existing
 *       {@code EffectBindingIR("resume")} / {@code ("abort")} reads
 *       inside the op impl body pick these up unchanged.</li>
 *   <li>Enter dispatch mode on the fiber (sets
 *       {@link EffectFiber#DISPATCH DISPATCH} thread-local so nested
 *       reads work).</li>
 *   <li>Call the op impl with {@code arg}.</li>
 *   <li>Catch {@link AbortPerformException} — its {@code value} becomes
 *       the perform expression's result. Otherwise the impl's return
 *       value is the result (fall-through mode).</li>
 * </ol>
 *
 * <h2>What resume does under Path D</h2>
 *
 * <p>Path A's {@code resume {val = X}} re-enters the fiber's
 * {@code drive} loop with X as the yielded value's answer and runs the
 * body forward. Path D has no suspended fiber — the perform is a
 * straight-line call. So Path D's {@code resume} is <b>fall-through
 * only</b>: calling {@code resume {val = X}} returns X, and any code
 * the impl runs AFTER {@code resume} is discarded. This is a
 * behavioural narrowing from Path A.
 *
 * <p>All existing stdlib effect handlers except one work under Path D:
 * Exception, Return, Log, Mut, Time, Random, FileSystem, etc. use only
 * {@code abort} or fall-through. The one exception is
 * {@code Generator.AsStream}, which captures {@code resume} inside an
 * abort payload and calls it lazily — that pattern requires a real
 * continuation. Generator stays on Path A for now (Path D6+ picks it up
 * via the BDSL Yield primitive).
 *
 * <h2>Unhandled ops</h2>
 *
 * <p>Currently throws {@link UnhandledPathDOp}. The Loom path wraps
 * these in {@code MezzeSuspended} for lazy evaluation — Path D catches
 * up in a later phase.
 */
public final class BdslYieldDispatch {

    private BdslYieldDispatch() {}

    /**
     * Dispatch one perform against the current thread's handler chain
     * and return the value the perform expression should evaluate to.
     * See class docs.
     */
    @TruffleBoundary
    public static Object dispatch(Hash effectHash, String opName, Object arg) {
        MezzeRecord handler = EffectFiber.peekHandlerForEffect(effectHash);
        if (handler == null) {
            // No handler in scope — Path A wraps the op in a MezzeSuspended
            // so an outer perform (installed later, e.g. `let x = do_stuff
            // {} ; perform Handler in force x`) can force it. Same shape.
            return makeUnhandledSuspended(effectHash, opName, arg);
        }
        Object opImplRaw = handler.getField(opName);
        if (!(opImplRaw instanceof MezzeFunction opImpl)) {
            throw new IllegalStateException(
                "Path D dispatch: handler for " + effectHash + "." + opName
                + " field is not a MezzeFunction (got "
                + (opImplRaw == null ? "null" : opImplRaw.getClass().getName()) + ")");
        }

        // Set up a dispatch-only fiber that carries resume/abort as
        // top-of-stack effect-handles for EffectBindingIR to find.
        EffectFiber dispatchFiber = EffectFiber.acquireDispatchOnly();
        MezzeFunction resumeFn = fallThroughResumeFn();
        MezzeFunction abortFn = EffectHandlerDriver.makeAbortFunction();
        dispatchFiber.pushEffectHandles(resumeFn, abortFn);
        EffectFiber prevDispatch = dispatchFiber.enterDispatch();

        try {
            Object[] cv = opImpl.getClosureValues();
            Object[] opArgs = new Object[cv.length + 1];
            System.arraycopy(cv, 0, opArgs, 0, cv.length);
            opArgs[cv.length] = arg;
            return IndirectCallNode.getUncached().call(opImpl.getCallTarget(), opArgs);
        } catch (AbortPerformException abort) {
            // Abort semantics differ between main thread and spawned task:
            //   - Main thread: let AbortPerformException propagate up to
            //     the outer `perform ... in body` boundary (which runs on
            //     Path A via EffectHandlerDriver.run since HandleIR isn't
            //     BDSL-lowered). That driver catches abort and returns
            //     the aborted value as the perform's result.
            //   - Spawned task: the outer perform lives on the parent
            //     fiber, not this thread. Convert to
            //     TaskUnhandledOpException so SpawnSupport stashes the op
            //     on the promise and Async.await re-issues it on the
            //     parent — mirrors OpCallNode.dispatchOpOnChain's
            //     handling of the same case (#285).
            if (EffectHandlerDriver.isInsideTaskBoundary()) {
                throw new TaskUnhandledOpException(new OpCall(opName, arg, effectHash));
            }
            throw abort;
        } finally {
            EffectFiber.exitDispatch(prevDispatch);
            dispatchFiber.popEffectHandles();
            EffectFiber.releaseDispatchOnly(dispatchFiber);
        }
    }

    // ── resume: fall-through semantics ──────────────────────────────────
    //
    // Path A's resume re-enters the fiber's drive loop. Path D has no
    // fiber, so resume is fall-through: calling resume {val = X} returns
    // X. Anything the impl does AFTER calling resume is discarded. See
    // class-doc "What resume does under Path D" for the caveat.

    private static volatile MezzeFunction CACHED_FALLTHROUGH_RESUME_FN = null;

    private static MezzeFunction fallThroughResumeFn() {
        MezzeFunction cached = CACHED_FALLTHROUGH_RESUME_FN;
        if (cached != null) return cached;
        return buildAndCacheFallThroughResumeFn();
    }

    @TruffleBoundary
    private static synchronized MezzeFunction buildAndCacheFallThroughResumeFn() {
        if (CACHED_FALLTHROUGH_RESUME_FN != null) return CACHED_FALLTHROUGH_RESUME_FN;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                int recordIdx = frameArgs.length - 1;
                if (recordIdx < 0 || !(frameArgs[recordIdx] instanceof MezzeRecord rec)) {
                    throw new RuntimeException(
                        "resume expects record argument {val = ...}");
                }
                return rec.getField("val");
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            body,
            "path-d-resume-fallthrough",
            0,       // closureCount
            1,       // paramCount (the {val = ...} record)
            new int[0]
        ).getCallTarget();
        MezzeFunction fn = new MezzeFunction(target, "resume", new Object[0]);
        CACHED_FALLTHROUGH_RESUME_FN = fn;
        return fn;
    }

    /**
     * Build a lazy {@link MezzeSuspended} value carrying the unhandled
     * op — same shape as {@link EffectHandlerDriver#makeUnhandledSuspended}.
     * Used when a perform fires with no matching handler in scope; an
     * outer handler installed later can force this and dispatch.
     */
    @TruffleBoundary
    private static MezzeSuspended makeUnhandledSuspended(Hash effectHash, String opName, Object arg) {
        var thunk = new MezzeThunk(/* effectName */ null, opName, arg, effectHash);
        java.util.Set<Hash> effects = effectHash != null
            ? java.util.Collections.singleton(effectHash)
            : java.util.Collections.emptySet();
        return new MezzeSuspended(thunk, effects);
    }

    /**
     * Legacy marker exception — kept for the {@link BdslYieldDriver}
     * test harness which was written before we adopted MezzeSuspended
     * wrapping. Not thrown by {@link #dispatch} anymore.
     */
    public static final class UnhandledPathDOp extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnhandledPathDOp(Hash effectHash, String opName) {
            super("No handler in scope for effect " + effectHash + " op " + opName);
        }
    }

    /**
     * Legacy entry point for {@link BdslYieldDriver} — walks a
     * ContinuationResult chain if one flows out (which no longer happens
     * after D4 since the emit switched from Yield to a straight call,
     * but kept for the D2 test harness).
     */
    @TruffleBoundary
    public static Object driveWithChainDispatch(Object initial) {
        return BdslYieldDriver.drive(initial, BdslYieldDispatch::dispatch);
    }

    /** Debug helper — number of handlers on this thread's chain. */
    public static int currentChainDepth() {
        HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();
        return chain.length;
    }
}
