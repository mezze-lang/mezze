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
        /** Frames waiting for a value: a {@link ContinuationResult} (BDSL) or a
         *  {@link NativeSuspension} (a native loop, C4). */
        final ArrayDeque<Object> frames = new ArrayDeque<>();
        ContinuationResult leaf;
        ParkRequest parkedOn;
        long epoch;
        Object deliver;
        int state = RUNNABLE;
        final MezzePromise promise;                // null for a boundary body
        Object result;
        Boundary owner;                            // the boundary that drives this task
        HandlerChainEntry[] chain;                 // Phase B: the handlers in effect where it was spawned
        MezzeFunction spawnedFn;                   // for diagnostics: where the task's function was written (resolved lazily)

        Task(long id, MezzeFunction thunk, MezzePromise promise) {
            this.id = id; this.thunk = thunk; this.promise = promise;
        }

        @Override public String toString() {
            String site = spawnedFn != null ? siteOf(spawnedFn) : null;
            return site != null ? "the task spawned at " + site : (promise == null ? "the perform body" : "Task#" + id);
        }
    }

    /** `file:line` of a function's root, through a trampoline or stub; null when unknown. */
    @TruffleBoundary
    static String siteOf(MezzeFunction f) {
        com.oracle.truffle.api.CallTarget ct = f.getCallTarget();
        for (int hop = 0; hop < 4 && ct instanceof com.oracle.truffle.api.RootCallTarget rct; hop++) {
            com.oracle.truffle.api.nodes.RootNode root = rct.getRootNode();
            com.oracle.truffle.api.source.SourceSection sec = root.getSourceSection();
            if (sec != null && sec.getSource() != null) return sec.getSource().getName() + ":" + sec.getStartLine();
            if (root instanceof com.mezze.interpreter.bytecode.TrampolineRootNode t) { ct = t.peekInnerTarget(); continue; }
            if (root instanceof com.mezze.interpreter.bytecode.LazyStubRootNode st) { ct = st.getInflatedTarget(); continue; }
            break;
        }
        return null;
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
    private long nextPromiseId = 1;
    /** BUG 77: the size of the promise/task bookkeeping when the last boundary closed — a
     *  test's view that completed work is forgotten (it grew without bound: every lookup,
     *  resize and GC scan paid for every promise ever created; spawn+await went 1.4 → 2.8 us
     *  per element from 20k to 200k elements). */
    public static volatile int LAST_BOOKKEEPING = -1;
    private final LongObjectMap<MezzePromise> promisesById = new LongObjectMap<>();   // primitive keys: no Long boxing per lookup
    private final IdentityHashMap<MezzePromise, Boolean> ownPromises = new IdentityHashMap<>();
    /** Stale wakes — aimed at an epoch the task has left. Countable, never silent. */
    public long staleWakes;
    /** Every `cont.park` creates one request; every request a boundary sees is
     *  absorbed. A gap after a run means a continuation was SWALLOWED on its way
     *  to the boundary — the one thing that must never be silent. */
    private long parksRequested, parksAbsorbed;

    /** `cont.park` was evaluated. Outside any boundary there is nothing that could
     *  ever resume it, so that is an error here and now. */
    @TruffleBoundary
    public void notePark() {
        if (boundaries.isEmpty()) throw new MezzeGuestException(
            "cont.park outside a scheduler boundary: the perform that installed this performer is not "
            + "one the runtime can suspend — the module top level, a comptime block, or a frame reached "
            + "through a native or a polyglot call. Move the perform into a function (`main`) and keep "
            + "parking performers out of native callbacks.");
        parksRequested++;
    }

    private void checkNoneSwallowed(Task t) { checkNoneSwallowed(t, null); }

    private void checkNoneSwallowed(Task t, Throwable cause) {
        if (parksRequested == parksAbsorbed) return;
        long lost = parksRequested - parksAbsorbed;
        parksAbsorbed = parksRequested;   // report once
        // A native that caught its callback's park itself (OrderCallbacks) already said
        // exactly what happened; its guest error outranks this generic one.
        if (cause instanceof MezzeGuestException) return;
        if (cause != null) throw new MezzeGuestException(droppedParkMessage(lost, t), cause);
        throw new MezzeGuestException(droppedParkMessage(lost, t));
    }

    private static String droppedParkMessage(long lost, Task t) {
        return "a park was dropped on its way to the perform boundary (" + lost + " in " + t + "): the "
            + "suspension crossed a frame the runtime cannot suspend through. The collection HOFs resume "
            + "(seq, iter, list: map, fold, filter, for_each, find, …); what does not: a comparator or key "
            + "function (sort_by, min_by, dedup — reported by name when reached directly), `iter::sum` / "
            + "`count_all` over a parking chain, a polyglot call, and a callback that crossed to another thread. "
            + "Compute the parking part first (`map`), then hand plain values to the native.";
    }

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
        t.owner = b;
        t.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        t.spawnedFn = f;                           // siteOf() walks call targets — only on the error path
        b.ready.addLast(t);
        // `Async.cancel` on the task's promise: a PARKED task is woken with
        // `ParkCancelled { reason }` so its performer's arm decides what cancellation
        // means; a running or runnable one just finds its promise settled.
        p.setCancelHook(reason -> cancelParked(t, reason));
        return p;
    }

    private void cancelParked(Task t, Object reason) {
        if (t.state != PARKED || t.owner == null) return;
        t.owner.parked.remove(t);
        t.deliver = parkCancelled(reason);
        t.state = RUNNABLE;
        t.owner.ready.addLast(t);
    }

    @TruffleBoundary
    public long promiseId(MezzePromise p) {
        Long id = promiseIds.get(p);
        if (id == null) {
            id = nextPromiseId++;
            if (p.isDone()) return id;                 // BUG 77: a settled promise is never registered —
            promiseIds.put(p, id);                     // an id the machine does not know is a settled one
            promisesById.put(id, p);
        }
        return id;
    }

    /** BUG 77: completed work leaves the maps. */
    private void forget(MezzePromise p) {
        if (p == null) return;
        ownPromises.remove(p);
        Long id = promiseIds.remove(p);
        if (id != null) promisesById.remove(id);
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
        HandlerChainRegistry.enterParkScope();
        try {
            Task body = new Task(nextTaskId++, null, null);
            body.owner = b;
            body.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
            Object[] prevChain = HandlerChainRegistry.installTaskChain(body.chain);
            try {
                Object first;
                try {
                    first = callWith(bodyThunk, MezzeRecord.empty());
                } catch (Throwable e) {
                    checkNoneSwallowed(body, e);   // the body's own first run can drop a park too
                    throw e;
                }
                absorb(b, body, first);
            } finally {
                HandlerChainRegistry.restoreTaskChain(prevChain);
            }
            checkNoneSwallowed(body);
            while (body.state != DONE) {
                if (b.ready.isEmpty()) {
                    if (!wakeParked(b)) blockOrDeadlock(b);
                    continue;
                }
                Task t = pick(b);
                run(b, t);
                checkNoneSwallowed(t);
                wakeParked(b);
            }
            teardown(b);
            return body.result;
        } finally {
            HandlerChainRegistry.exitParkScope();
            boundaries.pop();
            LAST_BOOKKEEPING = promiseIds.size() + ownPromises.size() + promisesById.size();
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
            if (!b.ready.isEmpty()) { Task t = pick(b); run(b, t); checkNoneSwallowed(t); wakeParked(b); continue; }
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
        Object[] prevChain = HandlerChainRegistry.installTaskChain(t.chain);   // Phase B
        try {
            runUnderChain(b, t);
        } finally {
            HandlerChainRegistry.restoreTaskChain(prevChain);
        }
    }

    private void runUnderChain(Boundary b, Task t) {
        t.state = RUNNING;
        Object cur;
        try {
            if (t.thunk != null) {
                MezzeFunction f = t.thunk;
                t.thunk = null;
                cur = callWith(f, MezzeRecord.empty());
            } else {
                ev("resume-leaf", t.leaf, t.deliver);
                cur = continueBounced(t.leaf, t.deliver);
                t.leaf = null; t.deliver = null;
            }
        } catch (Throwable e) {
            // A park created during this run that never reached us is the real cause
            // of whatever broke next — typically a native treating the callback's
            // continuation as its value (measured: ClassCastException ContinuationResult
            // → Long). Say that, not the host exception.
            checkNoneSwallowed(t, e);
            if (t.promise == null) throw e;          // the body's failure is the perform's
            t.state = DONE;
            t.promise.fail(e);
            forget(t.promise);
            return;
        }
        absorb(b, t, cur);
    }

    /** Walk a run's result: descend nested continuations to the leaf, park there, or
     *  hand completed values outward until the task is done. */
    private void absorb(Boundary b, Task t, Object cur) {
        while (true) {
            if (cur instanceof NativeSuspension ns) {      // C4: a native loop waits for its callback
                ev("absorb-ns", ns, ns.inner);
                t.frames.push(ns);
                cur = ns.inner;
                continue;
            }
            if (cur instanceof ContinuationResult cr) {
                Object yielded = cr.getResult();
                ev("absorb-cr", cr, yielded);
                if (yielded instanceof ContinuationResult || yielded instanceof NativeSuspension) {
                    t.frames.push(cr);
                    cur = yielded;
                    continue;
                }
                if (yielded instanceof ParkRequest pr) {
                    parksAbsorbed++;
                    t.leaf = cr;
                    t.parkedOn = pr;
                    t.epoch++;
                    t.state = PARKED;
                    b.parked.add(t);
                    return;
                }
                throw new IllegalStateException(
                    "a root yielded something that is neither a nested continuation nor a park request: "
                    + describeYield(cr, yielded) + " (task " + t + ", " + t.frames.size() + " frames above)" + eventsDump());
            }
            if (t.frames.isEmpty()) {
                t.state = DONE;
                t.result = cur;
                if (t.promise != null) { t.promise.complete(cur); forget(t.promise); }
                return;
            }
            try {
                cur = resumeFrame(t.frames.pop(), cur);
            } catch (Throwable e) {
                checkNoneSwallowed(t, e);
                if (t.promise == null) throw e;
                t.state = DONE;
                t.promise.fail(e);
                forget(t.promise);
                return;
            }
        }
    }

    @TruffleBoundary
    private static String describeYield(ContinuationResult cr, Object yielded) {
        String root;
        try {
            var loc = cr.getBytecodeLocation();
            var sec = loc.getSourceLocation();
            root = ((com.oracle.truffle.api.nodes.RootNode) cr.getContinuationRootNode().getSourceRootNode()).getName()
                + " @ bci " + loc.getBytecodeIndex()
                + (sec != null ? " " + sec.getSource().getName() + ":" + sec.getStartLine() + ":" + sec.getStartColumn() + " `" + sec.getCharacters().toString().replace('\n', ' ').substring(0, Math.min(60, sec.getCharacters().length())) + "`" : "");
        } catch (Throwable e) { root = "?"; }
        String val = yielded == null ? "null" : yielded.getClass().getSimpleName();
        if (yielded instanceof MezzeVariant v) val += " " + v.getConstructorName();
        return val + " yielded by root `" + root + "`";
    }

    // ---- PROBE (MEZZE_PROBE_MACHINE=1): the last events, dumped with a machine error ----
    static final boolean PROBE = System.getenv("MEZZE_PROBE_MACHINE") != null;
    private static final java.util.ArrayDeque<String> EVENTS = new java.util.ArrayDeque<>();
    @TruffleBoundary
    static void ev(String kind, Object frame, Object value) {
        if (!PROBE) return;
        String f = frame instanceof ContinuationResult cr ? describeYield(cr, null).replace("null yielded by root ", "") + " id=" + System.identityHashCode(cr)
                 : frame instanceof NativeSuspension ? "NativeSuspension id=" + System.identityHashCode(frame) : String.valueOf(frame);
        String v = value == null ? "null" : value.getClass().getSimpleName() + (value instanceof MezzeVariant mv ? " " + mv.getConstructorName() : "") + " id=" + System.identityHashCode(value);
        synchronized (EVENTS) { EVENTS.addLast(kind + " " + f + " <- " + v); while (EVENTS.size() > 60) EVENTS.removeFirst(); }
    }
    @TruffleBoundary
    static String eventsDump() {
        if (!PROBE) return "";
        synchronized (EVENTS) { return "\n--- last machine events ---\n" + String.join("\n", EVENTS); }
    }

    private static Object resumeFrame(Object frame, Object value) {
        ev("resume", frame, value);
        if (frame instanceof ContinuationResult cr) return continueBounced(cr, value);
        return ((NativeSuspension) frame).resume.with(value);
    }

    /** Deliver to every parked task whose condition now holds. Returns whether any did. */
    private boolean wakeParked(Boundary b) {
        boolean any = false;
        for (int i = 0; i < b.parked.size(); ) {
            Task t = b.parked.get(i);
            int which = readyIndexUnderChain(t);
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
        int tag = on.getTag();
        if (tag == tagImmediately()) return 0;          // yield_now: ready on the next turn, nothing to evaluate
        boolean onPromises = tag == tagOnPromises();
        Object payload = on.getPayloadFieldByName(onPromises ? "ids" : "fs");
        int n = seqSize(payload);
        if (onPromises) {
            for (int i = 0; i < n; i++) {
                MezzePromise p = promisesById.get(asLong(seqAt(payload, i)));
                if (p == null) return i;                 // BUG 77: evicted ⇒ settled (ids are minted here only)
                if (p.isDone()) { forget(p); return i; }
            }
            return -1;
        }
        for (int i = 0; i < n; i++) {
            Object r = callWith((MezzeFunction) seqAt(payload, i), MezzeRecord.empty());
            if (r instanceof Boolean b && b) return i;
        }
        return -1;
    }

    private int readyIndexUnderChain(Task t) {
        Object[] prev = HandlerChainRegistry.installTaskChain(t.chain);
        try { return readyIndex(t.parkedOn); } finally { HandlerChainRegistry.restoreTaskChain(prev); }
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
            if (c.equals("Immediately")) return c;
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
        try {
            return IndirectCallNode.getUncached().call(fn.getCallTarget(), args);
        } catch (com.mezze.interpreter.nodes.function.TailCallException tce) {
            return bounce(tce);
        }
    }

    /**
     * BUG 74: the machine is the CALLER of every root it resumes (a continuation
     * root has no trampoline above it) and of the thunks and predicates it invokes
     * uncached. A tail call the root cannot fuse into a loop surfaces as a
     * {@link com.mezze.interpreter.nodes.function.TailCallException}, which a
     * trampoline would catch and bounce; here nothing did, and the control-flow
     * exception reached the user as a host error. So the machine bounces, exactly
     * as {@code TrampolineRootNode} does: call the target with the arguments the
     * tail-call site shaped, until a value (or a suspension) comes back.
     */
    private static Object bounce(com.mezze.interpreter.nodes.function.TailCallException tce) {
        com.oracle.truffle.api.CallTarget target = tce.getCallTarget();
        Object[] args = tce.getArguments();
        while (true) {
            try {
                return IndirectCallNode.getUncached().call(target, args);
            } catch (com.mezze.interpreter.nodes.function.TailCallException again) {
                target = again.getCallTarget();
                args = again.getArguments();
            }
        }
    }

    private static Object continueBounced(ContinuationResult cr, Object value) {
        try {
            return cr.continueWith(value);
        } catch (com.mezze.interpreter.nodes.function.TailCallException tce) {
            return bounce(tce);
        }
    }

    private static int seqSize(Object v) {
        if (v instanceof com.mezze.datastructures.seq.MezzeSeq seq) return seq.size();
        if (v instanceof Object[] arr) return arr.length;
        throw new IllegalStateException("expected a sequence, got " + (v == null ? "null" : v.getClass().getName()));
    }
    private static Object seqAt(Object v, int i) {
        if (v instanceof com.mezze.datastructures.seq.MezzeSeq seq) return seq.get(i);
        return ((Object[]) v)[i];
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
        int tag = TAG_CANCELLED;
        if (tag < 0) TAG_CANCELLED = tag = registry.getOrCreateTag("ParkCancelled");
        var rec = MezzeRecord.fromFields(REASON, new Object[]{reason});
        return MezzeVariant.singlePayload(registry, tag, rec);
    }

    static Object scopeTeardown() {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        return MezzeVariant.noPayload(registry, registry.getOrCreateTag("CancelScopeTeardown"));
    }

    private static final String[] WHICH = {"which"}, REASON = {"reason"};
    // ParkOn constructor tags, resolved once (the tag registry is global and append-only).
    private static volatile int TAG_IMMEDIATELY = -1, TAG_ON_PROMISES = -1;
    private static int tagImmediately() { int t = TAG_IMMEDIATELY; if (t < 0) TAG_IMMEDIATELY = t = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Immediately"); return t; }
    private static int tagOnPromises() { int t = TAG_ON_PROMISES; if (t < 0) TAG_ON_PROMISES = t = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("OnPromises"); return t; }
    private static volatile int TAG_READY = -1, TAG_CANCELLED = -1;
    private static volatile Object READY_0;
    static Object parkReady(long which) {
        if (which == 0) {                              // the common outcome, shared (values are immutable)
            Object r = READY_0;
            if (r == null) READY_0 = r = parkReadyFresh(0);
            return r;
        }
        return parkReadyFresh(which);
    }
    private static Object parkReadyFresh(long which) {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        int tag = TAG_READY;
        if (tag < 0) TAG_READY = tag = registry.getOrCreateTag("ParkReady");   // the tag registry is global
        var rec = MezzeRecord.fromFields(WHICH, new Object[]{which});
        return MezzeVariant.singlePayload(registry, tag, rec);
    }
}
