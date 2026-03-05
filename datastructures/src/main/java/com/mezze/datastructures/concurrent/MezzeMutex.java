package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reentrant mutual exclusion lock.
 *
 * Wraps Java's ReentrantLock with a Truffle-friendly interface.
 *
 * Thread-safe: Yes
 * Reentrant: Yes (same thread can lock multiple times)
 */
public final class MezzeMutex implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final ReentrantLock lock;

    /**
     * Creates a new mutex (non-fair).
     */
    public MezzeMutex() {
        this(false);
    }

    /**
     * Creates a new mutex with the given fairness policy.
     * @param fair true for fair ordering (FIFO), false for non-fair
     */
    public MezzeMutex(boolean fair) {
        this.id = NEXT_ID.getAndIncrement();
        this.lock = new ReentrantLock(fair);
    }

    /**
     * Returns the unique ID of this mutex.
     */
    public long getId() {
        return id;
    }

    /**
     * Acquires the lock, blocking if necessary.
     */
    @TruffleBoundary
    public void lock() {
        lock.lock();
    }

    /**
     * Releases the lock.
     */
    @TruffleBoundary
    public void unlock() {
        lock.unlock();
    }

    /**
     * Tries to acquire the lock without blocking.
     * @return true if lock acquired, false otherwise
     */
    @TruffleBoundary
    public boolean tryLock() {
        return lock.tryLock();
    }

    /**
     * Tries to acquire the lock with timeout.
     * @return true if lock acquired, false if timeout
     */
    @TruffleBoundary
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return lock.tryLock(timeout, unit);
    }

    /**
     * Returns true if the current thread holds this lock.
     */
    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /**
     * Returns true if any thread holds this lock.
     */
    public boolean isLocked() {
        return lock.isLocked();
    }

    /**
     * Returns the hold count for the current thread.
     */
    public int getHoldCount() {
        return lock.getHoldCount();
    }

    /**
     * Returns true if this is a fair mutex.
     */
    public boolean isFair() {
        return lock.isFair();
    }

    /**
     * Returns the number of threads waiting for this lock.
     */
    public int getQueueLength() {
        return lock.getQueueLength();
    }

    /**
     * Executes a runnable while holding the lock.
     */
    @TruffleBoundary
    public void withLock(Runnable action) {
        lock();
        try {
            action.run();
        } finally {
            unlock();
        }
    }

    /**
     * Executes a supplier while holding the lock.
     */
    @TruffleBoundary
    public <T> T withLock(java.util.function.Supplier<T> action) {
        lock();
        try {
            return action.get();
        } finally {
            unlock();
        }
    }

    @Override
    public String toString() {
        String status = lock.isLocked() ? "locked" : "unlocked";
        return "<mutex:" + id + ":" + status + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeMutex other)) return false;
        return this.id == other.id;
    }
}
