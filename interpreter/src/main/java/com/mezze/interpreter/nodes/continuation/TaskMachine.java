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
        /** A FOREIGN parker (a Loom task, an HTTP handler, a polyglot callback) that called
         *  `cont.park` under this boundary: no carrier, no baton — the outcome is delivered to
         *  its own thread, which blocks meanwhile. Null for the machine's own tasks. */
        ForeignPark foreign;
        TaskMachine machine;
        Task(long id, MezzeFunction thunk, MezzePromise promise) {
            this.id = id; this.thunk = thunk; this.promise = promise;
        }
        @Override public String toString() {
            if (foreign != null) return "the foreign thread `" + foreign.thread.getName() + "`";
            String site = spawnedFn != null ? siteOf(spawnedFn) : null;
            return site != null ? "the task spawned at " + site : (promise == null ? "the perform body" : "Task#" + id);
        }
    }

    /** A park posted by a thread the machine does not drive. See {@link #park}. */
    static final class ForeignPark {
        final Thread thread = Thread.currentThread();
        final ParkRequest request;
        final HandlerChainEntry[] chain;
        volatile Object deliver;
        ForeignPark(ParkRequest request, HandlerChainEntry[] chain) { this.request = request; this.chain = chain; }
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
        /** Parks posted by foreign threads, drained by the scheduler step. */
        final java.util.concurrent.ConcurrentLinkedQueue<Object> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();   // ForeignPark | Task (a foreign spawn)
        final MezzeRecord handler;
        Boundary prevForHandler;
        /** How many carriers may run at once. 1 (CoopAsync): the single-active discipline, no lock.
         *  N > 1 (`carriers = {} -> N` on the performer): up to N tasks run concurrently and the
         *  boundary's bookkeeping is taken under {@link #lock}. */
        final int budget;
        final java.util.concurrent.locks.ReentrantLock lock;
        int running;
        /** The carrier idling in {@link #blockOrDeadlock}, to be woken by a settling promise or a
         *  foreign post instead of waiting out the fallback. */
        volatile Thread idler;
        Boundary(MezzeFunction drive, MezzeRecord handler, int budget) {
            this.handler = handler; this.budget = budget; this.lock = budget > 1 ? new java.util.concurrent.locks.ReentrantLock() : null; this.drive = drive; this.runQueue = new RunQueue(this); this.entryThread = Thread.currentThread(); }
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

    private final java.util.concurrent.atomic.AtomicLong nextTaskId = new java.util.concurrent.atomic.AtomicLong(1);   // foreign spawns mint too
    private long nextPromiseId = 1;
    /** Open boundaries by their handler record, for a foreign thread to find the one it runs
     *  under through its own handler chain (identity: the record pushed by the perform IS the
     *  record in every chain snapshot taken beneath it). Read by foreign threads. */
    private final java.util.Map<MezzeRecord, Boundary> openByHandler =
        java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** BUG 77: the size of the promise bookkeeping when the last boundary closed (a test's view). */
    public static volatile int LAST_BOOKKEEPING = -1;
    private final LongObjectMap<MezzePromise> promisesById = new LongObjectMap<>();

    // ---------------------------------------------------------------- spawn / promises

    public MezzePromise spawn(MezzeFunction f) {
        Task cur = CURRENT.get();
        boolean foreign = cur == null;
        Boundary b = cur != null ? cur.owner : boundaryOfForeignThread();
        if (b == null) throw new MezzeGuestException(
            "spawn_task outside a scheduler: a task needs a cont-capable `perform` (one whose "
            + "performer mentions `cont`) to be driven by");
        MezzePromise p = new MezzePromise(null, null);
        p.machineOwned = true;
        Task t = new Task(nextTaskId.getAndIncrement(), f, p);
        t.machine = this;
        t.owner = b;
        t.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        t.scope = HandlerChainRegistry.lookupScopeVar();
        t.spawnedFn = f;
        p.machineTask = t;
        p.setCancelHook(reason -> cancelParked(t, reason));
        if (foreign) { b.inbox.add(t); wakeIdler(b); } // a Loom task / HTTP handler spawning under this boundary: the step queues it
        else { lockB(b); try { b.ready.addLast(t); } finally { unlockB(b); } }
        return p;
    }

    // ---------------------------------------------------------------- host-level waits on a carrier

    /** True on a thread that is a carrier running one of the machine's tasks. */
    public static boolean onCarrier() { return CURRENT.get() != null; }

    /**
     * A Java-level wait by code running ON A CARRIER — a Loom-style promise await, a sleep, a
     * backoff loop — must not block the thread: it holds the baton, and every other task of the
     * boundary would stall behind it (measured as a deadlock: a body awaiting a Loom promise while
     * the Loom task parks on the body's boundary). Instead the task parks on the condition; the
     * scheduler step re-checks it like a predicate and the other tasks take their turns. The wait
     * counts as external for the deadlock verdict (the machine cannot know who satisfies it).
     */
    public static void javaWait(java.util.function.BooleanSupplier ready) {
        Task t = CURRENT.get();
        if (t == null) throw new IllegalStateException("javaWait off a carrier");
        while (!ready.getAsBoolean()) t.machine.park(new ParkRequest(ready, null));
    }

    /** A host-level await of a promise on a carrier: the task parks on the PROMISE, so an idle
     *  boundary is woken by its settlement ({@code machineWaiter}) rather than by polling. */
    public static void awaitPromiseOnCarrier(MezzePromise p) {
        Task t = CURRENT.get();
        if (t == null) throw new IllegalStateException("awaitPromiseOnCarrier off a carrier");
        while (!p.isDone()) t.machine.park(new ParkRequest(p, null));
    }

    /** A time-based host wait: the idle fallback parks only until the nearest deadline. */
    public static final class Deadline implements java.util.function.BooleanSupplier {
        final long at;
        Deadline(long at) { this.at = at; }
        @Override public boolean getAsBoolean() { return System.nanoTime() - at >= 0; }
    }

    /** A condition that is false once, then true: a host-level yield (one turn to the others). */
    public static final class OnceFalse implements java.util.function.BooleanSupplier {
        private boolean asked;
        @Override public boolean getAsBoolean() { if (asked) return true; asked = true; return false; }
    }

    /** {@code LockSupport.parkNanos} that yields the baton when on a carrier. */
    public static void parkNanosCooperatively(long nanos) {
        if (CURRENT.get() == null) { LockSupport.parkNanos(nanos); return; }
        javaWait(new Deadline(System.nanoTime() + nanos));
    }

    private void cancelParked(Task t, Object reason) {
        Boundary b = t.owner;
        if (b == null) return;
        lockB(b);
        try {
            if (t.state != PARKED) return;
            b.parked.remove(t);
            deliver(b, t, parkCancelled(reason));
        } finally {
            unlockB(b);
        }
    }

    /** A parked task gets its outcome: the machine's own tasks become runnable and receive it on
     *  their next turn; a foreign parker receives it on its own thread at once. */
    private static void deliver(Boundary b, Task t, Object outcome) {
        if (t.foreign != null) {
            t.state = DONE;
            t.foreign.deliver = outcome;
            LockSupport.unpark(t.foreign.thread);
            return;
        }
        t.deliver = outcome;
        t.state = RUNNABLE;
        b.ready.addLast(t);
    }

    /** Foreign parks become parked tasks of the boundary, evaluated like any other. */
    private void drainInbox(Boundary b) {
        Object m;
        while ((m = b.inbox.poll()) != null) {
            if (m instanceof Task spawned) { b.ready.addLast(spawned); continue; }
            ForeignPark fp = (ForeignPark) m;
            Task t = new Task(nextTaskId.getAndIncrement(), null, null);
            t.machine = this;
            t.owner = b;
            t.foreign = fp;
            t.chain = fp.chain;
            t.parkedOn = fp.request;
            t.state = PARKED;
            b.parked.add(t);
        }
    }

    /** The boundary a foreign thread runs under: the innermost handler of ITS chain that an open
     *  boundary was entered with. Null when none. */
    private Boundary boundaryOfForeignThread() {
        for (MezzeRecord h : HandlerChainRegistry.currentHandlersInnermostFirst()) {
            Boundary b = openByHandler.get(h);
            if (b != null) return b;
        }
        return null;
    }

    public long promiseId(MezzePromise p) {
        long id = p.machineId;
        if (id == 0) {
            id = nextPromiseId++;
            if (p.isDone()) return id;                 // a settled promise is never registered —
            p.machineId = id;                          // an id the machine does not know is a settled one
            promisesById.put(id, p);
        }
        return id;
    }

    /** Completed work leaves the map (BUG 77). */
    private void forget(MezzePromise p) {
        if (p == null) return;
        long id = p.machineId;
        if (id != 0) { p.machineId = 0; promisesById.remove(id); }
    }

    // ---------------------------------------------------------------- the boundary

    /**
     * Run a cont-capable perform's body as this boundary's body task, drive every task it
     * spawns until the body is done, tear the scope down, and return the body's result (or
     * rethrow its failure — an `abort` included — on the driver, where the handle is).
     */
    private static void lockB(Boundary b) { if (b.lock != null) b.lock.lock(); }
    private static void unlockB(Boundary b) { if (b.lock != null) b.lock.unlock(); }

    @TruffleBoundary
    public Object runBoundary(MezzeFunction bodyThunk, MezzeRecord handler) {
        Boundary b = new Boundary(driveOf(handler), handler, carriersOf(handler));
        boundaries.push(b);
        if (handler != null) b.prevForHandler = openByHandler.put(handler, b);
        if (b.budget > 1) parallelOpen++;
        try {
            Task body = new Task(nextTaskId.getAndIncrement(), bodyThunk, null);
            body.machine = this;
            body.owner = b;
            body.chain = HandlerChainRegistry.snapshotHandlerChainEntries();
            body.scope = HandlerChainRegistry.lookupScopeVar();
            b.body = body;
            lockB(b);
            try { give(body); } finally { unlockB(b); }   // the body's carrier takes it from here
            while (!b.finished) {
                if (closing) throw new MachineClosed();
                LockSupport.park(b);
            }
            if (b.failure != null) sneakyThrow(b.failure);
            return body.result;
        } finally {
            if (b.budget > 1) parallelOpen--;
            boundaries.pop();
            if (handler != null) { if (b.prevForHandler != null) openByHandler.put(handler, b.prevForHandler); else openByHandler.remove(handler); }
            LAST_BOOKKEEPING = promisesById.size();
            // The pool lives as long as some boundary is open. Context close waits for every
            // polyglot thread, so idle carriers must not outlive the last perform.
            if (boundaries.isEmpty()) releaseIdleCarriers();
        }
    }

    private void releaseIdleCarriers() {
        Carrier c;
        while ((c = poolTake()) != null) {
            if (parallelOpen == 0) allCarriers.remove(c); else synchronized (poolLock) { allCarriers.remove(c); }
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
        return dispatch(b, null);
    }

    /**
     * The scheduler step. The caller holds {@link Boundary#lock} when the boundary is parallel.
     * {@code self}: the task of the carrier running the step (a parker), so a self re-pick is
     * reported and that carrier returns at once instead of waiting for its own turn.
     */
    private Task dispatch(Boundary b, Task self) {
        Task last = null;
        while (true) {
            drainInbox(b);
            wakeParked(b);                             // first: a task that just yielded is back in the ready set BEFORE drive picks
            if (!b.ready.isEmpty() && b.running < b.budget) {
                Task next = pick(b);
                give(next);
                if (next == self) return next;
                last = next;
                if (b.budget > 1) continue;            // parallel: fill every free slot
                return next;
            }
            if (last != null) return last;
            if (b.running > 0) return null;            // parallel: others are running; the next one to park or end steps again
            boolean bodyEnded = b.body.state == DONE || b.body.state == FAILED;
            if (bodyEnded) {
                if (b.parked.isEmpty()) { finish(b); return null; }
                if (!b.abandoning && b.teardownRounds++ < TEARDOWN_ROUNDS) {
                    Object cancelled = parkCancelled(scopeTeardown());
                    for (Task t : new ArrayList<>(b.parked)) deliver(b, t, cancelled);
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
        for (Task t : new ArrayList<>(b.parked)) deliver(b, t, ABANDON);
        b.parked.clear();
    }

    private static final Object ABANDON = new Object();

    /** Unwinds an abandoned task's carrier. Not a guest error: nothing in Mezze observes it. */
    static final class BoundaryAbandoned extends RuntimeException {
        BoundaryAbandoned() { super("the perform this task belongs to has failed", null, false, false); }
    }

    private static void wakeIdler(Boundary b) {
        Thread i = b.idler;
        if (i != null) LockSupport.unpark(i);
    }

    private void finish(Boundary b) {
        b.finished = true;
        LockSupport.unpark(b.entryThread);
    }

    /** The performer's carrier budget: `carriers = {} -> N`. Absent: 1 (the plain cooperative
     *  machine). 0 spelled explicitly: one per processor. */
    private static int carriersOf(MezzeRecord handler) {
        if (handler == null) return 1;
        Object c = handler.getField(com.mezze.language.ast.ScopedEffectConstants.CARRIERS);
        if (!(c instanceof MezzeFunction f)) return 1;
        Object n = callWith(f, MezzeRecord.empty());
        long v = n instanceof Long l ? l : 1;
        if (v == 0) v = Runtime.getRuntime().availableProcessors();
        return (int) Math.max(1, v);
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
        t.owner.running++;
        Carrier c = t.carrier;
        if (c == null) {
            c = poolTake();
            if (c == null) { if (env == null) env = com.mezze.interpreter.runtime.MezzeContext.get(null).getEnv(); c = newCarrier(); }
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
        }
        lockB(b);
        try {
            if (t.promise == null && t.state == FAILED && b.failure == null) {
                b.failure = t.failure;                 // the body's failure is the perform's, rethrown on the entry thread
            }
            b.running--;
            poolPut(mine);                             // LIFO, so dispatch hands THIS carrier the next fresh task: no thread switch at all
            Object[] bodyChain = HandlerChainRegistry.installTaskChain(b.body.chain);   // the step's Mezze calls (drive) see the boundary's handlers
            try {
                dispatch(b);
            } finally {
                HandlerChainRegistry.restoreTaskChain(bodyChain);
                CURRENT.remove();
            }
        } finally {
            unlockB(b);
        }
    }

    // The carrier pool is shared by every boundary of the machine. Under the single-active
    // discipline one thread touches it at a time; while a parallel boundary is open, pool
    // operations take poolLock.
    private volatile int parallelOpen;
    private final Object poolLock = new Object();
    private void poolPut(Carrier c) {
        if (parallelOpen == 0) { idle.addFirst(c); return; }
        synchronized (poolLock) { idle.addFirst(c); }
    }
    private Carrier poolTake() {
        if (parallelOpen == 0) return idle.pollFirst();
        synchronized (poolLock) { return idle.pollFirst(); }
    }
    private Carrier newCarrier() {
        Carrier c;
        if (parallelOpen == 0) c = new Carrier(env);
        else synchronized (poolLock) { c = new Carrier(env); }
        c.thread.start();
        return c;
    }

    /**
     * {@code cont.park} on the running task's carrier: hand the baton back with the request
     * and block until the driver delivers the outcome. Called from the `cont-park` root.
     */
    @TruffleBoundary
    public Object park(ParkRequest request) {
        Task t = CURRENT.get();
        if (t == null) return parkForeign(request);
        Boundary b = t.owner;
        if (b.abandoning) throw new BoundaryAbandoned();   // a task that swallowed its abandonment and parked again
        Task child;
        lockB(b);
        try { child = inlineChild(b, request); } finally { unlockB(b); }
        if (child != null) {
            // Zero handoffs: the awaited task runs on THIS carrier, nested above the awaiter. The
            // awaiter could not have run before it anyway (it waits for exactly this task), and a
            // sole runnable is the only pick `drive` could make, so nothing observable changes.
            runInline(t, child);
            if (b.abandoning) throw new BoundaryAbandoned();
            int which = readyIndex(request);
            if (which >= 0) return parkReady(which);
            // (the child settled its promise, so this is unreachable; fall through to a real park)
        }
        t.parkedOn = request;
        t.state = PARKED;
        t.turn = TURN_DRIVER;
        Task next;
        lockB(b);
        try {
            b.running--;
            b.parked.add(t);
            Object[] prev = HandlerChainRegistry.installTaskChain(b.body.chain);
            try { next = dispatch(b, t); } finally { HandlerChainRegistry.restoreTaskChain(prev); }
        } finally {
            unlockB(b);
        }
        if (next != t) awaitTurn(t, TURN_TASK, this);   // someone else holds the baton: wait for it to come back
        // next == t: woken at once (yield_now, a settled promise) — no park, no handoff
        Object d = t.deliver;
        t.deliver = null;
        t.parkedOn = null;
        if (d == ABANDON) throw new BoundaryAbandoned();
        return d;
    }

    /**
     * `cont.park` on a thread the machine does not drive — a Loom task, an HTTP handler, a
     * polyglot callback — that is running under a cont-capable perform (the handler is in its
     * chain). The park is posted to that boundary's inbox and this thread blocks until the
     * machine delivers the outcome; the boundary's own tasks keep taking turns meanwhile, and the
     * scheduler step evaluates this park's wait set like any other (under this thread's chain).
     * The foreign thread is concurrent with the carriers by nature — it already was.
     */
    private Object parkForeign(ParkRequest request) {
        Boundary b = boundaryOfForeignThread();
        if (b == null) throw new MezzeGuestException(
            "cont.park outside a cooperative task: a park needs a cont-capable `perform` (one whose "
            + "performer mentions `cont`) somewhere above it on this thread");
        ForeignPark fp = new ForeignPark(request, HandlerChainRegistry.snapshotHandlerChainEntries());
        b.inbox.add(fp);
        wakeIdler(b);
        long deadline = 0;
        Object d;
        while ((d = fp.deliver) == null) {
            if (closing) throw new MachineClosed();
            if (spinNanos > 0) {
                long now = System.nanoTime();
                if (deadline == 0) deadline = now + spinNanos;
                if (now < deadline) { Thread.onSpinWait(); continue; }
            }
            LockSupport.park(fp);
        }
        if (d == ABANDON) throw new BoundaryAbandoned();
        return d;
    }

    /**
     * The inline-child fast path's precondition: the request waits on exactly one promise, that
     * promise is settled by a task of this boundary that has not started, that task is the ONLY
     * runnable one, and nothing is waiting in the inbox ahead of it.
     */
    private Task inlineChild(Boundary b, ParkRequest request) {
        if (!(request.on instanceof MezzeVariant on) || on.getTag() != tagOnPromises()) return null;
        if (b.ready.size() != 1 || !b.inbox.isEmpty()) return null;
        Object payload = on.getPayloadFieldByName("ids");
        if (seqSize(payload) != 1) return null;
        MezzePromise p = promisesById.get(asLong(seqAt(payload, 0)));
        if (p == null || p.isDone() || !(p.machineTask instanceof Task c)) return null;
        if (c.state != RUNNABLE || c.carrier != null || c.thunk == null || b.ready.peekFirst() != c) return null;
        b.ready.pollFirst();
        return c;
    }

    /** Run {@code child} to completion (or until it parks and is later resumed on this same
     *  carrier) nested on {@code parent}'s carrier; settle its promise. No dispatch afterwards:
     *  the parent continues at once. */
    private void runInline(Task parent, Task child) {
        child.state = RUNNING;
        child.carrier = parent.carrier;                // a nested park/resume uses this carrier's thread
        Task prevCur = CURRENT.get();
        CURRENT.set(child);
        Object[] prev = HandlerChainRegistry.installTaskChain(child.chain);
        Object prevScope = HandlerChainRegistry.installScopeVar(child.scope);
        try {
            child.result = callWith(child.thunk, MezzeRecord.empty());
            child.state = DONE;
        } catch (MachineClosed closed) {
            throw closed;
        } catch (Throwable e) {
            child.failure = e;
            child.state = FAILED;
        } finally {
            HandlerChainRegistry.restoreScopeVar(prevScope);
            HandlerChainRegistry.restoreTaskChain(prev);
            CURRENT.set(prevCur);
        }
        child.carrier = null;
        if (child.state == DONE) child.promise.complete(child.result); else child.promise.fail(child.failure);
        forget(child.promise);
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
            int which = needsChain(t.parkedOn) ? readyIndexUnderChain(t) : readyIndex(t.parkedOn);
            if (which >= 0) {
                b.parked.remove(i);
                deliver(b, t, parkReady(which));
                any = true;
            } else {
                i++;
            }
        }
        return any;
    }

    private int readyIndex(ParkRequest pr) {
        if (pr.on instanceof MezzePromise p) return p.isDone() ? 0 : -1;                                     // awaitPromiseOnCarrier
        if (pr.on instanceof java.util.function.BooleanSupplier host) return host.getAsBoolean() ? 0 : -1;   // javaWait
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

    /** Only predicates run Mezze code and need the parker's handlers installed. */
    private static boolean needsChain(ParkRequest pr) {
        return pr.on instanceof MezzeVariant on && on.getTag() != tagImmediately() && on.getTag() != tagOnPromises();
    }

    private int readyIndexUnderChain(Task t) {
        Object[] prev = HandlerChainRegistry.installTaskChain(t.chain);
        try { return readyIndex(t.parkedOn); } finally { HandlerChainRegistry.restoreTaskChain(prev); }
    }

    private void blockOrDeadlock(Boundary b) {
        boolean external = false;
        long nearest = Long.MAX_VALUE;                 // nearest Deadline, if any
        Thread me = Thread.currentThread();
        ArrayList<MezzePromise> hooked = null;
        for (Task t : b.parked) {
            Object on = t.parkedOn.on;
            if (on instanceof MezzePromise p) {
                // Settled between wakeParked's check and this one (a Loom task completing on its own
                // thread does that within microseconds): not a deadlock — the next step wakes it.
                if (p.isDone()) return;
                external = true; p.machineWaiter = me; (hooked == null ? hooked = new ArrayList<>() : hooked).add(p);
                continue;
            }
            if (on instanceof Deadline d) { external = true; nearest = Math.min(nearest, d.at); continue; }
            if (on instanceof java.util.function.BooleanSupplier) { external = true; continue; }   // opaque host wait: fallback only
            if (on instanceof MezzeVariant v && v.getTag() == tagOnPromises()) {
                Object payload = v.getPayloadFieldByName("ids");
                int n = seqSize(payload);
                for (int i = 0; i < n; i++) {
                    MezzePromise p = promisesById.get(asLong(seqAt(payload, i)));
                    if (p == null || p.isDone()) return;        // settled since the wake check: the next step wakes it
                    if (!p.machineOwned) { external = true; p.machineWaiter = me; (hooked == null ? hooked = new ArrayList<>() : hooked).add(p); }
                }
            }
        }
        if (external) {
            // Idle until something can have changed: a hooked promise settles (its completer unparks
            // machineWaiter), a foreign thread posts to the inbox (it unparks idler), the nearest
            // deadline passes, or the 1 ms fallback. Register, then RE-CHECK before parking (Dekker:
            // both sides' stores are volatile) so a settlement racing the registration is not missed.
            b.idler = me;
            try {
                boolean settled = false;
                if (hooked != null) for (MezzePromise p : hooked) if (p.isDone()) { settled = true; break; }
                if (!settled && b.inbox.isEmpty()) {
                    long wait = 1_000_000L;
                    if (nearest != Long.MAX_VALUE) wait = Math.max(1_000L, Math.min(wait, nearest - System.nanoTime()));
                    unlockB(b);                        // a foreign cancel or spawn must not wait on the idle park
                    try { LockSupport.parkNanos(this, wait); } finally { lockB(b); }
                }
            } finally {
                b.idler = null;
                if (hooked != null) for (MezzePromise p : hooked) if (p.machineWaiter == me) p.machineWaiter = null;
            }
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
        if (pr.on instanceof MezzePromise) return "a promise (host-level await)";
        if (pr.on instanceof java.util.function.BooleanSupplier) return "a host-level wait";
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
