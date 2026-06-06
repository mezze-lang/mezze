package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspended;
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
 *       {@link MezzeSuspended} thunk. Forced when read in a context
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
        EffectFiber fiber = EffectFiber.current();
        if (fiber != null) {
            return fiber.suspend(new OpCall(operationName, args, effectHash));
        }
        // #257 marker fast path. Inside a marker-Mut region CURRENT is null
        // (set up by HandleNode.runMarker), so we reach here. If our
        // inline cache holds the same handler that's currently on the
        // chain, dispatch direct through markerCallNode — no chain walk,
        // no dispatch fiber, no resume/abort plumbing.
        if (isMutOp && markerCallNode != null) {
            com.mezze.interpreter.values.MezzeRecord top =
                EffectFiber.peekHandlerForEffect(effectHash);
            if (top == cachedMarkerHandler) {
                return invokeMarker(args);
            }
            // Different region — deopt and re-cache.
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        if (isMutOp) {
            com.mezze.interpreter.values.MezzeRecord top =
                EffectFiber.peekHandlerForEffect(effectHash);
            if (top != null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (initializeMarkerCache(top)) {
                    return invokeMarker(args);
                }
            }
        }
        HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();
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
     * Those reads consult {@link EffectFiber#dispatching()}, so we set up
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
        for (int i = 0; i < chain.length; i++) {
            // Filter by (effect_hash, op_name) — bare op_name match silently
            // misroutes when two effects in scope declare an op with the
            // same simple name (#246). Null on either side falls back to
            // the legacy "match any effect" behavior.
            Hash chainEffect = chain[i].effectHash();
            if (chainEffect != null && effectHash != null
                    && !chainEffect.equals(effectHash)) {
                continue;
            }
            Object f = chain[i].handler().getField(operationName);
            if (f instanceof MezzeFunction op) {
                if (chainCallNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    chainCallNode = insert(IndirectCallNode.create());
                }

                // Set up dispatch context so the handler body can read
                // resume/abort via EffectBindingNode. Acquire from a
                // per-thread 1-slot pool to avoid allocating a fresh
                // dispatch fiber per op-call.
                EffectFiber dispatchFiber = EffectFiber.acquireDispatchOnly();
                MezzeFunction resumeFn = makeChainResumeFunction();
                MezzeFunction abortFn = EffectHandlerDriver.makeAbortFunction();
                dispatchFiber.pushEffectHandles(resumeFn, abortFn);

                Object[] closure = op.getClosureValues();
                Object[] callArgs = new Object[closure.length + 1];
                System.arraycopy(closure, 0, callArgs, 0, closure.length);
                callArgs[closure.length] = args;

                EffectFiber prev = dispatchFiber.enterDispatch();
                try {
                    return chainCallNode.call(op.getCallTarget(), callArgs);
                } catch (AbortPerformException abort) {
                    // The handler called abort {val = ...}. The enclosing
                    // perform lives on the parent fiber; re-issue the
                    // original op-call there so the parent's driver runs
                    // the handler and terminates the parent's perform.
                    throw new TaskUnhandledOpException(
                        new OpCall(operationName, args, effectHash));
                } finally {
                    EffectFiber.exitDispatch(prev);
                    dispatchFiber.popEffectHandles();
                    EffectFiber.releaseDispatchOnly(dispatchFiber);
                }
            }
        }
        return createSuspended(args);
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

    private static MezzeFunction makeChainResumeFunction() {
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
    private MezzeSuspended createSuspended(Object args) {
        MezzeThunk thunk = new MezzeThunk(/* effectName */ null, operationName, args, effectHash);
        return new MezzeSuspended(thunk, Collections.singleton(effectHash));
    }
}
