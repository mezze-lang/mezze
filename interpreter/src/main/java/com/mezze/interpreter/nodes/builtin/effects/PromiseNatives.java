package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
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
 * a fresh capability record {@code { complete, fail }} whose closures
 * mutate a freshly-allocated {@link MezzePromise} via its CAS state
 * machine. If {@code setup} raises, the promise is auto-failed so it
 * doesn't leak in Pending forever.
 *
 * <p>{@code promise_of} / {@code promise_failed} are pure Mezze sugar
 * defined on top of {@code promise_from}; nothing to see here for them.
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li>Pre-cached {@link RootCallTarget}s for the two capability shapes
 *       — built once at class init, shared across all {@code promise_from}
 *       calls, warm the JIT.</li>
 *   <li>Pre-cached {@link MezzeRecord.Shape}s for {@code {complete, fail}},
 *       {@code {val}}, {@code {err}} live in {@code MezzeRecord}.
 *       Zero {@code @TruffleBoundary} on record construction / field
 *       reads.</li>
 *   <li>Per-call cost: 1 {@code MezzePromise} + 2 {@code MezzeFunction} +
 *       2 closure {@code Object[1]} + 1 capability record + setup args
 *       {@code Object[]}. All small, all short-lived, all escape-
 *       analyzable when {@code promise_from} inlines.</li>
 * </ul>
 */
public final class PromiseNatives {

    private PromiseNatives() {}

    // MezzeFunction names for the two capabilities. Same strings as the
    // field names in MezzeRecord.SHAPE_COMPLETE_FAIL — the JVM string
    // pool interns them once, we reuse in two places.
    private static final String NAME_COMPLETE = "complete";
    private static final String NAME_FAIL     = "fail";

    // Built once at class init.
    private static final RootCallTarget COMPLETE_TARGET =
        buildCapTarget(/* isComplete = */ true);
    private static final RootCallTarget FAIL_TARGET =
        buildCapTarget(/* isComplete = */ false);

    /**
     * Build the shared {@link RootCallTarget} for a capability body.
     *
     * <p>The body reads slot 0 of frameArgs (the closure) as the Promise
     * being built, reads the last frameArg as the caller's record arg
     * ({@code {val: 'a}} or {@code {err}}), and forwards to
     * {@link MezzePromise#complete(Object)} or
     * {@link MezzePromise#fail(Object)}.
     *
     * <p>Both capability targets share the same MezzeRootNode shape
     * (1 closure = Promise, 1 param = the caller's record). Only the
     * body differs — which method on the promise to call, which record
     * field to read.
     */
    @TruffleBoundary
    private static RootCallTarget buildCapTarget(boolean isComplete) {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] frameArgs = frame.getArguments();
                // Closure at [0], record arg at last position.
                MezzePromise promise = (MezzePromise) frameArgs[0];
                MezzeRecord rec = (MezzeRecord) frameArgs[frameArgs.length - 1];
                // Fast slot-0 read — both {val} and {err} put payload at index 0.
                Object payload = rec.getField(0);
                // MezzePromise.complete/fail are CAS-safe by construction
                // (#79): the single-outcome design means losing writers
                // return false with no side effect.
                if (isComplete) {
                    promise.complete(payload);
                } else {
                    promise.fail(payload);
                }
                return MezzeUnit.INSTANCE;
            }
        };
        return new MezzeRootNode(
            MezzeLanguage.get(null),
            fd,
            body,
            isComplete ? NAME_COMPLETE : NAME_FAIL,
            /* closureCount = */ 1,
            /* parameterCount = */ 1,
            new int[0]  // read frameArgs directly; no frame-slot binding needed
        ).getCallTarget();
    }

    /**
     * Native handler for {@code promise_from { setup } -> Promise 'a}.
     *
     * <p>Creates a fresh {@link MezzePromise}, builds two per-call
     * capability {@link MezzeFunction}s closing over it, packages them
     * in a {@code {complete, fail}} record, and invokes {@code setup}.
     *
     * <p>If {@code setup} throws, the promise is auto-failed with the
     * throwable so callers observing the promise get a structured
     * failure rather than a hang.
     *
     * <p>Called via {@link com.mezze.interpreter.nodes.builtin.NativeDispatch}.
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

        MezzeRecord capabilities = MezzeRecord.wrapCompleteFail(complete, fail);

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
