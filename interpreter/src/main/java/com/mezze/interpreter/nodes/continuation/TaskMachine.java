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
        Carrier me = t.carrier;
        long budget = me != null ? me.spinBudget : spinNanos;
        long start = System.nanoTime();
        long deadline = budget > 0 ? start + budget : 0;
        boolean parked = false;
        while (t.turn != wanted) {
            if (closing) throw new MachineClosed();
            if (deadline != 0 && System.nanoTime() < deadline) { Thread.onSpinWait(); continue; }
            parked = true;
            LockSupport.park(blocker);
        }
        if (me != null) me.adapt(System.nanoTime() - start, !parked);
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
        /** Bumped on every park; a {@link WaitQueue} entry from an earlier park is stale. */
        volatile long parkEpoch;
        /** Set by {@link WaitQueue#signal()}: this waiter's `wait_on` test is worth evaluating. */
        volatile boolean signalled;
        /** The park waits on at least one `wait_on` predicate (registered with its queue). */
        boolean waitOn;
        /** Where this task sits in its boundary's lists, for O(1) removal. -1 = not there. */
        int parkedIndex = -1, plainIndex = -1;
        /** The deadline this park wakes at (0 = none); the deadline heap orders by it. */
        long deadlineAt;
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
        /** Every parked task (the master list); the three indexes below find the ones to look at. */
        final ArrayList<Task> parked = new ArrayList<>();
        /** Tasks a wait queue or a promise signalled since the last step: re-checked, nothing else is. */
        final java.util.concurrent.ConcurrentLinkedQueue<Task> signalled = new java.util.concurrent.ConcurrentLinkedQueue<>();
        /** Tasks whose park is (also) a PLAIN predicate: the only ones the machine must poll. */
        final ArrayList<Task> plain = new ArrayList<>();
        /** Tasks with a deadline, soonest first; stale entries (woken earlier) are skipped when met. */
        final java.util.PriorityQueue<Task> deadlines = new java.util.PriorityQueue<>((a, c) -> Long.compare(a.deadlineAt, c.deadlineAt));
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
        /** The carrier idling in {@link #blockOrDeadlock}, to be woken by a settling promise or a
         *  foreign post instead of waiting out the fallback. */
        volatile Thread idler;
        /** Plain-predicate polling: the current backoff, and when the boundary went idle on them. */
        long pollNanos = 50_000L;
        /** Plain predicates are POLLS, so they have a rate: while tasks are runnable they are
         *  re-evaluated at most every {@link #PLAIN_SCAN_NANOS}; when the boundary is idle, every
         *  step. Without this a step was linear in parked plain waiters and a spawn loop quadratic
         *  (100k tasks parking on `is_done(gate)` ran for minutes). */
        long lastPlainScan;
        /** How long the last plain scan took: the next one waits at least ten times that, so polling
         *  plain predicates never takes more than a tenth of the machine's time however many there
         *  are (a time-only limit re-scanned 100k predicates on every step once one scan outlasted it). */
        long lastPlainScanCost;
        long idleSince;
        /** The stall report (`on_stall`): fire `stallHook { waiting }` once the machine has made no
         *  progress for `stallAfterNanos`; -1 = no report. Re-armed on every progress. */
        final long stallAfterNanos;
        final MezzeFunction stallHook;
        boolean stallReported;
        Boundary(MezzeFunction drive, MezzeRecord handler, Object[] stall) {
            this.stallAfterNanos = (Long) stall[0]; this.stallHook = (MezzeFunction) stall[1];
            this.handler = handler; this.drive = drive; this.runQueue = new RunQueue(this); this.entryThread = Thread.currentThread(); }
    }

    /** A pooled virtual thread that runs one task at a time. */
    private final class Carrier implements Runnable {
        final Thread thread;
        volatile Task assigned;
        /** This thread's effect state and the task it runs, read once instead of a thread-local
         *  per operation (ThreadLocal.get was 14–20% of a park, measured). */
        HandlerChainRegistry.EffectState es;
        Task current;
        /** Adaptive to the OBSERVED wait: a wait short enough to have been caught (under the
         *  machine's maximum) grows the budget towards it; a long wait (I/O-like) shrinks it, so a
         *  carrier that waits on the outside world stops burning CPU after a few rounds, while two
         *  tasks trading a baton keep catching each other. (Halving on every miss was tried first
         *  and collapsed to zero in a ping-pong: each turn's real work outlasted a halved budget.) */
        long spinBudget = spinNanos;
        void adapt(long waitedNanos, boolean caught) {
            if (caught) { spinBudget = Math.min(spinNanos, Math.max(spinBudget, waitedNanos * 2 + 500)); return; }
            if (waitedNanos < spinNanos * 4) spinBudget = Math.min(spinNanos, Math.max(waitedNanos * 2, 1_000L));   // a near miss: spin longer next time
            else spinBudget = Math.max(0L, spinBudget / 2);                                                            // a real wait: spin less
        }
        /** The Truffle context this carrier enters on start (see run). */
        private final com.oracle.truffle.api.TruffleContext truffleContext;
        Carrier(Env env) {
            // NOT env.newTruffleThreadBuilder: building a polyglot thread walks every live thread of the
            // context (PolyglotContextImpl.checkAllThreadAccesses, measured 72% of a spawn), so the
            // n-th carrier cost n steps and 100k parked tasks took minutes. A plain virtual thread that
            // enters the context itself takes the per-thread path once.
            truffleContext = env.getContext();
            thread = Thread.ofVirtual().name("mezze-coop-" + (++carriersMinted)).unstarted(this);
            allCarriers.add(this);
        }
        @Override public void run() {
            Object prevContext = truffleContext.enter(null);
            try {
                loop();
            } finally {
                truffleContext.leave(null, prevContext);
            }
        }

        private void loop() {
            es = HandlerChainRegistry.state();
            CURRENT_CARRIER.set(this);
            while (true) {
                Task t;
                long start = System.nanoTime();
                long deadline = spinBudget > 0 ? start + spinBudget : 0;
                boolean parked = false;
                while ((t = assigned) == null) {
                    if (closing) return;
                    if (deadline != 0 && System.nanoTime() < deadline) { Thread.onSpinWait(); continue; }   // the next fresh task usually arrives within a microsecond
                    parked = true;
                    LockSupport.park(this);
                }
                assigned = null;
                adapt(System.nanoTime() - start, !parked);
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
    private static final ThreadLocal<Carrier> CURRENT_CARRIER = new ThreadLocal<>();
    private static Task currentTask() { Carrier c = CURRENT_CARRIER.get(); return c == null ? null : c.current; }

    /** A wait queue signalled this parked task (from any thread): wake the boundary's idler so
     *  the next step re-evaluates it. Nothing to do when a carrier is already stepping. */
    static void poke(Task t) {
        Boundary b = t.owner;
        if (b != null) wakeIdler(b);
    }

    /** A wait queue or a promise says this parked task is worth re-checking. From any thread. */
    static void signalled(Task t) {
        t.signalled = true;
        Boundary b = t.owner;
        if (b != null) { b.signalled.add(t); wakeIdler(b); }
    }

    static Object callWith(MezzeFunction fn, MezzeRecord arg) { return callWithImpl(fn, arg); }

    private final java.util.concurrent.atomic.AtomicLong nextTaskId = new java.util.concurrent.atomic.AtomicLong(1);   // foreign spawns mint too
    /** Open boundaries by their handler record, for a foreign thread to find the one it runs
     *  under through its own handler chain (identity: the record pushed by the perform IS the
     *  record in every chain snapshot taken beneath it). Read by foreign threads. */
    private final java.util.Map<MezzeRecord, Boundary> openByHandler =
        java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** BUG 77: the size of the promise bookkeeping when the last boundary closed (a test's view). */
    public static volatile int LAST_BOOKKEEPING = -1;

    // ---------------------------------------------------------------- spawn / promises

    public MezzePromise spawn(MezzeFunction f) {
        Task cur = currentTask();
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
        p.machineTask = t;                             // cancel reaches the machine through the task (no hook object per spawn)
        if (foreign) { b.inbox.add(t); wakeIdler(b); } // a Loom task / HTTP handler spawning under this boundary: the step queues it
        else b.ready.addLast(t);
        return p;
    }

    // ---------------------------------------------------------------- host-level waits on a carrier

    /** True on a thread that is a carrier running one of the machine's tasks. */
    public static boolean onCarrier() { return currentTask() != null; }

    /**
     * A Java-level wait by code running ON A CARRIER — a Loom-style promise await, a sleep, a
     * backoff loop — must not block the thread: it holds the baton, and every other task of the
     * boundary would stall behind it (measured as a deadlock: a body awaiting a Loom promise while
     * the Loom task parks on the body's boundary). Instead the task parks on the condition; the
     * scheduler step re-checks it like a predicate and the other tasks take their turns. The wait
     * counts as external for the deadlock verdict (the machine cannot know who satisfies it).
     */
    public static void javaWait(java.util.function.BooleanSupplier ready) {
        Task t = currentTask();
        if (t == null) throw new IllegalStateException("javaWait off a carrier");
        while (!ready.getAsBoolean()) t.machine.park(new ParkRequest(ready, null));
    }

    /** A host-level await of a promise on a carrier: the task parks on the PROMISE, so an idle
     *  boundary is woken by its settlement ({@code machineWaiter}) rather than by polling. */
    public static void awaitPromiseOnCarrier(MezzePromise p) {
        Task t = currentTask();
        if (t == null) throw new IllegalStateException("awaitPromiseOnCarrier off a carrier");
        while (!p.isDone()) t.machine.park(new ParkRequest(p, null));
    }

    /**
     * A blocking native on a carrier (mutex lock, semaphore acquire, latch await, wait-group wait):
     * park the TASK on the primitive's wait queue and re-check {@code test} when the queue is
     * signalled, or at {@code deadlineNanos} (0 = none). Returns whether the test held (false only
     * at a deadline). The thread-per-task performers keep their blocking path.
     */
    public static boolean waitOnQueue(WaitQueue q, java.util.function.BooleanSupplier test, long deadlineNanos) {
        Task t = currentTask();
        if (t == null) throw new IllegalStateException("waitOnQueue off a carrier");
        while (!test.getAsBoolean()) {
            if (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0) return false;
            t.machine.park(new ParkRequest(new QueueWait(q, test, deadlineNanos), null));
        }
        return true;
    }

    /** The request `waitOnQueue` parks on: registered with {@code queue}, woken by its signal or
     *  the deadline; {@code getAsBoolean} is the re-check. */
    static final class QueueWait implements java.util.function.BooleanSupplier {
        final WaitQueue queue; final java.util.function.BooleanSupplier test; final long at;
        QueueWait(WaitQueue queue, java.util.function.BooleanSupplier test, long at) { this.queue = queue; this.test = test; this.at = at; }
        @Override public boolean getAsBoolean() { return test.getAsBoolean() || (at != 0 && System.nanoTime() - at >= 0); }
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
        if (currentTask() == null) { LockSupport.parkNanos(nanos); return; }
        javaWait(new Deadline(System.nanoTime() + nanos));
    }


    private void cancelParked(Task t, Object reason) {
        if (t.state != PARKED || t.owner == null) return;
        removeParked(t.owner, t);              // by index: a shift here corrupted every later index
        deliver(t.owner, t, parkCancelled(reason));
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
            t.parkEpoch++;
            addParked(b, t, fp.request);
            int nowReady = readyIndex(fp.request);     // the same registration race, for a foreign parker
            if (nowReady >= 0) { removeParked(b, t); deliver(b, t, parkReady(nowReady)); }
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

    /** Cancellation of a machine promise, from the promise itself (no per-spawn hook object). */
    public static void cancelFromPromise(MezzePromise p, Object reason) {
        if (p.machineTask instanceof Task t && t.machine != null) t.machine.cancelParked(t, reason);
    }

    /** The promise a `PromiseId` names: the id IS the promise (an opaque reference). */
    private static MezzePromise promiseOf(Object id) {
        if (id instanceof MezzePromise p) return p;
        throw new IllegalStateException("PromiseId is not a promise reference: " + (id == null ? "null" : id.getClass().getName()));
    }

    // ---------------------------------------------------------------- the boundary

    /**
     * Run a cont-capable perform's body as this boundary's body task, drive every task it
     * spawns until the body is done, tear the scope down, and return the body's result (or
     * rethrow its failure — an `abort` included — on the driver, where the handle is).
     */
    @TruffleBoundary
    public Object runBoundary(MezzeFunction bodyThunk, MezzeRecord handler) {
        Object[] stall = PENDING_STALL.get(); PENDING_STALL.remove();   // handed over by initialize_scope on this thread
        Boundary b = new Boundary(driveOf(handler), handler, stall != null ? stall : NO_STALL);
        boundaries.push(b);
        if (handler != null) b.prevForHandler = openByHandler.put(handler, b);
        try {
            Task body = new Task(nextTaskId.getAndIncrement(), bodyThunk, null);
            body.machine = this;
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
            if (handler != null) { if (b.prevForHandler != null) openByHandler.put(handler, b.prevForHandler); else openByHandler.remove(handler); }
            LAST_BOOKKEEPING = 0;                      // BUG 77: nothing is registered any more — the id is the promise
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
        return dispatch(b, null, HandlerChainRegistry.state());
    }

    private Task dispatch(Boundary b, Task self, HandlerChainRegistry.EffectState es) {
        while (true) {
            drainInbox(b);
            wakeParked(b, es);                         // first: a task that just yielded is back in the ready set BEFORE drive picks
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
                    for (Task t : new ArrayList<>(b.parked)) { removeParked(b, t); deliver(b, t, cancelled); }
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
        for (Task t : new ArrayList<>(b.parked)) { removeParked(b, t); deliver(b, t, ABANDON); }
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
        t.owner.pollNanos = 50_000L;
        t.owner.idleSince = 0;
        t.owner.stallReported = false;
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
        Carrier me = t.carrier;
        HandlerChainRegistry.EffectState es = me.es;
        me.current = t;
        Object[] prev = HandlerChainRegistry.installTaskChain(es, t.chain);
        Object prevScope = HandlerChainRegistry.installScopeVar(es, t.scope);
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
            HandlerChainRegistry.restoreScopeVar(es, prevScope);
            HandlerChainRegistry.restoreTaskChain(prev);
        }
        Boundary b = t.owner;
        Carrier mine = t.carrier;
        t.carrier = null;
        if (t.promise != null) {
            if (t.state == DONE) t.promise.complete(t.result); else t.promise.fail(t.failure);
        } else if (t.state == FAILED && b.failure == null) {
            b.failure = t.failure;                     // the body's failure is the perform's, rethrown on the entry thread
        }
        idle.addFirst(mine);                           // LIFO, so dispatch hands THIS carrier the next fresh task: no thread switch at all
        Object[] bodyChain = HandlerChainRegistry.installTaskChain(es, b.body.chain);   // the step's Mezze calls (drive) see the boundary's handlers
        try {
            dispatch(b, null, es);
        } finally {
            HandlerChainRegistry.restoreTaskChain(bodyChain);
            me.current = null;
        }
    }

    /**
     * {@code cont.park} on the running task's carrier: hand the baton back with the request
     * and block until the driver delivers the outcome. Called from the `cont-park` root.
     */
    @TruffleBoundary
    public Object park(ParkRequest request) {
        Task t = currentTask();
        if (t == null) return parkForeign(request);
        Boundary b = t.owner;
        if (b.abandoning) throw new BoundaryAbandoned();   // a task that swallowed its abandonment and parked again
        Task child = inlineChild(b, request);
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
        HandlerChainRegistry.EffectState es = t.carrier.es;
        boolean yield = isYield(request);
        if (!yield && request.on instanceof MezzeVariant on0 && on0.getTag() == tagOnPredicates()) {
            // A predicate that already holds: nothing to wait for, return without a dispatch, the way
            // a thread-per-task performer's park does. (A promise wait is not re-checked here: the
            // stdlib's `await` inspects the promise before it parks.) `yield_now` is the one park that
            // must always give the others a turn; it is handled below.
            int now = readyIndex(request);
            if (now >= 0) return parkReady(now);
        }
        t.parkedOn = request;
        t.turn = TURN_DRIVER;
        t.parkEpoch++;
        t.signalled = false;
        t.waitOn = false;
        if (yield) {                                   // ready by construction: straight behind the others, no parked-list round trip
            t.deliver = parkReady(0);
            t.state = RUNNABLE;
            b.ready.addLast(t);
        } else {
            t.state = PARKED;
            addParked(b, t, request);
            // The event may have landed between the pre-check and the registration (a foreign thread
            // settling the promise, a signal on the queue): nobody will signal again, so look once more.
            int nowReady = readyIndex(request);
            if (nowReady >= 0) {
                removeParked(b, t);
                t.state = RUNNING;
                t.parkedOn = null;
                return parkReady(nowReady);
            }
        }
        boolean sameChain = HandlerChainRegistry.installedTaskChain(es) == b.body.chain;
        Object[] prev = sameChain ? null : HandlerChainRegistry.installTaskChain(es, b.body.chain);
        Task next;
        try { next = dispatch(b, t, es); } finally { if (prev != null) HandlerChainRegistry.restoreTaskChain(prev); }
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
        MezzePromise p = promiseOf(seqAt(payload, 0));
        if (p.isDone() || !(p.machineTask instanceof Task c)) return null;
        if (c.state != RUNNABLE || c.carrier != null || c.thunk == null || b.ready.peekFirst() != c) return null;
        b.ready.pollFirst();
        return c;
    }

    /** Run {@code child} to completion (or until it parks and is later resumed on this same
     *  carrier) nested on {@code parent}'s carrier; settle its promise. No dispatch afterwards:
     *  the parent continues at once. */
    private void runInline(Task parent, Task child) {
        child.state = RUNNING;
        Carrier me = parent.carrier;
        child.carrier = me;                            // a nested park/resume uses this carrier's thread
        HandlerChainRegistry.EffectState es = me.es;
        me.current = child;
        Object[] prev = HandlerChainRegistry.installTaskChain(es, child.chain);
        Object prevScope = HandlerChainRegistry.installScopeVar(es, child.scope);
        try {
            child.result = callWith(child.thunk, MezzeRecord.empty());
            child.state = DONE;
        } catch (MachineClosed closed) {
            throw closed;
        } catch (Throwable e) {
            child.failure = e;
            child.state = FAILED;
        } finally {
            HandlerChainRegistry.restoreScopeVar(es, prevScope);
            HandlerChainRegistry.restoreTaskChain(prev);
            me.current = parent;
        }
        child.carrier = null;
        if (child.state == DONE) child.promise.complete(child.result); else child.promise.fail(child.failure);
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

    static final long PLAIN_SCAN_NANOS = 100_000L;

    /**
     * Wake what can be woken, in time proportional to what HAPPENED, not to what is parked: the
     * tasks a wait queue or a promise signalled, the deadlines that have passed, and — on the
     * amortised schedule — the plain predicates, which are polls.
     */
    private boolean wakeParked(Boundary b, HandlerChainRegistry.EffectState es) {
        boolean any = false;
        Task t;
        while ((t = b.signalled.poll()) != null) {
            if (t.state != PARKED || t.owner != b) continue;   // woke or re-parked since: stale
            t.signalled = false;
            if (tryWake(b, t, es)) any = true;
        }
        long now = System.nanoTime();
        while ((t = b.deadlines.peek()) != null && t.deadlineAt - now <= 0) {
            b.deadlines.poll();
            if (t.state != PARKED || t.owner != b || t.deadlineAt == 0) continue;   // stale entry
            if (tryWake(b, t, es)) any = true;
        }
        if (!b.plain.isEmpty() && (b.ready.isEmpty() || now - b.lastPlainScan >= Math.max(PLAIN_SCAN_NANOS, 10 * b.lastPlainScanCost))) {
            long scanStart = now;
            for (int i = 0; i < b.plain.size(); ) {
                Task pt = b.plain.get(i);
                if (tryWake(b, pt, es)) any = true; else i++;   // a wake swap-removes pt from the plain list
            }
            long end = System.nanoTime(); b.lastPlainScan = end; b.lastPlainScanCost = end - scanStart;
        }
        return any;
    }

    /** Re-check one parked task; deliver and unindex it when its wait holds. */
    private boolean tryWake(Boundary b, Task t, HandlerChainRegistry.EffectState es) {
        int which = needsChain(t.parkedOn) ? readyIndexUnderChain(t, es) : readyIndex(t.parkedOn);
        if (which < 0) return false;
        removeParked(b, t);
        deliver(b, t, parkReady(which));
        return true;
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
                if (promiseOf(seqAt(payload, i)).isDone()) return i;
            }
            return -1;
        }
        for (int i = 0; i < n; i++) {
            Object f = seqAt(payload, i);
            if (f == ContBinding.readyPredicate()) return i;    // `yield_now`: true by identity, no call
            Object r = callWith((MezzeFunction) f, MezzeRecord.empty());   // a wait_on predicate runs its test
            if (r instanceof Boolean b && b) return i;
        }
        return -1;
    }

    /** Only predicates run Mezze code and need the parker's handlers installed. */
    private static boolean needsChain(ParkRequest pr) {
        return pr.on instanceof MezzeVariant on && on.getTag() != tagImmediately() && on.getTag() != tagOnPromises() && !isYield(pr);
    }

    /** Index a parked task so every later event finds it without a scan: the master list, the plain
     *  list when it must be polled, the deadline heap when it has one, and the wait queues (a
     *  primitive's, or a promise's) that will signal it. */
    private static void addParked(Boundary b, Task t, ParkRequest pr) {
        t.parkedIndex = b.parked.size();
        b.parked.add(t);
        t.deadlineAt = deadlineOf(pr);
        if (t.deadlineAt != 0) b.deadlines.add(t);
        registerWaitQueues(t, pr);
        boolean opaqueHost = pr.on instanceof java.util.function.BooleanSupplier && !(pr.on instanceof QueueWait) && !(pr.on instanceof Deadline) && !(pr.on instanceof PromiseOrDeadline);
        if (hasPlainPredicate(pr) || opaqueHost) {
            t.plainIndex = b.plain.size();
            b.plain.add(t);
        }
    }

    private static long deadlineOf(ParkRequest pr) {
        if (pr.on instanceof Deadline d) return d.at;
        if (pr.on instanceof PromiseOrDeadline pd) return pd.at;
        if (pr.on instanceof QueueWait qw) return qw.at;
        return 0;
    }

    /** Take a task out of every index (O(1) each; its deadline-heap entry is skipped when met). */
    private static void removeParked(Boundary b, Task t) {
        int i = t.parkedIndex;
        if (i >= 0) {
            int last = b.parked.size() - 1;
            Task moved = b.parked.get(last);
            b.parked.set(i, moved); moved.parkedIndex = i;
            b.parked.remove(last);
            t.parkedIndex = -1;
        }
        int j = t.plainIndex;
        if (j >= 0) {
            int last = b.plain.size() - 1;
            Task moved = b.plain.get(last);
            b.plain.set(j, moved); moved.plainIndex = j;
            b.plain.remove(last);
            t.plainIndex = -1;
        }
        t.deadlineAt = 0;
    }

    /** Register the task with every wait queue (a primitive's or a promise's) that will signal it. */
    private static void registerWaitQueues(Task t, ParkRequest pr) {
        Object on = pr.on;
        if (on instanceof QueueWait qw) { qw.queue.register(t); t.waitOn = true; return; }
        if (on instanceof MezzePromise p) { p.taskWaiters().register(t); return; }
        if (on instanceof PromiseOrDeadline pd) { pd.promise.taskWaiters().register(t); return; }
        if (!(on instanceof MezzeVariant v)) return;
        if (v.getTag() == tagOnPromises()) {
            Object payload = v.getPayloadFieldByName("ids");
            int n = seqSize(payload);
            for (int i = 0; i < n; i++) promiseOf(seqAt(payload, i)).taskWaiters().register(t);
            return;
        }
        if (v.getTag() != tagOnPredicates()) return;
        Object payload = v.getPayloadFieldByName("fs");
        int n = seqSize(payload);
        for (int i = 0; i < n; i++) {
            WaitQueue q = ContBinding.queueOf(seqAt(payload, i));
            if (q != null) { q.register(t); t.waitOn = true; }
        }
    }

    /** True when the wait set can never be satisfied: no promise, no predicate, no deadline. */
    private static boolean unsatisfiable(ParkRequest pr) {
        if (!(pr.on instanceof MezzeVariant on)) return false;
        if (on.getTag() == tagOnPromises()) return seqSize(on.getPayloadFieldByName("ids")) == 0;
        if (on.getTag() == tagOnPredicates()) return seqSize(on.getPayloadFieldByName("fs")) == 0;
        return false;
    }

    /** A plain predicate park: a Mezze function the machine can only poll (no queue, not READY). */
    private static boolean hasPlainPredicate(ParkRequest pr) {
        if (!(pr.on instanceof MezzeVariant on) || on.getTag() != tagOnPredicates()) return false;
        Object payload = on.getPayloadFieldByName("fs");
        int n = seqSize(payload);
        for (int i = 0; i < n; i++) {
            Object f = seqAt(payload, i);
            if (f != ContBinding.readyPredicate() && ContBinding.queueOf(f) == null) return true;
        }
        return false;
    }

    /** A park that is ready at once by construction: `Immediately`, or the READY predicate alone. */
    private static boolean isYield(ParkRequest pr) {
        if (!(pr.on instanceof MezzeVariant on)) return false;
        if (on.getTag() == tagImmediately()) return true;
        if (on.getTag() != tagOnPredicates()) return false;
        Object payload = on.getPayloadFieldByName("fs");
        return seqSize(payload) == 1 && seqAt(payload, 0) == ContBinding.readyPredicate();
    }

    private int readyIndexUnderChain(Task t, HandlerChainRegistry.EffectState es) {
        if (HandlerChainRegistry.installedTaskChain(es) == t.chain) return readyIndex(t.parkedOn);   // already in place
        Object[] prev = HandlerChainRegistry.installTaskChain(es, t.chain);
        try { return readyIndex(t.parkedOn); } finally { HandlerChainRegistry.restoreTaskChain(prev); }
    }

    /**
     * Nothing is runnable. The ONLY state the machine reports as an error is a wait set that can
     * never be satisfied (no promise, no predicate, no deadline): that is provable. Everything
     * else is waited for, because in an open system a foreign thread, a signal or a settlement
     * can always arrive: promise waits and `wait_on` waits are woken by their event, deadlines by
     * time, and a PLAIN predicate — a Mezze function over state the machine cannot see — is
     * re-evaluated with exponential backoff (50 us .. 20 ms). The machine PRINTS NOTHING of its
     * own: a boundary that has made no progress for the performer's `on_stall.after` calls the
     * performer's `on_stall.f` with the parked tasks, under the perform site's handlers, so a
     * stuck program is visible through an effect the site declared, never guessed at.
     */
    private void blockOrDeadlock(Boundary b) {
        // Every wait but a plain predicate is event-driven: a promise settling signals its task waiters,
        // a primitive signals its queue, a deadline sits in the heap. The idle park below ends on any
        // signal (it wakes the idler), at the nearest deadline, or on the plain-predicate backoff. An
        // empty wait set is the one provable deadlock.
        boolean allUnsatisfiable = !b.parked.isEmpty();
        for (Task t : b.parked) if (!unsatisfiable(t.parkedOn)) { allUnsatisfiable = false; break; }
        boolean plain = !b.plain.isEmpty();
        Task soonest = b.deadlines.peek();
        long nearest = soonest != null ? soonest.deadlineAt : Long.MAX_VALUE;
        Thread me = Thread.currentThread();
        if (allUnsatisfiable) {
            StringBuilder sb = new StringBuilder("deadlock: ").append(b.parked.size())
                .append(b.parked.size() == 1 ? " task is parked" : " tasks are parked")
                .append(" on an empty wait set, which nothing can ever satisfy —");
            for (Task t : b.parked) sb.append(' ').append(t).append(" on ").append(describe(t.parkedOn));
            throw new MezzeGuestException(sb.toString());
        }
        b.idler = me;
        try {
            if (b.inbox.isEmpty() && !anySignalled(b)) {
                long wait;
                if (plain) {
                    wait = b.pollNanos;
                    b.pollNanos = Math.min(b.pollNanos * 2, 20_000_000L);
                } else {
                    wait = 1_000_000L;                 // the fallback for anything the hooks above missed
                }
                long now = System.nanoTime();
                if (b.idleSince == 0) b.idleSince = now;
                if (b.stallHook != null && !b.stallReported) {
                    // The stall report: no progress for `after`. The machine never prints; it calls
                    // the performer's `on_stall` function under the perform site's handlers, so what
                    // the report does — log, fail, complete a promise — is an effect the site declared.
                    // Once per stall; re-armed when any task runs again.
                    long stallAt = b.idleSince + b.stallAfterNanos;
                    if (now >= stallAt) { b.stallReported = true; fireStall(b); return; }
                    wait = Math.min(wait, stallAt - now);
                }
                if (nearest != Long.MAX_VALUE) wait = Math.max(1_000L, Math.min(wait, nearest - System.nanoTime()));
                LockSupport.parkNanos(this, wait);
            }
        } finally {
            b.idler = null;
        }
    }

    private static boolean anySignalled(Boundary b) {
        return !b.signalled.isEmpty();
    }

    private static final Object[] NO_STALL = {-1L, null};
    /** `CoopAsyncWith`'s `initialize_scope` hands its `on_stall` config here, on the thread about
     *  to open the boundary; `runBoundary` takes it. The handler record itself carries only the
     *  ops (config lives in their closures), so the performer, not the machine, reads the field. */
    private static final ThreadLocal<Object[]> PENDING_STALL = new ThreadLocal<>();

    /** `__native_coop_on_stall { on_stall }`: `None` → no report; `Some { val = { after, f } }` → the
     *  report `f { waiting }` after `after` of no progress. Anything else is a stdlib bug. */
    @TruffleBoundary
    public static void registerPendingStall(Object onStall) {
        if (!(onStall instanceof MezzeVariant v)) {
            throw new IllegalStateException("on_stall must be an Option, got " + (onStall == null ? "null" : onStall.getClass().getName()));
        }
        if (!"Some".equals(v.getConstructorName())) { PENDING_STALL.remove(); return; }
        Object payload = v.getPayloadFieldByName(com.mezze.language.ast.ScopedEffectConstants.VAL_FIELD);
        if (!(payload instanceof MezzeRecord r)) throw new IllegalStateException("on_stall payload is not a record: " + payload);
        Object after = r.getField(com.mezze.language.ast.ScopedEffectConstants.STALL_AFTER);
        Object f = r.getField(com.mezze.language.ast.ScopedEffectConstants.STALL_F);
        if (!(after instanceof com.mezze.interpreter.nodes.builtin.io.DateTimeNatives.MezzeDuration d) || !(f instanceof MezzeFunction fn)) {
            throw new IllegalStateException("on_stall needs { after: Duration, f }, got after=" + after + " f=" + f);
        }
        PENDING_STALL.set(new Object[]{Math.max(0L, d.duration.toNanos()), fn});
    }

    /** Call the stall report with every parked task, under the boundary's own handlers. A throw
     *  (a `fail` in the report) is the machine step failing: the boundary's failure, like a deadlock. */
    @TruffleBoundary
    private void fireStall(Boundary b) {
        Object[] waiting = new Object[b.parked.size()];
        String[] names = {com.mezze.language.ast.ScopedEffectConstants.STALL_TASK_ID, com.mezze.language.ast.ScopedEffectConstants.STALL_TASK_ON};
        for (int i = 0; i < waiting.length; i++) {
            Task t = b.parked.get(i);
            waiting[i] = MezzeRecord.fromFields(names, new Object[]{t.id, t + " on " + describe(t.parkedOn)});
        }
        HandlerChainRegistry.EffectState es = HandlerChainRegistry.state();
        var prev = HandlerChainRegistry.installTaskChain(es, b.body.chain);
        var prevScope = HandlerChainRegistry.installScopeVar(es, b.body.scope);
        try {
            callWith(b.stallHook, MezzeRecord.fromFields(
                new String[]{com.mezze.language.ast.ScopedEffectConstants.STALL_WAITING},
                new Object[]{com.mezze.datastructures.seq.MezzeSeq.fromArray(waiting)}));
        } finally {
            HandlerChainRegistry.installScopeVar(es, prevScope);
            HandlerChainRegistry.restoreTaskChain(prev);
        }
    }

    /** A host-level promise await WITH a deadline: woken by the settlement or at the deadline. */
    public static final class PromiseOrDeadline implements java.util.function.BooleanSupplier {
        final MezzePromise promise; final long at;
        public PromiseOrDeadline(MezzePromise promise, long at) { this.promise = promise; this.at = at; }
        @Override public boolean getAsBoolean() { return promise.isDone() || System.nanoTime() - at >= 0; }
    }

    private static String describe(ParkRequest pr) {
        if (pr.on instanceof MezzePromise) return "a promise (host-level await)";
        if (pr.on instanceof PromiseOrDeadline) return "a promise with a deadline (host-level await)";
        if (pr.on instanceof QueueWait) return "a synchronisation primitive (host-level wait)";
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

    private static Object callWithImpl(MezzeFunction fn, MezzeRecord arg) {
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
    private static volatile int TAG_ON_PREDICATES = -1;
    private static int tagOnPredicates() { int t = TAG_ON_PREDICATES; if (t < 0) TAG_ON_PREDICATES = t = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("OnPredicates"); return t; }
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
