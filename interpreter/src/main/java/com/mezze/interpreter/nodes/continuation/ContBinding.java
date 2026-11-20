package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.ast.ScopedEffectConstants;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * The value of the runtime-bound name {@code cont} inside a performer op body:
 * a record {@code { park, abort }}. One per language instance (a root node
 * belongs to one engine's sharing layer — see {@code MezzeLanguage.fallThroughResumeFn}).
 *
 * <p>{@code park} does not block and does not capture anything itself: it returns
 * a {@link ParkRequest}, and the lowering's suspension checks carry it out to the
 * perform boundary (see that class). {@code abort} is the ordinary abort.
 */
public final class ContBinding {
    private ContBinding() {}

    /**
     * The predicate `yield_now` parks on: a real `{} -> True` the machine recognises BY IDENTITY, so
     * a yield costs no predicate call and no parked-list round trip (`TaskMachine.park`), and a
     * thread-per-task performer answers it with `Thread.yield`. Handed out by
     * `__native_ready_predicate`.
     */
    public static MezzeFunction readyPredicate() {
        MezzeLanguage language = MezzeLanguage.get(null);
        MezzeFunction cached = language.readyPredicate;
        if (cached != null) return cached;
        return buildReady(language);
    }

    @TruffleBoundary
    private static synchronized MezzeFunction buildReady(MezzeLanguage language) {
        if (language.readyPredicate != null) return language.readyPredicate;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override public Object execute(VirtualFrame frame) { return Boolean.TRUE; }
        };
        RootCallTarget target = new MezzeRootNode(language, fd, body, "ready", 0, 1, new int[0]).getCallTarget();
        MezzeFunction f = new MezzeFunction(target, "ready", new Object[0]);
        language.readyPredicate = f;
        return f;
    }

    /**
     * `wait_on { on, test }`: a predicate FUNCTION (so every performer's `park` accepts it) that
     * also names the wait queue it belongs to. Closure values: [queue, test]. The task machine
     * recognises the shared call target, registers the parked task with the queue and evaluates
     * `test` only when the queue is signalled; a thread-per-task performer just calls it.
     */
    public static RootCallTarget waitOnTarget() {
        MezzeLanguage language = MezzeLanguage.get(null);
        RootCallTarget cached = language.waitOnTarget;
        if (cached != null) return cached;
        return buildWaitOn(language);
    }

    public static MezzeFunction waitOn(WaitQueue queue, MezzeFunction test) {
        return new MezzeFunction(waitOnTarget(), "wait_on", new Object[]{queue, test});
    }

    /** The queue a wait_on predicate names, or null for an ordinary predicate. */
    public static WaitQueue queueOf(Object predicate) {
        if (predicate instanceof MezzeFunction f && f.getCallTarget() == MezzeLanguage.get(null).waitOnTarget) {
            return (WaitQueue) f.getClosureValues()[0];
        }
        return null;
    }

    @TruffleBoundary
    private static synchronized RootCallTarget buildWaitOn(MezzeLanguage language) {
        if (language.waitOnTarget != null) return language.waitOnTarget;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            @Override public Object execute(VirtualFrame frame) {
                Object[] a = frame.getArguments();            // [queue, test, argRecord]
                return TaskMachine.callWith((MezzeFunction) a[1], MezzeRecord.empty());
            }
        };
        RootCallTarget target = new MezzeRootNode(language, fd, body, "wait_on", 2, 1, new int[0]).getCallTarget();
        language.waitOnTarget = target;
        return target;
    }

    public static Object contRecord() {
        MezzeLanguage language = MezzeLanguage.get(null);
        Object cached = language.contRecord;
        if (cached != null) return cached;
        return build(language);
    }

    @TruffleBoundary
    private static synchronized Object build(MezzeLanguage language) {
        if (language.contRecord != null) return language.contRecord;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            private Object lastShape; private int onIdx, setIdx;
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] args = frame.getArguments();
                Object last = args.length == 0 ? null : args[args.length - 1];
                if (!(last instanceof MezzeRecord rec)) {
                    throw new RuntimeException("cont.park expects a record argument { on, set }");
                }
                // Field indices cached per shape (shapes are interned): no string lookup per park.
                Object shape = rec.getShape();
                int on = onIdx, set = setIdx;
                if (shape != lastShape) {
                    on = rec.getFieldIndex("on"); set = rec.getFieldIndex("set");
                    onIdx = on; setIdx = set; lastShape = shape;
                }
                // Blocks this carrier until the machine delivers the outcome; no continuation.
                return com.mezze.interpreter.runtime.MezzeContext.get(this).taskMachine()   // node-based lookup: compiled, no slow path
                    .park(new ParkRequest(rec.getField(on), rec.getField(set)));
            }
        };
        RootCallTarget target = new MezzeRootNode(language, fd, body, "cont-park", 0, 1, new int[0]).getCallTarget();
        MezzeFunction park = new MezzeFunction(target, ScopedEffectConstants.CONT_PARK, new Object[0]);
        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{ScopedEffectConstants.CONT_PARK, ScopedEffectConstants.CONT_ABORT},
            new Object[]{park, EffectHandlerDriver.makeAbortFunction()}
        );
        language.contRecord = rec;
        return rec;
    }
}
