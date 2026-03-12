package com.mezze.datastructures.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance object pool for promises.
 *
 * Reduces GC pressure in high-throughput async code by reusing
 * promise objects instead of creating new ones.
 *
 * Features:
 * - Lock-free using ConcurrentLinkedQueue
 * - Bounded size to prevent memory bloat
 * - Automatic clearing of promise state on return
 */
public final class PromisePool<T> {

    private final ConcurrentLinkedQueue<T> pool;
    private final AtomicInteger size;
    private final int maxSize;
    private final PromiseFactory<T> factory;
    private final PromiseResetter<T> resetter;

    @FunctionalInterface
    public interface PromiseFactory<T> {
        T create();
    }

    @FunctionalInterface
    public interface PromiseResetter<T> {
        void reset(T promise);
    }

    public PromisePool(int maxSize, PromiseFactory<T> factory, PromiseResetter<T> resetter) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.size = new AtomicInteger(0);
        this.maxSize = maxSize;
        this.factory = factory;
        this.resetter = resetter;
    }

    /**
     * Acquire a promise from the pool, or create a new one if empty.
     */
    public T acquire() {
        T promise = pool.poll();
        if (promise != null) {
            size.decrementAndGet();
            return promise;
        }
        return factory.create();
    }

    /**
     * Return a promise to the pool for reuse.
     * Resets the promise state before returning.
     */
    public void release(T promise) {
        if (size.get() < maxSize) {
            resetter.reset(promise);
            pool.offer(promise);
            size.incrementAndGet();
        }
        // If pool is full, let GC collect it
    }

    /**
     * Get current pool size.
     */
    public int size() {
        return size.get();
    }

    /**
     * Clear the pool.
     */
    public void clear() {
        pool.clear();
        size.set(0);
    }
}
