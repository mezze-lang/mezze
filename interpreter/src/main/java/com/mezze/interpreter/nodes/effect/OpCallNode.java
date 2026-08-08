package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspend;
import com.mezze.interpreter.values.MezzeThunk;
import com.mezze.language.ast.ScopedEffectConstants;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Collections;

/**
 * Call site for an effect operation.
 *
 * <p>When a body executes {@code Effect.op {args}}, the compiler emits an
 * {@code OpCallNode}. At runtime, dispatch is tried in this order:
 *
 * <ol>
 *   <li><b>Fiber suspend</b>: if a fiber is current (we're inside a
 *       {@code perform Handler in body}), suspend it with
 *       {@code OpCall(opName, args)}. The surrounding driver dispatches.
 *       This path supports {@code abort}/{@code resume} fully.</li>
 *   <li><b>Chain dispatch</b>: no fiber, but the per-thread handler chain
 *       has at least one handler. Walk innermost-first, call the first
 *       matching op handler directly. This is used by spawned-task threads
 *       where Loom-virtual-thread + Continuation don't compose. Handlers
 *       that try to call {@code abort}/{@code resume} from here will
 *       fail loudly because no fiber is available.</li>
 *   <li><b>Lazy</b>: nothing matches — wrap the call as a
 *       {@link MezzeSuspend} thunk. Forced when read in a context
 *       with handlers available.</li>
 * </ol>
 */
@NodeInfo(shortName = "op-call")
public final class OpCallNode extends MezzeNode {

    @CompilationFinal
    private final String operationName;

    @CompilationFinal
    private final Hash effectHash;

    @Child
    private MezzeNode argumentNode;

    @Child
    private IndirectCallNode chainCallNode;

    /**
     * Marker-Mut fast path gate. ON by default; opt out with
     * {@code MEZZE_MARKER_MUT=0}. Note: {@code com.mezze.interpreter} is in
     * {@code --initialize-at-build-time} for native-image, so this static
     * final field is resolved at image build time — the runtime env var
     * doesn't change the value. The default-ON form is safe at build time
     * (build env has no MEZZE_MARKER_MUT, so the condition evaluates true).
     */
    private static final boolean MARKER_MUT_ENABLED =
        !"0".equals(System.getenv("MEZZE_MARKER_MUT"));

    /** True if this op-call's effectHash is the Mut effect. Cached at construction. */
    @CompilationFinal
    private final boolean isMutOp;

    /**
     * Inline cache for the marker fast path. Captured on first execution
     * inside a Mut region: the handler record on the chain, the op field
     * index inside that record, and a DirectCallNode pinned to the op
     * implementation's CallTarget. Subsequent calls verify
     * {@code cachedMarkerHandler == peekHandlerForEffect(Mut)} and dispatch
     * via {@link #markerCallNode} — Truffle then folds the call through
     * partial evaluation and the JIT inlines the callee. Mismatch
     * (different LocalMut region with a different handler record) deopts
     * and re-initializes; this is the standard PIC pattern. #257.
     */
    @CompilationFinal
    private com.mezze.interpreter.values.MezzeRecord cachedMarkerHandler;
    @CompilationFinal
    private int cachedMarkerFieldIndex = -1;
    @CompilationFinal
    private int cachedMarkerClosureLen = -1;
    @CompilationFinal(dimensions = 1)
    private Object[] cachedMarkerClosure;
    @Child
    private com.oracle.truffle.api.nodes.DirectCallNode markerCallNode;

    /**
     * True when this op-call is the specialized `Mut.mutate { act = X }`
     * shape produced by {@link #createMutMutateActFast}. In that mode
     * {@link #argumentNode} evaluates to the `act` lambda directly (a
     * {@link MezzeFunction}) — the {@code {act = ...}} wrapper record
     * is NOT pre-built. The fast-path code builds a minimal one-field
     * record per call so EA can scalarize it during PE.
     */
    @CompilationFinal
    private final boolean isMutMutateActFast;

    /**
     * Compilation-final Shape for the `{act = MezzeFunction}` wrapper
     * used by the fast path. Built once at construction so Truffle sees
     * it as a constant during PE.
     */
    @CompilationFinal
    private final MezzeRecord.Shape actWrapperShape;

    public OpCallNode(String operationName, Hash effectHash, MezzeNode argumentNode) {
        this(operationName, effectHash, argumentNode, false);
    }

    private OpCallNode(String operationName, Hash effectHash, MezzeNode argumentNode,
                       boolean isMutMutateActFast) {
        this.operationName = operationName;
        this.effectHash = effectHash;
        this.argumentNode = argumentNode;
        this.isMutOp = MARKER_MUT_ENABLED && MarkerEffects.isMutEffect(effectHash);
        this.isMutMutateActFast = isMutMutateActFast;
        if (isMutMutateActFast) {
            MezzeRecord.FieldLocation[] locs = {
                new MezzeRecord.FieldLocation(
                    MezzeRecord.FieldLocation.StorageType.OBJECT, 0)
            };
            this.actWrapperShape = new MezzeRecord.Shape(
                new String[] { "act" }, locs);
        } else {
            this.actWrapperShape = null;
        }
    }

    public static OpCallNode create(String operationName, Hash effectHash, MezzeNode argumentNode) {
        return new OpCallNode(operationName, effectHash, argumentNode);
    }

    /**
     * Fast-path factory for `Mut.mutate { act = X }`: emitted by
     * {@code TruffleCompiler} when the IR shape matches. The {@code actNode}
     * evaluates to the `act` lambda directly (no wrapper record on the
     * fast path). When PE folds the impl call through the marker PIC and
     * EA scalarizes the per-call wrapper, the net cost is just calling
     * `act` — same as Java.
     */
    public static OpCallNode createMutMutateActFast(Hash effectHash, MezzeNode actNode) {
        return new OpCallNode("mutate", effectHash, actNode, true);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Fast path for `Mut.mutate { act = X }` (see createMutMutateActFast).
        // argumentNode evaluates to the `act` lambda directly; we wrap it in
        // a one-field record just-in-time so the standard mutate impl
        // (`{ act } -> act{}`) can read it. The wrapper is allocated locally
        // and consumed inside the inlined impl — EA scalarizes it during PE.
        final Object args;
        if (isMutMutateActFast) {
            Object actFn = argumentNode.execute(frame);
            args = MezzeRecord.withAllObjects(actWrapperShape, new Object[] { actFn });
        } else {
            args = argumentNode.execute(frame);
        }
        return dispatchWith(args);
    }

    /**
     * BDSL entry point: called with the argument already on the operand
     * stack. Skips the {@link #argumentNode} evaluation that {@link #execute}
     * would do. Same lifecycle as execute for the standard path.
     *
     * For the Mut fast path, use {@link #executeMutMutateActFast(Object)}
     * instead — this method does NOT wrap the raw lambda into an {@code {act}}
     * record.
     */
    public Object executeWith(Object arg) {
        return dispatchWith(arg);
    }

    /**
     * BDSL entry point for the {@code Mut.mutate { act = X }} fast path:
     * caller passes the {@code act} lambda directly (matching the emit
     * shape). We build the one-field wrapper here so the mutate impl can
     * read it, then dispatch as usual.
     */
    public Object executeMutMutateActFast(Object actFn) {
        Object args = MezzeRecord.withAllObjects(actWrapperShape, new Object[] { actFn });
        return dispatchWith(args);
    }

    /**
     * Shared body of {@link #execute}, {@link #executeWith}, and
     * {@link #executeMutMutateActFast} once the argument value is in hand.
     */
    private Object dispatchWith(Object args) {
        // #257 marker fast path. CURRENT is always null now that the Loom
        // fiber-drive path is gone (#333). If our
        // inline cache holds the same handler that's currently on the
        // chain, dispatch direct through markerCallNode — no chain walk,
        // no dispatch fiber, no resume/abort plumbing.
        if (isMutOp && markerCallNode != null) {
            com.mezze.interpreter.values.MezzeRecord top =
                HandlerChainRegistry.peekHandlerForEffect(effectHash);
            if (top == cachedMarkerHandler) {
                return invokeMarker(args);
            }
            // Different region — deopt and re-cache.
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        if (isMutOp) {
            com.mezze.interpreter.values.MezzeRecord top =
                HandlerChainRegistry.peekHandlerForEffect(effectHash);
            if (top != null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (initializeMarkerCache(top)) {
                    return invokeMarker(args);
                }
            }
        }
        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        if (chain.length > 0) {
            return chainDispatch(chain, args);
        }
        return createSuspended(args);
    }

    /**
     * One-time cache setup for the marker fast path. Caller has already
     * deopted via {@link CompilerDirectives#transferToInterpreterAndInvalidate()}.
     * Returns false if the handler record doesn't expose the expected op
     * field as a {@link MezzeFunction} — the caller falls back to chain
     * dispatch so correctness is preserved even if the handler shape is
     * unexpected.
     */
    private boolean initializeMarkerCache(com.mezze.interpreter.values.MezzeRecord handler) {
        int idx = handler.getFieldIndex(operationName);
        if (idx < 0) return false;
        Object f = handler.getField(idx);
        if (!(f instanceof MezzeFunction op)) return false;
        Object[] closure = op.getClosureValues();
        com.oracle.truffle.api.nodes.DirectCallNode node =
            com.oracle.truffle.api.nodes.DirectCallNode.create(op.getCallTarget());
        node.forceInlining();
        this.markerCallNode = insert(node);
        this.cachedMarkerHandler = handler;
        this.cachedMarkerFieldIndex = idx;
        this.cachedMarkerClosureLen = closure.length;
        this.cachedMarkerClosure = closure;
        return true;
    }

    /**
     * Hot-path invoke through the marker inline cache. The handler's op
     * impl takes one record arg (the op's args) and returns its result.
     * Closure values are prepended per the standard Mezze call ABI.
     */
    private Object invokeMarker(Object args) {
        int closureLen = cachedMarkerClosureLen;
        Object[] callArgs = new Object[closureLen + 1];
        if (closureLen > 0) {
            System.arraycopy(cachedMarkerClosure, 0, callArgs, 0, closureLen);
        }
        callArgs[closureLen] = args;
        return markerCallNode.call(callArgs);
    }

    /**
     * Walk the chain innermost-first looking for a handler with a field
     * matching this op-name. Call it directly. Used by spawned tasks where
     * Loom-virtual-thread + Continuation don't compose.
     *
     * <p>Inherited handlers' op-handler bodies may reference {@code abort}
     * and {@code resume} via {@link com.mezze.interpreter.nodes.effect.EffectBindingNode}.
     * Those reads consult {@link HandlerChainRegistry#dispatching()}, so we set up
     * a dispatch context here: a synthetic dispatch-only fiber that
     * carries the effect-handle stack while the handler body runs.
     *
     * <ul>
     *   <li>{@code resume {val = X}} → handle returns X; if the handler's
     *       body's last expression is the resume call, the op-call returns X
     *       (fall-through). Same as the driver's behavior when the handler
     *       resumes once and returns.</li>
     *   <li>{@code abort {val = X}} → handle throws {@code AbortPerformException}.
     *       The chain-local perform of which this handler is a part lives on
     *       the parent fiber, not here. We re-throw as
     *       {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException}
     *       so the spawned-task boundary stashes the original op-call on the
     *       promise and {@code Async.await} re-issues it on the parent
     *       fiber, where the same handler runs again with a proper driver
     *       and terminates the parent's perform with X.</li>
     * </ul>
     */
    @TruffleBoundary
    private Object chainDispatch(HandlerChainEntry[] chain, Object args) {
        if (chainCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            chainCallNode = insert(IndirectCallNode.create());
        }
        Object result = dispatchOpOnChain(
            chain, operationName, args, effectHash, chainCallNode);
        if (result == DISPATCH_NO_MATCH) {
            return createSuspended(args);
        }
        return result;
    }

    /**
     * Sentinel returned by {@link #dispatchOpOnChain} when no handler in the
     * chain matches the op (caller decides how to fall back: suspend, lazy
     * wrap, etc.).
     */
    public static final Object DISPATCH_NO_MATCH = new Object();

    /**
     * Walk a handler chain innermost-first, call the first matching op
     * handler directly under a dispatch-only fiber. Used by:
     *
     * <ul>
     *   <li>{@link OpCallNode#chainDispatch} — fiber-less op-call dispatch
     *       on spawned tasks</li>
     *   <li>{@link com.mezze.interpreter.values.MezzeSuspend#force} —
     *       forcing a suspended op-call on a spawned task where a chain
     *       has been installed but no fiber is current (#285)</li>
     * </ul>
     *
     * Returns {@link #DISPATCH_NO_MATCH} when no handler in the chain
     * matches. Otherwise returns the handler's result. Translates an
     * {@code abort} inside the handler body to
     * {@link TaskUnhandledOpException} so the spawned-task boundary
     * stashes the op and {@code Async.await} re-issues it on the parent.
     */
    @TruffleBoundary
    public static Object dispatchOpOnChain(
            HandlerChainEntry[] chain,
            String operationName,
            Object args,
            Hash effectHash,
            IndirectCallNode callNode) {
        for (int i = 0; i < chain.length; i++) {
            // STRICT filter by (effect_hash, op_name). A null on either
            // side is the #246/#55 silent-misroute setup — refuse to
            // treat it as "match any" and skip. See
            // {@code EffectHandlerDriver.handlerMatchesEffect} for the
            // full rationale and warning emit path.
            Hash chainEffect = chain[i].effectHash();
            if (chainEffect == null || effectHash == null) {
                com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.warnNullEffectHash(
                    chainEffect, effectHash, operationName,
                    "OpCallNode.dispatchOpOnChain");
                continue;
            }
            if (!chainEffect.equals(effectHash)) {
                continue;
            }
            Object f = chain[i].handler().getField(operationName);
            if (f instanceof MezzeFunction op) {
                // Set up dispatch context so the handler body can read
                // resume/abort via EffectBindingNode. Acquire from a
                // per-thread 1-slot pool to avoid allocating a fresh
                // dispatch fiber per op-call.
                HandlerChainRegistry dispatchFiber = HandlerChainRegistry.acquireDispatchOnly();
                MezzeFunction resumeFn = makeChainResumeFunction();
                MezzeFunction abortFn = EffectHandlerDriver.makeAbortFunction();
                dispatchFiber.pushEffectHandles(resumeFn, abortFn);

                Object[] closure = op.getClosureValues();
                Object[] callArgs = new Object[closure.length + 1];
                System.arraycopy(closure, 0, callArgs, 0, closure.length);
                callArgs[closure.length] = args;

                HandlerChainRegistry prev = dispatchFiber.enterDispatch();
                try {
                    return callNode.call(op.getCallTarget(), callArgs);
                } catch (AbortPerformException abort) {
                    // The handler called abort {val = ...}. Two cases:
                    //   - Inside a BDSL Handle (Path D8): rethrow so the
                    //     enclosing Handle's TryCatchOtherwise catch branch
                    //     picks it up via UnwrapAbortValue.
                    //   - Otherwise (spawned task, top level): convert to
                    //     TaskUnhandledOpException so SpawnSupport stashes it
                    //     on the promise for parent await re-issue. Same
                    //     rule as YieldDispatch — the two dispatch
                    //     paths must agree on when to convert.
                    if (HandlerChainRegistry.insideBdslHandle()) {
                        throw abort;
                    }
                    throw new TaskUnhandledOpException(
                        new OpCall(operationName, args, effectHash));
                } finally {
                    HandlerChainRegistry.exitDispatch(prev);
                    dispatchFiber.popEffectHandles();
                    HandlerChainRegistry.releaseDispatchOnly(dispatchFiber);
                }
            }
        }
        return DISPATCH_NO_MATCH;
    }

    /**
     * Build a {@code resume} function for chain dispatch on a spawned
     * thread. Calling {@code resume {val = X}} on this thread just returns
     * X — the op-call's caller (the spawned task body) sees X. There's no
     * continuation to drive forward; the spawned body resumes naturally
     * via the normal Java return.
     *
     * <p>The body is stateless (reads val from arg record, returns it),
     * so we cache the whole MezzeFunction as a singleton. Saves
     * {@code FrameDescriptor + RootCallTarget + MezzeFunction + closure
     * Object[]} allocations per op-call dispatch — meaningful at high
     * op-call rates (1M op-calls = 4M fewer allocations).
     */
    private static volatile MezzeFunction CACHED_CHAIN_RESUME_FN = null;

    public static MezzeFunction makeChainResumeFunction() {
        MezzeFunction cached = CACHED_CHAIN_RESUME_FN;
        if (cached != null) return cached;
        return buildAndCacheChainResumeFunction();
    }

    @TruffleBoundary
    private static synchronized MezzeFunction buildAndCacheChainResumeFunction() {
        if (CACHED_CHAIN_RESUME_FN != null) return CACHED_CHAIN_RESUME_FN;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode resumeBody = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                int recordIdx = frameArgs.length - 1;
                if (recordIdx < 0 || !(frameArgs[recordIdx] instanceof MezzeRecord rec)) {
                    throw new RuntimeException(
                        "resume expects record argument {val = ...}");
                }
                return rec.getField(ScopedEffectConstants.VAL_FIELD);
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            resumeBody,
            "chain-dispatch-resume",
            0,
            1,
            new int[0]
        ).getCallTarget();
        CACHED_CHAIN_RESUME_FN = new MezzeFunction(
            target, ScopedEffectConstants.RESUME, new Object[0]);
        return CACHED_CHAIN_RESUME_FN;
    }

    @TruffleBoundary
    private MezzeSuspend createSuspended(Object args) {
        MezzeThunk thunk = new MezzeThunk(operationName, args, effectHash);
        return new MezzeSuspend(thunk, Collections.singleton(effectHash));
    }
}
