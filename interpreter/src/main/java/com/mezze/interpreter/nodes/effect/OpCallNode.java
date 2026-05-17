package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspended;
import com.mezze.interpreter.values.MezzeThunk;
import com.mezze.language.typechecker.scope.ScopedEffectConstants;
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

    public OpCallNode(String operationName, Hash effectHash, MezzeNode argumentNode) {
        this.operationName = operationName;
        this.effectHash = effectHash;
        this.argumentNode = argumentNode;
    }

    public static OpCallNode create(String operationName, Hash effectHash, MezzeNode argumentNode) {
        return new OpCallNode(operationName, effectHash, argumentNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argumentNode.execute(frame);
        EffectFiber fiber = EffectFiber.current();
        if (fiber != null) {
            return fiber.suspend(new OpCall(operationName, args, effectHash));
        }
        MezzeRecord[] chain = EffectFiber.snapshotHandlerChain();
        if (chain.length > 0) {
            return chainDispatch(chain, args);
        }
        return createSuspended(args);
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
    private Object chainDispatch(MezzeRecord[] chain, Object args) {
        for (int i = 0; i < chain.length; i++) {
            Object f = chain[i].getField(operationName);
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
