package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;

/**
 * The task machine of continuations-and-swappable-async: one per {@code MezzeContext},
 * owned by the runtime, reachable from every performer (design, risk 1: a {@code Time}
 * performer parks a task the Async machine owns, so the machine cannot live in a
 * scheduler's scope).
 *
 * <p>A task is a Mezze computation that may suspend. Its suspension is a STACK of
 * {@link ContinuationResult}s — each frame between the park and the boundary yielded
 * the inner frame's continuation onward (C2) — so a task keeps the outer frames in
 * {@link Task#frames} and the frame that yielded the {@link ParkRequest} as its leaf.
 * Resuming is {@code leaf.continueWith(outcome)} and then handing each completed
 * value outward until a frame suspends again or the task is done.
 *
 * <p>Policy is the performer's: {@code drive = { runnable } -> runnable.first {} }
 * picks the next task from an opaque O(1) view of the ready queue; with no
 * {@code drive} the machine is FIFO. Delivery is never re-entrant — a run returns
 * when the task next suspends or finishes, and the loop is flat.
 *
 * <p>Wakes: a task parked {@code OnPromises} wakes when one of them settles, parked
 * {@code OnPredicates} when one is true. Both are checked after every run (the
 * registered O(1) wake the design describes is an optimisation over this; the
 * semantics are the same). When nothing is runnable and nothing can wake — no
 * external promise pending — that is a DEADLOCK, the one error the machine raises.
 *
 * <p>A BOUNDARY — one cont-capable `perform` — owns the tasks spawned while it is
 * the innermost one, drives only those, and at exit tears them down: leftover
 * runnable tasks run to completion, tasks still parked are delivered
 * {@code ParkCancelled { reason = CancelScopeTeardown }} and run to completion,
 * with a bound on re-parking during teardown (open rule 3). An inner boundary
 * therefore never runs or cancels an outer boundary's tasks.
 *
 * <p>D1 limitation, stated: tasks run under the handler chain of the boundary that
 * drives them (Phase B — evidence travelling with the task — is what lifts it).
 */
public final class TaskMachine {

    public static final int RUNNABLE = 0, RUNNING = 1, PARKED = 2, DONE = 3;

    /** Opaque to Mezze: `Task` in std::effects::async. */
    public static final class Task implements TruffleObject {
        public final long id;
        MezzeFunction thunk;                       // set until the first run
        final ArrayDeque<ContinuationResult> frames = new ArrayDeque<>();
        ContinuationResult leaf;
        ParkRequest parkedOn;
        long epoch;
        Object deliver;
        int state = RUNNABLE;
        final MezzePromise promise;                // null for a boundary body
        Object result;

        Task(long id, MezzeFunction thunk, MezzePromise promise) {
            this.id = id; this.thunk = thunk; this.promise = promise;
        }

        @Override public String toString() { return "Task#" + id; }
    }

    /** Opaque to Mezze: `RunQueue` — the ready set as the performer's `drive` sees it. */
    public static final class RunQueue implements TruffleObject {
        private final TaskMachine m;
        private final Boundary b;
        RunQueue(TaskMachine m, Boundary b) { this.m = m; this.b = b; }
        public Object first() { return b.ready.peekFirst(); }
        public Object last() { return b.ready.peekLast(); }
        public long len() { return b.ready.size(); }
        public Object at(long i) {
            if (i < 0 || i >= b.ready.size()) throw new MezzeGuestException(
                "RunQueue.at: index " + i + " out of range, " + b.ready.size() + " runnable");
            Iterator<Task> it = b.ready.iterator();
            for (long k = 0; k < i; k++) it.next();
            return it.next();
        }
    }

    /** One cont-capable perform: its body task and the tasks spawned under it. */
    private static final class Boundary {
        final ArrayDeque<Task> ready = new ArrayDeque<>();
        final ArrayList<Task> parked = new ArrayList<>();
        final MezzeFunction drive;
        final RunQueue runQueue;
        Boundary(MezzeFunction drive, TaskMachine m) { this.drive = drive; this.runQueue = new RunQueue(m, this); }
    }

    private final ArrayDeque<Boundary> boundaries = new ArrayDeque<>();
    private long nextTaskId = 1;
    private final IdentityHashMap<MezzePromise, Long> promiseIds = new IdentityHashMap<>();
    private final HashMap<Long, MezzePromise> promisesById = new HashMap<>();
    private final IdentityHashMap<MezzePromise, Boolean> ownPromises = new IdentityHashMap<>();
    /** Stale wakes — aimed at an epoch the task has left. Countable, never silent. */
    public long staleWakes;

    // ---------------------------------------------------------------- natives' side

    /** `__native_task_spawn { f }`: register `f {}` as a new task; its promise settles when it finishes. */
    @TruffleBoundary
    public MezzePromise spawn(MezzeFunction f) {
        Boundary b = boundaries.peek();
        if (b == null) throw new MezzeGuestException(
            "spawn_task outside a scheduler: a task needs a cont-capable `perform` (one whose "
            + "performer mentions `cont`) to be driven by");
        MezzePromise p = new MezzePromise(null, null);
        ownPromises.put(p, Boolean.TRUE);
        Task t = new Task(nextTaskId++, f, p);
        b.ready.addLast(t);
        return p;
    }

    @TruffleBoundary
    public long promiseId(MezzePromise p) {
        Long id = promiseIds.get(p);
        if (id == null) {
            id = (long) promiseIds.size() + 1;
            promiseIds.put(p, id);
            promisesById.put(id, p);
        }
        return id;
    }


    // ---------------------------------------------------------------- the boundary

    /** Re-parks tolerated during teardown before it is an error (open rule 3). */
    private static final int TEARDOWN_ROUNDS = 8;

    /**
     * Run a cont-capable perform's body thunk as this boundary's body task, drive
     * the boundary until the body is done, tear its tasks down, and hand the body's
     * value back. A body that never suspends and spawns nothing costs one push, one
     * call, one type test and one pop.
     */
    @TruffleBoundary
    public Object runBoundary(MezzeFunction bodyThunk, MezzeRecord handler) {
        Boundary b = new Boundary(driveOf(handler), this);
        boundaries.push(b);
        try {
            Task body = new Task(nextTaskId++, null, null);
            absorb(b, body, callWith(bodyThunk, MezzeRecord.empty()));
            while (body.state != DONE) {
                if (b.ready.isEmpty()) {
                    if (!wakeParked(b)) blockOrDeadlock(b);
                    continue;
                }
                run(b, pick(b));
                wakeParked(b);
            }
            teardown(b);
            return body.result;
        } finally {
            boundaries.pop();
        }
    }

    /** The body is done. Leftover runnable tasks finish; tasks still parked are
     *  cancelled and finish; a task that keeps re-parking is an error after a bound. */
    private void teardown(Boundary b) {
        for (int round = 0; round < TEARDOWN_ROUNDS; round++) {
            drain(b);
            if (b.parked.isEmpty()) return;
            Object cancelled = parkCancelled(scopeTeardown());
            for (Task t : b.parked) {
                t.deliver = cancelled;
                t.state = RUNNABLE;
                b.ready.addLast(t);
            }
            b.parked.clear();
        }
        drain(b);
        if (!b.parked.isEmpty()) throw new MezzeGuestException(
            "scope teardown: " + b.parked.size() + " task(s) kept parking after being cancelled "
            + TEARDOWN_ROUNDS + " times — a task's cleanup must not wait on the scope it is leaving");
    }

    /** Run everything runnable or wakeable; stop when only unwakeable parks remain. */
    private void drain(Boundary b) {
        while (true) {
            if (!b.ready.isEmpty()) { run(b, pick(b)); wakeParked(b); continue; }
            if (!wakeParked(b)) return;
        }
    }

    private static MezzeFunction driveOf(MezzeRecord handler) {
        if (handler == null) return null;
        Object d = handler.getField(com.mezze.language.ast.ScopedEffectConstants.DRIVE);
        return d instanceof MezzeFunction f ? f : null;
    }

    private Task pick(Boundary b) {
        if (b.drive == null) return b.ready.pollFirst();
        Object chosen = callWith(b.drive, MezzeRecord.fromFields(
            new String[]{com.mezze.language.ast.ScopedEffectConstants.DRIVE_RUNNABLE}, new Object[]{b.runQueue}));
        if (!(chosen instanceof Task t) || !b.ready.remove(t)) {
            throw new MezzeGuestException(
                "drive must return one of the runnable tasks (runnable.first {}, .last {}, .at { i }); got "
                + (chosen == null ? "null" : chosen.toString()));
        }
        return t;
    }

    private void run(Boundary b, Task t) {
        t.state = RUNNING;
        Object cur;
        try {
            if (t.thunk != null) {
                MezzeFunction f = t.thunk;
                t.thunk = null;
                cur = callWith(f, MezzeRecord.empty());
            } else {
                cur = t.leaf.continueWith(t.deliver);
                t.leaf = null; t.deliver = null;
            }
        } catch (Throwable e) {
            if (t.promise == null) throw e;          // the body's failure is the perform's
            t.state = DONE;
            t.promise.fail(e);
            return;
        }
        absorb(b, t, cur);
    }

    /** Walk a run's result: descend nested continuations to the leaf, park there, or
     *  hand completed values outward until the task is done. */
    private void absorb(Boundary b, Task t, Object cur) {
        while (true) {
            if (cur instanceof ContinuationResult cr) {
                Object yielded = cr.getResult();
                if (yielded instanceof ContinuationResult nested) {
                    t.frames.push(cr);
                    cur = nested;
                    continue;
                }
                if (yielded instanceof ParkRequest pr) {
                    t.leaf = cr;
                    t.parkedOn = pr;
                    t.epoch++;
                    t.state = PARKED;
                    b.parked.add(t);
                    return;
                }
                throw new IllegalStateException(
                    "a root yielded something that is neither a nested continuation nor a park request: "
                    + (yielded == null ? "null" : yielded.getClass().getName()));
            }
            if (t.frames.isEmpty()) {
                t.state = DONE;
                t.result = cur;
                if (t.promise != null) t.promise.complete(cur);
                return;
            }
            try {
                cur = t.frames.pop().continueWith(cur);
            } catch (Throwable e) {
                if (t.promise == null) throw e;
                t.state = DONE;
                t.promise.fail(e);
                return;
            }
        }
    }

    /** Deliver to every parked task whose condition now holds. Returns whether any did. */
    private boolean wakeParked(Boundary b) {
        boolean any = false;
        for (int i = 0; i < b.parked.size(); ) {
            Task t = b.parked.get(i);
            int which = readyIndex(t.parkedOn);
            if (which >= 0) {
                b.parked.remove(i);
                t.deliver = parkReady(which);
                t.state = RUNNABLE;
                b.ready.addLast(t);
                any = true;
            } else {
                i++;
            }
        }
        return any;
    }

    /** Index of the first satisfied condition of a park request, or -1. */
    private int readyIndex(ParkRequest pr) {
        if (!(pr.on instanceof MezzeVariant on)) throw new IllegalStateException("ParkOn is not a variant: " + pr.on);
        Object[] items = seqItems(on.getPayloadFieldByName(
            on.getConstructorName().equals("OnPromises") ? "ids" : "fs"));
        if (on.getConstructorName().equals("OnPromises")) {
            for (int i = 0; i < items.length; i++) {
                MezzePromise p = promisesById.get(asLong(items[i]));
                if (p == null) throw new MezzeGuestException("cont.park: unknown PromiseId " + items[i]);
                if (p.isDone()) return i;
            }
            return -1;
        }
        for (int i = 0; i < items.length; i++) {
            Object r = callWith((MezzeFunction) items[i], MezzeRecord.empty());
            if (r instanceof Boolean b && b) return i;
        }
        return -1;
    }

    /** Nothing runnable, nothing woke. A promise settling on another thread can still
     *  wake someone — wait for it. Otherwise this is a deadlock, and it is an error. */
    private void blockOrDeadlock(Boundary b) {
        boolean external = false;
        for (Task t : b.parked) {
            if (t.parkedOn.on instanceof MezzeVariant on && on.getConstructorName().equals("OnPromises")) {
                for (Object id : seqItems(on.getPayloadFieldByName("ids"))) {
                    MezzePromise p = promisesById.get(asLong(id));
                    if (p != null && !p.isDone() && !ownPromises.containsKey(p)) external = true;
                }
            }
        }
        if (external) {
            java.util.concurrent.locks.LockSupport.parkNanos(50_000L);
            return;
        }
        StringBuilder sb = new StringBuilder("deadlock: ").append(b.parked.size())
            .append(b.parked.size() == 1 ? " task is parked" : " tasks are parked")
            .append(" and nothing can wake ")
            .append(b.parked.size() == 1 ? "it" : "them").append(" —");
        for (Task t : b.parked) sb.append(' ').append(t).append(" on ").append(describe(t.parkedOn));
        throw new MezzeGuestException(sb.toString());
    }

    private static String describe(ParkRequest pr) {
        if (pr.on instanceof MezzeVariant on) {
            String c = on.getConstructorName();
            Object[] items = seqItems(on.getPayloadFieldByName(c.equals("OnPromises") ? "ids" : "fs"));
            return c + "(" + items.length + ")";
        }
        return String.valueOf(pr.on);
    }

    // ---------------------------------------------------------------- helpers

    private static Object callWith(MezzeFunction fn, MezzeRecord arg) {
        Object[] cv = fn.getClosureValues();
        Object[] args = new Object[cv.length + 1];
        System.arraycopy(cv, 0, args, 0, cv.length);
        args[cv.length] = arg;
        return IndirectCallNode.getUncached().call(fn.getCallTarget(), args);
    }

    private static Object[] seqItems(Object v) {
        if (v instanceof com.mezze.datastructures.seq.MezzeSeq seq) return seq.toArray();
        if (v instanceof Object[] arr) return arr;
        throw new IllegalStateException("expected a sequence, got " + (v == null ? "null" : v.getClass().getName()));
    }

    private static long asLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i;
        throw new IllegalStateException("expected an Int id, got " + o);
    }

    static Object parkCancelled(Object reason) {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        int tag = registry.getOrCreateTag("ParkCancelled");
        var rec = MezzeRecord.fromFields(new String[]{"reason"}, new Object[]{reason});
        return MezzeVariant.singlePayload(registry, tag, rec);
    }

    static Object scopeTeardown() {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        return MezzeVariant.noPayload(registry, registry.getOrCreateTag("CancelScopeTeardown"));
    }

    static Object parkReady(long which) {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        int tag = registry.getOrCreateTag("ParkReady");
        var rec = MezzeRecord.fromFields(new String[]{"which"}, new Object[]{which});
        return MezzeVariant.singlePayload(registry, tag, rec);
    }
}
