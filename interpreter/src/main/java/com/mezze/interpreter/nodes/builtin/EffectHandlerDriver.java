package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.ast.ScopedEffectConstants;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Effect-handler support utilities shared between the Loom-free
 * {@code HandleNode.runNoFiber} path (#341) and BDSL Yield dispatch
 * (Path D). Historically this file also hosted a Loom-fiber–backed
 * {@code run} driver, but that path is gone as of #333 — every AST
 * Handle now runs on the marker-style Loom-free path, and BDSL emits
 * the Handle via {@code BdslYieldDispatch} directly. What remains
 * here is only the shared machinery both paths still need:
 *
 * <ul>
 *   <li>Control-flow sentinels: {@link AbortPerformException},
 *       {@link SuspensionRequested}, {@link TaskUnhandledOpException}.</li>
 *   <li>The cached {@code abort} function op-handlers can call
 *       ({@link #makeAbortFunction()}).</li>
 *   <li>Scope hook plumbing for handlers whose op impls need a
 *       {@code scope} var (LoomAsync installing a StructuredTaskScope,
 *       STM installing its transaction context, …) — invoked from
 *       {@code HandleNode.runNoFiber} via the {@code *Public} shims.</li>
 *   <li>The {@code warnNullEffectHash} shared warning site used by
 *       chain-dispatch paths.</li>
 * </ul>
 */
public final class EffectHandlerDriver {

    // Sentinel thrown by an op handler's `abort` to skip body and return
    // a value as the perform's result. Caught by
    // {@link com.mezze.interpreter.nodes.effect.HandleNode#runNoFiber}
    // (main-thread AST path), by
    // {@link com.mezze.interpreter.nodes.effect.OpCallNode} (spawned-
    // thread chain dispatch), and by BDSL Handle's TryCatchOtherwise
    // catch branch (Path D). Extends AbstractTruffleException so BDSL's
    // TryCatch catches it (BDSL filters out non-Truffle exceptions — a
    // plain RuntimeException would escape the catch branch entirely).
    public static final class AbortPerformException extends com.oracle.truffle.api.exception.AbstractTruffleException {
        public final Object value;
        public AbortPerformException(Object value) {
            super();
            this.value = value;
        }
    }

    /**
     * Path E (lazy-until-fully-discharged, task #336) sentinel: thrown by
     * {@link com.mezze.interpreter.nodes.effect.OpCallNode} when a perform
     * fires with no handler on the chain — signals "capture the enclosing
     * body as a MezzeSuspend and defer execution until a matching handler
     * is provided."
     *
     * <p>Caught either at a top-level {@code HandleNode.execute} that chose
     * the lazy path (builds MezzeSuspend holding the enclosing frame + this
     * pending op) or inside {@code MezzeSuspend.force} when a resumed body
     * re-suspends (builds a fresh MezzeSuspend for the next suspend point).
     *
     * <p>Extends AbstractTruffleException so BDSL's TryCatchOtherwise catches
     * it, mirroring how AbortPerformException works.
     */
    public static final class SuspensionRequested extends com.oracle.truffle.api.exception.AbstractTruffleException {
        public final com.mezze.language.typedast.Hash effectHash;
        public final String opName;
        public final Object args;
        public SuspensionRequested(
                com.mezze.language.typedast.Hash effectHash,
                String opName,
                Object args) {
            super();
            this.effectHash = effectHash;
            this.opName = opName;
            this.args = args;
        }
    }

    private EffectHandlerDriver() {}

    /**
     * Thrown when a spawned task's op-call propagates past every inherited
     * handler on that thread's chain. {@code Async.spawn} catches this on
     * the spawned thread, stores the op-call on the promise, and
     * {@code Async.await} re-issues it on the parent thread.
     */
    public static final class TaskUnhandledOpException extends RuntimeException {
        public final OpCall opCall;
        public TaskUnhandledOpException(OpCall opCall) {
            super(null, null, false, false);
            this.opCall = opCall;
        }
    }

    // ----------------------------------------------------------------
    // Shared warning: null effect-hash on a chain-dispatch site.
    // ----------------------------------------------------------------

    private static final java.util.Set<String> WARNED_NULL_HASH_SITES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    @TruffleBoundary
    public static void warnNullEffectHash(
            Hash handlerHash, Hash callHash, String opName, String site) {
        String key = site + ":" + opName
            + ":handlerNull=" + (handlerHash == null)
            + ":callNull=" + (callHash == null);
        if (WARNED_NULL_HASH_SITES.add(key)) {
            System.err.println(
                "[chain-dispatch WARN #246/#55] null effect hash at " + site
                + " for op '" + opName + "'"
                + " — handler side null=" + (handlerHash == null)
                + ", call side null=" + (callHash == null)
                + "; skipping entry to avoid silent misroute.");
        }
    }

    // ----------------------------------------------------------------
    // Cached `abort` function.
    //
    // The abort function is stateless — its body reads `val` out of the
    // argument record and throws AbortPerformException(val). No closure-
    // captured handler/fiber/etc, so one MezzeFunction can be shared
    // across all op-call dispatches forever. Saves ~1µs/op of per-call
    // allocation (FrameDescriptor + MezzeRootNode + CallTarget +
    // MezzeFunction). #88.
    // ----------------------------------------------------------------

    private static volatile MezzeFunction CACHED_ABORT_FN = null;

    /**
     * Get the (cached) {@code abort} function for op handler bodies.
     * Calling {@code abort {val = X}} terminates the surrounding perform
     * with X as its overall result, discarding body. Implemented by
     * throwing {@link AbortPerformException}, which the enclosing Handle
     * (AST {@code runNoFiber} or BDSL {@code Handle} @Operation) catches.
     */
    public static MezzeFunction makeAbortFunction() {
        MezzeFunction cached = CACHED_ABORT_FN;
        if (cached != null) return cached;
        return buildAndCacheAbortFunction();
    }

    @TruffleBoundary
    private static synchronized MezzeFunction buildAndCacheAbortFunction() {
        if (CACHED_ABORT_FN != null) return CACHED_ABORT_FN;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode abortBody = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                int recordIdx = frameArgs.length - 1;
                if (recordIdx < 0 || !(frameArgs[recordIdx] instanceof MezzeRecord rec)) {
                    throw new RuntimeException(
                        "abort expects record argument {val = ...}");
                }
                Object val = rec.getField(ScopedEffectConstants.VAL_FIELD);
                throw new AbortPerformException(val);
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            abortBody,
            "effect-handler-abort",
            0,
            1,
            new int[0]
        ).getCallTarget();
        MezzeFunction fn = new MezzeFunction(target, ScopedEffectConstants.ABORT, new Object[0]);
        CACHED_ABORT_FN = fn;
        return fn;
    }

    // ----------------------------------------------------------------
    // Scope hooks
    //
    // Handlers may declare initialize_scope / finalize_scope fields whose
    // return value becomes a `scope` var read by op impls via ReadScopeNode.
    // Exposed to HandleNode.runNoFiber via the *Public shims so the Loom-
    // free path runs the same setup/teardown the deleted fiber driver did.
    // ----------------------------------------------------------------

    @TruffleBoundary
    private static Object invokeInitializeScope(MezzeRecord handler, IndirectCallNode callNode) {
        Object fn = handler.getField(ScopedEffectConstants.INITIALIZE_SCOPE);
        if (!(fn instanceof MezzeFunction initFn)) {
            return null;
        }
        return invokeHookWithEmptyArg(initFn, callNode);
    }

    public static Object initializeScopePublic(MezzeRecord handler, IndirectCallNode callNode) {
        return invokeInitializeScope(handler, callNode);
    }
    public static void finalizeScopePublic(MezzeRecord handler, Object scope, IndirectCallNode callNode) {
        invokeFinalizeScope(handler, scope, callNode);
    }
    public static Object installScopePublic(MezzeNode dispatchNode, Object scope) {
        return installScope(dispatchNode, scope);
    }
    public static void restoreScopePublic(MezzeNode dispatchNode, Object prev) {
        restoreScope(dispatchNode, prev);
    }

    @TruffleBoundary
    private static void invokeFinalizeScope(
            MezzeRecord handler, Object scope, IndirectCallNode callNode) {
        Object fn = handler.getField(ScopedEffectConstants.FINALIZE_SCOPE);
        if (!(fn instanceof MezzeFunction finFn)) {
            return;
        }
        try {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{ScopedEffectConstants.SCOPE},
                new Object[]{scope == null ? MezzeRecord.empty() : scope}
            );
            invokeHookWithArg(finFn, arg, callNode);
        } catch (Throwable ignored) {
            // finalize_scope failures must not mask the perform's result.
        }
    }

    @TruffleBoundary
    private static Object invokeHookWithArg(
            MezzeFunction fn, MezzeRecord arg, IndirectCallNode callNode) {
        Object[] cv = fn.getClosureValues();
        Object[] args = new Object[cv.length + 1];
        System.arraycopy(cv, 0, args, 0, cv.length);
        args[cv.length] = arg;
        return callNode.call(fn.getCallTarget(), args);
    }

    @TruffleBoundary
    private static Object invokeHookWithEmptyArg(MezzeFunction fn, IndirectCallNode callNode) {
        return invokeHookWithArg(fn, MezzeRecord.empty(), callNode);
    }

    /**
     * Install {@code scope} as the current value of the "scope" var on the
     * MezzeContext, returning whatever was there before so the caller can
     * restore it on exit. Returns null if no context is available (e.g.
     * isolated unit tests).
     */
    @TruffleBoundary
    private static Object installScope(MezzeNode dispatchNode, Object scope) {
        if (scope == null) return null;
        MezzeContext ctx = tryGetContext(dispatchNode);
        if (ctx == null) return null;
        Object prev = ctx.lookupScopeVar(ScopedEffectConstants.SCOPE);
        ctx.defineScopeVar(ScopedEffectConstants.SCOPE, scope);
        return prev;
    }

    /**
     * Restore the outer perform's scope (if any) after this perform's body
     * completes. Only called when this perform installed a scope.
     *
     * <p>If there was no outer scope ({@code prev == null}), we write an
     * empty record rather than null because the underlying ConcurrentHashMap
     * doesn't store nulls; {@link com.mezze.interpreter.nodes.ReadScopeNode}
     * treats this the same as absence.
     */
    @TruffleBoundary
    private static void restoreScope(MezzeNode dispatchNode, Object prev) {
        MezzeContext ctx = tryGetContext(dispatchNode);
        if (ctx == null) return;
        ctx.defineScopeVar(
            ScopedEffectConstants.SCOPE,
            prev != null ? prev : MezzeRecord.empty()
        );
    }

    @TruffleBoundary
    private static MezzeContext tryGetContext(MezzeNode dispatchNode) {
        try {
            return MezzeContext.get(dispatchNode);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
