package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wait group for dynamic counter synchronization.
 *
 * Unlike a latch, a WaitGroup can be incremented dynamically.
 * Useful for waiting on a dynamic set of tasks.
 *
 * Thread-safe: Yes
 */
public final class MezzeWaitGroup implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final AtomicInteger count;
    private final ReentrantLock lock;
    private final Condition done;

    /**
     * Creates a wait group with count zero.
     */
    public MezzeWaitGroup() {
        this.id = NEXT_ID.getAndIncrement();
        this.count = new AtomicInteger(0);
        this.lock = new ReentrantLock();
        this.done = lock.newCondition();
    }

    /**
     * Returns the unique ID of this wait group.
     */
    public long getId() {
        return id;
    }

    /**
     * Adds delta to the counter.
     * @param delta positive to add tasks, negative when tasks complete
     */
    @TruffleBoundary
    public void add(int delta) {
        int newCount = count.addAndGet(delta);
        if (newCount < 0) {
            throw new IllegalStateException("negative WaitGroup counter");
        }
        if (newCount == 0) {
            lock.lock();
            try {
                done.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Decrements the counter by one.
     * Equivalent to add(-1).
     */
    @TruffleBoundary
    public void done() {
        add(-1);
    }

    /**
     * Waits until the counter reaches zero.
     */
    @TruffleBoundary
    public void await() throws InterruptedException {
        lock.lock();
        try {
            while (count.get() > 0) {
                done.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the counter reaches zero or timeout.
     * @return true if counter reached zero, false if timeout
     */
    @TruffleBoundary
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count.get() > 0) {
                if (nanos <= 0) return false;
                nanos = done.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current count.
     */
    public int getCount() {
        return count.get();
    }

    /**
     * Returns true if the count is zero.
     */
    public boolean isDone() {
        return count.get() == 0;
    }

    @Override
    public String toString() {
        return "<waitgroup:" + id + ":" + count.get() + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeWaitGroup other)) return false;
        return this.id == other.id;
    }
}
