package com.mezze.interpreter.nodes.continuation;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The wake-up list of one synchronisation primitive (a channel, a mutex, a semaphore, a latch,
 * a wait group, the STM). A cooperative task that waits on the primitive registers here with
 * its park epoch, a thread-per-task waiter with its thread; the primitive's natives call {@link #signal()} on every state change, FROM ANY
 * THREAD, and the machine then re-evaluates only the tasks that were signalled — a wake is O(1)
 * per waiter, event-driven, and never a poll. Stale entries (a task that woke or re-parked
 * elsewhere) are dropped as they are met.
 */
public final class WaitQueue {
    /** A cooperative task (with the park epoch it registered under), or a thread-per-task waiter. */
    private static final class Entry {
        final TaskMachine.Task task; final long epoch; final Thread thread;
        Entry(TaskMachine.Task task, long epoch) { this.task = task; this.epoch = epoch; this.thread = null; }
        Entry(Thread thread) { this.task = null; this.epoch = 0L; this.thread = thread; }
    }
    private final ConcurrentLinkedQueue<Entry> waiters = new ConcurrentLinkedQueue<>();

    void register(TaskMachine.Task t) { waiters.add(new Entry(t, t.parkEpoch)); }

    /**
     * A thread-per-task performer's waiter (LoomAsync, OSThreadAsync): the thread is unparked
     * on the next signal, then re-checks. One registration covers one signal; the waiter
     * registers again before each re-check, so a signal between check and park is never
     * lost (the unpark permit outlives it). Returns a token to {@link #deregister} on success.
     */
    public Object registerThread(Thread t) { Entry e = new Entry(t); waiters.add(e); return e; }

    /** Drop a thread registration whose wait already succeeded, so it does not linger. */
    public void deregister(Object token) { if (token instanceof Entry e) waiters.remove(e); }

    /** The primitive changed state: every current waiter is told to re-check. */
    public void signal() {
        for (Iterator<Entry> it = waiters.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.thread != null) { it.remove(); java.util.concurrent.locks.LockSupport.unpark(e.thread); continue; }
            TaskMachine.Task t = e.task;
            if (t.parkEpoch != e.epoch || t.state != TaskMachine.PARKED) { it.remove(); continue; }
            TaskMachine.signalled(t);                  // onto the boundary's signalled queue; the next step finds it in O(1)
        }
    }

    public boolean isEmpty() { return waiters.isEmpty(); }
    @Override public String toString() { return "<wait-queue>"; }
}
