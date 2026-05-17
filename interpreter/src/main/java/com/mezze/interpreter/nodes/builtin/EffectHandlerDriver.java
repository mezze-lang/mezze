package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.continuation.BodyResult;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspended;
import com.mezze.interpreter.values.MezzeThunk;
import com.mezze.language.typechecker.scope.ScopedEffectConstants;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Runtime driver for Mezze effect handlers.
 *
 * <p>Drives a body {@link MezzeNode} inside a Loom-backed {@link EffectFiber}
 * and dispatches effect-operation calls to the handler record's named
 * operation methods.
 *
 * <p>Invoked by {@code HandleNode.execute} when lowering
 * {@code perform Handler in body}. Not exposed to userland.
 *
 * <h3>Op call site protocol</h3>
 *
 * When body invokes an effect operation, the op call site
 * ({@code OpCallNode}) calls {@code EffectFiber.current().suspend(new OpCall(opName, args))}.
 * The fiber yields back to {@link #drive}, which finds {@code handler.<opName>}
 * and calls it with {@code {args, resume, abort}}.
 *
 * <ul>
 *   <li>If the op handler returns normally, that return value is what
 *       {@code suspend} returns inside body — body continues as if the op
 *       had returned that value.</li>
 *   <li>If the op handler calls {@code resume {val = X}} explicitly, body
 *       runs with {@code X} as the op's return; the op handler then
 *       continues with whatever body eventually produces.</li>
 *   <li>If the op handler calls {@code abort {val = X}}, body is discarded
 *       and {@code X} becomes the perform's overall result.</li>
 * </ul>
 *
 * <h3>Scope hooks</h3>
 *
 * An optional {@code initialize_scope} field, if present, is invoked once
 * before body runs. Its return value becomes the {@code scope} binding
 * accessible inside op handler bodies (via {@code ReadScopeNode} reading
 * the {@code MezzeContext} scope var).
 *
 * <p>An optional {@code finalize_scope} field, if present, is invoked once
 * after body completes (or aborts). It receives {@code {scope}} as its
 * argument. Its return value is ignored; failures are swallowed so they
 * cannot mask the perform's result.
 */
public final class EffectHandlerDriver {

    // Name constants come from typechecker.scope.ScopedEffectConstants —
    // single source of truth shared with the typechecker.

    // Sentinel thrown by an op handler's `abort` to skip body and return
    // a value as the perform's result. Caught by `drive` (parent thread
    // path) and by {@link com.mezze.interpreter.nodes.effect.OpCallNode}
    // (spawned-thread chain dispatch path).
    public static final class AbortPerformException extends RuntimeException {
        public final Object value;
        public AbortPerformException(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private EffectHandlerDriver() {}

    /**
     * Exception used by {@code runFunctionThroughChain}'s synthetic
     * task-boundary to capture an op-call that propagated past all
     * inherited handlers. {@code Async.spawn} catches this on the spawned
     * thread, stores the op-call on the promise, and {@code Async.await}
     * re-issues it on the parent fiber.
     */
    public static final class TaskUnhandledOpException extends RuntimeException {
        public final OpCall opCall;
        public TaskUnhandledOpException(OpCall opCall) {
            super(null, null, false, false);
            this.opCall = opCall;
        }
    }

    /**
     * Per-thread flag set by {@link #runFunctionThroughChain} while a
     * spawned task's body is running. When set and an op-call propagates
     * past the outermost inherited handler with no outer fiber to suspend
     * into, the driver throws {@link TaskUnhandledOpException} instead of
     * a generic "no handler" error — letting {@code Async.spawn} stash
     * the op on the promise for {@code Async.await} to re-raise.
     */
    private static final ThreadLocal<Boolean> TASK_BOUNDARY =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Drive a {@link MezzeFunction} as if it were the body of a series of
     * nested {@code perform Handler in body} layers — one layer per handler
     * in {@code chain}, outermost first.
     *
     * <p>Used by Async.spawn to re-establish the parent's handler chain on
     * a freshly-spawned virtual thread, so op-calls inside the task body
     * dispatch through the same effect handlers that were in scope at the
     * spawn site.
     *
     * <p>If an op-call propagates past the outermost handler (none of the
     * inherited handlers can dispatch it), throws
     * {@link TaskUnhandledOpException} so the caller can store the
     * unhandled op on the task's promise and re-raise it at {@code await}
     * time on the parent fiber.
     */
    @TruffleBoundary
    public static Object runFunctionThroughChain(
            MezzeRecord[] chain,
            MezzeFunction body,
            Object[] bodyArgs,
            IndirectCallNode callNode,
            MezzeNode dispatchNode) {

        EffectFiber[] fiberHolder = new EffectFiber[1];

        // Wrap body as a Runnable. When body completes naturally, stash
        // its return as a BodyResult so the driver can distinguish from
        // an op-call suspend.
        Runnable bodyRunner = () -> {
            Object result = callNode.call(body.getCallTarget(), bodyArgs);
            fiberHolder[0].setSuspendedValue(new BodyResult(result));
        };
        fiberHolder[0] = EffectFiber.create("task-fiber", bodyRunner);

        // Push every handler in the chain onto the per-thread chain
        // (innermost-first matches the snapshot's order) so any nested
        // `Async.spawn` from inside the task captures the same chain.
        // chain[0] is innermost; iterate in reverse so outermost is
        // pushed first.
        for (int i = chain.length - 1; i >= 0; i--) {
            EffectFiber.pushHandlerOnChain(chain[i]);
        }

        Boolean prevBoundary = TASK_BOUNDARY.get();
        TASK_BOUNDARY.set(Boolean.TRUE);
        try {
            return driveWithChain(chain, fiberHolder[0], null, callNode);
        } catch (AbortPerformException abort) {
            return abort.value;
        } finally {
            TASK_BOUNDARY.set(prevBoundary);
            for (int i = 0; i < chain.length; i++) {
                EffectFiber.popHandlerOffChain();
            }
        }
    }

    /**
     * Drive {@code fiber} forward; on each op-call suspend, find the
     * innermost handler in {@code chain} that has a matching op field
     * and dispatch through it. If no handler in the chain matches, throw
     * {@link TaskUnhandledOpException} (caught by {@code Async.spawn} on
     * the task thread, which stashes the op on the promise for
     * {@code Async.await} to re-raise on the parent fiber).
     */
    @TruffleBoundary
    private static Object driveWithChain(
            MezzeRecord[] chain,
            EffectFiber fiber,
            Object resumeWith,
            IndirectCallNode callNode) {
        if (resumeWith != null) {
            fiber.setResumeValue(resumeWith);
        }
        fiber.run();

        Object suspended = fiber.takeSuspendedValue();
        if (suspended instanceof BodyResult br) {
            return br.value();
        }
        if (!(suspended instanceof OpCall opCall)) {
            throw new RuntimeException(
                "EffectHandlerDriver: unexpected suspend payload: "
                + (suspended == null ? "null" : suspended.getClass().getName()));
        }

        // Find the innermost handler in the chain whose record has a
        // field matching this op-name.
        MezzeFunction opHandler = null;
        for (int i = 0; i < chain.length; i++) {
            Object f = chain[i].getField(opCall.opName());
            if (f instanceof MezzeFunction mf) {
                opHandler = mf;
                break;
            }
        }
        if (opHandler == null) {
            throw new TaskUnhandledOpException(opCall);
        }

        OpDispatchResult result = dispatchOp(
            opHandler, opCall, fiber,
            makeChainResumeFunction(chain, fiber, callNode),
            makeAbortFunction(),
            callNode);
        if (result.resumed()) {
            return result.opResult();
        }
        return driveWithChain(chain, fiber, result.opResult(), callNode);
    }

    // Cache one CallTarget for chain-dispatch resume, mirroring the
    // CACHED_RESUME_TARGET pattern from #88 for the in-perform resume.
    // Per-dispatch state (chain, fiber, callNode) flows through the
    // MezzeFunction's closure values; the shared body reads them from
    // frame.getArguments(). Saves a FrameDescriptor + MezzeRootNode +
    // RootCallTarget allocation per chain-dispatched op-call. #101.
    private static volatile RootCallTarget CACHED_CHAIN_RESUME_TARGET = null;

    private static MezzeFunction makeChainResumeFunction(
            MezzeRecord[] chain,
            EffectFiber fiber,
            IndirectCallNode callNode) {
        RootCallTarget target = CACHED_CHAIN_RESUME_TARGET;
        if (target == null) target = buildAndCacheChainResumeTarget();
        // Closure layout matches the order the shared body reads them in
        // frame.getArguments(): [chain, fiber, callNode] followed by the
        // user arg ({val = X}).
        return new MezzeFunction(
            target,
            ScopedEffectConstants.RESUME,
            new Object[]{chain, fiber, callNode}
        );
    }

    @TruffleBoundary
    private static synchronized RootCallTarget buildAndCacheChainResumeTarget() {
        if (CACHED_CHAIN_RESUME_TARGET != null) return CACHED_CHAIN_RESUME_TARGET;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode resumeBody = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                // frameArgs layout: [chain, fiber, callNode, argRecord]
                MezzeRecord[] chain = (MezzeRecord[]) frameArgs[0];
                EffectFiber fiber = (EffectFiber) frameArgs[1];
                IndirectCallNode callNode = (IndirectCallNode) frameArgs[2];
                int recordIdx = frameArgs.length - 1;
                if (recordIdx < 3 || !(frameArgs[recordIdx] instanceof MezzeRecord rec)) {
                    throw new RuntimeException(
                        "resume expects record argument {val = ...}");
                }
                Object val = rec.getField(ScopedEffectConstants.VAL_FIELD);
                fiber.markResumedAtCurrentFrame();
                // Symmetric with the in-perform resume: catch AbortPerform
                // so a deferred chain-dispatch resume re-entry (resume
                // captured inside an abort payload) translates a later
                // abort into the value resume returns.
                try {
                    return driveWithChain(chain, fiber, val, callNode);
                } catch (AbortPerformException abort) {
                    return abort.value;
                }
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            resumeBody,
            "effect-handler-resume-chain",
            0,
            1,
            new int[0]
        ).getCallTarget();
        CACHED_CHAIN_RESUME_TARGET = target;
        return target;
    }

    /**
     * Drive {@code bodyNode} to completion under {@code handler}, running
     * body inside a Loom-backed fiber so op calls can suspend.
     *
     * @param handler      the handler record (fields are op handler functions)
     * @param bodyNode     the body to execute; runs inside the fiber
     * @param outerFrame   the caller's virtual frame (used to materialize a
     *                     captured frame for body execution inside the fiber)
     * @param callNode     shared IndirectCallNode for downstream calls
     * @param dispatchNode the owning Truffle node (for MezzeContext.get)
     * @return the perform's result — body's natural return or an aborted value
     */
    @TruffleBoundary
    public static Object run(
            MezzeRecord handler,
            MezzeNode bodyNode,
            MaterializedFrame bodyFrame,
            IndirectCallNode callNode,
            MezzeNode dispatchNode) {

        // The outer fiber (if any) — the one whose body is executing this
        // perform. When an op-call reaches our handler that doesn't match
        // any field, we propagate it upward by suspending the outer fiber
        // with the same OpCall, so a surrounding HandleNode's driver can
        // try to dispatch it.
        EffectFiber outerFiber = EffectFiber.current();

        EffectFiber[] fiberHolder = new EffectFiber[1];

        // Body wrapped as a Runnable for the fiber. When body completes
        // naturally, its return value is stashed as a BodyResult so the
        // driver can distinguish it from an OpCall suspend.
        Runnable bodyRunner = () -> {
            Object result = bodyNode.execute(bodyFrame);
            fiberHolder[0].setSuspendedValue(new BodyResult(result));
        };

        fiberHolder[0] = EffectFiber.create("effect-handler", bodyRunner);

        // initialize_scope: optional hook producing the `scope` value.
        // Only touch the MezzeContext scope var if the handler actually has
        // an initialize_scope hook — handlers without scope hooks must not
        // disturb any outer perform's scope.
        Object scope = invokeInitializeScope(handler, callNode);
        boolean installedScope = scope != null;
        Object prevScope = installedScope ? installScope(dispatchNode, scope) : null;

        // Snapshot chain depth so we can restore it deterministically in
        // the outer finally — even when this perform's drive yields
        // cross-scope to an enclosing fiber (e.g. an inner perform whose
        // body propagates an unhandled op to the outer fiber, then the
        // outer aborts). Cross-scope yields bypass intervening Java
        // finally blocks, so popHandlerOffChain in the conventional path
        // wouldn't fire for nested performs.
        int chainDepthBefore = EffectFiber.handlerChainDepth();
        EffectFiber.pushHandlerOnChain(handler);

        Object performResult;
        try {
            performResult = drive(handler, fiberHolder[0], outerFiber, null, callNode);
        } catch (AbortPerformException abort) {
            performResult = abort.value;
        } finally {
            EffectFiber.truncateHandlerChainTo(chainDepthBefore);
            if (installedScope) {
                invokeFinalizeScope(handler, scope, callNode);
                restoreScope(dispatchNode, prevScope);
            }
        }
        return performResult;
    }

    /**
     * Result of one op-handler invocation. {@code resumed} is true when the
     * handler explicitly called {@code resume {val = ...}} during its body
     * — in that case it already drove the fiber forward, and {@code opResult}
     * is whatever value came back. When false, the handler fell through —
     * its return value should be fed back into the fiber as the op-call's
     * suspend value.
     */
    private record OpDispatchResult(boolean resumed, Object opResult) {}

    /**
     * Shared op-call dispatch step: push runtime-supplied {@code resume}/
     * {@code abort} handles for this invocation, pin the fiber as the
     * dispatch fiber, call the matched op handler, pop and unpin. Caller
     * decides what to do with the result (return it to the perform if
     * resumed, or drive the fiber forward with it on fall-through).
     *
     * <p>The op handler's body reads {@code resume}/{@code abort} via
     * {@link com.mezze.interpreter.nodes.effect.EffectBindingNode}, which
     * pulls from the dispatch fiber's effect-handle stack. Nested op
     * calls each push their own frame.
     */
    private static OpDispatchResult dispatchOp(
            MezzeFunction opHandler,
            OpCall opCall,
            EffectFiber fiber,
            MezzeFunction resumeFn,
            MezzeFunction abortFn,
            IndirectCallNode callNode) {
        fiber.pushEffectHandles(resumeFn, abortFn);
        EffectFiber prevDispatch = fiber.enterDispatch();
        Object opResult;
        try {
            Object[] cv = opHandler.getClosureValues();
            Object[] opArgs = new Object[cv.length + 1];
            System.arraycopy(cv, 0, opArgs, 0, cv.length);
            opArgs[cv.length] = opCall.args();
            opResult = callNode.call(opHandler.getCallTarget(), opArgs);
        } finally {
            EffectFiber.exitDispatch(prevDispatch);
        }
        boolean resumed = fiber.wasResumedAtCurrentFrame();
        fiber.popEffectHandles();
        return new OpDispatchResult(resumed, opResult);
    }

    /**
     * Drive the fiber forward by one cycle. If the fiber is fresh, calls
     * {@code run()}; otherwise injects {@code resumeWith} via setResumeValue
     * and then runs. After the fiber suspends or completes, decides:
     * <ul>
     *   <li>{@link BodyResult} =&gt; return body's value</li>
     *   <li>{@link OpCall}     =&gt; dispatch to handler operation</li>
     * </ul>
     * Throws {@link AbortPerformException} if the op handler aborts.
     */
    @TruffleBoundary
    private static Object drive(
            MezzeRecord handler,
            EffectFiber fiber,
            EffectFiber outerFiber,
            Object resumeWith,
            IndirectCallNode callNode) {
        // Originally written recursively (every op-call dispatch tail-called
        // drive() with the next resume value). Java doesn't TCO so a body
        // with N op-calls in one perform grew the Java stack N deep — N=10K
        // blew the stack with default 512KB. Now an explicit while loop:
        // unbounded op-calls per perform without stack growth. #128.
        while (true) {
            if (resumeWith != null) {
                fiber.setResumeValue(resumeWith);
            }
            fiber.run();

            Object suspended = fiber.takeSuspendedValue();
            if (suspended instanceof BodyResult br) {
                return br.value();
            }
            if (!(suspended instanceof OpCall opCall)) {
                throw new RuntimeException(
                    "EffectHandlerDriver: unexpected suspend payload: "
                    + (suspended == null ? "null" : suspended.getClass().getName()));
            }

            // Look up the handler operation by name.
            Object opHandlerObj = handler.getField(opCall.opName());
            if (!(opHandlerObj instanceof MezzeFunction opHandler)) {
                // Op isn't handled here — propagate to the outer fiber so a
                // surrounding HandleNode's driver can try to dispatch it.
                // If there's no outer fiber AND we're inside a spawned task
                // (TASK_BOUNDARY thread-local set by runFunctionThroughChain),
                // throw a TaskUnhandledOpException so Async.spawn can store
                // the op on the promise for re-raise at await time.
                if (outerFiber == null) {
                    if (TASK_BOUNDARY.get()) {
                        throw new TaskUnhandledOpException(opCall);
                    }
                    // Partial-handling: this perform handled some effects but
                    // not this op. Bubble out as MezzeSuspended carrying the
                    // unhandled effect; an outer perform can wrap this result
                    // and force it, or top-level code can hold a deferred
                    // computation. The driver short-circuits — body's fiber
                    // never resumes from here.
                    return makeUnhandledSuspended(opCall);
                }
                // Outer handler eventually resumes us with a value. Continue
                // driving this fiber forward with that value as the local
                // op-call's return.
                resumeWith = outerFiber.suspend(opCall);
                continue;
            }

            // Dispatch the op. resume/abort handles flow through the fiber's
            // handle stack — not the op handler's argument record — and the
            // op handler body reads them via EffectBindingNode.
            OpDispatchResult result = dispatchOp(
                opHandler, opCall, fiber,
                makeResumeFunction(handler, fiber, outerFiber, callNode),
                makeAbortFunction(),
                callNode);

            // Fall-through vs explicit-resume:
            //   - If the op handler explicitly called resume during its body,
            //     it already drove the fiber forward; the value it produced
            //     becomes this perform's result.
            //   - Otherwise the handler "fell through" — implicitly equivalent
            //     to `resume {val = opResult}`. Drive the fiber forward with
            //     opResult as the suspend's return value.
            if (result.resumed()) {
                return result.opResult();
            }
            resumeWith = result.opResult();
            // loop
        }
    }

    // ----------------------------------------------------------------
    // Runtime-bound `resume` and `abort`
    // ----------------------------------------------------------------

    // The resume function's BODY is the same shape across every op-call
    // dispatch: it reads `val` from the user arg, marks the fiber as
    // resumed, and calls drive(...). What varies per dispatch is the
    // (handler, fiber, outerFiber, callNode) tuple. We cache one
    // CallTarget that reads those four from the MezzeFunction's closure
    // values, instead of building a fresh CallTarget+FrameDescriptor
    // (with closed-over Java state) per op-call. Saves the bulk of the
    // per-op allocation cost. #88.
    private static volatile RootCallTarget CACHED_RESUME_TARGET = null;

    /**
     * Build a {@code resume} function the op handler may call. Calling
     * {@code resume {val = X}} drives the fiber forward with X as the
     * suspend's return value, and returns whatever body eventually produces
     * (either its natural return or a value from a nested op handler).
     */
    private static MezzeFunction makeResumeFunction(
            MezzeRecord handler,
            EffectFiber fiber,
            EffectFiber outerFiber,
            IndirectCallNode callNode) {
        RootCallTarget target = CACHED_RESUME_TARGET;
        if (target == null) target = buildAndCacheResumeTarget();
        // Closure layout matches the order resumeBody reads them in
        // frame.getArguments(): [handler, fiber, outerFiber, callNode]
        // followed by the user arg ({val = X}).
        return new MezzeFunction(
            target,
            ScopedEffectConstants.RESUME,
            new Object[]{handler, fiber, outerFiber, callNode}
        );
    }

    @TruffleBoundary
    private static synchronized RootCallTarget buildAndCacheResumeTarget() {
        if (CACHED_RESUME_TARGET != null) return CACHED_RESUME_TARGET;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode resumeBody = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                // frameArgs layout: [handler, fiber, outerFiber, callNode, argRecord]
                MezzeRecord handler = (MezzeRecord) frameArgs[0];
                EffectFiber fiber = (EffectFiber) frameArgs[1];
                EffectFiber outerFiber = (EffectFiber) frameArgs[2];
                IndirectCallNode callNode = (IndirectCallNode) frameArgs[3];
                int recordIdx = frameArgs.length - 1;
                if (recordIdx < 4 || !(frameArgs[recordIdx] instanceof MezzeRecord rec)) {
                    throw new RuntimeException(
                        "resume expects record argument {val = ...}");
                }
                Object val = rec.getField(ScopedEffectConstants.VAL_FIELD);
                fiber.markResumedAtCurrentFrame();
                // Catch AbortPerformException here so resume invocations
                // that escape the original op-handler body (e.g. captured
                // inside an abort'd return value and called later by user
                // code, as in stream-shaped Generator handlers) translate
                // a subsequent abort into the value resume returns. The
                // outermost `run` only catches abort once; deferred resume
                // re-entry needs its own catch.
                try {
                    return drive(handler, fiber, outerFiber, val, callNode);
                } catch (AbortPerformException abort) {
                    return abort.value;
                }
            }
        };
        RootCallTarget target = new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            resumeBody,
            "effect-handler-resume",
            4,   // closureCount
            1,   // paramCount
            new int[0]
        ).getCallTarget();
        CACHED_RESUME_TARGET = target;
        return target;
    }

    // The abort function is stateless — its body just reads `val` out of
    // the argument record and throws AbortPerformException(val). No
    // closure-captured handler/fiber/etc. So one MezzeFunction can be
    // shared across all op-call dispatches forever. Saves ~1µs/op of
    // per-call allocation (FrameDescriptor + MezzeRootNode + CallTarget +
    // MezzeFunction). #88.
    private static volatile MezzeFunction CACHED_ABORT_FN = null;

    /**
     * Get the (cached) {@code abort} function for op handler bodies.
     * Calling {@code abort {val = X}} terminates the surrounding perform
     * with X as its overall result, discarding body. Implemented by
     * throwing {@link AbortPerformException}, which only {@link #run} catches.
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
    // ----------------------------------------------------------------

    @TruffleBoundary
    private static Object invokeInitializeScope(MezzeRecord handler, IndirectCallNode callNode) {
        Object fn = handler.getField(ScopedEffectConstants.INITIALIZE_SCOPE);
        if (!(fn instanceof MezzeFunction initFn)) {
            return null;
        }
        return invokeHookWithEmptyArg(initFn, callNode);
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

    /**
     * Build a {@link com.mezze.interpreter.values.MezzeSuspended} for an op
     * the current handler doesn't have. Used when partial handling leaves
     * an effect dangling — the perform returns the suspended computation
     * to its caller, which may install another handler later.
     */
    @TruffleBoundary
    private static MezzeSuspended makeUnhandledSuspended(OpCall opCall) {
        var thunk = new MezzeThunk(
            /* effectName */ null,
            opCall.opName(),
            opCall.args(),
            opCall.effectHash()
        );
        java.util.Set<Hash> effects =
            opCall.effectHash() != null
                ? java.util.Collections.singleton(opCall.effectHash())
                : java.util.Collections.emptySet();
        return new MezzeSuspended(thunk, effects);
    }
}
