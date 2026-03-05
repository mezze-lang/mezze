package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counting semaphore for resource limiting.
 *
 * Allows a fixed number of threads to access a resource concurrently.
 *
 * Thread-safe: Yes
 */
public final class MezzeSemaphore implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final Semaphore semaphore;
    private final int initialPermits;

    /**
     * Creates a semaphore with the given number of permits.
     */
    public MezzeSemaphore(int permits) {
        this(permits, false);
    }

    /**
     * Creates a semaphore with the given permits and fairness policy.
     */
    public MezzeSemaphore(int permits, boolean fair) {
        this.id = NEXT_ID.getAndIncrement();
        this.initialPermits = permits;
        this.semaphore = new Semaphore(permits, fair);
    }

    /**
     * Returns the unique ID of this semaphore.
     */
    public long getId() {
        return id;
    }

    /**
     * Returns the initial number of permits.
     */
    public int getInitialPermits() {
        return initialPermits;
    }

    /**
     * Acquires a permit, blocking if none available.
     */
    @TruffleBoundary
    public void acquire() throws InterruptedException {
        semaphore.acquire();
    }

    /**
     * Acquires multiple permits, blocking if not enough available.
     */
    @TruffleBoundary
    public void acquire(int permits) throws InterruptedException {
        semaphore.acquire(permits);
    }

    /**
     * Acquires a permit, ignoring interruption.
     */
    @TruffleBoundary
    public void acquireUninterruptibly() {
        semaphore.acquireUninterruptibly();
    }

    /**
     * Releases a permit.
     */
    @TruffleBoundary
    public void release() {
        semaphore.release();
    }

    /**
     * Releases multiple permits.
     */
    @TruffleBoundary
    public void release(int permits) {
        semaphore.release(permits);
    }

    /**
     * Tries to acquire a permit without blocking.
     */
    @TruffleBoundary
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    /**
     * Tries to acquire a permit with timeout.
     */
    @TruffleBoundary
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return semaphore.tryAcquire(timeout, unit);
    }

    /**
     * Tries to acquire multiple permits with timeout.
     */
    @TruffleBoundary
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        return semaphore.tryAcquire(permits, timeout, unit);
    }

    /**
     * Returns the current number of available permits.
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * Returns true if this is a fair semaphore.
     */
    public boolean isFair() {
        return semaphore.isFair();
    }

    /**
     * Returns the number of threads waiting for permits.
     */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    /**
     * Drains all available permits.
     * @return the number of permits drained
     */
    @TruffleBoundary
    public int drainPermits() {
        return semaphore.drainPermits();
    }

    /**
     * Executes an action while holding a permit.
     */
    @TruffleBoundary
    public void withPermit(Runnable action) throws InterruptedException {
        acquire();
        try {
            action.run();
        } finally {
            release();
        }
    }

    /**
     * Executes a supplier while holding a permit.
     */
    @TruffleBoundary
    public <T> T withPermit(java.util.function.Supplier<T> action) throws InterruptedException {
        acquire();
        try {
            return action.get();
        } finally {
            release();
        }
    }

    @Override
    public String toString() {
        return "<semaphore:" + id + ":" + semaphore.availablePermits() + "/" + initialPermits + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSemaphore other)) return false;
        return this.id == other.id;
    }
}
