package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * User-space Promise constructors backing {@code std::effects::async}'s
 * {@code promise_from}, {@code promise_of}, {@code promise_failed}.
 *
 * <p>{@code promise_from} is the primitive. It invokes {@code setup} with
 * a fresh capability record {@code { complete, fail, on_cancel }} whose
 * closures mutate a freshly-allocated {@link MezzePromise}:
 * <ul>
 *   <li>{@code complete { val = ... }} — CAS-transition the promise to
 *       Completed.</li>
 *   <li>{@code fail { err = ... }} — CAS-transition to Failed.</li>
 *   <li>{@code on_cancel { hook = { reason } -> ... }} — register a
 *       cancel hook that fires with the {@link CancelReason} when the
 *       promise is cancelled.</li>
 * </ul>
 * If {@code setup} raises, the promise is auto-failed so it doesn't
 * leak in Pending forever.
 *
 * <p>{@code promise_of} / {@code promise_failed} are pure Mezze sugar
 * defined on top of {@code promise_from}; nothing to see here for them.
 *
 * <h2>Naming discipline</h2>
 * All names in this file trace back to the corresponding field name in
 * the setup capability record:
 * <pre>
 *   Setup field       Mezze name    Java constant    CallTarget
 *   { complete = ... } "complete"   NAME_COMPLETE    COMPLETE_TARGET
 *   { fail = ... }     "fail"       NAME_FAIL        FAIL_TARGET
 *   { on_cancel = ...} "on_cancel"  NAME_ON_CANCEL   ON_CANCEL_TARGET
 * </pre>
 * Corresponding record shapes live in {@link MezzeRecord}:
 * SHAPE_COMPLETE_FAIL_ON_CANCEL, SHAPE_VAL, SHAPE_ERR, SHAPE_HOOK,
 * SHAPE_REASON.
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li>Pre-cached {@link RootCallTarget}s for the three capability
 *       bodies — built once at class init, shared across all
 *       {@code promise_from} calls, warm the JIT.</li>
 *   <li>Pre-cached {@link MezzeRecord.Shape}s live in {@link MezzeRecord}
 *       — zero {@code @TruffleBoundary} on record construction / field
 *       reads.</li>
 *   <li>Per-call cost: 1 {@code MezzePromise} + 3 {@code MezzeFunction} +
 *       3 closure {@code Object[1]} + 1 capability record + setup args
 *       {@code Object[]}. All small, all short-lived, all escape-
 *       analyzable when {@code promise_from} inlines.</li>
 * </ul>
 */
public final class PromiseNatives {

    private PromiseNatives() {}

    // MezzeFunction names for the three capabilities. Same strings as
    // the field names in MezzeRecord.SHAPE_COMPLETE_FAIL_ON_CANCEL — the
    // JVM string pool interns them once, we reuse them in two places.
    private static final String NAME_COMPLETE  = "complete";
    private static final String NAME_FAIL      = "fail";
    private static final String NAME_ON_CANCEL = "on_cancel";

    // Built once at class init.
    private static final RootCallTarget COMPLETE_TARGET  = buildResolveTarget(true);
    private static final RootCallTarget FAIL_TARGET      = buildResolveTarget(false);
    private static final RootCallTarget ON_CANCEL_TARGET = buildOnCancelTarget();

    /**
     * Build the {@link RootCallTarget} for a resolve capability
     * ({@code complete} or {@code fail}).
     *
     * <p>The body reads slot 0 of frameArgs (the closure) as the Promise
     * being built, reads the last frameArg as the caller's record arg
     * ({@code {val: 'a}} or {@code {err}}), and forwards to
     * {@link MezzePromise#complete(Object)} or {@link MezzePromise#fail(Object)}.
     */
    @TruffleBoundary
    private static RootCallTarget buildResolveTarget(boolean isComplete) {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                MezzePromise promise = (MezzePromise) frameArgs[0];
                MezzeRecord rec = (MezzeRecord) frameArgs[frameArgs.length - 1];
                // Fast slot-0 read — both {val} and {err} put payload at index 0.
                Object payload = rec.getField(0);
                // complete/fail are CAS-safe by construction (#79): the
                // single-outcome design means losing writers return false
                // with no side effect.
                if (isComplete) {
                    promise.complete(payload);
                } else {
                    promise.fail(payload);
                }
                return MezzeRecord.empty();
            }
        };
        return new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            body,
            isComplete ? NAME_COMPLETE : NAME_FAIL,
            /* closureCount = */ 1,
            /* parameterCount = */ 1,
            new int[0]
        ).getCallTarget();
    }

    /**
     * Build the {@link RootCallTarget} for the {@code on_cancel} capability.
     *
     * <p>The body reads slot 0 of frameArgs (the closure) as the Promise,
     * reads the last frameArg as the caller's record arg ({@code {hook: ...}}),
     * extracts the {@code hook} MezzeFunction, wraps it in a Java
     * {@link java.util.function.Consumer} that when invoked by
     * {@link MezzePromise#cancel(Object)} calls the hook with
     * {@code { reason = <the cancel reason> }}, and registers it on the
     * promise via {@link MezzePromise#setCancelHook}.
     *
     * <p>Last-writer-wins semantics: repeated on_cancel calls in the same
     * setup body overwrite the previous hook.
     *
     * <p>The hook may run on any thread (whoever wins the cancel CAS).
     * We use {@link IndirectCallNode#getUncached()} for that deferred
     * invocation because we don't have a cached call node available
     * across the potentially-cross-thread boundary.
     */
    @TruffleBoundary
    private static RootCallTarget buildOnCancelTarget() {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                MezzePromise promise = (MezzePromise) frameArgs[0];
                MezzeRecord rec = (MezzeRecord) frameArgs[frameArgs.length - 1];
                // Fast slot-0 read on SHAPE_HOOK — the hook MezzeFunction.
                MezzeFunction hookFn = (MezzeFunction) rec.getField(0);
                promise.setCancelHook(reason -> invokeHook(hookFn, reason));
                return MezzeRecord.empty();
            }
        };
        return new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            body,
            NAME_ON_CANCEL,
            /* closureCount = */ 1,
            /* parameterCount = */ 1,
            new int[0]
        ).getCallTarget();
    }

    /**
     * Invoke the user's cancel hook with {@code { reason = <reason> }}.
     * Runs on the thread that won the cancel CAS. Errors thrown by
     * the hook are swallowed at the MezzePromise.cancel call site
     * (this method is invoked from within that try/catch).
     */
    @TruffleBoundary
    private static void invokeHook(MezzeFunction hookFn, Object reason) {
        MezzeRecord arg = MezzeRecord.wrapReason(reason);
        Object[] closureValues = hookFn.getClosureValues();
        Object[] hookArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, hookArgs, 0, closureValues.length);
        hookArgs[closureValues.length] = arg;
        IndirectCallNode.getUncached().call(hookFn.getCallTarget(), hookArgs);
    }

    /**
     * Native handler for {@code promise_from { setup } -> Promise 'a}.
     *
     * <p>Creates a fresh {@link MezzePromise}, builds three per-call
     * capability {@link MezzeFunction}s closing over it, packages them
     * in a {@code {complete, fail, on_cancel}} record, and invokes
     * {@code setup}.
     *
     * <p>If {@code setup} throws, the promise is auto-failed with the
     * throwable so callers observing the promise get a structured
     * failure rather than a hang.
     */
    @TruffleBoundary
    public static MezzePromise promiseFrom(
            MezzeRecord args,
            IndirectCallNode callNode,
            MezzeNode contextNode
    ) {
        MezzeFunction setup = (MezzeFunction) args.getField("setup");

        MezzePromise promise = new MezzePromise(null, null);

        MezzeFunction complete = new MezzeFunction(
            COMPLETE_TARGET, NAME_COMPLETE, new Object[]{promise});
        MezzeFunction fail = new MezzeFunction(
            FAIL_TARGET, NAME_FAIL, new Object[]{promise});
        MezzeFunction onCancel = new MezzeFunction(
            ON_CANCEL_TARGET, NAME_ON_CANCEL, new Object[]{promise});

        MezzeRecord capabilities = MezzeRecord.wrapCompleteFailOnCancel(complete, fail, onCancel);

        Object[] closureValues = setup.getClosureValues();
        Object[] setupArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, setupArgs, 0, closureValues.length);
        setupArgs[closureValues.length] = capabilities;

        try {
            callNode.call(setup.getCallTarget(), setupArgs);
        } catch (Throwable e) {
            // Auto-fail so a raising setup doesn't leave the promise
            // Pending forever. fail() is CAS-safe: if setup already
            // resolved the promise before throwing, this is a no-op.
            promise.fail(e);
        }

        return promise;
    }

    // Note: promise_of / promise_failed use existing
    // AsyncNatives.promiseSucceed / promiseFail natives directly —
    // no need to duplicate the trivial completed/failed factories here.
}
