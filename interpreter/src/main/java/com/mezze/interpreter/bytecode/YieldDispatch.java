package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspend;
import com.mezze.interpreter.values.MezzeThunk;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Perform-op runtime — dispatches a BDSL-lowered
 * {@link com.mezze.language.truffleir.PerformOpIR} against the current
 * thread's effect handler chain
 * ({@link HandlerChainRegistry#peekHandlerForEffect}) — no fiber, no reflection.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Find the innermost handler for {@code effectHash} on the
 *       thread's chain.</li>
 *   <li>Look up the op impl by name in the handler record.</li>
 *   <li>Acquire a dispatch-only registry entry (recycled from a
 *       per-thread pool) and push a fresh {@code (resume, abort)} pair
 *       onto its per-op effect-handle stack. Existing
 *       {@code EffectBindingIR("resume")} / {@code ("abort")} reads
 *       inside the op impl body pick these up unchanged.</li>
 *   <li>Enter dispatch mode (sets
 *       {@link HandlerChainRegistry#DISPATCH DISPATCH} thread-local so nested
 *       reads work).</li>
 *   <li>Call the op impl with {@code arg}.</li>
 *   <li>Catch {@link AbortPerformException}. If a BDSL Handle boundary
 *       is on the D8 stack, rethrow so its TryCatchOtherwise catch
 *       branch reads the value via UnwrapAbortValue. Otherwise (top
 *       level, or spawned task thread with no Handle in scope),
 *       convert to {@link TaskUnhandledOpException} — {@code SpawnSupport}
 *       stashes it on the promise and {@code Async.await} re-issues on
 *       the parent.</li>
 * </ol>
 *
 * <h2>What resume does</h2>
 *
 * <p>{@code resume} here is <b>fall-through only</b>: calling
 * {@code resume {val = X}} returns X, and any code the impl runs AFTER
 * {@code resume} is discarded. There is no suspended continuation to
 * re-enter — the perform is a straight-line call.
 *
 * <p>All existing stdlib effect handlers work under this model:
 * Exception, Return, Log, Mut, Time, Random, FileSystem, etc. use only
 * {@code abort} or fall-through. Patterns that would require capturing
 * {@code resume} inside an abort payload and calling it lazily need a
 * real continuation — those go through the BDSL Yield primitive
 * (Generator.AsStream is the historical example).
 *
 * <h2>Unhandled ops</h2>
 *
 * <p>Wrapped in a {@link MezzeSuspend} so an outer perform installed
 * later (e.g. {@code let x = do_stuff {} ; perform Handler in force x})
 * can force and dispatch it.
 */
public final class YieldDispatch {

    private YieldDispatch() {}

    /** Path D5 debug: set MEZZE_PATHD_TRACE=1 to log dispatch decisions. */
    private static final boolean TRACE =
        "1".equals(System.getenv("MEZZE_PATHD_TRACE"));

    private static void trace(String msg) {
        if (TRACE) {
            Thread t = Thread.currentThread();
            System.err.println("[PathD:" + t.getName() + (t.isVirtual() ? "/virt" : "/plat")
                + "] " + msg);
        }
    }

    /**
     * Dispatch one perform against the current thread's handler chain
     * and return the value the perform expression should evaluate to.
     * See class docs.
     */
    @TruffleBoundary
    public static Object dispatch(Hash effectHash, String opName, Object arg) {
        return dispatch(effectHash, opName, arg, /*fieldIndexBox*/ null);
    }

    /**
     * Same as {@link #dispatch(Hash, String, Object)} but uses a
     * per-site cached fieldIndex when the handler's shape matches.
     * A {@code cachedFieldIndex} of -1 means "no cache; fall back to
     * hash-map lookup by name" (used for uncached / first call).
     *
     * <p>Callers that can amortize a per-site cache (BDSL {@code PerformD}
     * @Operation via {@code @Cached}) get index-based field access instead
     * of {@link MezzeRecord#getField(String)}'s hash-map probe — eliminates
     * the string dispatch from the perform hot path per #347.
     */
    @TruffleBoundary
    public static Object dispatch(Hash effectHash, String opName, Object arg,
                                  int[] fieldIndexBox) {
        MezzeRecord handler = HandlerChainRegistry.peekHandlerForEffect(effectHash);
        if (TRACE) trace("dispatch " + opName + " effectHash=" + effectHash
            + " chainDepth=" + currentChainDepth()
            + " handler=" + (handler == null ? "null" : "found"));
        if (handler == null) {
            // No handler in scope — wrap the op in a MezzeSuspend so an
            // outer perform (installed later, e.g. `let x = do_stuff {} ;
            // perform Handler in force x`) can force it.
            return makeUnhandledSuspended(effectHash, opName, arg);
        }
        Object opImplRaw;
        // Try the cached fieldIndex first — hot path when the handler's
        // shape matches the one we saw last time. Otherwise fall through to
        // name-based lookup and update the cache for next call at this site.
        int cachedFieldIndex = fieldIndexBox == null ? -1 : fieldIndexBox[0];
        if (cachedFieldIndex >= 0 && cachedFieldIndex < handler.getFieldCount()
                && opName.equals(handler.getFieldName(cachedFieldIndex))) {
            opImplRaw = handler.getField(cachedFieldIndex);
        } else {
            int idx = handler.getFieldIndex(opName);
            opImplRaw = (idx >= 0) ? handler.getField(idx) : null;
            if (idx >= 0 && fieldIndexBox != null) {
                fieldIndexBox[0] = idx;
            }
        }
        if (!(opImplRaw instanceof MezzeFunction opImpl)) {
            throw new IllegalStateException(
                "Path D dispatch: handler for " + effectHash + "." + opName
                + " field is not a MezzeFunction (got "
                + (opImplRaw == null ? "null" : opImplRaw.getClass().getName()) + ")");
        }

        // Set up a dispatch-only fiber that carries resume/abort as
        // top-of-stack effect-handles for EffectBindingIR to find.
        HandlerChainRegistry dispatchFiber = HandlerChainRegistry.acquireDispatchOnly();
        MezzeFunction resumeFn = fallThroughResumeFn();
        MezzeFunction abortFn = EffectHandlerDriver.makeAbortFunction();
        dispatchFiber.pushEffectHandles(resumeFn, abortFn);
        HandlerChainRegistry prevDispatch = dispatchFiber.enterDispatch();

        try {
            Object[] cv = opImpl.getClosureValues();
            Object[] opArgs = new Object[cv.length + 1];
            System.arraycopy(cv, 0, opArgs, 0, cv.length);
            opArgs[cv.length] = arg;
            Object result = IndirectCallNode.getUncached().call(opImpl.getCallTarget(), opArgs);
            if (TRACE) trace("  impl returned normally: " + summarize(result));
            return result;
        } catch (AbortPerformException abort) {
            // Decide whether abort should propagate as AbortPerformException
            // or convert to TaskUnhandledOpException. The only enclosing
            // catch site is a BDSL Handle @Operation's TryCatchOtherwise
            // catch branch, which registers on D8_HANDLE_STACK via
            // enterBdslHandle. If insideBdslHandle() is true we rethrow the
            // abort for that catch to read via UnwrapAbortValue; otherwise
            // convert to TUE so SpawnSupport stashes on the promise.
            boolean insideD8 = HandlerChainRegistry.insideBdslHandle();
            if (TRACE) trace("  impl aborted with val=" + summarize(abort.value)
                + " (insideD8=" + insideD8
                + " → " + (!insideD8 ? "throw TUE" : "rethrow abort") + ")");
            if (!insideD8) {
                throw new TaskUnhandledOpException(new OpCall(opName, arg, effectHash));
            }
            throw abort;
        } finally {
            HandlerChainRegistry.exitDispatch(prevDispatch);
            dispatchFiber.popEffectHandles();
            HandlerChainRegistry.releaseDispatchOnly(dispatchFiber);
        }
    }

    // ── resume: fall-through semantics ──────────────────────────────────
    //
    // resume is fall-through: calling resume {val = X} returns X. Anything
    // the impl does AFTER calling resume is discarded. See class-doc
    // "What resume does" for the caveat.

    private static volatile MezzeFunction CACHED_FALLTHROUGH_RESUME_FN = null;

    public static MezzeFunction fallThroughResumeFnPublic() {
        return fallThroughResumeFn();
    }

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
     * Build a lazy {@link MezzeSuspend} value carrying the unhandled
     * op — same shape as {@link EffectHandlerDriver#makeUnhandledSuspended}.
     * Used when a perform fires with no matching handler in scope; an
     * outer handler installed later can force this and dispatch.
     */
    @TruffleBoundary
    private static MezzeSuspend makeUnhandledSuspended(Hash effectHash, String opName, Object arg) {
        var thunk = new MezzeThunk(opName, arg, effectHash);
        java.util.Set<Hash> effects = effectHash != null
            ? java.util.Collections.singleton(effectHash)
            : java.util.Collections.emptySet();
        return new MezzeSuspend(thunk, effects);
    }

    /**
     * Legacy marker exception — kept for the {@link YieldDriver}
     * test harness which was written before we adopted MezzeSuspend
     * wrapping. Not thrown by {@link #dispatch} anymore.
     */
    public static final class UnhandledPathDOp extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnhandledPathDOp(Hash effectHash, String opName) {
            super("No handler in scope for effect " + effectHash + " op " + opName);
        }
    }

    /**
     * Legacy entry point for {@link YieldDriver} — walks a
     * ContinuationResult chain if one flows out (which no longer happens
     * after D4 since the emit switched from Yield to a straight call,
     * but kept for the D2 test harness).
     */
    @TruffleBoundary
    public static Object driveWithChainDispatch(Object initial) {
        return YieldDriver.drive(initial, YieldDispatch::dispatch);
    }

    /** Debug helper — number of handlers on this thread's chain. */
    public static int currentChainDepth() {
        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        return chain.length;
    }

    private static String summarize(Object v) {
        if (v == null) return "null";
        String cls = v.getClass().getSimpleName();
        String s = v.toString();
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return cls + "(" + s + ")";
    }
}
