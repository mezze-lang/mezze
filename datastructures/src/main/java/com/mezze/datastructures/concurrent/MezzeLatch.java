package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Count-down latch for one-shot synchronization.
 *
 * A latch starts with a count and releases waiting threads when
 * the count reaches zero. Cannot be reset (use MezzeWaitGroup for that).
 *
 * Thread-safe: Yes
 */
public final class MezzeLatch implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final CountDownLatch latch;
    private final int initialCount;

    /**
     * Creates a latch with the given count.
     */
    public MezzeLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        this.id = NEXT_ID.getAndIncrement();
        this.initialCount = count;
        this.latch = new CountDownLatch(count);
    }

    /**
     * Returns the unique ID of this latch.
     */
    public long getId() {
        return id;
    }

    /**
     * Returns the initial count.
     */
    public int getInitialCount() {
        return initialCount;
    }

    /**
     * Decrements the count by one.
     */
    @TruffleBoundary
    public void countDown() {
        latch.countDown();
    }

    /**
     * Waits until the count reaches zero.
     */
    @TruffleBoundary
    public void await() throws InterruptedException {
        latch.await();
    }

    /**
     * Waits until the count reaches zero or timeout.
     * @return true if count reached zero, false if timeout
     */
    @TruffleBoundary
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return latch.await(timeout, unit);
    }

    /**
     * Returns the current count.
     */
    public long getCount() {
        return latch.getCount();
    }

    /**
     * Returns true if the count has reached zero.
     */
    public boolean isDone() {
        return latch.getCount() == 0;
    }

    @Override
    public String toString() {
        return "<latch:" + id + ":" + latch.getCount() + "/" + initialCount + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeLatch other)) return false;
        return this.id == other.id;
    }
}
