package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.concurrent.locks.LockSupport;

/**
 * The cooperative task machine: one per {@code MezzeContext}, driving the tasks of every
 * cont-capable {@code perform} (a {@link Boundary}) — the body task and the tasks spawned
 * under it.
 *
 * <h2>A suspended task is a parked carrier</h2>
 *
 * Each task runs on its own CARRIER, a virtual thread entered into the context through
 * {@code Env.newTruffleThreadBuilder}. Exactly one carrier runs at a time: the DRIVER (the
 * thread that entered the perform) hands a task the baton, parks until the task hands it
 * back — because it parked ({@code cont.park}) or finished — and then picks the next
 * runnable task. {@code cont.park} is therefore a plain blocking call on the carrier, with
 * no continuation capture: a park inside a comparator, inside a Java callback, inside a
 * polyglot frame or anywhere else just blocks that carrier, and the machine keeps running
 * the others. Nothing has to propagate, so nothing can fail to propagate, and the natives
 * run their ordinary loops. Sequential semantics are kept — no two tasks ever run at once
 * — so a scheduler written in Mezze stays deterministic.
 *
 * <p>The earlier design suspended tasks as chains of Bytecode-DSL continuations. It worked,
 * and it hit a floor: boxing elimination and continuation frames disagree about long slots,
 * so the interpreter's compiled helpers were thrown away on every slow path and a park cost
 * 386 ns against 98 with boxing elimination compiled out — and boxing elimination is a 3.8x
 * win on loops that must stay. A baton handoff between virtual threads costs ~0.3 us and has
 * none of those interactions. The Mezze-level design is unchanged: {@code cont.park},
 * performers written in Mezze, {@code drive}, deadlines, cancellation, teardown.
 *
 * <p>Carriers are pooled: a finished task's carrier waits for its next assignment, so a
 * spawn after warm-up does not create a thread. The pool is drained when the context is
 * disposed ({@link #shutdown}).
 */
public final class TaskMachine {

    static final int RUNNABLE = 0, RUNNING = 1, PARKED = 2, DONE = 3, FAILED = 4;
    private static final int TURN_DRIVER = 0, TURN_TASK = 1;
    /**
     * A baton handoff between two virtual threads through park/unpark costs ~400 ns on its own
     * and, measured (JFR on contloop k2), spends 45% of the kernel in ForkJoinPool deactivating
     * and re-signalling workers. A handoff that completes within a few microseconds is caught
     * by SPINNING on the turn flag first — both carriers stay mounted, no scheduler is involved,
     * and the cost is one cache-line transfer. The bound keeps a long wait (I/O) cheap: at most
     * SPIN_NANOS of CPU, then a real park. On one scheduler worker a spin would only delay the
     * other side, so it is off there.
     */
    // Not a static final: `com.mezze.interpreter` is initialised at native-image BUILD time, and
    // this must see the machine the binary RUNS on (its scheduler workers), not the build host.
    private final long spinNanos = spinNanos();
    private static long spinNanos() {
        String env = System.getenv("MEZZE_COOP_SPIN_NANOS");
        if (env != null) return Long.parseLong(env);
        String p = System.getProperty("jdk.virtualThreadScheduler.parallelism");
        int workers = p != null ? Integer.parseInt(p) : Runtime.getRuntime().availableProcessors();
        return workers >= 2 ? 5_000L : 0L;
    }
    /** Spin, then park, until {@code t.turn} is ours. */
    private void awaitTurn(Task t, int wanted, Object blocker) {
        long deadline = 0;
        while (t.turn != wanted) {
            if (closing) throw new MachineClosed();
            if (spinNanos > 0) {
                long now = System.nanoTime();
                if (deadline == 0) deadline = now + spinNanos;
                if (now < deadline) { Thread.onSpinWait(); continue; }
            }
            LockSupport.park(blocker);
        }
    }
    private static final int TEARDOWN_ROUNDS = 8;

    /** Opaque to Mezze: a scheduled task. `Task` handles reach `drive` through {@link RunQueue}. */
    public static final class Task implements TruffleObject {
        public final long id;
        MezzeFunction thunk;
        final MezzePromise promise;                // null for a boundary body
        volatile int state = RUNNABLE;
        /** Who holds the baton: the driver or this task's carrier. The ONLY field the two
         *  threads race on; every other field is written before the flip and read after it. */
        volatile int turn = TURN_DRIVER;
        ParkRequest parkedOn;
        Object deliver;
        Object result;
        Throwable failure;
        Boundary owner;
        HandlerChainEntry[] chain;                 // the handlers in effect where it was spawned
        Object scope;                              // the performer scope variable where it was spawned (per thread, like the chain)
        MezzeFunction spawnedFn;                   // for diagnostics, resolved lazily
        Carrier carrier;
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
        private final Boundary b;
        RunQueue(Boundary b) { this.b = b; }
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
        /** The thread that entered the perform. It parks once, until the boundary FINISHES; it
         *  never drives — the carrier giving up the baton runs the scheduler step, so every
         *  handoff is virtual-to-virtual (a platform park/unpark costs 3.5 us; virtual 0.3). */
        final Thread entryThread;
        Task body;
        volatile boolean finished;
        Throwable failure;
        int teardownRounds;
        /** Set once the boundary has failed with tasks still parked: each is woken with
         *  {@link #ABANDON} and unwinds its carrier, one at a time, before the boundary finishes. */
        boolean abandoning;
        Boundary(MezzeFunction drive) { this.drive = drive; this.runQueue = new RunQueue(this); this.entryThread = Thread.currentThread(); }
    }

    /** A pooled virtual thread that runs one task at a time. */
    private final class Carrier implements Runnable {
        final Thread thread;
        volatile Task assigned;
        Carrier(Env env) {
            thread = env.newTruffleThreadBuilder(this).virtual(true).build();
            thread.setName("mezze-coop-" + (++carriersMinted));
            allCarriers.add(this);
        }
        @Override public void run() {
            while (true) {
                Task t;
                long deadline = 0;
                while ((t = assigned) == null) {
                    if (closing) return;
                    if (spinNanos > 0) {               // the next fresh task usually arrives within a microsecond
                        long now = System.nanoTime();
                        if (deadline == 0) deadline = now + spinNanos;
                        if (now < deadline) { Thread.onSpinWait(); continue; }
                    }
                    LockSupport.park(this);
                }
                assigned = null;
                if (t == POISON || closing) return;
                try {
                    execute(t);
                } catch (MachineClosed closed) {
                    return;
                } catch (Throwable e) {
                    // The machine's own step failed (not the task's thunk — execute catches that):
                    // report it as the boundary's failure so the entry thread wakes with it.
                    Boundary b = t.owner;
                    b.failure = e;
                    finish(b);
                }
            }
        }
    }

    private final ArrayDeque<Boundary> boundaries = new ArrayDeque<>();
    private final ArrayDeque<Carrier> idle = new ArrayDeque<>();
    private final ArrayList<Carrier> allCarriers = new ArrayList<>();
    private int carriersMinted;
    private volatile boolean closing;
    private Env env;
    private static final Task POISON = new Task(-1, null, null);
    private static final ThreadLocal<Task> CURRENT = new ThreadLocal<>();

    private long nextTaskId = 1;
    private final IdentityHashMap<MezzePromise, Long> promiseIds = new IdentityHashMap<>();
    private long nextPromiseId = 1;
    /** BUG 77: the size of the promise bookkeeping when the last boundary closed (a test's view). */
    public static volatile int LAST_BOOKKEEPING = -1;
    private final LongObjectMap<MezzePromise> promisesById = new LongObjectMap<>();
    private final IdentityHashMap<MezzePromise, Boolean> ownPromises = new IdentityHashMap<>();

    // ---------------------------------------------------------------- spawn / promises

    public MezzePromise spawn(MezzeFunction f) {
        Task cur = CURRENT.get();
        Boundary b = cur != null ? cur.owner : boundaries.peek();
        if (b == null) throw new MezzeGuestException(
            "spawn_task outside a scheduler: a task needs a cont-capable `perform` (one whose "
            + "performer mentions `cont`) to be driven by");
        MezzePromise p = new MezzePromise(null, null);
        ownPromises.put(p, Boolean.TRUE);
        Task t = new Task(nextTaskId++, f, p);
        t.owner = b;
        t.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        t.scope = HandlerChainRegistry.lookupScopeVar();
        t.spawnedFn = f;
        b.ready.addLast(t);
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

    public long promiseId(MezzePromise p) {
        Long id = promiseIds.get(p);
        if (id == null) {
            id = nextPromiseId++;
            if (p.isDone()) return id;                 // a settled promise is never registered —
            promiseIds.put(p, id);                     // an id the machine does not know is a settled one
            promisesById.put(id, p);
        }
        return id;
    }

    /** Completed work leaves the maps (BUG 77). */
    private void forget(MezzePromise p) {
        if (p == null) return;
        ownPromises.remove(p);
        Long id = promiseIds.remove(p);
        if (id != null) promisesById.remove(id);
    }

    // ---------------------------------------------------------------- the boundary

    /**
     * Run a cont-capable perform's body as this boundary's body task, drive every task it
     * spawns until the body is done, tear the scope down, and return the body's result (or
     * rethrow its failure — an `abort` included — on the driver, where the handle is).
     */
    @TruffleBoundary
    public Object runBoundary(MezzeFunction bodyThunk, MezzeRecord handler) {
        Boundary b = new Boundary(driveOf(handler));
        boundaries.push(b);
        try {
            Task body = new Task(nextTaskId++, bodyThunk, null);
            body.owner = b;
            body.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
            body.scope = HandlerChainRegistry.lookupScopeVar();
            b.body = body;
            give(body);                                // the body's carrier takes it from here
            while (!b.finished) {
                if (closing) throw new MachineClosed();
                LockSupport.park(b);
            }
            if (b.failure != null) sneakyThrow(b.failure);
            return body.result;
        } finally {
            boundaries.pop();
            LAST_BOOKKEEPING = promiseIds.size() + ownPromises.size() + promisesById.size();
            // The pool lives as long as some boundary is open. Context close waits for every
            // polyglot thread, so idle carriers must not outlive the last perform.
            if (boundaries.isEmpty()) releaseIdleCarriers();
        }
    }

    private void releaseIdleCarriers() {
        Carrier c;
        while ((c = idle.pollFirst()) != null) {
            allCarriers.remove(c);
            c.assigned = POISON;
            LockSupport.unpark(c.thread);
        }
    }

    /**
     * The scheduler step, run by the carrier that is giving up the baton (it parked or its
     * task ended). Hands the baton to the next runnable task, or wakes parked ones, or — once
     * the body has ended — cancels what is still parked for a bounded number of rounds and
     * finishes the boundary, or reports a deadlock. Returns the task that received the
     * baton, or null when the boundary finished.
     */
    private Task dispatch(Boundary b) {
        while (true) {
            wakeParked(b);                             // first: a task that just yielded is back in the ready set BEFORE drive picks
            if (!b.ready.isEmpty()) {
                Task next = pick(b);
                give(next);
                return next;
            }
            boolean bodyEnded = b.body.state == DONE || b.body.state == FAILED;
            if (bodyEnded) {
                if (b.parked.isEmpty()) { finish(b); return null; }
                if (!b.abandoning && b.teardownRounds++ < TEARDOWN_ROUNDS) {
                    Object cancelled = parkCancelled(scopeTeardown());
                    for (Task t : b.parked) { t.deliver = cancelled; t.state = RUNNABLE; b.ready.addLast(t); }
                    b.parked.clear();
                    continue;
                }
                if (b.failure == null) b.failure = new MezzeGuestException(
                    "scope teardown: " + b.parked.size() + " task(s) kept parking after being cancelled "
                    + TEARDOWN_ROUNDS + " times — a task's cleanup must not wait on the scope it is leaving");
                abandonParked(b);
                continue;
            }
            try {
                blockOrDeadlock(b);                    // waits on an external promise, or throws
            } catch (Throwable deadlock) {
                if (b.failure == null) b.failure = deadlock;
                abandonParked(b);                      // the body is parked too: it unwinds with the rest
                continue;
            }
        }
    }

    /** The boundary has failed. Every parked task (the body included) is woken with
     *  {@link #ABANDON}: its park throws, its carrier unwinds and returns to the pool. They
     *  run one at a time, like any other turn, so nothing executes concurrently even here.
     *  A carrier left parked would outlive the perform — and context close waits for it. */
    private void abandonParked(Boundary b) {
        b.abandoning = true;
        for (Task t : b.parked) { t.deliver = ABANDON; t.state = RUNNABLE; b.ready.addLast(t); }
        b.parked.clear();
    }

    private static final Object ABANDON = new Object();

    /** Unwinds an abandoned task's carrier. Not a guest error: nothing in Mezze observes it. */
    static final class BoundaryAbandoned extends RuntimeException {
        BoundaryAbandoned() { super("the perform this task belongs to has failed", null, false, false); }
    }

    private void finish(Boundary b) {
        b.finished = true;
        LockSupport.unpark(b.entryThread);
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

    // ---------------------------------------------------------------- the baton

    /** Hand {@code t} the baton: on its carrier (assigned from the pool on its first run). The
     *  caller does not wait; the task's carrier runs until it parks or ends and then dispatches. */
    private void give(Task t) {
        t.state = RUNNING;
        Carrier c = t.carrier;
        if (c == null) {
            c = idle.pollFirst();
            if (c == null) { if (env == null) env = com.mezze.interpreter.runtime.MezzeContext.get(null).getEnv(); c = new Carrier(env); c.thread.start(); }
            t.carrier = c;
            t.turn = TURN_TASK;
            c.assigned = t;
            LockSupport.unpark(c.thread);
        } else {
            t.turn = TURN_TASK;
            LockSupport.unpark(c.thread);
        }
    }

    /** Carrier side: run the task's thunk to completion under the task's handler chain. */
    private void execute(Task t) {
        CURRENT.set(t);
        Object[] prev = HandlerChainRegistry.installTaskChain(t.chain);
        Object prevScope = HandlerChainRegistry.installScopeVar(t.scope);
        try {
            Object r = callWith(t.thunk, MezzeRecord.empty());
            t.result = r;
            t.state = DONE;
        } catch (MachineClosed closed) {
            throw closed;
        } catch (Throwable e) {
            t.failure = e;
            t.state = FAILED;
        } finally {
            HandlerChainRegistry.restoreScopeVar(prevScope);
            HandlerChainRegistry.restoreTaskChain(prev);
        }
        Boundary b = t.owner;
        Carrier mine = t.carrier;
        t.carrier = null;
        if (t.promise != null) {
            if (t.state == DONE) t.promise.complete(t.result); else t.promise.fail(t.failure);
            forget(t.promise);
        } else if (t.state == FAILED && b.failure == null) {
            b.failure = t.failure;                     // the body's failure is the perform's, rethrown on the entry thread
        }
        idle.addFirst(mine);                           // LIFO, so dispatch hands THIS carrier the next fresh task: no thread switch at all
        Object[] bodyChain = HandlerChainRegistry.installTaskChain(b.body.chain);   // the step's Mezze calls (drive) see the boundary's handlers
        try {
            dispatch(b);
        } finally {
            HandlerChainRegistry.restoreTaskChain(bodyChain);
            CURRENT.remove();
        }
    }

    /**
     * {@code cont.park} on the running task's carrier: hand the baton back with the request
     * and block until the driver delivers the outcome. Called from the `cont-park` root.
     */
    @TruffleBoundary
    public Object park(ParkRequest request) {
        Task t = CURRENT.get();
        if (t == null) throw new MezzeGuestException(
            "cont.park outside a cooperative task: a park needs a cont-capable `perform` to be driven by"
            + " (a callback running on a foreign thread cannot park yet)");
        Boundary b = t.owner;
        if (b.abandoning) throw new BoundaryAbandoned();   // a task that swallowed its abandonment and parked again
        t.parkedOn = request;
        t.state = PARKED;
        t.turn = TURN_DRIVER;
        b.parked.add(t);
        Object[] prev = HandlerChainRegistry.installTaskChain(b.body.chain);
        Task next;
        try { next = dispatch(b); } finally { HandlerChainRegistry.restoreTaskChain(prev); }
        if (next != t) awaitTurn(t, TURN_TASK, this);   // someone else holds the baton: wait for it to come back
        // next == t: woken at once (yield_now, a settled promise) — no park, no handoff
        Object d = t.deliver;
        t.deliver = null;
        t.parkedOn = null;
        if (d == ABANDON) throw new BoundaryAbandoned();
        return d;
    }

    /** Thrown through a parked carrier when its context is disposed, so the thread exits. */
    static final class MachineClosed extends RuntimeException {
        MachineClosed() { super("the task machine was shut down", null, false, false); }
    }

    /** Context disposal: wake every carrier so the pooled threads exit. */
    public void shutdown() {
        closing = true;
        for (Carrier c : allCarriers) {
            c.assigned = POISON;
            LockSupport.unpark(c.thread);
        }
        for (Carrier c : allCarriers) {
            try { c.thread.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------------------------------------------------------- wakes

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
                if (p == null) return i;                 // evicted ⇒ settled (ids are minted here only)
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

    private void blockOrDeadlock(Boundary b) {
        boolean external = false;
        for (Task t : b.parked) {
            if (t.parkedOn.on instanceof MezzeVariant on && on.getTag() == tagOnPromises()) {
                Object payload = on.getPayloadFieldByName("ids");
                int n = seqSize(payload);
                for (int i = 0; i < n; i++) {
                    MezzePromise p = promisesById.get(asLong(seqAt(payload, i)));
                    if (p != null && !p.isDone() && !ownPromises.containsKey(p)) external = true;
                }
            }
        }
        if (external) {
            LockSupport.parkNanos(50_000L);
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
            Object payload = on.getPayloadFieldByName(c.equals("OnPromises") ? "ids" : "fs");
            return c + "(" + seqSize(payload) + ")";
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

    /** The machine is the caller of thunks and predicates it invokes uncached; a tail call the
     *  root could not fuse surfaces as a control-flow exception, which a trampoline would catch
     *  and bounce — so the machine bounces. */
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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T { throw (T) t; }

    private static int seqSize(Object v) {
        if (v instanceof com.mezze.datastructures.seq.MezzeSeq seq) return seq.size();
        if (v instanceof Object[] arr) return arr.length;
        throw new IllegalStateException("expected a sequence, got " + (v == null ? "null" : v.getClass().getName()));
    }
    private static Object seqAt(Object v, int i) {
        if (v instanceof com.mezze.datastructures.seq.MezzeSeq seq) return seq.get(i);
        return ((Object[]) v)[i];
    }
    private static long asLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i;
        throw new IllegalStateException("expected an Int id, got " + (o == null ? "null" : o.getClass().getName()));
    }

    private static final String[] WHICH = {"which"}, REASON = {"reason"};
    private static volatile int TAG_READY = -1, TAG_CANCELLED = -1, TAG_IMMEDIATELY = -1, TAG_ON_PROMISES = -1;
    private static int tagImmediately() { int t = TAG_IMMEDIATELY; if (t < 0) TAG_IMMEDIATELY = t = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Immediately"); return t; }
    private static int tagOnPromises() { int t = TAG_ON_PROMISES; if (t < 0) TAG_ON_PROMISES = t = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("OnPromises"); return t; }

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
        if (tag < 0) TAG_READY = tag = registry.getOrCreateTag("ParkReady");
        var rec = MezzeRecord.fromFields(WHICH, new Object[]{which});
        return MezzeVariant.singlePayload(registry, tag, rec);
    }
}
