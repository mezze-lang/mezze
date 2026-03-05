package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Transactional Variable for Software Transactional Memory (STM).
 *
 * TVars are mutable references that can only be modified within STM transactions.
 * They provide:
 * - Multi-version concurrency control (MVCC)
 * - Optimistic conflict detection
 * - Automatic retry on conflicts
 *
 * Thread-safe: Yes
 * Lock-free reads: Yes
 * Lock-free writes: No (requires commit lock)
 */
public final class MezzeTVar implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    @CompilationFinal
    private final long id;

    private final AtomicReference<VersionedValue> versionedValue;
    private final ConcurrentLinkedQueue<Thread> waiters;

    /**
     * A versioned value for MVCC.
     */
    public record VersionedValue(long version, Object value) {}

    /**
     * Creates a new TVar with the given initial value.
     */
    public MezzeTVar(Object initialValue) {
        this.id = NEXT_ID.getAndIncrement();
        this.versionedValue = new AtomicReference<>(new VersionedValue(0L, initialValue));
        this.waiters = new ConcurrentLinkedQueue<>();
    }

    /**
     * Returns the unique ID of this TVar.
     */
    public long getId() {
        return id;
    }

    /**
     * Reads the current value without version info.
     * For use outside transactions or for simple deref.
     */
    @TruffleBoundary
    public Object read() {
        return versionedValue.get().value();
    }

    /**
     * Reads the current versioned value.
     * For use by STM transactions.
     */
    @TruffleBoundary
    public VersionedValue readVersioned() {
        return versionedValue.get();
    }

    /**
     * Force-writes a new value with version.
     * Called by transaction commit.
     */
    @TruffleBoundary
    public void forceWrite(long version, Object value) {
        versionedValue.set(new VersionedValue(version, value));
        wakeWaiters();
    }

    /**
     * Compare-and-set for the versioned value.
     * Returns true if successful.
     */
    @TruffleBoundary
    public boolean compareAndSet(VersionedValue expected, VersionedValue newValue) {
        boolean success = versionedValue.compareAndSet(expected, newValue);
        if (success) {
            wakeWaiters();
        }
        return success;
    }

    private void wakeWaiters() {
        Thread waiter;
        while ((waiter = waiters.poll()) != null) {
            LockSupport.unpark(waiter);
        }
    }

    /**
     * Adds a thread to wait for changes to this TVar.
     * Used for STM retry/orElse.
     */
    @TruffleBoundary
    public void addWaiter(Thread t) {
        waiters.add(t);
    }

    /**
     * Removes a waiting thread.
     */
    @TruffleBoundary
    public void removeWaiter(Thread t) {
        waiters.remove(t);
    }

    @Override
    public String toString() {
        return "<tvar:" + id + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeTVar other)) return false;
        return this.id == other.id;
    }
}
