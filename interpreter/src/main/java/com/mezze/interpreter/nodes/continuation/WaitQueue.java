package com.mezze.interpreter.nodes.continuation;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The wake-up list of one synchronisation primitive (a channel, a mutex, a semaphore, a latch,
 * a wait group, the STM). A cooperative task that waits on the primitive registers here with
 * its park epoch; the primitive's natives call {@link #signal()} on every state change, FROM ANY
 * THREAD, and the machine then re-evaluates only the tasks that were signalled — a wake is O(1)
 * per waiter, event-driven, and never a poll. Stale entries (a task that woke or re-parked
 * elsewhere) are dropped as they are met.
 */
public final class WaitQueue {
    private static final class Entry {
        final TaskMachine.Task task; final long epoch;
        Entry(TaskMachine.Task task, long epoch) { this.task = task; this.epoch = epoch; }
    }
    private final ConcurrentLinkedQueue<Entry> waiters = new ConcurrentLinkedQueue<>();

    void register(TaskMachine.Task t) { waiters.add(new Entry(t, t.parkEpoch)); }

    /** The primitive changed state: every current waiter is told to re-check. */
    public void signal() {
        for (Iterator<Entry> it = waiters.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            TaskMachine.Task t = e.task;
            if (t.parkEpoch != e.epoch || t.state != TaskMachine.PARKED) { it.remove(); continue; }
            t.signalled = true;
            TaskMachine.poke(t);
        }
    }

    public boolean isEmpty() { return waiters.isEmpty(); }
    @Override public String toString() { return "<wait-queue>"; }
}
