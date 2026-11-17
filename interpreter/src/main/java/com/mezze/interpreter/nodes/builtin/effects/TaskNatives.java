package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.nodes.continuation.TaskMachine;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Natives of the task machine (continuations-and-swappable-async). Stdlib-only
 * (#109): `std::effects::async` wraps them as the `EventLoop` performer's `spawn`,
 * `promise_id`, and the `RunQueue` / `Task` handle methods a `drive` selector uses.
 */
public final class TaskNatives {
    private TaskNatives() {}

    private static TaskMachine machine() {
        return MezzeContext.get(null).taskMachine();
    }

    @TruffleBoundary
    public static Object spawn(MezzeRecord args) {
        Object f = args.getField("f");
        if (!(f instanceof MezzeFunction fn)) throw new MezzeGuestException("__native_task_spawn: `f` must be a function");
        return machine().spawn(fn);
    }

    @TruffleBoundary
    public static Object promiseId(MezzeRecord args) {
        Object p = args.getField("p");
        if (!(p instanceof AsyncNatives.MezzePromise promise)) throw new MezzeGuestException("__native_promise_id: `p` must be a Promise");
        return machine().promiseId(promise);
    }

    private static TaskMachine.RunQueue queue(MezzeRecord args) {
        Object q = args.getField("q");
        if (!(q instanceof TaskMachine.RunQueue rq)) throw new MezzeGuestException("expected a RunQueue, got " + q);
        return rq;
    }

    @TruffleBoundary public static Object runqFirst(MezzeRecord args) { return nonNull(queue(args).first(), "first"); }
    @TruffleBoundary public static Object runqLast(MezzeRecord args) { return nonNull(queue(args).last(), "last"); }
    @TruffleBoundary public static Object runqLen(MezzeRecord args) { return queue(args).len(); }
    @TruffleBoundary public static Object runqAt(MezzeRecord args) {
        Object i = args.getField("i");
        if (!(i instanceof Long n)) throw new MezzeGuestException("RunQueue.at: `i` must be an Int");
        return queue(args).at(n);
    }

    @TruffleBoundary
    public static Object taskId(MezzeRecord args) {
        Object t = args.getField("t");
        if (!(t instanceof TaskMachine.Task task)) throw new MezzeGuestException("expected a Task, got " + t);
        return task.id;
    }

    private static Object nonNull(Object t, String what) {
        if (t == null) throw new MezzeGuestException("RunQueue." + what + ": the run queue is empty — drive is only called with at least one runnable task");
        return t;
    }
}
